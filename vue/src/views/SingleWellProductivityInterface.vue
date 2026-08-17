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
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import RibbonMenu from '@/components/RibbonMenu.vue'
import WorkspaceSidebar from '@/components/WorkspaceSidebar.vue'
import BinomialPressureContent from '@/views/WellControlInventory/BinomialPressureContent.vue'
import { wellApi } from '@/api/docker'
import { getPvtRecords } from '@/utils/pvtRecords'
import {
  workspaceActiveNodeId,
  workspacePendingCommand,
  workspacePendingNode,
  workspaceSelectedWellName,
  workspaceTreeCollapsed,
  workspaceTreeData,
  workspaceTreeKeyword
} from '@/utils/workspaceTreeState'

const PROJECT_ID = 6
const GAS_RESERVOIR_ID = 3

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
const maximumFormationPressure = ref('50')
const formationTemperature = ref('120')
const calculationMethod = ref('拟压力')
const calculationResult = ref('二项式')
const activeContentTab = ref('chart')
const pressureContentRef = ref(null)

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

const pvtTableOptions = computed(() =>
  selectedWellName.value
    ? getPvtRecords(PROJECT_ID, GAS_RESERVOIR_ID, selectedWellName.value)
      .map(record => `PVT性质${record.index}`)
    : []
)

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
const usesPressureBinomial = computed(() =>
  activeModule.value === '产能试井' &&
  calculationMethod.value === '压力法' &&
  calculationResult.value === '二项式'
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

const handleCommand = async ({ group, name, parent }) => {
  // 顶部菜单栏“单井产能”板块：留在当前独立页面并切换功能模块/计算方法。
  if (group === '单井产能') {
    if (!selectedWellName.value) {
      ElMessage.warning('请先在左侧选择一口井')
      return
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
  if (!usesPressureBinomial.value) {
    ElMessage.info('当前仅接入压力法二项式计算')
    return
  }
  await nextTick()
  pressureContentRef.value?.analyze?.()
}

const toggleParamsPanel = () => {
  paramsCollapsed.value = !paramsCollapsed.value
}

onMounted(() => {
  if (selectedWellName.value) {
    selectModule(activeModule.value, String(route.query.method || ''))
  }
  loadWells()
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

          <section class="test-workspace">
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

                  <button type="button" class="calculate-button" @click="handleCalculate">计算</button>
                </div>
              </template>
            </aside>

            <div class="result-output-panel" :aria-label="`${testTitle}结果区域`">
              <BinomialPressureContent
                v-if="usesPressureBinomial && selectedDataTable"
                :key="`${selectedWellName}-${activeMethod}-${selectedDataTable}`"
                ref="pressureContentRef"
                embedded
                auto-select-data
                :well-names="wells.map(item => item.wellName)"
                :initial-well-name="selectedWellName"
                :initial-test-type="pressureTestType"
                :external-formation-pressure="Number(maximumFormationPressure)"
                :external-temperature="Number(formationTemperature)"
                :project-id="PROJECT_ID"
                :gas-reservoir-id="GAS_RESERVOIR_ID"
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
