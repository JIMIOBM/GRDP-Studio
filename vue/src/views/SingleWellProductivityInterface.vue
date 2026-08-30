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
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import RibbonMenu from '@/components/RibbonMenu.vue'
import WorkspaceSidebar from '@/components/WorkspaceSidebar.vue'
import BinomialPressureContent from '@/views/WellControlInventory/BinomialPressureContent.vue'
import ModifiedIsochronalContent from '@/views/SingleWellProductivity/ModifiedIsochronalContent.vue'
import { NODETYPE } from '@/constants/nodeType'
import { wellApi } from '@/api/docker'
import { pvtStorageApi } from '@/api/pvtStorage'
import { productivityStorageApi } from '@/api/productivityStorage'
import { productivityTestsApi } from '@/api/productivityTests'
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
  workspaceActiveNodeId,
  workspacePendingCommand,
  workspacePendingNode,
  workspaceSelectedWellName,
  workspaceTreeCollapsed,
  workspaceTreeData,
  workspaceTreeHydrated,
  workspaceTreeKeyword
} from '@/utils/workspaceTreeState'

const PROJECT_ID = 6
const GAS_RESERVOIR_ID = 4
const MODIFIED_ISOCHRONAL_PROJECT_ID = 6
const MODIFIED_ISOCHRONAL_GAS_RESERVOIR_ID = 4

const route = useRoute()
const router = useRouter()

const MODULES = [
  { name: '产能试井', methods: ['回压试井', '等时试井', '修正等时', '一点法'] },
  { name: '产能系数', methods: ['二项式', '指数式'] },
  { name: '理论计算', methods: ['稳定流', '不稳定流'] },
  { name: '动态产能', methods: ['稳定流', '不稳定流'] },
  { name: '产能对比', methods: ['多周期'] }
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
if (route.query.well) selectedWellName.value = String(route.query.well)
const sideTreeCollapsed = workspaceTreeCollapsed
// 展开后适当加宽以容纳三种计算方式；收起形态对齐 PVT 的“图表数据”侧栏。
const paramsCollapsed = ref(false)
const routeModule = String(route.query.module || '')
const routeConfig = MODULES.find(item => item.name === routeModule)
const routeMethod = String(route.query.method || '')
const activeModule = ref(routeConfig?.name || '')
const activeMethod = ref(routeConfig?.methods.includes(routeMethod) ? routeMethod : '')
const routeTestId = Number(route.query.testId)
const routeEvaluationId = Number(route.query.evaluationId)
const activeProductivityTestId = ref(Number.isFinite(routeTestId) && routeTestId > 0 ? routeTestId : null)
const activeEvaluationId = ref(Number.isFinite(routeEvaluationId) && routeEvaluationId > 0 ? routeEvaluationId : null)
const selectedPvtTable = ref('')
const selectedDataTable = ref('')
const maximumFormationPressure = ref('56.34')
const formationTemperature = ref('120')
const onePointAlpha = ref('0.25')
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
    gasSettings,
    gasResultRows: detail?.gasResults || []
  }
}

const loadSelectedPvtDetail = async () => {
  selectedPvtDetail.value = null
  if (!selectedPvtTable.value || !selectedWellName.value) return
  try {
    const detail = unwrapData(await pvtStorageApi.getDetail(
      selectedPvtTable.value, PROJECT_ID, GAS_RESERVOIR_ID, selectedWellName.value
    ))
    selectedPvtDetail.value = normalizePvtDetail(detail)
  } catch (error) {
    ElMessage.warning(error?.msg || error?.message || 'PVT性质明细读取失败')
  }
}

const loadPvtOptions = async (preferredPvtId = null) => {
  databasePvtRecords.value = []
  selectedPvtDetail.value = null
  if (!selectedWellName.value) return void (selectedPvtTable.value = '')
  try {
    const records = unwrapData(await pvtStorageApi.list(
      PROJECT_ID, GAS_RESERVOIR_ID, selectedWellName.value
    )) || []
    databasePvtRecords.value = records
    const preferred = records.find(item => String(item.pvtId) === String(preferredPvtId))
    const current = records.find(item => String(item.pvtId) === String(selectedPvtTable.value))
    selectedPvtTable.value = String((preferred || current || records[0])?.pvtId || '')
    await loadSelectedPvtDetail()
  } catch (error) {
    selectedPvtTable.value = ''
    console.warn('当前井PVT性质读取失败', error)
  }
}

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

const testTitle = computed(() => `产能试井-${activeMethod.value}`)
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
  router.replace({
    name: 'SingleWellProductivity',
    query: {
      module: activeModule.value,
      method: activeMethod.value,
      well: selectedWellName.value
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

const handleCommand = async ({ group, name, parent }) => {
  // 顶部菜单栏“单井产能”板块：留在当前独立页面并切换功能模块/计算方法。
  if (group === '单井产能') {
    if (!selectedWellName.value) {
      ElMessage.warning('请先在左侧选择一口井')
      return
    }
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
      await loadPvtOptions()
    }
    selectModule(parent || name, parent ? name : '')
    return
  }

  // 将本次点击一并交给 IPR 工作台，避免用户切换后还要再点第二次。
  workspacePendingCommand.value = { group, name, parent }
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
  selectedWellName.value = wellName
  selectedPvtTable.value = ''
  selectedDataTable.value = activeMethod.value === '等时试井' ? 'local-import' : ''
  importedDataFileName.value = '当前井产能测试数据'
  activeProductivityTestId.value = null
  activeEvaluationId.value = null
  pressureWorkspaceKey.value += 1
  try {
    await Promise.all([
      loadModifiedIsochronalNodes(wellName),
      loadIsochronalNodes(wellName),
      loadPvtOptions()
    ])
  } catch (error) {
    ElMessage.warning(error?.msg || error?.message || '已保存试井记录读取失败')
  }
}

const handleSidebarSelect = async node => {
  if (!node) return
  workspaceActiveNodeId.value = node.id || ''

  const isModifiedIsochronalRecord =
    node.type === NODETYPE.NodeType_ProductivityEvaluationModifiedIsochronalWellTest
  const isProductivityTestNode = node.type === 'productivity-test' || node.label === '产能试井'

  // “产能试井”仅作为目录层级，不代表一条真实试井记录。
  if (isProductivityTestNode || node.type === ISOCHRONAL_METHOD_NODE_TYPE) return

  if (node.type === ISOCHRONAL_RECORD_NODE_TYPE && node.testId) {
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
      await router.replace({
        name: 'SingleWellProductivity',
        query: {
          module: '产能试井', method: '等时试井',
          well: selectedWellName.value, testId: node.testId
        }
      })
      await nextTick()
      await pressureContentRef.value?.loadWellData?.()
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
          testId: targetTestId,
          ...(activeEvaluationId.value ? { evaluationId: activeEvaluationId.value } : {})
        }
      })
    }
    return
  }

  if (node.wellName) {
    await selectWell(node.wellName)
  }

  const isWorkspaceGroup = WORKSPACE_GROUPS.some(group => group.id === node.type)
  const isWellNode = node.wellName && node.label === node.wellName
  const isRootNode = ['g-well', 'g-reservoir', 'g-group'].includes(node.id)

  if (isWorkspaceGroup || isWellNode || isRootNode) return

  workspacePendingNode.value = node
  await router.push({ name: 'IprInterface' })
}

const handleCalculate = async () => {
  if (!selectedPvtTable.value) {
    ElMessage.warning('请选择PVT表')
    return
  }
  if (!selectedDataTable.value) {
    ElMessage.warning('请选择数据表')
    return
  }
  calculationOutput.value = null
  await nextTick()
  await pressureContentRef.value?.analyze?.()
  if (activeMethod.value === '等时试井' && calculationOutput.value) {
    await handleSaveIsochronal()
  }
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
    const nodes = await loadIsochronalNodes(selectedWellName.value, { expand: true })
    const savedNode = nodes.find(item => Number(item.testId) === Number(record.testId))
    if (savedNode) workspaceActiveNodeId.value = savedNode.id
    await router.replace({
      name: 'SingleWellProductivity',
      query: {
        module: '产能试井', method: '等时试井',
        well: selectedWellName.value, testId: record.testId
      }
    })
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
      testId: saved.testId
    }
  })
}

onMounted(async () => {
  if (!workspaceTreeHydrated.value) {
    await router.replace({ name: 'IprInterface' })
    return
  }
  await loadWells()
  if (selectedWellName.value) await loadPvtOptions()
  if (route.query.method === '等时试井') selectedDataTable.value = 'local-import'
  await Promise.allSettled([
    loadAllModifiedIsochronalNodes(),
    loadAllIsochronalNodes()
  ])
  if (selectedWellName.value && route.query.method === '等时试井' && activeProductivityTestId.value) {
    await handleSidebarSelect({
      id: `productivity-test-isochronal-${activeProductivityTestId.value}`,
      type: ISOCHRONAL_RECORD_NODE_TYPE,
      testId: activeProductivityTestId.value,
      wellName: selectedWellName.value
    })
  }
})
</script>

<template>
  <!-- 顶部菜单栏对应板块：单井产能。 -->
  <div class="productivity-interface">
    <RibbonMenu @command="handleCommand" />

    <div class="productivity-main">
      <!-- 公共左侧目录：与 IprInterface.vue 使用同一个组件。 -->
      <WorkspaceSidebar
        v-model:keyword="keyword"
        v-model:collapsed="sideTreeCollapsed"
        :nodes="sidebarTreeData"
        :active-id="workspaceActiveNodeId"
        :loading="loadingWells"
        @select="handleSidebarSelect"
      />

      <main class="productivity-content">
        <template v-if="activeModule === '产能试井' && activeMethod">
          <div class="test-tabs">
            <div class="test-tab">
              <span>{{ testTitle }}</span>
            </div>
          </div>

          <ModifiedIsochronalContent
            v-if="activeMethod === '修正等时'"
            :project-id="MODIFIED_ISOCHRONAL_PROJECT_ID"
            :gas-reservoir-id="MODIFIED_ISOCHRONAL_GAS_RESERVOIR_ID"
            :well-name="selectedWellName"
            :test-id="activeProductivityTestId"
            :evaluation-id="activeEvaluationId"
            @saved="handleProductivitySaved"
          />

          <section v-else class="test-workspace">
            <aside class="parameter-panel" :class="{ collapsed: paramsCollapsed }">
              <button
                v-if="paramsCollapsed"
                class="parameter-collapsed-tab"
                type="button"
                title="展开参数设置"
                @click="toggleParamsPanel"
              >
                参数设置
              </button>

              <template v-else>
                <div class="parameter-heading">
                  <span>参数设置</span>
                  <button
                    class="parameter-toggle"
                    type="button"
                    title="收起参数设置"
                    aria-label="收起参数设置"
                    @click="toggleParamsPanel"
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true">
                      <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
                    </svg>
                  </button>
                </div>
                <div class="parameter-form">
                <label class="field-group">
                  <span>选择PVT表</span>
                  <select v-model="selectedPvtTable" @change="loadSelectedPvtDetail">
                    <option value="" disabled>{{ pvtTableOptions.length ? '请选择PVT性质' : '当前井暂无PVT性质' }}</option>
                    <option v-for="option in pvtTableOptions" :key="option.value" :value="option.value">
                      {{ option.label }}
                    </option>
                  </select>
                </label>

                <label class="field-group">
                  <span>选择数据表</span>
                  <button type="button" class="local-import-button" :disabled="importingData" @click="chooseDataFile">
                    {{ importingData ? '正在导入…' : '本地导入' }}
                  </button>
                  <input ref="dataFileInput" class="hidden-data-file" type="file" accept=".xlsx,.xls,.csv" @change="handleDataFile" />
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
                  <label class="disabled-radio" title="当前计算仅支持采气">
                    <input type="radio" value="injection" disabled />注气
                  </label>
                </fieldset>

                <fieldset class="radio-group result-methods">
                  <legend>计算结果</legend>
                  <label><input v-model="calculationResult" type="radio" value="二项式" />二项式</label>
                  <label><input v-model="calculationResult" type="radio" value="指数式" />指数式</label>
                </fieldset>

                  <button type="button" class="calculate-button" @click="handleCalculate">计算</button>
                  <div v-if="calculationOutput" class="calculation-output">
                    <template v-if="calculationOutput.calculationResultType === 'exponential'">
                      <label class="field-group">
                        <span>产能系数 C</span>
                        <input :value="scientific(calculationOutput.productivityCoefficient)" readonly />
                      </label>
                      <label class="field-group">
                        <span>产能指数 n</span>
                        <input :value="Number(calculationOutput.productivityExponent).toFixed(4)" readonly />
                      </label>
                    </template>
                    <template v-else>
                      <label class="field-group">
                        <span>达西渗流系数 A</span>
                        <input :value="scientific(calculationOutput.darcyCoefficient)" readonly />
                      </label>
                      <label class="field-group">
                        <span>非达西高速流系数 B</span>
                        <input :value="scientific(calculationOutput.nonDarcyCoefficient)" readonly />
                      </label>
                    </template>
                  </div>
                </div>
              </template>
            </aside>

            <div class="result-output-panel" :aria-label="`${testTitle}结果区域`">
              <BinomialPressureContent
                v-if="usesPressureCalculation && selectedDataTable"
                :key="`${selectedWellName}-${activeMethod}-${pressureWorkspaceKey}`"
                ref="pressureContentRef"
                embedded
                auto-select-data
                :well-names="wells.map(item => item.wellName)"
                :initial-well-name="selectedWellName"
                :initial-test-type="pressureTestType"
                :external-formation-pressure="Number(maximumFormationPressure)"
                :external-temperature="Number(formationTemperature)"
                :external-one-point-alpha="Number(onePointAlpha)"
                :external-calculation-method="pressureCalculationMethod"
                :external-calculation-result="calculationResult === '指数式' ? 'exponential' : 'binomial'"
                :pvt-result-rows="selectedPvtRecord?.gasResultRows || []"
                :pvt-record="selectedPvtRecord"
                :project-id="PROJECT_ID"
                :gas-reservoir-id="GAS_RESERVOIR_ID"
                @result-change="calculationOutput = $event"
              />

              <template v-else>
              <div v-show="activeContentTab === 'table'" class="result-table-panel">
                <el-table
                  :data="resultGridRows"
                  :show-header="false"
                  height="100%"
                  size="small"
                  border
                  empty-text=""
                  row-key="id"
                >
                  <el-table-column
                    v-for="column in resultGridColumns"
                    :key="column.prop"
                    :prop="column.prop"
                    :min-width="column.minWidth"
                  />
                </el-table>
              </div>

              <div
                v-show="activeContentTab === 'chart'"
                class="result-chart-panel"
                :aria-label="`${testTitle}结果分析图`"
              ></div>

              <div class="bottom-chart-tabs">
                <button
                  type="button"
                  class="bottom-chart-tab"
                  :class="{ active: activeContentTab === 'table' }"
                  @click="activeContentTab = 'table'"
                >
                  数据列表
                </button>
                <button
                  type="button"
                  class="bottom-chart-tab"
                  :class="{ active: activeContentTab === 'chart' }"
                  @click="activeContentTab = 'chart'"
                >
                  结果分析图
                </button>
              </div>
              </template>
            </div>
          </section>
        </template>

      </main>
    </div>
  </div>
</template>

<style lang="scss" scoped>
$accent: #f4d000;
$accent-soft: #fff8d8;

.productivity-interface {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
  color: #252525;
  font: 14px "Microsoft YaHei", "Segoe UI", sans-serif;
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

  > span {
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

  &:hover { background: $accent-soft; }
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

  > span {
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

  &:hover { border-color: #777; }
  &:disabled { color: #999; cursor: wait; }
}

.hidden-data-file { display: none; }

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

  i { flex: 1; height: 1px; background: #999; }
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

.result-methods { margin-bottom: 10px; }

.disabled-radio {
  color: #aaa;
  cursor: not-allowed !important;
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

  &:hover { background: #050505; }
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
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: stretch;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.bottom-chart-tab {
  min-width: 104px;
  height: 34px;
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
  .field-group > span {
    line-height: 18px;
  }

  .radio-group label {
    margin: 0 5px 5px 0;
  }
}

</style>
