<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { preprocessingCurves, preprocessingTimeline } from './eclipsePreprocessing.js'

const props = defineProps({
  template: { type: Object, required: true },
  values: { type: Array, required: true },
})
const curveHost = ref(null),
  compositionHost = ref(null),
  keyword = ref('')
const names = {
  PVTO: '油相PVT',
  PVDG: '气相PVT',
  SWOF: '油水相渗',
  SGOF: '油气相渗',
  SWFN: '水相相渗',
  SGFN: '气相相渗',
  SOF3: '油相相渗',
  PCRIT: '组分临界压力',
  TCRIT: '组分临界温度',
  SPECHA: '组分液相比热',
  OILVISCT: '油相黏温关系',
  GASVISCT: '气相黏温关系',
  TEMPVD: '初始温度剖面',
}
const choices = computed(() =>
  Object.entries(names).filter(([key]) =>
    props.template.fields.some((field) => field.keyword === key),
  ),
)
const timeline = computed(() => preprocessingTimeline(props.template, props.values))
const controlNames = {
  WCONINJE: '注入井控制',
  WCONPROD: '生产井控制',
  WINJTEMP: '蒸汽条件',
  GRUPSALE: '售气目标',
  GCONINJE: '注气组控制',
  WELTARG: '调整目标',
  WELOPEN: '井状态变化',
}
const composition = computed(() => {
  const inputs = new Map(props.values.map((value) => [value.id, Number(value.value)]))
  return props.template.fields
    .filter(
      (field) =>
        field.keyword === 'ZMFVD' &&
        field.streamIndex > 0 &&
        field.streamIndex <= props.template.components.length,
    )
    .map((field, index) => ({
      name: props.template.components[index],
      value: inputs.get(field.id),
    }))
})
let curveChart, compositionChart, observer

function draw() {
  if (!curveChart) return
  if (!choices.value.some(([value]) => value === keyword.value))
    keyword.value = choices.value[0]?.[0] ?? ''
  const curves = preprocessingCurves(props.template, props.values, keyword.value)
  if (curves) {
    const units = [...new Set(curves.series.map((series) => series.unit))]
    curveChart.setOption(
      {
        color: ['#3177b8', '#36a6a0', '#d4964d', '#8070c6', '#bd708a'],
        animation: false,
        tooltip: { trigger: 'axis' },
        legend: { top: 0, type: 'scroll' },
        grid: { left: 68, right: units.length > 1 ? 66 : 26, top: 45, bottom: 60 },
        xAxis: {
          type: 'value',
          name: `${curves.x} (${curves.xUnit})`,
          nameLocation: 'middle',
          nameGap: 35,
          axisLine: { lineStyle: { color: '#b4c3cf' } },
          splitLine: { lineStyle: { color: '#edf1f5' } },
        },
        yAxis: units.map((unit, index) => ({
          type: 'value',
          name: unit,
          position: index ? 'right' : 'left',
          splitLine: { show: index === 0, lineStyle: { color: '#edf1f5' } },
        })),
        series: curves.series.map((series) => ({
          name: series.name,
          type: 'line',
          data: series.data,
          yAxisIndex: units.indexOf(series.unit),
          symbolSize: 4,
          lineStyle: { width: 2.5 },
          smooth: false,
        })),
      },
      true,
    )
  }
  if (compositionChart)
    compositionChart.setOption(
      {
        animation: false,
        tooltip: {
          trigger: 'item',
          formatter: (item) => `${item.name}<br/>摩尔分数 ${item.value}`,
        },
        legend: { bottom: 0, type: 'scroll' },
        color: [
          '#4b83b6',
          '#5bafbd',
          '#66b3a1',
          '#e1bd60',
          '#ce8a56',
          '#9c80b3',
          '#d594a3',
          '#8d9dba',
          '#7d9585',
        ],
        series: [
          {
            type: 'pie',
            radius: ['42%', '66%'],
            center: ['50%', '44%'],
            data: composition.value,
            label: { formatter: '{b}\n{d}%', fontSize: 11 },
            itemStyle: { borderWidth: 2, borderColor: '#fff' },
          },
        ],
      },
      true,
    )
}
onMounted(() => {
  curveChart = echarts.init(curveHost.value, null, { renderer: 'svg' })
  if (compositionHost.value)
    compositionChart = echarts.init(compositionHost.value, null, { renderer: 'svg' })
  observer = new ResizeObserver(() => {
    curveChart?.resize()
    compositionChart?.resize()
  })
  observer.observe(curveHost.value)
  draw()
})
watch([() => props.values, keyword], draw, { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  curveChart?.dispose()
  compositionChart?.dispose()
})
</script>

<template>
  <div class="input-charts">
    <section class="property-card">
      <header>
        <div>
          <span>输入预览</span>
          <h3>物性与初始状态</h3>
        </div>
        <el-select
          popper-class="preprocessing-select-menu"
          v-model="keyword"
          aria-label="输入物性曲线"
          style="width: 195px"
          ><el-option v-for="[value, label] in choices" :key="value" :value="value" :label="label"
        /></el-select>
      </header>
      <div ref="curveHost" class="chart" />
    </section>
    <section v-if="composition.length" class="property-card composition">
      <header>
        <div>
          <span>输入预览</span>
          <h3>初始组分比例</h3>
        </div>
        <small>第一个组成深度 · 原始摩尔分数</small>
      </header>
      <div ref="compositionHost" class="chart" />
    </section>
  </div>
  <section class="timeline-card">
    <header>
      <div>
        <span>输入预览</span>
        <h3>注采时间计划</h3>
      </div>
      <small>累计天数来自填写的报告间隔，不是计算结果</small>
    </header>
    <div class="timeline">
      <div v-for="(event, index) in timeline" :key="index" class="event">
        <div class="time">第 {{ event.timeDays.toLocaleString() }} 天</div>
        <i />
        <div class="event-detail">
          <strong
            >{{ event.object }}
            <span>{{ controlNames[event.keyword] || event.keyword }}</span></strong
          >
          <p>{{ event.description }}</p>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.input-charts {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: 12px;
  margin-top: 12px;
}
.input-charts:has(> section:only-child) {
  grid-template-columns: 1fr;
}
.property-card,
.timeline-card {
  min-width: 0;
  background: #fff;
  border: 1px solid #deded9;
  overflow: hidden;
}
header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  min-height: 34px;
  padding: 6px 10px;
  background: #f5f5f1;
  border-bottom: 1px solid #e1e1dc;
}
header > div {
  display: flex;
  align-items: center;
  gap: 10px;
}
header span {
  color: #777;
  font-size: 11px;
}
h3 {
  margin: 0;
  color: #333;
  font-size: 13px;
  font-weight: 500;
}
small {
  color: #777;
  font-size: 11px;
}
.chart {
  height: 290px;
  margin: 8px;
}
.timeline-card {
  margin-top: 12px;
}
.timeline {
  max-height: 320px;
  overflow: auto;
  padding: 2px 12px 10px;
}
.event {
  display: grid;
  grid-template-columns: 100px 14px minmax(0, 1fr);
  gap: 10px;
  font-size: 12px;
}
.time {
  padding-top: 11px;
  color: #666;
}
.event > i {
  width: 6px;
  height: 6px;
  margin-top: 15px;
  background: #b59b17;
  border-radius: 50%;
}
.event-detail {
  padding: 9px 0;
  border-bottom: 1px solid #eee;
}
.event-detail strong {
  color: #333;
  font-weight: 500;
}
.event-detail span {
  margin-left: 10px;
  color: #777;
  font-weight: 400;
}
.event-detail p {
  margin: 4px 0 0;
  color: #666;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
@media (max-width: 1000px) {
  .input-charts {
    grid-template-columns: 1fr;
  }
  header {
    flex-wrap: wrap;
  }
}
</style>
