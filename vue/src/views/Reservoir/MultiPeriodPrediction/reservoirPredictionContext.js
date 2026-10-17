import { reactive } from 'vue'

// 页面间共享未保存的参数和真实接口结果，刷新后清空；不创建数据库记录。
const scopes = new Map()

export function getReservoirPredictionContext(projectId, gasReservoirId, storageId) {
  const key = [projectId, gasReservoirId, storageId].map(String).join(':')
  if (!scopes.has(key)) {
    scopes.set(key, reactive({
      predictionId: null,
      constraintDraft: null,
      appliedAt: null,
      objectiveQ: '',
      liveSolutions: [],
      nextSolutionNo: 1
    }))
  }
  return scopes.get(key)
}

// 字段、选项和默认值与用户最初上传的库级 ConstraintConditions.vue 完全一致。
export function originalConstraintForm() {
  return {
    gasProperties: {
      gasType: '干气', dless: '0.7336', h2sContent: '14.62',
      co2Content: '8.96', n2Content: '0'
    },
    calcMethod: {
      correctionMethod: 'Wichert-Aziz 修正方法',
      deviationMethod: 'Dranchuk-Abu-Kassem 方法',
      viscosityMethod: 'Lee-Gonzalez-Eakin 方法'
    },
    otherData: {
      productionEquationType: '压力形式',
      initialPressure: '55.634', reservoirTemperature: '120',
      materialBalanceType: '定容气藏', targetProduction: '77.8337',
      forecastStartDate: '2014-01-15', forecastTimeStep: '30',
      forecastTotalDays: '240', wellheadExportPressure: '9',
      abandonmentRate: '4'
    }
  }
}

export function copyConstraintForm(form) {
  return {
    gasProperties: { ...form.gasProperties },
    calcMethod: { ...form.calcMethod },
    otherData: { ...form.otherData }
  }
}
