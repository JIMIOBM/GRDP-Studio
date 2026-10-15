import { createApp, h } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../../src/style/parameter-panels.scss'
import '../../src/style/parameter-resize.scss'
import resizableParameterPanel from '../../src/directives/resizableParameterPanel'
import PipelineCapacityContent from '../../src/views/PipelineCapacity/PipelineCapacityContent.vue'

createApp({ render: () => h(PipelineCapacityContent, { projectId: 91001, gasReservoirId: 91002, wellName: '冲蚀测试井', initialSection: 'erosion' }) })
  .use(ElementPlus).directive('resizable-parameter-panel', resizableParameterPanel).mount('#app')
