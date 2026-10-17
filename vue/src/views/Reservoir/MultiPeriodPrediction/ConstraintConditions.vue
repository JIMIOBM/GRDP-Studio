<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { validateOriginalConstraintForm } from '@/api/reservoirPredictionApi'
import {
  getReservoirPredictionContext, originalConstraintForm, copyConstraintForm
} from './reservoirPredictionContext'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  storageId: { type: [Number, String], required: true },
  storageName: { type: String, default: '' },
  recordId: { type: [Number, String], default: null } // 与父组件兼容；此版不保存
})
const emit = defineEmits(['constraints-calculated', 'constraints-invalidated'])
const context = computed(() => getReservoirPredictionContext(
  props.projectId, props.gasReservoirId, props.storageId
))
const formData = reactive(context.value.constraintDraft
  ? copyConstraintForm(context.value.constraintDraft) : originalConstraintForm())
const isCalculating = ref(false)
let updatingScope = false

function invalidate() {
  if (updatingScope) return
  context.value.constraintDraft = null
  context.value.appliedAt = null
  emit('constraints-invalidated')
}
watch(formData, invalidate, { deep: true, flush: 'sync' })
watch(() => [props.projectId, props.gasReservoirId, props.storageId], () => {
  const replacement = context.value.constraintDraft || originalConstraintForm()
  updatingScope = true
  try {
    for (const key of ['gasProperties', 'calcMethod', 'otherData']) {
      Object.assign(formData[key], replacement[key])
    }
  } finally { updatingScope = false }
  if (!context.value.constraintDraft) emit('constraints-invalidated')
})

function handleCalculateConstraints() {
  if (isCalculating.value) return
  isCalculating.value = true
  try {
    validateOriginalConstraintForm(formData)
    const snapshot = copyConstraintForm(formData)
    context.value.constraintDraft = snapshot
    context.value.appliedAt = Date.now()
    emit('constraints-calculated', {
      projectId: Number(props.projectId),
      gasReservoirId: Number(props.gasReservoirId),
      storageId: props.storageId,
      ...snapshot,
      inputs: snapshot,
      // 不伪造 erosion/sand/liquid 边界数值
      calculatedLimits: null,
      source: '原约束条件表单（未保存）'
    })
    ElMessage.success('原有约束参数已应用，可前往方案生成')
  } catch (error) {
    ElMessage.error(error.message || '参数校验失败')
  } finally {
    isCalculating.value = false
  }
}
</script>

<template>
  <div class="constraint-conditions-workspace">
    <div class="result-tabs">
      <div class="result-tab" :title="`多周期预测 - 约束条件${storageName ? ' - ' + storageName : ''}`">
        多周期预测 - 约束条件{{ storageName ? ` - ${storageName}` : '' }}
      </div>
    </div>

    <div class="content-area">
      <div class="section-title">参数设置</div>

      <fieldset class="field-section">
        <legend class="sec-label">气体性质</legend>
        <div class="parameter-grid cols-5">
          <label class="field">
            <span>天然气类型</span>
            <select v-model="formData.gasProperties.gasType">
              <option value="干气">干气</option>
              <option value="湿气">湿气</option>
              <option value="凝析气">凝析气</option>
            </select>
          </label>
          <label class="field">
            <span>天然气比重(dless)</span>
            <input v-model="formData.gasProperties.dless" type="number" step="0.01">
          </label>
          <label class="field">
            <span>H₂S摩尔百分含量(%)</span>
            <input v-model="formData.gasProperties.h2sContent" type="number" step="0.01">
          </label>
          <label class="field">
            <span>CO₂摩尔百分含量(%)</span>
            <input v-model="formData.gasProperties.co2Content" type="number" step="0.01">
          </label>
          <label class="field">
            <span>N₂摩尔百分含量(%)</span>
            <input v-model="formData.gasProperties.n2Content" type="number" step="0.01">
          </label>
        </div>
      </fieldset>

      <fieldset class="field-section">
        <legend class="sec-label">计算方法</legend>
        <div class="parameter-grid cols-3">
          <label class="field">
            <span>非烃气体修正方法</span>
            <select v-model="formData.calcMethod.correctionMethod">
              <option value="Wichert-Aziz 修正方法">Wichert-Aziz 修正方法</option>
              <option value="Carr-Kobayashi-Burrous 修正方法">Carr-Kobayashi-Burrous 修正方法</option>
            </select>
          </label>
          <label class="field">
            <span>天然气偏差系数计算方法</span>
            <select v-model="formData.calcMethod.deviationMethod">
              <option value="Dranchuk-Abu-Kassem 方法">Dranchuk-Abu-Kassem 方法</option>
              <option value="Dranchuk-Purvis-Robinson 方法">Dranchuk-Purvis-Robinson 方法</option>
              <option value="Hall-Yarborough 方法">Hall-Yarborough 方法</option>
            </select>
          </label>
          <label class="field">
            <span>天然气粘度计算方法</span>
            <select v-model="formData.calcMethod.viscosityMethod">
              <option value="Lee-Gonzalez-Eakin 方法">Lee-Gonzalez-Eakin 方法</option>
              <option value="Carr-Kobayashi-Burrous 方法">Carr-Kobayashi-Burrous 方法</option>
              <option value="Sutton 方法">Sutton 方法</option>
            </select>
          </label>
        </div>
      </fieldset>

      <fieldset class="field-section">
        <legend class="sec-label">其它数据</legend>
        <div class="parameter-grid cols-5">
          <label class="field">
            <span>产能方程形式</span>
            <select v-model="formData.otherData.productionEquationType">
              <option value="压力形式">压力形式</option>
              <option value="压力平方形式">压力平方形式</option>
              <option value="拟压力形式">拟压力形式</option>
            </select>
          </label>
          <label class="field">
            <span>原始地层压力(MPa)</span>
            <input v-model="formData.otherData.initialPressure" type="number" step="0.1">
          </label>
          <label class="field">
            <span>地层温度(°C)</span>
            <input v-model="formData.otherData.reservoirTemperature" type="number" step="0.1">
          </label>
          <label class="field"></label>
          <label class="field"></label>

          <label class="field">
            <span>物质平衡方程类型</span>
            <select v-model="formData.otherData.materialBalanceType">
              <option value="定容气藏">定容气藏</option>
              <option value="水驱气藏">水驱气藏</option>
            </select>
          </label>
          <label class="field">
            <span>井组目标产量(10⁴m³/d)</span>
            <input v-model="formData.otherData.targetProduction" type="number" step="0.0001">
          </label>
          <label class="field">
            <span>预测开始时间</span>
            <input v-model="formData.otherData.forecastStartDate" type="date">
          </label>
          <label class="field"></label>
          <label class="field"></label>

          <label class="field">
            <span>预测时间步长(d)</span>
            <input v-model="formData.otherData.forecastTimeStep" type="number" step="1">
          </label>
          <label class="field">
            <span>预测时间步数(d)</span>
            <input v-model="formData.otherData.forecastTotalDays" type="number" step="1">
          </label>
          <label class="field">
            <span>井口外输压力(MPa)</span>
            <input v-model="formData.otherData.wellheadExportPressure" type="number" step="0.1">
          </label>
          <label class="field"></label>
          <label class="field"></label>

          <label class="field">
            <span>单井废弃产量(10⁴m³/d)</span>
            <input v-model="formData.otherData.abandonmentRate" type="number" step="0.0001">
          </label>
        </div>
      </fieldset>

      <div class="form-actions">
        <button type="button" class="calculate-button" :disabled="isCalculating || saving" @click="handleCalculateConstraints">
          {{ isCalculating ? '校验中...' : '应用约束参数' }}
        </button>
      </div>
      <p class="constraint-note">此处仅校验并传递原有参数，不模拟不存在的冲蚀、出砂和积液计算结果。</p>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.constraint-conditions-workspace {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
  color: #252525;
  font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif;
}

.result-tabs {
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}

.result-tab {
  height: 34px;
  line-height: 34px;
  max-width: min(430px, 100%);
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

.header-actions {
  display: flex;
  gap: 10px;
  padding-right: 8px;
}

.header-actions button {
  height: 27px;
  padding: 0 14px;
  border: 1px solid #c9cdd3;
  border-radius: 4px;
  background: #fff;
  color: #292929;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.header-actions button:hover:not(:disabled) {
  border-color: #b49a00;
}

.header-actions button:disabled {
  opacity: 0.6;
  cursor: default;
}

.content-area {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 24px 24px;
  background: #fff;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
  color: #1f2937;
  font-size: 15px;
  font-weight: 700;
  line-height: 22px;
  letter-spacing: 0.2px;
}

.section-title::before {
  width: 4px;
  height: 15px;
  flex: 0 0 4px;
  border-radius: 2px;
  background: #d5b900;
  content: '';
}

.field-section {
  border: 0;
  padding: 0;
  margin: 0 0 12px;
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

.parameter-grid {
  display: grid;
  gap: 8px;

  &.cols-3 {
    grid-template-columns: repeat(3, 1fr);
  }

  &.cols-5 {
    grid-template-columns: repeat(5, 1fr);
  }
}

.field {
  display: block;
  min-width: 0;
  margin-bottom: 0;
}

.field span {
  display: block;
  margin-bottom: 3px;
  color: #252525;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.field input,
.field select {
  display: block;
  width: 100%;
  min-width: 0;
  height: 24px;
  padding: 0 8px;
  box-sizing: border-box;
  border: 1px solid #aaa;
  border-radius: 3px;
  background: #fff;
  color: #333;
  font: inherit;
  font-size: 12px;
}

.field input:focus-visible,
.field select:focus-visible {
  outline: 1px solid #d5b900;
  outline-offset: 1px;
}

.form-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
}

.calculate-button {
  min-width: 110px;
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

.calculate-button:hover:not(:disabled) {
  background: #ffe033;
}

.calculate-button:disabled {
  opacity: 0.6;
  cursor: wait;
}

.save-button {
  min-width: 110px;
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
</style>
<style scoped>
.constraint-note { margin: 12px 0 0; color: #909399; font-size: 12px; }
</style>
