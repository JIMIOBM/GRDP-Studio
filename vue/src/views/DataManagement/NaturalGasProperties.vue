<script setup>
import { computed, ref } from 'vue'

const activeResultTab = ref('数据列表')
const activeCurve = ref('曲线1')
const analysisTableCollapsed = ref(false)
const gasCorrectionMethod = ref('Wichert-Aziz 修正方法')
const deviationFactorMethod = ref('Dranchuk-Abu-Kassem 方法')
const initialPressure = ref('56.34')
const reservoirTemperature = ref('120')

const gasPropertyColumns = [
  '天然气类型',
  '天然气比重(dless)',
  'H₂S摩尔百分含量(%)',
  'CO₂摩尔百分含量(%)',
  'N₂摩尔百分含量(%)'
]

const gasCurveOptions = [
  {
    name: '曲线1',
    leftYAxis: '天然气偏差系数 Z(dless)',
    rightYAxis: '气体拟压力 m(p)(MPa²/(mPa·s))',
    leftTableColumn: '天然气偏差系数(dless)',
    rightTableColumn: '气体拟压力(MPa²/(mPa·s))'
  },
  {
    name: '曲线2',
    leftYAxis: '天然气体积系数 Bg(dless)',
    rightYAxis: '天然气密度 ρg(kg/m³)',
    leftTableColumn: '天然气体积系数(dless)',
    rightTableColumn: '天然气密度(kg/m³)'
  },
  {
    name: '曲线3',
    leftYAxis: '天然气压缩系数 Cg(MPa⁻¹)',
    leftTableColumn: '天然气压缩系数(MPa⁻¹)'
  },
  {
    name: '曲线4',
    leftYAxis: '天然气粘度 μg(mPa·s)',
    leftTableColumn: '天然气粘度(mPa·s)'
  }
]

const activeCurveOption = computed(
  () => gasCurveOptions.find((curve) => curve.name === activeCurve.value) ?? gasCurveOptions[0]
)

const analysisTableColumns = computed(() => [
  '原始地层压力(MPa)',
  '地层温度(℃)',
  activeCurveOption.value.leftTableColumn,
  ...(activeCurveOption.value.rightTableColumn ? [activeCurveOption.value.rightTableColumn] : [])
])

const analysisDataCellCount = computed(() => analysisTableColumns.value.length * 25)
</script>

<template>
  <div class="gas-properties-view">
    <div v-if="activeResultTab === '数据列表'" class="gas-workspace">
      <aside class="gas-parameter-panel">
        <div class="gas-parameter-section">
          <div class="gas-section-heading">
            <span>计算方法</span>
            <span class="gas-section-rule"></span>
          </div>

          <label class="gas-field-group">
            <span>非烃气体修正方法</span>
            <select v-model="gasCorrectionMethod">
              <option>Wichert-Aziz 修正方法</option>
              <option>Carr-Kobayashi-Burrous 修正方法</option>
            </select>
          </label>

          <label class="gas-field-group">
            <span>天然气偏差系数计算方法</span>
            <select v-model="deviationFactorMethod">
              <option>Dranchuk-Abu-Kassem 方法</option>
              <option>Dranchuk-Purvis-Robinson 方法</option>
              <option>Hall-Yarborough 方法</option>
            </select>
          </label>
        </div>

        <div class="gas-parameter-section">
          <div class="gas-section-heading">
            <span>其他数据</span>
            <span class="gas-section-rule"></span>
          </div>

          <label class="gas-field-group">
            <span>原始地层压力（MPa）</span>
            <input v-model="initialPressure" inputmode="decimal" />
          </label>

          <label class="gas-field-group">
            <span>地层温度（℃）</span>
            <input v-model="reservoirTemperature" inputmode="decimal" />
          </label>
        </div>
      </aside>

      <div class="gas-data-grid" aria-label="天然气性质数据表格">
        <div
          v-for="column in gasPropertyColumns"
          :key="column"
          class="gas-grid-cell header"
        >
          {{ column }}
        </div>
        <div
          v-for="cell in 135"
          :key="`data-${cell}`"
          class="gas-grid-cell"
        ></div>
      </div>
    </div>

    <div
      v-else
      class="gas-analysis-workspace"
      :class="{ 'table-collapsed': analysisTableCollapsed }"
    >
      <aside class="gas-analysis-panel" :class="{ collapsed: analysisTableCollapsed }">
        <button
          v-if="analysisTableCollapsed"
          class="gas-analysis-collapsed-tab"
          type="button"
          title="展开分析数据表"
          @click="analysisTableCollapsed = false"
        >
          图表数据
        </button>

        <template v-else>
          <div class="gas-analysis-expanded">
            <div class="gas-analysis-panel-heading">
              <span>图表数据</span>
              <button
                class="gas-analysis-toggle"
                type="button"
                title="收起图表数据"
                @click="analysisTableCollapsed = true"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
                  <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
                </svg>
              </button>
            </div>
            <div
              class="gas-analysis-grid"
              aria-label="天然气分析数据表格"
              :style="{ '--analysis-column-count': analysisTableColumns.length }"
            >
              <div
                v-for="column in analysisTableColumns"
                :key="column"
                class="gas-analysis-grid-cell header"
              >
                {{ column }}
              </div>
              <div
                v-for="cell in analysisDataCellCount"
                :key="`analysis-data-${cell}`"
                class="gas-analysis-grid-cell"
              ></div>
            </div>
          </div>
        </template>
      </aside>

      <section class="gas-chart-panel" aria-label="天然气结果分析图">
        <div class="gas-curve-selector">
          <label v-for="curve in gasCurveOptions" :key="curve.name">
            <input v-model="activeCurve" type="radio" :value="curve.name" />
            <span>{{ curve.name }}</span>
          </label>
        </div>

        <div class="gas-chart" :class="{ 'has-right-axis': activeCurveOption.rightYAxis }">
          <div class="gas-chart-y-title gas-chart-y-title-left">
            {{ activeCurveOption.leftYAxis }}
          </div>
          <div class="gas-chart-plot"></div>
          <div v-if="activeCurveOption.rightYAxis" class="gas-chart-y-title gas-chart-y-title-right">
            {{ activeCurveOption.rightYAxis }}
          </div>
          <div class="gas-chart-x-title">压力 P(MPa)</div>
        </div>
      </section>
    </div>

    <footer class="gas-result-tabs">
      <button
        type="button"
        class="gas-result-tab"
        :class="{ active: activeResultTab === '数据列表' }"
        @click="activeResultTab = '数据列表'"
      >
        数据列表
      </button>
      <button
        type="button"
        class="gas-result-tab"
        :class="{ active: activeResultTab === '结果分析图' }"
        @click="activeResultTab = '结果分析图'"
      >
        结果分析图
      </button>
    </footer>
  </div>
</template>

<style lang="scss" scoped>
.gas-properties-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.gas-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 12px;
  padding: 12px 14px;
  box-sizing: border-box;
  overflow: hidden;
}

.gas-analysis-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  padding: 12px 14px;
  box-sizing: border-box;
  overflow: hidden;
}

.gas-analysis-workspace.table-collapsed {
  padding: 12px 14px;
}

.gas-analysis-panel {
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

.gas-analysis-expanded {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #d4d7db;
  border-right: 0;
  border-radius: 4px 0 0 4px;
  background: #fff;
  overflow: hidden;
}

.gas-analysis-panel-heading {
  height: 40px;
  flex: 0 0 40px;
  padding: 0 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  border-bottom: 1px solid #e2e6ea;
  color: #222;
  font-weight: 400;
}

.gas-analysis-grid {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(var(--analysis-column-count), minmax(0, 1fr));
  grid-template-rows: 48px repeat(25, minmax(31px, 1fr));
  min-height: 0;
  overflow: hidden;
}

.gas-analysis-toggle {
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

.gas-analysis-collapsed-tab {
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

.gas-analysis-grid-cell {
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

.gas-chart-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #d4d7db;
  border-radius: 0 4px 4px 0;
  background: #fff;
}

.gas-curve-selector {
  height: 40px;
  flex: 0 0 40px;
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 0 16px;
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

.gas-chart {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) 56px;
  grid-template-rows: minmax(0, 1fr) 32px;
  padding: 18px 20px 8px 12px;
  box-sizing: border-box;
}

.gas-chart-y-title {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #111;
  white-space: nowrap;
  line-height: 1;
}

.gas-chart-y-title-left {
  transform: rotate(-90deg);
}

.gas-chart-y-title-right {
  grid-column: 3;
  transform: rotate(90deg);
}

.gas-chart-plot {
  min-width: 0;
  min-height: 0;
  border-left: 1px solid #777;
  border-bottom: 1px solid #777;
  background-image:
    linear-gradient(to right, rgba(212, 220, 229, 0.45) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(212, 220, 229, 0.45) 1px, transparent 1px);
  background-size: 10% 10%;
}

.gas-chart.has-right-axis .gas-chart-plot {
  border-right: 1px solid #777;
}

.gas-chart-x-title {
  grid-column: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #111;
}

.gas-parameter-panel {
  width: 240px;
  flex: 0 0 240px;
  padding: 10px 12px 12px;
  box-sizing: border-box;
  background: #fff;
  border: 1px solid #d4d7db;
  border-radius: 4px;
  overflow-y: auto;
}

.gas-parameter-section + .gas-parameter-section {
  margin-top: 13px;
}

.gas-section-heading {
  height: 23px;
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.gas-section-rule {
  height: 1px;
  flex: 1;
  background: #c8cdd3;
}

.gas-field-group {
  display: block;
  margin-top: 9px;
  color: #404040;

  > span {
    display: block;
    margin-bottom: 5px;
    line-height: 19px;
  }

  select,
  input {
    width: 100%;
    height: 28px;
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

.gas-data-grid {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(5, minmax(145px, 1fr));
  grid-template-rows: repeat(28, minmax(31px, 1fr));
  margin: 0;
  overflow: hidden;
  border: 1px solid #d4d7db;
  border-radius: 4px;
  box-shadow: 0 1px 3px rgba(31, 45, 61, 0.08);
}

.gas-grid-cell {
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

.gas-result-tabs {
  height: 30px;
  flex: 0 0 30px;
  display: flex;
  align-items: flex-end;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.gas-result-tab {
  min-width: 88px;
  height: 30px;
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
    font-weight: 700;
  }
}

@media (max-width: 950px) {
  .gas-parameter-panel {
    width: 225px;
    flex-basis: 225px;
  }

  .gas-data-grid {
    grid-template-columns: repeat(5, minmax(130px, 1fr));
  }

  .gas-analysis-panel {
    width: 640px;
    flex-basis: 640px;
  }

}
</style>
