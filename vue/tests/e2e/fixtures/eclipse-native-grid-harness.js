import { createApp, h } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import EclipseGrid3D from '../../../src/views/SoftwareIntegration/EclipseGrid3D.vue'

// This fixture is not linked from or bundled into the production application.
// Playwright supplies a real, already-completed run; every binary range is forwarded read-only.
const run = await (await fetch('./native-run.json')).json()
const fields = run.result.fieldIndex.files.flatMap(file => file.fields.map(field => ({
  ...field, file: file.name, byteOrder: file.byteOrder
})))
createApp({ render: () => h(EclipseGrid3D, {
  run, runId: run.id, grid: run.result.grid, fields, artifacts: run.artifacts, wellCompletions: []
}) }).use(ElementPlus).mount('#app')
