<template>
  <div class="registry">
    <el-row :gutter="14">
      <el-col :span="13">
        <el-card shadow="never" class="block">
          <template #header>
            <span class="tt">设备影子台账</span>
            <el-button size="small" style="float:right" @click="loadDevices">刷新</el-button>
          </template>
          <el-table :data="devices" height="420" size="small" stripe>
            <el-table-column prop="deviceId" label="设备ID" width="140" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'online' ? 'success' : 'danger'" size="small">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="温度℃" width="90">
              <template #default="{ row }">{{ row.temperature ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="湿度%" width="90">
              <template #default="{ row }">{{ row.humidity ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="最近心跳" min-width="160">
              <template #default="{ row }">
                {{ row.lastHeartbeat ? fmt(row.lastHeartbeat) : '-' }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="11">
        <el-card shadow="never" class="block">
          <template #header>
            <span class="tt">接入密钥管理</span>
            <el-button size="small" style="float:right" @click="loadKeys">刷新</el-button>
          </template>
          <el-form :inline="true" class="key-form">
            <el-form-item label="新设备ID">
              <el-input v-model="newId" placeholder="如 device-10001" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="genKey(newId)" :disabled="!newId">生成密钥</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="keys" height="360" size="small" stripe>
            <el-table-column prop="deviceId" label="设备ID" width="130" />
            <el-table-column label="状态" width="86">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" min-width="150">
              <template #default="{ row }">
                <el-button size="small" type="warning" plain @click="genKey(row.deviceId)">重置</el-button>
                <el-button size="small" type="danger" plain @click="disableKey(row.deviceId)"
                           :disabled="row.status !== 'ENABLED'">停用</el-button>
              </template>
            </el-table-column>
          </el-table>
          <p class="muted" style="margin-top:8px">
            真实设备须以 username=设备ID、password=密钥 连接 MQTT；模拟器使用独立的服务账号一次性放行。
          </p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'

const devices = ref([])
const keys = ref([])
const newId = ref('')
let timer = null
let errShown = false

function errMsg(e) {
  return (e && e.response && e.response.data && e.response.data.message) || (e && e.message) || '未知错误'
}

function fmt(ts) {
  if (!ts) return ''
  return new Date(Number(ts)).toLocaleString('zh-CN', { hour12: false })
}

function normalize(devicesRaw) {
  return (devicesRaw || []).map(d => ({
    deviceId: d.deviceId,
    status: d.status,
    temperature: d.temperature,
    humidity: d.humidity,
    lastHeartbeat: d.lastHeartbeat
  }))
}

async function loadDevices() {
  try {
    const { data } = await api.devices()
    devices.value = normalize(data.data)
    errShown = false
  } catch (e) {
    if (!errShown) { errShown = true; ElMessage.error('设备台账加载失败：' + errMsg(e)) }
  }
}
async function loadKeys() {
  try {
    const { data } = await api.deviceKeys()
    keys.value = data.data || []
  } catch (e) {
    if (!errShown) { errShown = true; ElMessage.error('密钥列表加载失败：' + errMsg(e)) }
  }
}
async function genKey(id) {
  if (!id) return
  try {
    const { data } = await api.genKey(id)
    const secret = data.data.secret
    await loadKeys()
    ElMessage.success(`设备 ${id} 密钥已生成/重置（仅显示一次）：${secret}`)
  } catch (e) {
    ElMessage.error('生成失败')
  }
}
async function disableKey(id) {
  try {
    await api.disableKey(id)
    await loadKeys()
    ElMessage.success(`设备 ${id} 密钥已停用`)
  } catch {}
}

onMounted(() => { loadDevices(); loadKeys(); timer = setInterval(loadDevices, 4000) })
onBeforeUnmount(() => { timer && clearInterval(timer) })
</script>

<style scoped>
.registry { padding: 4px; }
.block { background: linear-gradient(180deg,#121d30,#16243c); border:1px solid var(--line); }
.tt { font-weight: 600; }
.key-form { margin-bottom: 6px; }
</style>
