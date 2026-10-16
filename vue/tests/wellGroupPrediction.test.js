import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildPredictionResultPayload,
  buildWellGroupCalcRequest,
  dateTextToPredictionStartTime,
  evaluateTargetDemand,
  extractWellGroupTaskNodes,
  fingerprintWellGroupPoints,
  normalizeWellGroupChart,
  predictionStartTimeToDateText,
  unwrapResponse
} from '../src/utils/wellGroupPrediction.js'

/* ===== 预测开始时间：界面日期 ↔ 原平台 UTC 零点毫秒 ===== */
test('date text converts to UTC midnight milliseconds like the platform parameter page', () => {
  assert.equal(dateTextToPredictionStartTime('2026-10-16'), Date.parse('2026-10-16T00:00:00.000Z'))
  assert.equal(dateTextToPredictionStartTime(' 2026-01-01 '), Date.parse('2026-01-01T00:00:00.000Z'))
  assert.equal(dateTextToPredictionStartTime(''), '')
  assert.equal(dateTextToPredictionStartTime(null), '')
})
test('invalid start dates are rejected instead of being silently shifted', () => {
  assert.throws(() => dateTextToPredictionStartTime('2026/10/16'), /格式不正确/)
  assert.throws(() => dateTextToPredictionStartTime('2026-13-01'), /无效/)
  assert.throws(() => dateTextToPredictionStartTime('2026-02-30'), /无效/)
})
test('start time values convert back to display date for panel autofill', () => {
  assert.equal(predictionStartTimeToDateText(Date.parse('2026-10-16T00:00:00.000Z')), '2026-10-16')
  assert.equal(predictionStartTimeToDateText('2026-10-16'), '2026-10-16')
  assert.equal(predictionStartTimeToDateText('2026-10-16T00:00:00.000Z'), '2026-10-16')
  assert.equal(predictionStartTimeToDateText(null), '')
  assert.equal(predictionStartTimeToDateText(''), '')
})

/* ===== Calc 请求组装 ===== */
const detailPayload = (overrides = {}) => ({
  reservoirInput: {
    DynamicPredictionId: 5,
    targetProduction: 10,
    externalWellheadPressure: 6.5,
    predictedTimeStep: 1,
    predictedTimeStepNumber: 365,
    predictionStartTime: 1792368000000,
    gasType: 1,
    specificGravity: 0.62,
    mbEquationType: 1,
    productivityEvaluationForm: 1,
    rockCompressionCoefficient: 0.0004,
    waterCompressionCoefficient: 0.0004,
    // 平台详情里可能夹带的数据库表字段不应进入 Calc 请求。
    id: 88,
    createdTime: '2026-10-01T00:00:00Z'
  },
  wellInputs: [{ wellName: 'X-1' }, { wellName: 'X-2' }],
  ...overrides
})
const scope = { projectId: 1, gasReservoirId: 2, dynamicPredictionId: 5 }

test('calc request keeps hidden detail fields and applies panel overrides', () => {
  const request = buildWellGroupCalcRequest(detailPayload(), scope, {
    targetProduction: 20,
    predictionStartTime: '2026-10-20'
  })
  assert.equal(request.projectId, 1)
  assert.equal(request.gasReservoirId, 2)
  assert.equal(request.dynamicPredictionId, 5)
  assert.equal(request.reservoirInput.targetProduction, 20)
  assert.equal(request.reservoirInput.predictionStartTime, Date.parse('2026-10-20T00:00:00.000Z'))
  // 未在面板展示的字段原样带回，平台专用字段不丢失。
  assert.equal(request.reservoirInput.gasType, 1)
  assert.equal(request.reservoirInput.mbEquationType, 1)
  assert.equal(request.reservoirInput.externalWellheadPressure, 6.5)
  // 数据库表字段不进入 Calc 请求。
  assert.equal(request.reservoirInput.id, undefined)
  assert.equal(request.reservoirInput.createdTime, undefined)
})
test('calc request rejects details belonging to another prediction task', () => {
  const detail = detailPayload()
  detail.reservoirInput.DynamicPredictionId = 6
  assert.throws(() => buildWellGroupCalcRequest(detail, scope, {}), /不一致/)
  assert.throws(() => buildWellGroupCalcRequest({}, scope, {}), /缺少 reservoirInput/)
})
test('calc request unwraps axios data layers and validates scope ids', () => {
  const request = buildWellGroupCalcRequest({ data: { data: detailPayload() } }, scope, {})
  assert.equal(request.dynamicPredictionId, 5)
  assert.throws(() => buildWellGroupCalcRequest(detailPayload(), { ...scope, projectId: 1.5 }, {}), /projectId/)
  // 负数任务号先命中断言「详情与请求编号不一致」，同样被拒绝。
  assert.throws(() => buildWellGroupCalcRequest(detailPayload(), { ...scope, dynamicPredictionId: -1 }, {}), /不一致/)
  assert.throws(() => buildWellGroupCalcRequest(
    { reservoirInput: { DynamicPredictionId: 5, targetProduction: 1 } },
    { ...scope, projectId: -1 }, {}), /projectId/)
})
test('empty panel values do not override detail values', () => {
  const request = buildWellGroupCalcRequest(detailPayload(), scope, { targetProduction: undefined })
  assert.equal(request.reservoirInput.targetProduction, 10)
})

/* ===== 绘图响应解析 ===== */
const chartResponse = (items) => ({ chartItems: items })
const series = (field, name, rows) => ({ yAxisField: field, name, data: rows })

test('well group chart maps gas production and cumulative production by date', () => {
  const parsed = normalizeWellGroupChart(chartResponse([
    series('gasProduction', '气产量', [
      { xValue: '2026-10-02T00:00:00', yValue: 12.5 },
      { xValue: '2026-10-01T00:00:00', yValue: 10 },
      // 0 是有意义的停产值，必须保留。
      { xValue: '2026-10-03T00:00:00', yValue: 0 }
    ]),
    series('cumulativeGasProduction', '累产气量', [
      { xValue: '2026-10-01', yValue: 0.1 },
      { xValue: '2026-10-02', yValue: 0.2 },
      { xValue: '2026-10-03', yValue: 0.2 }
    ])
  ]), { dynamicPredictionId: 5 })
  assert.equal(parsed.id, 5)
  assert.deepEqual(parsed.points.map(point => point.date), ['2026-10-01', '2026-10-02', '2026-10-03'])
  assert.deepEqual(parsed.points.map(point => point.gas), [10, 12.5, 0])
  assert.deepEqual(parsed.points.map(point => point.cumulative), [0.1, 0.2, 0.2])
  assert.deepEqual(parsed.points.map(point => point.formation), [null, null, null])
})
test('deleted, malformed and missing chart entries are skipped per point', () => {
  const parsed = normalizeWellGroupChart(chartResponse([
    series('gasProduction', '气产量', [
      { xValue: '2026-10-01', yValue: 10 },
      { xValue: '2026-10-02', yValue: 11, isDeleted: true },
      { xValue: 'invalid-date', yValue: 99 },
      { xValue: '2026-10-04', yValue: null },
      { xValue: '2026-10-05', yValue: 13 }
    ]),
    series('cumulativeGasProduction', '累产气量', [
      { xValue: '2026-10-01', yValue: 1 },
      { xValue: '2026-10-05', yValue: 2 }
    ])
  ]))
  assert.deepEqual(parsed.points.map(point => point.date), ['2026-10-01', '2026-10-05'])
  assert.deepEqual(parsed.points.map(point => point.gas), [10, 13])
})
test('required series must exist and carry valid points', () => {
  assert.throws(() => normalizeWellGroupChart(chartResponse([
    series('cumulativeGasProduction', '累产气量', [{ xValue: '2026-10-01', yValue: 1 }])
  ])), /缺少“气产量”/)
  assert.throws(() => normalizeWellGroupChart(chartResponse([
    series('gasProduction', '气产量', [{ xValue: '2026-10-01', yValue: 1 }]),
    series('cumulativeGasProduction', '累产气量', [])
  ])), /“累产气量”序列没有有效时间点/)
  assert.throws(() => normalizeWellGroupChart({}), /缺少 chartItems/)
  // 整条序列都是缺失值（null 表示数据缺失）等同没有有效时间点，与单井 /chart 解析口径一致。
  assert.throws(() => normalizeWellGroupChart(chartResponse([
    series('gasProduction', '气产量', [{ xValue: '2026-10-01', yValue: 1 }]),
    series('cumulativeGasProduction', '累产气量', [{ xValue: '2026-10-01', yValue: null }])
  ])), /“累产气量”序列没有有效时间点/)
})
test('formation pressure and recovery degree are optional and read when present', () => {
  // 原平台井组动态预测的 4 个输出指标：气产量、累产气量、地层压力、采出程度。
  const parsed = normalizeWellGroupChart(chartResponse([
    series('gasProduction', '气产量', [{ xValue: '2026-10-01', yValue: 10 }]),
    series('cumulativeGasProduction', '累产气量', [{ xValue: '2026-10-01', yValue: 1 }]),
    series('formationPressure', '地层压力', [{ xValue: '2026-10-01', yValue: 22.4 }]),
    series('reservoirRecoveryDegree', '采出程度', [{ xValue: '2026-10-01', yValue: 18.6 }])
  ]))
  assert.equal(parsed.points[0].formation, 22.4)
  assert.equal(parsed.points[0].recovery, 18.6)
  // 缺失时不报错，字段保持 null（多目标页面按数据存在与否决定是否画线）。
  const partial = normalizeWellGroupChart(chartResponse([
    series('gasProduction', '气产量', [{ xValue: '2026-10-01', yValue: 10 }]),
    series('cumulativeGasProduction', '累产气量', [{ xValue: '2026-10-01', yValue: 1 }])
  ]))
  assert.equal(partial.points[0].formation, null)
  assert.equal(partial.points[0].recovery, null)
})

/* ===== 目标量判定：计算量 = 预测期首尾累计气产量差 ===== */
test('target demand compares prediction-span cumulative production against the input', () => {
  const points = [
    { date: '2026-10-01', gas: 8, cumulative: 8 },
    { date: '2026-10-02', gas: 7.8, cumulative: 15.8 },
    { date: '2026-10-03', gas: 7.6, cumulative: 23.4 }
  ]
  const result = evaluateTargetDemand(points, '20')
  assert.equal(result.startDate, '2026-10-01')
  assert.equal(result.endDate, '2026-10-03')
  assert.ok(Math.abs(result.computedAmount - 15.4) < 1e-9)
  assert.equal(result.target, 20)
  assert.ok(Math.abs(result.difference - (15.4 - 20)) < 1e-9)
  assert.equal(result.satisfied, false)
  // 输入低于计算量 → 满足；恰好相等也视为满足。
  assert.equal(evaluateTargetDemand(points, '10').satisfied, true)
  assert.equal(evaluateTargetDemand(points, '15.4').satisfied, true)
  // 未输入目标量：仍给出计算量，但不做判定。
  const pending = evaluateTargetDemand(points, '')
  assert.equal(pending.target, null)
  assert.equal(pending.difference, null)
  assert.equal(pending.satisfied, null)
})
test('target demand skips missing cumulative endpoints and guards bad input', () => {
  const points = [
    { date: '2026-10-01', cumulative: null },
    { date: '2026-10-02', cumulative: 10 },
    { date: '2026-10-03', cumulative: null },
    { date: '2026-10-04', cumulative: 22 }
  ]
  const result = evaluateTargetDemand(points, '10')
  assert.equal(result.startDate, '2026-10-02')
  assert.equal(result.endDate, '2026-10-04')
  assert.equal(result.computedAmount, 12)
  // 有效累计点不足两个 / 空输入：不判定。
  assert.equal(evaluateTargetDemand([{ date: '2026-10-01', cumulative: 5 }], '10'), null)
  assert.equal(evaluateTargetDemand([], '10'), null)
  assert.equal(evaluateTargetDemand(null, '10'), null)
  assert.equal(evaluateTargetDemand(points, 'abc').satisfied, null)
})

/* ===== 预测结果留存：请求体组装 ===== */
test('prediction result payload carries scope, task metadata and dated points', () => {
  const points = [
    { date: '2026-10-01', gas: 8, cumulative: 8, formation: 22.4, recovery: 19.1 },
    { date: '2026-10-02', gas: 7.8, cumulative: 15.8, formation: 22.2, recovery: 19.3 }
  ]
  const payload = buildPredictionResultPayload({
    scope: { projectId: 1, gasReservoirId: 2, storageId: 1 },
    task: { dynamicPredictionId: 55, name: '井组动态预测 12' },
    mode: '多目标',
    wellNames: ['X-1', 'X-2'],
    points,
    request: { projectId: 1, reservoirInput: { targetProduction: 8 } }
  })
  assert.equal(payload.projectId, 1)
  assert.equal(payload.gasReservoirId, 2)
  assert.equal(payload.storageId, 1)
  assert.equal(payload.predictionId, 55)
  assert.equal(payload.predictionName, '井组动态预测 12')
  assert.equal(payload.mode, '多目标')
  assert.deepEqual(payload.wellNames, ['X-1', 'X-2'])
  assert.equal(payload.startDate, '2026-10-01')
  assert.equal(payload.endDate, '2026-10-02')
  assert.equal(payload.points.length, 2)
  assert.deepEqual(payload.points[0], { date: '2026-10-01', gas: 8, cumulative: 8, formation: 22.4, recovery: 19.1 })
  assert.equal(payload.request.reservoirInput.targetProduction, 8)
})
test('prediction result payload rejects incomplete data instead of saving garbage', () => {
  const scope = { projectId: 1, gasReservoirId: 2, storageId: null }
  const task = { dynamicPredictionId: 55, name: 'x' }
  const points = [{ date: '2026-10-01', gas: 1, cumulative: 1 }]
  // 无点 / 坏日期 / 无任务号 / 无项目范围：均拒绝。
  assert.equal(buildPredictionResultPayload({ scope, task, points: [] }), null)
  assert.equal(buildPredictionResultPayload({ scope, task, points: [{ date: 'bad', cumulative: 1 }] }), null)
  assert.equal(buildPredictionResultPayload({ scope, task: { name: 'x' }, points }), null)
  assert.equal(buildPredictionResultPayload({ scope: { projectId: 0 }, task, points }), null)
  // 无 storageId 时保存为 null（由后端按项目/气藏范围校验）。
  const payload = buildPredictionResultPayload({ scope, task, points })
  assert.equal(payload.storageId, null)
})

/* ===== 井组任务目录树提取（复用已有任务） ===== */
test('well group task nodes are extracted from the prediction tree with newest first', () => {
  const tree = {
    node: {
      nodeType: 60, nodeId: 1, nodeTitle: '动态预测',
      subNodes: [
        {
          nodeType: 62, nodeId: 2, nodeTitle: '井组动态预测',
          subNodes: [
            { nodeType: 79, nodeId: 9, nodeTitle: '井组动态预测 1' },
            { nodeType: 79, nodeId: 15, nodeTitle: '井组动态预测 7', subNodes: [] },
            // 单井任务（nodeType 80）与其他类型不混入井组任务。
            { nodeType: 80, nodeId: 30, nodeTitle: 'X-1单井动态预测 1' }
          ]
        },
        { nodeType: 63, nodeId: 31, nodeTitle: 'X-1单井动态预测 2' }
      ]
    }
  }
  const tasks = extractWellGroupTaskNodes({ data: tree })
  assert.deepEqual(tasks, [
    { dynamicPredictionId: 15, name: '井组动态预测 7' },
    { dynamicPredictionId: 9, name: '井组动态预测 1' }
  ])
})
test('task tree extraction guards malformed nodes and name conflicts', () => {
  assert.deepEqual(extractWellGroupTaskNodes({}), [])
  assert.deepEqual(extractWellGroupTaskNodes({ node: { nodeType: 79, nodeId: 0, nodeTitle: 'x' } }), [])
  assert.deepEqual(extractWellGroupTaskNodes({ node: { nodeType: 79, nodeId: 8, nodeTitle: '  ' } }), [])
  // 同一编号出现不同名称时拒绝猜测。
  assert.throws(() => extractWellGroupTaskNodes({
    node: { nodeType: 79, nodeId: 8, nodeTitle: '井组动态预测 1', subNodes: [
      { nodeType: 79, nodeId: 8, nodeTitle: '井组动态预测 2' }
    ] }
  }), /不同名称/)
})

/* ===== 指纹：区分本次计算的新曲线与历史结果 ===== */
test('fingerprint changes when a fresh prediction replaces history', () => {
  const before = [
    { date: '2026-10-01', gas: 10, cumulative: 1 },
    { date: '2026-10-02', gas: 9, cumulative: 1.9 }
  ]
  const sameShape = JSON.parse(JSON.stringify(before))
  const recalculated = [
    { date: '2026-10-01', gas: 12, cumulative: 1.2 },
    { date: '2026-10-02', gas: 11, cumulative: 2.3 }
  ]
  assert.equal(fingerprintWellGroupPoints(before), fingerprintWellGroupPoints(sameShape))
  assert.notEqual(fingerprintWellGroupPoints(before), fingerprintWellGroupPoints(recalculated))
})
test('unwrapResponse digs through axios data layers', () => {
  assert.deepEqual(unwrapResponse({ data: { data: { chartItems: [] } } }), { chartItems: [] })
  assert.deepEqual(unwrapResponse({ chartItems: [] }), { chartItems: [] })
})
