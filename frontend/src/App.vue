<template>
  <div class="app">
    <!-- 登录遮罩 -->
    <div v-if="!loggedIn" class="login-mask">
      <el-card class="login-card" shadow="always">
        <h2>🌿 智慧农业云管端一体化平台</h2>
        <p class="muted">Cloud–Edge–Terminal Integrated Platform</p>
        <el-input v-model="form.username" placeholder="用户名" />
        <el-input v-model="form.password" type="password" placeholder="密码" @keyup.enter="doLogin" show-password />
        <div v-if="loginErr" class="login-err">{{ loginErr }}</div>
        <el-button type="primary" style="width:100%" @click="doLogin" :loading="logging">登 录</el-button>
        <p class="muted" style="margin-top:6px">默认账户：admin / admin123</p>
      </el-card>
    </div>

    <template v-else>
      <!-- 顶部状态栏 -->
      <header class="topbar">
        <div class="brand">🌿 智慧农业云管端一体化平台</div>
        <div class="status">
          <span class="chip"><span class="dot" :class="wsConnected ? 'on' : 'off'"></span>WS {{ wsConnected ? '已连接' : '断开' }}</span>
          <el-tag size="small" :type="influxMode === 'real' ? 'success' : 'warning'">InfluxDB {{ influxMode }}</el-tag>
          <el-tag size="small" :type="redisMode === 'real' ? 'success' : 'warning'">Redis {{ redisMode }}</el-tag>
          <span class="chip">在线设备 <b>{{ sysStatus.online ?? 0 }}</b></span>
          <span class="chip">连接 <b>{{ sysStatus.connections ?? 0 }}</b></span>
          <span class="chip">大屏 <b>{{ sysStatus.dashboards ?? 0 }}</b></span>
          <span class="chip">遥测/秒 <b>{{ health.throughput?.telemetryPerSecond ?? 0 }}</b></span>
          <el-button size="small" text type="primary" @click="logout">退出</el-button>
        </div>
      </header>

      <el-tabs v-model="activeTab" class="tabs">
        <el-tab-pane label="总览大屏" name="overview" />
        <el-tab-pane label="设备台账" name="devices" />
        <el-tab-pane label="告警中心" name="alerts" />
        <el-tab-pane label="规则管理" name="rules" />
        <el-tab-pane label="设备控制" name="control" />
        <el-tab-pane label="模拟器控制台" name="sim" />
      </el-tabs>

      <main class="content">
        <DashboardOverview v-if="activeTab === 'overview'" />
        <DeviceRegistry v-else-if="activeTab === 'devices'" />
        <AlertCenter v-else-if="activeTab === 'alerts'" />
        <RuleManager v-else-if="activeTab === 'rules'" />
        <DeviceControl v-else-if="activeTab === 'control'" />
        <SimulatorConsole v-else-if="activeTab === 'sim'" />
      </main>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, provide, onMounted, onBeforeUnmount } from 'vue'
import { api, WS_URL } from './api'
import { ElMessage } from 'element-plus'
import DashboardOverview from './components/DashboardOverview.vue'
import DeviceRegistry from './components/DeviceRegistry.vue'
import AlertCenter from './components/AlertCenter.vue'
import RuleManager from './components/RuleManager.vue'
import DeviceControl from './components/DeviceControl.vue'
import SimulatorConsole from './components/SimulatorConsole.vue'

const form = reactive({ username: 'admin', password: 'admin123' })
const loginErr = ref('')
const logging = ref(false)
const token = ref(localStorage.getItem('agri_token') || '')
const loggedIn = ref(!!token.value)
const activeTab = ref('overview')
const wsConnected = ref(false)

const live = reactive({ byDevice: {} })
const alerts = ref([])
const commands = ref([])
const aiExplain = reactive({})
const sysStatus = reactive({ online: 0, connections: 0, dashboards: 0 })
const health = reactive({ influx: { mode: 'memory' }, redis: { mode: 'memory' }, throughput: { telemetryPerSecond: 0 } })

provide('live', live)
provide('sysStatus', sysStatus)
provide('health', health)
provide('alerts', alerts)
provide('commands', commands)
provide('aiExplain', aiExplain)

const influxMode = computed(() => health.influx?.mode || 'memory')
const redisMode = computed(() => health.redis?.mode || 'memory')

let ws = null
let healthTimer = null

function connectWS() {
  disconnectWS()
  try {
    ws = new WebSocket(WS_URL(token.value))
    ws.onopen = () => { wsConnected.value = true }
    ws.onclose = () => { wsConnected.value = false; if (loggedIn.value) setTimeout(connectWS, 2000) }
    ws.onmessage = (ev) => onWsMessage(ev.data)
  } catch (e) { wsConnected.value = false }
}
function disconnectWS() {
  if (ws) { ws.onclose = null; ws.close(); ws = null }
  wsConnected.value = false
}
function onWsMessage(raw) {
  let msg
  try { msg = JSON.parse(raw) } catch { return }
  if (msg.type === 'telemetry') {
    live.byDevice[msg.deviceId] = { ts: msg.ts, metrics: msg.metrics }
  } else if (msg.type === 'status') {
    Object.assign(sysStatus, msg.data)
  } else if (msg.type === 'alert') {
    alerts.value.unshift(msg.data)
    if (alerts.value.length > 200) alerts.value.pop()
  } else if (msg.type === 'command') {
    upsertCommand(msg.data)
  } else if (msg.type && msg.type.startsWith('ai_explain')) {
    handleAiExplain(msg.type, msg.data)
  }
}

/**
 * AI 解读流式消息：start 清空 → chunk 追加 → done 用完整正文覆盖并落状态。
 * 以 cid 为键，同一条告警重复解读不会串台。
 */
function handleAiExplain(type, d) {
  if (!d || !d.cid) return
  const cur = aiExplain[d.cid] || (aiExplain[d.cid] = {
    content: '', status: 'STREAMING', model: '', latencyMs: 0, error: null
  })
  if (type === 'ai_explain_start') {
    cur.content = ''
    cur.status = 'STREAMING'
    cur.error = null
    if (d.model) cur.model = d.model
  } else if (type === 'ai_explain_chunk') {
    cur.content += (d.delta || '')
  } else if (type === 'ai_explain_done') {
    cur.status = d.status || 'DONE'
    cur.model = d.model || cur.model
    cur.latencyMs = d.latencyMs || 0
    if (d.content) cur.content = d.content
    cur.error = d.error || null
  }
}

function upsertCommand(cmd) {
  if (!cmd || !cmd.id) return
  const i = commands.value.findIndex((c) => c.id === cmd.id)
  if (i >= 0) commands.value.splice(i, 1, cmd)
  else commands.value.unshift(cmd)
  if (commands.value.length > 300) commands.value.length = 300
}

async function doLogin() {
  loginErr.value = ''
  logging.value = true
  try {
    const { data } = await api.login(form.username, form.password)
    token.value = data.data.token
    localStorage.setItem('agri_token', token.value)
    loggedIn.value = true
    start()
  } catch (e) {
    loginErr.value = (e.response && e.response.data && e.response.data.message) || '登录失败'
  } finally {
    logging.value = false
  }
}
function logout() {
  localStorage.removeItem('agri_token')
  token.value = ''
  loggedIn.value = false
  disconnectWS()
  healthTimer && clearInterval(healthTimer)
}

async function loadHealth() {
  try { const { data } = await api.health(); Object.assign(health, data.data) } catch {}
}

function start() {
  connectWS()
  loadHealth()
  healthTimer = setInterval(loadHealth, 3000)
}

onMounted(() => { if (loggedIn.value) start() })
onBeforeUnmount(() => { disconnectWS(); healthTimer && clearInterval(healthTimer) })
</script>

<style scoped>
.app { min-height: 100vh; }
.login-mask { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; z-index: 50; }
.login-card { width: 340px; display: flex; flex-direction: column; gap: 12px; padding: 24px; }
.login-card h2 { font-size: 18px; text-align: center; }
.login-err { color: var(--el-color-danger); font-size: 12px; }
.topbar { display: flex; justify-content: space-between; align-items: center; padding: 12px 20px; border-bottom: 1px solid var(--line); }
.brand { font-size: 18px; font-weight: 700; }
.status { display: flex; align-items: center; gap: 12px; font-size: 13px; color: var(--muted); }
.status b { color: var(--text); }
.chip { display: inline-flex; align-items: center; gap: 6px; }
.tabs { padding: 0 16px; }
.content { padding: 6px 14px 18px; height: calc(100vh - 110px); overflow: auto; }
</style>
