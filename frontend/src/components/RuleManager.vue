<template>
  <div class="rule-mgr">
    <el-row :gutter="14">
      <el-col :span="9">
        <el-card shadow="never" class="block">
          <template #header><span class="tt">新增告警规则（热更新）</span></template>
          <el-form :model="form" label-width="92px" size="small">
            <el-form-item label="规则名"><el-input v-model="form.name" placeholder="如 高温告警" /></el-form-item>

            <el-form-item label="条件组合">
              <div v-for="(c, idx) in form.conditions" :key="idx" class="cond-row">
                <el-input v-model="c.metric" placeholder="指标" style="width:118px" />
                <el-select v-model="c.operator" style="width:108px">
                  <el-option label="大于 GT" value="GT" />
                  <el-option label="小于 LT" value="LT" />
                  <el-option label="大于等于 GTE" value="GTE" />
                  <el-option label="小于等于 LTE" value="LTE" />
                  <el-option label="等于 EQ" value="EQ" />
                </el-select>
                <el-input-number v-model="c.threshold" :precision="2" :step="1" style="width:118px" />
                <el-button v-if="form.conditions.length > 1" type="danger" plain size="small"
                           @click="rmCond(idx)">删</el-button>
              </div>
              <el-button size="small" @click="addCond">+ 增加条件</el-button>
            </el-form-item>

            <el-form-item label="逻辑" v-if="form.conditions.length > 1">
              <el-select v-model="form.logic" style="width:100%">
                <el-option label="且 AND（全部满足）" value="AND" />
                <el-option label="或 OR（任一满足）" value="OR" />
              </el-select>
            </el-form-item>

            <el-form-item label="持续时长">
              <el-input-number v-model="form.durationSec" :min="0" :step="5" style="width:140px" />
              <span class="muted" style="margin-left:6px">秒，0=即时触发；&gt;0 需持续满足该时长才告警</span>
            </el-form-item>

            <el-form-item label="级别">
              <el-select v-model="form.severity" style="width:100%">
                <el-option label="CRITICAL" value="CRITICAL" />
                <el-option label="WARN" value="WARN" />
                <el-option label="INFO" value="INFO" />
              </el-select>
            </el-form-item>
            <el-form-item label="限定设备"><el-input v-model="form.deviceId" placeholder="留空=全部设备" /></el-form-item>
            <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
            <el-form-item>
              <el-button type="primary" @click="add" :loading="saving">添加（即时生效）</el-button>
            </el-form-item>
          </el-form>
          <p class="muted">规则保存后立即刷新引擎缓存，<b>无需重启服务</b>即可生效。</p>
        </el-card>
      </el-col>

      <el-col :span="15">
        <el-card shadow="never" class="block">
          <template #header>
            <span class="tt">当前生效规则</span>
            <span class="muted" style="margin-left:8px">共 {{ rules.length }} 条</span>
            <el-button size="small" style="float:right" @click="load">刷新</el-button>
          </template>
          <el-table :data="rules" height="430" size="small" stripe>
            <el-table-column prop="name" label="规则名" width="160" />
            <el-table-column label="条件" min-width="240">
              <template #default="{ row }">
                {{ conditionText(row) }}
                <span class="muted" v-if="row.deviceId"> ({{ row.deviceId }})</span>
                <span class="muted" v-else> (全部)</span>
              </template>
            </el-table-column>
            <el-table-column prop="severity" label="级别" width="100">
              <template #default="{ row }">
                <el-tag :type="row.severity === 'CRITICAL' ? 'danger' : (row.severity === 'WARN' ? 'warning' : 'info')" size="small">{{ row.severity }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="80">
              <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="14" style="margin-top:14px">
      <el-col :span="24">
        <el-card shadow="never" class="block">
          <template #header>
            <span class="tt">规则 DSL 编辑器（高级 / 批量）</span>
            <el-button size="small" style="float:right" @click="loadExample">载入示例</el-button>
          </template>

          <el-input v-model="dsl" type="textarea" :rows="12" class="dsl-box"
            placeholder="在此编写/粘贴规则 DSL，点「解析预览」检查，点「应用规则」写入并即时生效。点右上角「载入示例」查看语法。" />

          <div style="margin-top:10px; display:flex; flex-wrap:wrap; gap:10px; align-items:center">
            <el-button type="primary" size="small" :loading="parsing" @click="parseDsl">解析预览</el-button>
            <el-button type="success" size="small" :loading="applying" @click="applyDsl">应用规则</el-button>
            <span class="muted">语法：RULE 名称 / SCOPE 设备|ALL / WHEN 条件(AND|OR，可分行续写) / HOLD FOR 秒数 / THEN 级别 ["消息"] / ENABLED true|false</span>
          </div>

          <el-alert v-if="dslErrors.length" type="error" :closable="false" show-icon style="margin-top:12px"
            :title="'解析发现 ' + dslErrors.length + ' 处问题（未写入）'" />
          <ul v-if="dslErrors.length" class="dsl-err">
            <li v-for="(e, i) in dslErrors" :key="i">
              <template v-if="e.line > 0"><b>第 {{ e.line }} 行：</b></template>{{ e.message }}
            </li>
          </ul>

          <template v-if="dslPreview.length">
            <div class="muted" style="margin:14px 0 6px">解析预览（{{ dslPreview.length }} 条，尚未写入）</div>
            <el-table :data="dslPreview" size="small" stripe>
              <el-table-column prop="name" label="规则名" width="180" />
              <el-table-column label="条件" min-width="260">
                <template #default="{ row }">{{ conditionText(row) }}</template>
              </el-table-column>
              <el-table-column prop="severity" label="级别" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.severity === 'CRITICAL' ? 'danger' : (row.severity === 'WARN' ? 'warning' : 'info')" size="small">{{ row.severity }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="作用域" width="150">
                <template #default="{ row }">{{ row.deviceId ? row.deviceId : '全部' }}</template>
              </el-table-column>
              <el-table-column label="启用" width="80">
                <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
              </el-table-column>
            </el-table>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'

const rules = ref([])
const saving = ref(false)
const form = ref({
  name: '', severity: 'CRITICAL', deviceId: '', enabled: true,
  logic: 'AND', durationSec: 0,
  conditions: [{ metric: 'temperature', operator: 'GT', threshold: 35 }]
})
let timer = null
let errShown = false

function errMsg(e) {
  return (e && e.response && e.response.data && e.response.data.message) || (e && e.message) || '未知错误'
}

function addCond() {
  form.value.conditions.push({ metric: 'humidity', operator: 'GT', threshold: 80 })
}
function rmCond(idx) {
  form.value.conditions.splice(idx, 1)
}
function opSym(op) {
  return ({ GT: '>', LT: '<', GTE: '≥', LTE: '≤', EQ: '=' })[op] || op
}
function conditionText(row) {
  const conds = (row.conditions && row.conditions.length)
    ? row.conditions
    : (row.metric ? [{ metric: row.metric, operator: row.operator, threshold: row.threshold }] : [])
  if (!conds.length) return '—'
  const sep = (row.logic === 'OR') ? ' 或 ' : ' 且 '
  return conds.map(c => `${c.metric} ${opSym(c.operator)} ${c.threshold}`).join(sep)
    + (row.durationSec ? ` · 持续${row.durationSec}s` : '')
}

async function load() {
  try {
    const { data } = await api.rules()
    rules.value = data.data || []
    errShown = false
  } catch (e) {
    if (!errShown) { errShown = true; ElMessage.error('规则列表加载失败：' + errMsg(e)) }
  }
}
async function add() {
  if (!form.value.name) { ElMessage.warning('请填写规则名'); return }
  const valid = form.value.conditions.every(c => c.metric && c.metric.trim())
  if (!valid) { ElMessage.warning('每条条件都需填写指标'); return }
  saving.value = true
  try {
    await api.saveRule({
      name: form.value.name,
      severity: form.value.severity,
      deviceId: form.value.deviceId,
      enabled: form.value.enabled,
      logic: form.value.logic,
      durationSec: form.value.durationSec,
      conditions: form.value.conditions.map(c => ({ ...c }))
    })
    ElMessage.success('规则已保存并即时生效')
    form.value.name = ''
    form.value.conditions = [{ metric: 'temperature', operator: 'GT', threshold: 35 }]
    form.value.durationSec = 0
    form.value.logic = 'AND'
    await load()
  } catch (e) {
    ElMessage.error('保存失败：' + ((e.response && e.response.data && e.response.data.message) || e.message))
  } finally {
    saving.value = false
  }
}
async function remove(row) {
  try { await api.deleteRule(row.id); await load(); ElMessage.success('已删除') } catch {}
}

// —— 规则 DSL 编辑器 ——
const dsl = ref('')
const dslPreview = ref([])
const dslErrors = ref([])
const parsing = ref(false)
const applying = ref(false)

const DSL_EXAMPLE = `# 智慧农业告警规则 DSL 示例
# 语法：RULE 名称 / SCOPE 设备|ALL / WHEN 条件(AND|OR) / HOLD FOR 秒数 / THEN 级别 ["消息"] / ENABLED true|false

RULE 高温告警
WHEN temperature > 35
THEN CRITICAL "棚内温度过高，请开启通风"

RULE 闷热组合告警
WHEN temperature > 24 AND humidity > 60
HOLD FOR 8s
THEN WARN "高温高湿持续，注意病害"

RULE 低湿或高CO2
WHEN soilMoisture < 30 OR co2 > 1000
THEN INFO

RULE 单设备土壤过干
SCOPE device-00001
WHEN soilMoisture < 25
THEN WARN

RULE 多条件分行示例
WHEN temperature > 30
AND humidity > 70
THEN WARN

RULE 已禁用示例
WHEN light < 1000
THEN INFO
ENABLED false
`

function loadExample() {
  dsl.value = DSL_EXAMPLE
  dslPreview.value = []
  dslErrors.value = []
}

async function parseDsl() {
  if (!dsl.value.trim()) { ElMessage.warning('请先编写或载入 DSL'); return }
  parsing.value = true
  try {
    const { data } = await api.parseDsl(dsl.value)
    const r = data.data
    dslErrors.value = r.errors || []
    dslPreview.value = r.rules || []
    if (!dslErrors.value.length) {
      ElMessage.success('解析成功，共 ' + dslPreview.value.length + ' 条')
    }
  } catch (e) {
    ElMessage.error('解析失败：' + errMsg(e))
  } finally {
    parsing.value = false
  }
}

async function applyDsl() {
  if (!dsl.value.trim()) { ElMessage.warning('请先编写或载入 DSL'); return }
  applying.value = true
  try {
    const { data } = await api.applyDsl(dsl.value)
    const r = data.data
    dslErrors.value = r.errors || []
    if (r.ok) {
      ElMessage.success('已应用 ' + r.applied + ' 条规则并即时生效')
      await load()
    } else {
      ElMessage.error('存在 ' + dslErrors.value.length + ' 处错误，未应用')
    }
  } catch (e) {
    ElMessage.error('应用失败：' + errMsg(e))
  } finally {
    applying.value = false
  }
}

onMounted(() => { load(); timer = setInterval(load, 8000) })
onBeforeUnmount(() => { timer && clearInterval(timer) })
</script>

<style scoped>
.rule-mgr { padding: 4px; }
.block { background: linear-gradient(180deg,#121d30,#16243c); border:1px solid var(--line); }
.tt { font-weight: 600; }
.cond-row { display: flex; gap: 6px; align-items: center; margin-bottom: 6px; }
.dsl-box :deep(textarea) {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  color: #d6e4ff;
  background: #0e1828;
  border: 1px solid var(--line);
}
.dsl-err { margin: 8px 0 0; padding-left: 18px; color: #ff9a9a; font-size: 13px; line-height: 1.7; }
</style>
