<template>
  <div class="ctl">
    <!-- 设备选择 -->
    <el-card class="block" shadow="never">
      <div class="row">
        <span class="tt">设备控制</span>
        <el-input v-model="deviceId" placeholder="输入设备ID，如 device-00001" style="max-width:260px" @keyup.enter="loadControls" />
        <el-button type="primary" @click="loadControls">查询状态</el-button>
        <el-button @click="pickRandom">随机在线设备</el-button>
        <span class="muted">在线设备数：{{ onlineCount }}</span>
      </div>

      <div class="status-row" v-if="loaded">
        <div v-for="a in actors" :key="a.key" class="ctl-item">
          <div class="ctl-name">{{ a.name }}</div>
          <el-tag :type="isOn(a.key) ? 'success' : 'info'" size="small" effect="dark">
            {{ isOn(a.key) ? '运行中' : '已关闭' }}
          </el-tag>
          <div class="ctl-btns">
            <el-button size="small" type="success" plain @click="send(a.on)">开</el-button>
            <el-button size="small" type="info" plain @click="send(a.off)">关</el-button>
          </div>
        </div>
      </div>
      <el-empty v-else description="请输入设备ID并查询" :image-size="60" />
    </el-card>

    <!-- 指令历史 -->
    <el-card class="block" shadow="never" style="margin-top:12px">
      <div class="row">
        <span class="tt">指令历史（实时）</span>
        <el-checkbox v-model="onlyMine" border size="small">只看本设备</el-checkbox>
        <el-button size="small" text type="primary" @click="refreshHistory">刷新</el-button>
      </div>
      <el-table :data="history" size="small" height="360" style="width:100%;margin-top:8px">
        <el-table-column prop="deviceId" label="设备" width="130" />
        <el-table-column prop="action" label="动作" width="130" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下发时间" width="110">
          <template #default="{ row }">{{ fmtTime(row.issuedAt) }}</template>
        </el-table-column>
        <el-table-column label="回执时间" width="110">
          <template #default="{ row }">{{ row.ackedAt ? fmtTime(row.ackedAt) : '—' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="备注" min-width="160" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, inject, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'

const sysStatus = inject('sysStatus')
const commands = inject('commands') // App 提供的实时指令流（WS 推送 + 本地下发）

const deviceId = ref('device-00001')
const controls = reactive({ fan: 0, valve: 0, lamp: 0, pump: 0 })
const loaded = ref(false)
const onlyMine = ref(true)

const actors = [
  { key: 'fan', name: '通风风扇', on: 'FAN_ON', off: 'FAN_OFF' },
  { key: 'valve', name: '灌溉阀', on: 'VALVE_OPEN', off: 'VALVE_CLOSE' },
  { key: 'lamp', name: '补光灯', on: 'LAMP_ON', off: 'LAMP_OFF' },
  { key: 'pump', name: '水泵', on: 'PUMP_ON', off: 'PUMP_OFF' }
]

const onlineCount = computed(() => sysStatus?.online ?? 0)

function isOn(key) {
  const v = controls[key]
  return v === 1 || v === '1' || v === '1.0'
}

function statusType(s) {
  return { PENDING: 'info', SENT: 'warning', EXECUTED: 'success', FAILED: 'danger', NO_SESSION: 'warning', TIMEOUT: 'danger' }[s] || 'info'
}

function fmtTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

// 指令历史：优先使用 WS 实时流；本设备过滤
const history = computed(() => {
  const list = commands?.value || []
  const filtered = onlyMine.value ? list.filter((c) => c.deviceId === deviceId.value) : list
  return [...filtered].sort((a, b) => (b.issuedAt || 0) - (a.issuedAt || 0)).slice(0, 200)
})

let ctrlTimer = null

async function loadControls() {
  if (!deviceId.value) return
  try {
    const { data } = await api.deviceControls(deviceId.value)
    const c = data.data || {}
    for (const a of actors) controls[a.key] = c[a.key] ?? 0
    loaded.value = true
  } catch {
    loaded.value = false
    ElMessage.warning('查询失败，确认设备ID是否正确')
  }
}

async function pickRandom() {
  try {
    const { data } = await api.devices()
    const arr = data.data || []
    if (!arr.length) { ElMessage.warning('暂无在线设备'); return }
    const pick = arr[Math.floor(Math.random() * arr.length)]
    deviceId.value = pick.deviceId || pick.device_id || Object.values(pick)[0]
    await loadControls()
  } catch { ElMessage.warning('获取设备列表失败') }
}

async function send(action) {
  try {
    const { data } = await api.sendCommand({ deviceId: deviceId.value, action })
    const cmd = data.data
    // 立即把本条插入实时指令流，WS 回执会再更新状态
    upsertLocal(cmd)
    if (cmd.status === 'NO_SESSION') {
      ElMessage.warning('设备当前无连接，指令未下发（已记录为 NO_SESSION）')
    } else {
      ElMessage.success(`指令已下发：${action}`)
    }
  } catch {
    ElMessage.error('下发失败')
  }
}

function upsertLocal(cmd) {
  if (!commands || !commands.value) return
  const i = commands.value.findIndex((c) => c.id === cmd.id)
  if (i >= 0) commands.value.splice(i, 1, cmd)
  else commands.value.unshift(cmd)
  if (commands.value.length > 300) commands.value.length = 300
}

async function refreshHistory() {
  try {
    const { data } = await api.commands(deviceId.value, 100)
    const list = data.data || []
    for (const c of list) upsertLocal(c)
  } catch {}
}

onMounted(() => { loadControls(); ctrlTimer = setInterval(loadControls, 3000) })
onBeforeUnmount(() => { ctrlTimer && clearInterval(ctrlTimer) })
</script>

<style scoped>
.ctl { padding: 4px; }
.block { background: linear-gradient(180deg,#121d30,#16243c); border:1px solid var(--line); }
.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.tt { font-weight: 600; font-size: 15px; }
.muted { color: var(--muted); font-size: 12px; }
.status-row { display: flex; gap: 14px; margin-top: 14px; flex-wrap: wrap; }
.ctl-item { background: rgba(255,255,255,0.03); border:1px solid var(--line); border-radius: 10px; padding: 12px 14px; min-width: 150px; }
.ctl-name { font-weight: 600; margin-bottom: 8px; }
.ctl-btns { margin-top: 10px; display: flex; gap: 8px; }
</style>
