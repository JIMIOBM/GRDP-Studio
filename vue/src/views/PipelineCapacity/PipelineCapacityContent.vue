<script setup>
import { computed } from 'vue'
import { usePipelineWorkspace } from '@/composables/usePipelineWorkspace'
import { pipelinePageTitles } from '@/utils/pipelineNavigation'
import PipelineBoundaryPage from './PipelineBoundaryPage.vue'
import PipelineFlowPage from './PipelineFlowPage.vue'
import PipelineComparisonPage from './PipelineComparisonPage.vue'
import PipelineConstraintPage from './PipelineConstraintPage.vue'
import PipelineTopologyEditor from './PipelineTopologyEditor.vue'
import PipelineTemperatureEditor from './PipelineTemperatureEditor.vue'
import PipelineGasPropertyPage from './PipelineGasPropertyPage.vue'
import PipelinePvtModelPage from './PipelinePvtModelPage.vue'
const props = defineProps({
  projectId: { type: [Number, String], required: true }, gasReservoirId: { type: [Number, String], required: true },
  wellName: { type: String, default: '' }, initialSection: { type: String, default: 'flow' },
  commandKey: { type: [Number, String], default: 0 }
})
const emit = defineEmits(['navigate'])
const s = usePipelineWorkspace(props)
const title = computed(() => pipelinePageTitles[s.activePage])
const editable = computed(() => ['flow', 'boundary', 'hydrate'].includes(s.activePage))
const hasResults = computed(() => ['flow', 'equipment', 'hydrate', 'comparison'].includes(s.activePage))
const pageBusy = computed(() => hasResults.value ? !!(s.batchBusy || s.busy) : s.busy)
const pageError = computed(() => hasResults.value ? s.batchError : s.error)
const pageStale = computed(() => s.batchStale)
function navigate(section) { emit('navigate', section) }
defineExpose({ dirty: computed(() => s.dirty), mayDiscard: s.mayDiscard })
</script>

<template>
  <div v-if="!wellName" class="pipeline-empty">请先在左侧选择一口井，再进入管束能力。</div>
  <PipelinePvtModelPage v-else-if="s.activePage === 'pvt'" :context="s.context" :command-key="commandKey" @saved="s.refreshGasProperties" />
  <PipelineGasPropertyPage v-else-if="['temperature-z', 'temperature-cp'].includes(s.activePage)" :context="s.context" :kind="s.activePage === 'temperature-z' ? 'z' : 'cp'" :command-key="commandKey" @saved="s.refreshGasProperties" />
  <section v-else-if="['erosion', 'freeze'].includes(s.activePage)" class="pipeline-workspace" :aria-label="title">
    <header class="model-strip"><strong>{{ title }}</strong><span class="well-context">{{ wellName }}</span></header>
    <PipelineConstraintPage :state="s" :kind="s.activePage" />
  </section>
  <div v-else-if="!s.loaded" class="pipeline-empty">{{ s.storageError || '正在加载当前井数据…' }}<button v-if="s.storageError" @click="s.loadModel()">重试</button></div>
  <PipelineTopologyEditor v-else-if="s.activePage === 'topology'" :context="s.context" @saved="s.topologySaved" @close="navigate('flow')" />
  <PipelineTemperatureEditor v-else-if="s.activePage.startsWith('temperature-')" :context="s.context" :input="s.form" :initial-tab="s.temperaturePage?.tab || 'properties'" :command-key="commandKey" @saved="s.refreshThermalSource" />
  <section v-else class="pipeline-workspace" :aria-label="title">
    <header class="model-strip" :class="{ 'boundary-title': s.activePage === 'boundary' }">
      <strong>{{ title }}</strong><span class="well-context">{{ wellName }}</span>
      <span class="save-state">{{ editable ? (s.pageDirty ? '有未保存修改' : s.revision ? '已保存' : '参数待填写') : '管束能力' }}</span>
      <div class="actions">
        <button v-if="s.activePage === 'comparison'" @click="navigate('flow')">返回管流计算</button>
        <button v-if="s.activePage !== 'boundary'" :disabled="pageBusy" @click="s.reloadPage()">{{ s.activePage === 'flow' ? '刷新' : '重新加载' }}</button>
        <button v-if="s.activePage === 'flow'" :disabled="pageBusy || !s.batchResult || s.batchStale" @click="s.exportResult">导出</button>
      </div>
    </header>
    <div v-if="pageError" class="error-strip" role="alert">{{ pageError }}</div>
    <div v-if="hasResults && s.topologyError" class="topology-source invalid"><span>{{ s.topologyError }}</span><button v-if="s.activePage !== 'flow'" @click="navigate('topology')">前往管网拓扑结构</button></div>
    <div v-if="hasResults && pageStale" class="stale-strip">{{ s.activePage === 'flow' ? '边界工况、PVT、温度模型、含水条件或拓扑等计算条件已变化，请重新计算全部工况。' : '边界、物性、含水条件或已保存拓扑等计算条件已变化，请重新计算全部工况后查看结果。' }}</div>
    <PipelineBoundaryPage v-if="s.activePage === 'boundary'" :state="s" @navigate="navigate" />
    <PipelineFlowPage v-else-if="s.activePage === 'flow'" :state="s" />
    <PipelineComparisonPage v-else-if="s.activePage === 'comparison'" :state="s" @navigate="navigate" />
    <PipelineConstraintPage v-else :key="s.activePage" :state="s" :kind="s.activePage" @navigate="navigate" />
    <footer class="status-bar" :class="{ 'storage-warning': s.storageError }">
      <span v-if="s.storageError">{{ s.storageError }}</span>
      <span v-else>{{ pageBusy ? '正在处理' : '就绪' }} · {{ title }}{{ s.dirty && !s.pageDirty ? ' · 其他页面有未保存修改' : '' }}</span>
    </footer>
  </section>
</template>

<style lang="scss">
@use './pipeline-pages.scss';
.pipeline-empty{display:flex;align-items:center;justify-content:center;height:100%;gap:12px;color:#999;font-size:13px}
.pipeline-workspace .model-strip.boundary-title{background:#ffffcc}
</style>
