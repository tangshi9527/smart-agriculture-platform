import axios from 'axios'

const BASE = (import.meta.env.VITE_API_BASE || 'http://localhost:8080').replace(/\/$/, '')
const http = axios.create({ baseURL: BASE, timeout: 15000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('agri_token')
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

export const api = {
  login: (username, password) => http.post('/auth/login', { username, password }),
  overview: () => http.get('/api/dashboard/overview'),
  health: () => http.get('/api/system/health'),

  // 设备与设备影子
  devices: () => http.get('/api/devices'),
  deviceShadow: (id) => http.get('/api/devices/' + id + '/shadow'),
  deviceKeys: () => http.get('/api/devices/keys'),
  genKey: (id) => http.post('/api/devices/' + id + '/key'),
  disableKey: (id) => http.delete('/api/devices/' + id + '/key'),

  // 告警
  alerts: (status, limit = 100) =>
    http.get('/api/alerts', { params: { status, limit } }),
  ackAlert: (id) => http.post('/api/alerts/' + id + '/ack'),
  ignoreAlert: (id) => http.post('/api/alerts/' + id + '/ignore'),
  alertRules: () => http.get('/api/alerts/rules'),

  // 规则（热更新）
  rules: () => http.get('/api/alerts/rules'),
  saveRule: (rule) => http.post('/api/alerts/rules', rule),
  deleteRule: (id) => http.delete('/api/alerts/rules/' + id),

  // 规则 DSL（高级 / 批量）
  parseDsl: (dsl) => http.post('/api/alerts/rules/dsl/parse', { dsl }),
  applyDsl: (dsl) => http.post('/api/alerts/rules/dsl/apply', { dsl }),

  // 模拟器控制台
  simDefaults: () => http.get('/simulator/defaults'),
  simStart: (cfg) => http.post('/simulator/start', cfg),
  simStop: () => http.post('/simulator/stop'),
  simStatus: () => http.get('/simulator/status'),

  // 设备控制闭环（云端下发 -> 设备执行 -> 回执）
  sendCommand: (body) => http.post('/api/commands', body),
  commands: (deviceId, limit = 100) =>
    http.get('/api/commands', { params: { deviceId, limit } }),
  deviceControls: (id) => http.get('/api/devices/' + id + '/controls'),

  // 时序查询 / 降采样 benchmark
  telemetry: (deviceId, metric, start, stop) =>
    http.get('/api/telemetry/query', { params: { deviceId, metric, start, stop } }),
  benchmark: (deviceId, metric) =>
    http.get('/api/telemetry/benchmark', { params: { deviceId, metric } }),
  agg: (deviceId, metric, start, stop) =>
    http.get('/api/telemetry/agg', { params: { deviceId, metric, start, stop } }),

  // AI 告警解读（结果通过 WebSocket 的 ai_explain_* 消息流式返回）
  aiStatus: () => http.get('/api/ai/status'),
  explainAlert: (body) => http.post('/api/ai/explain', body),
  aiExplanation: (cid) => http.get('/api/ai/explain/' + cid)
}

export const WS_URL = (token) => `${BASE.startsWith('https') ? 'wss' : 'ws'}://${BASE.split('//')[1]}/ws/dashboard?token=${token || ''}`
