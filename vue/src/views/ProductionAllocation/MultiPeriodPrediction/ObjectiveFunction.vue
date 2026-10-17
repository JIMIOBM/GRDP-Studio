<!-- filepath: d:\shiyou\GRDP-Studio\vue\src\views\ProductionAllocation\MultiPeriodPrediction\ObjectiveFunction.vue -->
<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  wellName: { type: String, default: '' },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true }
})

const demandQ = ref('')
const saving = ref(false)

function handleSave() {
  if (saving.value) return
  saving.value = true
  try {
    const payload = {
      projectId: props.projectId,
      gasReservoirId: props.gasReservoirId,
      wellName: props.wellName,
      demandQ: demandQ.value,
      savedAt: new Date().toISOString()
    }
    const key = `grdp:objective:v1:${props.projectId}:${props.gasReservoirId}:${encodeURIComponent(props.wellName || '')}`
    window.sessionStorage.setItem(key, JSON.stringify(payload))
    window.dispatchEvent(new CustomEvent('grdp:objective-saved', {
      detail: { demandQ: demandQ.value, wellName: props.wellName }
    }))
    ElMessage.success('目标函数参数已保存')
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || '未知错误'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="objective-workspace">
    <aside class="params-panel">
      <div class="panel-head">参数设置</div>
      <div class="panel-body">
        <fieldset class="field-section">
          <legend class="sec-label">需求参数</legend>
          <label class="field">
            <span>需求气量 Q（×10⁴m³）</span>
            <input
              v-model="demandQ"
              type="number"
              min="0"
              step="any"
              inputmode="decimal"
              placeholder="请输入需求气量"
            />
          </label>
        </fieldset>
                <div class="panel-actions">
          <button
            type="button"
            class="save-button"
            :disabled="saving"
            @click="handleSave"
          >
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </aside>

    <main class="result-area">
      <div class="result-tabs">
        <div class="result-tab">
          多周期预测 - 目标函数{{ props.wellName ? ` - ${props.wellName}` : '' }}
        </div>
      </div>

      <div class="content-area">
        <div class="formula-row">
          <div class="formula">
            <span class="operator">max</span>
            <span class="sum">
              <span class="sum-upper">i≤j</span>
              <span class="sum-symbol">∑</span>
              <span class="sum-lower">i = 1</span>
            </span>
            <span class="variable">q<sub>sc(i)</sub></span>
          </div>
          <span class="formula-condition">不能满足调峰需求气量</span>
        </div>

        <div class="formula-row">
          <div class="formula">
            <span class="sum">
              <span class="sum-upper">j</span>
              <span class="sum-symbol">∑</span>
              <span class="sum-lower">i = 1</span>
            </span>
            <span class="variable">q<sub>sc(i)</sub></span>
            <span class="operator">=</span>
            <span class="variable">Q<sub>pjh(m)</sub></span>
          </div>
          <span class="formula-condition">能够满足调峰需求气量</span>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.objective-workspace {
  display: flex;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  color: #252525;
  background: #fff;
  font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif;
}

.params-panel {
  display: flex;
  flex: 0 0 286px;
  flex-direction: column;
  min-width: 0;
  overflow: auto;
  border-right: 1px solid #d7d7d7;
  background: #fff;
}

.panel-head {
  display: flex;
  flex: 0 0 34px;
  align-items: center;
  padding: 0 12px;
  border-bottom: 1px solid #d7d7d7;
  background: #f2f2f2;
  font-weight: 600;
}

.panel-body {
  padding: 10px 12px;
}

.field-section {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

.sec-label {
  width: 100%;
  margin: 8px 0;
  padding: 0;
  color: #252525;
  font-size: 13px;
}

.field {
  display: block;
  min-width: 0;
}

.field > span {
  display: block;
  margin-bottom: 5px;
}

.field input {
  box-sizing: border-box;
  width: 100%;
  height: 28px;
  padding: 0 8px;
  border: 1px solid #aaa;
  border-radius: 3px;
  background: #fff;
  color: #333;
  font: inherit;
}

.field input:focus {
  border-color: #c9ad00;
  outline: 1px solid #d5b900;
}

.result-area {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: #fff;
}

.result-tabs {
  display: flex;
  flex: 0 0 34px;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}

.result-tab {
  box-sizing: border-box;
  max-width: 100%;
  height: 34px;
  padding: 0 12px;
  overflow: hidden;
  border-right: 1px solid #e4e7ed;
  background: #f4d000;
  color: #202020;
  font-weight: 600;
  line-height: 34px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content-area {
  flex: 1;
  min-height: 0;
  padding: 24px;
  overflow: auto;
}

.formula-row {
  display: grid;
  grid-template-columns: minmax(260px, 380px) minmax(180px, 1fr);
  align-items: center;
  gap: 20px;
  min-height: 76px;
  padding: 8px 12px;
  border-bottom: 1px solid #e4e7ed;
}

.formula {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 56px;
  color: #303133;
  font-family: "Cambria Math", "Times New Roman", serif;
  font-size: 22px;
  white-space: nowrap;
}

.operator {
  font-size: 18px;
  font-style: italic;
}

.variable {
  font-style: italic;
}

.variable sub,
.sum-upper sub {
  font-size: 0.65em;
  font-style: normal;
}

.sum {
  display: grid;
  grid-template-rows: 13px 30px 13px;
  justify-items: center;
  align-items: center;
  min-width: 34px;
}

.sum-upper,
.sum-lower {
  font-size: 10px;
  line-height: 13px;
  white-space: nowrap;
}

.sum-symbol {
  font-size: 34px;
  line-height: 30px;
}

.formula-condition {
  color: #303133;
  font-size: 14px;
}

.panel-actions {
  margin-top: 16px;
}

.save-button {
  width: 100%;
  height: 30px;
  padding: 0 22px;
  border: 1px solid #d5b900;
  border-radius: 4px;
  background: #f4d000;
  color: #222;
  font: inherit;
  cursor: pointer;
  font-weight: 500;
}

.save-button:hover:not(:disabled) {
  background: #ffe033;
}

.save-button:disabled {
  opacity: 0.6;
  cursor: wait;
}

@media (max-width: 700px) {
  .params-panel {
    flex-basis: 220px;
  }

  .content-area {
    padding: 16px 12px;
  }

  .formula-row {
    grid-template-columns: 1fr;
    justify-items: start;
    gap: 4px;
    padding: 12px 4px;
  }

  .formula {
    justify-content: flex-start;
    font-size: 20px;
  }
}
</style>