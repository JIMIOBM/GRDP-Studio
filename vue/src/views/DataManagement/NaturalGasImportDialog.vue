<script setup>
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Document } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'confirm'])

const fileInput = ref(null)
const selectedFile = ref(null)
const dragging = ref(false)
const importOptions = reactive({
  removeEmptyRows: false,
  removeEmptyColumns: false,
  removeZeroRows: false,
  removeZeroColumns: false,
  fillEmptyWithZero: false
})

const acceptedExtensions = '.xls,.xlsx,.csv'

const close = () => emit('update:modelValue', false)

const chooseFile = () => fileInput.value?.click()

const setFile = (file) => {
  if (!file) return
  selectedFile.value = file
}

const handleFileChange = (event) => {
  setFile(event.target.files?.[0])
  event.target.value = ''
}

const handleDrop = (event) => {
  dragging.value = false
  setFile(event.dataTransfer?.files?.[0])
}

const clearFile = (event) => {
  event.stopPropagation()
  selectedFile.value = null
}

const downloadTemplate = (kind) => {
  const rows = kind === 'result'
    ? [
        ['压力(MPa)', '天然气偏差系数(dless)', '天然气体积系数(dless)', '天然气密度(kg/m³)', '天然气粘度(mPa·s)'],
        ['', '', '', '', '']
      ]
    : [
        ['天然气类型', '天然气比重(dless)', 'H₂S摩尔百分含量(%)', 'CO₂摩尔百分含量(%)', 'N₂摩尔百分含量(%)'],
        ['干气', '', '', '', '']
      ]
  const csv = `\uFEFF${rows.map(row => row.join(',')).join('\r\n')}`
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = kind === 'result' ? '天然气结果数据模板.csv' : '天然气数据模板.csv'
  anchor.click()
  URL.revokeObjectURL(url)
}

const confirmImport = () => {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择需要导入的文件')
    return
  }
  emit('confirm', {
    file: selectedFile.value,
    options: { ...importOptions }
  })
  close()
}

const handleKeydown = (event) => {
  if (event.key === 'Escape' && props.modelValue) close()
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      selectedFile.value = null
      window.addEventListener('keydown', handleKeydown)
    } else {
      window.removeEventListener('keydown', handleKeydown)
    }
  }
)

onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="gas-import-overlay" role="presentation" @mousedown.self="close">
      <section
        class="gas-import-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="gas-import-title"
      >
        <header class="gas-import-header">
          <h2 id="gas-import-title">导入</h2>
          <button type="button" class="gas-import-close" aria-label="关闭导入窗口" @click="close">×</button>
        </header>

        <div class="gas-import-body">
          <div class="gas-import-downloads">
            <button type="button" @click="downloadTemplate('data')">数据模板下载</button>
            <button type="button" @click="downloadTemplate('result')">结果数据模板下载</button>
          </div>

          <div class="gas-import-options" aria-label="导入清洗选项">
            <label><input v-model="importOptions.removeEmptyRows" type="checkbox" />移除空行</label>
            <label><input v-model="importOptions.removeEmptyColumns" type="checkbox" />移除空列</label>
            <label><input v-model="importOptions.removeZeroRows" type="checkbox" />移除数据全是0的行</label>
            <label><input v-model="importOptions.removeZeroColumns" type="checkbox" />移除数据全是0的列</label>
            <label><input v-model="importOptions.fillEmptyWithZero" type="checkbox" />数据为空的填充0</label>
          </div>

          <div
            class="gas-import-dropzone"
            :class="{ dragging, selected: selectedFile }"
            role="button"
            tabindex="0"
            aria-label="选择或拖拽上传文件"
            @click="chooseFile"
            @keydown.enter.prevent="chooseFile"
            @keydown.space.prevent="chooseFile"
            @dragenter.prevent="dragging = true"
            @dragover.prevent="dragging = true"
            @dragleave.prevent="dragging = false"
            @drop.prevent="handleDrop"
          >
            <input
              ref="fileInput"
              class="gas-import-file-input"
              type="file"
              :accept="acceptedExtensions"
              @change="handleFileChange"
            />
            <el-icon class="gas-import-icon"><Document /></el-icon>
            <template v-if="selectedFile">
              <p class="gas-import-file-name">{{ selectedFile.name }}</p>
              <button type="button" class="gas-import-clear" @click="clearFile">重新选择</button>
            </template>
            <template v-else>
              <p class="gas-import-prompt">点击或将文件拖拽到这里上传</p>
              <p class="gas-import-hint">支持扩展名：.xlsx .xls .csv</p>
            </template>
          </div>
        </div>

        <footer class="gas-import-footer">
          <button type="button" class="gas-import-cancel" @click="close">取消</button>
          <button type="button" class="gas-import-confirm" @click="confirmImport">确定</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style lang="scss" scoped>
.gas-import-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px 20px;
  box-sizing: border-box;
  background: rgba(0, 0, 0, 0.18);
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
}

.gas-import-dialog {
  width: min(742px, calc(100vw - 40px));
  height: min(494px, calc(100vh - 32px));
  min-height: 410px;
  display: flex;
  flex-direction: column;
  background: #fff;
  color: #333;
  box-shadow: 0 2px 7px rgba(0, 0, 0, 0.14);
}

.gas-import-header {
  height: 38px;
  flex: 0 0 38px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 11px 0 13px;
  box-sizing: border-box;
  background: #353535;
  color: #fff;

  h2 {
    margin: 0;
    font-size: 15px;
    font-weight: 700;
    line-height: 1;
  }
}

.gas-import-close {
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #ffe9ef;
  font: 22px/22px Arial, sans-serif;
  cursor: pointer;

  &:hover,
  &:focus-visible {
    color: #fff;
    background: rgba(255, 255, 255, 0.1);
    outline: none;
  }
}

.gas-import-body {
  flex: 1;
  min-height: 0;
  padding: 12px 24px 8px;
  box-sizing: border-box;
}

.gas-import-downloads {
  display: flex;
  gap: 8px;

  button {
    height: 32px;
    padding: 0 12px;
    border: 1px solid #8f8f8f;
    border-radius: 4px;
    background: #fff;
    color: #222;
    font: 14px/30px "Microsoft YaHei", sans-serif;
    cursor: pointer;

    &:hover,
    &:focus-visible {
      border-color: #409eff;
      color: #1677d2;
      outline: none;
    }
  }
}

.gas-import-options {
  min-height: 42px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px 12px;
  color: #333;
  font-size: 14px;

  label {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    white-space: nowrap;
    cursor: pointer;
  }

  input {
    width: 14px;
    height: 14px;
    margin: 0;
    accent-color: #3f55e7;
  }
}

.gas-import-dropzone {
  height: calc(100% - 74px);
  min-height: 250px;
  border: 1px dashed #dedede;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;

  &:hover,
  &:focus-visible,
  &.dragging {
    border-color: #2495ef;
    background: #f8fcff;
    outline: none;
  }
}

.gas-import-file-input {
  display: none;
}

.gas-import-icon {
  width: 44px;
  height: 44px;
  color: #2495ef;
  font-size: 44px;
}

.gas-import-prompt,
.gas-import-file-name {
  margin: 51px 0 0;
  color: #666;
  font-size: 17px;
  line-height: 24px;
}

.gas-import-file-name {
  margin-top: 28px;
  max-width: 80%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gas-import-hint {
  margin: 15px 0 0;
  color: #999;
  font-size: 14px;
  line-height: 20px;
}

.gas-import-clear {
  margin-top: 12px;
  padding: 4px 12px;
  border: 0;
  background: transparent;
  color: #2495ef;
  font: inherit;
  cursor: pointer;
}

.gas-import-footer {
  height: 60px;
  flex: 0 0 60px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 0 10px;
  box-sizing: border-box;
  border-top: 1px solid #888;

  button {
    width: 72px;
    height: 30px;
    border-radius: 3px;
    font: 14px "Microsoft YaHei", sans-serif;
    cursor: pointer;
  }
}

.gas-import-cancel {
  border: 1px solid #9b7278;
  background: #fff7f7;
  color: #72222a;

  &:hover,
  &:focus-visible {
    background: #fff0f0;
    outline: 2px solid rgba(155, 114, 120, 0.18);
  }
}

.gas-import-confirm {
  border: 1px solid #3d53e5;
  background: #3d53e5;
  color: #fff;

  &:hover,
  &:focus-visible {
    background: #3047dd;
    outline: 2px solid rgba(61, 83, 229, 0.2);
  }
}

@media (max-width: 680px) {
  .gas-import-body {
    padding-inline: 14px;
  }

  .gas-import-options {
    padding: 7px 0;
  }

  .gas-import-dropzone {
    height: calc(100% - 96px);
  }

  .gas-import-prompt {
    margin-top: 28px;
  }
}
</style>
