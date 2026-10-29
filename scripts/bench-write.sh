#!/usr/bin/env bash
# 写入路径基准：攒批开 / 攒批关，同一份负载下对比吞吐与落库延迟。
#
# 用法：
#   python scripts/mock-influx-batch-test.py 8086 &     # 先起假 InfluxDB
#   bash scripts/bench-write.sh                          # 跑全部 8 组
#   bash scripts/bench-write.sh single --path=batch --delay=8 --rate=100 --seconds=30
#
# 说明见 src/test/java/com/smartagri/influx/InfluxWriteBenchmark.java 的类注释。
set -u
cd "$(dirname "$0")/.." || exit 1

PORT=${PORT:-8086}
URL="http://127.0.0.1:${PORT}"

# 依赖 jar：直接从已构建的 fat jar 里取，避免依赖 Maven 联网/离线仓库元数据
if [ ! -d target/bench-lib/BOOT-INF/lib ]; then
  if [ ! -f target/smart-agriculture-platform.jar ]; then
    echo "缺少 target/smart-agriculture-platform.jar，先执行：mvn package -DskipTests" >&2
    exit 1
  fi
  mkdir -p target/bench-lib
  (cd target/bench-lib && unzip -q -o ../smart-agriculture-platform.jar 'BOOT-INF/lib/*')
fi

CP="target/classes;target/test-classes;target/bench-lib/BOOT-INF/lib/*"
JAVA="java -Dstdout.encoding=UTF-8 -cp ${CP} com.smartagri.influx.InfluxWriteBenchmark"

# 假服务必须先起来，否则每组都会把 InfluxDB 客户端初始化失败
if ! curl -s --max-time 3 "${URL}/ping" >/dev/null 2>&1; then
  echo "假 InfluxDB 没起。先执行：python scripts/mock-influx-batch-test.py ${PORT} &" >&2
  exit 1
fi

run() {
  local title="$1"; shift
  echo
  echo "########## ${title} ##########"
  $JAVA "$@" 2>&1 | sed -n '/=====/,$p'
}

# single 模式：原样透传参数，方便临时试一组
if [ "${1:-}" = "single" ]; then
  shift
  run "单组" "$@"
  exit 0
fi

# ---------- 一、稳态：当前真实负载 100 点/秒（200 台设备 × 每 2 秒一报） ----------
run "1/8 攒批关 · 100 点/秒 · 服务端 8ms" --path=perpoint --delay=8 --rate=100 --seconds=30
run "2/8 攒批开 · 100 点/秒 · 服务端 8ms" --path=batch    --delay=8 --rate=100 --seconds=30

# ---------- 二、超载：负载翻倍到 200 点/秒 ----------
run "3/8 攒批关 · 200 点/秒 · 服务端 8ms" --path=perpoint --delay=8 --rate=200 --seconds=20
run "4/8 攒批开 · 200 点/秒 · 服务端 8ms" --path=batch    --delay=8 --rate=200 --seconds=20

# ---------- 三、服务端变慢：单次写从 8ms 涨到 20ms ----------
run "5/8 攒批关 · 100 点/秒 · 服务端 20ms" --path=perpoint --delay=20 --rate=100 --seconds=20
run "6/8 攒批开 · 100 点/秒 · 服务端 20ms" --path=batch    --delay=20 --rate=100 --seconds=20

# ---------- 四、饱和：不限速，测两条路径各自的吞吐天花板 ----------
run "7/8 攒批关 · 饱和 · 服务端 8ms" --path=perpoint --delay=8 --rate=0 --seconds=15 --latency=false
run "8/8 攒批开 · 饱和 · 服务端 8ms" --path=batch    --delay=8 --rate=0 --seconds=15 --latency=false --queue=500000

echo
echo "跑完。注意：服务端处理耗时是脚本注入的假设值，不是真实 InfluxDB 的实测延迟。"
