import { createApp, h, ref } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as echarts from 'echarts'
import Panel from '../../../src/views/SoftwareIntegration/EclipseThermalRockPanel.vue'
const data = await (await fetch('./thermal-rock-source.json')).json()
globalThis.thermalRockChart = host => echarts.getInstanceByDom(host)?.getOption()
globalThis.thermalRockEmitted = []
createApp({ setup() {
  const mode = ref('编辑原模型'), disabled = ref(false)
  return () => {
    const key = { '热容方案': 'heat-capacity', '导热率方案': 'conductivity' }[mode.value]
    return h('main', {}, [h('nav', {}, ['编辑原模型', '热容方案', '导热率方案', '禁用操作'].map(label => h('button', { onClick: () => {
      if (label === '禁用操作') disabled.value = !disabled.value; else mode.value = label
    } }, label))), h(Panel, { key: mode.value, run: key ? data.cases[key] : data.baseline, inspection: data.inspection, baseline: key ? data.baseline : null,
      disabled: disabled.value, onRunScenario: p => globalThis.thermalRockEmitted.push(p) })])
  }
} }).use(ElementPlus).mount('#app')
