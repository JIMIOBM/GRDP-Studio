<script setup>
import { ref } from 'vue'

const activeResultTab = ref('数据列表')
const compressibilityMethod = ref('Hall方法')
const rockCompressibility = ref('0.00045')
const porosity = ref('8.5')
const initialPressure = ref('56.34')
</script>

<template>
  <div class="rock-properties-view">
    <div class="rock-workspace">
      <aside class="rock-parameter-panel">
        <div class="rock-parameter-section">
          <div class="rock-section-heading">
            <span>计算方法</span>
            <span class="rock-section-rule"></span>
          </div>

          <label class="rock-field-group">
            <span>岩石压缩系数计算方法</span>
            <select v-model="compressibilityMethod">
              <option>Hall方法</option>
              <option>Newman方法</option>
            </select>
          </label>
        </div>

        <div class="rock-parameter-section">
          <div class="rock-section-heading">
            <span>岩石数据</span>
            <span class="rock-section-rule"></span>
          </div>

          <label class="rock-field-group">
            <span>岩石压缩系数（1/MPa）</span>
            <input v-model="rockCompressibility" inputmode="decimal" />
          </label>

          <label class="rock-field-group">
            <span>岩石孔隙度（%）</span>
            <input v-model="porosity" inputmode="decimal" />
          </label>

          <label class="rock-field-group">
            <span>原始地层压力（MPa）</span>
            <input v-model="initialPressure" inputmode="decimal" />
          </label>
        </div>
      </aside>

      <div class="rock-data-grid" aria-label="岩石性质数据表格">
        <div
          v-for="cell in 168"
          :key="cell"
          class="rock-grid-cell"
          :class="{ header: cell <= 6 }"
        ></div>
      </div>
    </div>

    <footer class="rock-result-tabs">
      <button
        type="button"
        class="rock-result-tab"
        :class="{ active: activeResultTab === '数据列表' }"
        @click="activeResultTab = '数据列表'"
      >
        数据列表
      </button>
      <button
        type="button"
        class="rock-result-tab"
        :class="{ active: activeResultTab === '结果分析图' }"
        @click="activeResultTab = '结果分析图'"
      >
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
  overflow: hidden;
}

.rock-parameter-panel {
  width: 247px;
  flex: 0 0 247px;
  padding: 0 13px 12px 10px;
  box-sizing: border-box;
  background: #fff;
}

.rock-parameter-section + .rock-parameter-section {
  margin-top: 13px;
}

.rock-section-heading {
  height: 23px;
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.rock-section-rule {
  height: 1px;
  flex: 1;
  background: #8f8f8f;
}

.rock-field-group {
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

.rock-data-grid {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(6, minmax(105px, 1fr));
  grid-template-rows: repeat(28, minmax(31px, 1fr));
  overflow: hidden;
  border-top: 1px solid #d4d7db;
  border-left: 1px solid #d4d7db;
}

.rock-grid-cell {
  min-width: 0;
  border-right: 1px solid #d4d7db;
  border-bottom: 1px solid #d4d7db;
  background: #fff;

  &.header {
    background: #f4f4f4;
  }
}

.rock-result-tabs {
  height: 32px;
  flex: 0 0 32px;
  display: flex;
  border-top: 1px solid #d4d7db;
  background: #fff;
}

.rock-result-tab {
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
  .rock-parameter-panel {
    width: 225px;
    flex-basis: 225px;
  }

  .rock-data-grid {
    grid-template-columns: repeat(6, minmax(92px, 1fr));
  }
}
</style>
