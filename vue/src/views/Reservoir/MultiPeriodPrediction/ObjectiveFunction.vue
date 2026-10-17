<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getReservoirPredictionContext } from './reservoirPredictionContext'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  storageId: { type: [Number, String], required: true },
  storageName: { type: String, default: '' }
})
const emit = defineEmits(['objective-updated'])
const context = computed(() => getReservoirPredictionContext(
  props.projectId, props.gasReservoirId, props.storageId
))
const demandQ = ref(context.value.objectiveQ || '')
const statusText = ref('')

function applyDemand() {
  const raw = demandQ.value
  if (raw === '' || raw === null || !Number.isFinite(Number(raw)) || Number(raw) < 0) {
    ElMessage.warning('请输入合法的非负调峰需求气量')
    return
  }
  context.value.objectiveQ = String(raw)
  statusText.value = '调峰需求已应用到当前页面（未保存）'
  emit('objective-updated', { demandQ: Number(raw), storageId: props.storageId })
  ElMessage.success('需求参数已应用')
}

watch(
  () => [props.projectId, props.gasReservoirId, props.storageId],
  () => {
    demandQ.value = context.value.objectiveQ || ''
    statusText.value = ''
  }
)
</script>

<template>
  <div class="objective-workspace">
    <aside class="params-panel">
      <div class="panel-head">参数设置</div>
      <div class="panel-body">
        <fieldset class="field-section">
          <legend class="sec-label">需求参数</legend>
          <label class="field">
            <span>库群调峰需求气量 Q（×10⁴m³）</span>
            <input v-model="demandQ" type="number" min="0" step="any"
              inputmode="decimal" placeholder="请输入库群调峰需求气量" />
          </label>
        </fieldset>
        <div class="panel-actions">
          <button type="button" class="save-button" @click="applyDemand">应用需求参数</button>
        </div>
        <p v-if="statusText" class="objective-note">{{ statusText }}</p>
        <p class="objective-note">
          目前 HAR 的动态预测 Calc 接口不含 Q 参数；此处仅用于前端需求记录和展示，
          未实现目标函数最优化，也不会发出保存请求。
        </p>
      </div>
    </aside>

    <main class="result-area">
      <div class="result-tabs">
        <div class="result-tab">
          多周期预测 - 目标函数{{ props.storageName ? ` - ${props.storageName}` : '' }}
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

<style lang="scss" scoped>
.objective-workspace {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
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
  border: 0;
  padding: 0;
  margin: 0 0 9px;
  min-width: 0;
}

.sec-label {
  width: 100%;
  height: 22px;
  padding: 0;
  margin: 8px 0 7px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
  font-size: 13px;
  color: #252525;
}

.sec-label::after {
  content: '';
  height: 1px;
  flex: 1;
  background: #999;
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
  height: 24px;
  padding: 0 8px;
  border: 1px solid #aaa;
  border-radius: 3px;
  background: #fff;
  color: #333;
  font: inherit;
}

.field input:focus-visible {
  outline: 1px solid #d5b900;
  outline-offset: 1px;
}

.panel-actions {
  margin-top: 16px;
  text-align: center;
}

.save-button {
  min-width: 86px;
  height: 30px;
  padding: 0 22px;
  border: 1px solid #d5b900;
  border-radius: 4px;
  background: #f4d000;
  color: #222;
  font: inherit;
  font-weight: 500;
  cursor: pointer;
}

.save-button:hover:not(:disabled) {
  background: #ffe033;
}

.save-button:disabled {
  opacity: 0.6;
  cursor: wait;
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
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}

.result-tab {
  height: 34px;
  line-height: 34px;
  max-width: min(580px, 100%);
  padding: 0 12px;
  border-right: 1px solid #e4e7ed;
  box-sizing: border-box;
  background: #f4d000;
  color: #202020;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}

.content-area {
  flex: 1;
  padding: 24px 32px;
  overflow: auto;
}

.formula-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 0;
  border-bottom: 1px dashed #e4e7ed;
}

.formula-row:last-child {
  border-bottom: none;
}

.formula {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-family: "Times New Roman", serif;
}

.operator {
  font-weight: 600;
  color: #333;
}

.sum {
  position: relative;
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  margin: 0 4px;
}

.sum-symbol {
  font-size: 24px;
  line-height: 1;
}

.sum-upper {
  font-size: 11px;
  position: absolute;
  top: -14px;
}

.sum-lower {
  font-size: 11px;
  position: absolute;
  bottom: -14px;
}

.variable {
  font-style: italic;
  color: #303133;
}

.formula-condition {
  font-size: 13px;
  color: #606266;
  min-width: 200px;
  text-align: right;
}

.objective-note {
  font-size: 12px; line-height: 1.6;
  color: #606266; margin: 10px 0;
}
</style>
