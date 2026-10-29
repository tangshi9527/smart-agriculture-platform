"""最小 InfluxDB v2 HTTP 假服务：只为验证客户端的写入批次行为。

不是为了模拟 InfluxDB 的语义，只要让 influxdb-client-java 正常走完
「查 bucket → 查 task → POST /api/v2/write」这条路，并统计每次写入的点数。
"""
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ORG = {"id": "org-1", "name": "smart-agri", "status": "active"}
BUCKETS = [
    {"id": "b-raw", "orgID": "org-1", "name": "agri-raw", "retentionRules": [
        {"type": "expire", "everySeconds": 2592000}], "createdAt": "2026-01-01T00:00:00Z",
     "updatedAt": "2026-01-01T00:00:00Z"},
    {"id": "b-ds", "orgID": "org-1", "name": "agri-1m", "retentionRules": [
        {"type": "expire", "everySeconds": 31536000}], "createdAt": "2026-01-01T00:00:00Z",
     "updatedAt": "2026-01-01T00:00:00Z"},
]
TASK = {"id": "t-1", "orgID": "org-1", "name": "agri-downsample-1m", "status": "active",
        "flux": "option task = {name: \"agri-downsample-1m\", every: 1m}"}

lock = threading.Lock()
# 运行时可通过 /_mode?m=ok|err400|err500 切换写入行为，用来验证客户端的错误分类
mode = {"write": "ok"}
stats = {"write_calls": 0, "points_total": 0, "batch_sizes": [], "devices": set()}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, code, body=b"", ctype="application/json"):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/_mode":
            from urllib.parse import parse_qs
            q = parse_qs(self.path.split("?", 1)[1]) if "?" in self.path else {}
            new = q.get("m", ["ok"])[0]
            mode["write"] = new
            return self._send(200, json.dumps(mode))
        if p == "/_stats":
            with lock:
                return self._send(200, json.dumps({
                    "write_calls": stats["write_calls"],
                    "points_total": stats["points_total"],
                    "batch_sizes": stats["batch_sizes"][-20:],
                    "batch_count": len(stats["batch_sizes"]),
                    "device_count": len(stats["devices"]),
                }))
        if p == "/ping":
            return self._send(204)
        if p == "/health":
            return self._send(200, json.dumps({"status": "pass"}))
        if p == "/api/v2/orgs":
            return self._send(200, json.dumps({"orgs": [ORG], "links": {}}))
        if p == "/api/v2/buckets":
            return self._send(200, json.dumps({"buckets": BUCKETS, "links": {}}))
        if p == "/api/v2/tasks":
            return self._send(200, json.dumps({"tasks": [TASK], "links": {}}))
        if p == "/api/v2/query":
            # 探活查询：返回空的 annotated CSV，让客户端认为"查得到但没数据"
            return self._send(200, "\r\n", "text/csv")
        return self._send(200, json.dumps({"links": {}}))

    def do_POST(self):
        p = self.path.split("?")[0]
        n = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(n) if n else b""
        if p == "/api/v2/write":
            if mode["write"] == "err500":
                return self._send(500, json.dumps({"code": "internal error", "message": "simulated outage"}))
            if mode["write"] == "err400":
                return self._send(400, json.dumps({"code": "invalid",
                                                   "message": "partial write: field type conflict"}))
            lines = [l for l in body.decode("utf-8", "ignore").split("\n") if l.strip()]
            devices = set()
            for l in lines:
                m = re.search(r"device=([^ ,]+)", l)
                if m:
                    devices.add(m.group(1))
            with lock:
                stats["write_calls"] += 1
                stats["points_total"] += len(lines)
                stats["batch_sizes"].append(len(lines))
                stats["devices"].update(devices)
            return self._send(204)
        if p == "/api/v2/query":
            return self._send(200, "\r\n", "text/csv")
        return self._send(201, json.dumps({"id": "new-1", **ORG}))

    def do_PATCH(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        if n:
            self.rfile.read(n)
        return self._send(200, json.dumps({"id": "b-raw", "name": "agri-raw"}))


class StatsHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        with lock:
            body = json.dumps({
                "write_calls": stats["write_calls"],
                "points_total": stats["points_total"],
                "batch_sizes": stats["batch_sizes"],
                "device_count": len(stats["devices"]),
            }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8086
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
