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
import { productivityStorageApi } from '@/api/productivityStorage'
import { getPvtRecords } from '@/utils/pvtRecords'
import {
  getRememberedModifiedIsochronalWells,
  rememberModifiedIsochronalNode
} from '@/utils/productivityRecords'
import {
  workspaceActiveNodeId,
  workspacePendingCommand,
  workspacePendingNode,
  workspaceSelectedWellName,
  workspaceTreeCollapsed,
  workspaceTreeData,
  workspaceTreeKeyword
} from '@/utils/workspaceTreeState'

const PROJECT_ID = 2
const GAS_RESERVOIR_ID = 1
// 用户指定的原平台修正等时结果接口：/productivityevaluation/6/4/20
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
const activeModule = ref(
  MODULES.some(item => item.name === route.query.module)
    ? route.query.module
    : MODULES[0].name
)
const activeMethod = ref('')
const selectedPvtTable = ref('')
const selectedDataTable = ref('')
const maximumFormationPressure = ref('56.34')
const formationTemperature = ref('120')
const onePointAlpha = ref('0.25')
const calculationMethod = ref('拟压力')
const calculationResult = ref('二项式')
const activeContentTab = ref('chart')
const pressureContentRef = ref(null)
const calculationOutput = ref(null)
const activeProductivityTestId = ref(null)
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

const availablePvtRecords = computed(() =>
  selectedWellName.value
    ? getPvtRecords(PROJECT_ID, GAS_RESERVOIR_ID, selectedWellName.value)
    : []
)

const pvtTableOptions = computed(() => {
  return availablePvtRecords.value.map(record => `PVT性质${record.index}`)
})

const selectedPvtRecord = computed(() => {
  if (!selectedWellName.value || !selectedPvtTable.value) return null
  const index = Number(String(selectedPvtTable.value).match(/(\d+)$/)?.[1])
  return availablePvtRecords.value.find(record => Number(record.index) === index) || null
})

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

const dataTableOptions = computed(() => selectedWellName.value
  ? [`${selectedWellName.value}-产能测试数据`]
  : []
)

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

const ensureModifiedIsochronalNode = (wellName, { activate = true, persist = true } = {}) => {
  const wellGroup = workspaceTreeData.value.find(node => node.id === 'g-well')
  const wellNode = wellGroup?.children?.find(node =>
    (node.wellName || node.label) === wellName
  )
  if (!wellNode) return null

  const productivityGroup = wellNode.children?.find(node =>
    node.type === 'single-well-productivity' || node.label === '单井产能'
  )
  if (!productivityGroup) return null

  let testGroup = productivityGroup.children?.find(node =>
    node.type === 'productivity-test' || node.label === '产能试井'
  )
  if (!testGroup) {
    testGroup = {
      id: `${wellNode.id}-single-well-productivity-productivity-test`,
      label: '产能试井',
      type: 'productivity-test',
      wellName,
      children: []
    }
    productivityGroup.children = [...(productivityGroup.children || []), testGroup]
  }

  let resultNode = testGroup.children?.find(node =>
    node.type === NODETYPE.NodeType_ProductivityEvaluationModifiedIsochronalWellTest ||
    node.label === '修正等时'
  )
  if (!resultNode) {
    resultNode = {
      id: `${wellNode.id}-modified-isochronal`,
      label: '修正等时',
      type: NODETYPE.NodeType_ProductivityEvaluationModifiedIsochronalWellTest,
      wellName,
      projectId: MODIFIED_ISOCHRONAL_PROJECT_ID,
      gasReservoirId: MODIFIED_ISOCHRONAL_GAS_RESERVOIR_ID,
      pNodeType: NODETYPE.NodeType_ProductivityEvaluationByPseudoPressure,
      children: []
    }
    testGroup.children = [...(testGroup.children || []), resultNode]
  }

  wellNode.expanded = true
  productivityGroup.expanded = true
  testGroup.expanded = true
  if (persist) rememberModifiedIsochronalNode(PROJECT_ID, GAS_RESERVOIR_ID, wellName)
  if (activate) workspaceActiveNodeId.value = resultNode.id
  return resultNode
}

const ensureIsochronalRecordNode = (record, { activate = false } = {}) => {
  const wellGroup = workspaceTreeData.value.find(node => node.id === 'g-well')
  const wellNode = wellGroup?.children?.find(node => (node.wellName || node.label) === record.wellName)
  const productivityGroup = wellNode?.children?.find(node =>
    node.type === 'single-well-productivity' || node.label === '单井产能'
  )
  if (!wellNode || !productivityGroup) return null
  let testGroup = productivityGroup.children?.find(node =>
    node.type === 'productivity-test' || node.label === '产能试井'
  )
  if (!testGroup) {
    testGroup = {
      id: `${wellNode.id}-single-well-productivity-productivity-test`,
      label: '产能试井', type: 'productivity-test', wellName: record.wellName, children: []
    }
    productivityGroup.children = [...(productivityGroup.children || []), testGroup]
  }
  let recordNode = testGroup.children?.find(node =>
    node.type === 'productivity-test-record' && Number(node.testId) === Number(record.testId)
  )
  if (!recordNode) {
    recordNode = {
      id: `${wellNode.id}-productivity-test-${record.testId}`,
      label: record.testName || `产能试井-${record.testNo}`,
      type: 'productivity-test-record', wellName: record.wellName, testId: record.testId,
      children: [{
        id: `${wellNode.id}-productivity-test-${record.testId}-isochronal`,
        label: '等时试井', type: 'productivity-test-isochronal',
        wellName: record.wellName, testId: record.testId, children: []
      }]
    }
    testGroup.children = [...(testGroup.children || []), recordNode]
      .sort((left, right) => Number(left.testId || 0) - Number(right.testId || 0))
  }
  if (activate) {
    wellNode.expanded = true
    productivityGroup.expanded = true
    testGroup.expanded = true
    recordNode.expanded = true
    workspaceActiveNodeId.value = recordNode.children[0].id
  }
  return recordNode.children[0]
}

const loadIsochronalRecordNodes = async () => {
  try {
    const response = await productivityStorageApi.listIsochronal(PROJECT_ID, GAS_RESERVOIR_ID)
    ;(response?.data || []).forEach(record => ensureIsochronalRecordNode(record))
  } catch (error) {
    console.warn('读取等时试井记录失败', error)
  }
}

const handleCommand = async ({ group, name, parent }) => {
  // 顶部菜单栏“单井产能”板块：留在当前独立页面并切换功能模块/计算方法。
  if (group === '单井产能') {
    if (!selectedWellName.value) {
      ElMessage.warning('请先在左侧选择一口井')
      return
    }
    if (parent === '产能试井' && name === '修正等时') {
      ensureModifiedIsochronalNode(selectedWellName.value)
    }
    if (parent === '产能试井' && name === '等时试井') {
      activeProductivityTestId.value = null
      calculationOutput.value = null
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
        defaultExpanded: well.wellName === selectedWellName.value,
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

const selectWell = wellName => {
  if (selectedWellName.value !== wellName) {
    activeProductivityTestId.value = null
    calculationOutput.value = null
  }
  selectedWellName.value = wellName
  selectedPvtTable.value = ''
  selectedDataTable.value = ''
}

const handleSidebarSelect = async node => {
  workspaceActiveNodeId.value = node?.id || ''
  if (node?.wellName) {
    selectWell(node.wellName)
  }

  if (!node) return

  if (['productivity-test', 'productivity-test-record'].includes(node.type)) {
    return
  }

  if (node.type === 'productivity-test-isochronal' && node.testId) {
    activeModule.value = '产能试井'
    activeMethod.value = '等时试井'
    activeProductivityTestId.value = Number(node.testId)
    selectedPvtTable.value = ''
    selectedDataTable.value = `${node.wellName}-产能测试数据`
    try {
      const response = await productivityStorageApi.getIsochronal(
        node.testId, PROJECT_ID, GAS_RESERVOIR_ID
      )
      const detail = response?.data
      selectedPvtTable.value = `PVT性质${detail.record.pvtNo}`
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
        query: { module: '产能试井', method: '等时试井', well: node.wellName, testId: node.testId }
      })
      await nextTick()
      await pressureContentRef.value?.loadWellData?.()
      pressureContentRef.value?.restorePersisted?.(detail)
    } catch (error) {
      ElMessage.error(error.response?.data?.msg || error.message || '等时试井记录读取失败')
    }
    return
  }

  if (
    node.type === NODETYPE.NodeType_ProductivityEvaluationModifiedIsochronalWellTest ||
    (node.label === '修正等时' && node.resultId)
  ) {
    activeModule.value = '产能试井'
    activeMethod.value = '修正等时'
    activeContentTab.value = 'chart'
    await router.replace({
      name: 'SingleWellProductivity',
      query: { module: '产能试井', method: '修正等时', well: node.wellName }
    })
    return
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
  if (activeMethod.value !== '等时试井') return
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
    const pvtNo = Number(String(selectedPvtTable.value).match(/(\d+)$/)?.[1])
    const response = await productivityStorageApi.saveIsochronal({
      projectId: PROJECT_ID,
      gasReservoirId: GAS_RESERVOIR_ID,
      wellName: selectedWellName.value,
      testId: activeProductivityTestId.value,
      pvtNo,
      pvtName: selectedPvtTable.value,
      ...snapshot
    })
    const record = response?.data
    activeProductivityTestId.value = Number(record.testId)
    ensureIsochronalRecordNode(record, { activate: true })
    ElMessage.success(`已保存${record.testName}`)
  } catch (error) {
    ElMessage.error(error.response?.data?.msg || error.response?.data?.message || error.message || '保存失败')
  } finally {
    savingProductivityTest.value = false
  }
}

const toggleParamsPanel = () => {
  paramsCollapsed.value = !paramsCollapsed.value
}

onMounted(async () => {
  await loadWells()
  await loadIsochronalRecordNodes()
  getRememberedModifiedIsochronalWells(PROJECT_ID, GAS_RESERVOIR_ID).forEach(wellName => {
    ensureModifiedIsochronalNode(wellName, { activate: false, persist: false })
  })
  if (selectedWellName.value) {
    const method = String(route.query.method || '')
    const routeTestId = Number(route.query.testId)
    if (method === '等时试井' && Number.isFinite(routeTestId) && routeTestId > 0) {
      const recordNode = workspaceTreeData.value
        .find(node => node.id === 'g-well')?.children
        ?.flatMap(well => well.children || [])
        .flatMap(group => group.children || [])
        .flatMap(record => record.children || [])
        .flatMap(method => method.children || [])
        .find(node => node.type === 'productivity-test-isochronal' && Number(node.testId) === routeTestId)
      await handleSidebarSelect(recordNode || {
        id: `productivity-test-${routeTestId}-isochronal`,
        type: 'productivity-test-isochronal',
        testId: routeTestId,
        wellName: selectedWellName.value,
        label: '等时试井'
      })
      return
    }
    if (activeModule.value === '产能试井' && method === '修正等时') {
      ensureModifiedIsochronalNode(selectedWellName.value)
    }
    selectModule(activeModule.value, method)
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
            :pvt-table-options="pvtTableOptions"
            :data-table-options="dataTableOptions"
            v-model:selected-pvt-table="selectedPvtTable"
            v-model:selected-data-table="selectedDataTable"
            v-model:maximum-formation-pressure="maximumFormationPressure"
            v-model:formation-temperature="formationTemperature"
            v-model:calculation-method="calculationMethod"
            v-model:calculation-result="calculationResult"
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
                  <select v-model="selectedPvtTable">
                    <option value="">请选择</option>
                    <option v-for="option in pvtTableOptions" :key="option" :value="option">
                      {{ option }}
                    </option>
                  </select>
                </label>

                <label class="field-group">
                  <span>选择数据表</span>
                  <select v-model="selectedDataTable">
                    <option value="">请选择</option>
                    <option v-for="option in dataTableOptions" :key="option" :value="option">
                      {{ option }}
                    </option>
                  </select>
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

                <fieldset class="radio-group result-methods">
                  <legend>计算结果</legend>
                  <label><input v-model="calculationResult" type="radio" value="二项式" />二项式</label>
                  <label><input v-model="calculationResult" type="radio" value="指数式" />指数式</label>
                </fieldset>
                  <button
                    type="button"
                    class="calculate-button"
                    :disabled="savingProductivityTest"
                    @click="handleCalculate"
                  >{{ savingProductivityTest ? '保存中' : '计算' }}</button>
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
                :key="`${selectedWellName}-${activeMethod}-${selectedDataTable}-${calculationResult}`"
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

.calculate-button:disabled {
  cursor: wait;
  opacity: 0.6;
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
