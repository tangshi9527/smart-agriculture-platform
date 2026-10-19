<template>
  <div class="overview">
    <el-row :gutter="14">
      <el-col :span="6"><el-card shadow="never" class="kpi">
        <div class="kpi-num">{{ sysStatus.online ?? 0 }}</div><div class="kpi-lbl">在线设备</div>
      </el-card></el-col>
      <el-col :span="6"><el-card shadow="never" class="kpi">
        <div class="kpi-num">{{ sysStatus.connections ?? 0 }}</div><div class="kpi-lbl">并发连接</div>
      </el-card></el-col>
      <el-col :span="6"><el-card shadow="never" class="kpi">
        <div class="kpi-num">{{ throughput.telemetryPerSecond ?? 0 }}</div><div class="kpi-lbl">遥测/秒</div>
      </el-card></el-col>
      <el-col :span="6"><el-card shadow="never" class="kpi">
        <div class="kpi-num">{{ throughput.alertTotal ?? 0 }}</div><div class="kpi-lbl">累计告警</div>
      </el-card></el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header><span class="tt">全网点平均实时遥测趋势</span></template>
      <div class="chart-box"><RealtimeChart :points="points" /></div>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <span class="tt">时序查询 / 降采样性能对比</span>
        <span class="muted" style="margin-left:10px">原始库 vs 1 分钟降采样库（两条查询路径耗时对比）</span>
      </template>
      <el-form :inline="true" class="bench-form">
        <el-form-item label="设备ID"><el-input v-model="deviceId" placeholder="device-00000" clearable /></el-form-item>
        <el-form-item label="指标"><el-input v-model="metric" placeholder="temperature" clearable /></el-form-item>
        <el-form-item><el-button type="primary" @click="runBenchmark" :loading="loading">执行对比</el-button></el-form-item>
      </el-form>
      <el-descriptions v-if="bench.rawCostMs != null" :column="2" border size="small">
        <el-descriptions-item label="原始库耗时">{{ bench.rawCostMs }} ms / {{ bench.rawPoints }} 点</el-descriptions-item>
        <el-descriptions-item label="降采样库耗时">{{ bench.downsampledCostMs }} ms / {{ bench.downsampledPoints }} 点</el-descriptions-item>
      </el-descriptions>
      <el-empty v-else description="点击「执行对比」查看降采样优化效果" :image-size="60" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, inject, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'
import RealtimeChart from './RealtimeChart.vue'

const live = inject('live')
const sysStatus = inject('sysStatus')
const health = inject('health')

const points = ref([])
const deviceId = ref('device-00000')
const metric = ref('temperature')
const loading = ref(false)
const bench = ref({ rawCostMs: null, rawPoints: 0, downsampledCostMs: null, downsampledPoints: 0 })
const throughput = ref({ telemetryPerSecond: 0, alertTotal: 0 })

let timer = null

function sampleChart() {
  const map = live?.byDevice || {}
  const list = Object.values(map)
  let t = 0, h = 0, n = 0
  for (const d of list) {
    const m = d.metrics || {}
    if (typeof m.temperature === 'number') { t += m.temperature; n++ }
    if (typeof m.humidity === 'number') { h += m.humidity }
  }
  if (n === 0) return
  const now = new Date()
  const label = now.toLocaleTimeString('zh-CN', { hour12: false })
  points.value.push({ time: label, temperature: +(t / n).toFixed(1), humidity: +(h / n).toFixed(1) })
  if (points.value.length > 60) points.value.shift()
}

function syncThroughput() {
  // health 是 inject 进来的 reactive 对象（非 ref），不能取 .value
  const tp = health?.throughput
  if (tp) throughput.value = tp
}

async function runBenchmark() {
  if (!deviceId.value || !metric.value) { ElMessage.warning('请填写设备ID与指标'); return }
  loading.value = true
  try {
    const { data } = await api.benchmark(deviceId.value, metric.value)
    Object.assign(bench.value, data.data)
  } catch (e) {
    ElMessage.error('对比失败：' + ((e.response && e.response.data && e.response.data.message) || e.message))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  timer = setInterval(() => { sampleChart(); syncThroughput() }, 1000)
})
onBeforeUnmount(() => { timer && clearInterval(timer) })
</script>

<style scoped>
.overview { padding: 4px; }
.kpi { background: linear-gradient(180deg,#121d30,#16243c); border:1px solid var(--line); }
.kpi-num { font-size: 30px; font-weight: 700; color: var(--accent); }
.kpi-lbl { color: var(--muted); font-size: 12px; margin-top: 4px; }
.block { margin-top: 14px; background: linear-gradient(180deg,#121d30,#16243c); border:1px solid var(--line); }
.tt { font-weight: 600; }
.chart-box { height: 280px; }
.bench-form { margin-top: 4px; }
</style>
