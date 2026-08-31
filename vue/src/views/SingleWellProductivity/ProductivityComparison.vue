<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  wellName: { type: String, default: '' },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  methodType: {
    type: String,
    default: '多周期',
    validator: value => ['多周期', '多方法', '注采对比'].includes(value)
  }
})

// 产能试井选择
const testBackPressure = ref(false)
const testIsochronal = ref(false)
const testModifiedIsochronal = ref(false)
const testSinglePoint = ref(false)

// 理论计算选择
const theoryStableFlow = ref(false)
const theoryUnstableFlow = ref(false)

// 时间段 - 使用字符串格式 YYYY-MM-DD
const startDate = ref('')
const endDate = ref('')

// 对比类型
const comparisonType = ref('注气')

// 地层压力
const formationPressure = ref('')

const chartEl = ref(null)
let chart = null

const initChart = () => {
  if (!chartEl.value) return
  chart = echarts.init(chartEl.value)
  chart.setOption({
    grid: {
      left: 50,
      right: 30,
      top: 30,
      bottom: 50
    },
    xAxis: {
      type: 'value',
      name: '回压试井',
      nameLocation: 'middle',
      nameGap: 30,
      nameTextStyle: { fontSize: 12, color: '#555' },
      axisLine: { lineStyle: { color: '#d0d0d0' } },
      axisTick: { show: false },
      axisLabel: { show: false },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      name: '无阻流量',
      nameLocation: 'middle',
      nameGap: 40,
      nameTextStyle: { fontSize: 12, color: '#555' },
      axisLine: { lineStyle: { color: '#d0d0d0' } },
      axisTick: { show: false },
      axisLabel: { show: false },
      splitLine: { show: false }
    },
    series: []
  })
}

const handleResize = () => {
  chart?.resize()
}

onMounted(() => {
  nextTick(() => initChart())
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  if (chart) {
    chart.dispose()
    chart = null
  }
  window.removeEventListener('resize', handleResize)
})

const handleCalculate = () => {
  console.log('产能对比计算:', props.methodType)
}
</script>

<template>
  <div class="comparison-workspace">
    
    <!-- 左侧参数面板 -->
    <aside class="parameter-panel">
      
      <!-- 参数设置标题 -->
      <div class="panel-header">
        <h3 class="panel-title">参数设置</h3>
      </div>

      <div class="parameter-form">
        
        <!-- 产能试井 -->
        <fieldset class="field-section">
          <legend class="section-title">产能试井</legend>
          <div class="checkbox-grid">
            <label class="checkbox-label">
              <input type="checkbox" v-model="testBackPressure" />
              <span>回压试井</span>
            </label>
            <label class="checkbox-label">
              <input type="checkbox" v-model="testIsochronal" />
              <span>等时试井</span>
            </label>
            <label class="checkbox-label">
              <input type="checkbox" v-model="testModifiedIsochronal" />
              <span>修正等时</span>
            </label>
            <label class="checkbox-label">
              <input type="checkbox" v-model="testSinglePoint" />
              <span>一点法</span>
            </label>
          </div>
        </fieldset>

        <!-- 理论计算 -->
        <fieldset class="field-section">
          <legend class="section-title">理论计算</legend>
          <div class="checkbox-grid two-cols">
            <label class="checkbox-label">
              <input type="checkbox" v-model="theoryStableFlow" />
              <span>稳定流</span>
            </label>
            <label class="checkbox-label">
              <input type="checkbox" v-model="theoryUnstableFlow" />
              <span>不稳定流</span>
            </label>
          </div>
        </fieldset>

        <!-- 时间段 -->
        <div class="field-group">
          <label class="field-label">时间段</label>
          <div class="date-range-picker">
            <input 
              type="date" 
              v-model="startDate"
              placeholder="开始日期"
              class="date-input"
            />
            <span class="date-separator">~</span>
            <input 
              type="date" 
              v-model="endDate"
              placeholder="结束日期"
              class="date-input"
            />
          </div>
        </div>

        <!-- 对比类型 -->
        <fieldset 
          v-if="methodType !== '注采对比'" 
          class="field-section"
        >
          <legend class="section-title">对比类型</legend>
          <div class="radio-group-horizontal">
            <label class="radio-label">
              <input 
                type="radio" 
                name="comparison-type" 
                value="注气"
                v-model="comparisonType"
              />
              <span>注气</span>
            </label>
            <label class="radio-label">
              <input 
                type="radio" 
                name="comparison-type" 
                value="采气"
                v-model="comparisonType"
              />
              <span>采气</span>
            </label>
          </div>
        </fieldset>

        <!-- 计算无阻流量的地层压力 -->
        <div class="field-group">
          <label class="field-label">计算无阻流量的地层压力</label>
          <input 
            type="text" 
            v-model="formationPressure"
            placeholder="请输入"
            class="text-input"
          />
        </div>

        <!-- 计算按钮 -->
        <button 
          type="button" 
          class="calculate-btn"
          @click="handleCalculate"
        >
          计算
        </button>

      </div>
    </aside>

        <main class="result-panel">
      <div ref="chartEl" class="chart-container"></div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.comparison-workspace {
  flex: 1;
  display: flex;
  gap: 0;
  min-height: 0;
  background: #fff;
}

/* ========== 左侧参数面板 ========== */
.parameter-panel {
  width: 280px;
  flex: 0 0 280px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #e5e5e5;
  background: #fafafa;
  overflow-y: auto;
}

.panel-header {
  padding: 12px 16px;
  border-bottom: 1px solid #e5e5e5;
  background: #f0f0f0;
}

.panel-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #333;
}

.parameter-form {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 字段分组 */
.field-section {
  border: none;
  margin: 0;
  padding: 0;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #333;
  padding: 0 0 8px 0;
  display: block;
}

.field-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 13px;
  color: #555;
  font-weight: 500;
}

/* 复选框网格 */
.checkbox-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px 12px;

  &.two-cols {
    grid-template-columns: repeat(2, 1fr);
  }
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #555;
  cursor: pointer;
  
  input[type="checkbox"] {
    cursor: pointer;
    width: 14px;
    height: 14px;
    accent-color: #f4d000;
  }
  
  span {
    user-select: none;
  }
}

/* 单选按钮组 */
.radio-group-horizontal {
  display: flex;
  gap: 20px;
}

.radio-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #555;
  cursor: pointer;
  
  input[type="radio"] {
    cursor: pointer;
    width: 14px;
    height: 14px;
    accent-color: #f4d000;
  }
  
  span {
    user-select: none;
  }
}

/* 日期范围选择器 */
.date-range-picker {
  display: flex;
  align-items: center;
  gap: 6px;
  
  .date-input {
    flex: 1;
    height: 30px;
    padding: 0 8px;
    border: 1px solid #dcdcdc;
    border-radius: 3px;
    font-size: 12px;
    background: #fff;
    color: #252525;
    
    &:focus {
      outline: none;
      border-color: #f4d000;
      box-shadow: 0 0 0 2px rgba(244, 208, 0, 0.15);
    }
    
    /* Webkit浏览器日期输入框样式优化 */
    &::-webkit-calendar-picker-indicator {
      cursor: pointer;
      opacity: 0.6;
      transition: opacity 0.2s;
      
      &:hover {
        opacity: 1;
      }
    }
    
    &::placeholder {
      color: #aaa;
    }
  }
  
  .date-separator {
    color: #999;
    font-size: 13px;
    user-select: none;
  }
}

/* 文本输入框 */
.text-input {
  height: 32px;
  padding: 0 10px;
  border: 1px solid #dcdcdc;
  border-radius: 4px;
  font-size: 13px;
  background: #fff;
  
  &:focus {
    outline: none;
    border-color: #f4d000;
  }

  &::placeholder {
    color: #aaa;
  }
}

/* 计算按钮 */
.calculate-btn {
  width: 100%;
  height: 36px;
  background: #202020;
  color: #fff;
  border: none;
  border-radius: 4px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  
  &:hover {
    background: #333;
  }
  
  &:active {
    transform: scale(0.98);
  }
}

/* ========== 右侧结果区域========== */
.result-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  position: relative;
  background: #ffffff;
}
.chart-container {
  flex: 1;
  margin: 30px 60px 30px 35px;
  min-height: 450px;
}
</style>