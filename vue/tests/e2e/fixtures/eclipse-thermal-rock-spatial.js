import { createApp, h, ref } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import Spatial from '../../../src/views/SoftwareIntegration/EclipseThermalSpatialCompare.vue'
const source = await (await fetch('./thermal-rock-spatial-source.json')).json()
createApp({ setup() {
  const selected = ref('heat-capacity')
  return () => h('main', {}, [h('nav', {}, [['热容方案', 'heat-capacity'], ['导热率方案', 'conductivity']].map(([label, key]) => h('button', { onClick: () => { selected.value = key } }, label))),
    h(Spatial, { run: source.cases[selected.value], baseline: source.baseline, unitSystem: source.inspection.unitSystem })])
} }).use(ElementPlus).mount('#app')
