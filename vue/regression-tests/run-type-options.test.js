import assert from 'node:assert/strict'
import test from 'node:test'
import { buildRunTypeOptions } from '../src/views/SoftwareIntegration/runTypeOptions.js'

const baseOptions = [
  { value: 'nodal', label: '节点分析' },
  { value: 'profile', label: 'PT 剖面' },
  { value: 'combined', label: '组合运行' },
  { value: 'sensitivity', label: '敏感性分析' },
  { value: 'esp-parameter', label: 'ESP 参数方案' },
  { value: 'trajectory', label: '井轨迹' }
]
const well = (context = 'Well_1', overrides = {}) => ({
  context, espContexts: [`${context}:ESP`],
  completionContexts: [`${context}:Completion`], tubingContexts: [`${context}:Tubing`],
  ...overrides
})
const input = overrides => ({
  wellRunTypeOptions: baseOptions, isNetworkModel: false, isWellModel: true,
  modelKind: 'black_oil_liquid', inspectionSchemaVersion: 'pipesim-well-inspection/8',
  wellContexts: [well()], selectedWellContext: 'Well_1',
  hasNetworkCapability: false, selectedStudy: 'Base Case',
  networkPerformanceContextAvailable: false, ...overrides
})
const values = options => options.map(option => option.value)
const choices = overrides => values(buildRunTypeOptions(input(overrides)))
const blackOilTasks = ['nodal', 'profile', 'combined', 'sensitivity', 'esp-parameter', 'trajectory', 'gas-lift-performance', 'gas-lift-diagnostics', 'vfp-tables', 'esp-curves']
const networkTasks = ['network', 'system-analysis', 'network-optimizer']

test('deployed well and network tasks limit choices independently of model kind', () => {
  assert.deepEqual(choices({ wellAvailableTasks: ['profile'] }), ['profile'])
  assert.deepEqual(choices({ isNetworkModel: true, networkAvailableTasks: ['network'] }), ['network'])
  assert.deepEqual(choices({ wellAvailableTasks: [] }), [])
  assert.deepEqual(choices({ isNetworkModel: true, networkAvailableTasks: [] }), [])
  assert.deepEqual(choices({ wellAvailableTasks: null }), [])
  assert.deepEqual(choices({ wellAvailableTasks: ['arbitrary-task'] }), [])
})

test('mixed Study support does not borrow unsupported tasks from the other simulator', () => {
  const mixed = { hasNetworkCapability: true, networkCapabilityStudies: ['Base Case'], wellAvailableTasks: ['profile'], networkAvailableTasks: ['network'] }
  assert.deepEqual(choices(mixed), ['profile', 'network'])
  assert.deepEqual(choices({ ...mixed, selectedStudy: 'Other Study' }), ['profile'])
  assert.deepEqual(choices({ ...mixed, networkAvailableTasks: [] }), ['profile'])
  assert.deepEqual(choices({ ...mixed, wellContexts: [well('Well_1', { completionContexts: [] })] }), ['network'])
})

test('network models retain ordered labels and optional performance curves', () => {
  assert.deepEqual(buildRunTypeOptions(input({ isNetworkModel: true })), [
    { value: 'network', label: '管网模拟' },
    { value: 'system-analysis', label: '系统分析' },
    { value: 'network-optimizer', label: '网络优化' }
  ])
  assert.deepEqual(choices({ isNetworkModel: true, networkPerformanceContextAvailable: true }), [...networkTasks, 'network-well-performance-curves'])
})

test('non-well models do not acquire well or mixed-Study tasks', () => {
  assert.deepEqual(choices({ isWellModel: false, hasNetworkCapability: true, networkCapabilityStudies: ['Base Case'] }), [])
})

test('model kinds preserve their original task sets and order', () => {
  assert.deepEqual(choices(), blackOilTasks)
  assert.deepEqual(choices({ modelKind: 'basic_gas' }), ['nodal', 'profile', 'combined', 'sensitivity', 'esp-parameter', 'trajectory', 'esp-curves'])
  assert.deepEqual(choices({ modelKind: 'legacy_well' }), ['nodal', 'profile', 'combined', 'esp-parameter', 'trajectory', 'esp-curves'])
  assert.deepEqual(choices({ modelKind: 'unknown' }), [...values(baseOptions), 'esp-curves'])
})

for (let version = 1; version <= 4; version++) {
  test(`/ ${version} keeps pre-scoped component behavior`, () => {
    const overrides = { inspectionSchemaVersion: `pipesim-well-inspection/${version}`, wellContexts: [well('Well_1', { completionContexts: [], tubingContexts: [] })] }
    assert.deepEqual(choices(overrides), blackOilTasks)
    assert.deepEqual(choices({ ...overrides, wellContexts: [] }), blackOilTasks.filter(task => !task.startsWith('esp-')))
    assert.deepEqual(choices({ ...overrides, modelKind: 'legacy_well' }), ['nodal', 'profile', 'combined', 'esp-parameter', 'trajectory'])
  })
}

test('/5 multi-well compatibility uses model ESP inventory without flow metadata', () => {
  const overrides = {
    inspectionSchemaVersion: 'pipesim-well-inspection/5', selectedWellContext: '',
    wellContexts: [well('Well_1', { completionContexts: [], tubingContexts: [] }), well('Well_2', { espContexts: [] })]
  }
  assert.deepEqual(choices(overrides), ['trajectory', 'esp-parameter', 'esp-curves'])
  assert.deepEqual(choices({ ...overrides, selectedWellContext: 'Well_2' }), ['trajectory', 'esp-parameter', 'esp-curves'])
  assert.deepEqual(choices({ ...overrides, wellContexts: [well('Well_1', { espContexts: [] }), well('Well_2', { espContexts: [] })] }), ['trajectory'])
})

for (const version of [6, 7, 8]) {
  const schema = `pipesim-well-inspection/${version}`
  test(`/${version} multi-well tasks bind ESP and flow capability to the selected well`, () => {
    const overrides = { inspectionSchemaVersion: schema, wellContexts: [well(), well('Well_2', { espContexts: [] })] }
    assert.deepEqual(choices(overrides), ['trajectory', 'esp-parameter', 'esp-curves'])
    assert.deepEqual(choices({ ...overrides, selectedWellContext: 'Well_2' }), ['trajectory'])
    assert.deepEqual(choices({ ...overrides, selectedWellContext: '' }), ['trajectory'])
    assert.deepEqual(choices({ ...overrides, selectedWellContext: 'Missing' }), ['trajectory'])
  })

  for (const missing of ['completion', 'tubing', 'both']) {
    test(`/${version} missing ${missing} blocks ESP for single and multiple wells`, () => {
      const target = well('Well_2', {
        completionContexts: missing === 'tubing' ? ['Well_2:Completion'] : [],
        tubingContexts: missing === 'completion' ? ['Well_2:Tubing'] : []
      })
      const overrides = { inspectionSchemaVersion: schema, selectedWellContext: 'Well_2' }
      assert.deepEqual(choices({ ...overrides, wellContexts: [target] }), ['trajectory'])
      assert.deepEqual(choices({ ...overrides, wellContexts: [well(), target] }), ['trajectory'])
    })
  }

  test(`/${version} flow-capable well without ESP keeps non-ESP tasks`, () => {
    assert.deepEqual(choices({ inspectionSchemaVersion: schema, wellContexts: [well('Well_1', { espContexts: [] })] }), blackOilTasks.filter(task => !task.startsWith('esp-')))
  })
}

test('/8 pure well without networkCapability keeps all supported well tasks', () => {
  assert.deepEqual(choices({ networkCapabilityStudies: undefined }), blackOilTasks)
})

test('mixed models add network tasks only for a validated and exactly matched Study', () => {
  const overrides = { hasNetworkCapability: true, networkCapabilityStudies: ['Base Case'] }
  assert.deepEqual(choices(overrides), [...blackOilTasks, ...networkTasks])
  for (const selectedStudy of ['Other', 'base case', 'Base Case ', '']) {
    assert.deepEqual(choices({ ...overrides, selectedStudy }), blackOilTasks)
  }
  assert.deepEqual(choices({ ...overrides, hasNetworkCapability: false }), blackOilTasks)
  assert.deepEqual(choices({ ...overrides, networkCapabilityStudies: undefined }), blackOilTasks)
  assert.deepEqual(choices({ ...overrides, networkCapabilityStudies: [] }), blackOilTasks)
  assert.deepEqual(choices({ ...overrides, networkPerformanceContextAvailable: true }), [...blackOilTasks, ...networkTasks, 'network-well-performance-curves'])
})

test('mixed Study network capability is independent of selected-well flow components', () => {
  assert.deepEqual(choices({
    wellContexts: [well('Well_1', { completionContexts: [], tubingContexts: [] })],
    hasNetworkCapability: true, networkCapabilityStudies: ['Base Case'], networkPerformanceContextAvailable: true
  }), ['trajectory', ...networkTasks, 'network-well-performance-curves'])
})

test('derivation is repeatable and does not mutate frozen input options or capabilities', () => {
  const freeze = value => {
    if (value && typeof value === 'object') {
      Object.values(value).forEach(freeze)
      Object.freeze(value)
    }
    return value
  }
  const original = freeze(input({ modelKind: 'unknown', hasNetworkCapability: true, networkCapabilityStudies: ['Base Case'] }))
  const snapshot = structuredClone(original)
  const first = buildRunTypeOptions(original)
  assert.deepEqual(first, buildRunTypeOptions(original))
  assert.deepEqual(original, snapshot)
  first.push({ value: 'unrelated', label: 'Unrelated' })
  assert(!values(buildRunTypeOptions(original)).includes('unrelated'))
})
