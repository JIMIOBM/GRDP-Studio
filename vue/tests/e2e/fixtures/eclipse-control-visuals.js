import { createApp, h, ref } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as echarts from 'echarts'
import EclipseControlTimeline from '../../../src/views/SoftwareIntegration/EclipseControlTimeline.vue'
import EclipseGroupAllocation from '../../../src/views/SoftwareIntegration/EclipseGroupAllocation.vue'
import { controlPlanResponse } from '../../../src/views/SoftwareIntegration/eclipseControlPlan.js'
import { waterInjectionPlanResponse } from '../../../src/views/SoftwareIntegration/eclipseWaterInjectionPlan.js'
import { groupStructureResponse } from '../../../src/views/SoftwareIntegration/eclipseGroupStructure.js'
const source = await (await fetch('./control-visuals-source.json')).json()
globalThis.readControlChart = el => echarts.getInstanceByDom(el)?.getOption()
createApp({ setup() {
  const selected = ref('oil')
  return () => {
    const { run, baseline, inspection } = source[selected.value], injection = selected.value.startsWith('water'), group = selected.value.startsWith('group')
    const response = (group ? groupStructureResponse : injection ? waterInjectionPlanResponse : controlPlanResponse)(run.result.summary, baseline.result.summary, run.parameters, inspection)
    return h('main', {}, [h('nav', {}, Object.keys(source).map(key => h('button', { onClick: () => { selected.value = key } }, key))), h(group ? EclipseGroupAllocation : EclipseControlTimeline, { runId: run.id, parameters: run.parameters, inspection, response, injection })])
  }
} }).use(ElementPlus).mount('#app')
