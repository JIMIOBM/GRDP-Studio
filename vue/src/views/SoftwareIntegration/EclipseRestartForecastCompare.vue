<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import EclipseSummaryExplorer from './EclipseSummaryExplorer.vue'
import { validRestartSource, validRestartForecast, compareRestartForecasts, restartForecastMatchesSource, restartForecastSeries } from './eclipseRestartForecast'

const props = defineProps({ run: { type: Object, default: null }, history: { type: Array, default: () => [] }, inspection: { type: Object, default: null } })
const source = ref(null), historical = ref(null), other = ref(null), otherId = ref(null), loading = ref(false), error = ref('')
const otherError = ref(''), otherLoading = ref(false)
let sourceGeneration = 0, comparisonGeneration = 0
const options = computed(() => props.history.filter(r => r.id !== props.run?.id && r.runType === 'eclipse' && r.status === 'SUCCEEDED' && r.resultContract === 'VALID_FULL' &&
  r.modelVersionId === props.run?.modelVersionId && validRestartForecast(r.parameters) && r.parameters.historyRunId === props.run?.parameters?.historyRunId &&
  r.parameters.restartReport === props.run?.parameters?.restartReport && r.parameters.forecastDataFile !== props.run?.parameters?.forecastDataFile))
watch([() => props.run?.id, () => props.run?.parameters, () => props.inspection], async () => {
  const generation = ++sourceGeneration
  comparisonGeneration++
  source.value = null; historical.value = null; other.value = null; otherId.value = null; error.value = ''; loading.value = false; otherError.value = ''; otherLoading.value = false
  const current = props.run
  if (!validRestartForecast(current?.parameters)) return
  loading.value = true
  try {
    const payload = await softwareIntegrationApi.getRun(current.parameters.historyRunId)
    const history = payload?.data || payload
    if (generation !== sourceGeneration) return
    if (history?.modelId !== current.modelId || history.modelVersionId !== current.modelVersionId) throw new Error('Wrong source version')
    const artifact = history.artifacts?.find(a => a.name === 'eclipse-restart-catalog.json')
    if (!artifact) throw new Error('No catalog')
    const blob = await softwareIntegrationApi.downloadArtifact(history.id, artifact.id)
    if (blob.size !== artifact.sizeBytes || blob.size > 8 * 1024 * 1024) throw new Error('Catalog size mismatch')
    const bytes = await blob.arrayBuffer()
    const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
    const checksum = [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, '0')).join('')
    const catalog = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
    if (generation !== sourceGeneration) return
    if (checksum !== artifact.sha256 || !validRestartSource(catalog) || !restartForecastMatchesSource(current.parameters, catalog, history, props.inspection)) throw new Error('Source does not match')
    source.value = catalog; historical.value = history
  } catch {
    if (generation === sourceGeneration) error.value = '历史报告、日期或物理来源核对失败，暂不能接续对比'
  } finally { if (generation === sourceGeneration) loading.value = false }
}, { immediate: true })
watch(otherId, async id => {
  const generation = ++comparisonGeneration
  other.value = null; otherError.value = ''; otherLoading.value = Boolean(id)
  if (!id) return
  try {
    const payload = await softwareIntegrationApi.getRun(id)
    if (generation !== comparisonGeneration) return
    const candidate = payload?.data || payload
    if (candidate?.id !== id || !options.value.some(r => r.id === id)) throw new Error('Wrong comparison')
    other.value = candidate
  } catch { if (generation === comparisonGeneration) otherError.value = '另一个未来方案读取失败，请重新选择' }
  finally { if (generation === comparisonGeneration) otherLoading.value = false }
})
onBeforeUnmount(() => { sourceGeneration++; comparisonGeneration++ })
const comparison = computed(() => other.value ? compareRestartForecasts({ source: source.value, history: historical.value, first: props.run, second: other.value, inspection: props.inspection }) : null)
const twoStageSeries = computed(() => comparison.value?.valid ? comparison.value.series : restartForecastSeries({ source: source.value, history: historical.value, run: props.run, inspection: props.inspection }))
const number = value => Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
</script>

<template>
  <section class="result-panel" aria-label="ECLIPSE 历史接续与未来方案对比">
    <div class="panel-heading"><h2>历史接续与未来方案对比</h2><el-tag :type="source ? 'success' : 'warning'">{{ loading ? '核对历史中' : source ? '截止状态已核对' : '等待核对' }}</el-tag></div>
    <p v-if="source">历史 #{{ historical.id }} 截止 {{ run.parameters.restartDate }}（第 {{ run.parameters.restartTimeDays }} 天）；共同起算日期 {{ source.catalog.startDate }}。历史和未来分别绘制，未来增量按原生累计产油相减。</p>
    <el-alert v-if="error" type="warning" :closable="false" :title="error" />
    <label v-if="source">另一个未来方案
      <el-select v-model="otherId" clearable :loading="otherLoading" :disabled="!source" placeholder="先分别运行两个未来方案，再选择对比" aria-label="ECLIPSE 另一个未来方案">
        <el-option v-for="item in options" :key="item.id" :value="item.id" :label="`#${item.id} · ${item.parameters.forecastDataFile}`" />
      </el-select>
    </label>
    <el-alert v-if="otherError" type="warning" :closable="false" :title="otherError" />
    <el-alert v-if="comparison" :type="comparison.valid ? 'success' : 'warning'" :closable="false" :title="comparison.reason" />
    <el-table v-if="comparison?.valid" :data="comparison.rows" border size="small" max-height="360" aria-label="ECLIPSE 未来累计产油与新增产油">
      <el-table-column prop="keyword" label="指标" width="90" /><el-table-column prop="objectName" label="对象" width="100" /><el-table-column prop="date" label="实际日期" width="125" />
      <el-table-column prop="unit" label="单位" width="90" />
      <el-table-column label="历史累计" min-width="125"><template #default="{ row }">{{ number(row.historical) }}</template></el-table-column>
      <el-table-column :label="`当前 #${run.id} 新增`" min-width="145"><template #default="{ row }">{{ number(row.firstIncrement) }}</template></el-table-column>
      <el-table-column :label="`对比 #${other.id} 新增`" min-width="145"><template #default="{ row }">{{ number(row.secondIncrement) }}</template></el-table-column>
      <el-table-column label="新增产油差值" min-width="130"><template #default="{ row }">{{ number(row.difference) }}</template></el-table-column>
    </el-table>
    <EclipseSummaryExplorer v-if="twoStageSeries.length" :series="twoStageSeries" :run-id="run.id" />
    <p v-if="source">完整工程下载：在本页“结果文件”下载 eclipse-forecast-applied-package.zip，上传为新模型后直接计算。工程含选定历史状态及所需输入。</p>
  </section>
</template>
