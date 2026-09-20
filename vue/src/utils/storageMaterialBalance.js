import { ref } from 'vue'

export function createStorageMaterialBalanceState(fetchAggregate, {
  keys = ['projectId', 'gasReservoirId', 'storageId'],
  validate = data => Array.isArray(data?.wells) && Array.isArray(data?.rows) && Array.isArray(data?.skippedDates)
} = {}) {
  const result = ref(null)
  const loading = ref(false)
  const error = ref('')
  let version = 0
  let controller
  const clear = () => {
    version++
    controller?.abort()
    controller = null
    result.value = null
    loading.value = false
    error.value = ''
  }
  const load = async scope => {
    clear()
    const current = version
    const snapshot = Object.fromEntries(keys.map(key => [key, Number(scope?.[key])]))
    if (!Object.values(snapshot).every(value => Number.isSafeInteger(value) && value > 0)) {
      error.value = '请先选择具体储气库'
      return
    }
    controller = new AbortController()
    loading.value = true
    try {
      const response = await fetchAggregate(snapshot, controller.signal)
      if (current !== version) return
      const data = response?.data ?? response
      if (!validate(data, snapshot)) {
        throw new Error('库物质平衡数据格式不正确')
      }
      result.value = data
    } catch (cause) {
      if (current === version) error.value = cause?.response?.data?.msg || cause?.msg || cause?.message || '读取来源数据失败，请重试'
    } finally {
      if (current === version) loading.value = false
    }
  }
  return { result, loading, error, load, clear }
}

// 这是汇总输入的压力变化曲线，不把p当成p/Z，也不把来源储量之和冒充库级回归储量。
export function storageMaterialBalanceChart(rows) {
  const valid = (rows || []).filter(row => typeof row.date === 'string'
    && Number.isFinite(row.pressure) && row.pressure > 0 && Number.isFinite(row.gas) && row.gas >= 0)
  return {
    animation: false,
    title: { text: '加权地层压力—累产气量', left: 'center', textStyle: { fontSize: 16 } },
    tooltip: { trigger: 'item', formatter: item => {
      const [gas, pressure, date] = item.value
      return `${String(date).replace(/[<>&"']/g, '')}<br/>累产气量：${gas.toFixed(4)} ×10⁸m³<br/>加权地层压力：${pressure.toFixed(4)} MPa`
    } },
    grid: { left: 80, right: 30, top: 65, bottom: 75 },
    xAxis: { type: 'value', name: '累产气量 (10⁸m³)', nameLocation: 'middle', nameGap: 35 },
    yAxis: { type: 'value', name: '加权地层压力 (MPa)', nameLocation: 'middle', nameGap: 50, scale: true },
    series: [{ name: '汇总输入', type: 'line', showSymbol: true, symbolSize: 7,
      data: valid.map(row => [row.gas, row.pressure, row.date]) }]
  }
}
