<!-- d:\shiyou\GRDP-Studio\vue\src\views\ProductionAllocation\MultiPeriodPrediction\ConstraintConditions.vue -->
<script setup>
import { ref, reactive, computed, watch, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { erosionApi } from '@/api/wellboreErosion'
import { sandProductionApi } from '@/api/sandProduction'
import { liquidLoadingApi } from '@/api/wellboreRisk'
import { scopeKey } from '@/api/solutionGeneration'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  wellName: { type: String, required: true }
})
const emit = defineEmits(['constraints-calculated', 'constraints-invalidated'])

const formData = reactive({
  gasProperties: {
    gasType: '干气', dless: '0.58', h2sContent: '4.62',
    co2Content: '3.96', n2Content: '0'
  },
  calcMethod: {
    correctionMethod: 'Wichert-Aziz 修正方法',
    deviationMethod: 'Dranchuk-Abu-Kassem 方法',
    viscosityMethod: 'Lee-Gonzalez-Eakin 方法'
  },
  pipeData: {
    wellboreStructure: '情况4', mdDepth: '3596.95', tvdDepth: '3575.11',
    casingInnerDiameter: '124.46', tubingMdDepth: '3516.8',
    tubingTvdDepth: '3504.47', tubingInnerDiameter: '57.72'
  },
  otherData: {
    coefficientA: '3.1477E-2', coefficientB: '1.6793E-5',
    initialPressure: '50', reservoirTemperature: '80', wellheadTemperature: '20',
    dynamicReserve: '24.4552', productionRate: '31.5058',
    cumulativeProduction: '13.1084', materialBalanceType: '定容气藏',
    targetProduction: '31.5058', forecastStartDate: '2014-01-15',
    forecastTimeStep: '30', forecastTotalDays: '240',
    wellheadExportPressure: '9', abandonmentRate: '4'
  }
})

const isCalculating = ref(false)
const calculationResults = ref(null)
const calculationMeta = ref(null)
const selectedConstraints = reactive({
  erosionLimit: true, sandProductionLimit: true, liquidLoadingLimit: true
})
const selectedKeys = ['erosionLimit', 'sandProductionLimit', 'liquidLoadingLimit']
const pvtInputSignature = () => JSON.stringify({ ...formData.gasProperties, ...formData.calcMethod })
const initialPvtInputSignature = pvtInputSignature()
const pvtInputChanged = () => pvtInputSignature() !== initialPvtInputSignature
let requestVersion = 0
let unmounted = false

const scope = () => ({
  projectId: Number(props.projectId),
  gasReservoirId: Number(props.gasReservoirId),
  wellName: props.wellName
})
const parseJson = value => {
  if (typeof value !== 'string') return value
  try { return JSON.parse(value) } catch { return null }
}
const unwrap = value => {
  let current = parseJson(value)
  for (let i = 0; i < 3 && current && typeof current === 'object' &&
    !Array.isArray(current) && Object.prototype.hasOwnProperty.call(current, 'data'); i++) {
    current = parseJson(current.data)
  }
  return current
}
const asNumber = (value, label, { zero = false, temperature = false } = {}) => {
  if (value == null || String(value).trim() === '' || !Number.isFinite(Number(value))) {
    throw new Error(`${label}不是有效数字`)
  }
  const n = Number(value)
  if (temperature ? n <= -273.15 : (zero ? n < 0 : n <= 0)) {
    throw new Error(`${label}超出允许范围`)
  }
  return n
}
const optionalNumber = value => value == null || value === '' || !Number.isFinite(Number(value))
  ? null : Number(value)
const positive = value => {
  const n = optionalNumber(value)
  return n !== null && n > 0 ? n : null
}
const errorText = error => error?.msg || error?.message || '算法接口未返回有效结果'
const clone = value => JSON.parse(JSON.stringify(value))

// 与方案生成页共享本井的最新计算快照；无须依赖父组件额外转发事件。
function syncConstraintSnapshot(payload) {
  if (typeof window === 'undefined') return
  const key = scopeKey(scope())
  try {
    const storageKey = `grdp:constraints:v1:${key}`
    if (payload) window.sessionStorage.setItem(storageKey, JSON.stringify(payload))
    else window.sessionStorage.removeItem(storageKey)
    window.dispatchEvent(new CustomEvent('grdp:constraints-updated', {
      detail: { scopeKey: key }
    }))
  } catch (error) {
    console.warn('[约束条件] 同步计算快照失败，仍会通过 Vue 事件通知父组件', error)
  }
}
function invalidate(clearCurrentSnapshot = true) {
  ++requestVersion
  isCalculating.value = false
  calculationResults.value = null
  calculationMeta.value = null
  if (clearCurrentSnapshot) syncConstraintSnapshot(null)
  emit('constraints-invalidated')
}
watch(formData, () => invalidate(true), { deep: true, flush: 'sync' })
// 切换井时不删除上一口井的有效缓存，只重置当前组件中的计算状态。
watch(() => [props.projectId, props.gasReservoirId, props.wellName], () => invalidate(false), { flush: 'sync' })
onBeforeUnmount(() => { unmounted = true; ++requestVersion })

const productionRange = computed(() => {
  const results = calculationResults.value
  if (!results) return null
  const selected = selectedKeys.filter(key => selectedConstraints[key])
  const missing = selected.filter(key => !results[key]?.available)
  const unverified = selected.filter(key => results[key]?.available && results[key]?.verified === false)
  const lower = selectedConstraints.liquidLoadingLimit && results.liquidLoadingLimit?.available
    ? results.liquidLoadingLimit.value : null
  const candidates = ['erosionLimit', 'sandProductionLimit']
    .filter(key => selectedConstraints[key] && results[key]?.available)
    .map(key => results[key].value)
  const upper = candidates.length ? Math.min(...candidates) : null
  const feasible = lower === null || upper === null || lower <= upper
  return {
    lower, upper, feasible, missing, unverified,
    complete: selected.length > 0 && missing.length === 0 && unverified.length === 0
  }
})
const formatBound = value => value == null || !Number.isFinite(Number(value))
  ? '未取得' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 })

function publishResults() {
  if (!calculationResults.value || !calculationMeta.value) return
  const range = productionRange.value
  const cr = calculationResults.value
  const pressureReady = cr.wellheadExportPressure?.available && cr.wellheadExportPressure?.verified
  const fourReady = range.complete && pressureReady
  const payload = {
    ...scope(),
    inputs: clone(formData),
    selectedConstraints: { ...selectedConstraints },
    constraintResults: clone(cr),
    erosionLimit: clone(cr.erosionLimit),
    sandProductionLimit: clone(cr.sandProductionLimit),
    liquidLoadingLimit: clone(cr.liquidLoadingLimit),
    wellheadExportPressure: clone(cr.wellheadExportPressure),
    // 显式提供四个边界值：三项是计算气量阈值，一项是用户设定的井口压力边界。
    boundaryConditions: {
      erosionLimit: cr.erosionLimit.verified ? cr.erosionLimit.value : null,
      sandProductionLimit: cr.sandProductionLimit.verified ? cr.sandProductionLimit.value : null,
      liquidLoadingLimit: cr.liquidLoadingLimit.verified ? cr.liquidLoadingLimit.value : null,
      wellheadExportPressure: pressureReady ? cr.wellheadExportPressure.value : null
    },
    lowerBound: range.lower,
    upperBound: range.upper,
    complete: fourReady,
    feasible: range.feasible,
    usableForPlan: fourReady && range.feasible,
    pending: [...range.missing, ...range.unverified, ...(pressureReady ? [] : ['wellheadExportPressure'])],
    outletPressureMpa: cr.wellheadExportPressure.value,
    // 预测曲线只有地层压力，尚不能据此校验实际井口压力是否达到外输要求。
    pressureConstraintPendingCurve: true,
    source: clone(calculationMeta.value)
  }
  syncConstraintSnapshot(payload)
  emit('constraints-calculated', payload)
}
watch(selectedConstraints, publishResults, { deep: true, flush: 'sync' })

async function savedModelInput(api, currentScope, isErosion = false) {
  const args = isErosion ? [currentScope] : [currentScope.projectId, currentScope.gasReservoirId, currentScope.wellName]
  const listing = unwrap(await api.list(...args))
  const records = Array.isArray(listing) ? listing : (listing?.records ?? listing?.rows ?? listing?.list ?? [])
  if (!Array.isArray(records) || records.length === 0) {
    throw new Error('当前井没有该算法的基础参数记录；请先在该算法原页面保存一次输入')
  }
  const latest = [...records].sort((a, b) => Number(b.calculationNo ?? b.calculation_no ?? b.id ?? 0) -
    Number(a.calculationNo ?? a.calculation_no ?? a.id ?? 0))[0]
  if (latest?.id == null) throw new Error('算法基础记录缺少 ID')
  const detail = unwrap(await api.detail(latest.id, ...args))
  const record = detail?.record ?? detail
  const input = parseJson(record?.input_json ?? record?.inputJson ?? record?.input)
  if (!input || Array.isArray(input) || typeof input !== 'object') {
    throw new Error('最新记录没有可用的模型输入参数')
  }
  return input
}

function currentInput() {
  const currentScope = scope()
  if (!Number.isSafeInteger(currentScope.projectId) || currentScope.projectId <= 0 ||
      !Number.isSafeInteger(currentScope.gasReservoirId) || currentScope.gasReservoirId <= 0 ||
      !currentScope.wellName?.trim()) throw new Error('项目、气藏或井号不完整')
  const gasParts = [formData.gasProperties.h2sContent, formData.gasProperties.co2Content, formData.gasProperties.n2Content]
    .map((v, i) => asNumber(v, ['H₂S含量', 'CO₂含量', 'N₂含量'][i], { zero: true }))
  if (gasParts.some(v => v > 100) || gasParts.reduce((s, n) => s + n, 0) >= 100) {
    throw new Error('非烃气体组分百分比无效')
  }
  return {
    scope: currentScope,
    rate: asNumber(formData.otherData.productionRate, '产气量', { zero: true }),
    diameterMm: asNumber(formData.pipeData.tubingInnerDiameter, '油管内径'),
    gasGravity: asNumber(formData.gasProperties.dless, '天然气比重'),
    pressureMpa: asNumber(formData.otherData.initialPressure, '原始地层压力'),
    temperatureC: asNumber(formData.otherData.reservoirTemperature, '地层温度', { temperature: true }),
    exportPressure: asNumber(formData.otherData.wellheadExportPressure, '井口外输压力')
  }
}

function item(name, formula, value, { verified = true, reason = '', source = '', referenceValue = null } = {}) {
  const valid = value !== null && Number.isFinite(Number(value)) && Number(value) >= 0
  return {
    name, formula, value: valid ? Number(value) : null,
    available: valid, verified: valid && verified === true,
    reason, source, referenceValue,
    unit: '10⁴m³/d'
  }
}
function failed(name, formula, error) {
  return item(name, formula, null, { verified: false, reason: errorText(error), source: '本次未成功计算' })
}

async function calculateErosion(current, old) {
  if (!old) throw new Error('冲蚀算法缺少历史专业参数/PVT方案')
  const pvtId = asNumber(old.pvtId, '冲蚀PVT方案ID')
  const props = unwrap(await erosionApi.properties({
    ...current.scope, pvtId, pressureMpa: current.pressureMpa, temperatureC: current.temperatureC
  }))
  const pvt = props?.properties ?? props
  if (!pvt || typeof pvt !== 'object') throw new Error('PVT接口没有返回气体物性')
  const request = {
    ...current.scope,
    pvtId,
    pvtSnapshot: pvt.pvtSnapshot ?? parseJson(old.pvtSnapshot) ?? null,
    pressureMpa: current.pressureMpa,
    temperatureC: current.temperatureC,
    actualGasRate1e4M3d: current.rate,
    tubingInnerDiameterMm: current.diameterMm,
    gasDensityKgM3: asNumber(pvt.gasDensityKgM3, '新工况气体密度'),
    liquidDensityKgM3: asNumber(pvt.liquidDensityKgM3, '新工况液体密度'),
    gasVolumeFactor: asNumber(pvt.gasVolumeFactor, '新工况气体体积系数'),
    liquidHoldupPercent: asNumber(old.liquidHoldupPercent, '冲蚀持液率', { zero: true }),
    sandContentPercent: asNumber(old.sandContentPercent, '冲蚀含砂率', { zero: true }),
    sandDensityKgM3: asNumber(old.sandDensityKgM3, '砂粒密度')
  }
  const rawResponse = await erosionApi.calculate(request)
  const response = unwrap(rawResponse)
  const result = response?.result ?? response
  if (!result || typeof result !== 'object') {
    throw new Error('冲蚀calculate没有返回结果对象')
  }
  const limit = positive(result.criticalGasRate)
  if (limit === null) throw new Error('本次冲蚀calculate未返回 criticalGasRate')
  return item('冲蚀气量上限', 'qₑ', limit, {
    verified: !pvtInputChanged(),
    source: '本次冲蚀calculate返回的 criticalGasRate',
    reason: pvtInputChanged() ? '气体组成/物性方法已变更，但后端仍使用旧PVT方案；数值仅供参考，需先同步PVT' : ''
  })
}
async function calculateSand(current, old) {
  if (!old) throw new Error('出砂算法缺少历史支撑剂/裂缝专业参数')
  const request = {
    ...current.scope,
    proppantType: old.proppantType,
    massT: asNumber(old.massT, '支撑剂质量'),
    densityGCm3: old.densityGCm3 == null ? null : asNumber(old.densityGCm3, '堆积密度'),
    halfLengthM: asNumber(old.halfLengthM, '裂缝半长'),
    closurePressureMpa: asNumber(old.closurePressureMpa, '闭合压力'),
    criticalVelocityOverrideMS: old.criticalVelocityOverrideMS == null || old.criticalVelocityOverrideMS === ''
      ? null : asNumber(old.criticalVelocityOverrideMS, '人工临界流速'),
    actualRate1e4M3d: current.rate
  }
  if (!request.proppantType) throw new Error('缺少支撑剂类型')
  const result = unwrap(await sandProductionApi.calculate(request))
  const limit = positive(result?.criticalRate1e4M3d)
  if (limit === null) throw new Error('本次出砂calculate未返回 criticalRate1e4M3d')
  return item('出砂气量上限', 'qₛ', limit, {
    source: '本次出砂calculate；裂缝和闭合压力取同井已保存专业输入',
    reason: '原始地层压力不是出砂DTO中的闭合压力，二者不会被自动等同'
  })
}

async function calculateLiquid(current, old) {
  if (!old) throw new Error('积液算法缺少历史界面张力/PVT专业参数')
  const sigma = old.surfaceTensionMnM ?? old.sigma
  const request = {
    ...current.scope,
    qg: current.rate,
    qw: old.qw == null ? null : asNumber(old.qw, '日产水量', { zero: true }),
    pressureMpa: current.pressureMpa,
    temperatureC: current.temperatureC,
    gasSpecificGravity: current.gasGravity,
    liquidDensityKgM3: null,
    surfaceTensionMnM: asNumber(sigma, '气液界面张力'),
    tubingIdMm: current.diameterMm,
    pvtId: old.pvtId ?? null,
    pvtSnapshot: parseJson(old.pvtSnapshot) ?? null
  }
  const result = unwrap(await liquidLoadingApi.calculate(request))
  const limit = positive(result?.criticalRate1e4M3d ?? result?.qCritical)
  if (limit === null) throw new Error('本次积液calculate未返回临界携液气量')
  const historicalGravity = optionalNumber(old.gasSpecificGravity)
  const pvtDiffers = historicalGravity !== null &&
    Math.abs(historicalGravity - current.gasGravity) > 1e-5
  return item('积液气量下限', 'qₘᵢₙ', limit, {
    verified: !pvtDiffers && !pvtInputChanged(),
    source: '本次积液calculate；当前P/T由后端更新PVT物性',
    reason: pvtDiffers || pvtInputChanged()
      ? '天然气比重、组成或物性方法与原PVT输入未同步；后端以既有PVT来源计算，本次数值仅供参考'
      : ''
  })
}

async function handleCalculateConstraints() {
  if (isCalculating.value) return
  let current
  try { current = currentInput() } catch (error) {
    ElMessage.warning(errorText(error)); return
  }
  const version = ++requestVersion
  isCalculating.value = true
  calculationResults.value = null
  calculationMeta.value = null
  // 新一轮计算尚未成功时，禁止方案生成继续沿用旧的四项边界。
  syncConstraintSnapshot(null)
  emit('constraints-invalidated')
  try {
    const APIs = [erosionApi, sandProductionApi, liquidLoadingApi]
    const initial = await Promise.allSettled(APIs.map((api, i) => savedModelInput(api, current.scope, i === 0)))
    if (unmounted || version !== requestVersion) return
    const taskResults = await Promise.allSettled([
      initial[0].status === 'fulfilled' ? calculateErosion(current, initial[0].value) : Promise.reject(initial[0].reason),
      initial[1].status === 'fulfilled' ? calculateSand(current, initial[1].value) : Promise.reject(initial[1].reason),
      initial[2].status === 'fulfilled' ? calculateLiquid(current, initial[2].value) : Promise.reject(initial[2].reason)
    ])
    if (unmounted || version !== requestVersion) return
    const boundNames = [
      ['冲蚀气量上限', 'qₑ'], ['出砂气量上限', 'qₛ'], ['积液气量下限', 'qₘᵢₙ']
    ]
    const bounds = taskResults.map((r, i) => r.status === 'fulfilled'
      ? r.value : failed(...boundNames[i], r.reason))
    calculationResults.value = {
      erosionLimit: bounds[0], sandProductionLimit: bounds[1], liquidLoadingLimit: bounds[2],
      wellheadExportPressure: {
        name: '井口外输压力', formula: 'Pₒᵤₜ', value: current.exportPressure,
        available: true, verified: true, unit: 'MPa', reason: '',
        source: '本页面输入的井口外输压力边界（非预测得到的地层压力）'
      }
    }
    calculationMeta.value = {
      evaluatedAt: new Date().toISOString(),
      calculationCondition: '按表单的原始地层压力和地层温度评价（初步筛选，非实时井口工况）',
      pressureMpa: current.pressureMpa, temperatureC: current.temperatureC,
      evaluatedRate1e4M3d: current.rate,
      warning: '气体组分、物性算法必须与同井PVT方案同步；井口外输压力在方案生成阶段校验。'
    }
    publishResults()
    const range = productionRange.value
    if (range.complete && range.feasible) ElMessage.success('三项气量阈值及井口压力边界已同步到方案生成')
  } catch (error) {
    if (!unmounted && version === requestVersion) ElMessage.error(errorText(error))
  } finally {
    if (version === requestVersion) isCalculating.value = false
  }
}
</script>

<template>
  <div class="constraint-conditions-workspace">
    <div class="result-tabs">
      <div
        class="result-tab"
        :title="`多周期预测 - 约束条件${props.wellName ? ' - ' + props.wellName : ''}`"
      >
        多周期预测 - 约束条件{{ props.wellName ? ` - ${props.wellName}` : '' }}
      </div>
    </div>

    <div class="content-area">
      <div class="section-title">参数设置</div>

      <fieldset class="field-section">
        <legend class="sec-label">气体性质</legend>
        <div class="parameter-grid cols-5">
          <label class="field">
            <span>天然气类型</span>
            <select v-model="formData.gasProperties.gasType">
              <option value="干气">干气</option>
              <option value="湿气">湿气</option>
              <option value="凝析气">凝析气</option>
            </select>
          </label>
          <label class="field">
            <span>天然气比重(dless)</span>
            <input v-model="formData.gasProperties.dless" type="number" step="0.01">
          </label>
          <label class="field">
            <span>H₂S摩尔百分含量(%)</span>
            <input v-model="formData.gasProperties.h2sContent" type="number" step="0.01">
          </label>
          <label class="field">
            <span>CO₂摩尔百分含量(%)</span>
            <input v-model="formData.gasProperties.co2Content" type="number" step="0.01">
          </label>
          <label class="field">
            <span>N₂摩尔百分含量(%)</span>
            <input v-model="formData.gasProperties.n2Content" type="number" step="0.01">
          </label>
        </div>
      </fieldset>

      <fieldset class="field-section">
        <legend class="sec-label">计算方法</legend>
        <div class="parameter-grid cols-3">
          <label class="field">
            <span>非烃气体修正方法</span>
            <select v-model="formData.calcMethod.correctionMethod">
              <option value="Wichert-Aziz 修正方法">Wichert-Aziz 修正方法</option>
              <option value="Carr-Kobayashi-Burrous 修正方法">Carr-Kobayashi-Burrous 修正方法</option>
            </select>
          </label>
          <label class="field">
            <span>天然气偏差系数计算方法</span>
            <select v-model="formData.calcMethod.deviationMethod">
              <option value="Dranchuk-Abu-Kassem 方法">Dranchuk-Abu-Kassem 方法</option>
              <option value="Dranchuk-Purvis-Robinson 方法">Dranchuk-Purvis-Robinson 方法</option>
              <option value="Hall-Yarborough 方法">Hall-Yarborough 方法</option>
            </select>
          </label>
          <label class="field">
            <span>天然气粘度计算方法</span>
            <select v-model="formData.calcMethod.viscosityMethod">
              <option value="Lee-Gonzalez-Eakin 方法">Lee-Gonzalez-Eakin 方法</option>
              <option value="Carr-Kobayashi-Burrous 方法">Carr-Kobayashi-Burrous 方法</option>
              <option value="Sutton 方法">Sutton 方法</option>
            </select>
          </label>
        </div>
      </fieldset>

      <fieldset class="field-section">
        <legend class="sec-label">管柱数据</legend>
        <div class="parameter-grid cols-6">
          <label class="field">
            <span>井身结构</span>
            <select v-model="formData.pipeData.wellboreStructure">
              <option value="情况4">情况4</option>
              <option value="情况1">情况1</option>
              <option value="情况2">情况2</option>
              <option value="情况3">情况3</option>
            </select>
          </label>
          <label class="field">
            <span>当前产层中深（MD）(m)</span>
            <input v-model="formData.pipeData.mdDepth" type="number" step="0.01">
          </label>
          <label class="field">
            <span>当前产层中深（TVD）(m)</span>
            <input v-model="formData.pipeData.tvdDepth" type="number" step="0.01">
          </label>
          <label class="field">
            <span>生产套管内径(mm)</span>
            <input v-model="formData.pipeData.casingInnerDiameter" type="number" step="0.01">
          </label>
          <label class="field">
            <span>油管下深（MD）(m)</span>
            <input v-model="formData.pipeData.tubingMdDepth" type="number" step="0.01">
          </label>
          <label class="field">
            <span>油管下深（TVD）(m)</span>
            <input v-model="formData.pipeData.tubingTvdDepth" type="number" step="0.01">
          </label>
          <label class="field">
            <span>油管内径(mm)</span>
            <input v-model="formData.pipeData.tubingInnerDiameter" type="number" step="0.01">
          </label>
        </div>
      </fieldset>

      <fieldset class="field-section">
        <legend class="sec-label">其它数据</legend>
        <div class="parameter-grid cols-5">
          <label class="field">
            <span>产能方程系数A(MPa/(10⁴m³/d))</span>
            <input v-model="formData.otherData.coefficientA" type="number" step="1e-6">
          </label>
          <label class="field">
            <span>产能方程系数B(MPa/(10⁴m³/d)²)</span>
            <input v-model="formData.otherData.coefficientB" type="number" step="1e-6">
          </label>
          <label class="field">
            <span>原始地层压力(MPa)</span>
            <input v-model="formData.otherData.initialPressure" type="number" step="0.1">
          </label>
          <label class="field">
            <span>地层温度(°C)</span>
            <input v-model="formData.otherData.reservoirTemperature" type="number" step="0.1">
          </label>
          <label class="field">
            <span>井口温度(°C)</span>
            <input v-model="formData.otherData.wellheadTemperature" type="number" step="0.1">
          </label>
          <label class="field">
            <span>动态地质储量(10⁸m³)</span>
            <input v-model="formData.otherData.dynamicReserve" type="number" step="0.0001">
          </label>
          <label class="field">
            <span>气产量(10⁴m³/d)</span>
            <input v-model="formData.otherData.productionRate" type="number" step="0.0001">
          </label>
          <label class="field">
            <span>累产气量(10⁸m³)</span>
            <input v-model="formData.otherData.cumulativeProduction" type="number" step="0.0001">
          </label>
          <label class="field">
            <span>物质平衡方程类型</span>
            <select v-model="formData.otherData.materialBalanceType">
              <option value="定容气藏">定容气藏</option>
              <option value="水驱气藏">水驱气藏</option>
            </select>
          </label>
          <label class="field">
            <span>单井目标产量(10⁴m³/d)</span>
            <input v-model="formData.otherData.targetProduction" type="number" step="0.0001">
          </label>
          <label class="field">
            <span>预测开始时间</span>
            <input v-model="formData.otherData.forecastStartDate" type="date">
          </label>
          <label class="field">
            <span>预测时间步长(d)</span>
            <input v-model="formData.otherData.forecastTimeStep" type="number" step="1">
          </label>
          <label class="field">
            <span>预测时间步数(d)</span>
            <input v-model="formData.otherData.forecastTotalDays" type="number" step="1">
          </label>
          <label class="field">
            <span>井口外输压力(MPa)</span>
            <input v-model="formData.otherData.wellheadExportPressure" type="number" step="0.1">
          </label>
          <label class="field">
            <span>单井废弃产量(10⁴m³/d)</span>
            <input v-model="formData.otherData.abandonmentRate" type="number" step="0.0001">
          </label>
        </div>
      </fieldset>

      <div class="form-actions">
        <button
          type="button"
          class="calculate-button"
          :disabled="isCalculating"
          @click="handleCalculateConstraints"
        >
          {{ isCalculating ? '计算中...' : '计算约束条件' }}
        </button>
      </div>

      <div class="section-title results-title">约束条件计算结果</div>

      <div v-if="calculationResults" class="results-container">
        <div class="results-grid">
          <div
            v-for="(result, key) in calculationResults"
            :key="key"
            class="result-card"
          >
            <div class="result-header">
              <span class="result-name">{{ result.name }}</span>
              <span class="result-formula">{{ result.formula }}</span>
            </div>
           <div class="result-value">
              <span class="value-number">{{ formatBound(result.value) }}</span>
              <span class="value-unit">{{ result.unit }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.constraint-conditions-workspace {
  flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column;
  overflow: hidden; background: #fff; color: #252525;
  font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif;
}

.result-tabs {
  height: 34px; flex: 0 0 34px; display: flex; align-items: center;
  border-bottom: 1px solid #e4e7ed; background: #fafafa;
}

.result-tab {
  height: 34px; line-height: 34px; max-width: min(430px, 100%);
  padding: 0 12px; border-right: 1px solid #e4e7ed; box-sizing: border-box;
  background: #f4d000; color: #202020; font-weight: 600;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 13px;
}

.content-area { flex: 1; min-height: 0; overflow-y: auto; padding: 16px 24px 24px; background: #fff; }

.section-title {
  display: flex; align-items: center; gap: 8px; margin: 0 0 12px;
  color: #1f2937; font-size: 15px; font-weight: 700; line-height: 22px; letter-spacing: 0.2px;
}

.section-title::before { width: 4px; height: 15px; flex: 0 0 4px; border-radius: 2px; background: #d5b900; content: ''; }

.results-title { margin-top: 24px; }

.field-section { border: 0; padding: 0; margin: 0 0 12px; min-width: 0; }

.sec-label {
  width: 100%; height: 22px; padding: 0; margin: 8px 0 7px;
  display: flex; align-items: center; gap: 8px; font-weight: 500; font-size: 13px; color: #252525;
}

.sec-label::after { content: ''; height: 1px; flex: 1; background: #999; }

.parameter-grid {
  display: grid; gap: 8px;

  &.cols-3 { grid-template-columns: repeat(3, 1fr); }
  &.cols-5 { grid-template-columns: repeat(5, 1fr); }
  &.cols-6 { grid-template-columns: repeat(6, 1fr); }
}

.field { display: block; min-width: 0; margin-bottom: 0; }

.field span {
  display: block; margin-bottom: 3px; color: #252525; font-size: 12px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.field input,
.field select {
  display: block; width: 100%; min-width: 0; height: 24px; padding: 0 8px;
  box-sizing: border-box; border: 1px solid #aaa; border-radius: 3px;
  background: #fff; color: #333; font: inherit; font-size: 12px;
}

.field input:focus-visible,
.field select:focus-visible { outline: 1px solid #d5b900; outline-offset: 1px; }

.form-actions { display: flex; align-items: center; gap: 10px; margin-top: 16px; }

.calculate-button {
  min-width: 110px; height: 30px; padding: 0 22px;
  border: 1px solid #d5b900; border-radius: 4px; background: #f4d000;
  color: #222; font: inherit; cursor: pointer; font-weight: 500;
}

.calculate-button:hover:not(:disabled) { background: #ffe033; }
.calculate-button:disabled { opacity: 0.6; cursor: wait; }

.empty-state { text-align: center; padding: 40px 20px; color: #909399; }
.empty-icon { font-size: 48px; margin-bottom: 12px; }
.empty-state p { margin: 6px 0; font-size: 14px; }
.empty-hint { font-size: 12px !important; color: #c0c4cc !important; }

.results-grid {
  display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px; margin-bottom: 20px;
}

.result-card {
  padding: 14px; background: #f8fafc; border: 1px solid #e4e7ed; border-radius: 4px;

  .result-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
  .result-name { font-size: 13px; font-weight: 600; color: #303133; }
  .result-formula { font-size: 11px; color: #909399; font-style: italic; }
  .result-value { display: flex; align-items: baseline; gap: 4px; margin-bottom: 4px; }
  .value-number { font-size: 22px; font-weight: 700; color: #d5b900; }
  .value-unit { font-size: 11px; color: #909399; }
  .result-desc { font-size: 11px; color: #909399; line-height: 1.5; }
}

.result-warning { font-size: 11px; color: #e6a23c; line-height: 1.5; margin-top: 4px; }

@media (max-width: 1200px) {
  .parameter-grid {
    &.cols-5, &.cols-6 { grid-template-columns: repeat(3, 1fr); }
  }
  .results-grid { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 768px) {
  .content-area { padding: 12px; }
  .parameter-grid {
    &.cols-3, &.cols-5, &.cols-6 { grid-template-columns: 1fr 1fr; }
  }
  .results-grid { grid-template-columns: 1fr; }
}
</style>