<template>
  <div class="alert-center">
    <el-card shadow="never" class="block">
      <template #header>
        <span class="tt">告警中心</span>
        <span class="muted" style="margin-left:10px">实时推送 + 持久化记录，支持确认/忽略/AI 解读</span>
        <el-button size="small" style="float:right" @click="load">刷新</el-button>
      </template>
      <el-form :inline="true" class="filter">
        <el-form-item label="状态">
          <el-select v-model="status" style="width:130px" @change="load">
            <el-option label="全部" value="" />
            <el-option label="活动" value="ACTIVE" />
            <el-option label="已确认" value="ACKED" />
            <el-option label="已忽略" value="IGNORED" />
          </el-select>
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="severity" style="width:130px" @change="load">
            <el-option label="全部" value="" />
            <el-option label="CRITICAL" value="CRITICAL" />
            <el-option label="WARN" value="WARN" />
            <el-option label="INFO" value="INFO" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-table :data="filtered" height="460" size="small" stripe>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmt(row.ts) }}</template>
        </el-table-column>
        <el-table-column prop="deviceId" label="设备" width="140" />
        <el-table-column prop="metric" label="指标" width="100" />
        <el-table-column label="值/阈值" width="140">
          <template #default="{ row }">{{ row.value }} / {{ row.threshold }}</template>
        </el-table-column>
        <el-table-column label="级别" width="100">
          <template #default="{ row }">
            <el-tag :type="row.severity === 'CRITICAL' ? 'danger' : (row.severity === 'WARN' ? 'warning' : 'info')" size="small">
              {{ row.severity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'danger' : 'success'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="描述" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="236" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" plain @click="ack(row)" :disabled="row.status !== 'ACTIVE'">确认</el-button>
            <el-button size="small" type="info" plain @click="ignore(row)" :disabled="row.status !== 'ACTIVE'">忽略</el-button>
            <el-button size="small" type="primary" plain @click="explain(row)">AI 解读</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="aiOpen" title="AI 告警解读" size="520px" class="ai-drawer">
      <div v-if="aiRow" class="ai-meta">
        <el-tag size="small" :type="aiRow.severity === 'CRITICAL' ? 'danger' : 'warning'">{{ aiRow.severity }}</el-tag>
        <span class="mono">{{ aiRow.deviceId }}</span>
        <span class="muted">{{ aiRow.metric }} = {{ aiRow.value }}（阈值 {{ aiRow.operator }} {{ aiRow.threshold }}）</span>
      </div>

      <div class="ai-status">
        <el-tag size="small" :type="statusTagType">
          {{ statusText }}
        </el-tag>
        <span class="muted" v-if="cur && cur.latencyMs">耗时 {{ cur.latencyMs }} ms</span>
        <span class="muted" v-if="cur && cur.model">模型 {{ cur.model }}</span>
      </div>

      <div class="ai-body">
        <span class="ai-text">{{ cur ? cur.content : '' }}</span>
        <span v-if="cur && cur.status === 'STREAMING'" class="caret"></span>
      </div>

      <el-alert v-if="cur && cur.status === 'LOCAL'" type="warning" :closable="false" show-icon
                title="本次未调用大模型"
                description="未配置 API Key、熔断保护打开或调用失败时，会由本地规则模板生成解读。配好 llm.api-key 后即是模型生成。" />
      <el-alert v-if="cur && cur.error" type="info" :closable="false" title="降级原因" :description="cur.error" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, inject, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'

const rows = ref([])
const status = ref('')
const severity = ref('')
let timer = null
let errShown = false

const aiExplain = inject('aiExplain', null)
const aiOpen = ref(false)
const aiCid = ref('')
const aiRow = ref(null)

const cur = computed(() => (aiExplain && aiCid.value ? aiExplain[aiCid.value] : null))

const statusText = computed(() => {
  const s = cur.value && cur.value.status
  if (!s || s === 'STREAMING') return '生成中…'
  if (s === 'DONE') return 'AI 生成'
  if (s === 'LOCAL') return '本地规则'
  return '生成失败'
})

const statusTagType = computed(() => {
  const s = cur.value && cur.value.status
  if (!s || s === 'STREAMING') return 'info'
  if (s === 'DONE') return 'success'
  if (s === 'LOCAL') return 'warning'
  return 'danger'
})

function errMsg(e) {
  return (e && e.response && e.response.data && e.response.data.message) || (e && e.message) || '未知错误'
}

const filtered = computed(() =>
  rows.value.filter(r => (!severity.value || r.severity === severity.value)))

function fmt(ts) {
  if (!ts) return ''
  return new Date(Number(ts)).toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  try {
    const { data } = await api.alerts(status.value || undefined, 200)
    rows.value = data.data || []
    errShown = false
  } catch (e) {
    if (!errShown) { errShown = true; ElMessage.error('告警列表加载失败：' + errMsg(e)) }
  }
}
async function ack(row) {
  try { await api.ackAlert(row.id); await load(); ElMessage.success('已确认') } catch {}
}
async function ignore(row) {
  try { await api.ignoreAlert(row.id); await load(); ElMessage.success('已忽略') } catch {}
}

/**
 * 触发 AI 解读：cid 由前端生成并立即占位，随后的 WebSocket 增量就能直接拼上，
 * 不需要等 HTTP 响应回来再关联。
 */
async function explain(row) {
  const cid = 'ai-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8)
  aiCid.value = cid
  aiRow.value = row
  aiOpen.value = true
  if (aiExplain) {
    aiExplain[cid] = { content: '', status: 'STREAMING', model: '', latencyMs: 0, error: null }
  }
  try {
    await api.explainAlert({
      cid,
      deviceId: row.deviceId,
      ruleName: row.ruleName,
      metric: row.metric,
      value: row.value,
      threshold: row.threshold,
      operator: row.operator,
      severity: row.severity,
      message: row.message,
      ts: row.ts
    })
  } catch (e) {
    if (aiExplain) aiExplain[cid].status = 'FAILED'
    ElMessage.error('AI 解读请求失败：' + errMsg(e))
  }
}

onMounted(() => { load(); timer = setInterval(load, 5000) })
onBeforeUnmount(() => { timer && clearInterval(timer) })
</script>

<style scoped>
.alert-center { padding: 4px; }
.block { background: linear-gradient(180deg,#121d30,#16243c); border:1px solid var(--line); }
.tt { font-weight: 600; }
.filter { margin-bottom: 6px; }
.ai-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.ai-status { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.ai-body { min-height: 160px; padding: 14px 16px; border-radius: 8px;
  background: #f7faf5; border: 1px solid #e2ebdc; }
.ai-text { white-space: pre-wrap; line-height: 1.85; font-size: 14px; color: #1f2328; }
.caret { display: inline-block; width: 7px; height: 15px; margin-left: 2px;
  background: #2f6f4f; vertical-align: text-bottom; animation: blink 1s steps(1) infinite; }
@keyframes blink { 50% { opacity: 0; } }
.mono { font-family: ui-monospace, Menlo, Consolas, monospace; }
.muted { color: var(--muted, #8fa3bd); font-size: 12px; }
/* 抽屉是白底，覆盖深色主题下的浅色文字 */
.ai-meta .mono { color: #1f2328; }
.ai-meta .muted, .ai-status .muted { color: #5a6360; }
</style>
