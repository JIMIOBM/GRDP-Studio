<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const activeTab = ref('chart')
const chartEl = ref(null)
let chart = null

const demoData = [
  [0.1, 12.5], [0.2, 10.8], [0.4, 9.2], [0.8, 7.8],
  [1.6, 6.5], [3.2, 5.4], [6.4, 4.5], [12.8, 3.8],
  [25.6, 3.2], [51.2, 2.7], [102.4, 2.3], [204.8, 1.9],
  [409.6, 1.6], [819.2, 1.3]
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
      text: 'Blasingame典型曲线',
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
        name: 'Blasingame',
        type: 'line',
        data: demoData,
        symbolSize: 7,
        smooth: true,
        lineStyle: { color: '#91cc75', width: 2 },
        itemStyle: { color: '#91cc75' }
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
  <div class="bg-wrap">
    <aside class="bg-panel">
      <div class="panel-title">Blasingame</div>
      <div class="section-title">输入参数</div>
      <div class="field"><label>井名</label><el-input size="small" readonly :model-value="node?.wellName || ''" /></div>
      <div class="field"><label>项目 ID</label><el-input size="small" readonly :model-value="projectId" /></div>
      <div class="field"><label>气藏 ID</label><el-input size="small" readonly :model-value="gasReservoirId" /></div>
      <div class="section-title">曲线设置</div>
      <div class="field"><label>曲线类型</label><el-input size="small" readonly model-value="Blasingame" /></div>
      <div class="field"><label>坐标类型</label><el-input size="small" readonly model-value="双对数" /></div>
    </aside>

    <main class="bg-main">
      <div class="tabs">
        <button :class="{ active: activeTab === 'chart' }" @click="activeTab = 'chart'">曲线图</button>
        <button :class="{ active: activeTab === 'table' }" @click="activeTab = 'table'">数据列表</button>
      </div>

      <div v-show="activeTab === 'chart'" ref="chartEl" class="chart"></div>

      <div v-if="activeTab === 'table'" class="table-wrap">
        <el-table :data="tableRows" size="small" height="100%" border>
          <el-table-column prop="index" label="序号" width="70" />
          <el-table-column prop="time" label="tD(dless)" min-width="160" />
          <el-table-column prop="rate" label="qD(dless)" min-width="160" />
        </el-table>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.bg-wrap {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.bg-panel {
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

.bg-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.tabs {
  height: 34px;
  display: flex;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;

  button {
    border: 0;
    border-right: 1px solid #e4e7ed;
    background: transparent;
    padding: 0 18px;
    color: #555;
    cursor: pointer;

    &.active {
      background: #fff;
      color: #409eff;
      font-weight: 600;
      border-bottom: 2px solid #409eff;
    }
  }
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
</style>