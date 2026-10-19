<script setup>
import EclipseForecastDashboard from './EclipseForecastDashboard.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { compositionalResult } from './eclipseCompositional'
import { compositionalSaveCatalogName, validCompositionalForecast, validCompositionalPortableContext, compositionalSaveMatchesInspection,
  compositionalSaveMatchesResult, compositionalForecastMatches, buildCompositionalForecast, compositionalForecastEvidence } from './eclipseCompositionalForecast'
const props = defineProps({ run: Object, inspection: Object, history: { type: Array, default: () => [] }, disabled: Boolean })
const emit = defineEmits(['run-scenario','select-run'])
const catalog = ref(null), artifact = ref(null), historical = ref(null), loading = ref(false), error = ref(''), target = ref(null), inputError = ref('')
const otherId = ref(null), other = ref(null), otherLoading = ref(false), otherError = ref('')
let generation = 0, comparisonGeneration = 0
const portable = computed(() => validCompositionalPortableContext(props.inspection?.compositionalRestartContext, props.inspection) ? props.inspection.compositionalRestartContext : null)
const scenario = computed(() => validCompositionalForecast(props.run?.parameters) ? props.run.parameters : null)
const historyCandidate = computed(() => ['eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(props.inspection?.schemaVersion) && props.run?.parameters == null &&
  props.run?.status === 'SUCCEEDED' && props.run?.resultContract === 'VALID_FULL' && props.run?.artifacts?.some(a => a.name === compositionalSaveCatalogName))
const shown = computed(() => historyCandidate.value || scenario.value || portable.value)
watch([() => props.run?.id, () => props.run?.parameters, () => props.run?.artifacts, () => props.inspection], async () => {
  const current = ++generation
  comparisonGeneration++; otherId.value = null; other.value = null; otherError.value = ''; otherLoading.value = false
  catalog.value = null; artifact.value = null; historical.value = null; error.value = ''; loading.value = false; target.value = null; inputError.value = ''
  if (portable.value) { catalog.value = portable.value.historyCatalog; return }
  if (!historyCandidate.value && !scenario.value) return
  loading.value = true
  try {
    let history = props.run
    if (scenario.value) {
      const response = await softwareIntegrationApi.getRun(scenario.value.historyRunId)
      if (current !== generation) return
      history = response?.data
      if (response?.code !== 200 || history?.id !== scenario.value.historyRunId || history.modelId !== props.run?.modelId || history.modelVersionId !== props.run?.modelVersionId)
        throw new Error('Foreign history')
    }
    if (history?.status !== 'SUCCEEDED' || history.resultContract !== 'VALID_FULL' || history.parameters != null || history.runType !== 'eclipse' || !compositionalResult(history, props.inspection))
      throw new Error('History is not a complete baseline')
    const descriptors = history.artifacts?.filter(a => a.name === compositionalSaveCatalogName)
    if (descriptors?.length !== 1) throw new Error('Missing history catalog')
    const descriptor = descriptors[0]
    if (descriptor.contentType !== 'application/json' || descriptor.sizeBytes < 1 || descriptor.sizeBytes > 64 * 1024) throw new Error('Invalid history catalog')
    const blob = await softwareIntegrationApi.downloadArtifact(history.id, descriptor.id)
    if (current !== generation) return
    if (blob.size !== descriptor.sizeBytes) throw new Error('History size mismatch')
    const bytes = await blob.arrayBuffer(), digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
    const checksum = [...new Uint8Array(digest)].map(n => n.toString(16).padStart(2, '0')).join('')
    const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
    if (current !== generation) return
    if (checksum !== descriptor.sha256 || !compositionalSaveMatchesInspection(value, props.inspection) || !compositionalSaveMatchesResult(value, history.result) ||
      value.files.some(f => history.artifacts.filter(a => a.name === f.artifactName && a.sha256 === f.sha256 && a.sizeBytes === f.sizeBytes && a.contentType === 'application/octet-stream').length !== 1) ||
      scenario.value && (scenario.value.saveCatalogSha256 !== checksum || !compositionalForecastMatches(scenario.value, value, props.inspection)))
      throw new Error('History source mismatch')
    catalog.value = value; artifact.value = descriptor; historical.value = history
  } catch { if (current === generation) error.value = '历史保存点来源、完整状态文件或校验值不匹配，请重新计算原模型后选择保存点。' }
  finally { if (current === generation) loading.value = false }
}, { immediate: true })
const alternatives = computed(() => scenario.value ? props.history.filter(r => r.id !== props.run?.id && r.runType === 'eclipse' && r.status === 'SUCCEEDED' && r.resultContract === 'VALID_FULL' && r.modelId === props.run?.modelId && r.modelVersionId === props.run?.modelVersionId && validCompositionalForecast(r.parameters) && r.parameters.historyRunId === scenario.value.historyRunId && r.parameters.saveCatalogSha256 === scenario.value.saveCatalogSha256 && r.parameters.saveReport === scenario.value.saveReport && r.parameters.targetGasRate !== scenario.value.targetGasRate) : [])
watch(otherId, async id => {
  const current = ++comparisonGeneration
  other.value = null; otherError.value = ''; otherLoading.value = !!id
  if (!id) return
  try {
    const response = await softwareIntegrationApi.getRun(id)
    if (current !== comparisonGeneration) return
    const value = response?.data
    if (response?.code !== 200 || value?.id !== id || !alternatives.value.some(r => r.id === id) || value.modelId !== props.run?.modelId || value.modelVersionId !== props.run?.modelVersionId) throw new Error('Wrong forecast')
    other.value = value
  } catch { if (current === comparisonGeneration) otherError.value = '对比预测读取失败或来源已变化，请重新选择。' }
  finally { if (current === comparisonGeneration) otherLoading.value = false }
})
onBeforeUnmount(() => { generation++; comparisonGeneration++ })
const submit = value => {
  if (props.disabled || !historyCandidate.value || !catalog.value) return
  const p = buildCompositionalForecast({ inspection: props.inspection, historyRun: historical.value, catalog: catalog.value, artifact: artifact.value, targetGasRate: value })
  if (!p) { inputError.value = '新产气目标须大于0、不超过100000000且不同于原目标，单位Mscf/day。'; return }
  inputError.value = ''; emit('run-scenario', p)
}
const evidence = computed(() => (scenario.value || portable.value) && catalog.value && compositionalResult(props.run, props.inspection)
  ? compositionalForecastEvidence(props.run.result, catalog.value, props.inspection.wellNames) : null)
</script>
<template>
  <section v-if="shown" aria-label="E300历史保存点续算">
    <h3>{{ portable ? '导入的E300续算模型' : scenario ? 'E300历史状态预测方案' : '从E300历史保存点继续计算' }}</h3>
    <p v-if="loading">正在核对历史保存点…</p>
    <el-alert v-else-if="error" type="warning" :closable="false" :title="error" />
    <template v-else-if="catalog">
      <p>保存点：第{{ catalog.timeDays }}天（{{ catalog.date }}），报告{{ catalog.reportNumber }}；原起算日期{{ catalog.startDate }}，预测至第{{ catalog.endTimeDays }}天。</p>
      <p v-if="portable">网格、井和组分继承包内原模型；{{ portable.targetGasRate === null ? '沿原计划续算' : `新产气目标 ${portable.targetGasRate} Mscf/day` }}。可直接计算，无需再选择历史运行。</p>
      <p v-else>历史运行 #{{ historical?.id }}；保留原组分、网格、压力约束、分离器调整及原注气关井安排。</p>
      <label v-if="scenario" class="forecast-alternative">比较同一保存点的另一个预测
        <el-select v-model="otherId" clearable :loading="otherLoading" aria-label="E300对比预测方案" placeholder="选择已经计算的另一产气方案"><el-option v-for="r in alternatives" :key="r.id" :value="r.id" :label="`#${r.id} · ${r.parameters.targetGasRate == null ? '沿原计划续算' : `产气目标 ${r.parameters.targetGasRate} Mscf/day`}`"/></el-select>
      </label>
      <el-alert v-if="otherError" type="warning" :closable="false" :title="otherError"/>
      <EclipseForecastDashboard kind="e300" :catalog="catalog" :history="historical" :run="run" :other="other" :inspection="inspection" :portable="!!portable" @select-run="emit('select-run', $event)"/>
      <el-form v-if="historyCandidate" label-position="top" :disabled="disabled" @submit.prevent="submit(target)">
        <el-button :disabled="disabled" @click="submit(null)">沿原计划续算</el-button>
        <el-form-item label="续算产气目标（Mscf/day）"><el-input-number v-model="target" :controls="false" aria-label="E300续算产气目标" /></el-form-item>
        <el-alert v-if="inputError" type="error" :closable="false" :title="inputError" />
        <el-button type="primary" :disabled="disabled || target == null" @click="submit(target)">修改产气目标并续算</el-button>
        <p>两个方案分别从同一历史状态开始；修改只作用于保存点之后。</p>
      </el-form>
      <template v-if="scenario || portable">
        <el-alert v-if="!evidence" type="warning" :closable="false" title="预测时钟或原生结果未能与保存点对齐，请查看诊断。" />
        <template v-else>
          <el-alert :type="evidence.complete ? 'success' : 'warning'" :closable="false"
            :title="evidence.complete ? '已延续原日期并覆盖完整预测时段' : '预测未覆盖完整成功时段，不能判定完整方案通过'" />
          <p>原生{{ evidence.rawVectorCount }}条曲线、{{ evidence.actualTimes }}个实际时刻，第{{ evidence.startDays }}至{{ evidence.endDays }}天；累计量使用求解器原始值。</p>
          <el-table :data="evidence.metrics" max-height="300" size="small" border aria-label="E300续算原生产量压力累计">
            <el-table-column prop="vectorIndex" label="原始列号" /><el-table-column prop="keyword" label="指标" />
            <el-table-column label="对象"><template #default="{row}">{{ row.objectName || '全场' }}</template></el-table-column>
            <el-table-column prop="unit" label="单位" /><el-table-column prop="first.value" label="首个预测报告值" /><el-table-column prop="last.value" label="预测末值" />
          </el-table>
          <p>同名指标各自保留原始列号；实际报告时刻不插值、不从零重新编号。</p>
          <p>下载eclipse-forecast-applied-package.zip，完整上传到新项目，主文件选{{ inspection.caseName }}直接运行。包内包含预测输入、原模型和完整历史状态。</p>
        </template>
      </template>
    </template>
  </section>
</template>
