import { supportsPressureScenario } from './wellParameterPreview.js'

// This is the existing platform editing bound, not a claimed PTK physical limit.
export const MAX_RESERVOIR_PRESSURE_PSI = 100000

export const buildPressureScenarioParameters = ({ enabled, modelKind, runType, pressure }) => {
  if (!enabled) return { parameters: null, fieldError: null, contextError: null }
  if (!supportsPressureScenario(modelKind, runType)) {
    return { parameters: null, fieldError: null, contextError: '当前模型或任务不支持地层压力方案，请切换任务。' }
  }
  if (!Number.isFinite(pressure) || pressure <= 0 || pressure > MAX_RESERVOIR_PRESSURE_PSI) {
    return { parameters: null, fieldError: '请输入大于 0、且不超过 100000 的压力值（psia，平台编辑范围）。', contextError: null }
  }
  return {
    parameters: { schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: pressure },
    fieldError: null,
    contextError: null
  }
}
