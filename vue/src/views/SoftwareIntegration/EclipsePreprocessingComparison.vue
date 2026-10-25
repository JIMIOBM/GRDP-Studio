<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
const props = defineProps({
  comparison: { type: Object, required: true },
  currentRunId: Number,
  baselineRunId: Number,
})
const host = ref(null),
  selected = ref('')
const metric = computed(
  () =>
    props.comparison.metrics.find((value) => value.key === selected.value) ??
    props.comparison.metrics[0],
)
let chart, observer
function draw() {
  if (!chart || !metric.value) return
  const item = metric.value
  if (selected.value !== item.key) selected.value = item.key
  chart.setOption(
    {
      animation: false,
      color: ['#9aaebc', '#287fb9'],
      legend: { top: 0 },
      tooltip: { trigger: 'axis' },
      grid: { left: 80, right: 28, top: 48, bottom: 55 },
      xAxis: {
        type: 'value',
        name: '累计天数',
        nameLocation: 'middle',
        nameGap: 33,
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        name: item.unit,
        scale: true,
        splitLine: { lineStyle: { color: '#edf1f5' } },
      },
      series: [
        {
          name: `原计算 #${props.baselineRunId}`,
          type: 'line',
          data: item.points.map((point) => [point.timeDays, point.baseline]),
          smooth: false,
          symbolSize: 4,
          lineStyle: { type: 'dashed', width: 2 },
        },
        {
          name: `新计算 #${props.currentRunId}`,
          type: 'line',
          data: item.points.map((point) => [point.timeDays, point.current]),
          smooth: false,
          symbolSize: 4,
          lineStyle: { width: 3 },
        },
      ],
    },
    true,
  )
}
const number = (value) => Number(value).toLocaleString(undefined, { maximumSignificantDigits: 9 })
onMounted(() => {
  chart = echarts.init(host.value, null, { renderer: 'svg' })
  observer = new ResizeObserver(() => chart.resize())
  observer.observe(host.value)
  draw()
})
watch([metric, () => props.comparison], draw)
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>
<template>
  <section class="comparison-result" aria-label="前处理两次真实计算对比">
    <header>
      <div>
        <span>真实计算结果</span>
        <h3>修改参数带来了什么变化</h3>
      </div>
      <el-select
        popper-class="preprocessing-select-menu"
        v-model="selected"
        placeholder="选择指标"
        aria-label="前处理结果对比指标"
        style="width: 260px"
        ><el-option
          v-for="item in comparison.metrics"
          :key="item.key"
          :label="`${item.label} (${item.unit})`"
          :value="item.key"
      /></el-select>
    </header>
    <div class="metric-cards">
      <article v-for="item in comparison.metrics.slice(0, 4)" :key="item.key">
        <small>{{ item.label }} · {{ item.unit }}</small
        ><strong>{{ number(item.final.current) }}</strong>
        <p>
          原值 {{ number(item.final.baseline) }}
          <span :class="{ changed: item.final.difference !== 0 }"
            >差值 {{ number(item.final.difference) }}</span
          >
        </p>
        <small>共同报告时刻：第 {{ item.final.timeDays }} 天</small>
      </article>
    </div>
    <div ref="host" class="comparison-chart" />
    <p class="clock-note">
      只使用同名对象、原单位和共有真实报告时刻。缺失时刻不补零、不插值。曲线连线辅助阅读。
    </p>
  </section>
</template>
<style scoped>
.comparison-result {
  margin: 12px 0;
  padding: 12px;
  border: 1px solid #deded9;
  background: #fff;
}
header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  min-height: 34px;
  margin: -12px -12px 12px;
  padding: 6px 12px;
  border-bottom: 1px solid #deded9;
  background: #f5f5f1;
}
header > div {
  display: flex;
  align-items: center;
  gap: 12px;
}
header span {
  color: #777;
  font-size: 11px;
}
h3 {
  margin: 0;
  color: #333;
  font-size: 13px;
  font-weight: 600;
}
.metric-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 10px 0;
  border: 1px solid #deded9;
}
article {
  min-width: 0;
  padding: 10px 12px;
  background: #fff;
  border-right: 1px solid #e1e1dc;
}
article:last-child {
  border-right: 0;
}
small {
  color: #777;
  font-size: 11px;
}
strong {
  display: block;
  margin: 9px 0 6px;
  color: #333;
  font-size: 22px;
  font-weight: 500;
  overflow-wrap: anywhere;
}
article p {
  color: #666;
  font-size: 12px;
  line-height: 1.6;
}
article p span {
  margin-left: 6px;
}
.changed {
  color: #966b20;
  font-weight: 600;
}
.comparison-chart {
  height: 320px;
}
.clock-note {
  margin: 8px 0 0;
  color: #888;
  font-size: 11px;
}
@media (max-width: 1100px) {
  .metric-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  article {
    border-bottom: 1px solid #e1e1dc;
  }
}
@media (max-width: 650px) {
  header,
  header > div {
    flex-wrap: wrap;
  }
  .metric-cards {
    grid-template-columns: 1fr;
  }
}
</style>
