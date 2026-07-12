<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const activeTab = ref('chart')
const chartEl = ref(null)
let chart = null

const currentWellName = computed(() =>
    props.node?.wellName || props.node?.raw?.wellName || props.node?.raw?.input?.wellName || props.node?.raw?.input?.wellNames?.[0] || ''
)

const chartTabTitle = computed(() =>
    `诊断曲线-Wattenbarger-${currentWellName.value || '当前井'}-分析结果`
)

const demoData = [
  [0.08, 9.8], [0.16, 8.6], [0.32, 7.4], [0.64, 6.2],
  [1.28, 5.1], [2.56, 4.15], [5.12, 3.35], [10.24, 2.68],
  [20.48, 2.05], [40.96, 1.58], [81.92, 1.2]
]

const tableRows = demoData.map(([time, rate], index) => ({
  index: index + 1,
  time,
  rate
}))

function renderChart() {
  if (!chart) return
  chart.setOption({
    animation: false,
    title: {
      text: 'Wattenbarger典型曲线',
      left: 'center',
      top: 10,
      textStyle: { color: '#333', fontSize: 16, fontWeight: 600 }
    },
    tooltip: {
      trigger: 'item',
      formatter: params => `${params.marker}数据点<br/>tD: ${params.value[0]}<br/>qD: ${params.value[1]}`
    },
    grid: { left: 72, right: 34, top: 58, bottom: 62 },
    xAxis: {
      type: 'log',
      name: 'tD(dless)',
      nameLocation: 'middle',
      nameGap: 36,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    yAxis: {
      type: 'log',
      name: 'qD(dless)',
      nameLocation: 'middle',
      nameGap: 46,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    series: [
      {
        name: 'Wattenbarger',
        type: 'line',
        data: demoData,
        symbolSize: 7,
        smooth: true,
        lineStyle: { color: '#5470c6', width: 2 },
        itemStyle: { color: '#5470c6' }
      }
    ]
  }, true)
}

watch(activeTab, () => nextTick(renderChart))

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', chart.resize)
  renderChart()
})

onBeforeUnmount(() => {
  if (chart) {
    window.removeEventListener('resize', chart.resize)
    chart.dispose()
  }
})
</script>

<template>
  <div class="wb-wrap">
    <aside class="wb-panel">
      <div class="panel-title">Wattenbarger</div>
      <div class="section-title">输入参数</div>
      <div class="field"><label>井名</label><el-input size="small" readonly :model-value="node?.wellName || ''" /></div>
      <div class="field"><label>项目 ID</label><el-input size="small" readonly :model-value="projectId" /></div>
      <div class="field"><label>气藏 ID</label><el-input size="small" readonly :model-value="gasReservoirId" /></div>
      <div class="section-title">曲线设置</div>
      <div class="field"><label>曲线类型</label><el-input size="small" readonly model-value="Wattenbarger" /></div>
      <div class="field"><label>坐标类型</label><el-input size="small" readonly model-value="双对数" /></div>
    </aside>

    <main class="wb-main">
      <div class="dynamic-result-tabs">
        <button type="button" class="dynamic-result-tab active" :title="chartTabTitle">
          <span class="dynamic-result-tab-text">{{ chartTabTitle }}</span>
        </button>
      </div>

      <div v-show="activeTab === 'chart'" ref="chartEl" class="chart"></div>

      <div v-if="activeTab === 'table'" class="table-wrap">
        <el-table :data="tableRows" size="small" height="100%" border>
          <el-table-column prop="index" label="序号" width="70" />
          <el-table-column prop="time" label="tD(dless)" min-width="160" />
          <el-table-column prop="rate" label="qD(dless)" min-width="160" />
        </el-table>
      </div>

      <div class="bottom-chart-tabs">
        <button type="button" class="bottom-chart-tab" :class="{ active: activeTab === 'table' }" @click="activeTab = 'table'">
          数据列表
        </button>
        <button type="button" class="bottom-chart-tab" :class="{ active: activeTab === 'chart' }" :title="chartTabTitle" @click="activeTab = 'chart'">
          结果分析图
        </button>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.wb-wrap {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.wb-panel {
  width: 280px;
  min-width: 280px;
  border-right: 1px solid #e0e0e0;
  overflow-y: auto;
  padding: 10px 12px 16px;
}

.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #222;
  margin-bottom: 12px;
}

.section-title {
  font-size: 14px;
  color: #333;
  margin: 12px 0 8px;
  font-weight: 600;
}

.field {
  margin-bottom: 9px;

  label {
    display: block;
    font-size: 12px;
    color: #666;
    margin-bottom: 3px;
  }
}

.wb-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.dynamic-result-tabs {
  height: 34px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
  overflow-x: auto;
  overflow-y: hidden;
}

.dynamic-result-tab {
  height: 34px;
  max-width: 320px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-bottom: 2px solid #409eff;
  background: transparent;
  color: #409eff;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  cursor: default;
  white-space: nowrap;
}

.dynamic-result-tab-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart {
  flex: 1;
  min-height: 0;
  width: 100%;
}

.table-wrap {
  flex: 1;
  min-height: 0;
  padding: 10px;
}

.bottom-chart-tabs {
  display: flex;
  align-items: flex-end;
  height: 30px;
  flex-shrink: 0;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.bottom-chart-tab {
  min-width: 88px;
  height: 30px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-top: 2px solid transparent;
  background: #fff;
  color: #333;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;

  &:hover {
    color: #409eff;
  }

  &.active {
    color: #409eff;
    border-top-color: #409eff;
    font-weight: 600;
  }
}
</style>
