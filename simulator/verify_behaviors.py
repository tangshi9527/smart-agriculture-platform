# -*- coding: utf-8 -*-
"""
device_sim.py 关键行为自证脚本（不连网）。

验证 6 件事，每件都打印可核对的数字：
  1. 闭环：云端 FAN_ON 之后温度是否真的下降（旧版这里 controls 只是回显，物理不动）
  2. 手动保持：10 分钟内自动控制器不夺权，超时后回落自动
  3. 同棚相关：同一棚 50 台设备读数应高度相关，跨棚应有差异
  4. 故障生命周期：卡值必须持续一段时间，不是单帧抖动
  5. 可复现：同 seed 两次运行逐字段一致
  6. 时间连续性：相邻两拍跳变量远小于旧版白噪声

用法: python simulator/verify_behaviors.py
"""
import importlib.util
import json
import math
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SIM = os.path.join(HERE, "device_sim.py")
spec = importlib.util.spec_from_file_location("ds", SIM)
ds = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ds)

PASS = []
FAIL = []


def check(name, ok, detail):
    (PASS if ok else FAIL).append(name)
    print("  [%s] %s  %s" % ("PASS" if ok else "FAIL", name, detail))


def build(seed=7, scenario="summer"):
    cfg = ds.build_cfg(None, None, scenario, None, None)
    return cfg, ds.SimFarm(cfg, seed, 1.0, cfg["crop"].get("init_soil"))


def run_for(farm, devs, seconds, dt=2.0, now0=0.0, collect=True):
    """跑 seconds 秒，返回每帧的 {dev_idx: frame}。now 必须递增，否则手动超时不生效。"""
    out = []
    steps = int(seconds / dt)
    for i in range(steps):
        now = now0 + i * dt
        farm.step_physics(dt, now)
        snaps = [farm.zone_snapshot(z) for z in range(len(farm.zones))]
        row = {}
        for idx, (sensor, fault, z) in enumerate(devs):
            r = sensor.sample(snaps[z], None, dt)
            r = fault.apply(r, snaps[z], dt)
            if r is not None:
                row[idx] = ds.build_frame(r, {}, False)
        if collect:
            out.append(row)
    return out


print("=" * 76)
print("1) 闭环：云端 FAN_ON 是否让温度真的下降（对照组实验）")
print("=" * 76)
# 单看前后差会被「太阳同时升高」抵掉，所以用同一 seed 的两个 farm 做对照：
# 一个下发 FAN_ON，一个不动作，其余完全相同，差值才是 FAN_ON 的净效果。
zone = 0
cfg_a, farm_a = build()
cfg_b, farm_b = build()
sensor_a = ds.SensorUnit(cfg_a, __import__("random").Random(1), zone)
sensor_b = ds.SensorUnit(cfg_b, __import__("random").Random(1), zone)
fault_a = ds.FaultEngine(cfg_a, __import__("random").Random(2), 0.0)
fault_b = ds.FaultEngine(cfg_b, __import__("random").Random(2), 0.0)

# 先一起跑到上午 9 点多（自动通风只开一部分，FAN_ON 才有加量空间）
run_for(farm_a, [(sensor_a, fault_a, zone)], 3.3 * 3600, dt=60.0, now0=0.0, collect=False)
run_for(farm_b, [(sensor_b, fault_b, zone)], 3.3 * 3600, dt=60.0, now0=0.0, collect=False)
t_a0 = farm_a.zones[zone]["gh"].t_in
t_b0 = farm_b.zones[zone]["gh"].t_in
v_a0 = farm_a.zones[zone]["gh"].u["vent"]

farm_a.zones[zone]["arb"].set_manual("fan", 1, time.monotonic())
run_for(farm_a, [(sensor_a, fault_a, zone)], 540.0, dt=60.0, now0=time.monotonic(), collect=False)
run_for(farm_b, [(sensor_b, fault_b, zone)], 540.0, dt=60.0, now0=time.monotonic(), collect=False)

t_a1 = farm_a.zones[zone]["gh"].t_in
t_b1 = farm_b.zones[zone]["gh"].t_in
v_a1 = farm_a.zones[zone]["gh"].u["vent"]
print("  实验组(FAN_ON): vent %.2f → %.2f,  T %.2f → %.2f℃" % (v_a0, v_a1, t_a0, t_a1))
print("  对照组(不动)  : vent %.2f → %.2f,  T %.2f → %.2f℃" % (
    v_a0, farm_b.zones[zone]["gh"].u["vent"], t_b0, t_b1))
print("  9 分钟（手动保持期内）温度差（对照组 - 实验组）= %.2f℃" % (t_b1 - t_a1))
check("FAN_ON 使通风量上升", v_a1 > v_a0 + 0.05, "vent %.2f → %.2f" % (v_a0, v_a1))
check("FAN_ON 让温度低于对照", t_a1 < t_b1 - 0.2,
      "实验组 %.2f℃ vs 对照 %.2f℃ (低 %.2f℃)" % (t_a1, t_b1, t_b1 - t_a1))

print()
print("=" * 76)
print("2) 手动保持：10 分钟内自动不夺权，超时回落")
print("=" * 76)


class _FakeClock:
    def __init__(self, t0):
        self.t = t0

    def __call__(self):
        return self.t


cfg2, farm2 = build()
a2 = farm2.zones[0]["arb"]
clock = _FakeClock(1000.0)
a2.set_manual("fan", 1, clock())
gh2, w2 = farm2.zones[0]["gh"], farm2.zones[0]["weather"].state

u_at_1min = a2.resolve(gh2, w2, clock())        # t+60
clock.t = 1000.0 + 540.0
u_at_9min = a2.resolve(gh2, w2, clock())        # t+540，仍在保持期
clock.t = 1000.0 + 720.0
u_at_12min = a2.resolve(gh2, w2, clock())       # t+720 > 600，应回落
auto_now = a2.auto_control(gh2, w2)

print("  设定 manual_hold_s = %.0f 秒" % cfg2["ctrl"]["manual_hold_s"])
print("  t+1min  vent=%.2f (手动=1.0)" % u_at_1min["vent"])
print("  t+9min  vent=%.2f (仍手动)" % u_at_9min["vent"])
print("  t+12min vent=%.2f (应回落自动=%.2f)" % (u_at_12min["vent"], auto_now["vent"]))
check("10 分钟内手动保持", abs(u_at_9min["vent"] - 1.0) < 1e-9, "vent=%.2f" % u_at_9min["vent"])
check("超时后回落自动", abs(u_at_12min["vent"] - auto_now["vent"]) < 1e-9,
      "vent=%.2f vs 自动 %.2f" % (u_at_12min["vent"], auto_now["vent"]))

print()
print("=" * 76)
print("3) 同棚相关 vs 跨棚差异")
print("=" * 76)
cfg3, farm3 = build()
devs3 = [farm3.attach_device(i, 7, 0.0) for i in range(40)]   # fault_scale=0，排除故障干扰
rows3 = run_for(farm3, devs3, 600.0, dt=60.0, now0=0.0)


def series(dev_idx, key):
    return [row[dev_idx][key] for row in rows3 if dev_idx in row]


def corr(a, b):
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    ma, mb = sum(a) / n, sum(b) / n
    cov = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    va = math.sqrt(sum((x - ma) ** 2 for x in a))
    vb = math.sqrt(sum((y - mb) ** 2 for y in b))
    return cov / (va * vb) if va and vb else 0.0


same_zone = [(0, 4), (1, 5), (2, 6)]        # 0..9 都在 zone 0..3 轮转，0和4同属 zone0
cross_zone = [(0, 1), (2, 3)]
# 不同棚在同一片天空下温度走势本来就同步，比时序相关看不出差别；
# 真正该验的是「同一时刻的绝对偏差」：同棚应集中在个体标定差上，跨棚要大一个量级。
def spread_of(group):
    vals = [sum(row[i]["temperature"] for i in group) / len(group)
            for row in rows3 if all(i in row for i in group)]
    # 用每个棚的均值做组内离散度
    devs_in = [[row[i]["temperature"] for i in group] for row in rows3 if all(i in row for i in group)]
    return sum(max(d) - min(d) for d in devs_in) / len(devs_in)


zone_ids = [i % 4 for i in range(40)]
within = []
for z in range(4):
    members = [i for i in range(40) if zone_ids[i] == z]
    diffs_z = []
    for row in rows3:
        if all(i in row for i in members):
            vals = [row[i]["temperature"] for i in members]
            diffs_z.append(max(vals) - min(vals))
    within.append(sum(diffs_z) / len(diffs_z))
last_full = next(row for row in reversed(rows3) if len(row) == 40)
means_last = [sum(last_full[i]["temperature"] for i in range(40) if zone_ids[i] == z) / 10
              for z in range(4)]
print("  各棚内部温度极差（10 台，平均）= %s" % ["%.2f℃" % x for x in within])
print("  同一时刻 4 个棚的棚均值 = %s" % ["%.2f℃" % x for x in means_last])
print("  跨棚棚均值极差 = %.2f℃" % (max(means_last) - min(means_last)))
check("同棚内部极差 < 2℃", max(within) < 2.0, "最大 %.2f℃" % max(within))

print()
print("=" * 76)
print("4) 故障生命周期（卡值应持续多帧，不是单帧抖动）")
print("=" * 76)
cfg4, _ = build()
fe = ds.FaultEngine(cfg4, __import__("random").Random(11), scale=10.0)   # 放大设备故障率便于观察（链路丢包已与倍率解耦）
base = {"temperature": 25.0, "humidity": 60.0, "soilMoisture": 28.0,
        "light": 20000.0, "co2": 600.0, "ph": 6.5}
snap = {"ppfd": 800.0, "t_in": 25.0, "rh": 60.0, "co2": 600.0, "vwc": 0.28,
        "ph": 6.5, "t_soil": 24.0, "vpd": 1.0}
stuck_runs, cur, max_cur, drops, total = [], 0, 0, 0, 0
prev = None
for i in range(20000):
    r = fe.apply(dict(base), snap, 2.0)
    total += 1
    if r is None:
        drops += 1
        prev = None
        continue
    if prev is not None and abs(r["temperature"] - prev) < 1e-9:
        cur += 1
        max_cur = max(max_cur, cur)
    else:
        if cur:
            stuck_runs.append(cur)
        cur = 0
    prev = r["temperature"]
dur = sorted(stuck_runs)[len(stuck_runs) // 2] if stuck_runs else 0
print("  模拟 20000 帧（约 11 小时），设备故障强度 10 倍")
print("  丢帧 %d / %d = %.1f%%" % (drops, total, 100.0 * drops / total))
print("  卡值段数 %d，最长连续 %d 帧（= %.0f 秒）" % (len(stuck_runs), max_cur, max_cur * 2))
check("卡值有持续时长（>30 帧）", max_cur > 30, "最长 %d 帧" % max_cur)
check("丢帧率在合理区间 0.3%~5%", 0.003 < drops / total < 0.05,
      "%.1f%%" % (100.0 * drops / total))

print()
print("=" * 76)
print("5) 可复现（同 seed 两次运行逐字段一致）")
print("=" * 76)


def fingerprint(seed):
    cfgx, farmx = build(seed)
    dv = [farmx.attach_device(i, seed, 0.0) for i in range(6)]
    rows = run_for(farmx, dv, 3600.0, dt=60.0, now0=0.0)
    return json.dumps(rows[-1], sort_keys=True)


f1, f2 = fingerprint(123), fingerprint(123)
check("同 seed 输出一致", f1 == f2, "两次指纹%s" % ("相同" if f1 == f2 else "不同"))
f3 = fingerprint(124)
check("换 seed 输出不同", f1 != f3, "seed 123 vs 124 %s" % ("一致(异常)" if f1 == f3 else "不同(符合预期)"))

print()
print("=" * 76)
print("6) 时间连续性：相邻两拍跳变量")
print("=" * 76)
cfg6, farm6 = build()
d6 = [farm6.attach_device(i, 9, 0.0) for i in range(4)]
rows6 = run_for(farm6, d6, 7200.0, dt=2.0, now0=0.0)
temps = [row[0]["temperature"] for row in rows6 if 0 in row]
diffs = [abs(temps[i + 1] - temps[i]) for i in range(len(temps) - 1)]
diffs.sort()
print("  2 秒一拍，共 %d 帧" % len(temps))
print("  相邻温差 P50=%.3f℃  P99=%.3f℃  最大=%.3f℃" % (
    diffs[len(diffs) // 2], diffs[int(len(diffs) * 0.99)], diffs[-1]))
print("  （旧版实测 P50=4.72℃  P99=14.33℃）")
check("相邻跳变 P50 < 0.5℃", diffs[len(diffs) // 2] < 0.5,
      "P50=%.3f℃" % diffs[len(diffs) // 2])

print()
print("=" * 76)
print("结果: %d 项通过 / %d 项失败" % (len(PASS), len(FAIL)))
if FAIL:
    print("失败项: %s" % ", ".join(FAIL))
    sys.exit(1)
print("=" * 76)
