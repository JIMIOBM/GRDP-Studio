<script setup>
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Document } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  dataTemplateRows: { type: Array, default: () => [] },
  dataTemplateFileName: { type: String, default: '数据模板.csv' }
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

const close = () => emit('update:modelValue', false)
const chooseFile = () => fileInput.value?.click()

const setFile = file => {
  if (!file) return
  const extension = file.name.split('.').pop()?.toLowerCase()
  if (!['xlsx', 'xls', 'csv'].includes(extension)) {
    ElMessage.warning('仅支持 .xlsx、.xls、.csv 表格文件')
    return
  }
  selectedFile.value = file
}

const handleFileChange = event => {
  setFile(event.target.files?.[0])
  event.target.value = ''
}

const handleDrop = event => {
  dragging.value = false
  setFile(event.dataTransfer?.files?.[0])
}

const clearFile = event => {
  event.stopPropagation()
  selectedFile.value = null
}

const downloadTemplate = () => {
  const csv = `\uFEFF${props.dataTemplateRows.map(row => row.join(',')).join('\r\n')}`
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = props.dataTemplateFileName
  anchor.click()
  URL.revokeObjectURL(url)
}

const confirmImport = () => {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择需要导入的文件')
    return
  }
  emit('confirm', { file: selectedFile.value, options: { ...importOptions } })
  close()
}

const handleKeydown = event => {
  if (event.key === 'Escape' && props.modelValue) close()
}

watch(
  () => props.modelValue,
  visible => {
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
    <div v-if="modelValue" class="well-import-overlay" @mousedown.self="close">
      <section class="well-import-dialog" role="dialog" aria-modal="true" aria-labelledby="well-import-title">
        <header class="well-import-header">
          <h2 id="well-import-title">导入</h2>
          <button type="button" aria-label="关闭导入窗口" @click="close">×</button>
        </header>

        <div class="well-import-body">
          <div class="well-import-downloads">
            <button type="button" @click="downloadTemplate">数据模板下载</button>
          </div>

          <div class="well-import-options" aria-label="导入清洗选项">
            <label><input v-model="importOptions.removeEmptyRows" type="checkbox" />移除空行</label>
            <label><input v-model="importOptions.removeEmptyColumns" type="checkbox" />移除空列</label>
            <label><input v-model="importOptions.removeZeroRows" type="checkbox" />移除数据全是0的行</label>
            <label><input v-model="importOptions.removeZeroColumns" type="checkbox" />移除数据全是0的列</label>
            <label><input v-model="importOptions.fillEmptyWithZero" type="checkbox" />数据为空的填充0</label>
          </div>

          <div
            class="well-import-dropzone"
            :class="{ dragging, selected: selectedFile }"
            role="button"
            tabindex="0"
            @click="chooseFile"
            @keydown.enter.prevent="chooseFile"
            @keydown.space.prevent="chooseFile"
            @dragenter.prevent="dragging = true"
            @dragover.prevent="dragging = true"
            @dragleave.prevent="dragging = false"
            @drop.prevent="handleDrop"
          >
            <input ref="fileInput" type="file" accept=".xls,.xlsx,.csv" @change="handleFileChange" />
            <el-icon class="well-import-icon"><Document /></el-icon>
            <template v-if="selectedFile">
              <p class="well-import-file-name">{{ selectedFile.name }}</p>
              <button type="button" class="well-import-clear" @click="clearFile">重新选择</button>
            </template>
            <template v-else>
              <p class="well-import-prompt">点击或将文件拖拽到这里上传</p>
              <p class="well-import-hint">支持扩展名：.xlsx .xls .csv</p>
            </template>
          </div>
        </div>

        <footer class="well-import-footer">
          <button type="button" class="cancel" @click="close">取消</button>
          <button type="button" class="confirm" @click="confirmImport">确定</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style lang="scss" scoped>
.well-import-overlay {
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

.well-import-dialog {
  width: min(742px, calc(100vw - 40px));
  height: min(494px, calc(100vh - 32px));
  min-height: 410px;
  display: flex;
  flex-direction: column;
  background: #fff;
  color: #333;
  box-shadow: 0 2px 7px rgba(0, 0, 0, 0.14);
}

.well-import-header {
  height: 38px;
  flex: 0 0 38px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 11px 0 13px;
  box-sizing: border-box;
  background: #353535;
  color: #fff;

  h2 { margin: 0; font-size: 15px; line-height: 1; }
  button {
    width: 24px;
    height: 24px;
    padding: 0;
    border: 0;
    background: transparent;
    color: #fff;
    font: 22px/22px Arial, sans-serif;
    cursor: pointer;
  }
}

.well-import-body {
  flex: 1;
  min-height: 0;
  padding: 12px 24px 8px;
  box-sizing: border-box;
}

.well-import-downloads button {
  height: 32px;
  padding: 0 12px;
  border: 1px solid #8f8f8f;
  border-radius: 4px;
  background: #fff;
  color: #222;
  font: 14px/30px "Microsoft YaHei", sans-serif;
  cursor: pointer;
}

.well-import-options {
  min-height: 42px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px 12px;
  font-size: 14px;

  label { display: inline-flex; align-items: center; gap: 6px; white-space: nowrap; cursor: pointer; }
  input { width: 14px; height: 14px; margin: 0; accent-color: #3f55e7; }
}

.well-import-dropzone {
  height: calc(100% - 74px);
  min-height: 250px;
  border: 1px dashed #dedede;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;

  &:hover, &:focus-visible, &.dragging { border-color: #2495ef; background: #f8fcff; outline: none; }
  > input { display: none; }
}

.well-import-icon { width: 44px; height: 44px; color: #2495ef; font-size: 44px; }
.well-import-prompt, .well-import-file-name { margin: 51px 0 0; color: #666; font-size: 17px; }
.well-import-file-name { margin-top: 28px; max-width: 80%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.well-import-hint { margin: 15px 0 0; color: #999; font-size: 14px; }
.well-import-clear { margin-top: 12px; border: 0; background: transparent; color: #2495ef; cursor: pointer; }

.well-import-footer {
  height: 60px;
  flex: 0 0 60px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 0 10px;
  border-top: 1px solid #888;
  box-sizing: border-box;

  button { width: 72px; height: 30px; border-radius: 3px; cursor: pointer; }
  .cancel { border: 1px solid #9b7278; background: #fff7f7; color: #72222a; }
  .confirm { border: 1px solid #3d53e5; background: #3d53e5; color: #fff; }
}
</style>
