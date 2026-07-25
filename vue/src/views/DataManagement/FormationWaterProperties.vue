<script setup>
import { ref } from 'vue'

const activeResultTab = ref('数据列表')
const volumeFactorMethod = ref('McCain方法')
const compressibilityMethod = ref('Meehan方法')
const salinity = ref('35000')
const initialPressure = ref('56.34')
const reservoirTemperature = ref('120')
</script>

<template>
  <div class="water-properties-view">
    <div class="water-workspace">
      <aside class="water-parameter-panel">
        <div class="water-parameter-section">
          <div class="water-section-heading">
            <span>计算方法</span>
            <span class="water-section-rule"></span>
          </div>

          <label class="water-field-group">
            <span>地层水体积系数计算方法</span>
            <select v-model="volumeFactorMethod">
              <option>McCain方法</option>
              <option>Standing方法</option>
            </select>
          </label>

          <label class="water-field-group">
            <span>地层水压缩系数计算方法</span>
            <select v-model="compressibilityMethod">
              <option>Meehan方法</option>
              <option>Dodson-Standing方法</option>
            </select>
          </label>
        </div>

        <div class="water-parameter-section">
          <div class="water-section-heading">
            <span>地层水数据</span>
            <span class="water-section-rule"></span>
          </div>

          <label class="water-field-group">
            <span>地层水矿化度（mg/L）</span>
            <input v-model="salinity" inputmode="decimal" />
          </label>

          <label class="water-field-group">
            <span>原始地层压力（MPa）</span>
            <input v-model="initialPressure" inputmode="decimal" />
          </label>

          <label class="water-field-group">
            <span>地层温度（℃）</span>
            <input v-model="reservoirTemperature" inputmode="decimal" />
          </label>
        </div>
      </aside>

      <div class="water-data-grid" aria-label="地层水性质数据表格">
        <div
          v-for="cell in 168"
          :key="cell"
          class="water-grid-cell"
          :class="{ header: cell <= 6 }"
        ></div>
      </div>
    </div>

    <footer class="water-result-tabs">
      <button
        type="button"
        class="water-result-tab"
        :class="{ active: activeResultTab === '数据列表' }"
        @click="activeResultTab = '数据列表'"
      >
        数据列表
      </button>
      <button
        type="button"
        class="water-result-tab"
        :class="{ active: activeResultTab === '结果分析图' }"
        @click="activeResultTab = '结果分析图'"
      >
        结果分析图
      </button>
    </footer>
  </div>
</template>

<style lang="scss" scoped>
.water-properties-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.water-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}

.water-parameter-panel {
  width: 247px;
  flex: 0 0 247px;
  padding: 0 13px 12px 10px;
  box-sizing: border-box;
  background: #fff;
}

.water-parameter-section + .water-parameter-section {
  margin-top: 13px;
}

.water-section-heading {
  height: 23px;
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.water-section-rule {
  height: 1px;
  flex: 1;
  background: #8f8f8f;
}

.water-field-group {
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
    border: 1px solid #8a8a8a;
    border-radius: 2px;
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

.water-data-grid {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(6, minmax(105px, 1fr));
  grid-template-rows: repeat(28, minmax(31px, 1fr));
  overflow: hidden;
  border-top: 1px solid #d4d7db;
  border-left: 1px solid #d4d7db;
}

.water-grid-cell {
  min-width: 0;
  border-right: 1px solid #d4d7db;
  border-bottom: 1px solid #d4d7db;
  background: #fff;

  &.header {
    background: #f4f4f4;
  }
}

.water-result-tabs {
  height: 32px;
  flex: 0 0 32px;
  display: flex;
  border-top: 1px solid #d4d7db;
  background: #fff;
}

.water-result-tab {
  min-width: 88px;
  padding: 0 16px;
  border: 0;
  border-right: 1px solid #d4d7db;
  background: #fff;
  color: #8b8b8b;
  font: inherit;
  cursor: pointer;
  position: relative;

  &.active {
    color: #111;
    font-weight: 700;

    &::after {
      content: "";
      position: absolute;
      right: 15px;
      bottom: 1px;
      left: 15px;
      height: 3px;
      background: #111;
    }
  }
}

@media (max-width: 950px) {
  .water-parameter-panel {
    width: 225px;
    flex-basis: 225px;
  }

  .water-data-grid {
    grid-template-columns: repeat(6, minmax(92px, 1fr));
  }
}
</style>
