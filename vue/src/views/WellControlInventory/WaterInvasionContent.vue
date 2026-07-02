<script setup>
/**
 * WaterInvasionContent.vue
 * 水侵动态分析 —— 右侧内容面板
 * 调用真实接口，左侧参数来自 input，右侧图表来自 outputs[].chartItems
 *
 * 路径：src/views/WellControlInventory/WaterInvasionContent.vue
 */
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import axios from 'axios'

const props = defineProps({
  node:           Object,           // 包含 wellName 字段
  projectId:      [Number, String],
  gasReservoirId: [Number, String]
})

// 通知父组件（IprInterface）刷新左侧树
const emit = defineEmits(['refresh-tree'])

// ─── 常量：方法枚举 ───
const MODIFICATION_METHODS = ['Wichert-Aziz修正方法', 'Pitzer修正方法']
const DEVIATION_METHODS    = ['Dranchuk-Abu-Kassem方法', 'Hall-Yarborough方法', 'BK方法']

// ─── 状态 ───
const loading        = ref(false)
const wellData       = ref(null)
const activeChartIdx = ref(0)

// ─── 从接口数据派生 ───
const input = computed(() => wellData.value?.input || {})

const wgrEnabled = computed(() => (input.value.waterGasRatioLimit ?? -1) > 0)

// 只保留有 chartItems 的 outputs，作为图表标签页
const chartTabs = computed(() => {
  if (!wellData.value?.outputs) return []
  return wellData.value.outputs
      .filter(o => o.chartItems?.length > 0)
      .map(o => ({
        analysisId:  o.analysisId,
        label:       o.chartItems[0].name,
        chartItems:  o.chartItems,
        outputItems: o.outputItems || []
      }))
})

const activeTab = computed(() => chartTabs.value[activeChartIdx.value] || null)

// ─── ECharts ───
const chartEl = ref(null)
let chart = null
const onResize = () => chart?.resize()

/** 简单最小二乘线性回归 */
function linearRegression(pts) {
  const n = pts.length
  if (n < 2) return null
  let sx = 0, sy = 0, sxy = 0, sxx = 0
  for (const [x, y] of pts) { sx += x; sy += y; sxy += x * y; sxx += x * x }
  const denom = n * sxx - sx * sx
  if (Math.abs(denom) < 1e-14) return null
  const slope     = (n * sxy - sx * sy) / denom
  const intercept = (sy - slope * sx) / n
  const yBar = sy / n
  let ssTot = 0, ssRes = 0
  for (const [x, y] of pts) {
    ssTot += (y - yBar) ** 2
    ssRes += (y - (slope * x + intercept)) ** 2
  }
  const r2 = ssTot < 1e-14 ? 0 : 1 - ssRes / ssTot
  return { slope, intercept, r2 }
}

/** 格式化为科学计数法字符串，如 -7.9748E-1 */
function fmtSci(v) {
  if (v === 0) return '0'
  const sign  = v < 0 ? '-' : ''
  const abs   = Math.abs(v)
  const exp   = Math.floor(Math.log10(abs))
  const coeff = (abs / Math.pow(10, exp)).toFixed(4)
  return `${sign}${coeff}E${exp >= 0 ? '+' : ''}${String(exp).padStart(1, '0')}`
}

function renderChart() {
  if (!chart || !activeTab.value) return

  const tab    = activeTab.value
  const isTime = tab.chartItems[0].xAxisField === 'date'
  const COLORS = ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de']
  const series = []

  if (isTime) {
    // ────── 时间序列折线图 ──────
    tab.chartItems.forEach((item, i) => {
      const data = item.data
          .filter(d => !d.isDeleted)
          .map(d => [d.xValue.slice(0, 10), +d.yValue])
      series.push({
        name: item.name,
        type: 'line',
        data,
        smooth: true,
        symbol: 'circle', symbolSize: 5,
        lineStyle: { color: COLORS[i], width: 2 },
        itemStyle: { color: COLORS[i] }
      })
    })

    chart.setOption({
      animation: false,
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'cross',
          crossStyle: { color: '#aaa', type: 'dashed', width: 1 },
          label: { backgroundColor: '#555' }
        },
        formatter: params => params
            .map(p => `${p.marker}${p.seriesName}：<b>${(+p.data[1]).toFixed(4)}</b>`)
            .join('<br/>')
      },
      legend: { bottom: 4, textStyle: { fontSize: 11 }, itemGap: 12 },
      grid: { left: 62, right: 18, top: 28, bottom: 72 },
      xAxis: {
        type: 'category',
        data: tab.chartItems[0].data.map(d => d.xValue.slice(0, 10)),
        axisLabel: { rotate: 30, fontSize: 10, color: '#999' },
        axisLine: { lineStyle: { color: '#ccc' } },
        splitLine: { lineStyle: { color: '#ebebeb' } }
      },
      yAxis: {
        type: 'value',
        axisLabel: { fontSize: 11, color: '#999' },
        splitLine: { lineStyle: { color: '#ebebeb' } }
      },
      series
    }, true)

  } else {
    // ────── X-Y 散点图（PFD / PHD）──────
    const primary = tab.chartItems[0]
    const scatter = primary.data
        .filter(d => !d.isDeleted)
        .map(d => [+d.xValue, +d.yValue])

    // 蓝色散点
    series.push({
      name: primary.name,
      type: 'scatter',
      data: scatter,
      symbolSize: 7,
      itemStyle: { color: '#5470c6', opacity: 0.85 }
    })

    // 线性拟合线（黑色）
    const reg = linearRegression(scatter)
    let eqText = ''
    if (reg) {
      const xs   = scatter.map(d => d[0])
      const xMin = Math.min(...xs), xMax = Math.max(...xs)
      const pad  = (xMax - xMin) * 0.02
      series.push({
        name: '拟合线',
        type: 'line',
        data: [
          [xMin - pad, reg.slope * (xMin - pad) + reg.intercept],
          [xMax + pad, reg.slope * (xMax + pad) + reg.intercept]
        ],
        symbol: 'none',
        lineStyle: { color: '#2a2a2a', width: 1.8 },
        itemStyle: { color: '#2a2a2a' }
      })
      eqText = `y = ${fmtSci(reg.slope)}×X + ${fmtSci(reg.intercept)}    R² = ${reg.r2.toFixed(4)}`
    }

    // 理论线（金色）—— 从 outputItems 中取
    const theoryPts = tab.outputItems
        .filter(o => o.theoreticalRecoveryDegree > 0 && o.theoreticalApparentPressure > 0)
        .map(o => [+o.theoreticalRecoveryDegree, +o.theoreticalApparentPressure])
        .sort((a, b) => a[0] - b[0])

    if (theoryPts.length > 1) {
      series.push({
        name: '理论线',
        type: 'line',
        data: theoryPts,
        symbol: 'none',
        lineStyle: { color: '#e8a500', width: 1.8 },
        itemStyle: { color: '#e8a500' }
      })
    }

    const xName = primary.xAxisField === 'recoveryDegree' ? 'Rg(%)'
        : primary.xAxisField === 'cumulativeGasProduction' ? 'Gp(10⁸m³)'
            : primary.xAxisField
    const yName = primary.yAxisField === 'apparentPressure' ? 'PFD'
        : primary.yAxisField === 'pressure' ? 'P(MPa)'
            : primary.yAxisField

    chart.setOption({
      animation: false,
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'cross',
          crossStyle: { color: '#aaa', type: 'dashed', width: 1 },
          label: { backgroundColor: '#555', formatter: p => (+p.value).toFixed(4) }
        },
        formatter: params => {
          if (!params?.length) return ''
          const [x, y] = params[0].data
          return `X: <b>${(+x).toFixed(4)}</b><br/>Y: <b>${(+y).toFixed(4)}</b>`
        }
      },
      legend: {
        orient: 'vertical', right: 8, top: 'middle',
        itemGap: 10, textStyle: { fontSize: 11 }
      },
      grid: { left: 58, right: 142, top: 28, bottom: 46 },
      xAxis: {
        type: 'value', name: xName, nameLocation: 'middle', nameGap: 28,
        nameTextStyle: { fontSize: 11, color: '#666' },
        axisLine: { lineStyle: { color: '#ccc' } },
        axisLabel: { fontSize: 11, color: '#999' },
        splitLine: { lineStyle: { color: '#ebebeb' } }
      },
      yAxis: {
        type: 'value', name: yName, nameLocation: 'middle', nameGap: 42,
        nameTextStyle: { fontSize: 11, color: '#666' },
        axisLine: { lineStyle: { color: '#ccc' } },
        axisLabel: { fontSize: 11, color: '#999' },
        splitLine: { lineStyle: { color: '#ebebeb' } }
      },
      graphic: eqText ? [{
        type: 'text', right: 148, bottom: 54,
        style: { text: eqText, font: 'italic 11px "Microsoft YaHei", sans-serif', fill: '#555' }
      }] : [],
      series
    }, true)
  }
}

// ─── API 调用 ───
async function fetchData() {
  const wellName = props.node?.wellName
  if (!wellName || !props.projectId || !props.gasReservoirId) return

  loading.value        = true
  activeChartIdx.value = 0
  wellData.value       = null

  try {
    const res = await axios.get(
        `/docker-api/projectanalysis/waterinvasionanalysis/${props.projectId}/${props.gasReservoirId}/well/${encodeURIComponent(wellName)}`
    )
    wellData.value = res.data
    await nextTick()
    renderChart()
    // 数据加载成功后通知父组件刷新左侧树
    emit('refresh-tree')
  } catch (e) {
    console.error('[WaterInvasionContent] 数据加载失败', e)
  } finally {
    loading.value = false
  }
}

watch(() => props.node?.wellName, fetchData, { immediate: true })
watch(activeChartIdx, () => nextTick().then(renderChart))

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', onResize)
  if (wellData.value) renderChart()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="wia-wrap">

    <!-- 左侧参数面板 -->
    <div class="params-panel">
      <div class="panel-head">
        <span>参数设置</span>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="#999">
          <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z"/>
        </svg>
      </div>

      <div class="panel-body">
        <div class="sec-label">气体性质</div>
        <div class="field">
          <label>天然气类型</label>
          <el-select size="small" :model-value="input.gasType || '干气'" style="width:100%">
            <el-option label="干气" value="干气"/>
            <el-option label="湿气" value="湿气"/>
          </el-select>
        </div>
        <div class="field">
          <label>天然气比重</label>
          <el-input size="small" readonly :model-value="input.specificGravity ?? ''"/>
        </div>
        <div class="field">
          <label>H₂S摩尔百分含量(%)</label>
          <el-input size="small" readonly :model-value="input.hydrogenSulfide ?? ''"/>
        </div>
        <div class="field">
          <label>CO₂摩尔百分含量(%)</label>
          <el-input size="small" readonly :model-value="input.carbonDioxide ?? ''"/>
        </div>
        <div class="field">
          <label>N₂摩尔百分含量(%)</label>
          <el-input size="small" readonly :model-value="input.nitrogen ?? ''"/>
        </div>

        <div class="sec-label">计算方法</div>
        <div class="field">
          <label>非烃气体修正方法</label>
          <el-select size="small"
                     :model-value="MODIFICATION_METHODS[input.modificationMethod ?? 0]"
                     style="width:100%">
            <el-option v-for="m in MODIFICATION_METHODS" :key="m" :label="m" :value="m"/>
          </el-select>
        </div>
        <div class="field">
          <label>天然气偏差系数计算方法</label>
          <el-select size="small"
                     :model-value="DEVIATION_METHODS[input.deviationFactorMethod ?? 0]"
                     style="width:100%">
            <el-option v-for="m in DEVIATION_METHODS" :key="m" :label="m" :value="m"/>
          </el-select>
        </div>

        <div class="sec-label">其他数据</div>
        <div class="field">
          <label>原始地层压力  (MPa)</label>
          <el-input size="small" readonly :model-value="input.originalFormationPressure ?? ''"/>
        </div>
        <div class="field">
          <label>底层温度  (°C)</label>
          <el-input size="small" readonly :model-value="input.formationTemperature ?? ''"/>
        </div>
        <div class="field">
          <label>束缚水饱和度  (%)</label>
          <el-input size="small" readonly :model-value="input.waterSaturation ?? ''"/>
        </div>
        <div class="field">
          <div class="wgr-label-row">
            <span>生产水气比上限(m³/10⁴m³)</span>
            <el-switch
                :model-value="wgrEnabled"
                disabled
                style="--el-switch-on-color:#e8a000;--el-switch-off-color:#ccc"
                size="small"
            />
          </div>
          <el-input
              size="small" readonly :disabled="!wgrEnabled"
              :model-value="wgrEnabled ? input.waterGasRatioLimit : ''"
          />
        </div>

        <div class="sec-label">生产数据</div>
        <div class="btn-row">
          <el-button size="small">模版下载</el-button>
          <el-button size="small">导入</el-button>
        </div>
      </div>
    </div>

    <!-- 右侧图表区域 -->
    <div class="chart-area">
      <div v-if="chartTabs.length" class="chart-tabs">
        <div
            v-for="(tab, i) in chartTabs"
            :key="tab.analysisId"
            class="chart-tab"
            :class="{ active: i === activeChartIdx }"
            @click="activeChartIdx = i"
        >{{ tab.label }}</div>
      </div>
      <div ref="chartEl" class="chart-instance"/>
    </div>

  </div>
</template>

<style lang="scss" scoped>
.wia-wrap {
  display: flex;
  height: 100%;
  background: #fff;
  overflow: hidden;
}

.params-panel {
  width: 238px;
  min-width: 238px;
  border-right: 1px solid #e0e0e0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 12px 6px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
  font-size: 13px;
  color: #333;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 4px 12px 14px;
}

.sec-label {
  font-weight: 500;
  color: #333;
  font-size: 13px;
  margin: 10px 0 7px;
  &:first-child { margin-top: 4px; }
}

.field {
  margin-bottom: 9px;
  label {
    display: block;
    color: #555;
    font-size: 12px;
    margin-bottom: 3px;
  }
}

.wgr-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 3px;
  span { color: #555; font-size: 12px; }
}

.btn-row { display: flex; gap: 8px; }

.chart-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chart-tabs {
  display: flex;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
  background: #fafafa;
}

.chart-tab {
  padding: 6px 14px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  white-space: nowrap;
  transition: color 0.15s, border-color 0.15s;
  &:hover { color: #409eff; }
  &.active {
    color: #409eff;
    border-bottom-color: #409eff;
    background: #fff;
    font-weight: 500;
  }
}

.chart-instance {
  flex: 1;
  min-height: 0;
  width: 100%;
}
</style>
