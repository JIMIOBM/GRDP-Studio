import { createApp, h, ref } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as echarts from 'echarts'
import E100 from '../../../src/views/SoftwareIntegration/EclipseRestartForecastCompare.vue'
import E300 from '../../../src/views/SoftwareIntegration/EclipseCompositionalForecastPanel.vue'
const source = await (await fetch('./forecast-visual-source.json')).json()
globalThis.forecastChart = el => echarts.getInstanceByDom(el)?.getOption()
globalThis.forecastSelectedRuns = []; globalThis.forecastEmittedPlans = []
createApp({ setup() {
  const mode = ref('E100')
  return () => {
    const s = source[mode.value === 'E100' ? 'e100' : mode.value === '导入续算' ? 'portable' : 'e300']
    return h('main',{},[h('nav',{},['E100','E300','历史保存点','导入续算'].map(label=>h('button',{onClick:()=>{mode.value=label}},label))), h(mode.value === 'E100' ? E100:E300,{ key:mode.value,run:mode.value==='历史保存点'?s.history:s.run,inspection:s.inspection,history:[s.run,s.other,s.history].filter(Boolean),onSelectRun:id=>globalThis.forecastSelectedRuns.push(id),onRunScenario:parameters=>globalThis.forecastEmittedPlans.push(parameters)})])
  }
} }).use(ElementPlus).mount('#app')
