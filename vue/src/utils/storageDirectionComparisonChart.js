import { comparisonMethods } from './productivityComparisonChart.js'
import { buildStorageGroupedChart } from './storageMethodComparisonChart.js'

// 与单井注采对比一致：蓝色采气、橙色注气，跨井、跨周期及图例切换均不变色。
const directions = [
  { value: 'production', label: '采气', color: '#5879c4' },
  { value: 'injection', label: '注气', color: '#dc9740' }
]
const number = value => Number(value).toLocaleString('en-US', { maximumFractionDigits: 4 })

/** 每根柱取后端“周期 + 井 + 方向”的均值，不混合采气与注气，也不将缺失值当作0。 */
export function buildStorageDirectionChart(response, pressureMethod, emptyText = '请选择井并计算', width = 1000, selected = {}) {
  const method = comparisonMethods.find(item => item.value === response?.method)?.label || ''
  return buildStorageGroupedChart(response, pressureMethod, emptyText, width, selected, {
    items: response ? directions : [], field: 'directions', key: 'operationType', kind: '注采方向',
    title: '注采', axisName: '周期 / 单井 / 注采方向',
    subtitle: response ? `${method} · 采气地层压力 ${number(response.formationPressure)} MPa\n注气压力 ${number(response.injectionPressure)} MPa` : ''
  })
}
