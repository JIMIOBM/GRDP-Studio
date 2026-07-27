<script setup>
import { ref, computed } from 'vue'

const activeResultTab = ref('数据列表')
const activeCurve = ref('曲线1')
const analysisTableCollapsed = ref(false)
const porosity = ref('25')

const rockPropertyColumns = [
  '岩石孔隙度（%）',
]

const rockCurveOptions = [
  {
    name: '曲线1',
    leftYAxis: '胶结砂岩压缩系数(MPa⁻¹)',
    leftTableColumn: '胶结砂岩压缩系数(MPa⁻¹)',
  },
  {
    name: '曲线2',
    leftYAxis: '碳酸盐岩压缩系数(MPa⁻¹)',
    leftTableColumn: '碳酸盐岩压缩系数(MPa⁻¹)',
  }
]

const activeCurveOption = computed(() => rockCurveOptions.find(item => item.name === activeCurve.value))

const analysisTableColumns = computed(() => [
  '岩石孔隙度（%）',
  activeCurveOption.value?.leftTableColumn || '胶结砂岩压缩系数(MPa⁻¹)',
])

const analysisDataCellCount = computed(() => analysisTableColumns.value.length * 25)
</script>

<template>
  <div class="rock-properties-view">
    <div v-if="activeResultTab === '数据列表'" class="rock-workspace">
      <aside class="rock-parameter-panel">
        <div class="rock-parameter-section">
          <label class="rock-field-group">
            <span>岩石孔隙度（%）</span>
            <input v-model="porosity" inputmode="decimal" />
          </label>
        </div>
      </aside>

      <div class="rock-data-grid" aria-label="岩石性质数据表格">
        <div v-for="column in rockPropertyColumns" :key="column" class="rock-grid-cell header">
          {{ column }}
        </div>
        <div v-for="cell in rockPropertyColumns.length * 27" :key="`data-${cell}`" class="rock-grid-cell"></div>
      </div>
    </div>

    <div v-else class="rock-analysis-workspace" :class="{ 'table-collapsed': analysisTableCollapsed }">
      <aside class="rock-analysis-panel" :class="{ collapsed: analysisTableCollapsed }">
        <button v-if="analysisTableCollapsed" class="rock-analysis-collapsed-tab" type="button" title="展开分析数据表"
          @click="analysisTableCollapsed = false">
          图表数据
        </button>

        <template v-else>
          <div class="rock-analysis-expanded">
            <div class="rock-analysis-panel-heading">
              <span>图表数据</span>
              <button class="rock-analysis-toggle" type="button" title="收起图表数据" @click="analysisTableCollapsed = true">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
                  <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
                </svg>
              </button>
            </div>
            <div class="rock-analysis-grid" aria-label="岩石分析数据表格"
              :style="{ '--analysis-column-count': analysisTableColumns.length }">
              <div v-for="column in analysisTableColumns" :key="column" class="rock-analysis-grid-cell header">
                {{ column }}
              </div>
              <div v-for="cell in analysisDataCellCount" :key="`analysis-data-${cell}`" class="rock-analysis-grid-cell">
              </div>
            </div>
          </div>
        </template>
      </aside>

      <section class="rock-chart-panel" aria-label="岩石结果分析图">
        <div class="rock-curve-selector">
          <label v-for="curve in rockCurveOptions" :key="curve.name">
            <input v-model="activeCurve" type="radio" :value="curve.name" />
            <span>{{ curve.name }}</span>
          </label>
        </div>

        <div class="rock-chart" :class="{ 'has-right-axis': activeCurveOption.rightYAxis }">
          <div class="rock-chart-y-title rock-chart-y-title-left">
            {{ activeCurveOption.leftYAxis }}
          </div>
          <div class="rock-chart-plot"></div>
          <div v-if="activeCurveOption.rightYAxis" class="rock-chart-y-title rock-chart-y-title-right">
            {{ activeCurveOption.rightYAxis }}
          </div>
          <div class="rock-chart-x-title">岩石孔隙度(%)</div>
        </div>
      </section>
    </div>

    <footer class="rock-result-tabs">
      <button type="button" class="rock-result-tab" :class="{ active: activeResultTab === '数据列表' }"
        @click="activeResultTab = '数据列表'">
        数据列表
      </button>
      <button type="button" class="rock-result-tab" :class="{ active: activeResultTab === '结果分析图' }"
        @click="activeResultTab = '结果分析图'">
        结果分析图
      </button>
    </footer>
  </div>
</template>

<style lang="scss" scoped>
.rock-properties-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.rock-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  padding: 10px 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.rock-analysis-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  padding: 10px 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.rock-analysis-workspace.table-collapsed {
  padding: 10px 12px;
}

.rock-analysis-panel {
  width: 760px;
  flex: 0 0 760px;
  height: 100%;
  min-height: 0;
  align-self: stretch;
  display: flex;
  position: relative;
  transition: width 0.16s ease, flex-basis 0.16s ease;

  &.collapsed {
    width: 34px;
    flex-basis: 34px;
    height: 100%;
    border: 1px solid #d4d7db;
    border-right: 0;
    box-sizing: border-box;
    background: #fff;
  }
}

.rock-analysis-expanded {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #d4d7db;
  border-right: 0;
  background: #fff;
  overflow: hidden;
}

.rock-analysis-panel-heading {
  height: 36px;
  flex: 0 0 36px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  border-bottom: 1px solid #e2e6ea;
  color: #222;
  font-weight: 400;
}

.rock-analysis-grid {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(var(--analysis-column-count), minmax(0, 1fr));
  grid-template-rows: 42px repeat(25, minmax(30px, 1fr));
  min-height: 0;
  overflow: hidden;
}

.rock-analysis-toggle {
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 2px;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;

  &:hover {
    background: #eef4ff;
  }
}

.rock-analysis-collapsed-tab {
  width: 100%;
  height: 76px;
  padding: 0;
  border: 0;
  border-bottom: 1px solid #e2e6ea;
  background: #fff;
  color: #222;
  cursor: pointer;
  font: inherit;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding-top: 8px;
  box-sizing: border-box;
  writing-mode: vertical-rl;
  text-orientation: upright;
  line-height: 1.05;
  letter-spacing: 0;

  &:hover {
    background: #eef4ff;
    color: #1677ff;
  }
}

.rock-analysis-grid-cell {
  min-width: 0;
  border-right: 1px solid #d4d7db;
  border-bottom: 1px solid #d4d7db;
  background: #fff;

  &.header {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 4px 6px;
    box-sizing: border-box;
    background: #f4f4f4;
    color: #333;
    font-size: inherit;
    font-weight: 400;
    line-height: 1.35;
    text-align: center;
    white-space: nowrap;
  }
}

.rock-chart-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #d4d7db;
  background: #fff;
}

.rock-curve-selector {
  height: 36px;
  flex: 0 0 36px;
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 0 14px;
  box-sizing: border-box;
  border-bottom: 1px solid #e2e6ea;
  background: #fafbfc;

  label {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: #222;
    cursor: pointer;
    white-space: nowrap;
  }

  input {
    width: 14px;
    height: 14px;
    margin: 0;
    accent-color: #1677ff;
  }
}

.rock-chart {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) 56px;
  grid-template-rows: minmax(0, 1fr) 32px;
  padding: 16px 18px 8px 10px;
  box-sizing: border-box;
}

.rock-chart-y-title {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #111;
  white-space: nowrap;
  line-height: 1;
}

.rock-chart-y-title-left {
  transform: rotate(-90deg);
}

.rock-chart-y-title-right {
  grid-column: 3;
  transform: rotate(90deg);
}

.rock-chart-plot {
  min-width: 0;
  min-height: 0;
  border-left: 1px solid #777;
  border-bottom: 1px solid #777;
  background-image:
    linear-gradient(to right, rgba(212, 220, 229, 0.45) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(212, 220, 229, 0.45) 1px, transparent 1px);
  background-size: 10% 10%;
}

.rock-chart.has-right-axis .rock-chart-plot {
  border-right: 1px solid #777;
}

.rock-chart-x-title {
  grid-column: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #111;
}

.rock-parameter-panel {
  width: 260px;
  flex: 0 0 260px;
  padding: 12px 14px;
  box-sizing: border-box;
  background: #fff;
  border: 1px solid #d4d7db;
  border-right: 0;
  overflow-y: auto;
}

.rock-parameter-section+.rock-parameter-section {
  margin-top: 16px;
}

.rock-section-heading {
  height: 22px;
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.rock-section-rule {
  height: 1px;
  flex: 1;
  background: #c8cdd3;
}

.rock-field-group {
  display: block;
  margin-top: 10px;
  color: #404040;

  >span {
    display: block;
    margin-bottom: 4px;
    line-height: 18px;
  }

  select,
  input {
    width: 100%;
    height: 30px;
    padding: 2px 8px;
    box-sizing: border-box;
    border: 1px solid #aeb6bf;
    border-radius: 3px;
    background: #fff;
    color: #333;
    font: inherit;
    outline: none;

    &:focus {
      border-color: #4c81b6;
      box-shadow: 0 0 0 1px rgba(76, 129, 182, 0.18);
    }
  }
}

.rock-data-grid {
   flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(1, minmax(145px, 1fr));
  grid-template-rows: 36px repeat(27, minmax(30px, 1fr));
  margin: 0;
  overflow: hidden;
  border: 1px solid #d4d7db;
}

.rock-grid-cell {
  min-width: 0;
  border-right: 1px solid #d4d7db;
  border-bottom: 1px solid #d4d7db;
  background: #fff;

  &.header {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0 8px;
    box-sizing: border-box;
    background: #f4f4f4;
    color: #333;
    font-size: inherit;
    font-weight: 400;
    text-align: center;
    white-space: nowrap;
  }
}

.rock-result-tabs {
  height: 32px;
  flex: 0 0 32px;
  display: flex;
  align-items: flex-end;
  padding-left: 12px;
  box-sizing: border-box;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.rock-result-tab {
  min-width: 88px;
  height: 32px;
  padding: 0 16px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-top: 2px solid #e4e7ed;
  background: #fff;
  color: #555;
  font: inherit;
  cursor: pointer;
  white-space: nowrap;

  &:hover {
    color: #111;
  }

  &.active {
    color: #111;
    border-top-color: #111;
    font-weight: 600;
  }
}

@media (max-width: 950px) {
  .rock-parameter-panel {
    width: 240px;
    flex-basis: 240px;
  }

  .rock-data-grid {
     grid-template-columns: repeat(1, minmax(130px, 1fr));
  }

  .rock-analysis-panel {
    width: 640px;
    flex-basis: 640px;
  }

}
</style>
