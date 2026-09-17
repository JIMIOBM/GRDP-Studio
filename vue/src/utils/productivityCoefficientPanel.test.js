import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileTemplate } from '@vue/compiler-sfc'
import { ATMOSPHERIC_PRESSURE_MPA, calculateExponentialCoefficientCurve, calculateExponentialCoefficientIprFamily, calculateExponentialCoefficientOpenFlow } from './productivityCoefficientCalculation.js'

const filename = new URL('../views/SingleWellProductivity/ExponentialContent.vue', import.meta.url)
const source = readFileSync(filename, 'utf8')
const { descriptor } = parse(source)
const template = descriptor.template.content

test('产能系数参数模板可以编译，且不再显示注气曲线说明', () => {
  const result = compileTemplate({ source: template, filename: filename.pathname, id: 'coefficient-panel' })
  assert.deepEqual(result.errors, [])
  assert.ok(!source.includes('pressure-difference-note'))
  assert.ok(!template.includes('注气拟合采用最低压力级别'))
})

test('计算和保存处于同一操作行，沿用参数面板的8px间距', () => {
  const actions = template.match(/<div class="form-actions">([\s\S]*?)<\/div>/)?.[1] || ''
  assert.ok(actions.includes('@click="handleCalculate"'))
  assert.ok(actions.includes('@click="saveRecord"'))
  const theme = readFileSync(new URL('../style/parameter-panels.scss', import.meta.url), 'utf8')
  assert.match(theme, /:is\(\.form-actions,[\s\S]*?gap: 8px;/)
})

test('指数式和二项式共用参数点布局，气量输入位于计算按钮之前', () => {
  const sharedFields = template.slice(template.indexOf('<!-- 两种方法共用参数点布局'))
  const pressure = sharedFields.indexOf(':value="fittedFormationPressure"')
  const flow = sharedFields.indexOf(':value="fittedFlowRate"')
  const operation = sharedFields.indexOf('<legend>注采类型</legend>')
  const calculate = sharedFields.indexOf('@click="handleCalculate"')
  assert.ok(pressure >= 0 && flow > pressure && operation > flow && calculate > operation)
  assert.equal(template.match(/:value="fittedFlowRate"/g).length, 1)
  assert.equal(template.match(/:value="fittedFormationPressure"/g).length, 1)
  assert.ok(template.includes('<span>地层压力 Pr(MPa)</span>'))
  assert.ok(template.includes('water-parameter-theme'))
  assert.ok(template.includes('v-resizable-parameter-panel="paramsCollapsed"'))
})

test('指数式保留C/n及修正系数，并与二项式共用Pr注气绘图上限', () => {
  for (const name of ['productivityCoefficientC', 'productivityExponentN', 'correctedCoefficientC', 'correctedExponentN']) {
    assert.ok(template.includes(`:value="${name}"`))
  }
  assert.ok(!source.includes('maximumInjectionPressure'))
  assert.ok(descriptor.scriptSetup.content.includes('reservoirPressure: isInjection.value ? Number(props.maximumFormationPressure) / 10 : props.maximumFormationPressure'))
  assert.ok(descriptor.scriptSetup.content.includes('pressureLimit: Number(props.maximumFormationPressure)'))
  assert.ok(!template.includes("? '可选'"))
  assert.match(template, /:value="fittedFormationPressure" required/)
  assert.match(template, /:value="fittedFlowRate" required/)
  assert.ok(descriptor.scriptSetup.content.includes('calculateExponentialCoefficientCurve({'))
  assert.ok(descriptor.scriptSetup.content.includes('calculateExponentialCoefficientIprFamily({'))
})

for (const method of ['压力法', '压力平方法', '拟压力']) {
  test(`指数式${method}无阻流量使用完整Pr至大气压的压差，不受注气绘图起点影响`, () => {
    const options = {
      reservoirPressure: 56, coefficient: 26, exponent: 0.6,
      calculationMethod: method, operationType: 'injection', maximumFlowingPressure: 56,
      pvtResultRows: [{ pressure: 0, pseudoPressure: 0 }, { pressure: 56, pseudoPressure: 5600 }]
    }
    const potential = p => method === '压力法' ? p : method === '压力平方法' ? p ** 2 : 100 * p
    const expected = 26 * (potential(56) - potential(ATMOSPHERIC_PRESSURE_MPA)) ** 0.6
    assert.ok(Math.abs(calculateExponentialCoefficientOpenFlow(options) - expected) < 1e-8)
    const injectionCurve = calculateExponentialCoefficientCurve({ ...options, reservoirPressure: 5.6 })
    assert.notEqual(calculateExponentialCoefficientOpenFlow(options), injectionCurve.limitRate)
    assert.equal(injectionCurve.points[0].flowingPressure, 5.6)
    assert.equal(injectionCurve.points.at(-1).flowingPressure, 56)
  })
  test(`指数式${method}注气拟合从Pr/10到Pr，十级IPR共用上限且仍使用C乘压差的n次方`, () => {
    const pressure = 56.34
    const potential = p => method === '压力法' ? p : method === '压力平方法' ? p * p : 100 * p
    const options = {
      coefficient: 2.5, exponent: 0.8, operationType: 'injection',
      calculationMethod: method, maximumFlowingPressure: pressure,
      pvtResultRows: [{ pressure: 0, pseudoPressure: 0 }, { pressure, pseudoPressure: pressure * 100 }]
    }
    const curve = calculateExponentialCoefficientCurve({ ...options, reservoirPressure: pressure / 10 })
    assert.equal(curve.points[0].flowRate, 0)
    assert.equal(curve.points[0].flowingPressure, pressure / 10)
    assert.equal(curve.points.at(-1).flowingPressure, pressure)
    const expected = options.coefficient * (potential(pressure) - potential(pressure / 10)) ** options.exponent
    assert.ok(Math.abs(curve.limitRate - expected) < 1e-8)
    const family = calculateExponentialCoefficientIprFamily({ ...options, reservoirPressure: pressure })
    assert.equal(family.length, 10)
    for (const entry of family) {
      assert.equal(entry.curve.points[0].flowRate, 0)
      assert.equal(entry.curve.points[0].flowingPressure, entry.reservoirPressure)
      assert.ok(Math.abs(entry.curve.points.at(-1).flowingPressure - pressure) < 1e-10)
      assert.ok(entry.curve.points.every((point, i, points) => i === 0 || point.flowRate >= points[i - 1].flowRate))
    }
    assert.equal(family.at(-1).curve.limitRate, 0)
    assert.throws(() => calculateExponentialCoefficientIprFamily({ ...options, reservoirPressure: pressure, maximumFlowingPressure: pressure - 1 }))
  })
}

test('指数式结果显示无阻流量且使用修正系数；二项式结果保持原逻辑', () => {
  assert.ok(template.includes("methodType === '二项式' && isInjection\n              ? '最大注气量'"))
  assert.match(descriptor.scriptSetup.content, /calculateExponentialCoefficientOpenFlow\(\{\s*reservoirPressure: props.maximumFormationPressure,\s*coefficient: props.correctedCoefficientC,\s*exponent: props.correctedExponentN/)
  assert.ok(descriptor.scriptSetup.content.includes(': iprCurve.limitRate'))
  assert.ok(descriptor.scriptSetup.content.includes('outputRate.toFixed(4)'))
})
