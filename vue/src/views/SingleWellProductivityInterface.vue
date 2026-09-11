<script setup>
/**
 * 顶部菜单栏板块对应关系：
 *
 * 本页面专门负责“解析融合”页签下的“单井产能”板块，包括：
 * 1. 产能试井
 * 2. 产能系数
 * 3. 理论计算
 * 4. 动态产能
 * 5. 产能对比
 *
 * 页面路由：/single-well-productivity
 * 该页面与 IprInterface.vue 相互独立；其他菜单板块仍返回 IprInterface.vue。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import RibbonMenu from '@/components/RibbonMenu.vue'
import WorkspaceSidebar from '@/components/WorkspaceSidebar.vue'
import { isLossRecord, lossRecordLabel, deleteLossRecord, removeSavedTreeRecord } from '@/utils/lossRecordActions'
import { isProductivityTestRecord, deleteProductivityTestRecord } from '@/utils/productivityTestRecordActions'
import BinomialPressureContent from '@/views/WellControlInventory/BinomialPressureContent.vue'
import ModifiedIsochronalContent from '@/views/SingleWellProductivity/ModifiedIsochronalContent.vue'
import ExponentialContent from '@/views/SingleWellProductivity/ExponentialContent.vue'
import ProductivityComparison from '@/views/SingleWellProductivity/ProductivityComparison.vue'
import DynamicProductivityContent from '@/views/SingleWellProductivity/DynamicProductivityContent.vue'
import DynamicUnstableProductivityContent from '@/views/SingleWellProductivity/DynamicUnstableProductivityContent.vue'
import TheoreticalProductivityContent from '@/views/SingleWellProductivity/TheoreticalProductivityContent.vue'
import TheoreticalUnstableProductivityContent from '@/views/SingleWellProductivity/TheoreticalUnstableProductivityContent.vue'
import { NODETYPE } from '@/constants/nodeType'
import {
  WORKSPACE_PROJECT_ID,
  WORKSPACE_GAS_RESERVOIR_ID,
  resolveWorkspaceContextId
} from '@/constants/workspaceContext'
import { wellApi } from '@/api/docker'
import { pvtStorageApi } from '@/api/pvtStorage'
import { isPvtRecord, deletePvtTreeRecord, deletedPvtRecord, matchesPvtScope } from '@/utils/pvtRecordActions'
import { selectDefaultPvtRecord } from '@/utils/pvtSelection'
import { productivityStorageApi } from '@/api/productivityStorage'
import { productivityTestsApi } from '@/api/productivityTests'
import { dynamicProductivityApi } from '@/api/dynamicProductivity'
import { theoreticalProductivityApi } from '@/api/theoreticalProductivity'
import {
  ISOCHRONAL_METHOD_NODE_TYPE,
  ISOCHRONAL_RECORD_NODE_TYPE,
  loadAllIsochronalTreeNodes,
  loadIsochronalTreeNodes
} from '@/utils/isochronalTree'
import {
  loadAllModifiedIsochronalTreeNodes,
  loadModifiedIsochronalTreeNodes
} from '@/utils/modifiedIsochronalTree'
import {
  loadAllOwnedProductivityTestTreeNodes,
  loadOwnedProductivityTestTreeNodes,
  OWNED_PRODUCTIVITY_METHOD_NODE_TYPES,
  OWNED_PRODUCTIVITY_METHOD_NODE_TYPE,
  OWNED_PRODUCTIVITY_RECORD_NODE_TYPE
} from '@/utils/ownedProductivityTestTree'
import {
  DYNAMIC_PRODUCTIVITY_NODE_TYPE,
  DYNAMIC_STABLE_METHOD_NODE_TYPE,
  DYNAMIC_STABLE_RECORD_NODE_TYPE,
  DYNAMIC_UNSTABLE_METHOD_NODE_TYPE,
  DYNAMIC_UNSTABLE_RECORD_NODE_TYPE,
  loadAllDynamicStableTreeNodes,
  loadDynamicStableTreeNodes,
  loadDynamicUnstableTreeNodes
} from '@/utils/dynamicStableTree'
import {
  THEORETICAL_CALCULATION_NODE_TYPE,
  THEORETICAL_STABLE_METHOD_NODE_TYPE,
  THEORETICAL_STABLE_RECORD_NODE_TYPE,
  THEORETICAL_UNSTABLE_METHOD_NODE_TYPE,
  THEORETICAL_UNSTABLE_RECORD_NODE_TYPE,
  loadAllTheoreticalStableTreeNodes,
  loadTheoreticalStableTreeNodes,
  loadTheoreticalUnstableTreeNodes
} from '@/utils/theoreticalStableTree'
import {
  workspaceActiveNodeId,
  workspacePendingCommand,
  workspacePendingNode,
  workspaceSelectedWellName,
  resolveWorkspaceTargetWellName,
  workspaceTreeCollapsed,
  workspaceTreeData,
  workspaceTreeKeyword,
  workspaceRibbonScope,
  ensureWorkspaceReservoir,
  setWorkspaceRibbonScope,
  selectWorkspaceNodeScope,
  getReservoirCommandLocation
} from '@/utils/workspaceTreeState'

const props = defineProps({
  embedded: { type: Boolean, default: false },
  embeddedNode: { type: Object, default: null },
  projectId: { type: Number, default: null },
  gasReservoirId: { type: Number, default: null }
})

const route = useRoute()
const router = useRouter()
const PROJECT_ID = resolveWorkspaceContextId(
  props.projectId,
  props.embeddedNode?.projectId,
  route.query.projectId,
  WORKSPACE_PROJECT_ID
)
const GAS_RESERVOIR_ID = resolveWorkspaceContextId(
  props.gasReservoirId,
  props.embeddedNode?.gasReservoirId,
  route.query.gasReservoirId,
  WORKSPACE_GAS_RESERVOIR_ID
)
const MODIFIED_ISOCHRONAL_PROJECT_ID = PROJECT_ID
const MODIFIED_ISOCHRONAL_GAS_RESERVOIR_ID = GAS_RESERVOIR_ID
ensureWorkspaceReservoir({ projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID })
if (!props.embedded) setWorkspaceRibbonScope('well')

const MODULES = [
  { name: '产能试井', methods: ['回压试井', '等时试井', '修正等时', '一点法'] },
  { name: '产能系数', methods: ['二项式', '指数式'] },
  { name: '理论计算', methods: ['稳定流', '不稳定流'] },
  { name: '动态产能', methods: ['稳定流', '不稳定流'] },
  { name: '产能对比', methods: ['多周期', '多方法', '注采对比'] }
]

const WORKSPACE_GROUPS = [
  { id: 'data-management', label: '数据管理' },
  { id: 'well-control-inventory', label: '井控库存' },
  { id: 'single-well-productivity', label: '单井产能' },
  { id: 'wellbore-capacity', label: '井筒能力' },
  { id: 'pipeline-capacity', label: '管束能力' },
  { id: 'production-allocation', label: '配产配注' }
]

// 产能试井结果接入前使用空白数据行，表格结构与水侵分析的数据列表一致。
const resultGridColumns = Array.from({ length: 10 }, (_, index) => ({
  prop: `column${index + 1}`,
  minWidth: 112
}))
const resultGridRows = Array.from({ length: 24 }, (_, rowIndex) => ({
  id: `result-row-${rowIndex + 1}`,
  ...Object.fromEntries(resultGridColumns.map(column => [column.prop, '']))
}))

const wells = ref([])
const loadingWells = ref(false)
const keyword = workspaceTreeKeyword
const selectedWellName = workspaceSelectedWellName
// 左侧目录当前指向的井，仅供下一次顶部命令确定目标井。
// 它与 selectedWellName（右侧当前展示的井）分开，避免点击井/目录就刷新右侧。
const sidebarTargetWellName = ref(selectedWellName.value)
if (props.embeddedNode?.wellName) selectedWellName.value = String(props.embeddedNode.wellName)
else if (route.query.well) selectedWellName.value = String(route.query.well)
sidebarTargetWellName.value = selectedWellName.value
const sideTreeCollapsed = workspaceTreeCollapsed
// 展开后适当加宽以容纳三种计算方式；收起形态对齐 PVT 的“图表数据”侧栏。
const paramsCollapsed = ref(false)
const routeModule = props.embeddedNode ? '产能试井' : String(route.query.module || '')
const routeConfig = MODULES.find(item => item.name === routeModule)
const routeMethod = props.embeddedNode ? '等时试井' : String(route.query.method || '')
const activeModule = ref(routeConfig?.name || '')
const activeMethod = ref(routeConfig?.methods.includes(routeMethod) ? routeMethod : '')
const routeTestId = Number(props.embeddedNode?.testId ?? route.query.testId)
const routeEvaluationId = Number(props.embeddedNode?.evaluationId ?? route.query.evaluationId)
const routeStableId = Number(props.embeddedNode?.stableId ?? route.query.stableId)
const activeProductivityTestId = ref(Number.isFinite(routeTestId) && routeTestId > 0 ? routeTestId : null)
const activeEvaluationId = ref(Number.isFinite(routeEvaluationId) && routeEvaluationId > 0 ? routeEvaluationId : null)
const activeStableId = ref(Number.isFinite(routeStableId) && routeStableId > 0 ? routeStableId : null)
const autoCalculateStable = ref(route.query.initialCalc === '1')
const stableContextMenu = ref({ visible: false, x: 0, y: 0, node: null })
const selectedPvtTable = ref('')
const selectedDataTable = ref('')
const maximumFormationPressure = ref('56.34')
const formationTemperature = ref('120')
const onePointAlpha = ref('0.25')
// 产能系数
const productivityCoefficientC = ref('')
const productivityExponentN = ref('')
const correctedCoefficientC = ref('')
const correctedExponentN = ref('')
const fittedFormationPressure = ref('')
const fittedFlowRate = ref('')
const exponentialCalculationMethod = ref('拟压力')
const openFlowRate = ref('')

const calculationMethod = ref('拟压力')
const calculationResult = ref('二项式')
const operationType = ref('production')
const activeContentTab = ref('chart')
const pressureContentRef = ref(null)
const dataFileInput = ref(null)
const importingData = ref(false)
const importedDataFileName = ref('当前井产能测试数据')
const databasePvtRecords = ref([])
const selectedPvtDetail = ref(null)
const pressureWorkspaceKey = ref(0)
const calculationOutput = ref(null)
const savingProductivityTest = ref(false)
const storedProductivityTest = ref(null)
const savingResult = ref(false)
const resultDirty = ref(false)
const savedInputSignature = ref('')

const scientific = value => {
  const number = Number(value)
  if (!Number.isFinite(number)) return ''
  return number === 0 ? '0.0000' : number.toExponential(4).replace('e', 'E')
}

const activeModuleConfig = computed(
  () => MODULES.find(item => item.name === activeModule.value) || MODULES[0]
)

const sidebarTreeData = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return workspaceTreeData.value

  return workspaceTreeData.value.map(node => node.id === 'g-well'
    ? {
      ...node,
      children: (node.children || []).filter(well =>
        String(well.wellName || well.label || '').toLowerCase().includes(value)
      )
    }
    : node
  )
})

const pvtTableOptions = computed(() => databasePvtRecords.value.map(record => ({
  value: String(record.pvtId),
  label: record.pvtName || `PVT性质${record.pvtNo}`
})))

const selectedPvtOption = computed(() => databasePvtRecords.value.find(
  record => String(record.pvtId) === String(selectedPvtTable.value)
) || null)

const selectedPvtRecord = computed(() => selectedPvtDetail.value)

watch(selectedPvtRecord, record => {
  const firstResult = record?.gasResultRows?.[0]
  const pvtTemperature = Number(
    record?.gasSettings?.temperature ??
    record?.gasSettings?.formationTemperature ??
    record?.gasSettings?.reservoirTemperature ??
    (Array.isArray(firstResult) ? firstResult[1] : firstResult?.temperature)
  )
  if (Number.isFinite(pvtTemperature)) formationTemperature.value = String(pvtTemperature)
}, { immediate: true })

watch([calculationMethod, calculationResult], () => {
  if (!isOwnedPressureMethod.value) return
  calculationOutput.value = null
  resultDirty.value = false
})

watch(operationType, value => {
  if (!isOwnedPressureMethod.value) return
  // 注采方向变化后，旧方向的结果不能继续显示或保存。
  calculationOutput.value = null
  resultDirty.value = false
  const storedOperationType = storedProductivityTest.value?.operationType
  if (!activeProductivityTestId.value || !storedOperationType || storedOperationType === value) return
  activeProductivityTestId.value = null
  activeEvaluationId.value = null
  storedProductivityTest.value = null
  savedInputSignature.value = ''
  pressureWorkspaceKey.value += 1
})

const unwrapData = response => response?.data ?? response ?? {}
const parseSettings = value => {
  if (!value) return {}
  if (typeof value === 'object') return value
  try { return JSON.parse(value) } catch { return {} }
}

const normalizePvtDetail = detail => {
  const gasInput = detail?.gasInput || {}
  const gasSettings = { ...gasInput, ...parseSettings(detail?.settings?.gas) }
  return {
    pvtId: detail?.record?.pvtId,
    index: detail?.record?.pvtNo,
    pvtNo: detail?.record?.pvtNo,
    pvtName: detail?.record?.pvtName,
    gasRows: [[gasInput.gasType, gasInput.specificGravity, gasInput.hydrogenSulfide,
    gasInput.carbonDioxide, gasInput.nitrogen, gasInput.condensateOilDensity]],
    gasInput,
    gasSettings,
    gasResultRows: detail?.gasResults || []
  }
}

const pvtOptionsLoading = ref(false)
let pvtListRequest = 0
let pvtDetailRequest = 0
const loadSelectedPvtDetail = async () => {
  const requestId = ++pvtDetailRequest
  const wellName = selectedWellName.value
  const pvtId = selectedPvtTable.value
  selectedPvtDetail.value = null
  if (!pvtId || !wellName) return
  try {
    const detail = unwrapData(await pvtStorageApi.getDetail(
      pvtId, PROJECT_ID, GAS_RESERVOIR_ID, wellName
    ))
    // 快速切井/切表时，只接受最后一次选择对应的明细，避免旧请求覆盖新表。
    if (requestId !== pvtDetailRequest || wellName !== selectedWellName.value || pvtId !== selectedPvtTable.value) return
    selectedPvtDetail.value = normalizePvtDetail(detail)
  } catch (error) {
    if (requestId !== pvtDetailRequest || wellName !== selectedWellName.value || pvtId !== selectedPvtTable.value) return
    ElMessage.warning(error?.msg || error?.message || 'PVT性质明细读取失败')
  }
}

const loadPvtOptions = async (preferredPvtId = null) => {
  const requestId = ++pvtListRequest
  ++pvtDetailRequest
  const wellName = selectedWellName.value
  const preferredId = preferredPvtId ?? selectedPvtTable.value
  pvtOptionsLoading.value = false
  databasePvtRecords.value = []
  selectedPvtDetail.value = null
  selectedPvtTable.value = ''
  if (!wellName) return
  pvtOptionsLoading.value = true
  try {
    const records = unwrapData(await pvtStorageApi.list(
      PROJECT_ID, GAS_RESERVOIR_ID, wellName
    )) || []
    if (requestId !== pvtListRequest || wellName !== selectedWellName.value) return
    // 列表直接来自当前井的数据库记录，与左侧目录是否展开无关，不限制 PVT 编号。
    const usableRecords = Array.isArray(records) ? records : []
    databasePvtRecords.value = usableRecords
    const match = selectDefaultPvtRecord(usableRecords, preferredId)
    selectedPvtTable.value = String(match?.pvtId || '')
    await loadSelectedPvtDetail()
  } catch (error) {
    if (requestId !== pvtListRequest || wellName !== selectedWellName.value) return
    selectedPvtTable.value = ''
    console.warn('当前井PVT性质读取失败', error)
    ElMessage.warning('当前井PVT列表读取失败，请重新进入功能重试')
  } finally {
    if (requestId === pvtListRequest) pvtOptionsLoading.value = false
  }
}

const changeSelectedPvt = async () => {
  // 更换参数来源后旧结果不能继续保存；保留当前表格输入，等待用户重新计算。
  calculationOutput.value = null
  resultDirty.value = false
  await loadSelectedPvtDetail()
}

watch(deletedPvtRecord, deleted => {
  if (!matchesPvtScope(deleted, { projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID, wellName: selectedWellName.value })) return
  ++pvtListRequest
  pvtOptionsLoading.value = false
  databasePvtRecords.value = databasePvtRecords.value.filter(item => Number(item.pvtId) !== Number(deleted.pvtId))
  if (Number(selectedPvtTable.value) === Number(deleted.pvtId)) {
    selectedPvtTable.value = ''
    changeSelectedPvt()
  }
})

const chooseDataFile = () => dataFileInput.value?.click()
const handleDataFile = async event => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  importingData.value = true
  try {
    const result = unwrapData(await productivityTestsApi.importFile(file))
    const rows = result.rows || []
    selectedDataTable.value = 'local-import'
    await nextTick()
    pressureContentRef.value?.replaceInputRows?.(rows)
    importedDataFileName.value = `${file.name} · ${rows.length} 行`
    ElMessage.success(`已导入 ${rows.length} 条产能测试数据`)
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '本地文件导入失败')
  } finally {
    importingData.value = false
  }
}

const tabTitle = computed(() => {
  if (activeMethod.value) {
    return `${activeModule.value}-${activeMethod.value}`
  }
  return activeModule.value
})
const pressureTestType = computed(() => ({
  '回压试井': 'back-pressure',
  '等时试井': 'isochronal',
  '修正等时': 'modified-isochronal',
  '一点法': 'one-point'
}[activeMethod.value] || 'back-pressure'))
const pressureCalculationMethod = computed(() => ({
  '拟压力': 'pseudo-pressure',
  '压力平方方法': 'pressure-squared',
  '压力法': 'pressure'
}[calculationMethod.value] || 'pressure'))
const usesPressureCalculation = computed(() =>
  activeModule.value === '产能试井' && Boolean(activeMethod.value)
)
const isOwnedPressureMethod = computed(() =>
  activeModule.value === '产能试井' &&
  ['back-pressure', 'one-point'].includes(pressureTestType.value)
)

const normalizeWells = payload => {
  const source = payload?.data?.wells ?? payload?.wells ?? []
  return source
    .map((well, index) => ({
      id: well.id ?? well.wellId ?? `${well.wellName || 'well'}-${index}`,
      wellName: String(well.wellName ?? well.name ?? well.nodeTitle ?? '').trim()
    }))
    .filter(well => well.wellName)
}

const selectModule = (moduleName, method = '') => {
  const config = MODULES.find(item => item.name === moduleName)
  if (!config) return
  activeModule.value = config.name
  activeMethod.value = config.methods.includes(method) ? method : config.methods[0]
  activeContentTab.value = 'chart'
  if (['理论计算', '动态产能'].includes(activeModule.value)) activeStableId.value = null
  if (isOwnedPressureMethod.value) {
    storedProductivityTest.value = null
    activeProductivityTestId.value = null
    calculationOutput.value = null
    resultDirty.value = false
    savedInputSignature.value = ''
    selectedDataTable.value = pressureTestType.value
    pressureWorkspaceKey.value += 1
  }
  router.replace({
    name: 'SingleWellProductivity',
    query: {
      module: activeModule.value,
      method: activeMethod.value,
      well: selectedWellName.value,
      projectId: PROJECT_ID,
      gasReservoirId: GAS_RESERVOIR_ID,
      ...(autoCalculateStable.value ? { initialCalc: '1' } : {})
    }
  })
}

const loadModifiedIsochronalNodes = async (wellName, { expand = false } = {}) => {
  await loadModifiedIsochronalTreeNodes({
    treeData: workspaceTreeData.value,
    projectId: MODIFIED_ISOCHRONAL_PROJECT_ID,
    gasReservoirId: MODIFIED_ISOCHRONAL_GAS_RESERVOIR_ID,
    wellName,
    expand
  })
}

const loadAllModifiedIsochronalNodes = async () => {
  await loadAllModifiedIsochronalTreeNodes({
    treeData: workspaceTreeData.value,
    projectId: MODIFIED_ISOCHRONAL_PROJECT_ID,
    gasReservoirId: MODIFIED_ISOCHRONAL_GAS_RESERVOIR_ID
  })
}

const loadIsochronalNodes = async (wellName, { expand = false } = {}) => loadIsochronalTreeNodes({
  treeData: workspaceTreeData.value,
  projectId: PROJECT_ID,
  gasReservoirId: GAS_RESERVOIR_ID,
  wellName,
  expand
})

const loadAllIsochronalNodes = async () => loadAllIsochronalTreeNodes({
  treeData: workspaceTreeData.value,
  projectId: PROJECT_ID,
  gasReservoirId: GAS_RESERVOIR_ID
})

const loadOwnedProductivityNodes = async (wellName, { expand = false, testMethod = null } = {}) =>
  loadOwnedProductivityTestTreeNodes({
    treeData: workspaceTreeData.value,
    projectId: PROJECT_ID,
    gasReservoirId: GAS_RESERVOIR_ID,
    wellName,
    expand,
    testMethod
  })

const loadAllOwnedProductivityNodes = async () => loadAllOwnedProductivityTestTreeNodes({
  treeData: workspaceTreeData.value,
  projectId: PROJECT_ID,
  gasReservoirId: GAS_RESERVOIR_ID
})

const loadDynamicStableNodes = async (wellName, { expand = false } = {}) =>
  loadDynamicStableTreeNodes({
    treeData: workspaceTreeData.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID,
    wellName, expand, force: expand
  })

const loadDynamicUnstableNodes = async (wellName, { expand = false } = {}) =>
  loadDynamicUnstableTreeNodes({
    treeData: workspaceTreeData.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID,
    wellName, expand, force: expand
  })

const loadTheoreticalStableNodes = async (wellName, { expand = false } = {}) =>
  loadTheoreticalStableTreeNodes({
    treeData: workspaceTreeData.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID,
    wellName, expand, force: expand
  })

const loadTheoreticalUnstableNodes = async (wellName, { expand = false } = {}) =>
  loadTheoreticalUnstableTreeNodes({
    treeData: workspaceTreeData.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID,
    wellName, expand, force: expand
  })

const loadAllStableNodes = async () => {
  // 两个工具都会调整“单井产能”的 children，按固定顺序执行可避免并发覆盖节点。
  await loadAllTheoreticalStableTreeNodes({
    treeData: workspaceTreeData.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID
  })
  await loadAllDynamicStableTreeNodes({
    treeData: workspaceTreeData.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID
  })
}

const isStableRecordNode = node => [
  THEORETICAL_STABLE_RECORD_NODE_TYPE,
  THEORETICAL_UNSTABLE_RECORD_NODE_TYPE,
  DYNAMIC_STABLE_RECORD_NODE_TYPE,
  DYNAMIC_UNSTABLE_RECORD_NODE_TYPE
].includes(node?.type)

const closeStableContextMenu = () => { stableContextMenu.value.visible = false }
const handleStableContextMenu = (node, event) => {
  if (!isStableRecordNode(node) && !isLossRecord(node) && !isPvtRecord(node) && !isProductivityTestRecord(node)) return closeStableContextMenu()
  stableContextMenu.value = {
    visible: true,
    x: Math.max(8, Math.min(event.clientX, window.innerWidth - 190)),
    y: Math.max(8, Math.min(event.clientY, window.innerHeight - 86)),
    node
  }
}

const reloadStableBranch = async (node, expand = true) => {
  if (node.type === THEORETICAL_STABLE_RECORD_NODE_TYPE) {
    return loadTheoreticalStableNodes(node.wellName, { expand })
  }
  if (node.type === THEORETICAL_UNSTABLE_RECORD_NODE_TYPE) {
    return loadTheoreticalUnstableNodes(node.wellName, { expand })
  }
  if (node.type === DYNAMIC_UNSTABLE_RECORD_NODE_TYPE) {
    return loadDynamicUnstableNodes(node.wellName, { expand })
  }
  return loadDynamicStableNodes(node.wellName, { expand })
}

/** 展开哪个计算方法，就只加载该方法在当前井下的历史记录。 */
const handleSidebarExpand = async node => {
  try {
    if (node.type === THEORETICAL_CALCULATION_NODE_TYPE) {
      await Promise.all([
        loadTheoreticalStableNodes(node.wellName),
        loadTheoreticalUnstableNodes(node.wellName)
      ])
    } else if (node.type === DYNAMIC_PRODUCTIVITY_NODE_TYPE) {
      await Promise.all([
        loadDynamicStableNodes(node.wellName),
        loadDynamicUnstableNodes(node.wellName)
      ])
    } else if (node.type === ISOCHRONAL_METHOD_NODE_TYPE) {
      await loadIsochronalNodes(node.wellName)
    } else if (node.type === 'productivity-test-modified-isochronal-method') {
      await loadModifiedIsochronalNodes(node.wellName)
    } else if (node.type === OWNED_PRODUCTIVITY_METHOD_NODE_TYPE || OWNED_PRODUCTIVITY_METHOD_NODE_TYPES.has(node.type)) {
      await loadOwnedProductivityNodes(node.wellName, { testMethod: node.testMethod })
    } else if (node.type === THEORETICAL_STABLE_METHOD_NODE_TYPE) {
      await loadTheoreticalStableNodes(node.wellName)
    } else if (node.type === THEORETICAL_UNSTABLE_METHOD_NODE_TYPE) {
      await loadTheoreticalUnstableNodes(node.wellName)
    } else if (node.type === DYNAMIC_STABLE_METHOD_NODE_TYPE) {
      await loadDynamicStableNodes(node.wellName)
    } else if (node.type === DYNAMIC_UNSTABLE_METHOD_NODE_TYPE) {
      await loadDynamicUnstableNodes(node.wellName)
    }
  } catch (error) {
    ElMessage.warning(error?.response?.data?.msg || error?.message || '目录记录加载失败')
  }
}

const renameStableNode = async () => {
  const node = stableContextMenu.value.node
  closeStableContextMenu()
  if (!isStableRecordNode(node)) return
  try {
    const regimeLabel = [DYNAMIC_UNSTABLE_RECORD_NODE_TYPE, THEORETICAL_UNSTABLE_RECORD_NODE_TYPE].includes(node.type) ? '不稳定流' : '稳定流'
    const { value } = await ElMessageBox.prompt(`请输入新的${regimeLabel}名称`, '重命名', {
      inputValue: node.label,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
      confirmButtonText: '确定', cancelButtonText: '取消'
    })
    const identity = { projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID, wellName: node.wellName }
    if ([DYNAMIC_UNSTABLE_RECORD_NODE_TYPE, THEORETICAL_UNSTABLE_RECORD_NODE_TYPE].includes(node.type)) {
      const api = node.type === THEORETICAL_UNSTABLE_RECORD_NODE_TYPE
        ? theoreticalProductivityApi : dynamicProductivityApi
      await api.renameUnstable(node.unstableId, {
        ...identity, unstableName: String(value).trim()
      })
    } else {
      const api = node.type === THEORETICAL_STABLE_RECORD_NODE_TYPE
        ? theoreticalProductivityApi : dynamicProductivityApi
      await api.renameStable(node.stableId, { ...identity, stableName: String(value).trim() })
    }
    await reloadStableBranch(node)
    ElMessage.success('重命名成功')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.response?.data?.message || error.message || '重命名失败')
    }
  }
}

const deleteStableNode = async () => {
  const node = stableContextMenu.value.node
  closeStableContextMenu()
  if (isProductivityTestRecord(node)) {
    try {
      await ElMessageBox.confirm(`删除“${node.label}”及其输入、二项式/指数式计算结果和IPR曲线？此操作不可撤销。`, '删除产能试井记录', {
        type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
      })
      await deleteProductivityTestRecord(node)
      removeSavedTreeRecord(workspaceTreeData.value, node)
      if (String(workspaceActiveNodeId.value) === String(node.id)) workspaceActiveNodeId.value = ''
      if (activeModule.value === '产能试井' && Number(activeProductivityTestId.value) === Number(node.testId)
          && selectedWellName.value === node.wellName && Number(PROJECT_ID) === Number(node.projectId)
          && Number(GAS_RESERVOIR_ID) === Number(node.gasReservoirId)) {
        activeProductivityTestId.value = null
        activeEvaluationId.value = null
        storedProductivityTest.value = null
        calculationOutput.value = null
        resultDirty.value = false
        savedInputSignature.value = ''
        pressureWorkspaceKey.value += 1
        activeMethod.value = ''
        const query = { ...route.query }
        delete query.testId
        delete query.evaluationId
        delete query.method
        await router.replace({ query })
      }
      ElMessage.success(`${node.label}已删除`)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') ElMessage.error(error?.response?.data?.msg || error.message || '删除产能试井记录失败')
    }
    return
  }
  if (isPvtRecord(node)) {
    try {
      await ElMessageBox.confirm(`删除“${node.label}”及其天然气、地层水、岩石性质的输入和计算结果？此操作不可撤销。`, '删除PVT记录', {
        type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
      })
      const { removedIds } = await deletePvtTreeRecord(node, workspaceTreeData.value, { projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID })
      if (removedIds.includes(String(workspaceActiveNodeId.value))) workspaceActiveNodeId.value = ''
      ElMessage.success('PVT记录已删除')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') ElMessage.error(error?.msg || error.response?.data?.msg || error.message || '删除PVT记录失败')
    }
    return
  }
  if (isLossRecord(node)) {
    try {
      await ElMessageBox.confirm(`删除“${node.label}”及其输入、计算结果和相关明细？此操作不可撤销。`, '删除记录', {
        type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
      })
      await deleteLossRecord(node)
      removeSavedTreeRecord(workspaceTreeData.value, node)
      if (String(workspaceActiveNodeId.value) === String(node.id)) workspaceActiveNodeId.value = ''
      ElMessage.success(`${lossRecordLabel(node)}记录已删除`)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.msg || error.message || '删除损耗记录失败')
    }
    return
  }
  if (!isStableRecordNode(node)) return
  try {
    const regimeLabel = [DYNAMIC_UNSTABLE_RECORD_NODE_TYPE, THEORETICAL_UNSTABLE_RECORD_NODE_TYPE].includes(node.type) ? '不稳定流' : '稳定流'
    await ElMessageBox.confirm(
      `删除后将同时删除“${node.label}”的输入、三种输出和IPR曲线，此操作不可撤销，是否继续？`,
      `删除${regimeLabel}`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    const projectId = node.projectId ?? PROJECT_ID
    const gasReservoirId = node.gasReservoirId ?? GAS_RESERVOIR_ID
    if (!node.wellName || !(Number(node.unstableId ?? node.stableId) > 0)) throw new Error('记录归属信息不完整，无法删除')
    if ([DYNAMIC_UNSTABLE_RECORD_NODE_TYPE, THEORETICAL_UNSTABLE_RECORD_NODE_TYPE].includes(node.type)) {
      const api = node.type === THEORETICAL_UNSTABLE_RECORD_NODE_TYPE
        ? theoreticalProductivityApi : dynamicProductivityApi
      await api.deleteUnstable(node.unstableId, projectId, gasReservoirId, node.wellName)
    } else {
      const api = node.type === THEORETICAL_STABLE_RECORD_NODE_TYPE
        ? theoreticalProductivityApi : dynamicProductivityApi
      await api.deleteStable(node.stableId, projectId, gasReservoirId, node.wellName)
    }
    removeSavedTreeRecord(workspaceTreeData.value, node)
    if (String(workspaceActiveNodeId.value) === String(node.id)) workspaceActiveNodeId.value = ''
    const deletedModule = [THEORETICAL_STABLE_RECORD_NODE_TYPE, THEORETICAL_UNSTABLE_RECORD_NODE_TYPE].includes(node.type) ? '理论计算' : '动态产能'
    // 不同井、稳定/不稳定流及理论/动态记录可能拥有相同数字 ID，不能只比较 ID。
    if (activeModule.value === deletedModule && activeMethod.value === regimeLabel && selectedWellName.value === node.wellName
        && Number(projectId) === Number(PROJECT_ID) && Number(gasReservoirId) === Number(GAS_RESERVOIR_ID)
        && Number(activeStableId.value) === Number(node.unstableId ?? node.stableId)) {
      autoCalculateStable.value = false
      activeStableId.value = null
      await router.replace({
        name: 'SingleWellProductivity',
        query: { module: activeModule.value, method: activeMethod.value, well: selectedWellName.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID }
      })
    }
    ElMessage.success(`${node.label}已删除`)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.response?.data?.message || error.message || '稳定流删除失败')
    }
  }
}

const handleCommand = async ({ group, name, parent }) => {
  if (workspaceRibbonScope.value === 'reservoir') {
    const location = getReservoirCommandLocation({ group, name, parent })
    if (location) await router.push(location)
    else ElMessage.info('此公共功能暂未接入库工作区')
    return
  }
  // 顶部菜单栏“单井产能”板块：留在当前独立页面并切换功能模块/计算方法。
  if (group === '单井产能') {
    const targetWellName = sidebarTargetWellName.value || selectedWellName.value
    if (!targetWellName) {
      ElMessage.warning('请先在左侧选择一口井')
      return
    }
    // 点击目录本身不切换右侧；用户明确点击顶部功能后，才正式切换到目标井。
    if (targetWellName !== selectedWellName.value) await selectWell(targetWellName)
    else await loadPvtOptions(selectedPvtTable.value)
    if (parent === '产能试井' && name === '修正等时') {
      activeProductivityTestId.value = null
      activeEvaluationId.value = null
    }
    if (parent === '产能试井' && name === '等时试井') {
      activeProductivityTestId.value = null
      calculationOutput.value = null
      pressureWorkspaceKey.value += 1
      selectedDataTable.value = 'local-import'
      importedDataFileName.value = '当前井产能测试数据'
    }
    // 理论计算和动态产能的稳定流/不稳定流都在顶部点击后立即计算；
    // 左侧历史记录仍只恢复快照，不携带该标记。
    autoCalculateStable.value = ['理论计算', '动态产能'].includes(parent || name)
      && ['稳定流', '不稳定流'].includes(name)
    selectModule(parent || name, parent ? name : '')
    return
  }

  // 将本次点击一并交给 IPR 工作台，避免用户切换后还要再点第二次。
  // 跨工作台的命令携带左侧目标井，不依赖先打开该井的 PVT/其他记录。
  workspacePendingCommand.value = { group, name, parent,
    wellName: resolveWorkspaceTargetWellName(sidebarTargetWellName.value || selectedWellName.value) }
  await router.push({ name: 'IprInterface' })
}

const loadWells = async () => {
  const existingWellNodes = workspaceTreeData.value.find(node => node.id === 'g-well')?.children || []
  if (existingWellNodes.length) {
    wells.value = existingWellNodes.map((well, index) => ({
      id: well.id ?? well.nodeId ?? `${well.wellName || well.label || 'well'}-${index}`,
      wellName: String(well.wellName || well.label || '').trim()
    })).filter(well => well.wellName)
    return
  }

  loadingWells.value = true
  try {
    const response = await wellApi.getWells(PROJECT_ID, GAS_RESERVOIR_ID)
    wells.value = normalizeWells(response)
    if (selectedWellName.value && !wells.value.some(well => well.wellName === selectedWellName.value)) {
      selectedWellName.value = ''
    }

    const wellGroup = workspaceTreeData.value.find(node => node.id === 'g-well')
    if (wellGroup && !(wellGroup.children || []).length) {
      wellGroup.children = wells.value.map(well => ({
        id: `single-well-${well.id}`,
        label: well.wellName,
        type: 'single-well',
        wellName: well.wellName,
        defaultExpanded: false,
        children: WORKSPACE_GROUPS.map(group => ({
          id: `single-well-${well.id}-${group.id}`,
          label: group.label,
          type: group.id,
          wellName: well.wellName,
          children: []
        }))
      }))
    }
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '井列表加载失败')
  } finally {
    loadingWells.value = false
  }
}

const selectWell = async wellName => {
  const targetWellName = String(wellName || '').trim()
  if (!targetWellName || targetWellName === selectedWellName.value) return

  selectedWellName.value = targetWellName
  selectedPvtTable.value = ''
  selectedDataTable.value = activeMethod.value === '等时试井'
    ? 'local-import'
    : (isOwnedPressureMethod.value ? pressureTestType.value : '')
  importedDataFileName.value = '当前井产能测试数据'
  activeProductivityTestId.value = null
  activeEvaluationId.value = null
  activeStableId.value = null
  autoCalculateStable.value = false
  if (isOwnedPressureMethod.value) {
    storedProductivityTest.value = null
    calculationOutput.value = null
    resultDirty.value = false
    savedInputSignature.value = ''
  }
  pressureWorkspaceKey.value += 1
  try {
    // 全部井的目录在页面初始化时已经加载。切井只更新当前井上下文和右侧数据，
    // 不重新替换树分支，否则 TreeNode 会被卸载重建并表现为左侧菜单刷新。
    await loadPvtOptions()
  } catch (error) {
    ElMessage.warning(error?.msg || error?.message || '当前井PVT性质读取失败')
  }

  const query = {
    ...route.query,
    well: targetWellName,
    projectId: PROJECT_ID,
    gasReservoirId: GAS_RESERVOIR_ID
  }
  // 这些编号都属于上一口井；切井后保留会让右侧用新井名读取旧井记录。
  delete query.testId
  delete query.evaluationId
  delete query.stableId
  delete query.initialCalc
  await router.replace({ name: 'SingleWellProductivity', query })
}

const handleSidebarSelect = async node => {
  if (!node || node.disabled) return
  if (selectWorkspaceNodeScope(node) === 'reservoir') {
    workspaceActiveNodeId.value = node.id
    if (node.command) {
      const location = getReservoirCommandLocation(node.command)
      if (location) await router.push(location)
    }
    return
  }

  // 记录目录所指向的井，但此处不直接修改右侧组件正在使用的 selectedWellName。
  if (node.wellName) sidebarTargetWellName.value = node.wellName

  // 理论计算/动态产能及其“稳定流”都是纯目录节点：TreeNode 自己负责展开、收起，
  // 这里不能改变右侧页面，也不能覆盖当前具体记录的高亮状态。
  if ([
    THEORETICAL_CALCULATION_NODE_TYPE,
    DYNAMIC_PRODUCTIVITY_NODE_TYPE,
    THEORETICAL_STABLE_METHOD_NODE_TYPE,
    THEORETICAL_UNSTABLE_METHOD_NODE_TYPE,
    DYNAMIC_STABLE_METHOD_NODE_TYPE,
    DYNAMIC_UNSTABLE_METHOD_NODE_TYPE
  ].includes(node.type)) {
    workspacePendingNode.value = null
    return
  }

  workspaceActiveNodeId.value = node.id || ''

  const isModifiedIsochronalRecord =
    node.type === NODETYPE.NodeType_ProductivityEvaluationModifiedIsochronalWellTest
  const isProductivityTestNode = node.type === 'productivity-test' || node.label === '产能试井'

  if ([THEORETICAL_STABLE_RECORD_NODE_TYPE, DYNAMIC_STABLE_RECORD_NODE_TYPE].includes(node.type) && node.stableId) {
    workspacePendingNode.value = null
    selectedWellName.value = node.wellName || selectedWellName.value
    activeModule.value = node.type === THEORETICAL_STABLE_RECORD_NODE_TYPE ? '理论计算' : '动态产能'
    activeMethod.value = '稳定流'
    activeStableId.value = Number(node.stableId)
    autoCalculateStable.value = false
    await loadPvtOptions()
    await router.replace({
      name: 'SingleWellProductivity',
      query: {
        module: activeModule.value, method: '稳定流', well: selectedWellName.value,
        projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID, stableId: activeStableId.value
      }
    })
    return
  }

  if ([DYNAMIC_UNSTABLE_RECORD_NODE_TYPE, THEORETICAL_UNSTABLE_RECORD_NODE_TYPE].includes(node.type) && node.unstableId) {
    workspacePendingNode.value = null
    selectedWellName.value = node.wellName || selectedWellName.value
    activeModule.value = node.type === THEORETICAL_UNSTABLE_RECORD_NODE_TYPE ? '理论计算' : '动态产能'
    activeMethod.value = '不稳定流'
    activeStableId.value = Number(node.unstableId)
    autoCalculateStable.value = false
    await loadPvtOptions()
    await router.replace({
      name: 'SingleWellProductivity',
      query: {
        module: activeModule.value, method: '不稳定流', well: selectedWellName.value,
        projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID, stableId: activeStableId.value
      }
    })
    return
  }

  // “产能试井”仅作为目录层级，不代表一条真实试井记录。
  if (isProductivityTestNode || node.type === ISOCHRONAL_METHOD_NODE_TYPE || OWNED_PRODUCTIVITY_METHOD_NODE_TYPES.has(node.type)) return

  if (node.type === OWNED_PRODUCTIVITY_METHOD_NODE_TYPE ||
      OWNED_PRODUCTIVITY_METHOD_NODE_TYPES.has(node.type)) {
    selectedWellName.value = node.wellName || selectedWellName.value
    activeModule.value = '产能试井'
    activeMethod.value = node.pageMethod || (node.testMethod === 'one-point' ? '一点法' : '回压试井')
    selectedDataTable.value = node.testMethod
    activeProductivityTestId.value = null
    activeEvaluationId.value = null
    storedProductivityTest.value = null
    calculationOutput.value = null
    resultDirty.value = false
    savedInputSignature.value = ''
    pressureWorkspaceKey.value += 1
    await loadPvtOptions()
    await router.replace({
      name: 'SingleWellProductivity',
      query: {
        module: '产能试井', method: activeMethod.value, well: selectedWellName.value,
        projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID
      }
    })
    return
  }

  if (node.type === OWNED_PRODUCTIVITY_RECORD_NODE_TYPE && node.testId) {
    selectedWellName.value = node.wellName || selectedWellName.value
    activeModule.value = '产能试井'
    activeMethod.value = node.pageMethod || (node.testMethod === 'one-point' ? '一点法' : '回压试井')
    selectedDataTable.value = node.testMethod
    activeProductivityTestId.value = Number(node.testId)
    activeEvaluationId.value = null
    calculationOutput.value = null
    resultDirty.value = false
    try {
      const response = await productivityTestsApi.detail(
        node.testId, PROJECT_ID, GAS_RESERVOIR_ID, selectedWellName.value
      )
      const detail = unwrapData(response)
      const firstResult = detail.results?.[0] || detail.result
      storedProductivityTest.value = detail
      operationType.value = detail.operationType === 'injection' ? 'injection' : 'production'
      maximumFormationPressure.value = String(detail.input?.maximumFormationPressure ?? '')
      formationTemperature.value = String(detail.input?.formationTemperature ?? '')
      onePointAlpha.value = String(detail.input?.onePointAlpha ?? '0.25')
      calculationMethod.value = ({
        'pseudo-pressure': '拟压力',
        'pressure-squared': '压力平方方法',
        pressure: '压力法'
      })[firstResult?.pressureMethod] || '压力法'
      calculationResult.value = firstResult?.calculationResultType === 'exponential' ? '指数式' : '二项式'
      savedInputSignature.value = inputSignature({
        maximumFormationPressure: detail.input?.maximumFormationPressure,
        formationTemperature: detail.input?.formationTemperature,
        onePointAlpha: detail.input?.onePointAlpha,
        points: (detail.inputItems || []).map(item => ({
          pointNumber: item.testPointNumber,
          gasProduction: item.testDailyGasProduction,
          reservoirPressure: item.reservoirPressure,
          flowPressure: item.testFlowPressure
        }))
      }, detail.pvtId)
      await loadPvtOptions(detail.pvtId)
      pressureWorkspaceKey.value += 1
      await router.replace({
        name: 'SingleWellProductivity',
        query: {
          module: '产能试井', method: activeMethod.value,
          well: selectedWellName.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID, testId: node.testId
        }
      })
    } catch (error) {
      ElMessage.error(error.response?.data?.msg || error.message || '试井记录读取失败')
    }
    return
  }

  if (node.type === ISOCHRONAL_RECORD_NODE_TYPE && node.testId) {
    // 独立的新建/计算页只负责把点击交还给 /ipr；真正的记录详情由
    // /ipr 中复用的本工作台加载，使所有左侧记录节点拥有一致的地址行为。
    if (!props.embedded) {
      workspacePendingNode.value = node
      await router.push({ name: 'IprInterface' })
      return
    }

    selectedWellName.value = node.wellName || selectedWellName.value
    activeProductivityTestId.value = Number(node.testId)
    activeEvaluationId.value = null
    activeModule.value = '产能试井'
    activeMethod.value = '等时试井'
    pressureWorkspaceKey.value += 1
    selectedDataTable.value = 'local-import'
    importedDataFileName.value = `${node.label || '已保存等时试井'}数据`
    try {
      const response = await productivityStorageApi.getIsochronal(
        node.testId, PROJECT_ID, GAS_RESERVOIR_ID
      )
      const detail = response?.data
      await loadPvtOptions(detail.record.pvtId)
      maximumFormationPressure.value = String(detail.input.maximumFormationPressure)
      formationTemperature.value = String(detail.input.formationTemperature)
      calculationMethod.value = ({
        'pseudo-pressure': '拟压力',
        'pressure-squared': '压力平方方法',
        pressure: '压力法'
      })[detail.pressureMethod] || '压力法'
      calculationResult.value = detail.result?.calculationResultType === 'exponential'
        ? '指数式'
        : '二项式'
      operationType.value = detail.record?.operationType === 'injection' ? 'injection' : 'production'
      await nextTick()
      pressureContentRef.value?.restorePersisted?.(detail)
    } catch (error) {
      ElMessage.error(error.response?.data?.msg || error.message || '等时试井记录读取失败')
    }
    return
  }

  if (isModifiedIsochronalRecord) {
    // 该类节点本身已含有所属井和记录编号，点击时直接打开页面。
    // 不在此处重新请求并替换整棵子树，避免点击事件进行中节点被销毁而造成界面闪回。
    selectedWellName.value = node.wellName || selectedWellName.value
    activeProductivityTestId.value = node.testId || node.resultId || null
    activeEvaluationId.value = node.evaluationId || node.raw?.ProductivityEvaluationId || null
    activeModule.value = '产能试井'
    activeMethod.value = '修正等时'
    activeContentTab.value = 'chart'
    const targetWell = selectedWellName.value
    const targetTestId = activeProductivityTestId.value
    if (route.query.module !== '产能试井' || route.query.method !== '修正等时' ||
      route.query.well !== targetWell || Number(route.query.testId) !== Number(targetTestId)) {
      await router.replace({
        name: 'SingleWellProductivity',
        query: {
          module: '产能试井',
          method: '修正等时',
          well: targetWell,
          projectId: PROJECT_ID,
          gasReservoirId: GAS_RESERVOIR_ID,
          testId: targetTestId,
          ...(activeEvaluationId.value ? { evaluationId: activeEvaluationId.value } : {})
        }
      })
    }
    return
  }

  const isWorkspaceGroup = WORKSPACE_GROUPS.some(group => group.id === node.type)
  const isWellNode = node.wellName && node.label === node.wellName
  const isRootNode = ['g-well', 'g-reservoir', 'g-group'].includes(node.id)

  if (isWorkspaceGroup || isWellNode || isRootNode) return

  if (node.wellName) {
    await selectWell(node.wellName)
  }

  workspacePendingNode.value = node
  await router.push({ name: 'IprInterface' })
}

const inputSignature = (input, pvtId = null) => JSON.stringify({
  pvtId: pvtId == null || pvtId === '' ? null : Number(pvtId),
  maximumFormationPressure: Number(input?.maximumFormationPressure),
  formationTemperature: Number(input?.formationTemperature),
  onePointAlpha: Number(input?.onePointAlpha),
  gasType: input?.gasType || null,
  specificGravity: Number(input?.specificGravity) || null,
  hydrogenSulfide: Number(input?.hydrogenSulfide) || 0,
  carbonDioxide: Number(input?.carbonDioxide) || 0,
  nitrogen: Number(input?.nitrogen) || 0,
  points: (input?.points || []).map(point => [
    Number(point.pointNumber),
    Number(point.gasProduction),
    Number(point.reservoirPressure),
    Number(point.flowPressure)
  ])
})

const handleResultChange = (output, metadata = {}) => {
  calculationOutput.value = output
  if (isOwnedPressureMethod.value) resultDirty.value = !metadata.stored
}

const resultChartPoints = snapshot => {
  const exponential = snapshot.result.calculationResultType === 'exponential'
  const definitions = exponential
    ? [
        ['analysis', snapshot.result.analysisPoints],
        ['regression', snapshot.result.regressionLine],
        ['transient', snapshot.result.transientLine]
      ]
    : [
        ['regularized', snapshot.result.analysisPoints],
        ['regression', snapshot.result.regressionLine],
        ['shifted-regression', snapshot.result.transientLine]
      ]
  return definitions.flatMap(([curveType, points]) => (points || []).map((point, index) => ({
    curveType,
    pointNumber: index + 1,
    sourcePointNumber: curveType === 'analysis' ? index + 1 : null,
    xValue: Number(point.x),
    yValue: Number(point.y),
    deleted: false,
    dataLabel: point.label || null
  })))
}

const resultIprPoints = snapshot => (snapshot.result.iprCurves || []).flatMap((curve, curveIndex) =>
  (curve.points || []).map((point, pointIndex) => ({
    curveNumber: curveIndex + 1,
    pointNumber: pointIndex + 1,
    formationPressure: snapshot.result.calculationResultType === 'exponential'
      ? Number(curve.formationPressure)
      : null,
    gasProduction: Number(point.gasProduction),
    bottomHoleFlowingPressure: Number(point.bottomHoleFlowingPressure),
    deleted: false,
    dataLabel: point.label || null
  }))
)

const saveCalculation = async () => {
  if (!isOwnedPressureMethod.value || savingResult.value) return
  const snapshot = pressureContentRef.value?.getPersistenceSnapshot?.()
  if (!snapshot?.result || !snapshot.input?.points?.length) {
    ElMessage.warning('请先完成计算')
    return
  }
  if (snapshot.pressureMethod === 'pseudo-pressure' && !selectedPvtRecord.value) {
    ElMessage.warning('拟压力方法必须选择PVT表')
    return
  }
  savingResult.value = true
  try {
    const signature = inputSignature(snapshot.input, selectedPvtTable.value)
    const replaceInput = !activeProductivityTestId.value || signature !== savedInputSignature.value
    const response = await productivityTestsApi.save({
      testId: activeProductivityTestId.value,
      projectId: PROJECT_ID,
      gasReservoirId: GAS_RESERVOIR_ID,
      wellName: selectedWellName.value,
      pvtId: selectedPvtRecord.value ? Number(selectedPvtTable.value) : null,
      operationType: operationType.value,
      testMethod: pressureTestType.value,
      testNo: null,
      testDate: String(snapshot.testDate || new Date().toISOString().slice(0, 10)).slice(0, 10),
      wellType: null,
      replaceInput,
      input: {
        ...snapshot.input,
        points: undefined
      },
      inputItems: snapshot.input.points.map(point => ({
        testPointNumber: Number(point.pointNumber),
        testDailyGasProduction: Number(point.gasProduction),
        reservoirPressure: Number(point.reservoirPressure),
        testFlowPressure: Number(point.flowPressure)
      })),
      result: {
        calculationResultType: snapshot.result.calculationResultType,
        pressureMethod: snapshot.pressureMethod,
        evaluationId: snapshot.result.evaluationId,
        darcySeepageCoefficient: snapshot.result.darcyCoefficient,
        nonDarcySeepageCoefficient: snapshot.result.nonDarcyCoefficient,
        openFlowCapacity: snapshot.result.openFlowCapacity,
        productivityCoefficient: snapshot.result.productivityCoefficient,
        productivityExponent: snapshot.result.productivityExponent,
        gradient: snapshot.result.gradient,
        intercept: snapshot.result.intercept,
        rSquared: snapshot.result.rSquared,
        reliabilityLevel: snapshot.result.reliabilityLevel,
        reliabilityDescription: snapshot.result.reliabilityDescription,
        chartPoints: resultChartPoints(snapshot),
        iprPoints: resultIprPoints(snapshot)
      }
    })
    const saved = unwrapData(response)
    activeProductivityTestId.value = Number(saved.testId)
    savedInputSignature.value = signature
    resultDirty.value = false
    await loadOwnedProductivityNodes(selectedWellName.value, { expand: true })
    const detail = unwrapData(await productivityTestsApi.detail(
      saved.testId, PROJECT_ID, GAS_RESERVOIR_ID, selectedWellName.value
    ))
    storedProductivityTest.value = detail
    await router.replace({
      name: 'SingleWellProductivity',
      query: {
        module: '产能试井', method: activeMethod.value,
        well: selectedWellName.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID, testId: saved.testId
      }
    })
    ElMessage.success(`已保存${activeMethod.value}${saved.testNo}`)
  } catch (error) {
    ElMessage.error(error.response?.data?.msg || error.response?.data?.message || error.message || '保存失败')
  } finally {
    savingResult.value = false
  }
}

const handleCalculate = async () => {
  if (calculationMethod.value === '拟压力' && !selectedPvtTable.value) {
    ElMessage.warning('请先计算并保存PVT表')
    return
  }
  if (!selectedDataTable.value) {
    ElMessage.warning('请选择数据表')
    return
  }
  calculationOutput.value = null
  await nextTick()
  await pressureContentRef.value?.analyze?.()
}

const handleSourceInputSync = ({ maximumFormationPressure: pressure, formationTemperature: temperature }) => {
  if (Number.isFinite(Number(pressure))) maximumFormationPressure.value = String(pressure)
  if (Number.isFinite(Number(temperature))) formationTemperature.value = String(temperature)
}

const handleSaveIsochronal = async () => {
  if (activeMethod.value !== '等时试井' || savingProductivityTest.value) return
  if (!selectedPvtRecord.value) {
    ElMessage.warning('请选择PVT表')
    return
  }
  const snapshot = pressureContentRef.value?.getPersistenceSnapshot?.()
  if (!snapshot) {
    ElMessage.warning('请先完成等时试井计算')
    return
  }
  if (!snapshot.input.points.length) {
    ElMessage.warning('没有可保存的等时试井输入数据')
    return
  }
  savingProductivityTest.value = true
  try {
    const pvtNo = Number(selectedPvtOption.value?.pvtNo)
    const response = await productivityStorageApi.saveIsochronal({
      projectId: PROJECT_ID,
      gasReservoirId: GAS_RESERVOIR_ID,
      wellName: selectedWellName.value,
      testId: activeProductivityTestId.value,
      pvtNo,
      pvtName: selectedPvtOption.value?.pvtName || `PVT性质${pvtNo}`,
      ...snapshot
    })
    const record = response?.data
    activeProductivityTestId.value = Number(record.testId)
    const detail = (await productivityStorageApi.getIsochronal(
      record.testId, PROJECT_ID, GAS_RESERVOIR_ID
    ))?.data
    pressureContentRef.value?.restorePersisted?.(detail)
    const nodes = await loadIsochronalNodes(selectedWellName.value, { expand: true })
    const savedNode = nodes.find(item => Number(item.testId) === Number(record.testId))
    if (savedNode) workspaceActiveNodeId.value = savedNode.id
    if (!props.embedded) {
      await router.replace({
        name: 'SingleWellProductivity',
        query: {
          module: '产能试井', method: '等时试井',
          well: selectedWellName.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID, testId: record.testId
        }
      })
    }
    ElMessage.success(`已保存等时试井${record.testNo}`)
  } catch (error) {
    ElMessage.error(error.response?.data?.msg || error.response?.data?.message || error.message || '保存失败')
  } finally {
    savingProductivityTest.value = false
  }
}

const toggleParamsPanel = () => {
  paramsCollapsed.value = !paramsCollapsed.value
}

const handleProductivitySaved = async saved => {
  activeProductivityTestId.value = saved.testId
  await loadModifiedIsochronalNodes(selectedWellName.value, { expand: true })
  const wellNode = workspaceTreeData.value.find(node => node.id === 'g-well')?.children
    ?.find(node => (node.wellName || node.label) === selectedWellName.value)
  const savedNode = wellNode?.children?.find(node => node.type === 'single-well-productivity')
    ?.children?.find(node => node.type === 'productivity-test')
    ?.children?.find(node => Number(node.testId) === Number(saved.testId))
  if (savedNode) workspaceActiveNodeId.value = savedNode.id
  await router.replace({
    name: 'SingleWellProductivity',
    query: {
      module: '产能试井',
      method: '修正等时',
      well: selectedWellName.value,
      projectId: PROJECT_ID,
      gasReservoirId: GAS_RESERVOIR_ID,
      testId: saved.testId
    }
  })
}

const handleStableSaved = async saved => {
  activeStableId.value = Number(saved.unstableId ?? saved.stableId)
  const nodes = activeModule.value === '理论计算'
    ? activeMethod.value === '不稳定流'
      ? await loadTheoreticalUnstableNodes(selectedWellName.value, { expand: true })
      : await loadTheoreticalStableNodes(selectedWellName.value, { expand: true })
    : activeMethod.value === '不稳定流'
      ? await loadDynamicUnstableNodes(selectedWellName.value, { expand: true })
      : await loadDynamicStableNodes(selectedWellName.value, { expand: true })
  const savedNode = nodes.find(node => Number(node.unstableId ?? node.stableId) === activeStableId.value
    && (activeMethod.value !== '不稳定流' || [DYNAMIC_UNSTABLE_RECORD_NODE_TYPE, THEORETICAL_UNSTABLE_RECORD_NODE_TYPE].includes(node.type)))
  if (savedNode) workspaceActiveNodeId.value = savedNode.id
  await router.replace({
    name: 'SingleWellProductivity',
    query: {
      module: activeModule.value, method: activeMethod.value, well: selectedWellName.value,
      projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID, stableId: activeStableId.value
    }
  })
}

const handleStableRecordMissing = async () => {
  activeStableId.value = null
  if (activeModule.value === '理论计算') {
    if (activeMethod.value === '不稳定流') await loadTheoreticalUnstableNodes(selectedWellName.value)
    else await loadTheoreticalStableNodes(selectedWellName.value)
  }
  else if (activeMethod.value === '不稳定流') await loadDynamicUnstableNodes(selectedWellName.value)
  else await loadDynamicStableNodes(selectedWellName.value)
  await router.replace({
    name: 'SingleWellProductivity',
    query: { module: activeModule.value, method: activeMethod.value, well: selectedWellName.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID }
  })
}

const handleInitialStableCalculated = async () => {
  // 首次计算已经完成，立即消费路由标记，防止普通刷新再次自动发起三次计算。
  autoCalculateStable.value = false
  await router.replace({
    name: 'SingleWellProductivity',
    query: { module: activeModule.value, method: activeMethod.value, well: selectedWellName.value, projectId: PROJECT_ID, gasReservoirId: GAS_RESERVOIR_ID }
  })
}

onMounted(async () => {
  window.addEventListener('click', closeStableContextMenu)
  await loadWells()
  if (isOwnedPressureMethod.value) selectedDataTable.value = pressureTestType.value
  if (selectedWellName.value) await loadPvtOptions()
  if (route.query.method === '等时试井' || props.embeddedNode) selectedDataTable.value = 'local-import'
  await Promise.allSettled([
    loadAllModifiedIsochronalNodes(),
    loadAllIsochronalNodes()
  ])
  try {
    await loadAllOwnedProductivityNodes()
  } catch (error) {
    console.warn('部分回压/一点法记录加载失败', error)
  }
  try {
    await loadAllStableNodes()
  } catch (error) {
    console.warn('理论计算/动态产能目录加载失败', error)
  }
  if (selectedWellName.value && (route.query.method === '等时试井' || props.embeddedNode) && activeProductivityTestId.value) {
    await handleSidebarSelect({
      id: `productivity-test-isochronal-${activeProductivityTestId.value}`,
      type: ISOCHRONAL_RECORD_NODE_TYPE,
      testId: activeProductivityTestId.value,
      wellName: selectedWellName.value
    })
  } else if (selectedWellName.value && isOwnedPressureMethod.value && activeProductivityTestId.value) {
    await handleSidebarSelect({
      id: `owned-productivity-test-${pressureTestType.value}-${activeProductivityTestId.value}`,
      type: OWNED_PRODUCTIVITY_RECORD_NODE_TYPE,
      testMethod: pressureTestType.value,
      pageMethod: activeMethod.value,
      testId: activeProductivityTestId.value,
      wellName: selectedWellName.value
    })
  }
})
onBeforeUnmount(() => window.removeEventListener('click', closeStableContextMenu))
</script>

<template>
  <!-- 顶部菜单栏对应板块：单井产能。 -->
  <div class="productivity-interface" :class="{ embedded: props.embedded }">
    <RibbonMenu v-if="!props.embedded" :scope="workspaceRibbonScope"
      @scope-change="setWorkspaceRibbonScope" @command="handleCommand" />

    <div class="productivity-main">
      <!-- 公共左侧目录：与 IprInterface.vue 使用同一个组件。 -->
      <WorkspaceSidebar v-if="!props.embedded" v-model:keyword="keyword" v-model:collapsed="sideTreeCollapsed" :nodes="sidebarTreeData"
        :active-id="workspaceActiveNodeId" :loading="loadingWells" @select="handleSidebarSelect"
        @expand="handleSidebarExpand"
        @node-contextmenu="handleStableContextMenu" />

            <main class="productivity-content">
        <div v-if="activeModule && activeMethod" class="test-tabs">
          <div class="test-tab">
            <span>{{ tabTitle }}</span>
          </div>
        </div>

        <template v-if="activeModule === '产能试井' && activeMethod">

          <ModifiedIsochronalContent v-if="activeMethod === '修正等时'" :project-id="MODIFIED_ISOCHRONAL_PROJECT_ID"
            :gas-reservoir-id="MODIFIED_ISOCHRONAL_GAS_RESERVOIR_ID" :well-name="selectedWellName"
            :test-id="activeProductivityTestId" :evaluation-id="activeEvaluationId" @saved="handleProductivitySaved" />

          <section v-else class="test-workspace">
            <aside class="parameter-panel" :class="{ collapsed: paramsCollapsed }">
              <button v-if="paramsCollapsed" class="parameter-collapsed-tab" type="button" title="展开参数设置"
                @click="toggleParamsPanel">
                参数设置
              </button>

              <template v-else>
                <div class="parameter-heading">
                  <span>参数设置</span>
                  <button class="parameter-toggle" type="button" title="收起参数设置" aria-label="收起参数设置"
                    @click="toggleParamsPanel">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true">
                      <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
                    </svg>
                  </button>
                </div>
                <div class="parameter-form">
                  <label class="field-group">
                    <span>当前PVT表</span>
                    <input
                      :value="pvtOptionsLoading
                        ? '正在加载PVT…'
                        : selectedPvtOption ? 'PVT性质1' : '当前井暂无已保存PVT性质'"
                      readonly
                    />
                  </label>

                  <label class="field-group">
                    <span>选择数据表</span>
                    <button type="button" class="local-import-button" :disabled="importingData" @click="chooseDataFile">
                      {{ importingData ? '正在导入…' : '本地导入' }}
                    </button>
                    <input ref="dataFileInput" class="hidden-data-file" type="file" accept=".xlsx,.xls,.csv"
                      @change="handleDataFile" />
                    <small class="imported-data-name">{{ importedDataFileName }}</small>
                  </label>

                  <div class="section-heading">
                    <span>其他数据</span>
                    <i></i>
                  </div>

                  <label class="field-group">
                    <span>计算IPR曲线的最大地层压力（MPa）</span>
                    <input v-model="maximumFormationPressure" inputmode="decimal" />
                  </label>

                  <label class="field-group">
                    <span>地层温度（℃）</span>
                    <input v-model="formationTemperature" inputmode="decimal" />
                  </label>

                  <label v-if="activeMethod === '一点法'" class="field-group">
                    <span>产能系数（α）</span>
                    <input v-model="onePointAlpha" inputmode="decimal" />
                  </label>

                  <fieldset class="radio-group">
                    <legend>计算方法</legend>
                    <label><input v-model="calculationMethod" type="radio" value="拟压力" />拟压力</label>
                    <label><input v-model="calculationMethod" type="radio" value="压力平方方法" />压力平方方法</label>
                    <label><input v-model="calculationMethod" type="radio" value="压力法" />压力法</label>
                  </fieldset>

                <fieldset class="radio-group">
                  <legend>注采类型</legend>
                  <label><input v-model="operationType" type="radio" value="production" />采气</label>
                  <label><input v-model="operationType" type="radio" value="injection" />注气</label>
                </fieldset>

                  <fieldset class="radio-group result-methods">
                    <legend>计算结果</legend>
                    <label><input v-model="calculationResult" type="radio" value="二项式" />二项式</label>
                    <label><input v-model="calculationResult" type="radio" value="指数式" />指数式</label>
                  </fieldset>

                  <div class="parameter-actions">
                    <button type="button" class="calculate-button" @click="handleCalculate">计算</button>
                    <button
                      v-if="isOwnedPressureMethod"
                      type="button"
                      class="save-button"
                      :disabled="savingResult || !calculationOutput || !resultDirty"
                      @click="saveCalculation"
                    >{{ savingResult ? '保存中…' : '保存' }}</button>
                    <button
                      v-if="activeMethod === '等时试井'"
                      type="button"
                      class="save-button"
                      :disabled="savingProductivityTest || !calculationOutput"
                      @click="handleSaveIsochronal"
                    >{{ savingProductivityTest ? '保存中…' : '保存' }}</button>
                  </div>
                  <div v-if="calculationOutput" class="calculation-output">
                    <template v-if="calculationOutput.calculationResultType === 'exponential'">
                      <label class="field-group">
                        <span>{{ operationType === 'injection' ? '注气能力系数 C' : '产能系数 C' }}</span>
                        <input :value="scientific(calculationOutput.productivityCoefficient)" readonly />
                      </label>
                      <label class="field-group">
                        <span>{{ operationType === 'injection' ? '注气指数 n' : '产能指数 n' }}</span>
                        <input :value="Number(calculationOutput.productivityExponent).toFixed(4)" readonly />
                      </label>
                    </template>
                    <template v-else>
                      <label class="field-group">
                        <span>{{ operationType === 'injection' ? '注气达西渗流系数 A' : '达西渗流系数 A' }}</span>
                        <input :value="scientific(calculationOutput.darcyCoefficient)" readonly />
                      </label>
                      <label class="field-group">
                        <span>{{ operationType === 'injection' ? '注气非达西高速流系数 B' : '非达西高速流系数 B' }}</span>
                        <input :value="scientific(calculationOutput.nonDarcyCoefficient)" readonly />
                      </label>
                    </template>
                    <label class="field-group">
                      <span>{{ operationType === 'injection' ? '最大注气量(10⁴m³/d)' : '无阻流量(10⁴m³/d)' }}</span>
                      <input :value="Number.isFinite(Number(calculationOutput.aofRate)) ? Number(calculationOutput.aofRate).toFixed(4) : ''" readonly />
                    </label>
                  </div>
                </div>
              </template>
            </aside>

            <div class="result-output-panel" :aria-label="`${testTitle}结果区域`">
              <BinomialPressureContent v-if="usesPressureCalculation && selectedDataTable"
                :key="`${selectedWellName}-${activeMethod}-${pressureWorkspaceKey}`" ref="pressureContentRef" embedded
                auto-select-data :well-names="wells.map(item => item.wellName)" :initial-well-name="selectedWellName"
                :initial-test-type="pressureTestType" :external-formation-pressure="Number(maximumFormationPressure)"
                :external-temperature="Number(formationTemperature)" :external-one-point-alpha="Number(onePointAlpha)"
                :external-calculation-method="pressureCalculationMethod"
                :external-calculation-result="calculationResult === '指数式' ? 'exponential' : 'binomial'"
                :external-operation-type="operationType"
                :pvt-result-rows="selectedPvtRecord?.gasResultRows || []" :pvt-record="selectedPvtRecord"
                :stored-test="storedProductivityTest"
                :restore-only="!!activeProductivityTestId"
                :project-id="PROJECT_ID" :gas-reservoir-id="GAS_RESERVOIR_ID"
                @result-change="handleResultChange"
                @source-input-sync="handleSourceInputSync" />

              <template v-else>
                <div v-show="activeContentTab === 'table'" class="result-table-panel">
                  <el-table :data="resultGridRows" :show-header="false" height="100%" size="small" border empty-text=""
                    row-key="id">
                    <el-table-column v-for="column in resultGridColumns" :key="column.prop" :prop="column.prop"
                      :min-width="column.minWidth" />
                  </el-table>
                </div>

                <div v-show="activeContentTab === 'chart'" class="result-chart-panel" :aria-label="`${testTitle}结果分析图`">
                </div>

                <div class="bottom-chart-tabs">
                  <button type="button" class="bottom-chart-tab" :class="{ active: activeContentTab === 'table' }"
                    @click="activeContentTab = 'table'">
                    数据列表
                  </button>
                  <button type="button" class="bottom-chart-tab" :class="{ active: activeContentTab === 'chart' }"
                    @click="activeContentTab = 'chart'">
                    结果分析图
                  </button>
                </div>
              </template>
            </div>
          </section>
        </template>
        <template v-else-if="activeModule === '产能系数'">
          <ExponentialContent
            :pvt-table-options="pvtTableOptions"
            :selected-pvt-table="selectedPvtTable"
            :pvt-loading="pvtOptionsLoading"
            @select-pvt="selectedPvtTable = $event; changeSelectedPvt()"
            :operation-type="operationType"
            :well-name="selectedWellName"
            :maximum-formation-pressure="maximumFormationPressure"
            :formation-temperature="formationTemperature"
            :productivity-coefficient-c="productivityCoefficientC"
            :productivity-exponent-n="productivityExponentN"
            :corrected-coefficient-c="correctedCoefficientC"
            :corrected-exponent-n="correctedExponentN"
            :fitted-formation-pressure="fittedFormationPressure"
            :fitted-flow-rate="fittedFlowRate"
            :open-flow-rate="openFlowRate"
            :pvt-record="selectedPvtRecord"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
            :method-type="activeMethod"
            @update:coefficient-c="productivityCoefficientC = $event"
            @update:exponent-n="productivityExponentN = $event"
            @update:corrected-c="correctedCoefficientC = $event"
            @update:corrected-n="correctedExponentN = $event"
            @update:fitted-pressure="fittedFormationPressure = $event"
            @update:fitted-flow-rate="fittedFlowRate = $event"
            @update:open-flow-rate="openFlowRate = $event"
            @update:operation-type="operationType = $event"
          />
        </template>

        <template v-else-if="activeModule === '理论计算' && activeMethod === '稳定流'">
          <TheoreticalProductivityContent
            :well-name="selectedWellName"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
            :pvt-table-options="pvtTableOptions"
            :pvt-records="databasePvtRecords"
            :stable-id="activeStableId"
            :auto-calculate="autoCalculateStable"
            @saved="handleStableSaved"
            @record-missing="handleStableRecordMissing"
            @initial-calculated="handleInitialStableCalculated"
          />
        </template>

        <template v-else-if="activeModule === '动态产能' && activeMethod === '稳定流'">
          <DynamicProductivityContent
            :well-name="selectedWellName"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
            :pvt-table-options="pvtTableOptions"
            :pvt-records="databasePvtRecords"
            :stable-id="activeStableId"
            :auto-calculate="autoCalculateStable"
            @saved="handleStableSaved"
            @record-missing="handleStableRecordMissing"
            @initial-calculated="handleInitialStableCalculated"
          />
        </template>

        <template v-else-if="activeModule === '理论计算' && activeMethod === '不稳定流'">
          <TheoreticalUnstableProductivityContent
            :well-name="selectedWellName"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
            :pvt-table-options="pvtTableOptions"
            :pvt-records="databasePvtRecords"
            :unstable-id="activeStableId"
            :auto-calculate="autoCalculateStable"
            @saved="handleStableSaved"
            @record-missing="handleStableRecordMissing"
            @initial-calculated="handleInitialStableCalculated"
          />
        </template>

        <template v-else-if="activeModule === '动态产能' && activeMethod === '不稳定流'">
          <DynamicUnstableProductivityContent
            :well-name="selectedWellName"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
            :pvt-table-options="pvtTableOptions"
            :pvt-records="databasePvtRecords"
            :unstable-id="activeStableId"
            :auto-calculate="autoCalculateStable"
            @saved="handleStableSaved"
            @record-missing="handleStableRecordMissing"
            @initial-calculated="handleInitialStableCalculated"
          />
        </template>

        <template v-else-if="activeModule === '产能对比'">
          <ProductivityComparison
            :well-name="selectedWellName"
            :project-id="PROJECT_ID"
            :gas-reservoir-id="GAS_RESERVOIR_ID"
            :method-type="activeMethod"
          />
        </template>
      </main>
    </div>
  </div>
  <Teleport to="body">
    <div v-if="stableContextMenu.visible" class="stable-context-menu"
      :style="{ left: `${stableContextMenu.x}px`, top: `${stableContextMenu.y}px` }"
      @click.stop @contextmenu.prevent>
      <button v-if="isStableRecordNode(stableContextMenu.node)" type="button" @click="renameStableNode">重命名</button>
      <button type="button" class="danger" @click="deleteStableNode">删除</button>
    </div>
  </Teleport>
</template>

<style lang="scss" scoped>
$accent: #f4d000;
$accent-soft: #fff8d8;

:global(.stable-context-menu) {
  position: fixed;
  z-index: 4000;
  min-width: 168px;
  padding: 6px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  box-shadow: 0 8px 22px rgba(0, 0, 0, .18);
}
:global(.stable-context-menu button) {
  width: 100%; height: 32px; padding: 0 10px; border: 0; border-radius: 4px;
  background: transparent; text-align: left; cursor: pointer; color: #333;
}
:global(.stable-context-menu button:hover) { background: #f5f7fa; }
:global(.stable-context-menu button.danger) { color: #d93025; }

.productivity-interface {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
  color: #252525;
  font: 14px "Microsoft YaHei", "Segoe UI", sans-serif;

  &.embedded {
    width: 100%;
    height: 100%;
  }
}

.productivity-main {
  flex: 1;
  min-height: 0;
  display: flex;
}

.productivity-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.test-tabs {
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid transparent;
  background: #fafafa;
  overflow-x: auto;
  overflow-y: hidden;
}

.test-tab {
  height: 34px;
  max-width: 340px;
  padding: 0 12px;
  background: $accent;
  display: flex;
  align-items: center;
  box-sizing: border-box;
  color: #202020;
  font-family: Arial, sans-serif;
  font-size: 14px;
  font-weight: 600;
  border-right: 1px solid #e4e7ed;
  border-bottom: 2px solid transparent;
  white-space: nowrap;

  >span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 14px;
    font-weight: 600;
    line-height: normal;
    white-space: nowrap;
  }

}

.test-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  border-top: 0;
}

.parameter-panel {
  width: 280px;
  min-width: 280px;
  flex: 0 0 280px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  position: relative;
  border-right: 1px solid #d7d7d7;
  background: #fff;
  overflow: hidden;
  transition: width 0.16s ease, min-width 0.16s ease, flex-basis 0.16s ease;

  &.collapsed {
    width: 34px;
    min-width: 34px;
    flex-basis: 34px;
    height: 100%;
    border: 1px solid #d4d7db;
    border-right: 0;
    box-sizing: border-box;
    background: #fff;
  }
}

.parameter-heading {
  height: 34px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  border-bottom: 1px solid #d7d7d7;
  background: #f2f2f2;
  font-size: 13px;
}

.parameter-form {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 12px 14px;
}

.parameter-toggle {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 2px;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;

  &:hover {
    background: $accent-soft;
  }
}

.parameter-collapsed-tab {
  width: 100%;
  height: 76px;
  padding: 8px 0 0;
  border: 0;
  border-bottom: 1px solid #e2e6ea;
  box-sizing: border-box;
  background: #fff;
  color: #222;
  font: inherit;
  font-size: 13px;
  writing-mode: vertical-rl;
  text-orientation: upright;
  line-height: 1.05;
  letter-spacing: 0;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  cursor: pointer;

  &:hover {
    background: $accent-soft;
    box-shadow: inset -2px 0 0 $accent;
  }
}

.field-group {
  display: block;
  margin-bottom: 9px;
  color: #333;

  >span {
    display: block;
    margin-bottom: 3px;
    font-size: 12px;
    line-height: 18px;
  }

  select,
  input {
    width: 100%;
    height: 24px;
    padding: 0 8px;
    border: 1px solid #aaa;
    border-radius: 3px;
    background: #fff;
    color: #333;
    box-sizing: border-box;
    font: inherit;
    font-size: 13px;
    outline: none;

    &:focus {
      border-color: #b99500;
      box-shadow: 0 0 0 2px rgba(242, 200, 17, 0.16);
    }
  }
}

.local-import-button {
  width: 100%;
  height: 26px;
  padding: 0 8px;
  border: 1px solid #aaa;
  border-radius: 3px;
  background: #fff;
  color: #333;
  text-align: left;
  cursor: pointer;

  &:hover {
    border-color: #777;
  }

  &:disabled {
    color: #999;
    cursor: wait;
  }
}

.hidden-data-file {
  display: none;
}

.imported-data-name {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  color: #777;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-heading {
  height: 22px;
  margin: 10px 0 7px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 500;

  i {
    flex: 1;
    height: 1px;
    background: #999;
  }
}

.radio-group {
  margin: 0 0 10px;
  padding: 0;
  border: 0;

  legend {
    margin-bottom: 7px;
    padding: 0;
    font-size: 13px;
    font-weight: 500;
  }

  label {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-right: 10px;
    font-size: 13px;
    white-space: nowrap;
    cursor: pointer;
  }

  input {
    width: 14px;
    height: 14px;
    margin: 0;
    accent-color: #303133;
  }
}

.result-methods {
  margin-bottom: 10px;
}

.disabled-radio {
  color: #aaa;
  cursor: not-allowed !important;
}

.parameter-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.calculate-button {
  min-width: 86px;
  height: 32px;
  padding: 0 22px;
  border: 0;
  border-radius: 5px;
  background: #252525;
  color: #fff;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: background-color 0.15s ease;

  &:hover {
    background: #050505;
  }
}

.save-button {
  min-width: 86px;
  height: 32px;
  padding: 0 22px;
  border: 1px solid #252525;
  border-radius: 5px;
  background: #fff;
  color: #252525;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;

  &:hover:not(:disabled) { background: #f5f5f5; }
  &:disabled {
    border-color: #d7d7d7;
    color: #aaa;
    cursor: not-allowed;
  }
}

.calculation-output {
  margin-top: 10px;
}

.result-output-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
}

.result-table-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  --el-table-border-color: rgba(205, 205, 205, 0.55);
  --el-table-bg-color: #fff;
  --el-table-tr-bg-color: #fff;
  --el-table-row-hover-bg-color: #fff;

  :deep(.el-table) {
    width: 100%;
    height: 100%;
    background: #fff;
    color: #333;
    font-size: 13px;
  }

  :deep(.el-table__inner-wrapper::before),
  :deep(.el-table--border::after),
  :deep(.el-table--border::before) {
    background-color: rgba(205, 205, 205, 0.55);
  }

  :deep(.el-table td.el-table__cell) {
    height: 34px;
    padding: 0;
    background: #fff;
  }

  :deep(.el-table .cell) {
    height: 33px;
    padding: 0 8px;
    line-height: 33px;
  }

  :deep(.el-table__body tr:hover > td.el-table__cell) {
    background: #fff;
  }
}

.result-chart-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  background-color: #fff;
  background-image:
    linear-gradient(rgba(210, 214, 220, 0.42) 1px, transparent 1px),
    linear-gradient(90deg, rgba(210, 214, 220, 0.42) 1px, transparent 1px);
  background-size: 72px 34px;
}

.bottom-chart-tabs {
  // 未加载数据时的占位标签也沿用正式试井页面的尺寸和选中样式。
  height: 30px;
  flex: 0 0 30px;
  display: flex;
  align-items: stretch;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.bottom-chart-tab {
  min-width: 82px;
  height: 30px;
  padding: 0 14px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  background: #fff;
  color: #333;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;

  &:hover {
    background: $accent-soft;
    color: #202020;
  }

  &.active {
    background: #fff;
    color: #202020;
    font-weight: 600;
    box-shadow: inset 0 3px 0 $accent;
  }
}

@media (max-width: 1050px) {
  .parameter-heading {
    padding: 0 10px;
  }

  .radio-group label {
    margin-right: 8px;
  }
}

@media (max-width: 800px) {
  .field-group>span {
    line-height: 18px;
  }

  .radio-group label {
    margin: 0 5px 5px 0;
  }
}
</style>
