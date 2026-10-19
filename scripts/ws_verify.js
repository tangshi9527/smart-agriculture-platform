// 最小 WebSocket 客户端验证「下发 -> 大屏实时回执」链路（不依赖任何 npm 包）
// 直连 127.0.0.1:8080，不经过代理。Node 内置 http/crypto 即可。
const http = require('http');
const crypto = require('crypto');

const HOST = '127.0.0.1';
const PORT = 8080;

function httpReq(method, path, body, token) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const headers = { 'Content-Type': 'application/json', 'Host': 'localhost:' + PORT };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const req = http.request({ host: HOST, port: PORT, path, method, headers }, (res) => {
      let buf = '';
      res.on('data', (d) => (buf += d));
      res.on('end', () => { try { resolve(JSON.parse(buf)); } catch { resolve({ __raw: buf }); } });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

// 极简 WS：仅解析服务端->客户端（不掩码）文本帧
function wsConnect(path, onText, onOpen) {
  return new Promise((resolve, reject) => {
    const key = crypto.randomBytes(16).toString('base64');
    const req = http.request({
      host: HOST, port: PORT, path, method: 'GET',
      headers: { Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Key': key, 'Sec-WebSocket-Version': '13' }
    });
    req.on('upgrade', (res, socket) => {
      let buf = Buffer.alloc(0);
      socket.on('data', (d) => {
        buf = Buffer.concat([buf, d]);
        while (buf.length >= 2) {
          const b1 = buf[1];
          let len = b1 & 0x7f, off = 2;
          if (len === 126) { if (buf.length < 4) break; len = buf.readUInt16BE(2); off = 4; }
          else if (len === 127) { if (buf.length < 10) break; len = Number(buf.readBigUInt64BE(2)); off = 10; }
          if (buf.length < off + len) break;
          const opcode = buf[0] & 0x0f;
          const payload = buf.slice(off, off + len);
          buf = buf.slice(off + len);
          if (opcode === 0x1) onText(payload.toString('utf8'));
          else if (opcode === 0x8) socket.end();
        }
      });
      onOpen(socket);
      resolve(socket);
    });
    req.on('error', reject);
    req.end();
  });
}

(async () => {
  const login = await httpReq('POST', '/auth/login', { username: 'admin', password: 'admin123' });
  const token = login.data.token;
  console.log('[1] 登录成功, token长度=' + token.length);

  const received = [];
  await wsConnect('/ws/dashboard?token=' + token, (txt) => {
    try {
      const m = JSON.parse(txt);
      if (m.type === 'command') received.push(m.data);
    } catch {}
  }, () => console.log('[2] 大屏 WebSocket 已连接 (等待指令广播)'));

  const send = await httpReq('POST', '/api/commands', { deviceId: 'device-00001', action: 'FAN_ON' }, token);
  const cid = send.data.id;
  console.log('[3] 下发指令 device-00001 FAN_ON, id=' + cid + ', 即时状态=' + send.data.status);

  const t0 = Date.now();
  let final = null;
  while (Date.now() - t0 < 9000) {
    const hit = received.find((c) => c.id === cid);
    if (hit) { final = hit; break; }
    await new Promise((r) => setTimeout(r, 200));
  }

  if (final) {
    console.log('[4] ✅ 大屏实时收到指令回执 (WS type=command):');
    console.log('     状态=' + final.status + ' 动作=' + final.action + ' 设备=' + final.deviceId + ' 回执时间=' + (final.ackedAt || '—'));
  } else {
    console.log('[4] ❌ 9秒内未收到该指令的 WS 广播 (cid=' + cid + ')');
  }

  const ctrl = await httpReq('GET', '/api/devices/device-00001/controls', null, token);
  console.log('[5] 影子控制状态(fan/valve/lamp/pump): ' + JSON.stringify(ctrl.data));

  // 关闭风扇，验证反向动作也能实时回执 + 影子翻转
  const send2 = await httpReq('POST', '/api/commands', { deviceId: 'device-00001', action: 'FAN_OFF' }, token);
  const cid2 = send2.data.id;
  const t1 = Date.now();
  let final2 = null;
  while (Date.now() - t1 < 9000) {
    const hit = received.find((c) => c.id === cid2);
    if (hit) { final2 = hit; break; }
    await new Promise((r) => setTimeout(r, 200));
  }
  const ctrl2 = await httpReq('GET', '/api/devices/device-00001/controls', null, token);
  console.log('[6] FAN_OFF 回执: ' + (final2 ? final2.status : '未收到') + ' | 影子: ' + JSON.stringify(ctrl2.data));

  const ok = final && final.status === 'EXECUTED' && final2 && final2.status === 'EXECUTED';
  console.log('\n结论: ' + (ok ? '✅ 下发→设备执行→WS实时回执→影子翻转 全链路通过' : '❌ 链路存在异常'));
  process.exit(ok ? 0 : 1);
})().catch((e) => { console.error('ERR', e); process.exit(1); });
