<script setup>
import { ref, watch } from 'vue'

// 仅用于讨论注采方案时预览交互；不向计算接口传参，也不持久化。
const props = defineProps({
  contextKey: { type: [String, Number], default: '' }
})

const operationType = ref('production')
watch(() => props.contextKey, () => { operationType.value = 'production' })
</script>

<template>
  <fieldset class="operation-preview">
    <legend>注采类型</legend>
    <div class="operation-options">
      <label><input v-model="operationType" type="radio" value="production" />采气</label>
      <label><input v-model="operationType" type="radio" value="injection" />注气</label>
    </div>
    <small>界面预览，暂不参与计算或保存</small>
  </fieldset>
</template>

<style scoped>
.operation-preview {
  border: 0;
  padding: 0;
  margin: 0 0 12px;
  min-width: 0;
  color: #303133;
  font-size: 13px;
}
.operation-preview legend { padding: 0; margin-bottom: 6px; }
.operation-options { display: flex; flex-wrap: wrap; gap: 14px; }
.operation-options label { display: inline-flex; align-items: center; gap: 4px; cursor: pointer; white-space: nowrap; }
.operation-options input { margin: 0; accent-color: #303133; cursor: pointer; }
.operation-preview small { display: block; margin-top: 6px; color: #909399; font-size: 11px; line-height: 1.4; }
</style>
