import { createApp, h, ref } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import EclipseScenarioDashboard from '../../../src/views/SoftwareIntegration/EclipseScenarioDashboard.vue'
import EclipseThermalSpatialCompare from '../../../src/views/SoftwareIntegration/EclipseThermalSpatialCompare.vue'
import EclipseHistoryRanking from '../../../src/views/SoftwareIntegration/EclipseHistoryRanking.vue'
import EclipseGrid3D from '../../../src/views/SoftwareIntegration/EclipseGrid3D.vue'
import EclipseBatchResponseChart from '../../../src/views/SoftwareIntegration/EclipseBatchResponseChart.vue'
import * as echarts from 'echarts'
globalThis.readVisualChartOptions = element => echarts.getInstanceByDom(element)?.getOption()

// Read-only component integration fixture; supplied runs and evaluations are fetched from the actual local API.
const source = await (await fetch('./visual-business-source.json')).json()
createApp({ setup() {
  const mode = ref('热采'), scenario = ref('rate'), selectedRun = ref(null)
  return () => {
    const run = source[scenario.value], baseline = source.baseline
    return h('main', {}, [h('nav', {}, ['热采','组分','批量','历史排名','三维'].map(label => h('button', { onClick: () => { mode.value = label } }, label))),
      mode.value === '热采' ? h('div', {}, [h('nav', {}, [h('button', { onClick: () => { scenario.value = 'rate' } }, '调注方案1825'), h('button', { onClick: () => { scenario.value = 'temperature' } }, '降温方案1827')]),
        h(EclipseScenarioDashboard, { run, baseline, kind: 'thermal' }), h(EclipseThermalSpatialCompare, { run, baseline, unitSystem: source.inspection.unitSystem })]) : null,
      mode.value === '组分' ? h(EclipseScenarioDashboard, { run: source.compositional, baseline: source.compositionalBaseline, kind: 'compositional' }) : null,
      mode.value === '批量' ? h(EclipseBatchResponseChart, { rows: source.batchRows }) : null,
      mode.value === '历史排名' ? h('div', {}, [h('p', {}, '观察数据为合成观察，用于链路演示，不是现场实测。'), h(EclipseHistoryRanking, { comparison: source.ranking, metricLabel: source.metricLabel, onSelectRun: id => { selectedRun.value = id } }), h('p', { 'data-testid': 'ranking-selected-run' }, String(selectedRun.value || ''))]) : null,
      mode.value === '三维' ? h(EclipseGrid3D, { run, runId: run.id, grid: run.result.grid, fields: run.result.fieldIndex.files.flatMap(file => file.fields.map(field => ({ ...field, file: file.name, byteOrder: file.byteOrder }))), artifacts: run.artifacts, unitSystem: source.inspection.unitSystem }) : null])
  }
} }).use(ElementPlus).mount('#app')
