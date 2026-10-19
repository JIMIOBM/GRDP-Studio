<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import EclipseThermalRockPanel from './EclipseThermalRockPanel.vue'
import { matchesThermalRock } from './eclipseThermalRock.js'
import EclipseThermalPlanPanel from './EclipseThermalPlanPanel.vue'
import { matchesThermalPlan } from './eclipseThermalPlan.js'
import EclipseThermalInjectionEditor from './EclipseThermalInjectionEditor.vue'
import EclipseThermalSpatialCompare from './EclipseThermalSpatialCompare.vue'
import EclipseScenarioDashboard from './EclipseScenarioDashboard.vue'
import { matchesThermalInjection, thermalInjectionResponse, thermalInjectionLabels, thermalInjectionUnit } from './eclipseThermalInjection'
import EclipseSummaryExplorer from './EclipseSummaryExplorer.vue'
import { thermalResult, thermalObject, thermalCsv, thermalCategory } from './eclipseThermal'

const props = defineProps({ run: { type: Object, default: null }, inspection: { type: Object, default: null }, disabled: Boolean })
const emit = defineEmits(['run-scenario'])
const result = computed(() => thermalResult(props.run, props.inspection))
const baseline = ref(null), baselineError = ref(''), baselineLoading = ref(false)
let baselineGeneration = 0
const scenario = computed(() => matchesThermalInjection(props.run?.parameters, props.inspection) ? props.run.parameters : null)
const multiStagePlan = computed(() => matchesThermalPlan(props.run?.parameters, props.inspection) ? props.run.parameters : null)
const rockScenario = computed(() => matchesThermalRock(props.run?.parameters, props.inspection) ? props.run.parameters : null)
const loadBaseline = async () => {
  const generation = ++baselineGeneration
  baseline.value = null; baselineError.value = ''; baselineLoading.value = false
  if (!scenario.value && !multiStagePlan.value && !rockScenario.value) return
  const id = (scenario.value || multiStagePlan.value || rockScenario.value).baselineRunId
  baselineLoading.value = true
  try {
    const response = await softwareIntegrationApi.getRun(id)
    if (generation !== baselineGeneration) return
    const candidate = response?.data
    if (response?.code !== 200 || candidate?.id !== id || candidate.modelId !== props.run?.modelId || candidate.modelVersionId !== props.run?.modelVersionId ||
      candidate.runType !== 'eclipse' || candidate.status !== 'SUCCEEDED' || candidate.resultContract !== 'VALID_FULL' || candidate.parameters != null || !thermalResult(candidate, props.inspection)) {
      baselineError.value = '热采基线来源或完整结果核对未通过，无法确认方案响应。'; return
    }
    baseline.value = candidate
  } catch { if (generation === baselineGeneration) baselineError.value = '热采基线读取失败，请重新选择运行。' }
  finally { if (generation === baselineGeneration) baselineLoading.value = false }
}
watch([() => props.run?.id, () => props.run?.parameters, () => props.inspection], loadBaseline, { immediate: true })
onBeforeUnmount(() => { baselineGeneration++ })
const injectionResponse = computed(() => scenario.value && result.value && baseline.value ? thermalInjectionResponse(result.value.summary, baseline.value.result.summary, scenario.value, props.inspection) : null)
const scenarioUnit = computed(() => thermalInjectionUnit(scenario.value?.unit))
const category = ref('temperature'), hideUndefined = ref(true)
watch(() => props.run?.id, () => { category.value = 'temperature'; hideUndefined.value = true })
const selected = computed(() => result.value?.summary.series.filter(s => (!hideUndefined.value || s.objectName !== ':+:+:+:+') &&
  (category.value === 'all' || thermalCategory(s.keyword) === category.value)) || [])
const rows = computed(() => selected.value.map(s => ({ ...s, object: thermalObject(s, props.inspection.dimensions), time: s.points.at(-1).timeDays, last: s.points.at(-1).value })))
const plotted = computed(() => rows.value.map(s => ({ keyword: s.keyword, unit: s.unit, points: s.points, objectName: s.object + ' [' + (s.vectorIndex + 1) + ']', sourceRunId: props.run.id })))
const exportAll = () => {
  if (!result.value) return
  const url = URL.createObjectURL(new Blob(['\uFEFF' + thermalCsv(result.value)], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = 'E300-热采-' + props.run.id + '-完整原始曲线.csv'; link.click(); URL.revokeObjectURL(url)
}
</script>

<template>
  <section aria-label="ECLIPSE 300 热采计算结果">
    <el-empty v-if="!run" description="选择热采运行后查看结果" />
    <el-alert v-else-if="!result" type="warning" :closable="false" title="热采结果或模型来源核对未通过，请查看诊断。" />
    <template v-else>
      <h2>ECLIPSE 300 · 蒸汽驱热采</h2>
      <p>LIVEOIL热物性；{{ result.simulation.componentCount }}种油组分：{{ result.simulation.componentNames.join('、') }}，水与蒸汽单独参与热计算。起算{{ result.summary.startDate }}，完整保留{{ result.summary.series.length }}条曲线、每条{{ result.summary.series[0].points.length }}个实际时刻。</p>
      <p>Comment {{ result.eclEnd.comments }} · Warning {{ result.eclEnd.warnings }} · Problem {{ result.eclEnd.problems }} · Error {{ result.eclEnd.errors }} · Bug {{ result.eclEnd.bugs }}</p>
      <el-alert v-if="run.resultContract === 'VALID_PARTIAL'" type="warning" :closable="false" title="求解存在Problem，此运行不能作为完整成功方案。" />
      <EclipseThermalInjectionEditor :run="run" :inspection="inspection" :disabled="disabled" @run-scenario="emit('run-scenario', $event)" />
      <EclipseThermalPlanPanel :run="run" :inspection="inspection" :baseline="baseline" :disabled="disabled" @run-scenario="emit('run-scenario', $event)"/>
      <EclipseThermalRockPanel :run="run" :inspection="inspection" :baseline="baseline" :disabled="disabled" @run-scenario="emit('run-scenario', $event)"/>
      <el-alert v-if="rockScenario && baselineError" :title="baselineError" type="warning" :closable="false"/>
      <p v-if="multiStagePlan && baselineLoading">正在读取多阶段注汽原基线…</p>
      <el-alert v-if="multiStagePlan && baselineError" :title="baselineError" type="warning" :closable="false"/>
      <EclipseScenarioDashboard v-if="multiStagePlan && baseline" :run="run" :baseline="baseline" kind="thermal"/>
      <section v-if="scenario" aria-label="E300热采注汽方案验收">
        <EclipseScenarioDashboard v-if="baseline" :run="run" :baseline="baseline" kind="thermal"/>
        <h3>{{ scenario.well }} {{ thermalInjectionLabels[scenario.field] }} {{ scenario.originalValue }} → {{ scenario.targetValue }} {{ scenarioUnit }}</h3>
        <p v-if="scenario.startTimeDays">第{{ scenario.startTimeDays }}天报告后生效；此前使用原值，后续时段使用新值。</p>
        <p>基线 #{{ scenario.baselineRunId }}；来源 {{ scenario.sourceFile }}:{{ scenario.lineNumber }}。只修改上述一项，其余注入参数与原注采计划保留。</p>
        <p v-if="baselineLoading">正在读取原热采基线…</p>
        <el-alert v-else-if="baselineError || !injectionResponse" :title="baselineError || '缺少唯一的井压或热损失曲线，无法核对物理响应。'" type="warning" :closable="false" />
        <template v-else>
          <el-alert :type="run.resultContract === 'VALID_FULL' && injectionResponse.verified ? 'success' : 'warning'" :closable="false"
            :title="run.resultContract !== 'VALID_FULL' ? '部分成功，不能判定完整方案通过' : !injectionResponse.consistent ? '实际注入井压或地面注入量超过方案约束，请查看诊断' : injectionResponse.verified ? '注汽输入已修改，实际井压与热损失响应已核对' : '当前结果未形成完整可核对的井压与热损失响应'" />
          <p v-if="scenario.startTimeDays">调整前完整物理曲线核对：{{ injectionResponse.preStageVerified ? '与原基线一致' : '未通过，请核对诊断' }}；第{{ scenario.startTimeDays }}天报告值归入调整前，之后的报告值用于核对新目标。</p>
          <p>本方案BHP上限 {{ injectionResponse.bhpUpperLimit }} {{ injectionResponse.pressureUnit }}；地面注入目标 {{ injectionResponse.surfaceInjectionTarget }} {{ injectionResponse.rateUnit }}。累计量取原生WWIT，压力限注时实际量可能低于目标；输入温度用于定义焓，不等于实际井温。仅对齐原生共有时刻，不插值。</p>
          <p v-if="!injectionResponse.temperatureAvailable">原工程未请求注入井WTEMP，没有实际井温曲线；仍可查看井压、热损失与产量，未请求温度不补算。</p>
          <p v-if="injectionResponse.pressureLimited">部分时刻触及本方案注入压力上限，请结合实际注入量审阅。</p>
          <el-table :data="injectionResponse.metrics" border size="small" aria-label="E300注汽方案末次实际指标">
            <el-table-column prop="label" label="指标" /><el-table-column prop="unit" label="原生单位" /><el-table-column prop="pointCount" label="实际点数" /><el-table-column prop="commonCount" label="共有点数" />
            <el-table-column prop="changed" label="响应点数" /><el-table-column prop="finalTime" label="末次实际天数" /><el-table-column prop="finalValue" label="末次实际值" />
          </el-table>
          <el-table :data="injectionResponse.rows" max-height="300" border size="small" aria-label="E300注汽方案实际响应明细">
            <el-table-column v-if="scenario.startTimeDays" prop="phase" label="生效阶段" /><el-table-column prop="label" label="指标" /><el-table-column prop="unit" label="单位" /><el-table-column prop="timeDays" label="实际天数" />
            <el-table-column prop="baselineValue" label="基线值" /><el-table-column prop="value" label="方案值" /><el-table-column prop="difference" label="共有时刻差值" />
          </el-table>
          <p>下载eclipse-schedule-applied-package.zip，另一项目上传直接计算，应复现本次物理曲线。计算耗时指标按各次原生输出保留。</p>
        </template>
      </section>
      <EclipseThermalSpatialCompare v-if="(scenario || multiStagePlan || rockScenario) && baseline && run.resultContract === 'VALID_FULL'" :run="run" :baseline="baseline" :unit-system="inspection?.unitSystem" />
      <el-select v-model="category" aria-label="E300热采结果类型">
        <el-option label="井温与网格温度" value="temperature" /><el-option label="产汽量与累计产汽" value="steam" />
        <el-option label="热损失与累计热损失" value="heat" /><el-option label="全部原始指标" value="all" />
      </el-select>
      <el-checkbox v-model="hideUndefined">隐藏未定义槽位</el-checkbox>
      <el-button @click="exportAll">导出全部热采原始曲线CSV</el-button>
      <p>BTEMP为网格温度，WTEMP为井温；DEG F是华氏度，DEG C是摄氏度，图表保持原生单位。WINJTEMP的输入温度用于定义注入流体焓，实际井温随井底压力变化，不必等于输入温度。未请求的温度或热指标不会补算。</p>
      <el-table :data="rows" border size="small" max-height="340" empty-text="当前筛选下没有曲线；请在工程SUMMARY中请求需要的热指标" aria-label="E300温度与热指标">
        <el-table-column label="原始列" width="80"><template #default="{ row }">{{ row.vectorIndex + 1 }}</template></el-table-column>
        <el-table-column prop="keyword" label="指标" width="110" /><el-table-column prop="object" label="井／网格／对象" min-width="145" />
        <el-table-column prop="unit" label="原生单位" width="115" /><el-table-column prop="time" label="最后实际时刻（天）" min-width="155" /><el-table-column prop="last" label="最后实际值" min-width="140" />
      </el-table>
      <EclipseSummaryExplorer :series="plotted" :run-id="run.id" />
      <p>在“结果文件”下载eclipse-thermal-input-package.zip，另一项目上传后直接复算。当前支持已核准初始干度、输入温度、地面注入目标和注入BHP上限编辑，以及原报告时刻后的降温/调注和2–8阶段注汽方案；已核准活动网格热容与综合导热率可编辑重算；其它热物性、复杂热控与E300热采重启继续开发。</p>
    </template>
  </section>
</template>
