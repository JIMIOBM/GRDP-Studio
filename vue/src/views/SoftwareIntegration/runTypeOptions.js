const scopedWellSchemas = new Set([
  'pipesim-well-inspection/5', 'pipesim-well-inspection/6',
  'pipesim-well-inspection/7', 'pipesim-well-inspection/8'
])
const flowComponentSchemas = new Set([
  'pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8'
])
const espOptions = () => [
  { value: 'esp-parameter', label: 'ESP 参数方案' },
  { value: 'esp-curves', label: 'ESP 曲线' }
]
const trajectoryOptions = () => [{ value: 'trajectory', label: '井轨迹' }]
const networkOptions = performanceAvailable => [
  { value: 'network', label: '管网模拟' },
  { value: 'system-analysis', label: '系统分析' },
  { value: 'network-optimizer', label: '网络优化' },
  ...(performanceAvailable ? [{ value: 'network-well-performance-curves', label: '井性能曲线' }] : [])
]

const networkTasks = new Set(['network', 'system-analysis', 'network-optimizer', 'network-well-performance-curves'])
export const filterDeployedRunTypeOptions = (options, wellTasks, networkTaskList) => options.filter(option => {
  const available = networkTasks.has(option.value) ? networkTaskList : wellTasks
  return available === undefined || (Array.isArray(available) && available.includes(option.value))
})

// Object/Study eligibility and deployed Worker support are independent checks.
// Undefined task lists retain historical callers; the page supplies [] while
// capability discovery is pending, so unsupported controls cannot be offered.
export const buildRunTypeOptions = ({
  wellRunTypeOptions,
  isNetworkModel,
  isWellModel,
  modelKind,
  inspectionSchemaVersion,
  wellContexts = [],
  selectedWellContext,
  hasNetworkCapability,
  networkCapabilityStudies,
  selectedStudy,
  networkPerformanceContextAvailable,
  wellAvailableTasks,
  networkAvailableTasks
}) => {
  const availableOptions = options => filterDeployedRunTypeOptions(options, wellAvailableTasks, networkAvailableTasks)
  if (isNetworkModel) return availableOptions(networkOptions(networkPerformanceContextAvailable))
  if (!isWellModel) return []

  let options = [...wellRunTypeOptions]
  if (modelKind === 'legacy_well') {
    options = options.filter(option => ['nodal', 'profile', 'combined', 'esp-parameter', 'trajectory'].includes(option.value))
  } else if (modelKind === 'black_oil_liquid') {
    options.push(
      { value: 'gas-lift-performance', label: '气举性能' },
      { value: 'gas-lift-diagnostics', label: '气举诊断' },
      { value: 'vfp-tables', label: 'VFP 表生成' },
      { value: 'esp-curves', label: 'ESP 曲线' }
    )
  } else if (modelKind === 'basic_gas') {
    options.push({ value: 'esp-curves', label: 'ESP 曲线' })
  }

  const multipleWells = wellContexts.length > 1
  const anyEsp = wellContexts.some(well => well.espContexts?.length > 0)
  if (flowComponentSchemas.has(inspectionSchemaVersion)) {
    const targetWell = wellContexts.find(well => well.context === selectedWellContext)
    const hasFlowComponents = Boolean(targetWell?.completionContexts?.length && targetWell?.tubingContexts?.length)
    const hasEsp = Boolean(targetWell?.espContexts?.length)
    if (!hasFlowComponents || multipleWells) {
      options = trajectoryOptions()
      if (hasFlowComponents && hasEsp) options.push(...espOptions())
    } else if (hasEsp && !options.some(option => option.value === 'esp-curves')) {
      options.push({ value: 'esp-curves', label: 'ESP 曲线' })
    }
  } else if (scopedWellSchemas.has(inspectionSchemaVersion) && multipleWells) {
    // /5 predates the per-well Completion/Tubing contract; retain its behavior.
    options = trajectoryOptions()
    if (anyEsp) options.push(...espOptions())
  }

  options = options.filter(option => !['esp-parameter', 'esp-curves'].includes(option.value) || anyEsp)
  if (hasNetworkCapability && networkCapabilityStudies?.includes(selectedStudy)) options.push(...networkOptions(networkPerformanceContextAvailable))
  return availableOptions(options)
}
