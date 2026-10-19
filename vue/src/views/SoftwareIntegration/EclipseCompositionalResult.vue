<script setup>
import EclipseHistoryPanel from './EclipseHistoryPanel.vue'
import EclipseBatchPanel from './EclipseBatchPanel.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import EclipseCompositionalProductionEditor from './EclipseCompositionalProductionEditor.vue'
import EclipseCompositionalPressurePanel from './EclipseCompositionalPressurePanel.vue'
import EclipseCompositionalReinjectionPanel from './EclipseCompositionalReinjectionPanel.vue'
import EclipseCompositionalGasSalesPanel from './EclipseCompositionalGasSalesPanel.vue'
import EclipseCompositionalInjectionPressurePanel from './EclipseCompositionalInjectionPressurePanel.vue'
import EclipseCompositionalFluidPropertyPanel from './EclipseCompositionalFluidPropertyPanel.vue'
import EclipseCompositionalForecastPanel from './EclipseCompositionalForecastPanel.vue'
import { matchesCompositionalProduction, compositionalProductionResponse } from './eclipseCompositionalProduction'
import EclipseSummaryExplorer from './EclipseSummaryExplorer.vue'
import EclipseScenarioDashboard from './EclipseScenarioDashboard.vue'
import { componentOf, compositionalResult, compositionalObject, compositionalCsv } from './eclipseCompositional'

const props = defineProps({ run: { type: Object, default: null }, inspection: { type: Object, default: null }, history: { type: Array, default: () => [] }, disabled: Boolean })
const emit = defineEmits(['run-scenario','select-run'])
const result = computed(() => compositionalResult(props.run, props.inspection))
const forecast = computed(() => props.inspection?.schemaVersion === 'eclipse-data-inspection/18' || props.run?.parameters?.schemaVersion === 'eclipse-compositional-forecast-parameters/1')
const baseline = ref(null), baselineError = ref(''), baselineLoading = ref(false)
let baselineGeneration = 0
const scenario = computed(() => matchesCompositionalProduction(props.run?.parameters, props.inspection) ? props.run.parameters : null)
const loadBaseline = async () => {
  const generation = ++baselineGeneration
  baseline.value = null; baselineError.value = ''; baselineLoading.value = false
  const id = props.run?.parameters?.baselineRunId
  if (!Number.isSafeInteger(id) || id <= 0) return
  baselineLoading.value = true
  try {
    const response = await softwareIntegrationApi.getRun(id)
    if (generation !== baselineGeneration) return
    const candidate = response?.data
    if (response?.code !== 200 || candidate?.id !== id || candidate.modelId !== props.run?.modelId || candidate.modelVersionId !== props.run?.modelVersionId ||
      candidate.runType !== 'eclipse' || candidate.status !== 'SUCCEEDED' || candidate.resultContract !== 'VALID_FULL' || candidate.parameters != null || !compositionalResult(candidate, props.inspection)) {
      baselineError.value = '基线来源或完整结果核对未通过，无法确认方案响应。'; return
    }
    baseline.value = candidate
  } catch { if (generation === baselineGeneration) baselineError.value = '基线读取失败，请重新选择运行。' }
  finally { if (generation === baselineGeneration) baselineLoading.value = false }
}
watch([() => props.run?.id, () => props.run?.parameters, () => props.inspection], loadBaseline, { immediate: true })
onBeforeUnmount(() => { baselineGeneration++ })
const targetResponse = computed(() => scenario.value && result.value && baseline.value ? compositionalProductionResponse(result.value.summary, baseline.value.result.summary, scenario.value, props.inspection) : null)
const componentIndex = ref(null), hideUndefined = ref(true)
watch(() => props.run?.id, () => { componentIndex.value = null; hideUndefined.value = true })
const selected = computed(() => result.value?.summary.series.filter(s => (!hideUndefined.value || s.objectName !== ':+:+:+:+') &&
  (!componentIndex.value || componentOf(s, result.value.simulation)?.index === componentIndex.value)) || [])
const rows = computed(() => selected.value.map(s => ({ ...s, object: compositionalObject(s, props.inspection.dimensions),
  component: componentOf(s, result.value.simulation)?.name || '—', last: s.points.at(-1).value, time: s.points.at(-1).timeDays })))
const plotted = computed(() => rows.value.map(s => ({ keyword: s.keyword, unit: s.unit, points: s.points,
  objectName: `${s.object}${s.component === '—' ? '' : ' · ' + s.component} [${s.vectorIndex + 1}]`, sourceRunId: props.run.id })))
const exportAll = () => {
  if (!result.value) return
  const url = URL.createObjectURL(new Blob(['\uFEFF' + compositionalCsv(result.value)], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = `E300-${props.run.id}-完整原始曲线.csv`; link.click(); URL.revokeObjectURL(url)
}
</script>

<template>
  <section aria-label="ECLIPSE 300 组分计算结果">
    <el-empty v-if="!run" description="选择组分计算运行后查看结果" />
    <el-alert v-else-if="!result" type="warning" :closable="false" title="组分结果或模型来源核对未通过，请查看运行诊断；不展示合并或推算曲线。" />
    <template v-else>
      <h2>ECLIPSE 300 · {{ result.simulation.componentCount }}组分</h2>
      <p>Peng-Robinson状态方程；起算{{ result.summary.startDate }}。工程请求的{{ result.summary.series.length }}条原始曲线、每条{{ result.summary.series[0].points.length }}个实际时刻完整保留。</p>
      <el-alert v-if="run.resultContract === 'VALID_PARTIAL'" type="warning" :closable="false" :title="`求解报告${result.eclEnd.problems}个Problem，请查看诊断；此运行不能作为完整成功方案。`" />
      <p>Comment {{ result.eclEnd.comments }} · Warning {{ result.eclEnd.warnings }} · Problem {{ result.eclEnd.problems }} · Error {{ result.eclEnd.errors }} · Bug {{ result.eclEnd.bugs }}</p>
      <EclipseScenarioDashboard v-if="baseline" :run="run" :baseline="baseline" kind="compositional"/>
      <EclipseHistoryPanel :run="run" @select-run="emit('select-run', $event)" />
    <EclipseBatchPanel :run="run" :inspection="inspection" @select-run="emit('select-run', $event)" />
      <EclipseCompositionalProductionEditor :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <EclipseCompositionalForecastPanel :run="run" :inspection="inspection" :history="history" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" @select-run="emit('select-run', $event)" />
      <EclipseCompositionalPressurePanel :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <EclipseCompositionalReinjectionPanel :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <EclipseCompositionalGasSalesPanel :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <EclipseCompositionalInjectionPressurePanel :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <EclipseCompositionalFluidPropertyPanel :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <EclipseCompositionalFluidPropertyPanel field="TCRIT" :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <EclipseCompositionalFluidPropertyPanel v-for="field in ['MW', 'OMEGAA', 'OMEGAB']" :key="field" :field="field" :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <EclipseCompositionalFluidPropertyPanel field="ACF" :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <EclipseCompositionalFluidPropertyPanel field="ZCRIT" :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <section v-if="scenario" aria-label="E300产气目标方案验收">
        <h3>{{ scenario.well }} 产气目标 {{ scenario.originalGasRate }} → {{ scenario.targetGasRate }} {{ scenario.unit }}</h3>
        <p>基线 #{{ scenario.baselineRunId }}；来源 {{ scenario.sourceFile }}:{{ scenario.lineNumber }}。</p>
        <p v-if="baselineLoading">正在读取原基线…</p>
        <el-alert v-else-if="baselineError || !targetResponse" type="warning" :closable="false" :title="baselineError || '缺少唯一的井产气与压力曲线，无法核对。'" />
        <template v-else>
          <el-alert :type="targetResponse.verified && targetResponse.consistent ? 'success' : 'warning'" :closable="false" :title="!targetResponse.consistent ? '实际产气或井压力与原约束不一致，请核对诊断' : targetResponse.limited ? '部分时刻触及原BHP下限，实际产气低于目标' : targetResponse.verified ? '产气目标已达到，实际井压与基线响应已核对' : '产气目标响应尚不能完整确认'" />
          <p>全部{{ targetResponse.rows.length }}个实际时刻；达标{{ targetResponse.attained }}点，压力受限{{ targetResponse.limited }}点，共有时刻响应{{ targetResponse.changed }}点。BHP下限{{ targetResponse.bhpLimit }} {{ targetResponse.pressureUnit }}。</p>
          <p>仅对齐原生共有时刻；基线未输出的时刻留空，不插值。</p>
          <el-table :data="targetResponse.rows" max-height="300" border size="small" aria-label="E300产气目标实际响应">
            <el-table-column prop="timeDays" label="实际天数" /><el-table-column prop="baselineGasRate" :label="`基线产气 ${targetResponse.flowUnit}`" /><el-table-column prop="gasRate" :label="`方案产气 ${targetResponse.flowUnit}`" />
            <el-table-column prop="baselineBhp" :label="`基线井压 ${targetResponse.pressureUnit}`" /><el-table-column prop="bhp" :label="`方案井压 ${targetResponse.pressureUnit}`" /><el-table-column prop="pressureLimited" label="压力受限" />
          </el-table>
          <p>下载eclipse-schedule-applied-package.zip，在另一项目上传直接计算，应复现本方案物理结果。</p>
        </template>
      </section>
      <label>组分
        <el-select v-model="componentIndex" clearable placeholder="全部指标" aria-label="E300结果组分">
          <el-option v-for="(name, i) in result.simulation.componentNames" :key="i" :value="i + 1" :label="`${i + 1} · ${name}`" />
        </el-select>
      </label>
      <el-checkbox v-model="hideUndefined">隐藏未定义槽位</el-checkbox>
      <el-button @click="exportAll">导出全部原始曲线CSV</el-button>
      <p>WXMF为生产井液相组分摩尔分数，BXMF/BYMF为网格液相/气相组分摩尔分数，数值无量纲。仅显示工程实际请求的组分；未请求的组分不补算。重复曲线保留各自原始列编号，未定义槽位可取消隐藏查看。</p>
      <el-table :data="rows" border size="small" max-height="340" empty-text="当前筛选下没有曲线；未请求的组分不会补算" aria-label="E300组分与原始指标">
        <el-table-column label="原始列" width="80"><template #default="{ row }">{{ row.vectorIndex + 1 }}</template></el-table-column>
        <el-table-column prop="keyword" label="指标" width="110" /><el-table-column prop="object" label="井／网格／对象" min-width="145" />
        <el-table-column prop="component" label="组分" width="100" /><el-table-column prop="unit" label="原生单位" width="115" />
        <el-table-column prop="time" label="最后实际时刻（天）" min-width="155" /><el-table-column prop="last" label="最后实际值" min-width="140" />
      </el-table>
      <EclipseSummaryExplorer :series="plotted" :run-id="run.id" />
      <p v-if="!forecast">在“结果文件”下载eclipse-compositional-input-package.zip，另一项目上传后直接按E300复算。当前支持已核准初始GRAT产气目标、生产BHP下限、可用气回注比例、固定销售目标、注气BHP上限、PCRIT临界压力、TCRIT临界温度、ZCRIT临界Z因子、ACF偏心因子、MW分子量和OMEGAA/OMEGAB系数编辑及合格历史保存点续算；其它组分物性和复杂续算继续开发。</p>
    </template>
  </section>
</template>
