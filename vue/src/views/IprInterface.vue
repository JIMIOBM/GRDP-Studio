<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import RibbonMenu from '@/components/RibbonMenu.vue'
import TreeNode from '@/views/TreeNode.vue'
import WaterInvasionContent from '@/views/WellControlInventory/WaterInvasionContent.vue'
import MaterialBalanceContent from '@/views/WellControlInventory/MaterialBalanceContent.vue'
import AnalyticMethodContent from '@/views/WellControlInventory/AnalyticMethodContent.vue'
import { NODETYPE } from '@/constants/nodeType'
import { analyticMethodApi, materialBalanceApi, nodeApi, projectApi, waterInvasionApi } from '@/api/docker'

const PROJECT_ID = 2
const GAS_RESERVOIR_ID = 1

const WELL_GROUPS = [
  { id: 'data-management', label: '数据管理' },
  { id: 'well-control-inventory', label: '井控库存' },
  { id: 'single-well-productivity', label: '单井产能' },
  { id: 'wellbore-capacity', label: '井筒能力' },
  { id: 'pipeline-capacity', label: '管束能力' },
  { id: 'production-allocation', label: '配产配注' }
]

const NODE_GROUP_BY_TYPE = {
  [NODETYPE.NodeType_WaterInvasionAnalysis]: 'well-control-inventory',
  [NODETYPE.NodeType_AnalysisMethods]: 'well-control-inventory',
  [NODETYPE.NodeType_DynamicOriginalGasInplace]: 'well-control-inventory',
  [NODETYPE.NodeType_ProductionDeclineAnalysis]: 'data-management',
  [NODETYPE.NodeType_ProductivityInstabilityAnalysis]: 'data-management',
  [NODETYPE.NodeType_ProductionAnalysis]: 'data-management',
  [NODETYPE.NodeType_ProductivityEvaluation]: 'single-well-productivity',
  [NODETYPE.NodeType_ProductivityEvaluationByPressure]: 'single-well-productivity',
  [NODETYPE.NodeType_ProductivityEvaluationByPressureSquared]: 'single-well-productivity',
  [NODETYPE.NodeType_ProductivityEvaluationByPseudoPressure]: 'single-well-productivity',
  [NODETYPE.NodeType_ProductivityEvaluationByFlowEquation]: 'single-well-productivity',
  [NODETYPE.NodeType_DynamicPrediction]: 'production-allocation'
}

const NODE_LABEL_BY_TYPE = {
  [NODETYPE.NodeType_WaterInvasionAnalysis]: '水侵分析',
  [NODETYPE.NodeType_AnalysisMethods]: '解析法',
  [NODETYPE.NodeType_DynamicOriginalGasInplace]: '物质平衡',
  [NODETYPE.NodeType_ProductionDeclineAnalysis]: '产量递减分析',
  [NODETYPE.NodeType_ProductivityInstabilityAnalysis]: '产量不稳定分析',
  [NODETYPE.NodeType_ProductionAnalysis]: '生产分析',
  [NODETYPE.NodeType_ProductivityEvaluation]: '产能评价',
  [NODETYPE.NodeType_DynamicPrediction]: '动态预测'
}

const treeData = ref([
  { id: 'g-well', label: '井', children: [] },
  { id: 'g-reservoir', label: '库', children: [{ id: 'res-1', label: '项目 1', type: 'reservoir' }] },
  { id: 'g-group', label: '库群', children: [{ id: 'grp-1', label: '项目 1', type: 'group' }] }
])

const activeNodeId = ref('')
const activeNode = ref(null)
const currentView = ref(null)
const currentViewNode = ref(null)
const wellKeyword = ref('')
const selectedWellName = ref('')
const selectedWellRaw = ref(null)
const waterInvasionRunning = ref(false)
const materialBalanceRunning = ref(false)
const analyticMethodRunning = ref(false)

const filteredTreeData = computed(() => {
  const keyword = wellKeyword.value.trim().toLowerCase()
  if (!keyword) return treeData.value

  return treeData.value.map(node => {
    if (node.id !== 'g-well') return node
    return {
      ...node,
      children: node.children.filter(well =>
        String(well.label || '').toLowerCase().includes(keyword)
      )
    }
  })
})

const normalizePayload = (res) => res?.data?.data ?? res?.data ?? res
const toArray = (value) => !value ? [] : Array.isArray(value) ? value : [value]
const getNodeName = (item) => item?.wellName || item?.nodeTitle || item?.name || item?.title || item?.label || item?.well_name || ''
const getChildren = (item) => [
  ...toArray(item?.children),
  ...toArray(item?.subNodes),
  ...toArray(item?.nodes),
  ...toArray(item?.analysisNodes),
  ...toArray(item?.analyses)
]

const createEmptyWell = (wellName, wellId, raw = null) => ({
  id: wellId || `well-${wellName}`,
  label: wellName,
  type: NODETYPE.NodeType_Well,
  wellName,
  raw,
  defaultExpanded: false,
  children: WELL_GROUPS.map(group => ({
    ...group,
    id: `${wellId || wellName}-${group.id}`,
    type: group.id,
    wellName,
    defaultExpanded: false,
    children: []
  }))
})

const getWellGroup = () => treeData.value.find(node => node.id === 'g-well')
const getAllWellNames = () =>
  getWellGroup()?.children.map(item => item.wellName || item.label).filter(Boolean) || []

const getSelectedAnalyticWellNames = () => {
  if (activeNode.value?.id === 'g-well' || !selectedWellName.value) return getAllWellNames()
  return [selectedWellName.value]
}

const ensureWell = (wellName, wellId, raw = null) => {
  const wellGroup = getWellGroup()
  if (!wellGroup || !wellName) return null

  let wellItem = wellGroup.children.find(item => item.label === wellName || item.id === wellId)
  if (!wellItem) {
    wellItem = createEmptyWell(wellName, wellId, raw)
    wellGroup.children.push(wellItem)
  } else if (raw) {
    wellItem.raw = { ...(wellItem.raw || {}), ...raw }
  }

  WELL_GROUPS.forEach(group => {
    if (!wellItem.children.some(item => item.type === group.id)) {
      wellItem.children.push({
        ...group,
        id: `${wellItem.id}-${group.id}`,
        type: group.id,
        wellName,
        defaultExpanded: false,
        children: []
      })
    }
  })

  return wellItem
}

const addAnalysisNode = (wellName, rawNode) => {
  const nodeType = rawNode?.nodeType ?? rawNode?.type
  const groupId = NODE_GROUP_BY_TYPE[nodeType] || rawNode?.menuType || rawNode?.groupType
  const groupConfig = WELL_GROUPS.find(group => group.id === groupId || group.label === groupId)
  if (!groupConfig) return null

  const wellItem = ensureWell(wellName, rawNode?.wellId || rawNode?.parentId)
  const targetGroup = wellItem?.children.find(item => item.type === groupConfig.id)
  if (!targetGroup) return null

  const label = NODE_LABEL_BY_TYPE[nodeType] || getNodeName(rawNode)
  if (!label) return null

  const id = rawNode?.nodeId || rawNode?.resultId || rawNode?.analysisId ||
    rawNode?.id || `${wellItem.id}-${groupConfig.id}-${nodeType || label}`
  const analysisNode = {
    id,
    label,
    type: nodeType || rawNode?.type || label,
    wellName,
    raw: rawNode
  }

  const existedIndex = targetGroup.children.findIndex(item => item.id === id || item.label === label)
  if (existedIndex >= 0) {
    targetGroup.children[existedIndex] = analysisNode
  } else {
    targetGroup.children.push(analysisNode)
  }

  return analysisNode
}

const collectWellsFromProject = (payload) => {
  const wellMap = new Map()

  const addWell = (name, raw = null, id = '') => {
    if (!name || wellMap.has(name)) return
    wellMap.set(name, { id: id || `well-${name}`, name, raw })
  }

  const visit = (value, parentKey = '') => {
    if (!value) return
    const keyLooksLikeWells = /wells?|wellList|well_list/i.test(parentKey)

    if (Array.isArray(value)) {
      value.forEach(item => {
        if (keyLooksLikeWells && typeof item !== 'object') addWell(String(item))
        else visit(item, parentKey)
      })
      return
    }

    if (typeof value !== 'object') return

    const name = getNodeName(value)
    const isWell = value.nodeType === NODETYPE.NodeType_Well || Boolean(value.wellName) || keyLooksLikeWells
    if (isWell && name) {
      const id = value.nodeId || value.id || value.wellId || `well-${name}`
      addWell(name, value, id)
      getChildren(value).forEach(child => addAnalysisNode(name, child))
    }

    Object.entries(value).forEach(([key, child]) => {
      if (['children', 'subNodes', 'nodes', 'analysisNodes', 'analyses'].includes(key) && isWell) return
      visit(child, key)
    })
  }

  visit(payload)
  return [...wellMap.values()]
}

const rebuildProjectTree = (payload) => {
  const wells = collectWellsFromProject(payload)
  const wellGroup = getWellGroup()
  if (!wellGroup) return

  wellGroup.children = []
  wells.forEach(well => ensureWell(well.name, well.id, well.raw))
  wells.forEach(well => getChildren(well.raw).forEach(child => addAnalysisNode(well.name, child)))
}

const applyWaterInvasionNodes = (node) => {
  if (!node?.subNodes?.length) return
  node.subNodes.forEach(wellNode => {
    const wellName = wellNode.nodeTitle || wellNode.wellName
    if (!wellName) return
    ensureWell(wellName, wellNode.nodeId || `well-${wellName}`)
    addAnalysisNode(wellName, {
      ...wellNode,
      nodeType: NODETYPE.NodeType_WaterInvasionAnalysis,
      nodeTitle: '水侵分析'
    })
  })
}

const applyMaterialBalanceNodes = (node) => {
  if (!node?.subNodes?.length) return
  node.subNodes.forEach(wellNode => {
    const wellName = wellNode.nodeTitle || wellNode.wellName
    if (!wellName) return
    ensureWell(wellName, wellNode.nodeId || `well-${wellName}`)
    addAnalysisNode(wellName, {
      ...wellNode,
      nodeType: NODETYPE.NodeType_DynamicOriginalGasInplace,
      nodeTitle: '物质平衡'
    })
  })
}

const applyAnalyticMethodNodes = (node) => {
  const analyticRoot = (node?.subNodes || []).find(sub => sub.nodeType === NODETYPE.NodeType_AnalysisMethods)
  if (!analyticRoot?.subNodes?.length) return

  analyticRoot.subNodes.forEach(wellNode => {
    const wellName = wellNode.nodeTitle || wellNode.wellName
    if (!wellName || !wellNode.nodeId) return
    ensureWell(wellName, `well-${wellName}`, wellNode)
    addAnalysisNode(wellName, {
      ...wellNode,
      nodeType: NODETYPE.NodeType_AnalysisMethods,
      nodeTitle: '解析法',
      resultId: wellNode.nodeId,
      analysisId: wellNode.nodeId
    })
  })
}

const refreshProjectTree = async () => {
  try {
    const res = await projectApi.getProject(PROJECT_ID)
    rebuildProjectTree(normalizePayload(res))
  } catch (error) {
    console.warn('项目树加载失败', error)
  }
}

const refreshWaterInvasionNodes = async () => {
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_WaterInvasionAnalysis)
    applyWaterInvasionNodes(res?.data?.node)
  } catch {}
}

const refreshMaterialBalanceNodes = async () => {
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_DynamicOriginalGasInplace)
    applyMaterialBalanceNodes(res?.data?.node)
  } catch {}
}

const refreshAnalyticMethodNodes = async () => {
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
    applyAnalyticMethodNodes(res?.data?.node)
  } catch (error) {
    console.warn('解析法汇总加载失败', error)
  }
}

const findAnalyticMethodNode = (wellName) => {
  const well = getWellGroup()?.children.find(item => item.wellName === wellName || item.label === wellName)
  const group = well?.children.find(item => item.type === 'well-control-inventory')
  return group?.children.find(item => item.type === NODETYPE.NodeType_AnalysisMethods)
}

const pollAnalyticMethodNodes = async (wellNames, maxRetries = 20, intervalMs = 1500) => {
  const targets = wellNames.filter(Boolean)
  for (let i = 0; i < maxRetries; i++) {
    await new Promise(resolve => setTimeout(resolve, intervalMs))
    await refreshAnalyticMethodNodes()
    const matched = targets.map(wellName => findAnalyticMethodNode(wellName)).filter(Boolean)
    if (matched.length === targets.length) return matched
  }
  throw new Error('解析法计算超时，请稍后刷新查看结果')
}

const pollWaterInvasionNode = async (wellName, maxRetries = 20, intervalMs = 1500) => {
  for (let i = 0; i < maxRetries; i++) {
    await new Promise(resolve => setTimeout(resolve, intervalMs))
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_WaterInvasionAnalysis)
    const node = res?.data?.node
    const subNodes = node?.subNodes ?? []
    if (subNodes.some(sub => sub.nodeTitle === wellName || sub.wellName === wellName)) return node
  }
  throw new Error('分析超时，请稍后刷新查看结果')
}

const pollMaterialBalanceNode = async (wellName, maxRetries = 20, intervalMs = 1500) => {
  for (let i = 0; i < maxRetries; i++) {
    await new Promise(resolve => setTimeout(resolve, intervalMs))
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_DynamicOriginalGasInplace)
    const node = res?.data?.node
    const subNodes = node?.subNodes ?? []
    if (!wellName || subNodes.some(sub => sub.nodeTitle === wellName || sub.wellName === wellName)) return node
  }
  throw new Error('物质平衡计算超时，请稍后刷新查看结果')
}

const openAnalyticMethodResult = (wellName, raw = {}) => {
  const viewNode = {
    id: raw.resultId || raw.analysisId || raw.id || `analytic-${wellName}`,
    label: '解析法',
    type: NODETYPE.NodeType_AnalysisMethods,
    wellName,
    raw: {
      ...raw,
      nodeType: NODETYPE.NodeType_AnalysisMethods,
      nodeTitle: '解析法',
      wellName
    }
  }

  addAnalysisNode(wellName, viewNode.raw)
  activeNodeId.value = viewNode.id
  currentView.value = 'analytic-method'
  currentViewNode.value = viewNode
}

const runWaterInvasionForSelectedWell = async () => {
  const targetWellName = selectedWellName.value
  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }
  if (waterInvasionRunning.value) return

  waterInvasionRunning.value = true
  try {
    await waterInvasionApi.analyze({
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      analysisType: 1,
      wellNames: [targetWellName],
      useActualStaticPressure: true,
      waterGasRatioLimit: -1
    })

    ElMessage.info(`${targetWellName} 水侵分析计算中，请稍候...`)
    const node = await pollWaterInvasionNode(targetWellName)
    applyWaterInvasionNodes(node)
    const resultNode = node.subNodes?.find(sub => sub.nodeTitle === targetWellName || sub.wellName === targetWellName)
    currentView.value = 'water-invasion'
    currentViewNode.value = {
      id: resultNode?.nodeId || `wia-${targetWellName}`,
      label: '水侵分析',
      type: NODETYPE.NodeType_WaterInvasionAnalysis,
      wellName: targetWellName,
      raw: resultNode
    }
  } catch (error) {
    ElMessage.error(error.message || '水侵分析失败')
    console.error('水侵分析失败', error)
  } finally {
    waterInvasionRunning.value = false
  }
}

const runMaterialBalanceForSelectedWell = async () => {
  const targetWellName = selectedWellName.value
  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }
  if (materialBalanceRunning.value) return

  materialBalanceRunning.value = true
  try {
    await materialBalanceApi.calc({
      wellNames: [targetWellName],
      gasReservoirType: 3,
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      waterGasRatioLimit: 0.14
    })

    ElMessage.info(`${targetWellName} 物质平衡计算中，请稍候...`)
    const node = await pollMaterialBalanceNode(targetWellName)
    applyMaterialBalanceNodes(node)
    const subNodes = node?.subNodes ?? []
    const resultNode = subNodes.find(sub => sub.nodeTitle === targetWellName || sub.wellName === targetWellName) || subNodes[0] || node
    currentView.value = 'material-balance'
    currentViewNode.value = {
      id: resultNode?.nodeId || resultNode?.resultId || `mb-${targetWellName}`,
      label: '物质平衡',
      type: NODETYPE.NodeType_DynamicOriginalGasInplace,
      wellName: targetWellName,
      raw: resultNode
    }
  } catch (error) {
    ElMessage.error(error.message || '物质平衡计算失败')
    console.error('物质平衡计算失败', error)
  } finally {
    materialBalanceRunning.value = false
  }
}

const runAnalyticMethodForSelectedWell = async () => {
  const wellNames = getSelectedAnalyticWellNames()
  if (!wellNames.length) {
    ElMessage.warning('没有可执行解析法的井')
    return
  }
  if (analyticMethodRunning.value) return

  analyticMethodRunning.value = true
  try {
    await analyticMethodApi.fitHistory({
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      wellNames,
      dataSize: 30,
      minimumWaterGasRatio: -1
    })

    ElMessage.info(`解析法计算中，请稍候...`)
    const resultNodes = await pollAnalyticMethodNodes(wellNames)
    if (wellNames.length === 1 && resultNodes[0]?.raw) {
      openAnalyticMethodResult(wellNames[0], resultNodes[0].raw)
    }
    ElMessage.success(`解析法结果已更新`)
  } catch (error) {
    const msg = error.response?.data?.msg || error.response?.data?.message || ''
    ElMessage.error(msg || error.message || '解析法执行失败')
    console.error('解析法执行失败', error)
  } finally {
    analyticMethodRunning.value = false
  }
}

const initTree = async () => {
  await refreshProjectTree()
  await Promise.all([
    refreshWaterInvasionNodes(),
    refreshMaterialBalanceNodes(),
    refreshAnalyticMethodNodes()
  ])
}

const handleSelect = (node) => {
  const isWellMenuGroup = WELL_GROUPS.some(group => group.id === node.type)
  const nodeWellName = node.wellName || (node.type === NODETYPE.NodeType_Well ? node.label : '')

  if (nodeWellName) {
    selectedWellName.value = nodeWellName
    selectedWellRaw.value = node.raw || null
  }
  if (isWellMenuGroup) return

  activeNodeId.value = node.id
  activeNode.value = node

  if (node.type === NODETYPE.NodeType_WaterInvasionAnalysis) {
    currentView.value = 'water-invasion'
    currentViewNode.value = node
    return
  }

  if (node.type === NODETYPE.NodeType_AnalysisMethods) {
    currentView.value = 'analytic-method'
    currentViewNode.value = node
    return
  }

  if (node.type === NODETYPE.NodeType_DynamicOriginalGasInplace) {
    currentView.value = 'material-balance'
    currentViewNode.value = node
  }
}

const isCommand = (name, target) => String(name || '').includes(target)

const handleCommand = ({ group, name }) => {
  if (isCommand(name, '水侵分析')) {
    runWaterInvasionForSelectedWell()
    return
  }
  if (isCommand(name, '解析法')) {
    runAnalyticMethodForSelectedWell()
    return
  }
  if (isCommand(name, '物质平衡')) {
    runMaterialBalanceForSelectedWell()
    return
  }
  ElMessage.success(`[${group}] ${name}`)
}

const handleRefreshTree = () => {
  refreshWaterInvasionNodes()
  refreshMaterialBalanceNodes()
  refreshAnalyticMethodNodes()
}

onMounted(initTree)
</script>

<template>
  <div class="ipr-container">
    <RibbonMenu @command="handleCommand" />

    <div class="ipr-main">
      <aside class="side-panel">
        <div class="side-search">
          <el-input
            v-model="wellKeyword"
            size="small"
            clearable
            placeholder="搜索井名"
          />
        </div>

        <div class="side-tree">
          <TreeNode
            v-for="node in filteredTreeData"
            :key="node.id"
            :node="node"
            :active-id="activeNodeId"
            @select="handleSelect"
          />
        </div>
      </aside>

      <main class="content-area">
        <WaterInvasionContent
          v-if="currentView === 'water-invasion'"
          :node="currentViewNode"
          :project-id="PROJECT_ID"
          :gas-reservoir-id="GAS_RESERVOIR_ID"
          @refresh-tree="handleRefreshTree"
        />
        <MaterialBalanceContent
          v-if="currentView === 'material-balance'"
          :node="currentViewNode"
          :project-id="PROJECT_ID"
          :gas-reservoir-id="GAS_RESERVOIR_ID"
        />
        <AnalyticMethodContent
          v-if="currentView === 'analytic-method'"
          :node="currentViewNode"
          :project-id="PROJECT_ID"
          :gas-reservoir-id="GAS_RESERVOIR_ID"
        />
      </main>
    </div>
  </div>
</template>

<style lang="scss" scoped>
$border: #e0e0e0;

.ipr-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: #fff;
  overflow: hidden;
}

.ipr-main {
  flex: 1;
  display: flex;
  min-height: 0;
}

.side-panel {
  width: 210px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid $border;
  background-color: #fff;

  .side-search {
    padding: 6px 6px 4px;
    border-bottom: 1px solid $border;
    flex-shrink: 0;
  }

  .side-tree {
    flex: 1;
    overflow-y: auto;
    padding: 6px 4px;
  }
}

.content-area {
  flex: 1;
  background-color: #fff;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>
