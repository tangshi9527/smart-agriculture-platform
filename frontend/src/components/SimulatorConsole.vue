<template>
  <div class="console">
    <el-alert
      v-if="res"
      class="cap-alert"
      :type="resType"
      :closable="false"
      show-icon
      :title="`本机资源 ${res.level}　CPU ${pct(res.cpuLoad)}　堆 ${pct(res.heapUsed)}　建议上报间隔 ${res.suggestedReportIntervalMs} ms`"
      :description="res.advice" />
    <el-button
      v-if="res && res.level !== 'LOW'"
      size="small" type="warning" plain class="cap-btn"
      @click="applySuggestedInterval">一键降速到 {{ res.suggestedReportIntervalMs }} ms</el-button>
    <el-row :gutter="14">
      <el-col :span="10">
        <el-card shadow="never" class="block">
          <template #header><span class="tt">模拟器参数（界面可调）</span></template>
          <el-form :model="cfg" label-width="120px" size="small">
            <el-form-item label="设备数">
              <el-input-number v-model="cfg.devices" :min="1" :max="5000" style="width:100%" />
            </el-form-item>
            <el-form-item label="并发连接数">
              <el-input-number v-model="cfg.connections" :min="1" :max="10000" style="width:100%" />
            </el-form-item>
            <el-form-item label="上报间隔(ms)">
              <el-slider v-model="cfg.reportIntervalMs" :min="200" :max="10000" :step="100" show-input />
            </el-form-item>
            <el-form-item label="心跳间隔(ms)">
              <el-slider v-model="cfg.heartbeatIntervalMs" :min="5000" :max="60000" :step="1000" show-input />
            </el-form-item>
            <el-form-item label="高温故障率">
              <el-slider v-model="cfg.faultRate" :min="0" :max="0.5" :step="0.01" :format-tooltip="v => (v*100).toFixed(0)+'%'" />
            </el-form-item>
            <el-form-item label="基准温度℃">
              <el-input-number v-model="cfg.baseTemp" :precision="1" :step="1" style="width:100%" />
            </el-form-item>
            <el-form-item label="温度波动幅度">
              <el-input-number v-model="cfg.tempAmplitude" :precision="1" :step="1" style="width:100%" />
            </el-form-item>
            <el-form-item label="运行时长(s)">
              <el-input-number v-model="cfg.durationSec" :min="0" :max="3600" style="width:100%" />
              <span class="muted">0 = 不限时</span>
            </el-form-item>
            <el-form-item>
              <el-button type="success" @click="start" :loading="acting">启动</el-button>
              <el-button type="danger" @click="stop" :loading="acting">停止</el-button>
              <el-button @click="reset">恢复默认</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card shadow="never" class="block">
          <template #header><span class="tt">运行状态</span>
            <el-button size="small" style="float:right" @click="loadStatus">刷新</el-button>
          </template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="运行中">
              <el-tag :type="status.running ? 'success' : 'info'" size="small">{{ status.running ? '是' : '否' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="引擎">{{ status.engine }}</el-descriptions-item>
            <el-descriptions-item label="进程 PID">{{ status.pid ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="已运行">{{ status.uptimeSec ?? 0 }} s</el-descriptions-item>
            <el-descriptions-item label="遥测/秒">{{ (health?.throughput?.telemetryPerSecond) ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="累计遥测">{{ (health?.throughput?.telemetryTotal) ?? 0 }}</el-descriptions-item>
          </el-descriptions>

          <el-divider>当前生效配置</el-divider>
          <el-descriptions :column="2" border size="small" v-if="status.config">
            <el-descriptions-item label="设备数">{{ status.config.devices }}</el-descriptions-item>
            <el-descriptions-item label="连接数">{{ status.config.connections }}</el-descriptions-item>
            <el-descriptions-item label="上报间隔">{{ status.config.reportIntervalMs }} ms</el-descriptions-item>
            <el-descriptions-item label="心跳间隔">{{ status.config.heartbeatIntervalMs }} ms</el-descriptions-item>
            <el-descriptions-item label="故障率">{{ (status.config.faultRate*100).toFixed(0) }}%</el-descriptions-item>
            <el-descriptions-item label="基准温度">{{ status.config.baseTemp }}℃</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="尚未启动" :image-size="60" />

          <el-alert type="info" :closable="false" style="margin-top:12px">
            模拟器为独立 Python 脚本（paho-mqtt），由后端按需拉起；作为外部 MQTT 客户端接入，
            行为等同真实硬件。若环境无 Python 则自动回退到内置 Java 模拟器。
          </el-alert>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, computed, inject, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'

const health = inject('health')
// 资源压力：这是「节流」信号，不是「降级」信号——CPU 高应该降采样率，而不是切内存
const res = computed(() => (health && health.resources) || null)
const resType = computed(() => {
  const lv = res.value && res.value.level
  return lv === 'HIGH' ? 'error' : (lv === 'MEDIUM' ? 'warning' : 'success')
})
function pct(v) {
  return (v == null) ? '—' : Math.round(Number(v) * 100) + '%'
}
function applySuggestedInterval() {
  if (!res.value) return
  cfg.reportIntervalMs = res.value.suggestedReportIntervalMs
  ElMessage.success('已应用建议上报间隔 ' + cfg.reportIntervalMs + ' ms，点「启动」生效')
}
const acting = ref(false)
const defaultCfg = {
  // 与后端 SimulatorConfig 默认值保持一致；挂载后会被 /simulator/defaults 覆盖
  devices: 200, connections: 200, reportIntervalMs: 2000, heartbeatIntervalMs: 15000,
  faultRate: 0.04, baseTemp: 24.0, tempAmplitude: 8.0, durationSec: 0
}
const cfg = reactive({ ...defaultCfg })
const status = reactive({ running: false, engine: 'none', pid: null, uptimeSec: 0, config: null })
let timer = null

async function loadDefaults() {
  try { const { data } = await api.simDefaults(); Object.assign(cfg, data.data) } catch {}
}
async function loadStatus() {
  try { const { data } = await api.simStatus(); Object.assign(status, data.data) } catch {}
}
async function start() {
  acting.value = true
  try {
    await api.simStart({ ...cfg })
    ElMessage.success('模拟器已启动（参数已下发）')
    await loadStatus()
  } catch (e) {
    ElMessage.error('启动失败：' + ((e.response && e.response.data && e.response.data.message) || e.message))
  } finally {
    acting.value = false
  }
}
async function stop() {
  acting.value = true
  try { await api.simStop(); await loadStatus(); ElMessage.success('已停止') }
  catch (e) { ElMessage.error('停止失败') } finally { acting.value = false }
}
function reset() { Object.assign(cfg, defaultCfg) }

onMounted(() => { loadDefaults(); loadStatus(); timer = setInterval(loadStatus, 3000) })
onBeforeUnmount(() => { timer && clearInterval(timer) })
</script>

<style scoped>
.cap-alert { margin-bottom: 10px; }
.cap-btn { margin-bottom: 10px; }
.console { padding: 4px; }
.block { background: linear-gradient(180deg,#121d30,#16243c); border:1px solid var(--line); }
.tt { font-weight: 600; }
</style>
