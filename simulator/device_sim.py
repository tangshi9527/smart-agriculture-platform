#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
智慧农业 - 设备模拟器（脚本版，零依赖）

以 MQTT 客户端身份连接到平台的 Netty MQTT 接入层，模拟大量传感器设备并发接入：
  - 每台设备周期性上报遥测（温度/湿度/土壤湿度/光照/CO2/pH），设备标识写在 topic 中
  - 连接保活（PINGREQ，等价心跳），服务端回 PINGRESP
  - 支持故障注入（按概率触发高温，用于检验告警链路）
  - 全部参数可通过命令行调整，后端可将其作为子进程拉起并由前端界面下发参数

    依赖: 仅 Python 3 标准库（socket / struct / threading / select / random / json / time）
      无需 pip install，任何 Python 3.x 可直接运行。

    ⚠️ 运行强度与机器温度:
      默认仅 200 设备 / 200 连接，足以撑起大屏演示且 CPU/内存占用很低。
      1000 连接属于压力测试档，会持续拉高 CPU 占用、使风扇长时间满转，
      散热不佳的机器可能过热停机。如确需大规模，请分批启动并监控机器温度。

示例:
  python device_sim.py                      # 默认 200 设备 / 200 连接（推荐）
  python device_sim.py --devices 500 --connections 1000 --connect-pace-ms 5   # 压力测试档（需关注温度）
"""
import argparse
import json
import os
import random
import select
import socket
import struct
import sys
import threading
import time

LOG_LOCK = threading.Lock()


def log_info(msg):
    with LOG_LOCK:
        print("[device_sim] %s" % msg, flush=True)


# ---------------------------------------------------------------------------
# 极简 MQTT 3.1.1 客户端（仅实现 CONNECT / PUBLISH / PINGREQ，零依赖）
# ---------------------------------------------------------------------------

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
    def __init__(self, idx, device_id, host, port, keepalive, username, password):
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
        # 执行器本地状态：风扇/灌溉阀/补光灯/水泵（0=关 1=开）
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
        """执行一个云端下发的控制动作，更新本地执行器状态并返回 (ok, state)。"""
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


# ---------------------------------------------------------------------------
# 主逻辑
# ---------------------------------------------------------------------------

def make_telemetry(rnd, base_temp, amp, controls):
    temp = base_temp + rnd.uniform(-amp, amp)
    t = {
        "temperature": round(temp, 2),
        "humidity": round(50 + rnd.uniform(0, 40), 2),
        "soilMoisture": round(30 + rnd.uniform(0, 50), 2),
        "light": round(rnd.uniform(0, 100000), 1),
        "co2": round(400 + rnd.uniform(0, 800), 1),
        "ph": round(6 + rnd.uniform(0, 1.5), 2),
    }
    # 把执行器开关状态一并上报，使云端影子持续反映设备真实状态（闭环可见）
    t.update(controls)
    return t


def main():
    ap = argparse.ArgumentParser(description="智慧农业设备模拟器（零依赖）")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--devices", type=int, default=200)
    ap.add_argument("--connections", type=int, default=200)
    ap.add_argument("--connect-pace-ms", type=float, default=2.0,
                    help="建连节奏限速：每条连接间隔(ms)，避免启动瞬间冲击 CPU/broker")
    ap.add_argument("--interval", type=int, default=2000, help="上报间隔(ms)")
    ap.add_argument("--heartbeat", type=int, default=15000, help="心跳/keepalive 间隔(ms)")
    ap.add_argument("--fault-rate", type=float, default=0.04, help="高温故障注入概率 0~1")
    ap.add_argument("--base-temp", type=float, default=24.0)
    ap.add_argument("--temp-amplitude", type=float, default=8.0)
    ap.add_argument("--username", default=os.environ.get("MQTT_SIM_USERNAME", "simulator"))
    ap.add_argument("--password", default=os.environ.get("MQTT_SIM_SECRET", "sim-secret-2026-change-me"))
    ap.add_argument("--duration", type=int, default=0, help="运行时长(s)，0=不限时")
    args = ap.parse_args()

    rnd = random.Random()
    stop = {"flag": False}
    clients = []
    lock = threading.Lock()

    log_info("启动模拟器: %d 设备 / %d 连接 -> %s:%d" % (
        args.devices, args.connections, args.host, args.port))

    def report_all():
        started = False
        while not stop["flag"]:
            with lock:
                snapshot = list(clients)
            if not snapshot:
                # 尚无就绪连接：短暂等待（避免空转，也避免按 interval 空等）
                time.sleep(0.2)
                continue
            if not started:
                started = True
                log_info("开始上报遥测（当前已就绪 %d 台设备）" % len(snapshot))
            for c in snapshot:
                t = make_telemetry(rnd, args.base_temp, args.temp_amplitude, c.controls)
                if rnd.random() < args.fault_rate:
                    t["temperature"] = round(t["temperature"] + 12 + rnd.uniform(0, 6), 2)
                topic = "device/%s/telemetry" % c.device_id
                c.publish(topic, json.dumps(t).encode("utf-8"))
            time.sleep(args.interval / 1000.0)

    def keepalive_all():
        while not stop["flag"]:
            with lock:
                snapshot = list(clients)
            for c in snapshot:
                if time.time() - c.last_send >= (args.heartbeat / 1000.0) / 2.0:
                    c.ping()
            time.sleep(max(1, (args.heartbeat / 1000) // 2))

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

    import signal
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
        c = DeviceClient(i, device_id, args.host, args.port,
                         max(10, args.heartbeat // 1000), args.username, args.password)
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
