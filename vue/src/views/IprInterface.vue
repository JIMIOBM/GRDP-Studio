<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import RibbonMenu from '@/components/RibbonMenu.vue'
import TreeNode from '@/views/TreeNode.vue'
import WaterInvasionContent from '@/views/WellControlInventory/WaterInvasionContent.vue'
import MaterialBalanceContent from '@/views/WellControlInventory/MaterialBalanceContent.vue'
import AnalyticMethodContent from '@/views/WellControlInventory/AnalyticMethodContent.vue'
import WattenbargerContent from '@/views/WellControlInventory/WattenbargerContent.vue'
import BlasingameContent from '@/views/WellControlInventory/BlasingameContent.vue'
import NpiContent from '@/views/WellControlInventory/NpiContent.vue'
import DynamicBalanceContent from '@/views/WellControlInventory/DynamicBalanceContent.vue'
import { NODETYPE } from '@/constants/nodeType'
import { analyticMethodApi, materialBalanceApi, nodeApi, projectApi, typicalCurveApi, waterInvasionApi } from '@/api/docker'

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
  [NODETYPE.NodeType_WaterInvasionAnalysis]: 'well-control-inventory',  //水侵分析节点，要放到井控库存下面
  [NODETYPE.NodeType_AnalysisMethods]: 'well-control-inventory',
  [NODETYPE.NodeType_DynamicOriginalGasInplace]: 'well-control-inventory',
  [NODETYPE.NodeType_TypicalCurveBlasingame]: 'well-control-inventory',
  [NODETYPE.NodeType_TypicalCurveWattenbarger]: 'well-control-inventory',
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
  [NODETYPE.NodeType_TypicalCurve]: '诊断曲线',
  [NODETYPE.NodeType_TypicalCurveBlasingame]: 'Blasingame',
  [NODETYPE.NodeType_TypicalCurveWattenbarger]: 'Wattenbarger',
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

const activeNodeId = ref('')  // 当前左侧树选中的节点 ID. 用于高亮显示
const activeNode = ref(null)  // 当前选中的完整节点对象
const currentView = ref(null)  // currentView.value = 'water-invasion'，即确定右侧部分区域所显示的界面
const currentViewNode = ref(null)  // 传给右侧内容组件的节点对象
const wellKeyword = ref('')
const sideTreeCollapsed = ref(false)
const waterInvasionRunning = ref(false)  //用于判断水侵分析是否正在运行
const analyticMethodRunning = ref(false)
const materialBalanceRunning = ref(false)
const typicalCurveRunning = ref(false)
const WATER_INVASION_ANALYSIS_ERROR = '水侵分析计算失败，未生成分析结果节点'
const BLASINGAME_FITTING_REGRESSION_ERROR = '计算动态储量错误:参与回归分析的数据点数必须大于0'
const selectedWellName = ref('')
const treeContextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  node: null
})

const toggleSideTree = () => {
  sideTreeCollapsed.value = !sideTreeCollapsed.value
}  //当前选中的井名

const filteredTreeData = computed(() => {   //搜索井名，控制左侧树搜索
  const keyword = wellKeyword.value.trim().toLowerCase()
  if (!keyword) return treeData.value

  return treeData.value.map(node => {
    if (node.id !== 'g-well') return node  // 只有“井”这个分组会被搜索过滤。

    return {
      ...node,
      children: node.children.filter(well =>
          String(well.label || '').toLowerCase().includes(keyword)
      )
    }
  })
})

const normalizePayload = (res) => res?.data?.data ?? res?.data ?? res

const toArray = (value) => { // 把数据统一变成数组
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

const BLASINGAME_NODE_TYPES = new Set([
  NODETYPE.NodeType_TypicalCurveBlasingame,
  NODETYPE.NodeType_VerticalWellTypicalCurveBlasingame,
  NODETYPE.NodeType_HorizontalWellTypicalCurveBlasingame,
  NODETYPE.NodeType_FracturedVerticalWellTypicalCurveBlasingame,
  NODETYPE.NodeType_FracturedHorizontalWellTypicalCurveBlasingame
])

const NPI_NODE_TYPES = new Set([
  NODETYPE.NodeType_TypicalCurveNPI,
  NODETYPE.NodeType_VerticalWellTypicalCurveNPI,
  NODETYPE.NodeType_HorizontalWellTypicalCurveNPI,
  NODETYPE.NodeType_FracturedVerticalWellTypicalCurveNPI,
  NODETYPE.NodeType_FracturedHorizontalWellTypicalCurveNPI
])

const WATTENBARGER_NODE_TYPES = new Set([
  NODETYPE.NodeType_TypicalCurveWattenbarger,
  NODETYPE.NodeType_VerticalWellTypicalCurveWattenbarger,
  NODETYPE.NodeType_HorizontalWellTypicalCurveWattenbarger,
  NODETYPE.NodeType_FracturedVerticalWellTypicalCurveWattenbarger,
  NODETYPE.NodeType_FracturedHorizontalWellTypicalCurveWattenbarger
])

const isBlasingameNode = (item) => {
  const nodeType = item?.nodeType ?? item?.type
  return BLASINGAME_NODE_TYPES.has(nodeType) || getNodeName(item) === 'Blasingame'
}

const isNpiNode = (item) => {
  const nodeType = item?.nodeType ?? item?.type
  const nodeName = getNodeName(item)
  return NPI_NODE_TYPES.has(nodeType) || nodeName === 'Normalized Pressure Integral' || nodeName === 'NPI'
}

const isWattenbargerNode=(item) =>{
  const nodeType = item?.nodeType ?? item?.type
  return WATTENBARGER_NODE_TYPES.has(nodeType) || getNodeName(item) === 'Wattenbarger'
}

const isWaterInvasionNode = (item) => {
  const nodeType = item?.nodeType ?? item?.type
  return nodeType === NODETYPE.NodeType_WaterInvasionAnalysis || getNodeName(item) === '水侵分析'
}

const isTreeContextMenuNode = (item) => isWaterInvasionNode(item) || isBlasingameNode(item)

const treeContextMenuLabel = computed(() => {
  if (isBlasingameNode(treeContextMenu.value.node)) return '删除Blasingame分析结果'
  return '删除水侵动态分析结果'
})

const findBlasingameNodeByWell = (root, wellName) => {
  if (!root || !wellName) return null

  const visit = (item, currentWellName = '') => {
    if (!item) return null

    const nodeName = getNodeName(item)
    const nodeType = item?.nodeType ?? item?.type
    const nextWellName = nodeType === NODETYPE.NodeType_Well || nodeName === wellName
        ? nodeName
        : currentWellName

    if (nextWellName === wellName && isBlasingameNode(item)) {
      return item
    }

    for (const child of getChildren(item)) {
      const found = visit(child, nextWellName)
      if (found) return found
    }

    return null
  }

  return visit(root)
}

const findNpiNodeByWell = (root, wellName) => {
  if (!root || !wellName) return null

  const visit = (item, currentWellName = '') => {
    if (!item) return null
    const nodeName = getNodeName(item)
    const nodeType = item?.nodeType ?? item?.type
    const nextWellName = nodeType === NODETYPE.NodeType_Well || nodeName === wellName
        ? nodeName
        : currentWellName
    if (nextWellName === wellName && isNpiNode(item)) return item
    for (const child of getChildren(item)) {
      const found = visit(child, nextWellName)
      if (found) return found
    }
    return null
  }

  return visit(root)
}

const findWattenbargerNodeByWell = (root, wellName) => {
  if (!root || !wellName) return null

  const visit = (item, currentWellName = '') => {
    if (!item) return null
    const nodeName = getNodeName(item)
    const nodeType = item?.nodeType ?? item?.type
    const nextWellName = nodeType === NODETYPE.NodeType_Well || nodeName === wellName
        ? nodeName
        : currentWellName
    if (nextWellName === wellName && isWattenbargerNode(item)) {
      return item
    }
    for (const child of getChildren(item)) {
      const found = visit(child, nextWellName)
      if (found) return found
    }
    return null
  }
  return visit(root)
}


const createEmptyWell = (wellName, wellId) => ({ //创建一口空井
  id: wellId || `well-${wellName}`,
  label: wellName,
  type: NODETYPE.NodeType_Well,
  wellName,
  defaultExpanded: false,
  children: WELL_GROUPS.map(group => ({
    ...group,
    id: `${wellId || wellName}-${group.id}`,
    type: group.id,
    defaultExpanded: false,
    children: []
  }))
})

const getWellGroup = () => treeData.value.find(node => node.id === 'g-well')
const getAllWellNames = () =>
    getWellGroup()?.children.map(item => item.wellName || item.label).filter(Boolean) || []

const getSelectedAnalyticWellNames = () => {
  if (!selectedWellName.value) return []
  if (activeNode.value?.id === 'g-well') return getAllWellNames()
  return [selectedWellName.value]
}

const ensureWell = (wellName, wellId) => { //确保井存在
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

const addAnalysisNode = (wellName, rawNode) => {  // 添加分析节点
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

const addBlasingameNode = (wellName, rawNode = {}) => {
  const wellItem = ensureWell(wellName, rawNode?.wellId || rawNode?.parentId)
  const inventoryGroup = wellItem?.children.find(item => item.type === 'well-control-inventory')
  if (!inventoryGroup) return null

  const parentId = `${wellItem.id}-diagnostic-curve`
  let diagnosticNode = inventoryGroup.children.find(item =>
      item.id === parentId || item.type === NODETYPE.NodeType_TypicalCurve || item.label === '诊断曲线'
  )

  if (!diagnosticNode) {
    diagnosticNode = {
      id: parentId,
      label: '诊断曲线',
      type: NODETYPE.NodeType_TypicalCurve,
      wellName,
      defaultExpanded: true,
      children: []
    }
    inventoryGroup.children.push(diagnosticNode)
  } else {
    diagnosticNode.defaultExpanded = true
    diagnosticNode.children = diagnosticNode.children || []
  }

  const id = rawNode?.nodeId || rawNode?.id || `${parentId}-blasingame`
  const blasingameNode = {
    id,
    label: 'Blasingame',
    type: NODETYPE.NodeType_TypicalCurveBlasingame,
    wellName,
    raw: rawNode
  }

  const existedIndex = diagnosticNode.children.findIndex(item =>
      item.id === id || item.type === NODETYPE.NodeType_TypicalCurveBlasingame || item.label === 'Blasingame'
  )
  if (existedIndex >= 0) {
    diagnosticNode.children[existedIndex] = blasingameNode
  } else {
    diagnosticNode.children.push(blasingameNode)
  }

  return blasingameNode
}

const addNpiNode = (wellName, rawNode = {}) => {
  const wellItem = ensureWell(wellName, rawNode?.wellId || rawNode?.parentId)
  const inventoryGroup = wellItem?.children.find(item => item.type === 'well-control-inventory')
  if (!inventoryGroup) return null

  const parentId = `${wellItem.id}-diagnostic-curve`
  let diagnosticNode = inventoryGroup.children.find(item =>
      item.id === parentId || item.type === NODETYPE.NodeType_TypicalCurve || item.label === '诊断曲线'
  )
  if (!diagnosticNode) {
    diagnosticNode = {
      id: parentId,
      label: '诊断曲线',
      type: NODETYPE.NodeType_TypicalCurve,
      wellName,
      defaultExpanded: true,
      children: []
    }
    inventoryGroup.children.push(diagnosticNode)
  }
  diagnosticNode.defaultExpanded = true
  diagnosticNode.children = diagnosticNode.children || []

  const id = rawNode?.nodeId || rawNode?.id || `${parentId}-npi`
  const npiNode = {
    id,
    label: 'NPI',
    type: rawNode?.nodeType || rawNode?.type || NODETYPE.NodeType_TypicalCurveNPI,
    wellName,
    raw: rawNode
  }
  const existedIndex = diagnosticNode.children.findIndex(item =>
      item.id === id || NPI_NODE_TYPES.has(item.type) || item.label === 'Normalized Pressure Integral' || item.label === 'NPI'
  )
  if (existedIndex >= 0) diagnosticNode.children[existedIndex] = npiNode
  else diagnosticNode.children.push(npiNode)
  return npiNode
}

const addWattenbargerNode = (wellName, rawNode = {}) => {
  const wellItem = ensureWell(wellName, rawNode?.wellId || rawNode?.parentId)
  const inventoryGroup = wellItem?.children.find(item => item.type === 'well-control-inventory')
  if (!inventoryGroup) return null

  const parentId = `${wellItem.id}-diagnostic-curve`
  let diagnosticNode = inventoryGroup.children.find(item =>
      item.id === parentId || item.type === NODETYPE.NodeType_TypicalCurve || item.label === '诊断曲线'
  )

  if (!diagnosticNode) {
    diagnosticNode = {
      id: parentId,
      label: '诊断曲线',
      type: NODETYPE.NodeType_TypicalCurve,
      wellName,
      defaultExpanded: true,
      children: []
    }
    inventoryGroup.children.push(diagnosticNode)
  } else {
    diagnosticNode.defaultExpanded = true
    diagnosticNode.children = diagnosticNode.children || []
  }

  const id = rawNode?.nodeId || rawNode?.id || `${parentId}-wattenbarger`
  const wattenbargerNode = {
    id,
    label: 'Wattenbarger',
    type: NODETYPE.NodeType_TypicalCurveWattenbarger,
    wellName,
    raw: rawNode
  }

  const existedIndex = diagnosticNode.children.findIndex(item =>
      item.id === id || item.type === NODETYPE.NodeType_TypicalCurveWattenbarger || item.label === 'Wattenbarger'
  )
  if (existedIndex >= 0) {
    diagnosticNode.children[existedIndex] = wattenbargerNode
  } else {
    diagnosticNode.children.push(wattenbargerNode)
  }

  return wattenbargerNode
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

const applyAnalyticMethodNodes = (node) => {
  const analyticRoot = (node?.subNodes || []).find(sub => sub.nodeType === NODETYPE.NodeType_AnalysisMethods)
  if (!analyticRoot?.subNodes?.length) return

  analyticRoot.subNodes.forEach(wellNode => {
    const wellName = wellNode.nodeTitle || wellNode.wellName
    if (!wellName || !wellNode.nodeId) return

    ensureWell(wellName, wellNode.nodeId || `well-${wellName}`)
    addAnalysisNode(wellName, {
      ...wellNode,
      nodeType: NODETYPE.NodeType_AnalysisMethods,
      nodeTitle: '解析法',
      resultId: wellNode.nodeId,
      analysisId: wellNode.nodeId
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

const ensureMaterialBalanceNodeForWell = (wellName, rawNode = {}) => {
  if (!wellName) return null

  const wellItem = ensureWell(wellName, rawNode?.nodeId || rawNode?.wellId || `well-${wellName}`)
  addAnalysisNode(wellName, {
    ...rawNode,
    nodeType: NODETYPE.NodeType_DynamicOriginalGasInplace,
    nodeTitle: '物质平衡',
    wellName
  })

  const targetGroup = wellItem?.children.find(item => item.type === 'well-control-inventory')
  return targetGroup?.children.find(item =>
      item.type === NODETYPE.NodeType_DynamicOriginalGasInplace &&
      item.wellName === wellName
  )
}

const clearTypicalCurveNodes = () => {
  const wells = getWellGroup()?.children ?? []

  wells.forEach(well => {
    const inventoryGroup = well.children?.find(item => item.type === 'well-control-inventory')
    if (!inventoryGroup?.children?.length) return

    inventoryGroup.children = inventoryGroup.children.filter(item =>
        item.type !== NODETYPE.NodeType_TypicalCurve
    )
  })
}

const applyTypicalCurveNodes = (node) => {
  clearTypicalCurveNodes()

  const subNodes = node?.subNodes ?? []
  if (!subNodes.length) return

  const wellNames = new Set((getWellGroup()?.children ?? []).map(well => well.label))
  const visit = (item, fallbackWellName = '', inTypicalCurve = false) => {
    const nodeName = item?.wellName || item?.nodeTitle || item?.label || ''
    const wellName = wellNames.has(nodeName) ? nodeName : fallbackWellName
    const children = item?.subNodes ?? item?.children ?? []
    const nodeType = item?.nodeType ?? item?.type
    const nextInTypicalCurve = inTypicalCurve ||
        nodeType === NODETYPE.NodeType_TypicalCurve ||
        nodeName === '典型曲线' ||
        nodeName === '诊断曲线'

    if (wellName && nextInTypicalCurve && isBlasingameNode(item)) {
      addBlasingameNode(wellName, {
        ...item,
        nodeType: NODETYPE.NodeType_TypicalCurveBlasingame,
        nodeTitle: 'Blasingame'
      })
    }

    if (wellName && nextInTypicalCurve && isNpiNode(item)) {
      addNpiNode(wellName, {
        ...item,
        nodeTitle: 'Normalized Pressure Integral'
      })
    }

    if (wellName && nextInTypicalCurve && isWattenbargerNode(item)) {
      addWattenbargerNode(wellName, {
        ...item,
        nodeType: NODETYPE.NodeType_TypicalCurveWattenbarger,
        nodeTitle: 'Wattenbarger'
      })
    }

    children.forEach(child => visit(child, wellName, nextInTypicalCurve))
  }

  subNodes.forEach(item => visit(item))
}

const nodeContainsWell = (node, wellName) => {
  if (!node || !wellName) return false
  const nodeName = node?.wellName || node?.nodeTitle || node?.label || ''
  if (nodeName === wellName) return true

  const children = node?.subNodes ?? node?.children ?? []
  return children.some(child => nodeContainsWell(child, wellName))
}

const refreshProjectTree = async () => { //加在项目树
  try {
    const res = await projectApi.getProject(PROJECT_ID)
    rebuildProjectTree(normalizePayload(res))
  } catch (error) {
    console.warn('项目树加载失败', error)
  }
}

const refreshWaterInvasionNodes = async () => {  //加载已有水侵分析节点
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_WaterInvasionAnalysis)
    applyWaterInvasionNodes(res?.data?.node)
  } catch {
    // 没有已有水侵分析结果时，保持项目树不变。
  }
}

const refreshAnalyticMethodNodes = async () => {
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
    applyAnalyticMethodNodes(res?.data?.node)
  } catch {
    // 没有已有解析法结果时保持项目树不变。
  }
}

const findAnalyticMethodNode = (wellName) => {
  const well = getWellGroup()?.children.find(item => item.wellName === wellName || item.label === wellName)
  const group = well?.children.find(item => item.type === 'well-control-inventory')
  return group?.children.find(item => item.type === NODETYPE.NodeType_AnalysisMethods)
}

const refreshMaterialBalanceNodes = async () => {
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_DynamicOriginalGasInplace)
    applyMaterialBalanceNodes(res?.data?.node)
  } catch {
    // 没有已有物质平衡结果时保持项目树不变。
  }
}

const refreshTypicalCurveNodes = async () => {
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
    applyTypicalCurveNodes(res?.data?.node)
  } catch {
    // 没有已有典型曲线结果时保持项目树不变。
  }
}

const pollWaterInvasionNode = async (wellName, maxRetries = 20, intervalMs = 1500) => { //轮询结果
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

const getWaterInvasionNodeOnce = async (wellName, delayMs = 1200) => {
  if (delayMs > 0) {
    await new Promise(resolve => setTimeout(resolve, delayMs))
  }

  const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_WaterInvasionAnalysis)
  const rootNode = res?.data?.node
  const resultNode = rootNode?.subNodes?.find(sub => sub.nodeTitle === wellName || sub.wellName === wellName)
  return { rootNode, resultNode }
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

const fetchMaterialBalanceNode = async () => {
  const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_DynamicOriginalGasInplace)
  return res?.data?.node
}

const pollMaterialBalanceNode = async ( maxRetries = 20, intervalMs = 1500) => {
  for (let i = 0; i < maxRetries; i++) {
    await new Promise(resolve => setTimeout(resolve, intervalMs))

    // const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_DynamicOriginalGasInplace)
    // const node = res?.data?.node
    // const subNodes = node?.subNodes ?? []
    // if (!wellName || subNodes.some(sub => sub.nodeTitle === wellName || sub.wellName === wellName)) {
    const node = await fetchMaterialBalanceNode()
    // const subNodes = node?.subNodes ?? []
    if (node) {
      return node
    }
  }

  throw new Error('物质平衡计算超时，请稍后刷新查看结果')
}

const pollTypicalCurveNode = async (wellName, maxRetries = 20, intervalMs = 1500) => {
  for (let i = 0; i < maxRetries; i++) {
    await new Promise(resolve => setTimeout(resolve, intervalMs))

    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
    const node = res?.data?.node
    const subNodes = node?.subNodes ?? []
    if (!wellName || subNodes.some(sub => nodeContainsWell(sub, wellName))) {
      return node
    }
  }

  throw new Error('Blasingame计算超时，请稍后刷新查看结果')
}

const getBlasingameNodeOnce = async (wellName, delayMs = 1200) => {
  if (delayMs > 0) {
    await new Promise(resolve => setTimeout(resolve, delayMs))
  }

  const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
  const rootNode = res?.data?.node
  const blasingameNode = findBlasingameNodeByWell(rootNode, wellName)
  return { rootNode, blasingameNode }
}

const getNpiNodeOnce = async (wellName, delayMs = 1200) => {
  if (delayMs > 0) await new Promise(resolve => setTimeout(resolve, delayMs))
  const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
  const rootNode = res?.data?.node
  return { rootNode, npiNode: findNpiNodeByWell(rootNode, wellName) }
}

const getWattenbargerNodeOnce = async (wellName, delayMs = 1200) => {
  if (delayMs > 0) await new Promise(resolve => setTimeout(resolve, delayMs))
  const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
  const rootNode = res?.data?.node
  return { rootNode, wattenbargerNode: findWattenbargerNodeByWell(rootNode, wellName) }
}

const runWaterInvasionForSelectedWell = async () => { //点击水侵分析的操作
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
      isUseActualStaticPressure: true,
      waterGasRatioLimit: -1
    })

    ElMessage.info(`${targetWellName} 水侵分析计算中，请稍候...`)
    const { rootNode, resultNode } = await getWaterInvasionNodeOnce(targetWellName)

    if (!resultNode) {
      throw new Error(WATER_INVASION_ANALYSIS_ERROR)
    }

    applyWaterInvasionNodes(rootNode)

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

const runAnalyticMethodForSelectedWell = async () => {
  const wellNames = getSelectedAnalyticWellNames()
  if (!wellNames.length) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }

  if (analyticMethodRunning.value) return

  analyticMethodRunning.value = true
  try {
    await analyticMethodApi.historyFitting({
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      wellNames,
      dataSize: 30,
      minimumWaterGasRatio: -1
    })

    ElMessage.info('解析法计算中，请稍候...')
    const resultNodes = await pollAnalyticMethodNodes(wellNames)
    if (wellNames.length === 1 && resultNodes[0]?.raw) {
      const resultNode = resultNodes[0].raw
      const viewNode = {
        id: resultNode?.nodeId || resultNode?.resultId || `am-${wellNames[0]}`,
        label: '解析法',
        type: NODETYPE.NodeType_AnalysisMethods,
        wellName: wellNames[0],
        raw: resultNode
      }

      activeNodeId.value = viewNode.id
      currentView.value = 'analytic-method'
      currentViewNode.value = viewNode
    }

    ElMessage.success('解析法结果已更新')
  } catch (error) {
    const msg = error.response?.data?.msg || error.response?.data?.message || ''
    ElMessage.error(msg || error.message || '解析法计算失败')
    console.error('解析法计算失败', error)
  } finally {
    analyticMethodRunning.value = false
  }
}

const runMaterialBalanceForSelectedWell = async () => {
  const targetWellName = selectedWellName.value
  // 接口需要批量计算全部井，但页面仍只展示当前选中井的计算状态。
  const allWellNames = getAllWellNames()

  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }

  if (materialBalanceRunning.value) return
  materialBalanceRunning.value = true
  try {
    await materialBalanceApi.calc({
      wellNames: allWellNames,
      gasReservoirType: 1,
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      waterGasRatioLimit: 0.0602
    })

    ElMessage.info(`${targetWellName} 物质平衡计算中，请稍候...`)
    const node = await pollMaterialBalanceNode()
    applyMaterialBalanceNodes(node)

    const subNodes = node?.subNodes ?? []
    const resultNode = subNodes.find(sub => sub.nodeTitle === targetWellName || sub.wellName === targetWellName)
    // node 返回的列表只包含具有物质平衡结果的井。
    // 当前井不在列表中时，不再创建虚假的物质平衡节点。
    if (!resultNode) {
      ElMessage.warning(`${targetWellName}井物质平衡结果不存在`)
      return
    }
    const materialBalanceRawNode = {
      ...resultNode,
      parentNode: node,
      rootNode: node,
      nodeType: NODETYPE.NodeType_DynamicOriginalGasInplace,
      nodeTitle: '物质平衡'
    }
    const treeNode = ensureMaterialBalanceNodeForWell(targetWellName, materialBalanceRawNode)
    const viewNode = treeNode||{
      id: materialBalanceRawNode?.nodeId || materialBalanceRawNode?.resultId || `mb-${targetWellName}`,
      label: '物质平衡',
      type: NODETYPE.NodeType_DynamicOriginalGasInplace,
      wellName: targetWellName,
      raw: resultNode
    }

    activeNodeId.value = viewNode.id
    currentView.value = 'material-balance'
    currentViewNode.value = viewNode
    ElMessage.success(`${targetWellName} 物质平衡计算完成`)
  } catch (error) {
    ElMessage.error(error.message || '物质平衡计算失败')
    console.error('物质平衡计算失败', error)
  } finally {
    materialBalanceRunning.value = false
  }
}

const runBlasingameForSelectedWell = async () => {
  const targetWellName = selectedWellName.value

  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }

  if (typicalCurveRunning.value) return

  typicalCurveRunning.value = true
  try {
    await typicalCurveApi.fitting({
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      wellNames: [targetWellName],
      fittingType: 1,
      isSkipFitting: false,
      dataSize: 300,
      fineScanDataSize: 30,
      initScanDataSize: 10,
      minimumWaterGasRatio: 0.0602
    })

    ElMessage.info(`${targetWellName} Blasingame计算中，请稍候...`)
    const { rootNode, blasingameNode } = await getBlasingameNodeOnce(targetWellName)

    if (!blasingameNode) {
      throw new Error(BLASINGAME_FITTING_REGRESSION_ERROR)
    }

    applyTypicalCurveNodes(rootNode)

    const resultNode = blasingameNode
    const nodeId = resultNode?.nodeId || resultNode?.id

    if (!nodeId) {
      throw new Error('没有找到 Blasingame 对应的 nodeId')
    }

    const resultRes = await typicalCurveApi.getResult(PROJECT_ID, GAS_RESERVOIR_ID, nodeId)
    const result = normalizePayload(resultRes)

    const treeNode = addBlasingameNode(targetWellName, {
      ...resultNode,
      nodeType: NODETYPE.NodeType_TypicalCurveBlasingame,
      nodeTitle: 'Blasingame'
    })

    const viewNode = {
      id: nodeId,
      label: 'Blasingame',
      type: NODETYPE.NodeType_TypicalCurveBlasingame,
      wellName: targetWellName,
      raw: result,
      treeNode: resultNode
    }

    activeNodeId.value = viewNode.id
    currentView.value = 'blasingame'
    currentViewNode.value = viewNode
    activeNode.value = treeNode || viewNode
    ElMessage.success(`${targetWellName} Blasingame计算完成`)
  } catch (error) {
    ElMessage.error(error.message || 'Blasingame计算失败')
    console.error('Blasingame计算失败', error)
  } finally {
    typicalCurveRunning.value = false
  }
}

const runNpiForSelectedWell = async () => {
  const targetWellName = selectedWellName.value
  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }
  if (typicalCurveRunning.value) return

  typicalCurveRunning.value = true
  try {
    await typicalCurveApi.fitting({
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      wellNames: [targetWellName],
      fittingType: 3,
      isSkipFitting: false,
      dataSize: 300,
      initScanDataSize: 10,
      fineScanDataSize: 30,
      minimumWaterGasRatio: 0.0602
    })

    ElMessage.info(`${targetWellName} NPI计算中，请稍候...`)
    let resultNode = null
    let rootNode = null
    for (let i = 0; i < 20; i++) {
      const result = await getNpiNodeOnce(targetWellName, 1500)
      rootNode = result.rootNode
      resultNode = result.npiNode
      if (resultNode) break
    }
    if (!resultNode) throw new Error('NPI计算超时，请稍后刷新查看结果')

    applyTypicalCurveNodes(rootNode)
    const nodeId = resultNode.nodeId || resultNode.id
    const resultRes = await typicalCurveApi.getResult(PROJECT_ID, GAS_RESERVOIR_ID, nodeId)
    const viewNode = {
      id: nodeId,
      label: 'NPI',
      type: resultNode?.nodeType || resultNode?.type || NODETYPE.NodeType_TypicalCurveNPI,
      wellName: targetWellName,
      raw: normalizePayload(resultRes),
      treeNode: resultNode
    }
    activeNodeId.value = nodeId
    currentView.value = 'npi'
    currentViewNode.value = viewNode
    ElMessage.success(`${targetWellName} NPI计算完成`)
  } catch (error) {
    const msg = error.response?.data?.msg || error.response?.data?.message || error.message
    ElMessage.error(msg || 'NPI计算失败')
    console.error('NPI计算失败', error)
  } finally {
    typicalCurveRunning.value = false
  }
}

const runWattenbargerForSelectedWell = async () => {
  const targetWellName = selectedWellName.value
  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }
  if (typicalCurveRunning.value) return
  typicalCurveRunning.value = true
  try {
    await typicalCurveApi.fitting({
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      wellNames: [targetWellName],
      fittingType: 1,
      isSkipFitting: false,
      dataSize: 300,
      fineScanDataSize: 30,
      initScanDataSize: 10,
      minimumWaterGasRatio: 0.0602
    })
    ElMessage.info(`${targetWellName} Wattenbarger计算中，请稍候...`)
    const { rootNode, wattenbargerNode } = await getWattenbargerNodeOnce(targetWellName)
    if (!wattenbargerNode) throw new Error('Wattenbarger计算超时，请稍后刷新查看结果')

    applyTypicalCurveNodes(rootNode)

    const resultNode = wattenbargerNode
    addWattenbargerNode(targetWellName, {
      ...resultNode,
      nodeType: NODETYPE.NodeType_TypicalCurveWattenbarger,
      nodeTitle: 'Wattenbarger'
    })

    const viewNode = {
      id: resultNode?.nodeId || `wattenbarger-${targetWellName}`,
      label: 'Wattenbarger',
      type: NODETYPE.NodeType_TypicalCurveWattenbarger,
      wellName: targetWellName,
      raw: resultNode
    }

    activeNodeId.value = viewNode.id
    currentView.value = 'wattenbarger'
    currentViewNode.value = viewNode
    ElMessage.success(`${targetWellName} Wattenbarger计算完成`)
  } catch (error) {
    ElMessage.error(error.message || 'Wattenbarger计算失败')
    console.error('Wattenbarger计算失败', error)
  } finally {
    typicalCurveRunning.value = false
  }
}


const openBlasingameNode = async (node) => {
  const targetWellName = node?.wellName || selectedWellName.value

  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }

  currentView.value = 'blasingame'
  currentViewNode.value = node

  try {
    const nodeRes = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
    const rootNode = nodeRes?.data?.node
    applyTypicalCurveNodes(rootNode)

    const blasingameNode = findBlasingameNodeByWell(rootNode, targetWellName) || node?.raw || node
    const nodeId = blasingameNode?.nodeId || blasingameNode?.id

    if (!nodeId) {
      throw new Error('没有找到 Blasingame 对应的 nodeId')
    }

    const resultRes = await typicalCurveApi.getResult(PROJECT_ID, GAS_RESERVOIR_ID, nodeId)
    const result = normalizePayload(resultRes)

    activeNodeId.value = nodeId
    currentViewNode.value = {
      ...node,
      id: nodeId,
      label: 'Blasingame',
      type: NODETYPE.NodeType_TypicalCurveBlasingame,
      wellName: targetWellName,
      raw: result,
      treeNode: blasingameNode
    }
  } catch (error) {
    ElMessage.error(error.message || 'Blasingame结果加载失败')
    console.error('Blasingame结果加载失败', error)
  }
}

const openNpiNode = async (node) => {
  const targetWellName = node?.wellName || selectedWellName.value
  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }
  currentView.value = 'npi'
  currentViewNode.value = node
  try {
    const nodeRes = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
    const rootNode = nodeRes?.data?.node
    applyTypicalCurveNodes(rootNode)
    const npiNode = findNpiNodeByWell(rootNode, targetWellName) || node?.raw || node
    const nodeId = npiNode?.nodeId || npiNode?.id
    if (!nodeId) throw new Error('没有找到 NPI 对应的 nodeId')
    const resultRes = await typicalCurveApi.getResult(PROJECT_ID, GAS_RESERVOIR_ID, nodeId)
    activeNodeId.value = nodeId
    currentViewNode.value = {
      ...node,
      id: nodeId,
      label: 'NPI',
      type: npiNode?.nodeType || npiNode?.type || node?.type || NODETYPE.NodeType_TypicalCurveNPI,
      wellName: targetWellName,
      raw: normalizePayload(resultRes),
      treeNode: npiNode
    }
  } catch (error) {
    ElMessage.error(error.message || 'NPI结果加载失败')
    console.error('NPI结果加载失败', error)
  }
}

const openWattenbargerNode = async (node) => {
  const targetWellName = node?.wellName || selectedWellName.value

  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }

  currentView.value = 'wattenbarger'
  currentViewNode.value = node

  try {
    const nodeRes = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
    const rootNode = nodeRes?.data?.node
    applyTypicalCurveNodes(rootNode)

    const wattenbargerNode = findWattenbargerNodeByWell(rootNode, targetWellName) || node?.raw || node
    const nodeId = wattenbargerNode?.nodeId || wattenbargerNode?.id

    if (!nodeId) {
      throw new Error('没有找到 Wattenbarger 对应的 nodeId')
    }

    const resultRes = await typicalCurveApi.getResult(PROJECT_ID, GAS_RESERVOIR_ID, nodeId)
    const result = normalizePayload(resultRes)

    activeNodeId.value = nodeId
    currentViewNode.value = {
      ...node,
      id: nodeId,
      label: 'Wattenbarger',
      type: NODETYPE.NodeType_TypicalCurveWattenbarger,
      wellName: targetWellName,
      raw: result,
      treeNode: wattenbargerNode
    }
  } catch (error) {
    ElMessage.error(error.message || 'Wattenbarger结果加载失败')
    console.error('Wattenbarger结果加载失败', error)
  }
}

const initTree = async () => {
  await refreshProjectTree()
  await refreshWaterInvasionNodes()
  await refreshAnalyticMethodNodes()
  await refreshMaterialBalanceNodes()
  await refreshTypicalCurveNodes()
}

const closeTreeContextMenu = () => {
  treeContextMenu.value.visible = false
}

const handleNodeContextMenu = (node, event) => {
  if (!isTreeContextMenuNode(node)) {
    closeTreeContextMenu()
    return
  }

  const menuWidth = 190
  const menuHeight = 42
  const x = Math.min(event.clientX, window.innerWidth - menuWidth - 8)
  const y = Math.min(event.clientY, window.innerHeight - menuHeight - 8)

  treeContextMenu.value = {
    visible: true,
    x: Math.max(8, x),
    y: Math.max(8, y),
    node
  }
}

const handleDeleteContextNode = () => {
  const node = treeContextMenu.value.node
  closeTreeContextMenu()

  if (!node) return

  ElMessage.info(`${treeContextMenuLabel.value}：删除接口待确认`)
}

const handleSelect = (node) => { // 点击左侧树节点
  closeTreeContextMenu()
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

  if (node.type === NODETYPE.NodeType_AnalysisMethods) {
    currentView.value = 'analytic-method'
    currentViewNode.value = node
    return
  }

  if (node.type === NODETYPE.NodeType_DynamicOriginalGasInplace) {
    currentView.value = 'material-balance'
    currentViewNode.value = node
    return
  }

  if (node.type === NODETYPE.NodeType_TypicalCurveBlasingame) {
    openBlasingameNode(node)
    return
  }

  if (NPI_NODE_TYPES.has(node.type)) {
    openNpiNode(node)
    return
  }

  if (node.type === NODETYPE.NodeType_TypicalCurveWattenbarger) {
    openWattenbargerNode(node)
    return
  }

  if (node.type === NODETYPE.NodeType_Well) return
}

const handleCommand = ({ group, name }) => { // 接收顶部菜单栏的点击事件
  switch (name) {
    case '水侵分析':
      runWaterInvasionForSelectedWell()
      break
    case '解析法':
      runAnalyticMethodForSelectedWell()
      break
    case '物质平衡':
      runMaterialBalanceForSelectedWell()
      break
    case 'Blasingame':
      runBlasingameForSelectedWell()
      break
    case 'NPI':
      runNpiForSelectedWell()
      break
    case 'Wattenbarger':
      runWattenbargerForSelectedWell()
      break
    case '动态平衡':
      if (!selectedWellName.value) {
        ElMessage.warning('请先在左侧选择一口井')
        return
      }
      currentView.value = 'dynamic-balance'
      currentViewNode.value = {
        id: `db-${selectedWellName.value}`,
        label: '动态平衡',
        wellName: selectedWellName.value,
        raw: {}
      }
      activeNodeId.value = currentViewNode.value.id
      break
    case '动态平衡':
      if (!selectedWellName.value) {
        ElMessage.warning('请先在左侧选择一口井')
        return
      }
      currentView.value = 'dynamic-balance'
      currentViewNode.value = {
        id: `db-${selectedWellName.value}`,
        label: '动态平衡',
        wellName: selectedWellName.value,
        raw: {}
      }
      activeNodeId.value = currentViewNode.value.id
      break
    default:
      ElMessage.success(`[${group}] ${name}`)
  }
}

const handleRefreshTree = () => {
  refreshWaterInvasionNodes()
  refreshAnalyticMethodNodes()
  refreshMaterialBalanceNodes()
  refreshTypicalCurveNodes()
}

onMounted(() => {
  initTree()
  window.addEventListener('click', closeTreeContextMenu)
  window.addEventListener('resize', closeTreeContextMenu)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', closeTreeContextMenu)
  window.removeEventListener('resize', closeTreeContextMenu)
})
</script>

<template>
  <div class="ipr-container">
    <!--    顶部菜单栏目-->
    <RibbonMenu @command="handleCommand" />


    <div class="ipr-main">
      <!--      左侧菜单栏-->
      <aside class="side-panel" :class="{ collapsed: sideTreeCollapsed }">
        <button
            v-if="sideTreeCollapsed"
            class="side-collapsed-tab"
            type="button"
            title="&#x5C55;&#x5F00;&#x76EE;&#x5F55;"
            @click="toggleSideTree"
        >
          &#x76EE;&#x5F55;
        </button>

        <div v-show="!sideTreeCollapsed" class="side-search">
          <el-input
              v-model="wellKeyword"
              size="small"
              clearable
              placeholder="&#x641C;&#x7D22;&#x4E95;&#x540D;"
          />
          <button class="side-toggle" type="button" title="&#x6536;&#x8D77;&#x76EE;&#x5F55;" @click="toggleSideTree">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
              <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z"/>
            </svg>
          </button>
        </div>

        <div v-show="!sideTreeCollapsed" class="side-tree">
          <TreeNode
              v-for="node in filteredTreeData"
              :key="node.id"
              :node="node"
              :active-id="activeNodeId"
              @select="handleSelect"
              @node-contextmenu="handleNodeContextMenu"
          />
        </div>
      </aside>

      <!--     右侧的主要内容区域-->
      <main class="content-area">
        <WaterInvasionContent
            v-if="currentView === 'water-invasion'"
            :node="currentViewNode"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
            @refresh-tree="handleRefreshTree"
        />
        <AnalyticMethodContent
            v-if="currentView === 'analytic-method'"
            :node="currentViewNode"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
        />
        <MaterialBalanceContent
            v-if="currentView === 'material-balance'"
            :node="currentViewNode"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
            @refresh-tree="handleRefreshTree"
        />
        <BlasingameContent
            v-if="currentView === 'blasingame'"
            :node="currentViewNode"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
        />
        <NpiContent
            v-if="currentView === 'npi'"
            :node="currentViewNode"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
        />
        <WattenbargerContent
            v-if="currentView === 'wattenbarger'"
            :node="currentViewNode"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
        />
        <DynamicBalanceContent
            v-if="currentView === 'dynamic-balance'"
            :node="currentViewNode"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
        />
      </main>
    </div>

    <Teleport to="body">
      <div
          v-if="treeContextMenu.visible"
          class="tree-context-menu"
          :style="{ left: `${treeContextMenu.x}px`, top: `${treeContextMenu.y}px` }"
          @click.stop
          @contextmenu.prevent
      >
        <button class="tree-context-menu-item" type="button" @click="handleDeleteContextNode">
          <el-icon class="tree-context-menu-icon"><Delete /></el-icon>
          <span>{{ treeContextMenuLabel }}</span>
        </button>
      </div>
    </Teleport>
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
  min-width: 210px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid $border;
  background-color: #fff;
  position: relative;
  transition: width 0.16s ease, min-width 0.16s ease;

  &.collapsed {
    width: 22px;
    min-width: 22px;
    border-right: 0;
  }

  .side-search {
    padding: 6px 6px 4px;
    border-bottom: 1px solid $border;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .side-tree {
    flex: 1;
    overflow-y: auto;
    padding: 6px 4px;
  }
}

.side-toggle {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border-radius: 2px;
  flex-shrink: 0;

  &:hover {
    background: #eef4ff;
  }
}

.side-collapsed-tab {
  width: 22px;
  height: 54px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #333;
  cursor: pointer;
  writing-mode: vertical-rl;
  text-orientation: mixed;
  font-size: 13px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;

  &:hover {
    background: #eef4ff;
    color: #1677ff;
  }
}

.content-area {
  flex: 1;
  background-color: #fff;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

:global(.tree-context-menu) {
  position: fixed;
  z-index: 4000;
  min-width: 178px;
  padding: 6px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.18);
}

:global(.tree-context-menu-item) {
  width: 100%;
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #333;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  line-height: 32px;
  cursor: pointer;
  white-space: nowrap;
}

:global(.tree-context-menu-item:hover) {
  background: #f5f7fa;
}

:global(.tree-context-menu-icon) {
  color: #d93025;
  font-size: 16px;
}
</style>
