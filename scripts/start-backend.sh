#!/usr/bin/env bash
# 启动后端（本地开发）：构建并运行 jar
set -e
cd "$(dirname "$0")/.."
echo "==> 构建后端"
./mvnw -q -DskipTests package
echo "==> 启动后端 (http://localhost:8080, MQTT :1883)"
# JVM 调优：G1GC + 堆上限 4G，避免内存无界增长触发 GC 风暴把 CPU 占满（曾致风扇满转停机）
# MaxGCPauseMillis 约束 GC 停顿；HeapDumpOnOutOfMemoryError 便于事后定位内存问题
java -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms512m -Xmx4g \
     -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heapdump.hprof \
     -jar target/*.jar
