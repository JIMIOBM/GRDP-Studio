export const newSegment = index => ({ name: `管段${index + 1}`, lengthM: 1500, diameterMm: 100,
  roughnessMm: 0.03, elevationChangeM: 0, ambientC: 15, heatTransferWm2K: 2 })
export const newEquipment = count => ({ name: `阀门${count + 1}`, type: 'valve', afterSegment: 0,
  lossK: 8, pressureRatio: 1.2, efficiency: 0.75, maxPressureMpa: 10, maxPowerKw: 500 })
// New wells start with missing engineering inputs, never another well's example data.
export const createPipelineInput = () => ({ target: 'outlet', thermalMode: 'heat', frictionMethod: 'colebrook',
  inletMpa: null, outletMpa: null, rate10k: null, inletC: null, gasGravity: null, z: null,
  viscosityMpaS: null, cpJkgK: null, jtKmpa: null,
  standardPressurePa: 101325, standardTemperatureK: 293.15, standardZ: 1,
  segments: [], equipment: [],
  constraints: { waterState: 'unknown' }, boundary: null, thermalModel: null
})
export const inactiveConstraintKinds = Object.freeze(['erosion', 'freeze'])
export const statusLabels = { inactive: '本期暂不启用', pass: '满足', fail: '不满足', not_evaluated: '未评价', not_applicable: '不适用', risk: '进入形成区', equilibrium: '平衡边界', conditional: '条件性判断' }
export const fingerprint = input => JSON.stringify(input)
// Preserve unsaved work when switching modules/wells; never report this session cache as a DB save.
export const pipelineDrafts = new Map()
