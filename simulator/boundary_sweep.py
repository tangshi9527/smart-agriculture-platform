"""
boundary_sweep.py
室外气象边界 × 既有告警规则命中率 的扫描表。

用途：回答「闷热规则 t>24 & h>60 命中 33%，到底是仿真器错了，还是这条规则
      跟盛夏边界冲突」。做法是固定其余一切，只挪室外边界，看命中率怎么变：
      如果温和边界下命中率就掉到 5% 以下，说明错的是「规则 + 边界」的搭配，
      不是物理模型。

另一个用途：证明「temperature>35」和「soilMoisture<25」不是靠边界凑出来的
      ——它们在温和边界下应当归零（不该报），在热边界下应当打得出（该报）。

用法:
    python simulator/boundary_sweep.py                 # 默认 8×6 网格
    python simulator/boundary_sweep.py --quick         # 4×3，约 10 秒
    python simulator/boundary_sweep.py --hours 24 --seed 20261029
"""
import argparse
import importlib.util
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def _load_sim():
    """按文件路径加载 device_sim（它不在包里，直接 import 会失败）。"""
    path = os.path.join(HERE, "device_sim.py")
    spec = importlib.util.spec_from_file_location("device_sim", path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["device_sim"] = mod
    spec.loader.exec_module(mod)
    return mod


ds = _load_sim()

# 三条预置规则，与后端 DataInitializer 保持一致
RULES = [
    ("temperature>35", lambda r: r["temperature"] > 35.0),
    ("soilMoisture<25", lambda r: r["soilMoisture"] < 25.0),
    ("t>24 & h>60", lambda r: r["temperature"] > 24.0 and r["humidity"] > 60.0),
]


def run_point(t_mean, rh_day, hours, seed):
    """跑一个边界点，返回三条规则的命中率（作用在传感器读数上，不是真值）。

    故障强度固定为 0：扫描的是「物理边界能产生多少命中」，随机故障注入
    会往同一份统计里掺入与边界无关的命中，掩盖敏感性。
    """
    cfg = ds.build_cfg(base_temp=t_mean, scenario="summer", zones=1)
    cfg["env"]["rh_day"] = float(rh_day)
    # 夜间取日间 +20（上限 95），保持「昼干夜湿」的定性关系但不对昼夜差
    # 额外引入一个独立扫描维度
    cfg["env"]["rh_night"] = min(95.0, rh_day + 20.0)
    cfg["sim"]["start_hour"] = 6.0

    farm = ds.SimFarm(cfg, seed, 0.0, cfg["crop"].get("init_soil"))
    sensor, fault, zone = farm.attach_device(0, seed, 0.0)

    interval = 60.0
    steps = int(hours * 3600 / interval)
    now = time.monotonic()
    hits = [0] * len(RULES)
    total = 0
    for _ in range(steps):
        farm.step_physics(interval, now)
        snap = farm.zone_snapshot(zone)
        r = sensor.sample(snap, None, interval)
        r = fault.apply(r, snap, interval)
        if r is None:
            continue
        total += 1
        for i, (_, fn) in enumerate(RULES):
            if fn(r):
                hits[i] += 1
    return [100.0 * h / total if total else 0.0 for h in hits], total


def fmt_matrix(title, rows, cols, grid):
    """打印一个矩阵。grid[i][j] 对应 rows[i] × cols[j]。"""
    print()
    print("  " + title)
    head = "    T_mean\\RH  " + "".join("%7.0f" % c for c in cols)
    print(head)
    print("    " + "-" * (len(head) - 4))
    for i, rv in enumerate(rows):
        line = "    %-11.0f" % rv
        for j in range(len(cols)):
            v = grid[i][j]
            # 命中率 >5% 打星号：这是「规则在这条边界下会频繁响」的信号
            line += "%6.2f%s" % (v, "*" if v > 5.0 else " ")
        print(line)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true", help="4×3 网格，约 10 秒")
    ap.add_argument("--hours", type=float, default=24.0, help="每个边界点仿真小时数")
    ap.add_argument("--seed", type=int, default=20261029)
    args = ap.parse_args()

    if args.quick:
        t_list = [15.0, 20.0, 25.0, 28.0]
        rh_list = [50.0, 62.0, 75.0]
    else:
        t_list = [15.0, 17.0, 19.0, 21.0, 23.0, 25.0, 27.0, 28.0]
        rh_list = [50.0, 55.0, 60.0, 65.0, 70.0, 75.0]

    print("=" * 78)
    print("室外边界扫描：T_mean %d 档 × RH_day %d 档，每点 %.0f 仿真小时，seed=%d"
          % (len(t_list), len(rh_list), args.hours, args.seed))
    print("故障强度 = 0（只看物理边界本身能产生多少命中，不掺随机故障）")
    print("=" * 78)

    t0 = time.monotonic()
    # grid[k][i][j]：第 k 条规则、T_mean 第 i 档、RH 第 j 档
    grids = [[[0.0 for _ in rh_list] for _ in t_list] for _ in RULES]
    for i, tm in enumerate(t_list):
        for j, rh in enumerate(rh_list):
            rates, total = run_point(tm, rh, args.hours, args.seed)
            for k in range(len(RULES)):
                grids[k][i][j] = rates[k]
            print("    T_mean=%4.1f RH=%4.1f -> 高温 %5.2f%%  低土壤 %5.2f%%  闷热 %5.2f%%"
                  % (tm, rh, rates[0], rates[1], rates[2]), flush=True)
    cost = time.monotonic() - t0

    for k, (name, _) in enumerate(RULES):
        fmt_matrix("[%s] 命中率 %%" % name, t_list, rh_list, grids[k])

    print()
    print("  （* 表示命中率 > 5%：在这条边界下这条规则会频繁响）")
    print("  总耗时 %.1f 秒 / %d 个边界点" % (cost, len(t_list) * len(rh_list)))

    # ---- 结论 ----
    print()
    print("=" * 78)
    print("结论")
    print("=" * 78)

    mild_rows = [i for i, tm in enumerate(t_list) if tm <= 21.0] or [0]
    last = len(t_list) - 1

    # --- 闷热：必须按维度分开看，否则会把「湿度维度」误读成「温度维度」 ---
    muggy = grids[2]
    j_dry = 0
    i_ref = mild_rows[-1]
    print("  闷热 t>24&h>60 —— 分维度看敏感性（这是本条规则的关键）：")
    print("    只看温度维度（RH 固定 %.0f%%）：" % rh_list[j_dry])
    print("      " + "  ".join("T=%.0f→%.2f%%" % (t_list[i], muggy[i][j_dry]) for i in range(len(t_list))))
    print("    只看湿度维度（T 固定 %.0f℃）：" % t_list[i_ref])
    print("      " + "  ".join("RH=%.0f→%.2f%%" % (rh_list[j], muggy[i_ref][j]) for j in range(len(rh_list))))
    spread_t = (max(muggy[i][j_dry] for i in range(len(t_list)))
                - min(muggy[i][j_dry] for i in range(len(t_list))))
    spread_rh = (max(muggy[i_ref][j] for j in range(len(rh_list)))
                 - min(muggy[i_ref][j] for j in range(len(rh_list))))
    print("    -> 温度跨度 %.2f 个百分点，湿度跨度 %.2f 个百分点：%s" % (
        spread_t, spread_rh,
        "湿度是主导因子" if spread_rh > spread_t else "温度是主导因子"))
    print("       真实含义：这条规则主要在\"湿度过 60%\"时响；而温室夜间相对湿度")
    print("       长期在 60% 以上（作物蒸腾 + 闭棚），所以它不是一个异常判据。")

    # --- 高温：应当温和边界归零、热边界打得响 ---
    hot = grids[0]
    hot_mild = max(hot[i][j] for i in mild_rows for j in range(len(rh_list)))
    hot_hot = max(hot[last])
    print("  高温 temperature>35 ：温和边界(T<=21℃) 最高 %.2f%%  |  最热边界(T=%.0f℃) 最高 %.2f%%"
          % (hot_mild, t_list[last], hot_hot))
    print("    -> %s" % ("温和边界归零、热边界打得响，判据成立，不是靠边界凑出来的"
                       if hot_mild < 0.5 and hot_hot > 0.5
                       else "边界区分度不足，需检查遮阳率与通风能力标定"))

    # --- 低土壤：恒定值必须解释清楚是被什么驱动的 ---
    soil = grids[1]
    soil_all = [soil[i][j] for i in range(len(t_list)) for j in range(len(rh_list))]
    span = max(soil_all) - min(soil_all)
    print("  低土壤 soilMoisture<25 ：全域 %.2f%% ~ %.2f%%（跨度 %.2f 个百分点）"
          % (min(soil_all), max(soil_all), span))
    if span < 0.5:
        print("    -> 与室外气象完全解耦。但**恒定数值不代表物理过程触发了它**：")
        print("       本仿真一天的作物耗水约 2.2 mm（根区 0.20 m → 约 1.1 个百分点/天），")
        print("       从 0.30 掉到 0.25 需 4~5 天连续不灌溉。所以 24 小时窗口内它只反映")
        print("       初始含水率，不反映过程——这条规则本质是多日慢变量判据。")
    else:
        print("    -> 跨边界有波动，需检查灌溉触发条件是否被湿度间接影响")


if __name__ == "__main__":
    main()
