# -*- coding: utf-8 -*-
"""
极简 MQTT 3.1.1 broker（仅为验证 device_sim.py 的上报链路，不是生产实现）

支持 CONNECT / PUBLISH(QoS0) / SUBSCRIBE / PINGREQ / DISCONNECT，
统计收到多少设备、多少帧、字段是否齐全，并把下行指令下发到指定设备。

用法:
    python simulator/mock_broker.py --port 1883 --duration 45 --inject-command device-00000:FAN_ON

    --inject-command 会在指定秒数后向 device/{id}/command 发一条控制指令，
    用来验证「云端下发 → 设备执行 → 遥测变化」的闭环是否真的接上了。
"""
import argparse
import json
import socket
import struct
import threading
import time

LOCK = threading.Lock()
# 订阅表: socket -> [topic filter]，下发指令时按它转发（否则新开连接发 PUBLISH 设备根本收不到）
SUBS = {}
STATS = {
    "connects": 0,
    "connacked": 0,
    "subscribes": 0,
    "publishes": 0,
    "pings": 0,
    "disconnects": 0,
    "devices": set(),
    "topics": {},
    "samples": [],
    "bad_json": 0,
    "field_hist": {},
    "cmd_ack": 0,
}


def enc_len(n):
    out = b""
    while True:
        d = n % 128
        n //= 128
        if n > 0:
            d |= 0x80
        out += bytes([d])
        if n == 0:
            return out


def enc_str(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def read_packet(sock, buf):
    """从流里读一个完整包，返回 (包字节, 剩余缓冲) 或 (None, buf)。"""
    while True:
        if len(buf) >= 2:
            mult, val, pos = 1, 0, 1
            ok = False
            while pos < len(buf):
                e = buf[pos]
                val += (e & 0x7F) * mult
                mult *= 128
                pos += 1
                if (e & 0x80) == 0:
                    ok = True
                    break
            if ok and len(buf) >= pos + val:
                return buf[:pos + val], buf[pos + val:]
        try:
            chunk = sock.recv(65536)
        except Exception:
            return None, buf
        if not chunk:
            return None, buf
        buf += chunk


def parse_publish(pkt):
    pos = 1
    while pkt[pos] & 0x80:
        pos += 1
    pos += 1
    tl = struct.unpack(">H", pkt[pos:pos + 2])[0]
    pos += 2
    topic = pkt[pos:pos + tl].decode("utf-8", "replace")
    pos += tl
    qos = (pkt[0] >> 1) & 0x03
    if qos > 0:
        pos += 2
    return topic, pkt[pos:]


def serve_conn(sock, addr, stop):
    buf = b""
    try:
        while not stop.is_set():
            pkt, buf = read_packet(sock, buf)
            if pkt is None:
                break
            ptype = pkt[0] >> 4
            if ptype == 1:      # CONNECT
                with LOCK:
                    STATS["connects"] += 1
                sock.sendall(bytes([0x20, 0x02, 0x00, 0x00]))
                with LOCK:
                    STATS["connacked"] += 1
            elif ptype == 8:    # SUBSCRIBE
                pid = struct.unpack(">H", pkt[2:4])[0]
                # 解析 topic filter（可多个），记进订阅表
                pos, filters = 4, []
                while pos + 2 <= len(pkt):
                    tl = struct.unpack(">H", pkt[pos:pos + 2])[0]
                    pos += 2
                    if pos + tl > len(pkt):
                        break
                    filters.append(pkt[pos:pos + tl].decode("utf-8", "replace"))
                    pos += tl + 1        # 跳过请求 qos
                with LOCK:
                    SUBS[sock] = filters
                    STATS["subscribes"] += 1
                sock.sendall(bytes([0x90, 0x03]) + struct.pack(">H", pid) + bytes([0x00]))
            elif ptype == 3:    # PUBLISH
                topic, payload = parse_publish(pkt)
                with LOCK:
                    STATS["publishes"] += 1
                    STATS["topics"][topic] = STATS["topics"].get(topic, 0) + 1
                    if "/telemetry" in topic:
                        STATS["devices"].add(topic.split("/")[1])
                        try:
                            d = json.loads(payload.decode("utf-8"))
                            for k in d:
                                STATS["field_hist"][k] = STATS["field_hist"].get(k, 0) + 1
                            if len(STATS["samples"]) < 3:
                                STATS["samples"].append(d)
                        except Exception:
                            STATS["bad_json"] += 1
                    elif topic.endswith("/command/response"):
                        STATS["cmd_ack"] += 1
                        if len(STATS["samples"]) < 5:
                            STATS["samples"].append({"__cmdresp__": json.loads(payload.decode("utf-8"))})
            elif ptype == 12:   # PINGREQ
                sock.sendall(bytes([0xD0, 0x00]))
                with LOCK:
                    STATS["pings"] += 1
            elif ptype == 14:   # DISCONNECT
                with LOCK:
                    STATS["disconnects"] += 1
                break
    except Exception:
        pass
    finally:
        with LOCK:
            SUBS.pop(sock, None)
        try:
            sock.close()
        except Exception:
            pass


def inject_command(port, device_id, action, delay):
    time.sleep(delay)
    body = json.dumps({"cmdId": "cmd-%d" % int(time.time()), "action": action,
                       "deviceId": device_id}).encode("utf-8")
    topic = "device/%s/command" % device_id
    pkt = bytes([0x30]) + enc_len(len(enc_str(topic)) + len(body)) + enc_str(topic) + body
    with LOCK:
        targets = [s for s, fs in SUBS.items() if topic in fs]
    if not targets:
        print("[broker] 没有连接订阅 %s，下发无意义" % topic, flush=True)
        return
    ok = 0
    for s in targets:
        try:
            s.sendall(pkt)
            ok += 1
        except Exception:
            pass
    print("[broker] 已向 %d 条订阅连接下发 %s=%s" % (ok, device_id, action), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--duration", type=int, default=45)
    ap.add_argument("--inject-command", default=None,
                    help="形如 device-00000:FAN_ON，延迟 --inject-delay 秒后下发")
    ap.add_argument("--inject-delay", type=float, default=20.0)
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(512)
    srv.settimeout(0.5)
    stop = threading.Event()
    print("[broker] 监听 0.0.0.0:%d，运行 %d 秒" % (args.port, args.duration), flush=True)

    if args.inject_command:
        dev, act = args.inject_command.split(":")
        threading.Thread(target=inject_command, args=(args.port, dev, act, args.inject_delay),
                         daemon=True).start()

    t_end = time.time() + args.duration
    while time.time() < t_end:
        try:
            c, a = srv.accept()
            threading.Thread(target=serve_conn, args=(c, a, stop), daemon=True).start()
        except socket.timeout:
            continue
    stop.set()

    print("\n================ MQTT 链路统计 ================", flush=True)
    print("CONNECT 收到 %d / 已回 CONNACK %d" % (STATS["connects"], STATS["connacked"]))
    print("SUBSCRIBE %d   PUBLISH %d   PINGREQ %d   DISCONNECT %d" % (
        STATS["subscribes"], STATS["publishes"], STATS["pings"], STATS["disconnects"]))
    print("上报设备数 %d   帧解析失败 %d   指令回执 %d" % (
        len(STATS["devices"]), STATS["bad_json"], STATS["cmd_ack"]))
    print("\n字段出现次数（应含 temperature/humidity/soilMoisture/light/co2/ph/fan/valve/lamp/pump）:")
    for k, v in sorted(STATS["field_hist"].items(), key=lambda x: -x[1]):
        print("  %-14s %d" % (k, v))
    print("\n样本帧:")
    for s in STATS["samples"][:3]:
        print(" ", json.dumps(s, ensure_ascii=False))
    print("=============================================", flush=True)


if __name__ == "__main__":
    main()
