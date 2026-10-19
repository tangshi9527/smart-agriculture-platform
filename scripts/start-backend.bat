@echo off
cd /d "%~dp0.."
echo ==^> 构建后端
call mvnw.cmd -q -DskipTests package
echo ==^> 启动后端 (http://localhost:8080, MQTT :1883)
rem JVM 调优：G1GC + 堆上限 4G，避免内存无界增长触发 GC 风暴把 CPU 占满（曾致风扇满转停机）
java -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms512m -Xmx4g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heapdump.hprof -jar target/*.jar
