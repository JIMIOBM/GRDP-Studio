import { dataManagementApi } from '@/api/docker'
import { pvtStorageApi } from '@/api/pvtStorage'
import { unpack, rowsOf, wellRows, latestRow, deviationValues, tubingRows, read, numberOf } from '@/utils/temperatureSources'

export async function loadTemperatureSources(projectId, gasReservoirId, wellName) {
  const results = await Promise.allSettled([
    dataManagementApi.getWellDeviation(projectId, gasReservoirId, wellName),
    dataManagementApi.getWellCompletion(projectId, gasReservoirId, wellName),
    dataManagementApi.getGasProperties(projectId, gasReservoirId),
    loadProduction(projectId, gasReservoirId, wellName),
    dataManagementApi.getOtherData(projectId, gasReservoirId),
    pvtStorageApi.list(projectId, gasReservoirId, wellName)
  ])
  const names = ['井斜数据', '完井数据', '天然气性质', '生产数据', '其他数据', 'PVT记录']
  const errors = [], value = index => {
    if (results[index].status === 'fulfilled') return results[index].value
    errors.push(`${names[index]}读取失败：${results[index].reason?.msg || results[index].reason?.message || '接口异常'}`)
    return null
  }
  const deviation = value(0), completion = value(1), gas = value(2), production = value(3)
  // 其他数据只用于推断默认生产通道，读取失败不阻断温压计算。
  const other = results[4].status === 'fulfilled' ? results[4].value : null
  const pvts = value(5)
  const input = { depth: null, angle: null, idTubing: null, roughness: null, gammaG: null, rhoL: 1000, muL: null }
  if (deviation) { try { Object.assign(input, deviationValues(deviation, wellName)) } catch (e) { errors.push(e.message) } }
  let tubings = []
  if (completion) { try { tubings = tubingRows(completion, wellName); input.idTubing = tubings[0]?.diameter; input.roughness = tubings[0]?.roughness } catch (e) { errors.push(e.message) } }
  const gasRow = latestRow(wellRows(rowsOf(gas), wellName))
  const otherRow = latestRow(wellRows(rowsOf(other), wellName))
  input.gammaG = numberOf(read(gasRow, 'specificGravity', 'specific_gravity'))
  const pvtRecords = rowsOf(pvts).sort((a, b) => a.pvtNo - b.pvtNo)
  return {
    input,
    tubings,
    pvtRecords,
    production: production?.row ?? null,
    // 返回全量注采记录供日历按日期选择，production继续保留最新记录以兼容原调用方。
    productionRows: production?.rows ?? [],
    productionFields: production?.fields ?? [],
    flowPath: read(otherRow, 'flowPath', 'flow_path'),
    errors,
    loadedAt: new Date().toISOString(),
    productionDate: read(production?.row, 'date', 'production_date')
  }
}

async function loadProduction(projectId, gasReservoirId, wellName) {
  // 与数据管理“注采数据”使用同一接口；默认page=1、size=-1读取当前井全部记录。
  const response = await dataManagementApi.getProductionData(projectId, gasReservoirId, wellName)
  const payload = unpack(response)
  // 该接口已按井限定范围，因此允许返回记录不重复携带井名。
  const rows = wellRows(rowsOf(response), wellName, true)
  const row = latestRow(rows)
  if (!row) throw new Error('当前井的注采数据为空，请先在数据管理中检查或导入注采数据')
  return { row, rows, fields: Array.isArray(payload.fields) ? payload.fields : [] }
}
