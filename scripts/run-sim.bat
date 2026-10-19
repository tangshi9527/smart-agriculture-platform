@echo off
cd /d "%~dp0.."
echo ==^> 启动设备模拟器（默认 200 设备 / 200 连接，稳定档，CPU/内存占用低）
echo ==^> 如需压力测试请显式加 --connections 1000 --connect-pace-ms 5，并关注机器温度
python simulator/device_sim.py %*
