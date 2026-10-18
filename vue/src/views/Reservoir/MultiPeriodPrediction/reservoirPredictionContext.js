// d:\shiyou\GRDP-Studio\vue\src\views\Reservoir\MultiPeriodPrediction\reservoirPredictionContext.js
/**
 * 库级多周期预测共享上下文 —— 不依赖 /docker-api 和原平台接口。
 * 仅管理约束条件暂存、方案预览列表等前端状态。
 */
import { reactive } from 'vue'

const contextMap = new Map()

function contextKey(projectId, gasReservoirId, storageId) {
  return [projectId, gasReservoirId, storageId].map(String).join(':')
}

export function getReservoirPredictionContext(projectId, gasReservoirId, storageId) {
  const key = contextKey(projectId, gasReservoirId, storageId)
  if (!contextMap.has(key)) {
    contextMap.set(key, reactive({
      constraintDraft: null,
      appliedAt: null,
      liveSolutions: [],
      nextSolutionNo: 1,
      predictionId: null,
      constraintWellResults: null,
      constraintBoundaries: null,
      constraintRecordId: null
    }))
  }
  return contextMap.get(key)
}

export function originalConstraintForm() {
  return {
    gasProperties: {
      gasType: '干气',
      dless: 0.7336,
      h2sContent: 14.62,
      co2Content: 8.96,
      n2Content: 0
    },
    calcMethod: {
      correctionMethod: 'Wichert-Aziz 修正方法',
      deviationMethod: 'Dranchuk-Abu-Kassem 方法',
      viscosityMethod: 'Lee-Gonzalez-Eakin 方法'
    },
    otherData: {
      productionEquationType: '压力形式',
      initialPressure: 55.634,
      reservoirTemperature: 120,
      materialBalanceType: '定容气藏',
      targetProduction: 61.5117,
      forecastStartDate: '2014-01-15',
      forecastTimeStep: 30,
      forecastTotalDays: 240,
      wellheadExportPressure: 9,
      abandonmentRate: 4
    }
  }
}

export function copyConstraintForm(form) {
  // 始终只携带截图中的约束表单字段；旧草稿中的伪造井筒默认参数一律丢弃。
  const clean = originalConstraintForm()
  const source = form && typeof form === 'object' ? form : {}
  for (const group of ['gasProperties', 'calcMethod', 'otherData']) {
    for (const field of Object.keys(clean[group])) {
      const value = source[group]?.[field]
      if (value !== null && value !== undefined) clean[group][field] = value
    }
  }
  return clean
}