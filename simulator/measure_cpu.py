"""
measure_cpu.py
模拟器自身开销测量：单步耗时分布 + 用户态 CPU 占用。

为什么单独写这个脚本：
    回答问题「物理仿真有没有把上报节拍拖慢」时，CPU% 是个粗糙指标（受机器
    规格、其他进程干扰），单步耗时的 p50/p99 更能说明问题。两者都给，才好回答。

两个测量都是纯标准库（time.process_time / time.perf_counter），不依赖 psutil，
不调用 PowerShell —— 后者在本环境的 Bash 里会被安全策略拦掉。

注意平台差异：
    `resource` 是 Unix-only 模块，Windows 上 import 直接 ModuleNotFoundError。
    所以这里用 `time.process_time()`：它返回本进程的 CPU 时间（user+sys），
    语义与 `getrusage(RUSAGE_SELF).ru_utime + ru_stime` 一致，且 Windows/Linux 通用。

用法:
    python simulator/measure_cpu.py                  # 200 设备 / 4 棚 / 2000 步
    python simulator/measure_cpu.py --devices 500 --steps 1000
"""
import argparse
import importlib.util
import os
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def _load_sim():
    path = os.path.join(HERE, "device_sim.py")
    spec = importlib.util.spec_from_file_location("device_sim", path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["device_sim"] = mod
    spec.loader.exec_module(mod)
    return mod


ds = _load_sim()


def pct(xs, p):
    s = sorted(xs)
    return s[min(len(s) - 1, int(p / 100.0 * len(s)))]


def measure_real(devices, duration, port):
    """真实节拍测量：同进程跑 device_sim.main()，含 MQTT 发送与建连。

    与微基准的区别：微基准是「连续跑不停」，这里是「每 2 秒跑一次」，
    所以 process_time 增量除以墙钟才是**真实占用**。
    """
    print("=" * 72)
    print("真实节拍测量：%d 设备，运行 %d 秒，含 MQTT 上报" % (devices, duration))
    print("=" * 72)

    broker = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "mock_broker.py"),
         # broker 必须比设备端活得久：设备端 duration 之外还有建连与断开的尾巴，
         # 提前退出会让后半段 connect 被拒，统计就不完整了
         "--port", str(port), "--duration", str(duration + 25)],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    time.sleep(1.2)      # 等 broker 监听就绪

    argv = ["device_sim.py", "--host", "127.0.0.1", "--port", str(port),
            "--devices", str(devices), "--connections", str(devices),
            "--duration", str(duration), "--connect-pace-ms", "2"]
    old_argv = sys.argv
    sys.argv = argv
    c0 = time.process_time()
    w0 = time.perf_counter()
    try:
        ds.main()
    except SystemExit:
        pass
    finally:
        sys.argv = old_argv
        w1 = time.perf_counter()
        c1 = time.process_time()
        # 让 broker 自然跑完 duration 再退出：它是收到 SIGTERM 就死、来不及打统计的，
        # 而统计正是「链路真的通了、不是空转」的凭证
        try:
            blog, _ = broker.communicate(timeout=duration + 40)
        except subprocess.TimeoutExpired:
            broker.kill()
            blog, _ = broker.communicate()

    cpu, wall = c1 - c0, w1 - w0
    # broker 侧的收包统计，用来证明链路真的通了（不然 CPU 低可能只是没在发）
    conn = [ln for ln in blog.splitlines() if "CONNECT" in ln]
    pub = [ln for ln in blog.splitlines() if "PUBLISH" in ln]
    print()
    if conn:
        print("[broker 侧] %s" % conn[0].strip())
    if pub:
        print("           %s" % pub[0].strip())
    print("[真实运行] 墙钟 %.1f 秒，CPU %.2f 秒 -> 单核占用 **%.3f%%**"
          % (wall, cpu, 100.0 * cpu / wall))
    print("          折合每设备每秒 %.3f ms CPU" % (1000.0 * cpu / wall / max(1, devices)))
    print("          这个数字包含建连、MQTT 打包发送与物理计算，是本模拟器的真实开销；")
    print("          与后端 Java 进程的 CPU 占用无关，两者不可混用。")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--devices", type=int, default=200)
    ap.add_argument("--zones", type=int, default=4)
    ap.add_argument("--steps", type=int, default=2000, help="测量步数（每步 2 秒仿真）")
    ap.add_argument("--interval", type=float, default=2.0, help="采样间隔（秒，仿真时间）")
    ap.add_argument("--warmup", type=int, default=200)
    ap.add_argument("--real", action="store_true", help="改为真实节拍测量（含 MQTT，需跑满 duration）")
    ap.add_argument("--real-duration", type=int, default=40, help="真实测量的运行秒数")
    ap.add_argument("--real-port", type=int, default=18841, help="真实测量用的 broker 端口")
    args = ap.parse_args()

    if args.real:
        measure_real(args.devices, args.real_duration, args.real_port)
        return

    cfg = ds.build_cfg(scenario="summer", zones=args.zones)
    farm = ds.SimFarm(cfg, 20261029, 0.0, cfg["crop"].get("init_soil"))
    devs = []
    for i in range(args.devices):
        s, f, z = farm.attach_device(i, 20261029, 0.0)
        devs.append((s, f, z))
    extra = set()

    now = time.monotonic()

    def one_step():
        """一次完整的上报节拍：物理推进 + 全部设备采样 + 组帧。"""
        farm.step_physics(args.interval, now)
        snaps = [farm.zone_snapshot(z) for z in range(len(farm.zones))]
        for s, f, z in devs:
            snap = snaps[z]
            r = s.sample(snap, None, args.interval)
            r = f.apply(r, snap, args.interval)
            if r is not None:
                ds.build_frame(r, {}, extra)

    print("=" * 72)
    print("模拟器开销测量：%d 设备 / %d 棚 / 步长 %.1f s" % (args.devices, args.zones, args.interval))
    print("=" * 72)

    for _ in range(args.warmup):
        one_step()

    # ---- 1. 单步耗时分布 ----
    times = []
    c0 = time.process_time()          # 本进程 CPU 时间（user+sys）
    t_wall0 = time.perf_counter()
    for _ in range(args.steps):
        t0 = time.perf_counter()
        one_step()
        times.append((time.perf_counter() - t0) * 1000.0)   # ms
    t_wall1 = time.perf_counter()
    c1 = time.process_time()

    cpu = c1 - c0
    wall = t_wall1 - t_wall0

    print()
    print("[1] 单步耗时（一次节拍 = 物理推进 + %d 台设备采样组帧）" % args.devices)
    print("    均值 %6.2f ms   P50 %6.2f ms   P95 %6.2f ms   P99 %6.2f ms   最大 %6.2f ms"
          % (statistics.fmean(times), pct(times, 50), pct(times, 95), pct(times, 99), max(times)))
    print("    节拍预算 %.0f ms（采样间隔），P99 占用 %.2f%% 的预算"
          % (args.interval * 1000.0, 100.0 * pct(times, 99) / (args.interval * 1000.0)))

    # ---- 2. 进程 CPU ----
    print()
    print("[2] CPU 占用（time.process_time，纯标准库，Windows/Linux 通用）")
    print("    测量窗 %.2f 秒，CPU %.2f 秒" % (wall, cpu))
    print("    单核占用 %.1f%%   （= CPU 秒 / 墙钟秒）" % (100.0 * cpu / wall))
    print("    ^ 注意：这是**满负荷连续跑**的数字（循环里没有 sleep），是计算能力的")
    print("      上限，不是运行时真实占用。真实占用见下一行。")
    per_step = cpu / max(1, args.steps)
    equiv = 100.0 * per_step / args.interval
    print("    真实节拍下的等效占用 = 每步 CPU %.2f ms / 节拍 %.0f ms = %.2f%%"
          % (per_step * 1000.0, args.interval * 1000.0, equiv))
    print("    折合每设备每秒 %.3f ms CPU" % (1000.0 * cpu / wall / max(1, args.devices)))

    # ---- 3. 结论 ----
    print()
    print("[3] 结论")
    p99 = pct(times, 99)
    budget = args.interval * 1000.0
    if p99 < 0.2 * budget:
        print("    P99 单步只占节拍预算的 %.2f%%，物理计算不会拖慢上报节拍。" % (100.0 * p99 / budget))
    else:
        print("    P99 单步已占节拍预算的 %.1f%%，接近上限，需考虑降规模或分帧计算。"
              % (100.0 * p99 / budget))
    print("    这些是**模拟器自身**的开销，与后端进程的 CPU 占用无关，两者不可混用。")


if __name__ == "__main__":
    main()
