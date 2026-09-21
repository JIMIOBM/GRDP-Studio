<script setup>
/**
 * 库级诊断曲线
 *
 * 与单井版不同的是：
 * - 没有"选择数据表 / 本地导入 Excel"
 * - 后端自动读取项目+气藏下全部井的 CALCULATED 单井诊断方案
 * - 按时间求和形成库级注采序列，再复用 DiagnosticCurveService
 * - 前端只提交 projectId + gasReservoirId + pvtId + 压力上下限
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { gasReservoirDiagnosticApi } from '@/api/gasReservoirDiagnostic'

/* ==================== Props ==================== */

const props = defineProps({
  reservoir: { type: Object, default: null }
})

/* ==================== 基础字段 ==================== */

const projectId = computed(() => Number(props.reservoir?.projectId))
const gasReservoirId = computed(() => Number(props.reservoir?.gasReservoirId))
const storageId = computed(() => Number(props.reservoir?.storageId))  // ✅ 新增
const reservoirLabel = computed(() => props.reservoir?.label || '未选择库')

const unwrap = response => response?.data?.data ?? response?.data ?? response ?? {}

/* ==================== 井覆盖状态 ==================== */

const totalWellCount = ref(0)
const readyWellCount = ref(0)
const missingWellNames = ref([])

const coverageStatus = computed(() => {
  if (totalWellCount.value === 0) return 'loading'
  if (missingWellNames.value.length === 0) return 'ready'
  return 'partial'
})

/* ==================== PVT ==================== */

const pvtOptions = ref([])
const selectedPvtId = ref('')
const selectedPvtName = ref('')

const onPvtChange = () => {
  const opt = pvtOptions.value.find(
    o => String(o.pvtId) === String(selectedPvtId.value)
  )
  selectedPvtName.value = opt?.pvtName || ''
}

/* ==================== 计算参数 ==================== */

const upperLimit = ref('')
const lowerLimit = ref('')
const calculating = ref(false)

/* ==================== 结果 ==================== */

const result = ref(null)
const summaryRows = ref([])
const activePanel = ref('data')

/* ==================== 页面初始化 ==================== */

const loadContext = async () => {
  if (!projectId.value || !gasReservoirId.value || !storageId.value) return  // ✅ 添加 storageId 校验
  try {
    const res = unwrap(await gasReservoirDiagnosticApi.getContext(
      projectId.value, 
      gasReservoirId.value,
      storageId.value  
    ))
    totalWellCount.value = res.totalWellCount ?? 0
    readyWellCount.value = res.readyWellCount ?? 0
    missingWellNames.value = res.missingWellNames ?? []
    pvtOptions.value = res.pvtOptions ?? []
    if (pvtOptions.value.length) {
      selectedPvtId.value = String(pvtOptions.value[0].pvtId)
      onPvtChange()
    } else {
      selectedPvtId.value = ''
      selectedPvtName.value = ''
    }
  } catch (e) {
    console.error('库诊断曲线上下文加载失败', e)
    ElMessage.error('加载库诊断曲线上下文失败')
  }
}

watch(
  [
    () => props.reservoir?.projectId,
    () => props.reservoir?.gasReservoirId,
    () => props.reservoir?.storageId
  ],
  () => {
    result.value = null
    summaryRows.value = []
    activePanel.value = 'data'
    loadContext()
  },
  { immediate: true }
)

/* ==================== 计算 ==================== */

const parseNumber = v => {
  if (v === null || v === undefined || v === '') return null
  const num = Number(String(v).trim().replace(/,/g, ''))
  return Number.isFinite(num) ? num : null
}

const handleCalculate = async () => {
  if (!selectedPvtId.value) {
    ElMessage.warning('请先选择 PVT 表')
    return
  }

  if (coverageStatus.value === 'partial') {
    ElMessage.warning(`以下井缺少单井诊断方案：${missingWellNames.value.join('、')}`)
    return
  }

  const up = parseNumber(upperLimit.value)
  const lo = parseNumber(lowerLimit.value)

  if (up === null || lo === null) {
    ElMessage.warning('压力上下限必须是有效数字')
    return
  }

  if (lo <= 0 || up <= lo) {
    ElMessage.warning('上限压力必须大于下限压力，且下限必须大于0')
    return
  }

  calculating.value = true

  try {
    const res = unwrap(
      await gasReservoirDiagnosticApi.calculate({
        projectId: projectId.value,
        gasReservoirId: gasReservoirId.value,
        storageId: storageId.value,
        pvtId: Number(selectedPvtId.value),
        upperLimit: up,
        lowerLimit: lo
      })
    )

    /*
     * 后端返回：
     * {
     *   totalWellCount,
     *   readyWellCount,
     *   aggregatedRows,
     *   result: {
     *     cycleCurves,
     *     standardLine,
     *     lowerPressureOverZ,
     *     upperPressureOverZ,
     *     ...
     *   }
     * }
     *
     * 真正用于绘图的是 res.result。
     */
    const curveResult = res?.result ?? null

    if (!curveResult) {
      throw new Error('后端计算成功，但返回结果中缺少 result 曲线数据')
    }

    result.value = curveResult

    // AggregatedRow 的气量字段是 gasVolume1e8。
    summaryRows.value = (
      Array.isArray(res?.aggregatedRows)
        ? res.aggregatedRows
        : []
    ).map((r, idx) => ({
      index: r?.sequence ?? idx + 1,
      time: r?.time ?? '',
      gas: r?.gasVolume1e8 ?? 0,
      cycle: r?.cycle ?? ''
    }))

    activePanel.value = 'analysis'

    // 等待结果区域显示后再初始化 ECharts。
    await nextTick()
    updateChart(curveResult)

    ElMessage.success('计算完成')
  } catch (e) {
    console.error('库诊断曲线计算失败', {
      status: e?.response?.status,
      response: e?.response?.data,
      requestData: e?.config?.data,
      error: e
    })

    const code = e?.response?.status
    const body = e?.response?.data
    const responseData = body?.data ?? body

    if (code === 409) {
      const missing =
        responseData?.missingWellNames ??
        body?.missingWellNames ??
        []

      ElMessage.error(
        missing.length
          ? `以下井缺少单井诊断方案：${missing.join('、')}`
          : '部分井缺少可用的单井诊断方案'
      )
    } else {
      ElMessage.error(
        responseData?.msg ??
        responseData?.message ??
        body?.msg ??
        body?.message ??
        e?.message ??
        '计算失败'
      )
    }
  } finally {
    calculating.value = false
  }
}

/* ==================== 图表（与单井版相同） ==================== */

const chartEl = ref(null)
let chart = null

const initChart = () => {
  if (!chartEl.value) return
  if (!chart) chart = echarts.init(chartEl.value)
}

const toFiniteNumber = value => {
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

const updateChart = data => {
  initChart()
  if (!chart) return

  const cycleCurves = Array.isArray(data?.cycleCurves)
    ? data.cycleCurves
    : []

  const standardLine = Array.isArray(data?.standardLine)
    ? data.standardLine
    : []

  const lowerPZ = toFiniteNumber(data?.lowerPressureOverZ)
  const upperPZ = toFiniteNumber(data?.upperPressureOverZ)

  const actualSeries = cycleCurves
    .map((cycle, index) => {
      const points = (
        Array.isArray(cycle?.points)
          ? cycle.points
          : []
      )
        .map(item => {
          const inventory = toFiniteNumber(item?.inventory)
          const pressureOverZ = toFiniteNumber(item?.pressureOverZ)

          if (inventory === null || pressureOverZ === null) {
            return null
          }

          return {
            value: [inventory, pressureOverZ],
            raw: item,
            cycleName:
              cycle?.cycle ??
              cycle?.cycleName ??
              item?.cycle ??
              `周期${index + 1}`
          }
        })
        .filter(Boolean)

      return {
        id: `grd-diag-${index}`,
        name: '实际运行曲线',
        type: 'line',
        smooth: false,
        showSymbol: points.length <= 30,
        symbolSize: 6,
        connectNulls: false,
        data: points
      }
    })
    .filter(series => series.data.length > 0)

  const standardData = standardLine
    .map(item => {
      const inventory = toFiniteNumber(item?.inventory)
      const pressureOverZ = toFiniteNumber(item?.pressureOverZ)

      return inventory !== null && pressureOverZ !== null
        ? [inventory, pressureOverZ]
        : null
    })
    .filter(Boolean)

  chart.clear()

  // 避免“计算成功但图表纯空白”。
  if (actualSeries.length === 0 && standardData.length === 0) {
    chart.setOption({
      animation: false,
      title: {
        text: '计算完成，但没有可绘制的曲线数据',
        subtext: '请检查 result.cycleCurves 和 result.standardLine',
        left: 'center',
        top: 'middle',
        textStyle: {
          fontSize: 16,
          fontWeight: 500
        },
        subtextStyle: {
          fontSize: 12
        }
      }
    })
    chart.resize()
    return
  }

  const theoreticalSeries = {
    id: 'grd-diag-standard',
    name: '理论基准线',
    type: 'line',
    smooth: false,
    showSymbol: false,
    data: standardData,
    lineStyle: {
      type: 'dashed',
      width: 2
    },
    markLine:
      lowerPZ !== null && upperPZ !== null
        ? {
            silent: true,
            symbol: 'none',
            label: {
              formatter: p =>
                `${p.name}: ${Number(p.value).toFixed(4)} MPa`
            },
            data: [
              {
                name: 'Pmin/Z(Pmin)',
                yAxis: lowerPZ
              },
              {
                name: 'Pmax/Z(Pmax)',
                yAxis: upperPZ
              }
            ]
          }
        : undefined
  }

  chart.setOption({
    animation: false,

    legend: {
      top: 8,
      data: ['实际运行曲线', '理论基准线']
    },

    tooltip: {
      trigger: 'axis',

      formatter: paramsList => {
        const list = Array.isArray(paramsList)
          ? paramsList
          : [paramsList]

        return list
          .map(p => {
            if (p.seriesName === '理论基准线') {
              return [
                '<strong>理论基准线 P/Z = kG</strong>',
                `库存量：${Number(p.value?.[0]).toFixed(4)} ×10⁸m³`,
                `P/Z：${Number(p.value?.[1]).toFixed(4)} MPa`
              ].join('<br/>')
            }

            const raw = p.data?.raw
            if (!raw) return ''

            const dirText =
              raw.direction === 'INJECTION'
                ? '注气'
                : raw.direction === 'PRODUCTION'
                  ? '采气'
                  : '-'

            return [
              `<strong>${p.data?.cycleName || raw.cycle || '-'}</strong>`,
              `方向：${dirText}`,
              `库存量 G：${Number(raw.inventory).toFixed(4)} ×10⁸m³`,
              `运行 P/Z：${Number(raw.pressureOverZ).toFixed(4)} MPa`
            ].join('<br/>')
          })
          .filter(Boolean)
          .join('<hr style="margin:4px 0;border-color:#eee"/>')
      },

      axisPointer: {
        type: 'cross'
      }
    },

    grid: {
      left: 92,
      right: 42,
      top: 52,
      bottom: 72
    },

    xAxis: {
      type: 'value',
      name: '库存量 G (10⁸m³)',
      nameLocation: 'middle',
      nameGap: 44,
      scale: true,
      min: value => {
        if (!Number.isFinite(value.min)) return 0
        return value.min >= 0 ? 0 : value.min * 1.05
      },
      max: value => {
        if (!Number.isFinite(value.max)) return 1
        return value.max > 0 ? value.max * 1.10 : 1
      }
    },

    yAxis: {
      type: 'value',
      name: '压力/天然气偏差系数 P/Z (MPa)',
      nameLocation: 'middle',
      nameGap: 68,
      scale: true,
      min: value => {
        if (!Number.isFinite(value.min)) return 0
        return value.min >= 0 ? 0 : value.min * 1.05
      },
      max: value => {
        if (!Number.isFinite(value.max)) return 1
        return value.max > 0 ? value.max * 1.12 : 1
      }
    },

    series: [
      ...actualSeries,
      theoreticalSeries
    ]
  })

  chart.resize()
}

const handleResize = () => chart?.resize()

// 从“汇总数据”切回“结果分析”时重新适配尺寸。
watch(activePanel, async panel => {
  if (panel !== 'analysis' || !result.value) return

  await nextTick()

  if (!chart) {
    updateChart(result.value)
  } else {
    chart.resize()
  }
})

onMounted(() => {
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="diagnostic-workspace">
    <!-- 左侧参数面板 -->
    <aside class="params-panel water-parameter-theme">
      <div class="panel-head">
        参数设置
      </div>
      <div class="panel-body">
        <!-- 井覆盖状态 -->
        <label class="field">
          <span>井覆盖状态</span>
          <div class="coverage-status">
            <el-tag v-if="coverageStatus === 'ready'" type="success" size="small">
              全部就绪 {{ readyWellCount }}/{{ totalWellCount }}
            </el-tag>
            <el-tag v-else-if="coverageStatus === 'partial'" type="danger" size="small">
              部分就绪 {{ readyWellCount }}/{{ totalWellCount }}
            </el-tag>
            <el-tag v-else type="info" size="small">加载中...</el-tag>
          </div>
          <small v-if="missingWellNames.length" class="missing-hint">
            缺少：{{ missingWellNames.join('、') }}
          </small>
        </label>

        <!-- PVT选择 -->
        <label class="field">
          <span>库级代表PVT</span>
          <select v-model="selectedPvtId" @change="onPvtChange">
            <option value="" disabled>
              {{
                pvtOptions.length
                  ? '请选择PVT性质'
                  : '当前库暂无PVT性质'
              }}
            </option>
            <option
              v-for="opt in pvtOptions"
              :key="opt.pvtId"
              :value="String(opt.pvtId)"
            >
              {{ opt.pvtName }}（来自井 {{ opt.sourceWellName }}）
            </option>
          </select>
        </label>

        <!-- 压力上限 -->
        <label class="field">
          <span>压力上限 (MPa)</span>
          <input v-model="upperLimit" placeholder="请输入上限" />
        </label>

        <!-- 压力下限 -->
        <label class="field">
          <span>压力下限 (MPa)</span>
          <input v-model="lowerLimit" placeholder="请输入下限" />
        </label>

        <!-- 操作按钮 -->
        <div class="action-buttons">
          <button
            type="button"
            class="calculate"
            :disabled="calculating || coverageStatus === 'partial'"
            @click="handleCalculate"
          >
            {{ calculating ? '计算中…' : '计算' }}
          </button>
        </div>
      </div>
    </aside>

    <!-- 右侧结果区域 -->
    <main class="result-area">
      <!-- 汇总数据表 -->
      <div v-show="activePanel === 'data'" class="editable-data-grid">
        <el-table :data="summaryRows" border height="100%">
          <el-table-column label="序号" width="60" align="center">
            <template #default="{ row }">
              {{ row.index }}
            </template>
          </el-table-column>
          <el-table-column label="时间" min-width="170" align="center">
            <template #default="{ row }">
              {{ row.time }}
            </template>
          </el-table-column>
          <el-table-column label="注采气量（10⁸m³）" min-width="180" align="center">
            <template #default="{ row }">
              {{ row.gas }}
            </template>
          </el-table-column>
          <el-table-column label="周期" min-width="160" align="center">
            <template #default="{ row }">
              {{ row.cycle }}
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 图表分析 -->
      <div v-show="activePanel === 'analysis'" class="analysis-view">
        <div v-if="!result" class="chart-placeholder">
          请选择 PVT 表并点击"计算"查看库诊断曲线
        </div>
        <div v-show="result" ref="chartEl" class="chart"></div>
      </div>

      <!-- 底部标签页 -->
      <div class="bottom-tabs">
        <button
          type="button"
          class="bottom-chart-tab"
          :class="{ active: activePanel === 'data' }"
          @click="activePanel = 'data'"
        >
          汇总数据
        </button>
        <button
          type="button"
          class="bottom-chart-tab"
          :class="{ active: activePanel === 'analysis' }"
          @click="activePanel = 'analysis'"
        >
          结果分析
        </button>
      </div>
    </main>
  </section>
</template>

<style lang="scss" scoped>
.diagnostic-workspace {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.params-panel {
  width: 360px;
  min-width: 360px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #ddd;
}

.panel-head {
  height: 34px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  background: #f2f2f2;
  border-bottom: 1px solid #ddd;
  font-size: 13px;
}

.panel-body {
  flex: 1;
  overflow: auto;
  padding: 10px 14px;
}

.field {
  display: block;
  margin-bottom: 12px;
  font-size: 12px;
}

.field > span {
  display: block;
  margin-bottom: 4px;
}

.field select,
.field input {
  width: 100%;
  height: 30px;
  box-sizing: border-box;
  border: 1px solid #aaa;
  border-radius: 3px;
  background: #fff;
  padding: 0 8px;
  font-size: 13px;
  outline: none;
}

.field select:focus,
.field input:focus {
  border-color: #888;
}

.coverage-status {
  margin-bottom: 4px;
}

.missing-hint {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  color: #f56c6c;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.action-buttons {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.calculate {
  width: 100%;
  height: 32px;
  padding: 0 24px;
  border: 0;
  border-radius: 3px;
  color: #fff;
  cursor: pointer;
  background: #111;
}

.calculate:disabled {
  opacity: .6;
  cursor: not-allowed;
}

.result-area {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.editable-data-grid,
.analysis-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.chart {
  width: 100%;
  flex: 1;
  min-height: 420px;
}

.chart-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 14px;
}

:deep(.el-table .cell) {
  padding: 0;
  text-align: center;
}

:deep(.el-table th.el-table__cell > .cell) {
  padding: 0 10px;
}

:deep(.el-table td.el-table__cell) {
  padding: 0;
  background: #fff;
}

:deep(.el-table__row:hover > td.el-table__cell) {
  background: #fff !important;
}

.bottom-tabs {
  height: 30px;
  display: flex;
  align-items: flex-end;
  flex-shrink: 0;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.bottom-tabs button {
  height: 30px;
  min-width: 82px;
  padding: 0 14px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-radius: 0;
  background: #fff;
  color: #333;
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
}

.bottom-tabs button:hover {
  background: var(--theme-accent-soft, #fff8d9);
  color: var(--theme-ink, #222);
}

.bottom-tabs button.active {
  background: #fff;
  color: var(--theme-ink, #222);
  box-shadow: inset 0 3px 0 var(--theme-accent, #f4d000);
  font-weight: 600;
}
</style>