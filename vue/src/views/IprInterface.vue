<script setup>
import { computed, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import RibbonMenu from '@/components/RibbonMenu.vue'
import TreeNode from '@/views/TreeNode.vue'
import WaterInvasionContent from '@/views/WellControlInventory/WaterInvasionContent.vue'
import MaterialBalanceContent from '@/views/WellControlInventory/MaterialBalanceContent.vue'
import WattenbargerContent from '@/views/WellControlInventory/WattenbargerContent.vue'
import { NODETYPE } from '@/constants/nodeType'
import { materialBalanceApi, nodeApi, parametersApi, projectApi, typicalCurveApi, waterInvasionApi } from '@/api/docker'

const PROJECT_ID = 1
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
  [NODETYPE.NodeType_ConfinedGasReservoirMaterialBalanceMethodForActualStaticPressure]: 'well-control-inventory',
  [NODETYPE.NodeType_ConfinedGasReservoirMaterialBalanceMethodForCalculatedStaticPressure]: 'well-control-inventory',
  [NODETYPE.NodeType_ConstantVolumeGasReservoirMaterialBalanceMethodForActualStaticPressure]: 'well-control-inventory',
  [NODETYPE.NodeType_ConstantVolumeReservoirMaterialBalanceMethodForCalculatedStaticPressure]: 'well-control-inventory',
  [NODETYPE.NodeType_MaterialBalanceMethod]: 'well-control-inventory',
  [NODETYPE.NodeType_MaterialBalanceMethodForActualStaticPressure]: 'well-control-inventory',
  [NODETYPE.NodeType_MaterialBalanceMethodForCalculatedStaticPressure]: 'well-control-inventory',
  [NODETYPE.NodeType_VerticalWellTypicalCurveWb]: 'well-control-inventory',
  [NODETYPE.NodeType_ProductionDeclineAnalysis]: 'data-management',
  [NODETYPE.NodeType_ProductivityInstabilityAnalysis]: 'data-management',
  [NODETYPE.NodeType_ProductionAnalysis]: 'data-management',
  [NODETYPE.NodeType_DynamicOriginalGasInplace]: 'data-management',
  [NODETYPE.NodeType_ProductivityEvaluation]: 'single-well-productivity',
  [NODETYPE.NodeType_ProductivityEvaluationByPressure]: 'single-well-productivity',
  [NODETYPE.NodeType_ProductivityEvaluationByPressureSquared]: 'single-well-productivity',
  [NODETYPE.NodeType_ProductivityEvaluationByPseudoPressure]: 'single-well-productivity',
  [NODETYPE.NodeType_ProductivityEvaluationByFlowEquation]: 'single-well-productivity',
  [NODETYPE.NodeType_DynamicPrediction]: 'production-allocation'
}

const NODE_LABEL_BY_TYPE = {
  [NODETYPE.NodeType_WaterInvasionAnalysis]: '水侵分析',
  [NODETYPE.NodeType_ConfinedGasReservoirMaterialBalanceMethodForActualStaticPressure]: '物质平衡',
  [NODETYPE.NodeType_ConfinedGasReservoirMaterialBalanceMethodForCalculatedStaticPressure]: '物质平衡',
  [NODETYPE.NodeType_ConstantVolumeGasReservoirMaterialBalanceMethodForActualStaticPressure]: '物质平衡',
  [NODETYPE.NodeType_ConstantVolumeReservoirMaterialBalanceMethodForCalculatedStaticPressure]: '物质平衡',
  [NODETYPE.NodeType_MaterialBalanceMethod]: '物质平衡',
  [NODETYPE.NodeType_MaterialBalanceMethodForActualStaticPressure]: '物质平衡',
  [NODETYPE.NodeType_MaterialBalanceMethodForCalculatedStaticPressure]: '物质平衡',
  [NODETYPE.NodeType_VerticalWellTypicalCurveWb]: 'Wattenbarger',
  [NODETYPE.NodeType_ProductionDeclineAnalysis]: '产量递减分析',
  [NODETYPE.NodeType_ProductivityInstabilityAnalysis]: '产量不稳定分析',
  [NODETYPE.NodeType_ProductionAnalysis]: '生产分析',
  [NODETYPE.NodeType_DynamicOriginalGasInplace]: '动态储量分析',
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
const waterInvasionRunning = ref(false)
const materialBalanceRunning = ref(false)
const wattenbargerRunning = ref(false)
const selectedWellName = ref('')

const MATERIAL_BALANCE_NODE_TYPES = [
  NODETYPE.NodeType_MaterialBalanceMethod,
  NODETYPE.NodeType_MaterialBalanceMethodForActualStaticPressure,
  NODETYPE.NodeType_MaterialBalanceMethodForCalculatedStaticPressure,
  NODETYPE.NodeType_ConfinedGasReservoirMaterialBalanceMethodForActualStaticPressure,
  NODETYPE.NodeType_ConfinedGasReservoirMaterialBalanceMethodForCalculatedStaticPressure,
  NODETYPE.NodeType_ConstantVolumeGasReservoirMaterialBalanceMethodForActualStaticPressure,
  NODETYPE.NodeType_ConstantVolumeReservoirMaterialBalanceMethodForCalculatedStaticPressure
]

const WATTENBARGER_NODE_TYPES = [
  NODETYPE.NodeType_VerticalWellTypicalCurveWb
]

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

const toArray = (value) => {
  if (!value) return []
  return Array.isArray(value) ? value : [value]
}

const getNodeName = (item) =>
  item?.wellName || item?.nodeTitle || item?.name || item?.title || item?.label || item?.well_name || ''

const getChildren = (item) => [
  ...toArray(item?.children),
  ...toArray(item?.subNodes),
  ...toArray(item?.nodes),
  ...toArray(item?.analysisNodes),
  ...toArray(item?.analyses)
]

const createEmptyWell = (wellName, wellId) => ({
  id: wellId || `well-${wellName}`,
  label: wellName,
  type: NODETYPE.NodeType_Well,
  wellName,
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

const ensureWell = (wellName, wellId) => {
  const wellGroup = getWellGroup()
  if (!wellGroup || !wellName) return null

  let wellItem = wellGroup.children.find(item => item.label === wellName || item.id === wellId)
  if (!wellItem) {
    wellItem = createEmptyWell(wellName, wellId)
    wellGroup.children.push(wellItem)
  }

  WELL_GROUPS.forEach(group => {
    if (!wellItem.children.some(item => item.type === group.id || item.label === group.label)) {
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
  if (!groupConfig) return

  const wellItem = ensureWell(wellName, rawNode?.wellId || rawNode?.parentId)
  const targetGroup = wellItem?.children.find(item => item.type === groupConfig.id)
  if (!targetGroup) return

  const label = NODE_LABEL_BY_TYPE[nodeType] || getNodeName(rawNode)
  if (!label) return

  const id = rawNode?.nodeId || rawNode?.id || `${wellItem.id}-${groupConfig.id}-${nodeType || label}`
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
}

const collectWellsFromProject = (payload) => {
  const wellMap = new Map()

  const addWell = (name, raw = null, id = '') => {
    if (!name || wellMap.has(name)) return
    wellMap.set(name, {
      id: id || `well-${name}`,
      name,
      raw
    })
  }

  const visit = (value, parentKey = '') => {
    if (!value) return

    const keyLooksLikeWells = /wells?|wellList|well_list/i.test(parentKey)

    if (Array.isArray(value)) {
      value.forEach(item => {
        if (keyLooksLikeWells && typeof item !== 'object') {
          addWell(String(item))
          return
        }
        visit(item, parentKey)
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
  wells.forEach(well => ensureWell(well.name, well.id))
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

const applyWellAnalysisNodes = (node, nodeType, label) => {
  if (!node?.subNodes?.length) return

  node.subNodes.forEach(wellNode => {
    const wellName = wellNode.nodeTitle || wellNode.wellName
    if (!wellName) return

    ensureWell(wellName, wellNode.nodeId || `well-${wellName}`)
    addAnalysisNode(wellName, {
      ...wellNode,
      nodeType,
      nodeTitle: label
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
  } catch {
    // 没有已有水侵分析结果时，保持项目树不变。
  }
}

const refreshNodeType = async (nodeType, label) => {
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, nodeType)
    applyWellAnalysisNodes(res?.data?.node, nodeType, label)
  } catch {
    // 没有已有分析结果时，保持项目树不变。
  }
}

const refreshMaterialBalanceNodes = async () => {
  await Promise.all(MATERIAL_BALANCE_NODE_TYPES.map(type => refreshNodeType(type, '物质平衡')))
}

const refreshWattenbargerNodes = async () => {
  await Promise.all(WATTENBARGER_NODE_TYPES.map(type => refreshNodeType(type, 'Wattenbarger')))
}

const pollWaterInvasionNode = async (wellName, maxRetries = 20, intervalMs = 1500) => {
  for (let i = 0; i < maxRetries; i++) {
    await new Promise(resolve => setTimeout(resolve, intervalMs))

    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_WaterInvasionAnalysis)
    const node = res?.data?.node
    const subNodes = node?.subNodes ?? []
    if (subNodes.some(sub => sub.nodeTitle === wellName || sub.wellName === wellName)) {
      return node
    }
  }

  throw new Error('分析超时，请稍后刷新查看结果')
}

const pollAnalysisNode = async (wellName, nodeTypes, maxRetries = 20, intervalMs = 1500) => {
  for (let i = 0; i < maxRetries; i++) {
    await new Promise(resolve => setTimeout(resolve, intervalMs))

    for (const nodeType of nodeTypes) {
      try {
        const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, nodeType)
        const node = res?.data?.node
        const subNodes = node?.subNodes ?? []
        if (subNodes.some(sub => sub.nodeTitle === wellName || sub.wellName === wellName)) {
          return { node, nodeType }
        }
      } catch {
        // 部分节点类型不存在时继续查其他类型。
      }
    }
  }

  throw new Error('分析超时，请稍后刷新查看结果')
}

const findResultSubNode = (node, wellName) =>
  node?.subNodes?.find(sub => sub.nodeTitle === wellName || sub.wellName === wellName)

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
    const viewNode = {
      id: resultNode?.nodeId || `wia-${targetWellName}`,
      label: '水侵分析',
      type: NODETYPE.NodeType_WaterInvasionAnalysis,
      wellName: targetWellName,
      raw: resultNode
    }

    activeNodeId.value = viewNode.id
    currentView.value = 'water-invasion'
    currentViewNode.value = viewNode
    ElMessage.success(`${targetWellName} 水侵分析完成`)
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
    let waterGasRatioLimit = -1
    try {
      const res = await parametersApi.getMinWaterGasRatio(PROJECT_ID, GAS_RESERVOIR_ID)
      waterGasRatioLimit = res?.data?.vaule ?? -1
    } catch {
      waterGasRatioLimit = -1
    }

    await materialBalanceApi.calculate({
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      wellNames: [targetWellName],
      gasReservoirType: 1,
      waterGasRatioLimit
    })

    ElMessage.info(`${targetWellName} 物质平衡计算中，请稍候...`)
    const { node, nodeType } = await pollAnalysisNode(targetWellName, MATERIAL_BALANCE_NODE_TYPES)
    applyWellAnalysisNodes(node, nodeType, '物质平衡')

    const resultNode = findResultSubNode(node, targetWellName)
    const viewNode = {
      id: resultNode?.nodeId || `mb-${targetWellName}`,
      label: '物质平衡',
      type: nodeType,
      wellName: targetWellName,
      raw: resultNode
    }

    activeNodeId.value = viewNode.id
    currentView.value = 'material-balance'
    currentViewNode.value = viewNode
    ElMessage.success(`${targetWellName} 物质平衡完成`)
  } catch (error) {
    ElMessage.error(error.message || '物质平衡失败')
    console.error('物质平衡失败', error)
  } finally {
    materialBalanceRunning.value = false
  }
}

const runWattenbargerForSelectedWell = async () => {
  const targetWellName = selectedWellName.value

  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }

  if (wattenbargerRunning.value) return

  wattenbargerRunning.value = true
  try {
    let minimumWaterGasRatio = -1
    try {
      const res = await parametersApi.getMinWaterGasRatio(PROJECT_ID, GAS_RESERVOIR_ID)
      minimumWaterGasRatio = res?.data?.vaule ?? -1
    } catch {
      minimumWaterGasRatio = -1
    }

    await typicalCurveApi.fitting({
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      wellNames: [targetWellName],
      fittingType: 5,
      isSkipFitting: false,
      dataSize: 300,
      initScanDataSize: 10,
      fineScanDataSize: 30,
      minimumWaterGasRatio
    })

    ElMessage.info(`${targetWellName} Wattenbarger 计算中，请稍候...`)
    const { node, nodeType } = await pollAnalysisNode(targetWellName, WATTENBARGER_NODE_TYPES)
    applyWellAnalysisNodes(node, nodeType, 'Wattenbarger')

    const resultNode = findResultSubNode(node, targetWellName)
    const viewNode = {
      id: resultNode?.nodeId || `wb-${targetWellName}`,
      label: 'Wattenbarger',
      type: nodeType,
      wellName: targetWellName,
      raw: resultNode
    }

    activeNodeId.value = viewNode.id
    currentView.value = 'wattenbarger'
    currentViewNode.value = viewNode
    ElMessage.success(`${targetWellName} Wattenbarger 完成`)
  } catch (error) {
    ElMessage.error(error.message || 'Wattenbarger 失败')
    console.error('Wattenbarger 失败', error)
  } finally {
    wattenbargerRunning.value = false
  }
}

const initTree = async () => {
  await refreshProjectTree()
  await refreshWaterInvasionNodes()
  await refreshMaterialBalanceNodes()
  await refreshWattenbargerNodes()
}

const handleSelect = (node) => {
  const isWellMenuGroup = WELL_GROUPS.some(group => group.id === node.type)
  const nodeWellName = node.wellName || (node.type === NODETYPE.NodeType_Well ? node.label : '')

  if (nodeWellName) selectedWellName.value = nodeWellName
  if (isWellMenuGroup) return

  activeNodeId.value = node.id
  activeNode.value = node

  if (node.type === NODETYPE.NodeType_WaterInvasionAnalysis) {
    currentView.value = 'water-invasion'
    currentViewNode.value = node
    return
  }

  if (MATERIAL_BALANCE_NODE_TYPES.includes(node.type)) {
    currentView.value = 'material-balance'
    currentViewNode.value = node
    return
  }

  if (WATTENBARGER_NODE_TYPES.includes(node.type)) {
    currentView.value = 'wattenbarger'
    currentViewNode.value = node
    return
  }

  if (node.type === NODETYPE.NodeType_Well) return
}

const handleCommand = ({ group, name }) => {
  switch (name) {
    case '水侵分析':
      runWaterInvasionForSelectedWell()
      break
    case '物质平衡':
      runMaterialBalanceForSelectedWell()
      break
    case 'Wattenbarger':
      runWattenbargerForSelectedWell()
      break
    default:
      ElMessage.success(`[${group}] ${name}`)
  }
}

const handleRefreshTree = () => {
  refreshWaterInvasionNodes()
  refreshMaterialBalanceNodes()
  refreshWattenbargerNodes()
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
          v-else-if="currentView === 'material-balance'"
          :node="currentViewNode"
          :project-id="PROJECT_ID"
          :gas-reservoir-id="GAS_RESERVOIR_ID"
          @refresh-tree="handleRefreshTree"
        />
        <WattenbargerContent
          v-else-if="currentView === 'wattenbarger'"
          :node="currentViewNode"
          :project-id="PROJECT_ID"
          :gas-reservoir-id="GAS_RESERVOIR_ID"
          @refresh-tree="handleRefreshTree"
        />
      </main>
    </div>
  </div>
</template>

<style lang="scss" scoped>
$accent-yellow: #f4d000;
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
