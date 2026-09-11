<script setup>
// Standalone workbench uses the same content as the embedded IPR entry.
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import RibbonMenu from '@/components/RibbonMenu.vue'
import WorkspaceSidebar from '@/components/WorkspaceSidebar.vue'
import PipelineCapacityContent from './PipelineCapacity/PipelineCapacityContent.vue'
import { ensurePipelineNavigation, findPipelinePageNode, pipelinePageForCommand, resolvePipelinePage } from '@/utils/pipelineNavigation'
import { WORKSPACE_PROJECT_ID, WORKSPACE_GAS_RESERVOIR_ID } from '@/constants/workspaceContext'
import { workspaceTreeData, workspaceTreeKeyword, workspaceTreeCollapsed, workspaceActiveNodeId, workspacePendingCommand, workspacePendingNode, workspaceRibbonScope, ensureWorkspaceReservoir, setWorkspaceRibbonScope, selectWorkspaceNodeScope, getReservoirCommandLocation } from '@/utils/workspaceTreeState'
const route = useRoute(), router = useRouter()
const commandKey = ref(0)
const projectId = computed(() => Number(route.query.projectId) || WORKSPACE_PROJECT_ID)
const reservoirId = computed(() => Number(route.query.gasReservoirId) || WORKSPACE_GAS_RESERVOIR_ID)
ensureWorkspaceReservoir({ projectId: projectId.value, gasReservoirId: reservoirId.value })
setWorkspaceRibbonScope('well')
const wellName = computed(() => String(route.query.well || ''))
const section = computed(() => resolvePipelinePage(route.query.section || pipelinePageForCommand(route.query.method)))
const nodes = computed(() => workspaceTreeData.value.some(n => n.id === 'g-well' && n.children?.length) ? workspaceTreeData.value : wellName.value ? [{ id: `pipeline-${wellName.value}`, label: '管束能力', type: 'pipeline-capacity', wellName: wellName.value, expanded: true, children: [] }] : [])
watch([section, wellName, nodes], () => {
  ensurePipelineNavigation(nodes.value)
  const node = findPipelinePageNode(nodes.value, wellName.value, section.value, true)
  workspaceActiveNodeId.value = node?.id || ''
}, { immediate: true })
async function command(cmd) {
  if (workspaceRibbonScope.value === 'reservoir') {
    const location = getReservoirCommandLocation(cmd)
    if (location) await router.push(location)
    else ElMessage.info('此公共功能暂未接入库工作区')
    return
  }
  if (cmd.group === '管束能力') { await navigate(pipelinePageForCommand(cmd.name)); return }
  workspacePendingCommand.value = cmd
  await router.push({ name: 'IprInterface' })
}
async function select(node) {
  if (!node || node.disabled) return
  if (selectWorkspaceNodeScope(node) === 'reservoir') {
    workspaceActiveNodeId.value = node.id
    if (node.command) {
      const location = getReservoirCommandLocation(node.command)
      if (location) await router.push(location)
    }
    return
  }
  if (node.type === 'pipeline-capacity' || node.type === 'pipeline-temperature-group') return
  if (node.type === 'pipeline-capacity-page') {
    workspaceActiveNodeId.value = node.id
    await router.replace({ query: { ...route.query, well: node.wellName || wellName.value, section: node.section, method: undefined } })
    commandKey.value++
    return
  }
  workspacePendingNode.value = node
  await router.push({ name: 'IprInterface' })
}
async function navigate(page) {
  const node = findPipelinePageNode(nodes.value, wellName.value, page, true)
  if (node) workspaceActiveNodeId.value = node.id
  await router.replace({ query: { ...route.query, section: page, method: undefined } })
  commandKey.value++
}
</script>
<template><div class="pipeline-interface"><RibbonMenu :scope="workspaceRibbonScope" @scope-change="setWorkspaceRibbonScope" @command="command" /><div class="pipeline-main"><WorkspaceSidebar v-model:keyword="workspaceTreeKeyword" v-model:collapsed="workspaceTreeCollapsed" :nodes="nodes" :active-id="workspaceActiveNodeId" @select="select" /><main><PipelineCapacityContent :key="`${projectId}-${reservoirId}-${wellName}`" :project-id="projectId" :gas-reservoir-id="reservoirId" :well-name="wellName" :initial-section="section" :command-key="commandKey" @navigate="navigate" /></main></div></div></template>
<style scoped>.pipeline-interface{height:100vh;display:flex;flex-direction:column;background:#fff}.pipeline-main{flex:1;display:flex;min-height:0;overflow:hidden}.pipeline-main>main{flex:1;min-width:0;min-height:0;overflow:hidden}</style>
