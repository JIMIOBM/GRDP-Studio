import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import Creator from '../../../src/views/SoftwareIntegration/PipesimTemplateCreator.vue'

// Test-only Vite entry: resolve dependencies normally, not through unstable optimizer cache URLs.
export const mountCreator = () => createApp(Creator, { projectId: 1 }).use(ElementPlus).mount('#app')
