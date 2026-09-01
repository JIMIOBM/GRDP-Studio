<!--
  理论计算－不稳定流静态界面
  菜单入口：单井产能 > 理论计算 > 不稳定流
-->
<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pvtStorageApi } from '@/api/pvtStorage'

const props = defineProps({
  wellName: {
    type: String,
    default: ''
  },
  pvtTableOptions: {
    type: Array,
    default: () => []
  },
  pvtRecords: {
    type: Array,
    default: () => []
  },
  projectId: {
    type: Number,
    required: true
  },
  gasReservoirId: {
    type: Number,
    required: true
  },
  defaultWellType: {
    type: String,
    default: 'vertical'
  }
})

const paramsCollapsed = ref(false)
const activePanelTab = ref('input')
const activeResultTab = ref('chart')
const wellType = ref(props.defaultWellType === 'horizontal' ? 'horizontal' : 'vertical')
const calculationMethod = ref('拟压力')
const selectedPvtTable = ref('')
const gasType = ref('干气')
const specificGravity = ref('0')
const hydrogenSulfide = ref('0')
const carbonDioxide = ref('0')
const nitrogen = ref('0')
const nonHydrocarbonCorrectionMethod = ref('Wichert-Aziz 修正方法')
const gasDeviationFactorMethod = ref('Dranchuk-Abu-Kassem 方法')
const gasViscosityMethod = ref('Lee-Gonzalez-Eakin 方法')
const nonHydrocarbonCorrectionMethodOptions = ['Wichert-Aziz 修正方法', 'Carr-Kobayashi-Burrous 修正方法']
const gasDeviationFactorMethodOptions = ['Dranchuk-Abu-Kassem 方法', 'Dranchuk-Purvis-Robinson 方法', 'Hall-Yarborough 方法']
const gasViscosityMethodOptions = ['Lee-Gonzalez-Eakin 方法', 'Carr-Kobayashi-Burrous 方法', 'Sutton 方法']
const pvtDetailLoading = ref(false)
const formationPermeability = ref('15.1063')
const formationThickness = ref('221.93')
const skinFactor = ref('0')
const drainageRadius = ref('631.12')
const wellboreRadius = ref('0.09')
const horizontalSectionLength = ref('0')
const originalFormationPressure = ref('56')
const formationTemperature = ref('120')

const darcyFlowCoefficient = ref('0')
const nonDarcyFlowCoefficient = ref('0')

const handleCalculate = () => {
  darcyFlowCoefficient.value = '1.4676E+3'
  nonDarcyFlowCoefficient.value = '2.4004E+0'
  activePanelTab.value = 'output'
}

const availablePvtOptions = computed(() => props.pvtTableOptions.map(option =>
  typeof option === 'object'
    ? { value: String(option.value), label: option.label }
    : { value: String(option), label: String(option) }
))

const resetPvtFields = () => {
  gasType.value = '干气'
  specificGravity.value = '0'
  hydrogenSulfide.value = '0'
  carbonDioxide.value = '0'
  nitrogen.value = '0'
  nonHydrocarbonCorrectionMethod.value = 'Wichert-Aziz 修正方法'
  gasDeviationFactorMethod.value = 'Dranchuk-Abu-Kassem 方法'
  gasViscosityMethod.value = 'Lee-Gonzalez-Eakin 方法'
}

const parseSettings = value => {
  if (!value) return {}
  if (typeof value === 'object') return value
  try { return JSON.parse(value) } catch { return {} }
}

const displayValue = value => value === null || value === undefined || value === '' ? '0' : String(value)

const loadSelectedPvt = async () => {
  resetPvtFields()
  if (!selectedPvtTable.value) return
  pvtDetailLoading.value = true
  try {
    const response = await pvtStorageApi.getDetail(
      selectedPvtTable.value, props.projectId, props.gasReservoirId, props.wellName
    )
    const detail = response?.data ?? response ?? {}
    const input = detail.gasInput || {}
    const settings = parseSettings(detail.settings?.gas)
    gasType.value = input.gasType || '干气'
    specificGravity.value = displayValue(input.specificGravity)
    hydrogenSulfide.value = displayValue(input.hydrogenSulfide)
    carbonDioxide.value = displayValue(input.carbonDioxide)
    nitrogen.value = displayValue(input.nitrogen)
    nonHydrocarbonCorrectionMethod.value = settings.gasCorrectionMethod || 'Wichert-Aziz 修正方法'
    gasDeviationFactorMethod.value = settings.deviationFactorMethod || 'Dranchuk-Abu-Kassem 方法'
    gasViscosityMethod.value = settings.viscosityMethod || 'Lee-Gonzalez-Eakin 方法'
  } catch (error) {
    resetPvtFields()
    ElMessage.warning(error?.msg || error?.message || 'PVT性质明细读取失败')
  } finally {
    pvtDetailLoading.value = false
  }
}

const emptyRows = Array.from({ length: 18 }, (_, index) => ({
  index: index + 1,
  pressure: '',
  flowPressure: '',
  production: ''
}))

watch(() => props.wellName, () => {
  selectedPvtTable.value = ''
  resetPvtFields()
})

watch(() => props.defaultWellType, value => {
  wellType.value = value === 'horizontal' ? 'horizontal' : 'vertical'
})
</script>

<template>
  <div class="theoretical-unstable-productivity-wrap">
    <aside class="params-panel" :class="{ collapsed: paramsCollapsed }">
      <button
        v-if="paramsCollapsed"
        class="panel-collapsed-tab"
        type="button"
        title="展开参数设置"
        @click="paramsCollapsed = false"
      >
        参数设置
      </button>

      <template v-else>
        <div class="panel-head">
          <span>参数设置</span>
          <button
            class="panel-toggle"
            type="button"
            title="收起参数设置"
            aria-label="收起参数设置"
            @click="paramsCollapsed = true"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true">
              <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
            </svg>
          </button>
        </div>

        <div class="well-type-tabs" aria-label="井型选择">
          <button type="button" :class="{ active: wellType === 'vertical' }" @click="wellType = 'vertical'">直井</button>
          <button type="button" :class="{ active: wellType === 'horizontal' }" @click="wellType = 'horizontal'">水平井</button>
        </div>

        <div v-show="activePanelTab === 'input'" class="panel-body">
          <div class="field calculation-method-field">
            <label>计算方法</label>
            <div class="calculation-method-options">
              <label><input v-model="calculationMethod" type="radio" value="拟压力" />拟压力</label>
              <label><input v-model="calculationMethod" type="radio" value="压力平方方法" />压力平方方法</label>
              <label><input v-model="calculationMethod" type="radio" value="压力法" />压力法</label>
            </div>
          </div>

          <div class="field-grid">
            <div class="field">
              <label>选择PVT表</label>
              <el-select v-model="selectedPvtTable" size="small" placeholder="请选择" :loading="pvtDetailLoading" style="width: 100%" @change="loadSelectedPvt">
                <el-option v-for="option in availablePvtOptions" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </div>
          </div>

          <div class="section-title">气体性质</div>
          <div class="field-grid">
            <div class="field">
              <label>天然气类型</label>
              <el-select v-model="gasType" size="small" disabled style="width: 100%">
                <el-option :label="gasType" :value="gasType" />
              </el-select>
            </div>
            <div class="field"><label>天然气比重(dless)</label><el-input v-model="specificGravity" size="small" readonly /></div>
            <div class="field"><label>H₂S摩尔百分含量(%)</label><el-input v-model="hydrogenSulfide" size="small" readonly /></div>
            <div class="field"><label>CO₂摩尔百分含量(%)</label><el-input v-model="carbonDioxide" size="small" readonly /></div>
            <div class="field"><label>N₂摩尔百分含量(%)</label><el-input v-model="nitrogen" size="small" readonly /></div>
          </div>

          <div class="section-title">计算方法</div>
          <div class="field-grid">
            <div class="field"><label>非烃气体修正方法</label><el-select v-model="nonHydrocarbonCorrectionMethod" size="small" style="width: 100%"><el-option v-for="option in nonHydrocarbonCorrectionMethodOptions" :key="option" :label="option" :value="option" /></el-select></div>
            <div class="field"><label>天然气偏差系数计算方法</label><el-select v-model="gasDeviationFactorMethod" size="small" style="width: 100%"><el-option v-for="option in gasDeviationFactorMethodOptions" :key="option" :label="option" :value="option" /></el-select></div>
            <div class="field"><label>天然气粘度计算方法</label><el-select v-model="gasViscosityMethod" size="small" style="width: 100%"><el-option v-for="option in gasViscosityMethodOptions" :key="option" :label="option" :value="option" /></el-select></div>
          </div>

          <div class="section-title">物性数据</div>
          <div class="field-grid">
            <div class="field"><label>产层渗透率(mD)</label><el-input v-model="formationPermeability" size="small" inputmode="decimal" /></div>
            <div class="field"><label>产层厚度(m)</label><el-input v-model="formationThickness" size="small" inputmode="decimal" /></div>
            <div class="field"><label>表皮系数(dless)</label><el-input v-model="skinFactor" size="small" inputmode="decimal" /></div>
          </div>

          <div class="section-title">其他数据</div>
          <div class="field-grid">
            <div class="field"><label>泄气半径(m)</label><el-input v-model="drainageRadius" size="small" inputmode="decimal" /></div>
            <div class="field"><label>井筒半径(m)</label><el-input v-model="wellboreRadius" size="small" inputmode="decimal" /></div>
            <div class="field"><label>水平段长度(m)</label><el-input v-model="horizontalSectionLength" size="small" inputmode="decimal" /></div>
            <div v-if="wellType === 'horizontal'" class="field"><label>原始地层压力(MPa)</label><el-input v-model="originalFormationPressure" size="small" inputmode="decimal" /></div>
            <div class="field"><label>地层温度(℃)</label><el-input v-model="formationTemperature" size="small" inputmode="decimal" /></div>
          </div>

          <button type="button" class="calculate-button" @click="handleCalculate">计算</button>
        </div>

        <div v-show="activePanelTab === 'output'" class="panel-body">
          <div class="section-title">输出结果</div>
          <div class="field-grid">
            <div class="field"><label>达西渗流项系数A([(MPa²/(mPa·s))]/(10⁴m³/d))</label><el-input v-model="darcyFlowCoefficient" size="small" readonly /></div>
            <div class="field"><label>非达西渗流项系数B([(MPa²/(mPa·s))]/(10⁴m³/d)²)</label><el-input v-model="nonDarcyFlowCoefficient" size="small" readonly /></div>
          </div>
        </div>

        <div class="panel-tabs">
          <button type="button" :class="{ active: activePanelTab === 'input' }" @click="activePanelTab = 'input'">输入</button>
          <button type="button" :class="{ active: activePanelTab === 'output' }" @click="activePanelTab = 'output'">输出</button>
        </div>
      </template>
    </aside>

    <main class="result-area">
      <div class="analysis-title-tabs">
        <button type="button" class="analysis-title-tab active">
          <span>理论计算-不稳定流</span>
        </button>
      </div>

      <div v-show="activeResultTab === 'chart'" class="chart-placeholder" aria-label="理论计算不稳定流结果分析图">
        <svg viewBox="0 0 1000 620" preserveAspectRatio="none" aria-hidden="true">
          <g class="grid-lines">
            <line v-for="x in [150, 300, 450, 600, 750, 900]" :key="'x-' + x" :x1="x" y1="40" :x2="x" y2="540" />
            <line v-for="y in [40, 140, 240, 340, 440, 540]" :key="'y-' + y" x1="74" :y1="y" x2="960" :y2="y" />
          </g>
          <g class="axes">
            <line x1="74" y1="40" x2="74" y2="540" />
            <line x1="74" y1="540" x2="960" y2="540" />
          </g>
          <text x="515" y="595" text-anchor="middle">产气量(10⁴m³/d)</text>
          <text x="24" y="295" text-anchor="middle" transform="rotate(-90 24 295)">井底流压(MPa)</text>
        </svg>
      </div>

      <div v-show="activeResultTab === 'table'" class="data-list-panel">
        <el-table :data="emptyRows" size="small" height="100%" border>
          <el-table-column prop="index" label="序号" width="70" />
          <el-table-column prop="pressure" label="地层压力(MPa)" min-width="150" />
          <el-table-column prop="flowPressure" label="井底流压(MPa)" min-width="150" />
          <el-table-column prop="production" label="产气量(10⁴m³/d)" min-width="170" />
        </el-table>
      </div>

      <div class="chart-tabs">
        <button type="button" :class="{ active: activeResultTab === 'table' }" @click="activeResultTab = 'table'">数据列表</button>
        <button type="button" :class="{ active: activeResultTab === 'chart' }" @click="activeResultTab = 'chart'">结果分析图</button>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.theoretical-unstable-productivity-wrap {
  height: 100%;
  min-height: 0;
  display: flex;
  overflow: hidden;
  background: #fff;
}

.calculation-method-field { margin-bottom: 12px; }

.calculation-method-options {
  min-height: 28px;
  display: flex;
  align-items: center;
  gap: 14px;

  label {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    white-space: nowrap;
    cursor: pointer;
  }

  input[type='radio'] {
    width: 13px;
    height: 13px;
    margin: 0;
    appearance: none;
    border: 1px solid #454545;
    border-radius: 50%;
    background: #fff;
    cursor: pointer;

    &:checked {
      background: #333;
      box-shadow: inset 0 0 0 3px #fff;
    }
  }
}

.calculate-button {
  min-width: 86px;
  height: 30px;
  margin: 14px 0 4px;
  padding: 0 22px;
  border: 1px solid #d5b900;
  border-radius: 4px;
  background: #f4d000;
  color: #222;
  cursor: pointer;
}

.params-panel {
  width: 280px;
  min-width: 280px;
  flex: 0 0 280px;
  min-height: 0;
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid #d7d7d7;
  background: #fff;
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

.panel-head {
  height: 34px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  border-bottom: 1px solid #d7d7d7;
  background: #f2f2f2;
  color: #333;
  font-size: 13px;
}

.panel-toggle {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  cursor: pointer;

  &:hover { background: #fff8d8; }
}

.panel-collapsed-tab {
  width: 100%;
  height: 76px;
  padding: 8px 0 0;
  border: 0;
  border-bottom: 1px solid #e2e6ea;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  background: #fff;
  color: #333;
  font: inherit;
  font-size: 13px;
  writing-mode: vertical-rl;
  text-orientation: upright;
  line-height: 1.05;
  cursor: pointer;

  &:hover {
    background: #fff8d8;
    box-shadow: inset -2px 0 0 #f4d000;
  }
}

.well-type-tabs {
  height: 44px;
  padding: 7px 12px 0;
  display: flex;
  align-items: flex-start;
  flex-shrink: 0;
  box-sizing: border-box;
  background: #fff;

  button {
    min-width: 94px;
    height: 30px;
    padding: 0 12px;
    border: 1px solid #222;
    border-right: 0;
    background: #fff;
    color: #222;
    font: inherit;
    cursor: pointer;

    &:last-child { border-right: 1px solid #222; }
    &.active { background: #f4d000; color: #111; font-weight: 700; }
    &:focus-visible { outline: 2px solid #2f74c0; outline-offset: 2px; }
  }
}

.panel-body {
  flex: 1;
  min-height: 0;
  padding: 4px 12px 14px;
  overflow-y: auto;
}

.section-title {
  height: 22px;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 0 7px;
  color: #333;
  font-size: 13px;
  font-weight: 500;

  &:first-child { margin-top: 4px; }

  &::after {
    content: '';
    flex: 1;
    height: 1px;
    background: #999;
  }
}

.field {
  margin-bottom: 9px;

  label {
    display: block;
    margin-bottom: 3px;
    color: #555;
    font-size: 12px;
  }
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  column-gap: 24px;
}

:deep(.el-input__wrapper),
:deep(.el-select__wrapper) {
  min-height: 24px;
  border-radius: 3px;
  box-shadow: 0 0 0 1px #aaa inset;
  font-size: 13px;
}

:deep(.el-input__wrapper.is-focus),
:deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px #b99500 inset, 0 0 0 3px rgba(242, 200, 17, 0.16);
}

.button-row {
  display: flex;
  gap: 8px;
}

.panel-tabs {
  height: 30px;
  display: flex;
  flex-shrink: 0;
  border-top: 1px solid #e0e0e0;

  button {
    flex: 1;
    border: 0;
    border-right: 1px solid #e0e0e0;
    background: #fff;
    color: #333;
    font: inherit;
    font-size: 13px;
    cursor: pointer;

    &:last-child { border-right: 0; }
    &.active { background: #f4d000; color: #111; font-weight: 600; }
  }
}

.result-area {
  flex: 1;
  min-width: 0;
  min-height: 0;
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
}

.analysis-title-tabs {
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}

.analysis-title-tab {
  height: 34px;
  max-width: 420px;
  padding: 0 12px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  background: #f4d000;
  color: #202020;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chart-placeholder,
.data-list-panel {
  flex: 1;
  min-height: 0;
}

.chart-placeholder {
  width: 100%;

  svg { width: 100%; height: 100%; }
  text { fill: #555; font-size: 14px; font-family: "Microsoft YaHei", sans-serif; }
  .grid-lines line { stroke: #eceff3; stroke-width: 1; }
  .axes line { stroke: #777; stroke-width: 1.2; }
}

.data-list-panel {
  width: 100%;
  overflow: hidden;
  background: #fff;
}

.chart-tabs {
  height: 30px;
  flex: 0 0 30px;
  display: flex;
  align-items: flex-end;
  border-top: 1px solid #e0e0e0;

  button {
    height: 30px;
    min-width: 82px;
    padding: 0 14px;
    border: 0;
    border-top: 2px solid transparent;
    border-right: 1px solid #e0e0e0;
    background: #fff;
    color: #333;
    font: inherit;
    font-size: 13px;
    cursor: pointer;

    &.active {
      border-top-color: #f4d000;
      background: #fff;
      color: #111;
      font-weight: 600;
    }
  }
}
</style>
