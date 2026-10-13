<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  ArrowLeft, CircleCheck, CircleClose, Clock, Connection, Download,
  Loading, Refresh, Search, VideoPause, VideoPlay, WarningFilled
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { softwareIntegrationApi } from '@/api/softwareIntegration'

const emit = defineEmits(['back'])
const capability = ref(null)
const recentRuns = ref([])
const activeRun = ref(null)
const selectedRunId = ref('')
const busy = ref(false)
const cancelling = ref(false)
const pageLoading = ref(true)
const searchText = ref('')
const selectedEquipment = ref('')
const outletPressurePsia = ref(600)
const comparisonRunId = ref('')
const comparisonRun = ref(null)
const page = ref(1)
const pageSize = 30
let pollTimer = null

const result = computed(() => activeRun.value?.result || null)
const comparisonResult = computed(() => comparisonRun.value?.result || null)
const allValues = computed(() => Array.isArray(result.value?.values) ? result.value.values : [])
const comparisonValues = computed(() => Array.isArray(comparisonResult.value?.values) ? comparisonResult.value.values : [])
const equipmentOptions = computed(() => [...new Set(allValues.value.map(row => row.equipmentName).filter(Boolean))].sort())
const comparisonOptions = computed(() => recentRuns.value.filter(run => run.state === 'SUCCEEDED' && run.runId !== activeRun.value?.runId))
const outletControl = computed(() => capability.value?.controls?.find(control => control.parameter === 'outletPressure'))
const pressureValid = computed(() => Number.isFinite(outletPressurePsia.value)
  && outletPressurePsia.value >= (outletControl.value?.minimum ?? 500)
  && outletPressurePsia.value <= (outletControl.value?.maximum ?? 1000))
const filteredValues = computed(() => {
  const query = searchText.value.trim().toLocaleLowerCase()
  return allValues.value.filter(row => {
    if (selectedEquipment.value && row.equipmentName !== selectedEquipment.value) return false
    if (!query) return true
    return [row.equipmentName, row.variableName, row.measurement, row.unit, row.variableTypeId]
      .some(value => String(value ?? '').toLocaleLowerCase().includes(query))
  })
})
const pageValues = computed(() => filteredValues.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const active = computed(() => !!activeRun.value && !isTerminal(activeRun.value.state))
const capabilityAvailable = computed(() => capability.value?.status === 'AVAILABLE')
const canStart = computed(() => capabilityAvailable.value && capability.value?.idle && !active.value && !busy.value && pressureValid.value)
const currentOccurrenceKeys = computed(() => {
  const keys = buildOccurrenceKeys(allValues.value)
  return new Map(allValues.value.map((row, index) => [row, keys[index]]))
})
const comparisonOccurrenceValues = computed(() => {
  const keys = buildOccurrenceKeys(comparisonValues.value)
  return new Map(comparisonValues.value.map((row, index) => [keys[index], row.value]))
})
const displayValues = computed(() => pageValues.value.map(row => {
  const baselineValue = comparisonOccurrenceValues.value.get(currentOccurrenceKeys.value.get(row))
  const delta = typeof row.value === 'number' && typeof baselineValue === 'number' ? row.value - baselineValue : null
  return { ...row, baselineValue, delta }
}))
const responseIndicators = computed(() => {
  const choices = [
    { equipment: 'OIL_EXPORT', variable: 'Pressure', label: '出口节点压力' },
    { equipment: 'B-N10', variable: 'Pressure', label: 'B-N10 压力' },
    { equipment: 'OIL_EXPORT', variable: 'Stock-tank oil flowrate', label: '出口油量' }
  ]
  return choices.map(choice => {
    const row = allValues.value.find(item => item.equipmentName === choice.equipment && item.variableName === choice.variable)
    const baselineValue = row ? comparisonOccurrenceValues.value.get(currentOccurrenceKeys.value.get(row)) : undefined
    return { ...choice, row, baselineValue, delta: typeof row?.value === 'number' && typeof baselineValue === 'number' ? row.value - baselineValue : null }
  }).filter(item => item.row)
})

const stateLabels = {
  QUEUED: '排队中', PREPARING: '准备输入', IAM_AGENT_INITIALIZING: '初始化 IAM Agent',
  PIPESIM_SERVICE_CREATING: '创建 PIPESIM 服务', CALCULATING: 'PIPESIM 正在计算',
  COLLECTING: '读取计算结果', SUCCEEDED: '计算成功', FAILED: '计算失败',
  CANCELLED: '已取消', TIMED_OUT: '计算超时'
}
const stateTypes = {
  SUCCEEDED: 'success', FAILED: 'danger', CANCELLED: 'info', TIMED_OUT: 'danger'
}

function isTerminal(state) {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(state)
}

function unwrap(response) {
  return response?.data ?? response
}

function occurrenceBaseKey(row) {
  return [row.equipmentName, row.locator ?? '', row.locationOffset ?? '', row.variableTypeId].join('|')
}

function buildOccurrenceKeys(rows) {
  const occurrence = new Map()
  return rows.map(row => {
    const base = occurrenceBaseKey(row)
    const index = occurrence.get(base) || 0
    occurrence.set(base, index + 1)
    return `${base}|${index}`
  })
}

function clearPoll() {
  if (pollTimer) window.clearTimeout(pollTimer)
  pollTimer = null
}

async function refreshCapability() {
  const response = await softwareIntegrationApi.getIamPipesimDemoCapabilities()
  capability.value = unwrap(response)
}

async function refreshRuns() {
  const response = await softwareIntegrationApi.listIamPipesimDemoRuns()
  recentRuns.value = unwrap(response) || []
}

async function refreshPage() {
  pageLoading.value = true
  try {
    await Promise.all([refreshCapability(), refreshRuns()])
    if (!activeRun.value && recentRuns.value.length) await openRun(recentRuns.value[0].runId, false)
  } catch (error) {
    capability.value = { status: 'UNAVAILABLE', message: error?.msg || error?.message || '无法连接本机软件集成 Worker。' }
  } finally {
    pageLoading.value = false
  }
}

async function openRun(runId, startPolling = true) {
  if (!runId) return
  clearPoll()
  selectedRunId.value = runId
  try {
    const response = await softwareIntegrationApi.getIamPipesimDemoRun(runId)
    activeRun.value = unwrap(response)
    if (comparisonRunId.value === runId) {
      comparisonRunId.value = ''
      comparisonRun.value = null
    }
    page.value = 1
    if (startPolling && !isTerminal(activeRun.value.state)) schedulePoll(runId)
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '读取运行记录失败。')
  }
}

async function loadComparison(runId) {
  comparisonRunId.value = runId || ''
  comparisonRun.value = null
  if (!runId) return
  try {
    const response = await softwareIntegrationApi.getIamPipesimDemoRun(runId)
    const snapshot = unwrap(response)
    if (snapshot.state === 'SUCCEEDED') comparisonRun.value = snapshot
    else comparisonRunId.value = ''
  } catch (error) {
    comparisonRunId.value = ''
    ElMessage.error(error?.msg || error?.message || '读取对比运行结果失败。')
  }
}

function schedulePoll(runId) {
  clearPoll()
  pollTimer = window.setTimeout(async () => {
    if (selectedRunId.value !== runId) return
    try {
      const response = await softwareIntegrationApi.getIamPipesimDemoRun(runId)
      activeRun.value = unwrap(response)
      if (isTerminal(activeRun.value.state)) {
        await Promise.all([refreshCapability(), refreshRuns()])
      } else schedulePoll(runId)
    } catch (error) {
      schedulePoll(runId)
    }
  }, 1200)
}

async function startCalculation() {
  if (!canStart.value) return
  busy.value = true
  try {
    const previousSuccessfulRun = recentRuns.value.find(run => run.state === 'SUCCEEDED')
    if (previousSuccessfulRun) await loadComparison(previousSuccessfulRun.runId)
    const response = await softwareIntegrationApi.startIamPipesimDemo({ outletPressurePsia: outletPressurePsia.value })
    const accepted = unwrap(response)
    selectedRunId.value = accepted.runId
    activeRun.value = {
      ...accepted,
      events: [{ sequence: 1, state: accepted.state, occurredAtUtc: accepted.acceptedAtUtc, message: 'Worker 已接受本次真实计算任务。' }]
    }
    await refreshRuns()
    schedulePoll(accepted.runId)
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '无法启动 IAM–PIPESIM 计算。')
    await refreshCapability().catch(() => {})
  } finally {
    busy.value = false
  }
}

async function cancelCalculation() {
  if (!activeRun.value || !active.value) return
  cancelling.value = true
  try {
    await softwareIntegrationApi.cancelIamPipesimDemo(activeRun.value.runId)
    ElMessage.success('已请求停止当前演示计算。')
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '停止请求未能提交。')
  } finally {
    cancelling.value = false
  }
}

function downloadResult() {
  if (!result.value) return
  const blob = new Blob([JSON.stringify(result.value, null, 2)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `iam-pipesim-${activeRun.value.runId}-result.json`
  anchor.click()
  URL.revokeObjectURL(url)
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString() : '—'
}

function formatValue(value) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'number') return new Intl.NumberFormat('zh-CN', { maximumSignificantDigits: 8 }).format(value)
  return String(value)
}

function eventType(state) {
  if (state === 'SUCCEEDED') return 'success'
  if (state === 'FAILED' || state === 'TIMED_OUT') return 'danger'
  return 'primary'
}

onMounted(refreshPage)
onBeforeUnmount(clearPoll)
</script>

<template>
  <main class="iam-demo-page">
    <header class="page-header">
      <div class="page-heading">
        <el-button class="back-button" text @click="emit('back')">
          <el-icon><ArrowLeft /></el-icon>返回软件集成
        </el-button>
        <div class="title-row">
          <span class="title-icon"><el-icon><Connection /></el-icon></span>
          <div>
            <div class="eyebrow">一体化模拟 · 实时计算</div>
  <h1>IAM × PIPESIM 联动演示</h1>
            <p>调整 SIS2 网络出口压力，通过 IAM Agent Manager 调用 PIPESIM 2022.1 Engine，并查看结果响应。</p>
          </div>
        </div>
      </div>
      <div class="header-actions">
        <el-tag v-if="capabilityAvailable" type="success" effect="light" round>
          <el-icon><CircleCheck /></el-icon>本机运行环境就绪
        </el-tag>
        <el-tag v-else type="warning" effect="light" round>
          <el-icon><WarningFilled /></el-icon>运行环境未就绪
        </el-tag>
        <el-button :icon="Refresh" :loading="pageLoading" @click="refreshPage">刷新</el-button>
      </div>
    </header>

    <section class="page-content">
      <el-alert
        v-if="!capabilityAvailable"
        class="setup-alert"
        type="warning"
        :closable="false"
        show-icon
        title="Worker 尚未检测到完整的 IAM 演示运行时"
        :description="capability?.message || '请检查 IAM 隔离副本、PIPESIM 兼容目录、演示输入文件和 Agent Probe 的 Worker 配置。'"
      />

      <section class="overview-grid">
        <article class="overview-card model-card">
          <div class="card-title"><el-icon><Connection /></el-icon><span>计算方案</span><el-tag size="small" effect="plain">固定验收案例</el-tag></div>
          <el-form label-position="top" class="model-form">
            <el-form-item label="PIPESIM 模型">
              <el-select model-value="sis2" disabled>
                <el-option label="SIS2 Network · PIPESIM 2022.1" value="sis2" />
              </el-select>
            </el-form-item>
            <el-form-item label="调用链路">
              <div class="engine-chain"><span>IAM Agent Manager</span><b>→</b><span>Stingray Agent</span><b>→</b><span>PIPESIM Engine</span></div>
            </el-form-item>
            <el-form-item :label="`${outletControl?.equipment || 'OIL_EXPORT'} 出口压力 (${outletControl?.unit || 'psia'})`">
              <el-input-number v-model="outletPressurePsia" :min="outletControl?.minimum ?? 500" :max="outletControl?.maximum ?? 1000" :step="25" :precision="1" :disabled="!capabilityAvailable || !capability?.idle || active || busy" controls-position="right" />
              <div class="control-hint">只修改本次运行副本的边界；允许范围 {{ outletControl?.minimum ?? 500 }}–{{ outletControl?.maximum ?? 1000 }} psia。</div>
            </el-form-item>
          </el-form>
          <div class="version-strip">
            <div><span>IAM</span><strong>{{ capability?.iamVersion || '2022.1' }}</strong></div>
            <div><span>PIPESIM</span><strong>{{ capability?.pipesimVersion || '2022.1' }}</strong></div>
            <div><span>模型格式</span><strong>{{ capability?.model?.format || 'PIPESIM Network' }}</strong></div>
          </div>
          <div v-if="capability?.model?.sourceSha256" class="hash-row">
            <span>源模型 SHA-256</span><code :title="capability.model.sourceSha256">{{ capability.model.sourceSha256 }}</code>
          </div>
          <div v-if="capability?.engineInput?.sha256" class="hash-row">
            <span>模板引擎输入 SHA-256</span><code :title="capability.engineInput.sha256">{{ capability.engineInput.sha256 }}</code>
          </div>
          <div class="model-actions">
            <span class="availability" :class="{ ready: capabilityAvailable }">
              <el-icon><CircleCheck v-if="capabilityAvailable" /><CircleClose v-else /></el-icon>
              {{ capabilityAvailable ? '配置和演示输入已找到' : '缺少运行时配置' }}
            </span>
            <el-button type="primary" :icon="VideoPlay" :disabled="!canStart" :loading="busy" @click="startCalculation">
              开始真实计算
            </el-button>
          </div>
          <div v-if="active" class="running-note">
            <el-icon class="is-loading"><Loading /></el-icon>
            正在运行：{{ stateLabels[activeRun.state] || activeRun.state }}
            <el-button link type="danger" :icon="VideoPause" :loading="cancelling" @click="cancelCalculation">停止</el-button>
          </div>
        </article>

        <article class="overview-card run-card">
          <div class="card-title"><el-icon><Clock /></el-icon><span>本次运行</span>
            <el-tag v-if="activeRun" :type="stateTypes[activeRun.state] || (active ? 'primary' : 'info')" effect="light">
              {{ stateLabels[activeRun.state] || activeRun.state }}
            </el-tag>
          </div>
          <el-empty v-if="!activeRun && !pageLoading" description="尚无运行记录，点击“开始真实计算”启动案例" :image-size="72" />
          <div v-else-if="pageLoading && !activeRun" class="run-loading"><el-icon class="is-loading"><Loading /></el-icon>正在检查运行环境…</div>
          <template v-else-if="activeRun">
            <div class="run-meta">
              <div><span>运行编号</span><code>{{ activeRun.runId }}</code></div>
              <div><span>开始时间</span><strong>{{ formatTime(activeRun.acceptedAtUtc) }}</strong></div>
              <div><span>耗时</span><strong>{{ (activeRun.elapsedMillis / 1000).toFixed(1) }} 秒</strong></div>
            </div>
            <el-timeline class="run-timeline">
              <el-timeline-item
                v-for="item in (activeRun.events || []).slice().reverse()"
                :key="item.sequence"
                :timestamp="formatTime(item.occurredAtUtc)"
                :type="eventType(item.state)"
                :hollow="!isTerminal(item.state)"
              >
                <strong>{{ stateLabels[item.state] || item.state }}</strong>
                <p>{{ item.message }}</p>
              </el-timeline-item>
            </el-timeline>
            <el-alert v-if="activeRun.error" class="run-error" type="error" show-icon :closable="false" :title="activeRun.error" />
          </template>
        </article>
      </section>

      <section class="results-card">
        <div class="results-heading">
          <div>
            <div class="eyebrow">本次 Agent Manager SimulationResults</div>
            <h2>计算结果</h2>
              <p v-if="result">{{ result.scenario?.equipment }} 出口压力 {{ formatValue(result.scenario?.value) }} {{ result.scenario?.unit }}；本次输入与模板分别留有 SHA-256。包含 {{ result.equipmentCount }} 个设备、{{ result.pointResultCount }} 个测点和 {{ result.valueCount }} 个结果值。</p>
            <p v-else>计算完成后显示设备与测点的实际结果值、变量定义及单位。</p>
          </div>
          <el-button v-if="result" :icon="Download" @click="downloadResult">下载结果 JSON</el-button>
        </div>

        <div v-if="result" class="result-hashes">
          <span>本次输入 <code :title="result.engineInputSha256">{{ result.engineInputSha256 }}</code></span>
          <span>原始模板 <code :title="result.sourceEngineInputSha256">{{ result.sourceEngineInputSha256 }}</code></span>
        </div>

        <div v-if="result" class="response-grid">
          <article v-for="indicator in responseIndicators" :key="`${indicator.equipment}-${indicator.variable}`" class="response-card">
            <span>{{ indicator.label }}</span>
            <strong>{{ formatValue(indicator.row.value) }} <small>{{ indicator.row.unit }}</small></strong>
            <em v-if="indicator.delta !== null" :class="{ decrease: indicator.delta < 0 }">相对基准 {{ indicator.delta > 0 ? '+' : '' }}{{ formatValue(indicator.delta) }} {{ indicator.row.unit }}</em>
            <em v-else-if="comparisonResult">该项暂无匹配基准值</em>
          </article>
        </div>

        <div v-if="result" class="result-toolbar">
          <el-select :model-value="comparisonRunId" clearable placeholder="选择历史成功运行作基准" class="comparison-select" @change="loadComparison">
            <el-option v-for="run in comparisonOptions" :key="run.runId" :label="`${formatTime(run.acceptedAtUtc)} · ${run.runId.slice(0, 8)}`" :value="run.runId" />
          </el-select>
          <el-select v-model="selectedEquipment" clearable filterable placeholder="全部设备" class="equipment-select" @change="page = 1">
            <el-option v-for="name in equipmentOptions" :key="name" :label="name" :value="name" />
          </el-select>
          <el-input v-model="searchText" :prefix-icon="Search" clearable placeholder="搜索设备、变量或测量类型" @input="page = 1" />
          <span class="result-count">{{ filteredValues.length.toLocaleString() }} 条结果</span>
        </div>

        <el-table v-if="result" :data="displayValues" stripe height="420" class="result-table" empty-text="没有符合条件的结果">
          <el-table-column prop="equipmentName" label="设备" min-width="190" show-overflow-tooltip />
          <el-table-column prop="locator" label="测点" width="90" />
          <el-table-column prop="variableName" label="结果变量" min-width="200" show-overflow-tooltip />
          <el-table-column prop="measurement" label="测量类型" min-width="150" show-overflow-tooltip />
          <el-table-column label="单位" width="105"><template #default="scope">{{ scope.row.unit?.trim() || '—' }}</template></el-table-column>
          <el-table-column label="变量 ID" width="95" align="right"><template #default="scope">{{ scope.row.variableTypeId }}</template></el-table-column>
          <el-table-column label="实际值" min-width="145" align="right">
            <template #default="scope"><strong class="numeric-value">{{ formatValue(scope.row.value) }}</strong></template>
          </el-table-column>
          <el-table-column v-if="comparisonRun" label="基准值" min-width="145" align="right">
            <template #default="scope">{{ formatValue(scope.row.baselineValue) }}</template>
          </el-table-column>
          <el-table-column v-if="comparisonRun" label="差值" min-width="125" align="right">
            <template #default="scope"><span :class="{ 'delta-negative': scope.row.delta < 0 }">{{ scope.row.delta === null ? '—' : `${scope.row.delta > 0 ? '+' : ''}${formatValue(scope.row.delta)}` }}</span></template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!active" description="等待一次真实的 IAM Agent Manager 计算结果" :image-size="90" />
        <div v-if="result" class="result-footer">
          <span><el-icon><CircleCheck /></el-icon> Worker 验证了 Agent 初始化、Service 创建、Calculate 返回、引擎结果成功和清理状态。{{ comparisonRun ? `基准运行 ${comparisonRun.runId.slice(0, 8)}` : '' }}</span>
          <el-pagination v-model:current-page="page" :page-size="pageSize" :total="filteredValues.length" layout="prev, pager, next, total" small />
        </div>
      </section>

      <section class="history-card">
        <div class="history-heading"><div><div class="eyebrow">本机 Worker 最近记录</div><h2>运行记录</h2></div><span>最多显示 20 条</span></div>
        <el-table v-if="recentRuns.length" :data="recentRuns" row-key="runId" highlight-current-row :current-row-key="selectedRunId" @row-click="row => openRun(row.runId)">
          <el-table-column prop="runId" label="运行编号" min-width="270"><template #default="scope"><code>{{ scope.row.runId }}</code></template></el-table-column>
          <el-table-column label="状态" width="130"><template #default="scope"><el-tag size="small" :type="stateTypes[scope.row.state] || (isTerminal(scope.row.state) ? 'info' : 'primary')">{{ stateLabels[scope.row.state] || scope.row.state }}</el-tag></template></el-table-column>
          <el-table-column label="开始时间" min-width="185"><template #default="scope">{{ formatTime(scope.row.acceptedAtUtc) }}</template></el-table-column>
          <el-table-column label="耗时" width="110" align="right"><template #default="scope">{{ (scope.row.elapsedMillis / 1000).toFixed(1) }} 秒</template></el-table-column>
        </el-table>
        <el-empty v-else description="运行后会在这里保留本机 Worker 中的最近记录" :image-size="70" />
      </section>

      <section class="scope-note">
        <el-icon><WarningFilled /></el-icon>
        <div><strong>演示边界</strong><p>出口压力作用于本次隔离的 SIS2 PIPESIM 引擎输入副本，并由 IAM 2022.1 StingrayEngineAgentManager 调用 PIPESIM 2022.1 Engine 求解。IAM REST 模型激活与 ECLIPSE 2024.1 耦合流程仍未验收。</p></div>
      </section>
    </section>
  </main>
</template>

<style lang="scss" scoped>
.iam-demo-page { flex: 1; min-width: 0; min-height: 0; overflow: auto; background: #f4f5f6; color: #262a2e; }
.page-header { min-height: 128px; padding: 12px 32px 20px; display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; background: #fff; border-bottom: 1px solid #e5e7e9; }
.page-heading { min-width: 0; }
.back-button { margin: -3px 0 12px -9px; color: #71777d; }
.title-row { display: flex; align-items: center; gap: 13px; }
.title-icon { width: 44px; height: 44px; display: grid; place-items: center; flex: 0 0 auto; border: 1px solid #e9d36c; border-radius: 10px; background: #fff8d3; color: #987800; font-size: 22px; }
.eyebrow { color: #9a7a08; font-size: 10px; font-weight: 700; letter-spacing: .06em; }
h1 { margin: 3px 0 5px; font-size: 21px; line-height: 1.25; font-weight: 650; }
.title-row p { margin: 0; color: #747b81; font-size: 11px; }
.header-actions { display: flex; align-items: center; gap: 10px; padding-bottom: 3px; white-space: nowrap; }
.page-content { width: min(1230px, calc(100% - 48px)); margin: 20px auto 34px; }
.setup-alert { margin-bottom: 14px; }
.overview-grid { display: grid; grid-template-columns: minmax(0, 1.12fr) minmax(350px, .88fr); gap: 14px; align-items: stretch; }
.overview-card, .results-card, .history-card { min-width: 0; padding: 19px 20px; border: 1px solid #e2e5e8; border-radius: 8px; background: #fff; box-shadow: 0 3px 14px rgb(36 45 55 / 4%); }
.model-card { border-top: 3px solid #e0c130; }
.card-title { display: flex; align-items: center; gap: 8px; margin-bottom: 15px; color: #383f45; font-size: 14px; font-weight: 650; }
.card-title > .el-icon { color: #8d7410; font-size: 17px; }
.card-title .el-tag { margin-left: auto; }
.model-form :deep(.el-form-item) { margin-bottom: 12px; }
.model-form :deep(.el-form-item__label) { height: auto; padding-bottom: 4px; color: #7b8389; font-size: 10px; }
.model-form :deep(.el-select), .model-form :deep(.el-input) { width: 100%; }
.model-form :deep(.el-input-number) { width: 100%; }
.control-hint { margin-top: 4px; color: #92999f; font-size: 9px; line-height: 1.5; }
.engine-chain { min-height: 38px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 10px; border: 1px solid #e7e9eb; border-radius: 5px; background: #fafbfb; color: #57646f; font-size: 10px; }
.engine-chain b { color: #a1a8ad; font-size: 13px; }
.version-strip { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin-top: 8px; border: 1px solid #e9ebed; border-radius: 5px; }
.version-strip > div { min-width: 0; padding: 8px 10px; display: flex; flex-direction: column; gap: 4px; }
.version-strip > div + div { border-left: 1px solid #e9ebed; }
.version-strip span, .run-meta span { color: #899197; font-size: 9px; }
.version-strip strong { overflow: hidden; color: #3d454b; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.hash-row { min-width: 0; margin-top: 8px; display: flex; align-items: center; gap: 10px; }
.hash-row span { flex: 0 0 145px; color: #858d93; font-size: 9px; }
.hash-row code { overflow: hidden; color: #526579; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.model-actions { margin-top: 14px; padding-top: 13px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-top: 1px solid #eceef0; }
.availability { display: inline-flex; align-items: center; gap: 5px; color: #a07b14; font-size: 10px; }
.availability.ready { color: #43825f; }
.running-note { margin-top: 10px; display: flex; align-items: center; gap: 6px; color: #4b6d8c; font-size: 10px; }
.running-note .el-button { margin-left: auto; }
.run-card { min-height: 340px; }
.run-meta { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) auto; gap: 12px; margin: 3px 0 15px; }
.run-meta > div { min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.run-meta code, .run-meta strong { overflow: hidden; color: #4e5a64; font-size: 10px; font-weight: 550; text-overflow: ellipsis; white-space: nowrap; }
.run-timeline { max-height: 235px; margin: 0; overflow: auto; padding-left: 4px; }
.run-timeline :deep(.el-timeline-item) { padding-bottom: 11px; }
.run-timeline :deep(.el-timeline-item__timestamp) { margin-bottom: 3px; font-size: 9px; }
.run-timeline strong { color: #45515a; font-size: 10px; }
.run-timeline p { margin: 3px 0 0; color: #7b8389; font-size: 9px; line-height: 1.45; }
.run-error { margin-top: 6px; }
.run-loading { min-height: 230px; display: flex; align-items: center; justify-content: center; gap: 8px; color: #818990; font-size: 11px; }
.results-card, .history-card { margin-top: 14px; }
.results-heading, .history-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.results-heading h2, .history-heading h2 { margin: 4px 0 4px; font-size: 16px; font-weight: 650; }
.results-heading p { margin: 0; color: #81888e; font-size: 10px; }
.result-hashes { margin-top: 9px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 18px; }
.result-hashes span { min-width: 0; display: flex; align-items: center; gap: 7px; color: #8a9298; font-size: 9px; }
.result-hashes code { overflow: hidden; color: #526579; text-overflow: ellipsis; white-space: nowrap; }
.result-toolbar { margin: 14px 0 10px; display: flex; align-items: center; gap: 10px; }
.comparison-select { width: 260px; flex: 0 0 260px; }
.equipment-select { width: 210px; flex: 0 0 210px; }
.result-toolbar > .el-input { max-width: 340px; }
.result-count { margin-left: auto; color: #858c92; font-size: 10px; white-space: nowrap; }
.result-table :deep(th.el-table__cell) { color: #6e777d; font-size: 10px; font-weight: 600; }
.result-table :deep(td.el-table__cell) { color: #515b63; font-size: 10px; }
.numeric-value { color: #3f596e; font-variant-numeric: tabular-nums; font-weight: 600; }
.response-grid { margin-top: 13px; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; }
.response-card { min-width: 0; padding: 10px 12px; display: flex; flex-direction: column; gap: 5px; border: 1px solid #e7eaed; border-radius: 6px; background: #fbfcfc; }
.response-card > span { color: #818990; font-size: 9px; }
.response-card > strong { overflow: hidden; color: #3d5669; font-size: 16px; font-variant-numeric: tabular-nums; text-overflow: ellipsis; white-space: nowrap; }
.response-card small { color: #7e898f; font-size: 9px; font-weight: 500; }
.response-card em { color: #658671; font-size: 9px; font-style: normal; }
.response-card em.decrease, .delta-negative { color: #a86858; }
.result-footer { margin-top: 12px; display: flex; align-items: center; justify-content: space-between; gap: 16px; color: #588368; font-size: 9px; }
.result-footer > span { display: inline-flex; align-items: center; gap: 5px; }
.history-heading { margin-bottom: 13px; }
.history-heading > span { color: #969da2; font-size: 9px; }
.history-card :deep(.el-table) { cursor: pointer; }
.history-card :deep(th.el-table__cell) { color: #788087; font-size: 10px; }
.history-card :deep(td.el-table__cell) { font-size: 10px; }
.history-card code { color: #536779; font-size: 10px; }
.scope-note { margin-top: 14px; padding: 13px 15px; display: flex; align-items: flex-start; gap: 10px; border: 1px solid #eadca5; border-radius: 7px; background: #fffdf4; color: #856b18; }
.scope-note > .el-icon { margin-top: 1px; flex: 0 0 auto; font-size: 16px; }
.scope-note strong { font-size: 10px; }
.scope-note p { margin: 4px 0 0; color: #6e6860; font-size: 9px; line-height: 1.6; }
@media (max-width: 900px) {
  .page-header { align-items: flex-start; padding: 12px 18px 17px; }
  .header-actions { margin-top: 35px; }
  .page-content { width: calc(100% - 28px); margin-top: 14px; }
  .overview-grid { grid-template-columns: 1fr; }
}
@media (max-width: 600px) {
  .page-header { display: block; }
  .header-actions { margin: 14px 0 0 56px; justify-content: space-between; }
  .title-row { align-items: flex-start; gap: 9px; }
  .title-icon { width: 38px; height: 38px; font-size: 20px; }
  h1 { font-size: 18px; }
  .title-row p { max-width: 330px; font-size: 10px; line-height: 1.5; }
  .overview-card, .results-card, .history-card { padding: 15px 12px; }
  .engine-chain { gap: 4px; padding: 0 6px; font-size: 8px; }
  .hash-row span { flex-basis: 120px; }
  .run-meta { grid-template-columns: 1fr 1fr; }
  .run-meta > div:first-child { grid-column: span 2; }
  .result-toolbar { flex-wrap: wrap; }
  .result-hashes { grid-template-columns: 1fr; }
  .comparison-select { width: 100%; flex-basis: 100%; }
  .equipment-select { width: 100%; flex-basis: 100%; }
  .result-toolbar > .el-input { max-width: none; flex: 1; }
  .response-grid { grid-template-columns: 1fr; }
  .result-footer { align-items: flex-start; flex-direction: column; }
}
</style>
