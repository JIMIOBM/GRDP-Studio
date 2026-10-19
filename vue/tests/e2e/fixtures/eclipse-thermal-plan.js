import { createApp, h, ref } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as echarts from 'echarts'
import Panel from '../../../src/views/SoftwareIntegration/EclipseThermalPlanPanel.vue'
const data = await (await fetch('./thermal-plan-source.json')).json()
globalThis.thermalPlanChart = host => echarts.getInstanceByDom(host)?.getOption()
globalThis.thermalPlanEmitted = []
createApp({ setup() {
  const mode = ref('编辑计划'), disabled = ref(false)
  return () => {
    const key = { '两阶段计算': 'two-stage', '三阶段恢复': 'restore', '压力限注': 'pressure-limited' }[mode.value]
    return h('main', {}, [h('nav', {}, ['编辑计划', '两阶段计算', '三阶段恢复', '压力限注', '禁用操作'].map(label => h('button', { onClick: () => {
      if (label === '禁用操作') disabled.value = !disabled.value; else mode.value = label
    } }, label))), h(Panel, { key: mode.value, run: key ? data.plans[key] : data.baseline, inspection: data.inspection, baseline: key ? data.baseline : null,
      disabled: disabled.value, onRunScenario: p => globalThis.thermalPlanEmitted.push(p) })])
  }
} }).use(ElementPlus).mount('#app')
