import { createApp, h, ref } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as echarts from 'echarts'
import Oil from '../../../src/views/SoftwareIntegration/EclipseOilPvtEditor.vue'
import Gas from '../../../src/views/SoftwareIntegration/EclipseGasPvtEditor.vue'
import Live from '../../../src/views/SoftwareIntegration/EclipseLiveOilPvtEditor.vue'
import Wet from '../../../src/views/SoftwareIntegration/EclipseWetGasPvtEditor.vue'
import Relperm from '../../../src/views/SoftwareIntegration/EclipseRelpermEditor.vue'
import Coupled from '../../../src/views/SoftwareIntegration/EclipseCoupledRelpermEditor.vue'
import FamilyII from '../../../src/views/SoftwareIntegration/EclipseMultiSatFamilyIIEditor.vue'
import MultiPvt from '../../../src/views/SoftwareIntegration/EclipseMultiPvtEditor.vue'
import MultiLive from '../../../src/views/SoftwareIntegration/EclipseMultiLiveOilPvtEditor.vue'
import MultiWet from '../../../src/views/SoftwareIntegration/EclipseMultiWetGasPvtEditor.vue'
const components = { oil: Oil, gas: Gas, live: Live, wet: Wet, relperm: Relperm, coupled: Coupled, multiSat: Coupled, familyII: FamilyII, multiPvt: MultiPvt, multiLive: MultiLive, multiWet: MultiWet }
const source = await (await fetch('./input-curves-source.json')).json()
globalThis.readInputChart = el => echarts.getInstanceByDom(el)?.getOption()
globalThis.inputCurveSubmissions = []
createApp({ setup() {
  const selected = ref(Object.keys(source)[0]), secondRun = ref(false)
  return () => {
    const item = source[selected.value], run = secondRun.value ? { ...item.run, id: item.run.id + 100000 } : item.run
    const submitted = parameters => globalThis.inputCurveSubmissions.push(parameters)
    return h('main', {}, [h('nav', { class: 'test-nav' }, [...Object.keys(source).map(key => h('button', { onClick: () => { selected.value = key; secondRun.value = false } }, key)), h('button', { onClick: () => { secondRun.value = !secondRun.value } }, '切换基线身份（状态测试）')]), h(components[selected.value], { key: selected.value, run, inspection: item.inspection, onCreateOilPvtRun: submitted, onCreateGasPvtRun: submitted, onCreateRelpermRun: submitted, onCreatePvtRun: submitted })])
  }
} }).use(ElementPlus).mount('#app')
