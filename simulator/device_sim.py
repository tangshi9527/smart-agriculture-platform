#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
智慧农业 - 设备模拟器（物理仿真版，零依赖）

信号链路（每 2s 一拍）:

    室外气象 ─┐
              ├→ [温室气候真值] ─→ [执行器仲裁] ─→ [传感器非理想化] ─→ [故障状态机] ─→ MQTT
    云端指令 ─┘   能量/水汽/CO2     手动优先/超时回落   滞后·偏置·漂移·噪声   卡值·漂移·尖峰

旧版（v1）的 make_telemetry() 是 6 个独立 random.uniform —— 那是白噪声，不是信号。
实测旧版的病：相邻两拍温差中位数 4.72℃、温度与湿度相关系数 0.0002、
同一大棚 12 台设备极差 15.4℃、0–6 点光照 P50 达 49,815 lux、
soilMoisture 恒定落在 [30,80] 导致「<25」告警 0/43200 命中（规则永不触发）、
「temp>24 且 humidity>60」命中 37.6% 导致告警每 5 秒响一次且永不恢复。

本版把链路补全：

  1. 时间连续性   所有状态量走一阶指数松弛（无条件稳定，dt 多大都不发散）
  2. 物理耦合     温度↑→饱和含湿量↑→RH↓；通风把温湿度同时拉向室外
  3. 昼夜节律     太阳高度角驱动光照/温度/光合/蒸腾；夜间呼吸累积 CO2
  4. 同棚一致     默认 4 棚 × 50 测点，棚内共享真值只差传感器噪声，棚间有朝向差异
  5. 真闭环       云端 FAN_ON 会把 vent 真拉满，温度曲线当场拐头；手动保持 10 分钟
  6. 告警可达     参数按既有告警规则反推标定（>35℃ / 土壤<25% / 闷热组合持续 8s）
  7. 故障有生命周期 每种故障有持续时长与恢复过程，不是每帧独立掷骰子
  8. 可复现       每设备独立随机种子，同一命令两次运行结果逐字节一致

    依赖: 仅 Python 3 标准库（socket / struct / threading / select / random / json / time / math）
      无需 pip install，任何 Python 3.6+ 可直接运行。

    ⚠️ 运行强度与机器温度:
      默认 200 设备 / 200 连接，足以撑起大屏演示且 CPU/内存占用很低。
      1000 连接属于压力测试档，会持续拉高 CPU、使风扇长时间满转，
      散热不佳的机器可能过热停机。如确需大规模，请分批启动并监控机器温度。

用法:
  python device_sim.py                          # 200 设备 / 200 连接，真实物理
  python device_sim.py --dry-run --sim-hours 24 # 不连网，跑 24 仿真小时并打印统计（验参数用）
  python device_sim.py --scenario summer        # 盛夏场景（更容易打出高温告警）
  python device_sim.py --sim-speed 60           # 60 倍时间加速（24 分钟看完一天）
  python device_sim.py --devices 500 --connections 1000 --connect-pace-ms 5   # 压力档（关注温度）
"""
import argparse
import json
import math
import os
import random
import select
import signal
import socket
import struct
import sys
import threading
import time

LOG_LOCK = threading.Lock()


def log_info(msg):
    with LOG_LOCK:
        print("[device_sim] %s" % msg, flush=True)


# ===========================================================================
# 0. 物理常量与湿空气工具函数
# ===========================================================================
RHO_CP = 1.2 * 1005.0        # 空气体积热容 J/(m3·K)
L_V = 2.45e6                 # 汽化潜热 J/kg
PPFD_PER_WM2 = 2.02          # 日光: 1 W/m2 全波段 ≈ 2.02 μmol/(m2·s) PPFD
LUX_PER_PPFD = 54.0          # 日光: 1 μmol/(m2·s) ≈ 54 lux
MOLAR_VOL = 0.0416           # 25℃ 1 atm 下一摩尔气体体积 m3/mol（CO2 ppm 换算用）


def sat_vapor_pressure_hpa(t):
    """饱和水汽压 hPa（Magnus 公式，-45~60℃ 误差 <0.3%）。"""
    return 6.112 * math.exp(17.62 * t / (243.12 + t))


def sat_abs_humidity(t):
    """饱和绝对含湿量 g/m3。20℃→17.3，30℃→30.4，与湿空气表一致。"""
    return 216.7 * sat_vapor_pressure_hpa(t) / (t + 273.15)


def rh_to_abs_humidity(t, rh):
    return sat_abs_humidity(t) * max(0.0, min(100.0, rh)) / 100.0


def abs_humidity_to_rh(t, chi):
    sat = sat_abs_humidity(t)
    return 100.0 * chi / sat if sat > 1e-6 else 0.0


def vpd_kpa(t, rh):
    """水汽压亏缺 kPa：蒸腾的直接驱动力。"""
    return sat_vapor_pressure_hpa(t) * (1.0 - max(0.0, min(100.0, rh)) / 100.0) / 10.0


def relax(current, target, dt, tau):
    """一阶指数松弛：dX/dt = (target-X)/tau 的解析解。

    相比显式欧拉，它对任意 dt/tau 都无条件稳定（dt>>tau 时直接落到 target），
    所以时间加速场景不需要额外缩小步长也不会发散。
    """
    if tau <= 1e-9:
        return target
    return target + (current - target) * math.exp(-dt / tau)


# ===========================================================================
# 1. 配置
# ===========================================================================
# 记法: 所有量纲写在注释里。改这里即可适配任意大棚，不用改代码。
CFG = dict(
    # ---------------- 温室结构（30m × 10m 单栋玻璃棚）----------------
    gh=dict(
        a_floor=300.0,          # 地面面积 m2
        height=3.5,             # 平均高度 m
        a_cover=580.0,          # 覆盖面积 m2（屋顶 300 + 侧墙 (30+10)×2×3.5）
        c_thermal=2.5e7,        # 总热容 J/K（空气 1.27e6 + 结构/土壤表层耦合，文献 10~30 min 时间常数）
        u_cover=6.0,            # 覆盖层传热系数 W/(m2·K)（单层玻璃 5.8）
        k_soil=1500.0,          # 土壤-空气换热 W/K（300m2 × 5 W/(m2·K)）
        tau_soil=8 * 3600.0,    # 土壤热时间常数 s（8h，夜间放热让室内比室外高 1~2℃）
        vent_max_ach=60.0,      # 天窗+侧窗全开换气次数 次/h（大型温室 40~60）
        infil_ach=0.5,          # 关闭状态渗透换气 次/h
        p_heater=50000.0,       # 加热管功率 W（≈167 W/m2，典型 100~200）
        p_pad=30000.0,          # 湿帘蒸发降温等效冷量 W
        p_lamp=18000.0,         # 补光灯电功率 W，全部转热（LED 150 μmol 约 60 W/m2 × 300 m2）
        ppfd_lamp=150.0,        # 补光灯在冠层的 PPFD μmol/(m2·s)
        tau_cover=0.65,         # 覆盖层太阳透过率
        shade_max=0.30,         # 遮阳网遮光率 0~1（夏季外遮阳典型 50~70%，这里留出午后短暂超温余量）
    ),

    # ---------------- 作物（影响蒸腾与光合，决定湿度与 CO2 走势）----------------
    crop=dict(
        lai=3.0,                # 叶面积指数
        e_max=0.035,            # 最大蒸腾 kg/s（≈126 L/h，日光时段折合 3.4 mm/day）
        p_max=0.0146,           # 最大净光合 mol/s（C=420ppm 时实际约 0.0075 mol/s）
        r_resp=2.1e-4,          # 呼吸 mol/s（夜间 0.017 ppm/s → 每小时 +62 ppm）
        co2_comp=50.0,          # CO2 补偿点 ppm
        co2_kc=300.0,           # 光合 Michaelis 半饱和常数 ppm
        co2_inject=0.0158,      # 施气速率 mol/s（≈1.5 kg/h，8 分钟从 420 到 800）
        init_soil=0.235,        # 初始体积含水率（明显低于 25% 阈值，开局触发一次低土壤告警后自动灌溉恢复）
        vwc_fc=0.32,            # 田间持水量（体积含水率）
        vwc_wp=0.12,            # 凋萎点
        vwc_min=0.15,           # 传感器量程下限
        vwc_max=0.45,           # 传感器量程上限（>0.45 超过孔隙度，物理不可能）
        root_depth=0.20,        # 根区深度 m（浅根蔬菜；决定 1% VWC 对应多少升水）
        irr_rate=900.0,         # 灌溉流量 L/h（滴灌 3 L/(m2·h)，补水约 1 小时可抵消日耗）
        k_drain=0.012,          # 超田间持水量后的排水系数 1/h
    ),

    # ---------------- 室外气象基线（--base-temp/--temp-amplitude 会覆盖）----------------
    env=dict(
        t_mean=24.0,            # 日均气温 ℃
        t_amp=8.0,              # 日较差半幅 ℃
        rh_night=82.0,          # 夜间相对湿度 %
        rh_day=52.0,            # 午后相对湿度 %
        ppfd_clear=1800.0,      # 晴天正午 PPFD μmol/(m2·s)（≈890 W/m2）
        co2_out=420.0,          # 室外 CO2 ppm
        cloud_tau=1800.0,       # 云量自相关时间常数 s（30 min，避免太阳辐射逐拍白噪声抖动）
        cloud_sigma=0.35,       # 云量 OU 过程强度
    ),

    # ---------------- 自动控制设定点 ----------------
    ctrl=dict(
        t_heat_on=20.0,         # 低于此值加热
        t_heat_off=22.0,        # 高于此值停加热（滞回，防震荡）
        t_vent_on=26.0,         # 高于此值开始开窗
        t_vent_full=34.0,       # 到此处开满
        rh_vent_on=85.0,        # 高于此值强制开窗除湿
        t_pad_on=32.0,          # 高于此值开湿帘
        rh_pad_limit=80.0,      # 湿度高于此值不开湿帘（否则越降越湿）
        co2_set=800.0,          # CO2 施气目标 ppm
        co2_deadband=50.0,      # 施气滞回
        ppfd_lamp_on=250.0,     # 冠层 PPFD 低于此值补光
        vwc_irr_on=0.27,        # 土壤低于此值灌溉
        vwc_irr_off=0.30,       # 到此处停灌
        manual_hold_s=600.0,    # 云端指令手动保持时长 s，超时交还自动
    ),

    # ---------------- 传感器非理想化（每台设备独立抽样）----------------
    sens=dict(
        tau_t=60.0,             # 温度探头时间常数 s（带防辐射罩）
        tau_rh=30.0,            # 湿度探头 s
        tau_co2=120.0,          # NDIR 采样腔 s
        tau_soil=300.0,         # 土壤水分扩散 s
        rad_err=1.5,            # 辐射误差 ℃ at 1000 W/m2（防辐射罩下 0.5~2）
        k_trh=0.05,             # RH 对自身温度的交叉敏感 %/℃
        co2_tcoef=5e-4,         # NDIR 温补残差 1/℃
        par_tilt=0.2,           # PAR 探头安装角偏差（余弦损失）
        par_occl_base=0.92,     # 常态积灰遮挡（长期缓慢下降）
        sigma_t=0.08,           # 温度噪声 ℃
        sigma_rh=0.5,           # 湿度噪声 %
        sigma_co2=8.0,          # CO2 噪声 ppm
        sigma_par=5.0,          # PAR 噪声 μmol
        sigma_soil=0.3,         # 土壤水分噪声 %
        res_t=0.01,             # 温度分辨率 ℃
        res_rh=0.1,             # 湿度分辨率 %
        res_co2=1.0,            # CO2 分辨率 ppm
        res_par=1.0,            # PAR 分辨率 μmol
        res_soil=0.1,           # 土壤水分分辨率 %
        drift_rh_per_day=0.05,  # 湿度年漂移折算到每天 %
        drift_t_per_day=0.01,   # 温度漂移 ℃/天
        drift_reset_s=7 * 86400.0,  # 校准周期 s（漂移回零，模拟人工标定）
    ),

    # ---------------- 故障注入（每小时发生次数，乘 --fault-rate 倍率）----------------
    fault=dict(
        per_hour_stuck=0.020,   # 卡值：输出冻结
        per_hour_drift=0.010,   # 漂移：偏置缓慢拉大再恢复
        per_hour_spike=0.300,   # 单点尖峰
        per_hour_occlude=0.030, # PAR 遮挡
        per_hour_offline=0.005, # 设备离线
        p_drop=0.010,           # 链路整帧丢包（每帧）
        dur_stuck=(180, 900),   # 卡值持续 s
        dur_drift=(1800, 7200), # 漂移持续 s
        dur_occlude=(600, 3600),
        dur_offline=(60, 600),
        amp_drift=5.0,          # 漂移最大偏置 ℃
        amp_spike=(2.0, 6.0),   # 尖峰幅度 ℃（EMI 级，探头热惯性会削掉更大的突变）
        condense_rh=97.0,       # 结露阈值 %（RH 触此值后传感器贴 100 并迟滞）
    ),

    # ---------------- 仿真引擎 ----------------
    sim=dict(
        zones=4,                # 大棚数量（设备均分，棚间有朝向/覆盖率差异）
        phys_dt_max=30.0,       # 物理子步上限 s（时间加速时自动细分）
        time_scale=1.0,         # 仿真时间/真实时间
        start_hour=6.0,         # 仿真起始时刻（6 点开局，能很快看到日出生温）
    ),
)

# 场景预设：只覆盖 env / gh.shade_max / crop.vwc 初值，其余走默认
SCENARIOS = {
    "summer": dict(env=dict(t_mean=27.0, t_amp=7.0, rh_day=44.0, ppfd_clear=2100.0)),
    "mild":   dict(env=dict(t_mean=21.0, t_amp=6.0, rh_day=58.0, ppfd_clear=1500.0)),
    "winter": dict(env=dict(t_mean=9.0, t_amp=5.0, rh_day=65.0, ppfd_clear=900.0)),
}


def build_cfg(base_temp=None, temp_amp=None, scenario=None, zones=None, init_soil=None):
    """深拷贝默认配置并按场景/命令行覆盖。"""
    import copy
    cfg = copy.deepcopy(CFG)
    if scenario and scenario in SCENARIOS:
        for section, kv in SCENARIOS[scenario].items():
            cfg[section].update(kv)
    if base_temp is not None:
        cfg["env"]["t_mean"] = float(base_temp)
    if temp_amp is not None:
        cfg["env"]["t_amp"] = float(temp_amp)
    if zones is not None:
        cfg["sim"]["zones"] = max(1, int(zones))
    if init_soil is not None:
        cfg["crop"]["init_soil"] = float(init_soil)
    return cfg


# ===========================================================================
# 2. 室外气象
# ===========================================================================
class Weather:
    """室外边界条件。温度走正弦日循环，云量走 OU 过程（不是逐拍白噪声）。"""

    def __init__(self, cfg, rng, hour0=6.0):
        self.c = cfg["env"]
        self.rng = rng
        self.hour = hour0 % 24.0
        # 云量不透明度 0(晴)~1(全阴)，初值取中位
        self.cloud = 0.15
        # 最近一次 step 的边界条件，供 snapshot 复用（避免重复计算）
        self.state = self._make_state()

    def _make_state(self):
        e = self.c
        # 气温：15 点最高、3 点最低
        t_out = e["t_mean"] + e["t_amp"] * math.sin(2 * math.pi * (self.hour - 9.0) / 24.0)
        # 湿度与温度反相
        w = 0.5 + 0.5 * math.sin(2 * math.pi * (self.hour - 9.0) / 24.0)
        rh_out = e["rh_night"] + (e["rh_day"] - e["rh_night"]) * w
        # 太阳高度角：6 点日出、18 点日落
        elev = math.sin(math.pi * (self.hour - 6.0) / 12.0) if 6.0 <= self.hour <= 18.0 else 0.0
        ppfd_clear = e["ppfd_clear"] * max(0.0, elev)
        ppfd_out = ppfd_clear * (1.0 - 0.75 * self.cloud)
        return dict(
            hour=self.hour,
            t_out=t_out,
            rh_out=rh_out,
            chi_out=rh_to_abs_humidity(t_out, rh_out),
            ppfd_out=ppfd_out,
            i_out=ppfd_out / PPFD_PER_WM2,
            co2_out=e["co2_out"],
            cloud=self.cloud,
        )

    def step(self, dt):
        self.hour = (self.hour + dt / 3600.0) % 24.0
        tau = self.c["cloud_tau"]
        # OU 过程: k += -k/tau·dt + sigma·sqrt(2dt/tau)·N(0,1)
        self.cloud += -self.cloud / tau * dt + \
            self.c["cloud_sigma"] * math.sqrt(max(1e-9, 2.0 * dt / tau)) * self.rng.gauss(0.0, 1.0)
        self.cloud = max(0.0, min(1.0, self.cloud))
        self.state = self._make_state()
        return self.state


# ===========================================================================
# 3. 温室气候真值
# ===========================================================================
class Greenhouse:
    """单栋温室的三平衡：显热 / 水汽 / CO2，外加土壤温度与土壤水分。

    全部用「平衡值 + 时间常数」的指数松弛，dt 任意大都不发散。
    """

    def __init__(self, cfg, rng, zone_idx=0, init_soil=None):
        g = cfg["gh"]
        self.cfg = cfg
        self.rng = rng
        self.zone = zone_idx
        self.a_floor = g["a_floor"]
        self.vol = g["a_floor"] * g["height"]

        # 棚间差异：朝向/覆盖/密封性的个体差（±8%），让多棚数据有可信差别
        jitter = 1.0 + 0.08 * (rng.random() * 2 - 1)
        self.k_cover_scale = jitter
        self.infil_scale = 1.0 + 0.15 * (rng.random() * 2 - 1)

        # 状态量
        self.t_in = 26.0
        self.chi_in = rh_to_abs_humidity(self.t_in, 65.0)
        self.co2 = 600.0
        self.t_soil = 24.0
        crop = cfg["crop"]
        # 初始体积含水率 0~1。略低于灌溉阈值时，开局会有一段「低土壤告警 → 自动灌溉恢复」，
        # 正好用来看告警闭环；想要全程健康就传 0.30。
        self.vwc = float(init_soil) if init_soil is not None else 0.28
        self.ph = 6.5

        # 执行器现状（由仲裁器写入）
        self.u = dict(vent=0.0, heat=0.0, pad=0.0, co2=0.0, lamp=0.0, irr=0.0)

    # -- 派生量 ---------------------------------------------------------
    @property
    def rh(self):
        return max(1.0, min(100.0, abs_humidity_to_rh(self.t_in, self.chi_in)))

    @property
    def vpd(self):
        return vpd_kpa(self.t_in, self.rh)

    def _soil_water_factor(self):
        c = self.cfg["crop"]
        span = c["vwc_fc"] - c["vwc_wp"]
        return max(0.0, min(1.0, (self.vwc - c["vwc_wp"]) / span)) if span > 0 else 0.0

    def _light_factor(self, ppfd):
        """光响应：PPFD 400 以上接近饱和（光合与气孔共同的粗略形状）。"""
        return ppfd / (ppfd + 400.0)

    def _co2_factor(self):
        c = self.cfg["crop"]
        return max(0.0, (self.co2 - c["co2_comp"]) / (self.co2 - c["co2_comp"] + c["co2_kc"]))

    # -- 一步演化 -------------------------------------------------------
    def step(self, dt, u, w):
        g = self.cfg["gh"]
        crop = self.cfg["crop"]
        self.u = u

        # ---- 入射光 ----
        ppfd_in = w["ppfd_out"] * g["tau_cover"] * (1.0 - g["shade_max"])
        ppfd_in += u["lamp"] * g["ppfd_lamp"]
        i_in = ppfd_in / PPFD_PER_WM2

        # ---- 通风量 m3/s ----
        ach = u["vent"] * g["vent_max_ach"] + g["infil_ach"] * self.infil_scale
        v_dot = ach * self.vol / 3600.0

        # ---- 蒸腾 kg/s：光、土壤水、VPD 三者共同限制 ----
        vpd = vpd_kpa(self.t_in, self.rh)
        f_light = self._light_factor(ppfd_in)
        f_soil = self._soil_water_factor()
        f_vpd = max(0.0, min(1.0, vpd / 1.5))
        e_trans = crop["e_max"] * f_light * f_soil * f_vpd
        q_evap = e_trans * L_V

        # ---- 显热平衡 ----
        k_cover = g["u_cover"] * g["a_cover"] * self.k_cover_scale
        k_vent = RHO_CP * v_dot
        k_soil = g["k_soil"]
        q_sun = g["a_floor"] * i_in          # i_in 已是棚内辐射，不再乘透光率
        q_gain = q_sun + g["p_heater"] * u["heat"] + g["p_lamp"] * u["lamp"] \
            - g["p_pad"] * u["pad"] - q_evap
        # 平衡温度：把「与室外换热」和「与土壤换热」都折进来
        k_out = k_cover + k_vent
        t_eq = (k_out * w["t_out"] + k_soil * self.t_soil + q_gain) / max(1e-6, k_out + k_soil)
        tau_th = g["c_thermal"] / max(1e-6, k_out + k_soil)
        self.t_in = relax(self.t_in, t_eq, dt, tau_th)

        # ---- 土壤温度（慢）----
        self.t_soil = relax(self.t_soil, self.t_in, dt, g["tau_soil"])

        # ---- 水汽平衡（绝对含湿量 g/m3）----
        e_pad = g["p_pad"] * u["pad"] / L_V    # 湿帘蒸发量由等效冷量反推（kg/s）
        chi_eq = w["chi_out"] + (e_trans + e_pad) / max(1e-9, v_dot)
        tau_w = self.vol / max(1e-9, v_dot)
        self.chi_in = relax(self.chi_in, chi_eq, dt, tau_w)
        self.chi_in = max(0.0, min(sat_abs_humidity(self.t_in), self.chi_in))

        # ---- CO2 平衡（ppm）----
        photo = crop["p_max"] * f_light * f_soil * self._co2_factor()
        inj = crop["co2_inject"] * u["co2"]
        d_mol = inj - photo + crop["r_resp"]
        co2_eq = w["co2_out"] + d_mol * 1e6 / max(1e-9, v_dot) * MOLAR_VOL
        tau_c = self.vol / max(1e-9, v_dot)
        self.co2 = relax(self.co2, co2_eq, dt, tau_c)
        self.co2 = max(60.0, min(2000.0, self.co2))

        # ---- 土壤水分（L 计）----
        liters_per_vwc = self.a_floor * crop["root_depth"] * 1000.0   # 1.0 VWC = 这么多升
        irr_l = crop["irr_rate"] * u["irr"] * dt / 3600.0
        # 蒸腾耗水：e_trans 是 kg/s，×dt 得 kg，1 kg 水 = 1 L，不要再乘 1000
        uptake_l = e_trans * dt
        drain_l = 0.0
        if self.vwc > crop["vwc_fc"]:
            drain_l = crop["k_drain"] * (self.vwc - crop["vwc_fc"]) * liters_per_vwc * dt / 3600.0
        self.vwc += (irr_l - uptake_l - drain_l) / liters_per_vwc
        self.vwc = max(0.05, min(crop["vwc_max"], self.vwc))

        # ---- pH：灌溉水 7.0、施肥拉低，7 天周期缓慢摆动 ----
        ph_target = 6.4 + 0.25 * math.sin(2 * math.pi * (w["hour"] + self.zone * 2) / 168.0)
        if u["irr"] > 0.5:
            ph_target = min(7.0, ph_target + 0.15)
        self.ph = relax(self.ph, ph_target, dt, 6 * 3600.0)

        return self.snapshot(w, ppfd_in)

    def snapshot(self, w, ppfd_in):
        return dict(
            hour=w["hour"],
            t_in=self.t_in,
            rh=self.rh,
            chi=self.chi_in,
            co2=self.co2,
            t_soil=self.t_soil,
            vwc=self.vwc,
            ph=self.ph,
            ppfd=ppfd_in,
            ppfd_out=w["ppfd_out"],
            i_out=w["i_out"],
            t_out=w["t_out"],
            rh_out=w["rh_out"],
            vpd=self.vpd,
            u=dict(self.u),
        )


# ===========================================================================
# 4. 执行器仲裁（手动优先 + 超时回落自动）
# ===========================================================================
class ActuatorArbiter:
    """把「云端下发的手动指令」和「本地自动控制器」合成一份执行器输出。

    为什么不是纯自动：纯自动会让云端 FAN_OFF 下一拍就被本地改回去，闭环是假的。
    为什么不是纯手动：没人值守时温湿度会一路漂到室外，曲线难看且告警乱响。
    所以取真实温室控制器的标准做法 —— 手动写进去保持 manual_hold_s（默认 10 分钟），
    期间自动控制器不碰该执行器；到期自动交还。
    """

    # 云端动作 -> 物理执行器。fan 视作强制通风（等效天窗），valve/pump 都是灌溉。
    CLOUD_TO_PHYS = {
        "fan": "vent",
        "valve": "irr",
        "pump": "irr",
        "lamp": "lamp",
    }

    def __init__(self, cfg):
        self.c = cfg["ctrl"]
        self.lock = threading.Lock()
        self.manual = {}       # phys_name -> (value, expire_monotonic)
        # 内部滞回状态
        self._heat = 0.0
        self._pad = 0.0
        self._co2 = 0.0
        self._irr = 0.0
        self._lamp = 0.0
        self.last_manual = set()

    def set_manual(self, cloud_name, on, now):
        """云端指令落地。on: 1=开 0=关。"""
        phys = self.CLOUD_TO_PHYS.get(cloud_name)
        if phys is None:
            return False
        with self.lock:
            self.manual[phys] = (float(on), now + self.c["manual_hold_s"])
        return True

    def manual_state(self, now):
        with self.lock:
            out = {}
            expired = []
            for k, (v, exp) in self.manual.items():
                if now < exp:
                    out[k] = v
                else:
                    expired.append(k)
            for k in expired:
                del self.manual[k]
            return out

    def auto_control(self, gh, w):
        """纯自动控制律，带滞回防震荡。"""
        c = self.c
        t, rh = gh.t_in, gh.rh
        u = {}

        # 加热：滞回
        if t < c["t_heat_on"]:
            self._heat = 1.0
        elif t > c["t_heat_off"]:
            self._heat = 0.0
        u["heat"] = self._heat

        # 通风：温度比例 + 高湿强制
        vent = max(0.0, min(1.0, (t - c["t_vent_on"]) / max(0.1, c["t_vent_full"] - c["t_vent_on"])))
        if rh > c["rh_vent_on"]:
            vent = max(vent, min(1.0, (rh - c["rh_vent_on"]) / 8.0))
        u["vent"] = vent

        # 湿帘：热且不过湿才开
        if t > c["t_pad_on"] and rh < c["rh_pad_limit"]:
            self._pad = 1.0
        elif t < c["t_pad_on"] - 2.0 or rh >= c["rh_pad_limit"] + 3.0:
            self._pad = 0.0
        u["pad"] = self._pad

        # CO2：白天光照足且低于目标才施气，滞回
        if w["ppfd_out"] > 50.0:
            if gh.co2 < c["co2_set"] - c["co2_deadband"]:
                self._co2 = 1.0
            elif gh.co2 >= c["co2_set"]:
                self._co2 = 0.0
        else:
            self._co2 = 0.0
        u["co2"] = self._co2

        # 补光：只在白天光照不足时补，夜间一律关（真实温室不会整夜开灯）
        daylight = 6.0 <= w["hour"] <= 18.0
        if daylight and (w["ppfd_out"] * 0.65) < c["ppfd_lamp_on"]:
            self._lamp = 1.0
        else:
            self._lamp = 0.0
        u["lamp"] = self._lamp

        # 灌溉：滞回
        if gh.vwc < c["vwc_irr_on"]:
            self._irr = 1.0
        elif gh.vwc > c["vwc_irr_off"]:
            self._irr = 0.0
        u["irr"] = self._irr

        return u

    def resolve(self, gh, w, now):
        u = self.auto_control(gh, w)
        m = self.manual_state(now)
        for k, v in m.items():
            u[k] = v
        self.last_manual = set(m.keys())
        return u


# ===========================================================================
# 5. 传感器非理想化
# ===========================================================================
class SensorUnit:
    """每台设备一套。真值是共享的，畸变与噪声是个体的。

    不是「再随机一次」：先对真值做确定性畸变（滞后、辐射误差、交叉敏感、漂移），
    再叠加有界白噪声，最后量化。同一棚 50 台设备的读数因此高度相关但不相同。
    """

    def __init__(self, cfg, rng, zone_idx=0, init_soil=None):
        self.cfg = cfg
        s = cfg["sens"]
        self.rng = rng
        # 每台的固定个性：安装位置/个体标定差
        self.off_t = rng.gauss(0.0, 0.25)          # 安装位温差 ℃
        self.off_rh = rng.gauss(0.0, 1.5)          # 个体 RH 偏差 %
        self.off_co2 = rng.gauss(0.0, 12.0)        # 个体 CO2 偏差 ppm
        self.par_gain = 1.0 + 0.15 * (rng.random() * 2 - 1)
        self.par_occl = s["par_occl_base"] * (1.0 - 0.10 * rng.random())

        # 滞后状态（初值取合理值，避免开局有一段爬坡假数据）
        self.t_lag = 26.0 + self.off_t
        self.rh_lag = 65.0
        self.co2_lag = 600.0 + self.off_co2
        self.soil_lag = 0.28

        # 漂移
        self.drift_t_dir = 1.0 if rng.random() < 0.5 else -1.0
        self.drift_rh_dir = 1.0 if rng.random() < 0.5 else -1.0
        self.age = 0.0

        # 结露迟滞
        self.condensed = False

    def sample(self, snap, w, dt):
        """snap 是 Greenhouse.snapshot() 的干净真值 dict；w 保留为扩展位。"""
        s = self.cfg["sens"]
        rng = self.rng

        # ---- 温度探头 ----
        # 辐射误差：太阳/补光把探头烤热，随入射辐射线性增温（防辐射罩下仍有 1~2℃）
        t_truth = snap["t_in"] + self.off_t + s["rad_err"] * min(1.0, snap["ppfd"] / 1000.0)
        self.t_lag = relax(self.t_lag, t_truth, dt, s["tau_t"])

        # ---- 湿度探头：对自身温度变化交叉敏感 ----
        rh_truth = snap["rh"] + self.off_rh + s["k_trh"] * (self.t_lag - snap["t_in"])
        self.rh_lag = relax(self.rh_lag, rh_truth, dt, s["tau_rh"])
        if self.rh_lag >= self.cfg["fault"]["condense_rh"]:
            self.condensed = True
        if self.condensed and self.rh_lag < 92.0:
            self.condensed = False

        # ---- CO2 ----
        co2_truth = snap["co2"] * (1.0 + s["co2_tcoef"] * (self.t_lag - 25.0)) + self.off_co2
        self.co2_lag = relax(self.co2_lag, co2_truth, dt, s["tau_co2"])

        # ---- 土壤水分 ----
        self.soil_lag = relax(self.soil_lag, snap["vwc"], dt, s["tau_soil"])

        # ---- 漂移（按天累积，到校准周期回零）----
        self.age += dt
        frac = min(1.0, self.age / s["drift_reset_s"])
        drift_t = self.drift_t_dir * s["drift_t_per_day"] * frac
        drift_rh = self.drift_rh_dir * s["drift_rh_per_day"] * frac
        if self.age > s["drift_reset_s"]:
            self.age = 0.0

        # ---- 叠加噪声并量化 ----
        t_out = self.t_lag + drift_t + rng.gauss(0.0, s["sigma_t"])
        rh_out = self.rh_lag + drift_rh + rng.gauss(0.0, s["sigma_rh"])
        if self.condensed:
            rh_out = 100.0
        co2_out = self.co2_lag + rng.gauss(0.0, s["sigma_co2"])
        ppfd_out = snap["ppfd"] * self.par_gain * self.par_occl * math.cos(s["par_tilt"])
        ppfd_out = max(0.0, ppfd_out + rng.gauss(0.0, s["sigma_par"]))
        # 土壤水分：soil_lag 是 VWC(0~1)，而 sigma_soil 的语义是「百分点」，故 /100
        soil_out = self.soil_lag + rng.gauss(0.0, s["sigma_soil"] / 100.0)

        t_out = round(t_out / s["res_t"]) * s["res_t"]
        rh_out = max(0.0, min(100.0, rh_out))
        rh_pct = round(rh_out / s["res_rh"]) * s["res_rh"]
        co2_out = round(max(300.0, min(2000.0, co2_out)) / s["res_co2"]) * s["res_co2"]
        ppfd_out = round(ppfd_out / s["res_par"]) * s["res_par"]
        # 土壤水分：内部 VWC 0~1，输出百分比（跟后端/前端一致）
        soil_pct = max(0.0, min(100.0, soil_out * 100.0))
        soil_pct = round(soil_pct / s["res_soil"]) * s["res_soil"]

        return dict(
            temperature=round(t_out, 2),
            humidity=round(rh_pct, 1),
            soilMoisture=round(soil_pct, 1),
            light=round(ppfd_out * LUX_PER_PPFD, 0),
            co2=round(co2_out, 0),
            ph=round(max(0.0, min(14.0, snap["ph"] + rng.gauss(0.0, 0.02))), 2),
            # 扩展字段由 --extra-metrics 决定是否发出（默认不发，保证既有点数口径不变）
            _par=round(ppfd_out, 0),
            _soilTemp=round(snap["t_soil"] + rng.gauss(0.0, 0.1), 2),
            _leafTemp=round(snap["t_in"] + 1.2 * ppfd_out / 800.0 - 0.8 * snap["vpd"], 2),
            _vpd=round(snap["vpd"], 2),
            _dewPoint=round(dew_point(snap["t_in"], snap["rh"]), 2),
        )


def dew_point(t, rh):
    """露点 ℃（Magnus 反解）。"""
    if rh <= 0.01:
        return -40.0
    a, b = 17.62, 243.12
    g = math.log(max(1e-6, rh / 100.0)) + a * t / (b + t)
    return b * g / (a - g)


# ===========================================================================
# 6. 故障状态机
# ===========================================================================
class FaultEngine:
    """每种故障都有生命周期：idle → active(持续 duration) → 结束，不是逐帧掷骰子。

    旧版是「每帧 4% 概率加 12℃」，下一帧就恢复 —— 那不是故障，是噪声。
    """

    def __init__(self, cfg, rng, scale=1.0):
        self.c = cfg["fault"]
        self.rng = rng
        self.scale = max(0.0, scale)
        self.active = {}     # name -> dict(t_end, ...)
        self.offline_until = 0.0
        self.sim_t = 0.0
        self.events = 0

    def _p(self, per_hour, dt):
        return min(1.0, per_hour * self.scale * dt / 3600.0)

    def _start(self, name, dt, dur_key):
        lo, hi = self.c[dur_key]
        self.active[name] = dict(t_end=self.sim_t + self.rng.uniform(lo, hi),
                                 t_beg=self.sim_t)
        self.events += 1

    def alive(self, name):
        a = self.active.get(name)
        if a is None:
            return False
        if self.sim_t >= a["t_end"]:
            del self.active[name]
            return False
        return True

    def apply(self, reading, snap, dt):
        """就地修改读数；返回 None 表示整帧丢失。"""
        self.sim_t += dt
        rng = self.rng
        c = self.c

        # ---- 离线：整段时间不发 ----
        if self.sim_t < self.offline_until:
            return None
        if rng.random() < self._p(c["per_hour_offline"], dt):
            lo, hi = c["dur_offline"]
            self.offline_until = self.sim_t + rng.uniform(lo, hi)
            self.events += 1
            return None

        # ---- 链路丢帧 ----
        if rng.random() < min(0.5, c["p_drop"]):
            return None

        # ---- 卡值：输出冻结不再更新 ----
        if not self.alive("stuck") and rng.random() < self._p(c["per_hour_stuck"], dt):
            self._start("stuck", dt, "dur_stuck")
            self.active["stuck"]["frozen"] = dict(reading)
        if self.alive("stuck"):
            return dict(self.active["stuck"]["frozen"])

        # ---- 漂移：偏置线性拉大再回落 ----
        if not self.alive("drift") and rng.random() < self._p(c["per_hour_drift"], dt):
            self._start("drift", dt, "dur_drift")
            self.active["drift"]["dir"] = 1.0 if rng.random() < 0.5 else -1.0
        if self.alive("drift"):
            a = self.active["drift"]
            span = max(1.0, a["t_end"] - a["t_beg"])
            prog = (self.sim_t - a["t_beg"]) / span
            # 三角包络：0→1→0，避免阶跃
            env = 1.0 - abs(2.0 * prog - 1.0)
            bias = a["dir"] * c["amp_drift"] * env
            reading["temperature"] = round(reading["temperature"] + bias, 2)

        # ---- PAR 遮挡：缓慢加深再恢复 ----
        if not self.alive("occl") and snap["ppfd"] > 200 and rng.random() < self._p(c["per_hour_occlude"], dt):
            self._start("occl", dt, "dur_occlude")
            self.active["occl"]["factor"] = rng.uniform(0.2, 0.6)
        if self.alive("occl"):
            a = self.active["occl"]
            span = max(1.0, a["t_end"] - a["t_beg"])
            prog = (self.sim_t - a["t_beg"]) / span
            env = 1.0 - abs(2.0 * prog - 1.0)
            reading["light"] = round(reading["light"] * (1.0 - env * (1.0 - a["factor"])), 0)

        # ---- 单点尖峰 ----
        if rng.random() < self._p(c["per_hour_spike"], dt):
            amp = rng.uniform(*c["amp_spike"])
            if rng.random() < 0.5:
                amp = -amp
            reading["temperature"] = round(reading["temperature"] + amp, 2)

        return reading


# ===========================================================================
# 7. 极简 MQTT 3.1.1 客户端（CONNECT / PUBLISH / SUBSCRIBE / PINGREQ，零依赖）
# ===========================================================================

def encode_remaining_length(length):
    enc = b""
    while True:
        digit = length % 128
        length //= 128
        if length > 0:
            digit |= 0x80
        enc += bytes([digit])
        if length == 0:
            break
    return enc


def encode_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def build_connect(client_id, username, password, keepalive):
    var = encode_string("MQTT") + bytes([4])          # 协议名 + 级别 4 (3.1.1)
    flags = 0xC2                                       # clean session + username + password
    var += bytes([flags]) + struct.pack(">H", keepalive)
    payload = encode_string(client_id) + encode_string(username) + encode_string(password)
    return bytes([0x10]) + encode_remaining_length(len(var) + len(payload)) + var + payload


def build_publish(topic, payload_bytes, qos=0):
    var = encode_string(topic)
    if qos > 0:
        var += struct.pack(">H", 0)
    return bytes([0x30]) + encode_remaining_length(len(var) + len(payload_bytes)) + var + payload_bytes


def build_subscribe(packet_id, topics, qos=0):
    var = struct.pack(">H", packet_id)  # 可变头：报文标识符
    for t in topics:
        var += encode_string(t)
        var += bytes([qos])             # 每个主题请求的服务质量
    return bytes([0x82]) + encode_remaining_length(len(var)) + var


def build_pingreq():
    return bytes([0xC0, 0x00])


def try_parse_packet(buf):
    """尝试从缓冲中解析一个完整 MQTT 包；不完整返回 None。"""
    if len(buf) < 2:
        return None
    multiplier = 1
    value = 0
    pos = 1
    while pos < len(buf):
        enc = buf[pos]
        value += (enc & 0x7F) * multiplier
        multiplier *= 128
        pos += 1
        if (enc & 0x80) == 0:
            break
    else:
        return None  # 剩余长度字段未接收完整
    total = pos + value
    if len(buf) < total:
        return None  # 包体未接收完整
    ptype = buf[0] >> 4
    return ptype, total


def parse_publish(packet):
    """从完整 PUBLISH 包中解析出 (topic, payload_bytes)。"""
    flags = packet[0] & 0x0F
    qos = (flags >> 1) & 0x03
    pos = 1
    # 跳过剩余长度
    while True:
        enc = packet[pos]
        pos += 1
        if (enc & 0x80) == 0:
            break
    tlen = struct.unpack(">H", packet[pos:pos + 2])[0]
    pos += 2
    topic = packet[pos:pos + tlen].decode("utf-8")
    pos += tlen
    if qos > 0:
        pos += 2  # 报文标识符
    payload = packet[pos:]
    return topic, payload


class DeviceClient:
    def __init__(self, idx, device_id, host, port, keepalive, username, password,
                 arbiter=None, zone_idx=0):
        self.idx = idx
        self.device_id = device_id
        self.host = host
        self.port = port
        self.keepalive = keepalive
        self.username = username
        self.password = password
        self.sock = None
        self.buf = b""
        self.connected = False
        self.last_send = 0
        self.arbiter = arbiter
        self.zone_idx = zone_idx
        # 执行器本地状态：风扇/灌溉阀/补光灯/水泵（0=关 1=开）
        # 注意：这里的值由物理侧「实际执行器输出」回写，不是简单回显云端指令，
        # 这样云端影子看到的是真实状态（自动模式下风扇真的转起来了，上报也是 1）。
        self.controls = {"fan": 0, "valve": 0, "lamp": 0, "pump": 0}
        self.sub_packet_id = 1
        # 发送锁：serial 化对本 socket 的所有写（遥测/心跳/回执 由不同线程发起），
        # 避免并发 sendall 交错字节破坏 MQTT 帧（会导致回执/遥测偶发损坏）
        self.send_lock = threading.Lock()

    def connect(self):
        # 重连场景：先清理旧 socket 并清空缓冲，避免半开连接复用导致 CONNACK 解析错乱
        try:
            if self.sock is not None:
                self.sock.close()
        except Exception:
            pass
        self.sock = None
        self.buf = b""
        self.connected = False
        try:
            s = socket.create_connection((self.host, self.port), timeout=5)
        except Exception as e:
            log_info("connect 异常 %s: %s" % (self.cid(), e))
            return False
        s.setblocking(False)
        self.sock = s
        pkt = build_connect(self.cid(), self.username, self.password, self.keepalive)
        try:
            s.sendall(pkt)
        except Exception as e:
            log_info("send CONNECT 失败 %s: %s" % (self.cid(), e))
            return False
        # 等待 CONNACK（最多 3 秒）
        deadline = time.time() + 3
        while time.time() < deadline:
            try:
                data = s.recv(1024)
            except BlockingIOError:
                time.sleep(0.05)
                continue
            except Exception:
                return False
            if not data:
                return False
            self.buf += data
            parsed = try_parse_packet(self.buf)
            if parsed is not None:
                ptype, total = parsed
                packet = self.buf[:total]          # 先取出整包，再消费
                self.buf = self.buf[total:]
                if ptype == 2:  # CONNACK
                    # 固定头(0x20 + 剩余长度) + flags(1) + return code(1)
                    rc = packet[3] if len(packet) >= 4 else 1
                    if rc == 0:
                        self.connected = True
                        self.last_send = time.time()
                        # 订阅云端下行控制指令主题，支持控制闭环
                        self.subscribe("device/%s/command" % self.device_id)
                        return True
                    else:
                        log_info("CONNACK 拒绝 rc=%d %s" % (rc, self.cid()))
                        return False
        return False

    def subscribe(self, topic):
        if not self.connected or self.sock is None:
            return False
        try:
            with self.send_lock:
                self.sock.sendall(build_subscribe(self.sub_packet_id, [topic]))
            self.sub_packet_id = (self.sub_packet_id % 65535) + 1
            return True
        except Exception:
            self.connected = False
            return False

    def cid(self):
        return "sim-%08d" % self.idx

    def publish(self, topic, payload_bytes):
        if not self.connected or self.sock is None:
            return False
        try:
            with self.send_lock:
                self.sock.sendall(build_publish(topic, payload_bytes))
            self.last_send = time.time()
            return True
        except Exception:
            self.connected = False
            return False

    def ping(self):
        if not self.connected or self.sock is None:
            return
        try:
            with self.send_lock:
                self.sock.sendall(build_pingreq())
            self.last_send = time.time()
        except Exception:
            self.connected = False

    # 执行器名称映射：动作前缀 -> 控制字段
    ACTOR_MAP = {"FAN": "fan", "VALVE": "valve", "LAMP": "lamp", "PUMP": "pump"}

    def execute_action(self, action):
        """执行一个云端下发的控制动作，返回 (ok, state)。

        识别成功后会同步写入本棚的仲裁器（手动保持 manual_hold_s），
        物理侧因此会真的把风扇/阀门拉到位 —— 这才是闭环。
        """
        if not action:
            return False, self.controls
        up = action.upper()
        if up.endswith("_ON") or up.endswith("_OPEN"):
            on = 1
        elif up.endswith("_OFF") or up.endswith("_CLOSE"):
            on = 0
        else:
            return False, self.controls
        prefix = up.split("_")[0]
        name = DeviceClient.ACTOR_MAP.get(prefix)
        if name is None:
            return False, self.controls
        if self.arbiter is not None:
            self.arbiter.set_manual(name, on, time.monotonic())
        self.controls[name] = on
        return True, self.controls

    def handle_command(self, payload_bytes):
        """处理云端下行控制指令：执行并回执。"""
        try:
            msg = json.loads(payload_bytes.decode("utf-8"))
        except Exception:
            return
        cmd_id = msg.get("cmdId")
        action = msg.get("action")
        ok, state = self.execute_action(action)
        resp = {
            "cmdId": cmd_id,
            "deviceId": self.device_id,
            "action": action,
            "status": "OK" if ok else "UNSUPPORTED",
            "state": dict(state),
        }
        if not ok:
            resp["message"] = "不支持的动作: %s" % action
        topic = "device/%s/command/response" % self.device_id
        self.publish(topic, json.dumps(resp).encode("utf-8"))
        log_info("执行指令 %s -> %s : %s" % (cmd_id, action, resp["status"]))

    def close(self):
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass
        self.connected = False


# ===========================================================================
# 8. 仿真编排（网络无关，可单独跑）
# ===========================================================================
class SimFarm:
    """若干大棚 + 每棚一个仲裁器。设备只负责采样，不持有真值。"""

    def __init__(self, cfg, seed, fault_scale=1.0, init_soil=None):
        self.cfg = cfg
        nz = cfg["sim"]["zones"]
        self.zones = []
        for z in range(nz):
            rng = random.Random(seed * 1000 + z)
            w = Weather(cfg, rng, hour0=cfg["sim"]["start_hour"] + z * 0.3)
            gh = Greenhouse(cfg, rng, zone_idx=z, init_soil=init_soil)
            arb = ActuatorArbiter(cfg)
            self.zones.append(dict(weather=w, gh=gh, arb=arb,
                                   sensors=None, faults=None))

    def zone_of(self, dev_idx):
        return dev_idx % len(self.zones)

    def attach_device(self, dev_idx, seed, fault_scale=1.0):
        z = self.zone_of(dev_idx)
        slot = self.zones[z]
        # 同一棚的设备共享真值，只有传感器个体差
        rng = random.Random(seed * 100000 + dev_idx)
        sensor = SensorUnit(self.cfg, rng, zone_idx=z)
        # 传感器滞后状态初值对齐棚真值，避免开局有一段假爬坡
        sensor.t_lag = slot["gh"].t_in
        sensor.rh_lag = slot["gh"].rh
        sensor.co2_lag = slot["gh"].co2
        sensor.soil_lag = slot["gh"].vwc
        return sensor, FaultEngine(self.cfg, rng, fault_scale), z

    def step_physics(self, real_dt, now):
        """推进所有棚的物理。real_dt 是真实经过时间，乘 time_scale 后细分。"""
        sim_dt = real_dt * self.cfg["sim"]["time_scale"]
        n = max(1, int(math.ceil(sim_dt / self.cfg["sim"]["phys_dt_max"])))
        sub = sim_dt / n
        for slot in self.zones:
            snap = slot.get("snap")
            for _ in range(n):
                w = slot["weather"].step(sub)
                u = slot["arb"].resolve(slot["gh"], w, now)
                snap = slot["gh"].step(sub, u, w)
            slot["snap"] = snap
        return sim_dt

    def zone_snapshot(self, z):
        """返回该棚最近一步的真值快照（含传感器无关的干净真值）。"""
        return self.zones[z]["snap"]


def build_frame(sensor_reading, controls, extra=False):
    """把传感器读数与执行器状态拼成上报帧（字段名与后端/告警规则对齐）。"""
    frame = {
        "temperature": sensor_reading["temperature"],
        "humidity": sensor_reading["humidity"],
        "soilMoisture": sensor_reading["soilMoisture"],
        "light": sensor_reading["light"],
        "co2": sensor_reading["co2"],
        "ph": sensor_reading["ph"],
    }
    if extra:
        frame["par"] = sensor_reading["_par"]
        frame["soilTemp"] = sensor_reading["_soilTemp"]
        frame["leafTemp"] = sensor_reading["_leafTemp"]
        frame["vpd"] = sensor_reading["_vpd"]
        frame["dewPoint"] = sensor_reading["_dewPoint"]
    frame.update(controls)
    return frame


def sync_controls(client, gh_state):
    """把物理执行器的真实状态回写到设备 controls，供云端影子展示。"""
    u = gh_state["u"]
    vent_on = 1 if u.get("vent", 0.0) > 0.5 else 0
    irr_on = 1 if u.get("irr", 0.0) > 0.5 else 0
    lamp_on = 1 if u.get("lamp", 0.0) > 0.5 else 0
    client.controls["fan"] = vent_on
    client.controls["valve"] = irr_on
    client.controls["lamp"] = lamp_on
    client.controls["pump"] = irr_on


# ===========================================================================
# 9. Dry-run：不连网，纯物理 + 采样，用于标定与回归
# ===========================================================================
def dry_run(cfg, seed, fault_scale, hours, extra, interval_s):
    farm = SimFarm(cfg, seed, fault_scale, cfg["crop"].get("init_soil"))
    n_dev = min(20, cfg["sim"]["zones"] * 5)
    devs = []
    for i in range(n_dev):
        s, f, z = farm.attach_device(i, seed, fault_scale)
        devs.append(dict(sensor=s, fault=f, zone=z, frames=[], drops=0, events0=f.events))

    steps = int(hours * 3600 / interval_s)
    now = time.monotonic()
    for step in range(steps):
        farm.step_physics(interval_s, now)
        snaps = [farm.zone_snapshot(z) for z in range(len(farm.zones))]
        for d in devs:
            snap = snaps[d["zone"]]
            r = d["sensor"].sample(snap, None, interval_s)
            r = d["fault"].apply(r, snap, interval_s)
            if r is None:
                d["drops"] += 1
                continue
            frame = build_frame(r, {}, extra)
            frame["_hour"] = snap["hour"]      # 仅 dry-run 统计用，不会发出去
            frame["_t_in"] = round(snap["t_in"], 2)
            d["frames"].append(frame)

    report_dry_run(cfg, farm, devs, hours, steps)
    return farm, devs


def report_dry_run(cfg, farm, devs, hours, steps):
    def col(rows, k):
        return [x[k] for x in rows if x.get(k) is not None]

    def pct(xs, p):
        s = sorted(xs)
        return s[min(len(s) - 1, int(p / 100.0 * len(s)))] if s else float("nan")

    def lag1(xs):
        if len(xs) < 3:
            return 0.0
        a, b = xs[:-1], xs[1:]
        ma, mb = sum(a) / len(a), sum(b) / len(b)
        cov = sum((x - ma) * (y - mb) for x, y in zip(a, b))
        va = math.sqrt(sum((x - ma) ** 2 for x in a))
        vb = math.sqrt(sum((y - mb) ** 2 for y in b))
        return cov / (va * vb) if va and vb else 0.0

    f0 = devs[0]["frames"]
    print("\n" + "=" * 78)
    print("仿真 %.1f 小时 / %d 步 / %d 设备 / %d 棚" % (hours, steps, len(devs), len(farm.zones)))
    print("=" * 78)

    print("\n[1] 取值范围与时间连续性（lag-1 自相关越接近 1 越平滑）")
    print("    指标             min        max       mean     lag1     相邻跳变P50")
    for k in ("temperature", "humidity", "soilMoisture", "light", "co2", "ph"):
        xs = col(f0, k)
        if not xs:
            continue
        d = [abs(xs[i + 1] - xs[i]) for i in range(len(xs) - 1)]
        print("    %-14s %8.2f %10.2f %10.2f %8.4f %10.3f" % (
            k, min(xs), max(xs), sum(xs) / len(xs), lag1(xs), pct(d, 50)))

    # 温湿相关
    t = col(f0, "temperature")
    h = col(f0, "humidity")
    if len(t) == len(h) and len(t) > 2:
        mt, mh = sum(t) / len(t), sum(h) / len(h)
        cov = sum((a - mt) * (b - mh) for a, b in zip(t, h))
        va = math.sqrt(sum((a - mt) ** 2 for a in t))
        vb = math.sqrt(sum((b - mh) ** 2 for b in h))
        print("\n[2] 温度-湿度相关系数 = %.4f  （物理上应显著为负：升温→饱和含湿量升→RH降）"
              % (cov / (va * vb) if va and vb else 0))

    # 告警规则命中率（对照 DataInitializer 预置规则）
    print("\n[3] 既有告警规则命中率与「触发→恢复」事件（对照 AlertRule 预置三条）")
    n = len(f0)
    tt = col(f0, "temperature")
    hh = col(f0, "humidity")
    ss = col(f0, "soilMoisture")
    interval_min = (hours * 3600.0) / max(1, n) / 60.0   # 分钟/帧

    def runs(flags):
        """把布尔序列切成连续段，返回 [(长度, 是否命中), ...]。"""
        out, cur, val = [], 0, flags[0] if flags else None
        for f in flags:
            if f == val:
                cur += 1
            else:
                out.append((cur, val))
                cur, val = 1, f
        if cur:
            out.append((cur, val))
        return out

    for name, flags in (
        ("高温 temperature>35     ", [x > 35.0 for x in tt]),
        ("低土壤 soilMoisture<25  ", [x < 25.0 for x in ss]),
        ("闷热 temp>24 且 hum>60  ", [a > 24.0 and b > 60.0 for a, b in zip(tt, hh)]),
    ):
        hit = sum(1 for f in flags if f)
        segs = [r for r, v in runs(flags) if v]
        longest = max(segs) if segs else 0
        # 阈值附近来回穿越是「即时触发 + 传感器噪声」的固有现象，真实引擎靠 durationSec 去抖。
        # 这里把去抖前后的事件数都算出来，好判断到底是物理在抖还是规则没设去抖。
        need = max(1, int(round(0.5 / max(1e-9, interval_min))))   # durationSec=30 → 0.5 分钟
        deb = 0
        run = 0
        for f in flags:
            if f:
                run += 1
                if run == need:
                    deb += 1
            else:
                run = 0
        print("    %s: 命中 %6.2f%%  原始告警段 %4d 次（最长 %5.1f 分钟）  "
              "加 30s 去抖后 %3d 次" % (
                  name, 100.0 * hit / n, len(segs), longest * interval_min, deb))

    print("\n    阈值敏感性（同一份数据只换阈值，用来判断是不是规则定松了）：")
    for t_th, h_th in ((24.0, 60.0), (28.0, 60.0), (30.0, 65.0), (30.0, 80.0)):
        nh = sum(1 for a, b in zip(tt, hh) if a > t_th and b > h_th)
        print("      T>%4.1f 且 RH>%4.1f : %6.2f%%" % (t_th, h_th, 100.0 * nh / n))

    # 昼夜
    if "_hour" in f0[0]:
        print("\n[4] 逐小时真值曲线（棚0，小时: T真值/RH/CO2/PPFD/光照lux）")
        buckets = {}
        for x in f0:
            h = int(x["_hour"])
            buckets.setdefault(h, []).append(x)
        for h in sorted(buckets):
            rows = buckets[h]
            n = len(rows)
            print("    %02d时  T=%5.1f℃  RH=%5.1f%%  CO2=%5.0fppm  PPFD=%6.0f  light=%7.0f lux  soil=%4.1f%%" % (
                h,
                sum(r["_t_in"] for r in rows) / n,
                sum(r["humidity"] for r in rows) / n,
                sum(r["co2"] for r in rows) / n,
                sum(r.get("_par", r["light"] / LUX_PER_PPFD) for r in rows) / n,
                sum(r["light"] for r in rows) / n,
                sum(r["soilMoisture"] for r in rows) / n))
        day = [x for x in f0 if 9 <= x["_hour"] <= 16]
        night = [x for x in f0 if x["_hour"] < 5 or x["_hour"] > 21]
        if day and night:
            print("\n    白天 light 均值 = %10.0f lux   夜间 = %10.0f lux" % (
                sum(x["light"] for x in day) / len(day), sum(x["light"] for x in night) / len(night)))
            print("    白天 co2   均值 = %8.0f ppm   夜间 = %8.0f ppm" % (
                sum(x["co2"] for x in day) / len(day), sum(x["co2"] for x in night) / len(night)))
            print("    白天 T     均值 = %8.1f ℃     夜间 = %8.1f ℃" % (
                sum(x["_t_in"] for x in day) / len(day), sum(x["_t_in"] for x in night) / len(night)))

    print("\n[5] 棚真值终态")
    for z, slot in enumerate(farm.zones):
        gh = slot["gh"]
        print("    棚%d: T_in=%5.1f℃  RH=%5.1f%%  CO2=%6.1fppm  土壤=%5.1f%%  T_soil=%5.1f℃  vent=%.2f irr=%.0f" % (
            z, gh.t_in, gh.rh, gh.co2, gh.vwc * 100, gh.t_soil,
            gh.u.get("vent", 0), gh.u.get("irr", 0)))

    print("\n[6] 故障与丢帧")
    for i, d in enumerate(devs[:6]):
        print("    设备%02d: 丢帧 %3d / %d (%.1f%%)  故障事件 %d 次" % (
            i, d["drops"], len(d["frames"]) + d["drops"],
            100.0 * d["drops"] / max(1, len(d["frames"]) + d["drops"]),
            d["fault"].events))

    print("\n[7] 同棚一致性（同一时刻不同设备的温度极差）")
    if len(devs) >= 2:
        idx = min(500, len(devs[0]["frames"]) - 1)
        same = [d["frames"][idx]["temperature"] for d in devs if len(d["frames"]) > idx]
        print("    同一时刻 %d 台温度: min=%.2f max=%.2f 极差=%.2f℃" % (
            len(same), min(same), max(same), max(same) - min(same)))
        print("    （旧版同一时刻 12 台极差 15.4℃——同一大棚不可能差这么多）")

    print("\n样例帧:")
    print(json.dumps(devs[0]["frames"][-1], indent=2, ensure_ascii=False))
    print()


# ===========================================================================
# 10. 主流程
# ===========================================================================
def main():
    ap = argparse.ArgumentParser(description="智慧农业设备模拟器（物理仿真版，零依赖）")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--devices", type=int, default=200)
    ap.add_argument("--connections", type=int, default=200)
    ap.add_argument("--connect-pace-ms", type=float, default=2.0,
                    help="建连节奏限速：每条连接间隔(ms)，避免启动瞬间冲击 CPU/broker")
    ap.add_argument("--interval", type=int, default=2000, help="上报间隔(ms)")
    ap.add_argument("--heartbeat", type=int, default=15000, help="心跳/keepalive 间隔(ms)")
    ap.add_argument("--fault-rate", type=float, default=0.04,
                    help="故障强度倍率基准：0=不注入；0.04=正常（等价旧版默认）；0.2≈5 倍故障率")
    ap.add_argument("--base-temp", type=float, default=None, help="室外日均气温 ℃（默认取场景值）")
    ap.add_argument("--temp-amplitude", type=float, default=None, help="室外日较差半幅 ℃")
    ap.add_argument("--scenario", choices=sorted(SCENARIOS.keys()), default="summer",
                    help="气象场景预设")
    ap.add_argument("--zones", type=int, default=None, help="大棚数量（设备均分）")
    ap.add_argument("--sim-speed", type=float, default=1.0, help="仿真时间/真实时间 倍数")
    ap.add_argument("--init-soil", type=float, default=None,
                    help="初始土壤体积含水率 0~1（如 0.23 可演示低土壤告警→灌溉恢复）")
    ap.add_argument("--seed", type=int, default=20261029)
    ap.add_argument("--extra-metrics", action="store_true",
                    help="额外上报 par/soilTemp/leafTemp/vpd/dewPoint（默认关，保证既有点数口径不变）")
    ap.add_argument("--dry-run", action="store_true", help="不连网，跑完打印统计后退出")
    ap.add_argument("--sim-hours", type=float, default=24.0, help="dry-run 的仿真时长(小时)")
    ap.add_argument("--username", default=os.environ.get("MQTT_SIM_USERNAME", "simulator"))
    ap.add_argument("--password", default=os.environ.get("MQTT_SIM_SECRET", "sim-secret-2026-change-me"))
    ap.add_argument("--duration", type=int, default=0, help="运行时长(s)，0=不限时")
    args = ap.parse_args()

    cfg = build_cfg(args.base_temp, args.temp_amplitude, args.scenario, args.zones, args.init_soil)
    cfg["sim"]["time_scale"] = max(0.01, args.sim_speed)

    # 兼容映射：旧版 --fault-rate 是「高温故障概率」，默认 0.04。
    # 新版它是「故障强度倍率」，0.04 → 1.0 倍（保持后端默认参数下行为不变）。
    fault_scale = args.fault_rate / 0.04 if args.fault_rate > 0 else 0.0

    if args.dry_run:
        dry_run(cfg, args.seed, fault_scale, args.sim_hours,
                args.extra_metrics, args.interval / 1000.0)
        return

    rnd = random.Random(args.seed)
    stop = {"flag": False}
    clients = []
    lock = threading.Lock()
    farm = SimFarm(cfg, args.seed, fault_scale, cfg["crop"].get("init_soil"))

    log_info("启动模拟器: %d 设备 / %d 连接 / %d 棚 -> %s:%d  场景=%s 时间倍率=%.2fx" % (
        args.devices, args.connections, len(farm.zones), args.host, args.port,
        args.scenario, cfg["sim"]["time_scale"]))

    def report_all():
        started = False
        interval_s = args.interval / 1000.0
        while not stop["flag"]:
            t_begin = time.monotonic()
            with lock:
                snapshot = list(clients)
            if not snapshot:
                # 尚无就绪连接：短暂等待（避免空转，也避免按 interval 空等）
                time.sleep(0.2)
                continue
            if not started:
                started = True
                log_info("开始上报遥测（当前已就绪 %d 台设备）" % len(snapshot))

            # 1) 先按棚推进物理（每个棚每拍只算一次，不是每台设备算一次）
            farm.step_physics(interval_s, t_begin)
            snaps = [farm.zone_snapshot(z) for z in range(len(farm.zones))]

            # 2) 再逐设备采样 + 故障注入 + 上报
            for c in snapshot:
                snap = snaps[c.zone_idx]
                reading = c.sensor.sample(snap, None, interval_s)
                reading = c.faults.apply(reading, snap, interval_s)
                if reading is None:
                    continue
                sync_controls(c, snap)
                frame = build_frame(reading, c.controls, args.extra_metrics)
                topic = "device/%s/telemetry" % c.device_id
                c.publish(topic, json.dumps(frame).encode("utf-8"))

            # 3) 固定节拍：扣掉本轮耗时，避免物理计算让上报周期漂移
            time.sleep(max(0.0, interval_s - (time.monotonic() - t_begin)))

    def keepalive_all():
        while not stop["flag"]:
            with lock:
                snapshot = list(clients)
            for c in snapshot:
                if time.time() - c.last_send >= (args.heartbeat / 1000.0) / 2.0:
                    c.ping()
            time.sleep(max(1, (args.heartbeat // 1000) // 2))

    def reader_loop():
        """统一读取所有 socket，处理 CONNACK / PINGRESP / 下行 PUBLISH。

        防御性加固（解决此前"连接爬满后全量设备收不到下行指令、指令卡 SENT"）：
          * Windows WinSock 的 select() 受 FD_SETSIZE(默认64) 限制，单批传入过多
            fd 会使其抛 OSError 令读取线程静默停摆 —— 故分批 select（每批<=63）；
          * select / recv 异常只隔离失效 fd，绝不退出读取线程；
          * 单连接解析异常只隔离该连接；缓冲异常膨胀时重置。
        """
        MAX_BUF = 4 * 1024 * 1024          # 单连接缓冲上限，超且解析不出则视为错位重置
        BATCH = 63                         # < FD_SETSIZE，规避 Windows select 限制
        while not stop["flag"]:
            with lock:
                live = [c for c in clients if c.connected and c.sock is not None]
            if not live:
                time.sleep(0.2)
                continue
            socks = [c.sock for c in live]
            readable = set()
            for bi, i in enumerate(range(0, len(socks), BATCH)):
                batch = socks[i:i + BATCH]
                try:
                    # 第一批等待 0.5s 唤醒，其余批次立即轮询，整体不丢数据且零长阻塞
                    rl, _, _ = select.select(batch, [], [], 0.5 if bi == 0 else 0.0)
                    readable.update(rl)
                except (OSError, ValueError) as e:
                    log_info("reader_loop select 异常: %s，清理失效 fd" % e)
                    with lock:
                        for s in batch:
                            for c in clients:
                                if c.sock is s:
                                    c.connected = False
                                    try:
                                        c.sock.close()
                                    except Exception:
                                        pass
                                    c.sock = None
                    continue
            for s in readable:
                with lock:
                    owner = next((c for c in clients if c.sock is s), None)
                if owner is None:
                    continue
                try:
                    data = s.recv(4096)
                except Exception:
                    data = b""
                if not data:
                    # 对端关闭：标记掉线，交由重连线程恢复
                    with lock:
                        for c in clients:
                            if c.sock is s:
                                c.connected = False
                                try:
                                    c.sock.close()
                                except Exception:
                                    pass
                                c.sock = None
                    continue
                owner.buf += data
                if len(owner.buf) > MAX_BUF:          # 缓冲错位/异常膨胀保护
                    owner.buf = b""
                    continue
                try:
                    while True:
                        parsed = try_parse_packet(owner.buf)
                        if parsed is None:
                            break
                        ptype, total = parsed
                        packet = owner.buf[:total]      # 先取出整包，再消费缓冲
                        owner.buf = owner.buf[total:]
                        if ptype == 13:  # PINGRESP
                            pass
                        elif ptype == 2:  # CONNACK（重连场景）
                            pass
                        elif ptype == 3:  # PUBLISH（云端下行控制指令）
                            try:
                                topic, payload = parse_publish(packet)
                                if topic and topic.endswith("/command"):
                                    owner.handle_command(payload)
                            except Exception:
                                pass
                        if len(owner.buf) > MAX_BUF:
                            owner.buf = b""
                            break
                except Exception as e:
                    # 单连接解析异常：隔离该连接，保留线程继续服务其他设备
                    log_info("reader_loop 解析异常 %s: %s" % (owner.cid(), e))
                    with lock:
                        for c in clients:
                            if c.sock is s:
                                c.connected = False
                                try:
                                    c.sock.close()
                                except Exception:
                                    pass
                                c.sock = None

    def reconnect_loop():
        """周期性检测掉线连接并按原 deviceId 重建，维持稳态连接数（防止长稳运行漂移）。"""
        while not stop["flag"]:
            time.sleep(3)
            with lock:
                dead = [c for c in clients if not c.connected]
            if not dead:
                continue
            recovered = 0
            for c in dead:
                if stop["flag"]:
                    break
                # 错峰重连：每台间隔 50ms，避免瞬时冲爆 broker 监听队列（否则 10061 拒绝风暴）
                time.sleep(0.05)
                if c.connect():
                    recovered += 1
            if recovered:
                with lock:
                    alive = sum(1 for c in clients if c.connected)
                log_info("重连恢复 %d 个掉线连接（当前已就绪 %d / 目标 %d）" % (
                    recovered, alive, args.connections))

    def shutdown():
        log_info("收到停止信号，正在断开 %d 个连接..." % len(clients))
        stop["flag"] = True
        with lock:
            snapshot = list(clients)
        for c in snapshot:
            c.close()
        sys.exit(0)

    signal.signal(signal.SIGINT, lambda *_: shutdown())
    signal.signal(signal.SIGTERM, lambda *_: shutdown())

    # 先启动上报/心跳/读取线程：建连过程中即开始上报已就绪设备（边连边发），
    # 这样大屏在"连接数爬升"阶段就能看到遥测与在线设备，而不是等 1000 条全部就绪。
    threading.Thread(target=report_all, daemon=True).start()
    threading.Thread(target=keepalive_all, daemon=True).start()
    threading.Thread(target=reader_loop, daemon=True).start()
    threading.Thread(target=reconnect_loop, daemon=True).start()

    ok = 0
    for i in range(args.connections):
        device_id = "device-%05d" % (i % args.devices)
        z = farm.zone_of(i % args.devices)
        arb = farm.zones[z]["arb"]
        c = DeviceClient(i, device_id, args.host, args.port,
                         max(10, args.heartbeat // 1000), args.username, args.password,
                         arbiter=arb, zone_idx=z)
        c.sensor, c.faults, _ = farm.attach_device(i % args.devices, args.seed, fault_scale)
        if c.connect():
            with lock:
                clients.append(c)
                ok += 1
        # 建连节奏限速：平滑爬坡，避免启动瞬间把 CPU / broker accept 队列打满
        if args.connect_pace_ms > 0:
            time.sleep(args.connect_pace_ms / 1000.0)
        if (i + 1) % 100 == 0:
            log_info("已建立连接 %d/%d (成功 %d)" % (i + 1, args.connections, ok))

    log_info("全部连接就绪: 成功 %d / 目标 %d" % (ok, args.connections))

    if args.duration > 0:
        time.sleep(args.duration)
        shutdown()
    else:
        while not stop["flag"]:
            time.sleep(1)


if __name__ == "__main__":
    main()
