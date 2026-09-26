import { buildSpatialField } from './storageSpatialChart.js'

self.onmessage = ({ data }) => {
  try {
    const field = buildSpatialField(data.points, data.bounds, data.resolution)
    self.postMessage({ id: data.id, field }, field.values ? [field.values.buffer] : [])
  } catch {
    self.postMessage({ id: data.id, field: { message: '空间插值失败，请检查坐标和产能数据', count: 0 } })
  }
}
