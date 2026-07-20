<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import RibbonMenu from '@/components/RibbonMenu.vue'
import TreeNode from '@/views/TreeNode.vue'
import WaterInvasionContent from '@/views/WellControlInventory/WaterInvasionContent.vue'
import MaterialBalanceContent from '@/views/WellControlInventory/MaterialBalanceContent.vue'
import FlowBalanceContent from '@/views/WellControlInventory/FlowBalanceContent.vue'
import AnalyticMethodContent from '@/views/WellControlInventory/AnalyticMethodContent.vue'
import WattenbargerContent from '@/views/WellControlInventory/WattenbargerContent.vue'
import BlasingameContent from '@/views/WellControlInventory/BlasingameContent.vue'
import NpiContent from '@/views/WellControlInventory/NpiContent.vue'
import DynamicBalanceContent from '@/views/WellControlInventory/DynamicBalanceContent.vue'
import AGContent from '@/views/WellControlInventory/AGContent.vue'
import { NODETYPE } from '@/constants/nodeType'
import { analyticMethodApi, dynamicBalanceApi, materialBalanceApi, nodeApi, projectApi, typicalCurveApi, waterInvasionApi } from '@/api/docker'

const PROJECT_ID = 6
const GAS_RESERVOIR_ID = 3
const FLOW_BALANCE_NODE_TYPE = NODETYPE.NodeType_FlowingBalanceMethodBasedOnBottomPressure

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
  [NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame]: 'well-control-inventory',
  [FLOW_BALANCE_NODE_TYPE]: 'well-control-inventory',
  [NODETYPE.NodeType_TypicalCurveBlasingame]: 'well-control-inventory',
  [NODETYPE.NodeType_TypicalCurveWattenbarger]: 'well-control-inventory',
  [NODETYPE.NodeType_TypicalCurveAG]: 'well-control-inventory',
  [NODETYPE.NodeType_VerticalWellTypicalCurveWb]: 'well-control-inventory',
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
  [NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame]: '动态平衡',
  [NODETYPE.NodeType_TypicalCurve]: '诊断曲线',
  [FLOW_BALANCE_NODE_TYPE]: '流动平衡',
  [NODETYPE.NodeType_TypicalCurveBlasingame]: 'Blasingame',
  [NODETYPE.NodeType_TypicalCurveWattenbarger]: 'Wattenbarger',
  [NODETYPE.NodeType_ProductionDeclineAnalysis]: '产量递减分析',
  [NODETYPE.NodeType_ProductivityInstabilityAnalysis]: '产量不稳定分析',
  [NODETYPE.NodeType_ProductionAnalysis]: '生产分析',
  [NODETYPE.NodeType_ProductivityEvaluation]: '产能评AG价',
  [NODETYPE.NodeType_DynamicPrediction]: '动态预测',
  [NODETYPE.NodeType_TypicalCurveAG]: 'AG'
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
const flowBalanceRunning = ref(false)
const dynamicBalanceRunning = ref(false)
const typicalCurveRunning = ref(false)
const WATER_INVASION_ANALYSIS_ERROR = '水侵分析计算失败，未生成分析结果节点'
const WATER_INVASION_NOTIFY_MODULE = 'projectanalysis.waterinvasionanalysis'
const WATER_INVASION_LOG_TIMEOUT = 120000
const WATER_INVASION_ERROR_PATTERN = /\u5931\u8d25|\u9519\u8bef|\u5f02\u5e38|\u62a5\u9519|error|fail|exception/i
const WATER_INVASION_COMPLETE_PATTERN = /\u5b8c\u6210|\u5206\u6790\u7ed3\u675f|\u7ed3\u675f/i
const TYPICAL_CURVE_NOTIFY_MODULE = 'projectanalysis.typicalcurvefitting'
const TYPICAL_CURVE_LOG_TIMEOUT = 120000
const FLOW_BALANCE_LOG_TIMEOUT = 120000
const FLOW_BALANCE_LOG_PATTERN = /\u6d41\u52a8\u7269\u8d28\u5e73\u8861|\u6d41\u52a8\u5e73\u8861|\u4e95\u5e95\u6d41\u538b/i
const BLASINGAME_FITTING_REGRESSION_ERROR = '计算动态储量错误:参与回归分析的数据点数必须大于0'
const AG_FITTING_REGRESSION_ERROR = '计算AG节点错误:参与回归分析的数据点数必须大于0'
const selectedWellName = ref('')
const treeContextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  node: null
})
const loadingWellChildren = new Set()

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

const getResponseMessage = (res) =>
    res?.data?.message || res?.data?.msg || res?.message || res?.msg || ''

const isFailureResponse = (res) => {
  const code = res?.data?.code ?? res?.code
  const success = res?.data?.success ?? res?.success
  return success === false || (code !== undefined && Number(code) !== 0 && Number(code) !== 200)
}

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
  NODETYPE.NodeType_FracturedHorizontalWellTypicalCurveBlasingame,
])

const AG_NODE_TYPES = new Set([
  NODETYPE.NodeType_TypicalCurveAG,
  NODETYPE.NodeType_VerticalWellTypicalCurveAG,
  NODETYPE.NodeType_HorizontalWellTypicalCurveAG,
  NODETYPE.NodeType_FracturedVerticalWellTypicalCurveAG,
  NODETYPE.NodeType_FracturedHorizontalWellTypicalCurveAG,
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

const isWattenbargerNode = (item) => {
  const nodeType = item?.nodeType ?? item?.type
  return WATTENBARGER_NODE_TYPES.has(nodeType) || getNodeName(item) === 'Wattenbarger'
}
const isAGNode = (item) => {
  const nodeType = item?.nodeType ?? item?.type
  const nodeName = getNodeName(item)
  return AG_NODE_TYPES.has(nodeType) || nodeName === 'Agarwal-Gardner' || nodeName === 'AG'
}

const isWaterInvasionNode = (item) => {
  const nodeType = item?.nodeType ?? item?.type
  return nodeType === NODETYPE.NodeType_WaterInvasionAnalysis || getNodeName(item) === '水侵分析'
}

const getInventoryResultName = (item) => {
  const nodeType = item?.nodeType ?? item?.type
  if (nodeType === NODETYPE.NodeType_WaterInvasionAnalysis) return '水侵分析'
  if (nodeType === NODETYPE.NodeType_AnalysisMethods) return '解析法'
  if (nodeType === NODETYPE.NodeType_DynamicOriginalGasInplace) return '物质平衡'
  if (nodeType === NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame) return '动态平衡'
  if (nodeType === FLOW_BALANCE_NODE_TYPE) return '流动平衡'

  const nodeName = getNodeName(item)
  if (nodeName === '水侵分析') return '水侵分析'
  if (nodeName === '解析法') return '解析法'
  if (nodeName === '物质平衡') return '物质平衡'
  if (nodeName === '动态平衡') return '动态平衡'
  if (nodeName === '流动平衡') return '流动平衡'
  return ''
}

const getTypicalCurveResultName = (item) => {
  if (isBlasingameNode(item)) return 'Blasingame'
  if (isAGNode(item)) return 'Agarwal-Gardner'
  if (isNpiNode(item)) return 'NPI'
  if (isWattenbargerNode(item)) return 'Wattenbarger'
  return ''
}

const isTypicalCurveResultNode = (item) => Boolean(getTypicalCurveResultName(item))
const isInventoryResultNode = (item) => Boolean(getInventoryResultName(item))

const isTreeContextMenuNode = (item) => isInventoryResultNode(item) || isTypicalCurveResultNode(item)

const treeContextMenuLabel = computed(() => {
  const resultName = getTypicalCurveResultName(treeContextMenu.value.node) || getInventoryResultName(treeContextMenu.value.node)
  if (resultName) return `\u5220\u9664${resultName}\u7ed3\u679c`
  return '删除水侵动态分析结果'
})

const getAnalysisId = (node) =>
    node?.raw?.analysisId ?? node?.raw?.analysisID ?? node?.raw?.analysis_id ??
    node?.raw?.DynamicOriginalGasInPlaceId ?? node?.raw?.dynamicOriginalGasInPlaceId ??
    node?.raw?.dynamicOriginalGasInplaceId ?? node?.raw?.dynamicOriginalGasInplaceID ??
    node?.raw?.nodeId ?? node?.raw?.id ?? node?.analysisId ?? node?.analysisID ??
    node?.DynamicOriginalGasInPlaceId ?? node?.dynamicOriginalGasInPlaceId ??
    node?.dynamicOriginalGasInplaceId ?? node?.dynamicOriginalGasInplaceID ??
    node?.analysis_id ?? node?.nodeId ?? node?.id

const getAverageFormationPressureRows = (payload) => {
  const rows =
      payload?.data?.data ??
      payload?.data ??
      payload?.items ??
      payload?.rows ??
      payload

  if (Array.isArray(rows)) return rows
  return rows ? [rows] : []
}

const getMaterialBalanceAverageRowId = (row) =>
    row?.DynamicOriginalGasInPlaceId ??
    row?.dynamicOriginalGasInPlaceId ??
    row?.dynamicOriginalGasInplaceId ??
    row?.dynamicOriginalGasInplaceID ??
    row?.id

const isMaterialBalanceAverageRow = (row) => {
  const type = Number(row?.dynamicOriginalGasInplaceType ?? row?.dynamicOriginalGasInPlaceType)
  const description = String(row?.dynamicOriginalGasInplaceMethodDescription || row?.dynamicOriginalGasInPlaceMethodDescription || '')
  return type === 1 || type === 2 || /物质平衡.*(实测静压|计算静压)/.test(description)
}

const getMaterialBalanceRowsForWell = async (wellName, options = {}) => {
  if (!wellName) return []

  const pressureRes = await materialBalanceApi.getAverageFormationPressure(
      PROJECT_ID,
      GAS_RESERVOIR_ID,
      wellName,
      options
  )

  return getAverageFormationPressureRows(pressureRes.data)
      .filter(isMaterialBalanceAverageRow)
}

const getMaterialBalanceDeleteIds = async (node) => {
  const ids = []
  const addId = (id) => {
    if (id === undefined || id === null || id === '') return
    const value = String(id)
    if (!ids.includes(value)) ids.push(value)
  }
  const nodeId = getAnalysisId(node)

  const wellName = node?.wellName || selectedWellName.value
  if (!wellName) {
    addId(nodeId)
    return ids
  }

  const materialBalanceRows = await getMaterialBalanceRowsForWell(wellName, { silentError: true })

  materialBalanceRows
      .map(getMaterialBalanceAverageRowId)
      .forEach(addId)

  addId(nodeId)
  return ids
}

const pruneEmptyDiagnosticCurveNodes = (nodes = treeData.value) => {
  let changed = false

  nodes.forEach(item => {
    if (Array.isArray(item.children) && item.children.length) {
      changed = pruneEmptyDiagnosticCurveNodes(item.children) || changed
    }
  })

  for (let index = nodes.length - 1; index >= 0; index -= 1) {
    const item = nodes[index]
    const isDiagnosticCurve = item?.type === NODETYPE.NodeType_TypicalCurve
    if (isDiagnosticCurve && (!Array.isArray(item.children) || item.children.length === 0)) {
      nodes.splice(index, 1)
      changed = true
    }
  }

  return changed
}

const removeTreeNode = (targetNode) => {
  if (!targetNode) return false

  const targetId = targetNode.id
  const targetType = targetNode.type
  const targetWellName = targetNode.wellName

  const visit = (nodes = []) => {
    const index = nodes.findIndex(item =>
        item === targetNode ||
        (targetId !== undefined && String(item.id) === String(targetId)) ||
        (
            targetType !== undefined &&
            targetWellName &&
            item.type === targetType &&
            item.wellName === targetWellName
        )
    )

    if (index >= 0) {
      nodes.splice(index, 1)
      return true
    }

    return nodes.some(item => visit(item.children || []))
  }

  const removed = visit(treeData.value)
  if (removed) pruneEmptyDiagnosticCurveNodes()
  return removed
}

const clearCurrentViewAfterDelete = (deletedNode) => {
  if (!deletedNode) return

  const deletedId = deletedNode.id
  const sameId = deletedId !== undefined && String(currentViewNode.value?.id) === String(deletedId)
  const sameResult =
      currentViewNode.value?.type === deletedNode.type &&
      currentViewNode.value?.wellName === deletedNode.wellName

  if (sameId || sameResult) {
    currentView.value = null
    currentViewNode.value = null
    activeNodeId.value = ''
    activeNode.value = null
  }
}

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
const findAGNodeByWell = (root, wellName) => {
  if (!root || !wellName) return null

  const visit = (item, currentWellName = '') => {
    if (!item) return null

    const nodeName = getNodeName(item)
    const nodeType = item?.nodeType ?? item?.type
    const nextWellName = nodeType === NODETYPE.NodeType_Well || nodeName === wellName
        ? nodeName
        : currentWellName
    if (nextWellName === wellName && isAGNode(item)) return item
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

const WELL_CONTROL_NODE_ORDER = new Map([
  [NODETYPE.NodeType_WaterInvasionAnalysis, 10],
  [NODETYPE.NodeType_AnalysisMethods, 20],
  [NODETYPE.NodeType_DynamicOriginalGasInplace, 30],
  [NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame, 40],
  [NODETYPE.NodeType_TypicalCurve, 50]
])

const TYPICAL_CURVE_NODE_ORDER = new Map([
  [NODETYPE.NodeType_TypicalCurveBlasingame, 10],
  [NODETYPE.NodeType_TypicalCurveAG, 20],
  [NODETYPE.NodeType_TypicalCurveNPI, 30],
  [NODETYPE.NodeType_TypicalCurveWattenbarger, 40]
])

const sortNodesByFixedOrder = (nodes, orderMap) => {
  nodes.sort((a, b) => {
    const left = orderMap.get(a?.type) ?? 999
    const right = orderMap.get(b?.type) ?? 999
    if (left !== right) return left - right
    return String(a?.label || '').localeCompare(String(b?.label || ''), 'zh-Hans')
  })
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

  if (targetGroup.type === 'well-control-inventory') {
    sortNodesByFixedOrder(targetGroup.children, WELL_CONTROL_NODE_ORDER)
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

  sortNodesByFixedOrder(diagnosticNode.children, TYPICAL_CURVE_NODE_ORDER)
  sortNodesByFixedOrder(inventoryGroup.children, WELL_CONTROL_NODE_ORDER)
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
  sortNodesByFixedOrder(diagnosticNode.children, TYPICAL_CURVE_NODE_ORDER)
  sortNodesByFixedOrder(inventoryGroup.children, WELL_CONTROL_NODE_ORDER)
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

  sortNodesByFixedOrder(diagnosticNode.children, TYPICAL_CURVE_NODE_ORDER)
  sortNodesByFixedOrder(inventoryGroup.children, WELL_CONTROL_NODE_ORDER)
  return wattenbargerNode
}

const addAGNode = (wellName, rawNode = {}) => {
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
  const id = rawNode?.nodeId || rawNode?.id || `${parentId}-ag`
  const agNode = {
    id,
    label: 'Agarwal-Gardner',
    type: NODETYPE.NodeType_TypicalCurveAG,
    wellName,
    raw: rawNode
  }

  const existedIndex = diagnosticNode.children.findIndex(item =>
      item.id === id || item.type === NODETYPE.NodeType_TypicalCurveAG || item.label === 'AG'
  )
  if (existedIndex >= 0) {
    diagnosticNode.children[existedIndex] = agNode
  } else {
    diagnosticNode.children.push(agNode)
  }

  sortNodesByFixedOrder(diagnosticNode.children, TYPICAL_CURVE_NODE_ORDER)
  sortNodesByFixedOrder(inventoryGroup.children, WELL_CONTROL_NODE_ORDER)
  return agNode
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
}

const applyWaterInvasionNodes = (node, targetWellName = '') => {
  if (!node?.subNodes?.length) return

  node.subNodes.forEach(wellNode => {
    const wellName = wellNode.nodeTitle || wellNode.wellName
    if (targetWellName && wellName !== targetWellName) return
    if (!wellName) return

    ensureWell(wellName, wellNode.nodeId || `well-${wellName}`)
    addAnalysisNode(wellName, {
      ...wellNode,
      nodeType: NODETYPE.NodeType_WaterInvasionAnalysis,
      nodeTitle: '水侵分析'
    })
  })
}

const applyAnalyticMethodNodes = (node, targetWellName = '') => {
  const analyticRoot = (node?.subNodes || []).find(sub => sub.nodeType === NODETYPE.NodeType_AnalysisMethods)
  if (!analyticRoot?.subNodes?.length) return

  analyticRoot.subNodes.forEach(wellNode => {
    const wellName = wellNode.nodeTitle || wellNode.wellName
    if (targetWellName && wellName !== targetWellName) return
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

const applyMaterialBalanceNodes = async (node, targetWellName = '') => {
  if (!node?.subNodes?.length) return

  for (const wellNode of node.subNodes) {
    const wellName = wellNode.nodeTitle || wellNode.wellName
    if (targetWellName && wellName !== targetWellName) continue
    if (!wellName) continue

    let materialBalanceRows = []
    try {
      materialBalanceRows = await getMaterialBalanceRowsForWell(wellName, { silentError: true })
    } catch {
      materialBalanceRows = []
    }
    if (materialBalanceRows.length) {

      ensureWell(wellName, wellNode.nodeId || `well-${wellName}`)
      addAnalysisNode(wellName, {
        ...wellNode,
        nodeType: NODETYPE.NodeType_DynamicOriginalGasInplace,
        materialBalanceRows,
        nodeTitle: '物质平衡'
      })

    }
    else {
      removeMaterialBalanceNodeForWell(wellName)
    }

    const dynamicNode = (wellNode.subNodes || []).find(item =>
        (item.nodeType ?? item.type) === NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame
    )
    if (dynamicNode?.nodeId) {
      addAnalysisNode(wellName, {
        ...dynamicNode,
        nodeType: NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame,
        nodeTitle: '动态平衡',
        wellName
      })
    }
  }
}

const findDynamicBalanceNode = (rootNode, wellName) => {
  const wellNode = (rootNode?.subNodes || []).find(item =>
      item.nodeTitle === wellName || item.wellName === wellName
  )
  return (wellNode?.subNodes || []).find(item =>
      (item.nodeType ?? item.type) === NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame
  )
}

const ensureMaterialBalanceNodeForWell = (wellName, rawNode = {}) => {
  if (!wellName) return null
  if (Array.isArray(rawNode.materialBalanceRows) && !rawNode.materialBalanceRows.length) return null

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

const clearMaterialBalanceNodes = () => {
  const wells = getWellGroup()?.children ?? []

  wells.forEach(well => {
    const inventoryGroup = well.children?.find(item => item.type === 'well-control-inventory')
    if (!inventoryGroup?.children?.length) return

    inventoryGroup.children = inventoryGroup.children.filter(item =>
        item.type !== NODETYPE.NodeType_DynamicOriginalGasInplace
    )
  })
}

const removeMaterialBalanceNodeForWell = (wellName) => {
  if (!wellName) return

  const well = getWellGroup()?.children.find(item => item.wellName === wellName || item.label === wellName)
  const inventoryGroup = well?.children?.find(item => item.type === 'well-control-inventory')
  if (!inventoryGroup?.children?.length) return

  inventoryGroup.children = inventoryGroup.children.filter(item =>
      item.type !== NODETYPE.NodeType_DynamicOriginalGasInplace
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

const clearTypicalCurveNodesForWell = (wellName) => {
  if (!wellName) return

  const well = getWellGroup()?.children.find(item => item.wellName === wellName || item.label === wellName)
  const inventoryGroup = well?.children?.find(item => item.type === 'well-control-inventory')
  if (!inventoryGroup?.children?.length) return

  inventoryGroup.children = inventoryGroup.children.filter(item =>
      item.type !== NODETYPE.NodeType_TypicalCurve
  )
}

const applyTypicalCurveNodes = (node, targetWellName = '') => {
  if (targetWellName) clearTypicalCurveNodesForWell(targetWellName)
  else clearTypicalCurveNodes()

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

    if (targetWellName && wellName && wellName !== targetWellName) return

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
    if (wellName && nextInTypicalCurve && isAGNode(item)) {
      addAGNode(wellName, {
        ...item,
        nodeType: NODETYPE.NodeType_TypicalCurveAG,
        nodeTitle: 'AG'
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

const refreshWaterInvasionNodes = async (wellName = '') => {  //加载已有水侵分析节点
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_WaterInvasionAnalysis)
    applyWaterInvasionNodes(res?.data?.node, wellName)
  } catch {
    // 没有已有水侵分析结果时，保持项目树不变。
  }
}

const refreshAnalyticMethodNodes = async (wellName = '') => {
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
    applyAnalyticMethodNodes(res?.data?.node, wellName)
  } catch {
    // 没有已有解析法结果时保持项目树不变。
  }
}

const findAnalyticMethodNode = (wellName) => {
  const well = getWellGroup()?.children.find(item => item.wellName === wellName || item.label === wellName)
  const group = well?.children.find(item => item.type === 'well-control-inventory')
  return group?.children.find(item => item.type === NODETYPE.NodeType_AnalysisMethods)
}

const refreshMaterialBalanceNodes = async (wellName = '') => {
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_DynamicOriginalGasInplace)
    await applyMaterialBalanceNodes(res?.data?.node, wellName)
  } catch {
    // 没有已有物质平衡结果时保持项目树不变。
  }
}

const refreshTypicalCurveNodes = async (wellName = '') => {
  try {
    const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
    applyTypicalCurveNodes(res?.data?.node, wellName)
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

const createWaterInvasionLogWaiter = (wellName, timeoutMs = WATER_INVASION_LOG_TIMEOUT) => {
  return createAnalysisLogWaiter({
    module: WATER_INVASION_NOTIFY_MODULE,
    wellName,
    timeoutMs,
    timeoutMessage: `${wellName}水侵分析日志超时，未收到完成消息`,
    fallbackErrorMessage: `${wellName}水侵分析失败`
  })
}

const createAnalysisLogWaiter = ({
                                   module,
                                   wellName,
                                   timeoutMs,
                                   timeoutMessage,
                                   fallbackErrorMessage,
                                   allowGlobalComplete = false,
                                   isComplete = (payload, logText) => WATER_INVASION_COMPLETE_PATTERN.test(logText),
                                   isRelevant = null
                                 }) => {
  let settled = false
  let cleanup = () => {}

  const promise = new Promise((resolve, reject) => {
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      cleanup()
      callback(value)
    }

    const timeoutId = window.setTimeout(() => {
      finish(reject, new Error(timeoutMessage))
    }, timeoutMs)

    const onNotifyMessage = (event) => {
      const message = event.detail
      const payload = message?.payload
      if (message?.type !== 'user') return

      const modules = Array.isArray(module) ? module : (module ? [module] : [])
      if (modules.length && !modules.includes(payload?.module)) return

      const logText = String(payload.message || '')
      const errorText = String(payload.error || '')
      if (isRelevant && !isRelevant(payload, logText, errorText)) return

      const logLevel = String(payload.level || '').toLowerCase()
      const complete = isComplete(payload, logText)
      const matchesWell = !wellName || logText.includes(wellName)
      if (!matchesWell && !(allowGlobalComplete && complete)) return

      if (logLevel === 'error' || WATER_INVASION_ERROR_PATTERN.test(logText) || WATER_INVASION_ERROR_PATTERN.test(errorText)) {
        finish(reject, new Error(errorText || logText || fallbackErrorMessage))
        return
      }

      if (complete) {
        finish(resolve, payload)
      }
    }

    cleanup = () => {
      window.clearTimeout(timeoutId)
      window.removeEventListener('notify-message', onNotifyMessage)
    }

    window.addEventListener('notify-message', onNotifyMessage)
  })

  return {
    promise,
    cancel: () => {
      if (settled) return
      settled = true
      cleanup()
    }
  }
}

const createBlasingameLogWaiter = (wellName, timeoutMs = TYPICAL_CURVE_LOG_TIMEOUT) =>
    createAnalysisLogWaiter({
      module: TYPICAL_CURVE_NOTIFY_MODULE,
      wellName,
      timeoutMs,
      timeoutMessage: `${wellName} Blasingame日志超时，未收到完成消息`,
      fallbackErrorMessage: `${wellName} Blasingame计算失败`,
      allowGlobalComplete: true,
      isComplete: (payload, logText) => logText.includes('\u5b8c\u6210') && !logText.includes('\u5206\u6790\u7ed3\u675f')
    })

const createFlowBalanceLogWaiter = (wellName, timeoutMs = FLOW_BALANCE_LOG_TIMEOUT) =>
    createAnalysisLogWaiter({
      module: null,
      wellName,
      timeoutMs,
      timeoutMessage: `${wellName} 流动平衡日志超时，未收到完成消息`,
      fallbackErrorMessage: `${wellName} 流动平衡计算失败`,
      allowGlobalComplete: true,
      isRelevant: (payload, logText, errorText) => {
        const text = `${payload?.module || ''} ${logText} ${errorText}`
        return FLOW_BALANCE_LOG_PATTERN.test(text)
      },
      isComplete: (payload, logText) => WATER_INVASION_COMPLETE_PATTERN.test(logText)
    })

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

const getDynamicBalanceNodeOnce = async (wellName, delayMs = 1200) => {
  if (delayMs > 0) await new Promise(resolve => setTimeout(resolve, delayMs))
  const rootNode = await fetchMaterialBalanceNode()
  return { rootNode, resultNode: findDynamicBalanceNode(rootNode, wellName) }
}

const getMaterialBalanceNodeOnce = async (wellName, delayMs = 1200) => {
  if (delayMs > 0) await new Promise(resolve => setTimeout(resolve, delayMs))
  const rootNode = await fetchMaterialBalanceNode()
  const wellNode = (rootNode?.subNodes || []).find(item =>
      item.nodeTitle === wellName || item.wellName === wellName
  )
  const resultNode = (wellNode?.subNodes || []).find(item => {
    const type = item.nodeType ?? item.type
    return [
      NODETYPE.NodeType_ConfinedGasReservoirMaterialBalanceMethodForActualStaticPressure,
      NODETYPE.NodeType_ConfinedGasReservoirMaterialBalanceMethodForCalculatedStaticPressure,
      NODETYPE.NodeType_ConstantVolumeGasReservoirMaterialBalanceMethodForActualStaticPressure,
      NODETYPE.NodeType_ConstantVolumeReservoirMaterialBalanceMethodForCalculatedStaticPressure
    ].includes(type)
  })
  return { rootNode, resultNode }
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

const finalizeBlasingameResult = async (wellName, logPayload, maxRetries = 8, intervalMs = 1000) => {
  const logNodeId = logPayload?.node ?? logPayload?.nodeId
  let lastError = null

  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      const { rootNode, blasingameNode } = await getBlasingameNodeOnce(
          wellName,
          attempt === 0 ? 1 : intervalMs
      )

      if (rootNode) {
        applyTypicalCurveNodes(rootNode)
      }

      const resultNode = blasingameNode || (logNodeId ? {
        nodeId: logNodeId,
        nodeTitle: 'Blasingame',
        nodeType: NODETYPE.NodeType_TypicalCurveBlasingame,
        wellName
      } : null)
      const nodeId = resultNode?.nodeId || resultNode?.id

      if (!nodeId) {
        lastError = new Error('\u6ca1\u6709\u627e\u5230 Blasingame \u5bf9\u5e94\u7684 nodeId')
        continue
      }

      const resultRes = await typicalCurveApi.getResult(PROJECT_ID, GAS_RESERVOIR_ID, nodeId)
      const result = normalizePayload(resultRes)

      const treeNode = addBlasingameNode(wellName, {
        ...resultNode,
        nodeType: NODETYPE.NodeType_TypicalCurveBlasingame,
        nodeTitle: 'Blasingame'
      })

      const viewNode = {
        id: nodeId,
        label: 'Blasingame',
        type: NODETYPE.NodeType_TypicalCurveBlasingame,
        wellName,
        raw: result,
        treeNode: resultNode
      }

      activeNodeId.value = viewNode.id
      currentView.value = 'blasingame'
      currentViewNode.value = viewNode
      activeNode.value = treeNode || viewNode
      ElMessage.success(`${wellName} Blasingame\u8ba1\u7b97\u5b8c\u6210`)
      return
    } catch (error) {
      lastError = error
    }
  }

  throw lastError || new Error(`${wellName} Blasingame\u7ed3\u679c\u5df2\u5b8c\u6210\uff0c\u4f46\u6682\u65f6\u6ca1\u6709\u62ff\u5230\u7ed3\u679c`)
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

const getAGNodeOnce = async (wellName, delayMs = 1200) => {
  if (delayMs > 0) {
    await new Promise(resolve => setTimeout(resolve, delayMs))
  }

  const res = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
  const rootNode = res?.data?.node
  const agNode = findAGNodeByWell(rootNode, wellName)
  return { rootNode, agNode }
}

const runWaterInvasionForSelectedWell = async () => { //点击水侵分析的操作
  const targetWellName = selectedWellName.value

  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }

  if (waterInvasionRunning.value) return

  waterInvasionRunning.value = true
  const logWaiter = createWaterInvasionLogWaiter(targetWellName)
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
    await logWaiter.promise
    const { rootNode, resultNode } = await getWaterInvasionNodeOnce(targetWellName, 0)

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
    logWaiter.cancel()
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
  // const allWellNames = getAllWellNames()

  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }

  if (materialBalanceRunning.value) return
  materialBalanceRunning.value = true
  try {
    await materialBalanceApi.calc({
      wellNames: [targetWellName],
      gasReservoirType: 1,
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      waterGasRatioLimit: 0.0602
    })

    ElMessage.info(`${targetWellName} 物质平衡计算中，请稍候...`)

    // 计算完成后读取后端保存的节点。
    const { rootNode, resultNode } =
        await getMaterialBalanceNodeOnce(targetWellName)

    if (!resultNode) {
      ElMessage.warning(`${targetWellName}井物质平衡结果不存在`)
      return
    }

// node 接口现在只包含曾经计算过物质平衡的井，
// 因此可以恢复全部历史节点。

    const materialBalanceRows = await getMaterialBalanceRowsForWell(targetWellName, { silentError: true })
    const materialBalanceRawNode = {
      ...resultNode,
      parentNode: rootNode,
      rootNode,
      nodeType: NODETYPE.NodeType_DynamicOriginalGasInplace,
      materialBalanceRows,
      nodeTitle: '物质平衡'
    }
    const treeNode = ensureMaterialBalanceNodeForWell(targetWellName, materialBalanceRawNode)
    if (!treeNode) {
      ElMessage.warning(`${targetWellName}\u4e95\u6ca1\u6709\u53ef\u5c55\u793a\u7684\u7269\u8d28\u5e73\u8861\u66f2\u7ebf`)
      /*
      ElMessage.warning(`${targetWellName}井没有可展示的物质平衡曲线`)
      */
      return
    }
    const viewNode = treeNode || {
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

const isFlowBalanceAverageRow = (row) => {
  const type = Number(row?.dynamicOriginalGasInplaceType ?? row?.dynamicOriginalGasInPlaceType)
  const description = String(row?.dynamicOriginalGasInplaceMethodDescription || row?.dynamicOriginalGasInPlaceMethodDescription || '')
  return type === 6 || description.includes('流动物质平衡-基于井底流压')
}

const getFlowBalanceRowsForWell = async (wellName, options = {}) => {
  if (!wellName) return []

  const pressureRes = await materialBalanceApi.getAverageFormationPressure(
      PROJECT_ID,
      GAS_RESERVOIR_ID,
      wellName,
      options
  )

  return getAverageFormationPressureRows(pressureRes.data)
      .filter(isFlowBalanceAverageRow)
}

const ensureFlowBalanceNodeForWell = (wellName, rawNode = {}) => {
  if (!wellName) return null
  if (Array.isArray(rawNode.flowBalanceRows) && !rawNode.flowBalanceRows.length) return null

  const wellItem = ensureWell(wellName, rawNode?.nodeId || rawNode?.wellId || `well-${wellName}`)
  addAnalysisNode(wellName, {
    ...rawNode,
    nodeType: FLOW_BALANCE_NODE_TYPE,
    nodeTitle: '流动平衡',
    wellName
  })

  const targetGroup = wellItem?.children.find(item => item.type === 'well-control-inventory')
  return targetGroup?.children.find(item =>
      item.type === FLOW_BALANCE_NODE_TYPE &&
      item.wellName === wellName
  )
}

const removeFlowBalanceNodeForWell = (wellName) => {
  if (!wellName) return

  const well = getWellGroup()?.children.find(item => item.wellName === wellName || item.label === wellName)
  const inventoryGroup = well?.children?.find(item => item.type === 'well-control-inventory')
  if (!inventoryGroup?.children?.length) return

  inventoryGroup.children = inventoryGroup.children.filter(item =>
      item.type !== FLOW_BALANCE_NODE_TYPE
  )
}

const refreshFlowBalanceNodes = async (targetWellName = '') => {
  const wells = targetWellName
      ? (getWellGroup()?.children ?? []).filter(well =>
          (well.wellName || well.label) === targetWellName
      )
      : getWellGroup()?.children ?? []

  await Promise.all(wells.map(async (well) => {
    const wellName = well.wellName || well.label
    if (!wellName) return

    try {
      const flowBalanceRows = await getFlowBalanceRowsForWell(wellName, { silentError: true })
      if (!flowBalanceRows.length) {
        removeFlowBalanceNodeForWell(wellName)
        return
      }

      const resultId = getMaterialBalanceAverageRowId(flowBalanceRows[0])
      ensureFlowBalanceNodeForWell(wellName, {
        ...flowBalanceRows[0],
        nodeId: resultId,
        resultId,
        analysisId: resultId,
        flowBalanceRows
      })
    } catch {
      // 没有已有流动平衡结果时保持项目树不变。
    }
  }))
}

const finalizeFlowBalanceResult = async (wellName, maxRetries = 8, intervalMs = 1000) => {
  let lastError = null

  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      await new Promise(resolve => setTimeout(resolve, attempt === 0 ? 800 : intervalMs))
      const flowBalanceRows = await getFlowBalanceRowsForWell(wellName, { silentError: true })
      if (!flowBalanceRows.length) {
        lastError = new Error(`${wellName}井没有可展示的流动平衡曲线`)
        continue
      }

      const resultId = getMaterialBalanceAverageRowId(flowBalanceRows[0])
      const flowBalanceRawNode = {
        ...flowBalanceRows[0],
        nodeId: resultId,
        resultId,
        analysisId: resultId,
        nodeType: FLOW_BALANCE_NODE_TYPE,
        flowBalanceRows,
        nodeTitle: '流动平衡'
      }
      const treeNode = ensureFlowBalanceNodeForWell(wellName, flowBalanceRawNode)
      if (!treeNode) {
        lastError = new Error(`${wellName}井没有可展示的流动平衡曲线`)
        continue
      }

      activeNodeId.value = treeNode.id
      activeNode.value = treeNode
      currentView.value = 'flow-balance'
      currentViewNode.value = treeNode
      ElMessage.success(`${wellName} 流动平衡计算完成`)
      return
    } catch (error) {
      lastError = error
    }
  }

  removeFlowBalanceNodeForWell(wellName)
  throw lastError || new Error(`${wellName} 流动平衡结果已完成，但暂时没有拿到结果`)
}

const runFlowBalanceForSelectedWell = async () => {
  const targetWellName = selectedWellName.value
  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }
  if (flowBalanceRunning.value) return

  flowBalanceRunning.value = true
  const logWaiter = createFlowBalanceLogWaiter(targetWellName)
  try {
    await materialBalanceApi.calcFMB({
      projectId: Number(PROJECT_ID),
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      wellNames: [targetWellName],
      pressurePosition: 2,
      unstableFlowPeriodLength: 180,
      minimumWaterGasRatio: 0.0602
    })

    ElMessage.info(`${targetWellName} 流动平衡计算中，请稍候...`)
    logWaiter.promise
        .then(() => finalizeFlowBalanceResult(targetWellName))
        .catch((error) => {
          ElMessage.error(error.message || '流动平衡计算失败')
          console.error('流动平衡计算失败', error)
        })
  } catch (error) {
    logWaiter.cancel()
    const message = error.response?.data?.msg || error.response?.data?.message || error.message
    ElMessage.error(message || '流动平衡计算失败')
    console.error('流动平衡计算失败', error)
  } finally {
    flowBalanceRunning.value = false
  }
}

const openDynamicBalanceNode = async (node) => {
  const targetWellName = node?.wellName || selectedWellName.value
  const resultId = node?.raw?.nodeId || node?.nodeId || node?.id

  if (!targetWellName || !resultId || String(resultId).startsWith('db-')) {
    ElMessage.error('没有找到该井的动态平衡结果 ID')
    return
  }

  currentView.value = 'dynamic-balance'
  currentViewNode.value = { ...node, loading: true }
  try {
    const resultRes = await dynamicBalanceApi.getResult(PROJECT_ID, GAS_RESERVOIR_ID, resultId)
    const resultPayload = resultRes.data?.result ? resultRes.data : (resultRes.data?.data ?? resultRes.data)
    activeNodeId.value = resultId
    currentViewNode.value = {
      ...node,
      id: resultId,
      label: '动态平衡',
      type: NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame,
      wellName: targetWellName,
      raw: resultPayload,
      resultId
    }
  } catch (error) {
    currentViewNode.value = { ...node, loading: false }
    ElMessage.error(error.response?.data?.msg || error.message || '动态平衡结果加载失败')
    console.error('动态平衡结果加载失败', error)
  }
}

const runDynamicBalanceForSelectedWell = async () => {
  const targetWellName = selectedWellName.value
  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }
  if (dynamicBalanceRunning.value) return

  dynamicBalanceRunning.value = true
  try {
    await dynamicBalanceApi.calc({
      gasReservoirId: Number(GAS_RESERVOIR_ID),
      projectId: Number(PROJECT_ID),
      wellNames: [targetWellName],
      unstableFlowPeriodLength: 180,
      minimumWaterGasRatio: 0.0602,
      dataSize: 300
    })

    ElMessage.info(`${targetWellName} 动态平衡计算中，请稍候...`)
    const { rootNode, resultNode } = await getDynamicBalanceNodeOnce(targetWellName)
    if (!resultNode?.nodeId) {
      throw new Error('动态平衡计算失败，未生成分析结果节点')
    }

    const treeNode = {
      id: resultNode.nodeId,
      label: '动态平衡',
      type: NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame,
      wellName: targetWellName,
      raw: resultNode
    }
    addAnalysisNode(targetWellName, {
      ...resultNode,
      nodeType: NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame,
      nodeTitle: '动态平衡',
      wellName: targetWellName
    })
    await openDynamicBalanceNode(treeNode)
    ElMessage.success(`${targetWellName} 动态平衡计算完成`)
  } catch (error) {
    const message = error.response?.data?.msg || error.response?.data?.message || error.message
    ElMessage.error(message || '动态平衡计算失败')
    console.error('动态平衡计算失败', error)
  } finally {
    dynamicBalanceRunning.value = false
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
  const logWaiter = createBlasingameLogWaiter(targetWellName)
  try {
    const fittingRes = await typicalCurveApi.fitting({
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

    const fittingMessage = getResponseMessage(fittingRes)
    if (isFailureResponse(fittingRes) && !fittingMessage.includes('\u5927\u4e8e0')) {
      throw new Error(fittingMessage || BLASINGAME_FITTING_REGRESSION_ERROR)
    }

    ElMessage.info(`${targetWellName} Blasingame计算中，请稍候...`)
    logWaiter.promise
        .then((logPayload) => finalizeBlasingameResult(targetWellName, logPayload))
        .catch((error) => {
          ElMessage.error(error.message || 'Blasingame计算失败')
          console.error('Blasingame计算失败', error)
        })
  } catch (error) {
    logWaiter.cancel()
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
      fittingType: 5,
      isSkipFitting: false,
      dataSize: 300,
      fineScanDataSize: 30,
      initScanDataSize: 10,
      minimumWaterGasRatio: 0.0602
    })
    ElMessage.info(`${targetWellName} Wattenbarger计算中，请稍候...`)
    let rootNode = null
    let resultNode = null
    const result = await getWattenbargerNodeOnce(targetWellName, 1500)
    rootNode = result.rootNode
    resultNode = result.wattenbargerNode
    //关掉接口轮询，一旦没有结果返回 直接报错
    // for (let i = 0; i < 20; i++) {
    //   const result = await getWattenbargerNodeOnce(targetWellName, 1500)
    //   rootNode = result.rootNode
    //   resultNode = result.wattenbargerNode
    //   if (resultNode) break
    // }
    if (!resultNode) {ElMessage.warning(`${targetWellName}井Wattenbarger结果不存在`)
      return
    }

    applyTypicalCurveNodes(rootNode)

    const treeNode = addWattenbargerNode(targetWellName, {
      ...resultNode,
      nodeType: NODETYPE.NodeType_TypicalCurveWattenbarger,
      nodeTitle: 'Wattenbarger'
    })
    const nodeId = resultNode.nodeId || resultNode.id
    const resultRes = await typicalCurveApi.getResult(PROJECT_ID, GAS_RESERVOIR_ID, nodeId)

    const viewNode = {
      id: nodeId,
      label: 'Wattenbarger',
      type: resultNode?.nodeType || resultNode?.type || NODETYPE.NodeType_TypicalCurveWattenbarger,
      wellName: targetWellName,
      raw: normalizePayload(resultRes),
      treeNode: resultNode
    }

    activeNodeId.value = viewNode.id
    currentView.value = 'wattenbarger'
    currentViewNode.value = viewNode
    activeNode.value = treeNode || viewNode
    ElMessage.success(`${targetWellName} Wattenbarger计算完成`)
  } catch (error) {
    ElMessage.error(error.message || 'Wattenbarger计算失败')
    console.error('Wattenbarger计算失败', error)
  } finally {
    typicalCurveRunning.value = false
  }
}

const runAGForSelectedWell = async () => {
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
      fittingType: 2,
      isSkipFitting: false,
      dataSize: 300,
      fineScanDataSize: 30,
      initScanDataSize: 10,
      minimumWaterGasRatio: 0.0602
    })

    ElMessage.info(`${targetWellName} AG计算中，请稍候...`)

    let rootNode = null
    let agNode = null
    for (let i = 0; i < 20; i++) {
      const result = await getAGNodeOnce(targetWellName, 1500)
      rootNode = result.rootNode
      agNode = result.agNode
      if (agNode) break
    }

    if (!agNode) {
      throw new Error(AG_FITTING_REGRESSION_ERROR)
    }

    applyTypicalCurveNodes(rootNode)

    const nodeId = agNode?.nodeId || agNode?.id
    addAGNode(targetWellName, {
      ...agNode,
      nodeType: NODETYPE.NodeType_TypicalCurveAG,
      nodeTitle: 'AG'
    })

    const resultRes = await typicalCurveApi.getResult(PROJECT_ID, GAS_RESERVOIR_ID, nodeId)
    const result = normalizePayload(resultRes)

    const viewNode = {
      id: nodeId || `ag-${targetWellName}`,
      label: 'AG',
      type: NODETYPE.NodeType_TypicalCurveAG,
      wellName: targetWellName,
      raw: result
    }

    activeNodeId.value = viewNode.id
    currentView.value = 'Agarwal-Gardner'
    currentViewNode.value = viewNode
    ElMessage.success(`${targetWellName} AG计算完成`)
  } catch (error) {
    ElMessage.error(error.message || 'AG计算失败')
    console.error('AG计算失败', error)
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

const openAGNode = async (node) => {
  const targetWellName = node?.wellName || selectedWellName.value
  if (!targetWellName) {
    ElMessage.warning('请先在左侧选择一口井')
    return
  }
  currentView.value = 'Agarwal-Gardner'
  currentViewNode.value = node
  try {
    const nodeRes = await nodeApi.getNode(PROJECT_ID, GAS_RESERVOIR_ID, NODETYPE.NodeType_ProductivityInstabilityAnalysis)
    const rootNode = nodeRes?.data?.node
    applyTypicalCurveNodes(rootNode)
    //applyTypicalCurveNodes(rootNode)

    const agNode = findAGNodeByWell(rootNode, targetWellName) || node?.raw || node
    const nodeId = agNode?.nodeId || agNode?.id

    if (!nodeId) {
      throw new Error('没有找到 AG 对应的 nodeId')
    }

    const resultRes = await typicalCurveApi.getResult(PROJECT_ID, GAS_RESERVOIR_ID, nodeId)
    const result = normalizePayload(resultRes)

    activeNodeId.value = nodeId
    currentViewNode.value = {
      ...node,
      id: nodeId,
      label: 'AG',
      type: NODETYPE.NodeType_TypicalCurveAG,
      wellName: targetWellName,
      raw: result,
      treeNode: agNode
    }
  } catch (error) {
    ElMessage.error(error.message || 'AG结果加载失败')
    console.error('AG结果加载失败', error)
  }
}

const initTree = async () => {
  await refreshProjectTree()
}

const loadWellChildren = async (node, force = false) => {
  const wellName = node?.wellName || (node?.type === NODETYPE.NodeType_Well ? node.label : '')
  if (!wellName || node?.type !== NODETYPE.NodeType_Well) return
  if (!force && node.childrenLoaded) return
  if (loadingWellChildren.has(wellName)) return

  loadingWellChildren.add(wellName)
  node.childrenLoading = true
  try {
    await Promise.all([
      refreshWaterInvasionNodes(wellName),
      refreshAnalyticMethodNodes(wellName),
      refreshMaterialBalanceNodes(wellName),
      refreshFlowBalanceNodes(wellName),
      refreshTypicalCurveNodes(wellName)
    ])
    node.childrenLoaded = true
  } finally {
    node.childrenLoading = false
    loadingWellChildren.delete(wellName)
  }
}

const closeTreeContextMenu = () => {
  treeContextMenu.value.visible = false
}

const handleNodeContextMenu = (node, event) => {
  if (!isTreeContextMenuNode(node)) {
    closeTreeContextMenu()
    return
  }

  const menuWidth = 240
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

const handleDeleteContextNode = async () => {
  const node = treeContextMenu.value.node
  const deleteLabel = treeContextMenuLabel.value
  closeTreeContextMenu()

  if (!node) return

  if (node.type === NODETYPE.NodeType_WaterInvasionAnalysis) {
    const wellName = node.wellName || selectedWellName.value
    if (!wellName) {
      ElMessage.error('没有找到水侵分析对应井名')
      return
    }

    try {
      await waterInvasionApi.deleteResult(PROJECT_ID, GAS_RESERVOIR_ID, wellName)
      removeTreeNode(node)
      clearCurrentViewAfterDelete(node)
      ElMessage.success(`${deleteLabel}成功`)
    } catch (error) {
      ElMessage.error(error.response?.data?.message || error.message || `${deleteLabel}失败`)
      console.error(`${deleteLabel}失败`, error)
    }
    return
  }

  if (node.type === NODETYPE.NodeType_DynamicOriginalGasInplace) {
    try {
      const deleteIds = await getMaterialBalanceDeleteIds(node)
      if (!deleteIds.length) {
        ElMessage.error('没有找到物质平衡结果 ID')
        return
      }

      await Promise.all(
          deleteIds.map(id =>
              materialBalanceApi.deleteResult(PROJECT_ID, GAS_RESERVOIR_ID, id, { silentError: true })
          )
      )
      removeTreeNode(node)
      clearCurrentViewAfterDelete(node)
      ElMessage.success(`${deleteLabel}成功`)
    } catch (error) {
      ElMessage.error(error.response?.data?.message || error.message || `${deleteLabel}失败`)
      console.error(`${deleteLabel}失败`, error)
    }
    return
  }

  if (node.type === NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame) {
    const resultId = getAnalysisId(node)
    if (!resultId || Number.isNaN(Number(resultId))) {
      ElMessage.error('没有找到动态平衡结果 ID')
      return
    }

    try {
      await materialBalanceApi.deleteResult(PROJECT_ID, GAS_RESERVOIR_ID, resultId)
      removeTreeNode(node)
      clearCurrentViewAfterDelete(node)
      ElMessage.success(`${deleteLabel}成功`)
    } catch (error) {
      ElMessage.error(error.response?.data?.message || error.message || `${deleteLabel}失败`)
      console.error(`${deleteLabel}失败`, error)
    }
    return
  }

  if (node.type === FLOW_BALANCE_NODE_TYPE) {
    const resultId = getAnalysisId(node)
    if (!resultId || Number.isNaN(Number(resultId))) {
      ElMessage.error('没有找到流动平衡结果 ID')
      return
    }

    try {
      await materialBalanceApi.deleteResult(PROJECT_ID, GAS_RESERVOIR_ID, resultId)
      removeTreeNode(node)
      clearCurrentViewAfterDelete(node)
      ElMessage.success(`${deleteLabel}成功`)
    } catch (error) {
      ElMessage.error(error.response?.data?.message || error.message || `${deleteLabel}失败`)
      console.error(`${deleteLabel}失败`, error)
    }
    return
  }

  if (!isTypicalCurveResultNode(node)) {
    ElMessage.info(`${deleteLabel}：删除接口待确认`)
    return
  }

  const analysisId = getAnalysisId(node)
  if (!analysisId || Number.isNaN(Number(analysisId))) {
    ElMessage.error(`没有找到${getTypicalCurveResultName(node)}结果 ID`)
    return
  }

  try {
    await typicalCurveApi.deleteResult(PROJECT_ID, GAS_RESERVOIR_ID, analysisId)
    removeTreeNode(node)
    clearCurrentViewAfterDelete(node)
    ElMessage.success(`${deleteLabel}成功`)
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || `${deleteLabel}失败`)
    console.error(`${deleteLabel}失败`, error)
  }
}

const handleSelect = async (node) => { // 点击左侧树节点
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

  if (node.type === FLOW_BALANCE_NODE_TYPE) {
    currentView.value = 'flow-balance'
    currentViewNode.value = node
    return
  }

  if (node.type === NODETYPE.NodeType_DynamicMaterialBalanceMethodBlasingame) {
    openDynamicBalanceNode(node)
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
  if (node.type === NODETYPE.NodeType_TypicalCurveAG) {
    openAGNode(node)
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
    case '流动平衡':
      runFlowBalanceForSelectedWell()
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
    case 'AG':
      runAGForSelectedWell()
      break
    case '动态平衡':
      runDynamicBalanceForSelectedWell()
      break
    default:
      ElMessage.success(`[${group}] ${name}`)
  }
}

const handleNodeExpand = (node) => {
  loadWellChildren(node)
}

const handleRefreshTree = () => {
  const wellName = currentViewNode.value?.wellName || selectedWellName.value
  const wellNode = getWellGroup()?.children.find(item => item.wellName === wellName || item.label === wellName)
  if (wellNode) {
    loadWellChildren(wellNode, true)
    return
  }

  refreshProjectTree()
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
        <button v-if="sideTreeCollapsed" class="side-collapsed-tab" type="button"
                title="&#x5C55;&#x5F00;&#x76EE;&#x5F55;" @click="toggleSideTree">
          &#x76EE;&#x5F55;
        </button>

        <div v-show="!sideTreeCollapsed" class="side-search">
          <el-input v-model="wellKeyword" size="small" clearable placeholder="&#x641C;&#x7D22;&#x4E95;&#x540D;" />
          <button class="side-toggle" type="button" title="&#x6536;&#x8D77;&#x76EE;&#x5F55;" @click="toggleSideTree">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
              <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
            </svg>
          </button>
        </div>

        <div v-show="!sideTreeCollapsed" class="side-tree">
          <TreeNode v-for="node in filteredTreeData" :key="node.id" :node="node" :active-id="activeNodeId"
                    @select="handleSelect" @expand="handleNodeExpand" @node-contextmenu="handleNodeContextMenu" />
        </div>
      </aside>

      <!--     右侧的主要内容区域-->
      <main class="content-area">
        <WaterInvasionContent v-if="currentView === 'water-invasion'" :node="currentViewNode" :project-id="PROJECT_ID"
                              :gas-reservoir-id="GAS_RESERVOIR_ID" @refresh-tree="handleRefreshTree" />
        <AnalyticMethodContent v-if="currentView === 'analytic-method'" :node="currentViewNode" :project-id="PROJECT_ID"
                               :gas-reservoir-id="GAS_RESERVOIR_ID" />
        <MaterialBalanceContent v-if="currentView === 'material-balance'" :node="currentViewNode"
                                 :project-id="PROJECT_ID" :gas-reservoir-id="GAS_RESERVOIR_ID" @refresh-tree="handleRefreshTree" />
        <FlowBalanceContent v-if="currentView === 'flow-balance'" :node="currentViewNode"
                            :project-id="PROJECT_ID" :gas-reservoir-id="GAS_RESERVOIR_ID" @refresh-tree="handleRefreshTree" />
        <BlasingameContent v-if="currentView === 'blasingame'" :node="currentViewNode" :project-id="PROJECT_ID"
                           :gas-reservoir-id="GAS_RESERVOIR_ID" />
        <NpiContent v-if="currentView === 'npi'" :node="currentViewNode" :project-id="PROJECT_ID"
                    :gas-reservoir-id="GAS_RESERVOIR_ID" />
        <WattenbargerContent v-if="currentView === 'wattenbarger'" :node="currentViewNode" :project-id="PROJECT_ID"
                             :gas-reservoir-id="GAS_RESERVOIR_ID" />
        <DynamicBalanceContent v-if="currentView === 'dynamic-balance'" :node="currentViewNode" :project-id="PROJECT_ID"
                               :gas-reservoir-id="GAS_RESERVOIR_ID" />
        <AGContent v-if="currentView === 'Agarwal-Gardner'" :node="currentViewNode" :project-id="PROJECT_ID"
                   :gas-reservoir-id="GAS_RESERVOIR_ID" />
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
  width: 230px;
  min-width: 230px;
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
