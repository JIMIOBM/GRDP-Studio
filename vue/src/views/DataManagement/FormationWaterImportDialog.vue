<script setup>
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Document } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  importKind: {
    type: String,
    default: 'data',
    validator: value => ['data', 'result'].includes(value)
  }
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

// 关闭地层水导入窗口。
const close = () => emit('update:modelValue', false)

// 打开浏览器文件选择器。
const chooseFile = () => fileInput.value?.click()

// 保存用户选择或拖入的文件。
const setFile = (file) => {
  if (!file) return
  selectedFile.value = file
}

// 处理文件选择器返回的文件。
const handleFileChange = (event) => {
  setFile(event.target.files?.[0])
  event.target.value = ''
}

// 处理拖拽上传的文件。
const handleDrop = (event) => {
  dragging.value = false
  setFile(event.dataTransfer?.files?.[0])
}

// 清除当前文件并允许重新选择。
const clearFile = (event) => {
  event.stopPropagation()
  selectedFile.value = null
}

const triggerDownload = (blob, filename) => {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

// 下载当前页签对应的地层水基础数据或完整结果数据模板。
const downloadTemplate = async (kind) => {
  if (kind === 'result') {
    const rows = [[
      '压力(MPa)',
      '温度(℃)',
      '地层水矿化度(mg/L)',
      '天然气在水中的溶解度(dless)',
      '地层水体积系数(dless)',
      '地层水密度(kg/m³)',
      '地层水等温压缩系数(MPa⁻¹)',
      '地层水粘度(mPa·s)'
    ]]
    const csv = `\uFEFF${rows.map(row => row.join(',')).join('\r\n')}`
    triggerDownload(
      new Blob([csv], { type: 'text/csv;charset=utf-8' }),
      '地层水结果数据模板.csv'
    )
    return
  }

  try {
    const { default: ExcelJS } = await import('exceljs')
    const workbook = new ExcelJS.Workbook()
    const worksheet = workbook.addWorksheet('地层水数据')
    const headers = [
      '天然气类型',
      '天然气比重(dless)',
      'H₂S摩尔百分含量(%)',
      'CO₂摩尔百分含量(%)',
      'N₂摩尔百分含量(%)'
    ]
    worksheet.addRow(headers)
    worksheet.views = [{ state: 'frozen', ySplit: 1 }]
    worksheet.columns = [
      { width: 18 },
      { width: 22 },
      { width: 24 },
      { width: 24 },
      { width: 24 }
    ]
    const headerRow = worksheet.getRow(1)
    headerRow.font = { bold: true }
    headerRow.alignment = { vertical: 'middle', horizontal: 'center' }

    // 地层水页面的基础模板使用天然气五列数据，并限制天然气类型取值。
    worksheet.dataValidations.add('A2', {
      type: 'list',
      allowBlank: true,
      formulae: ['"干气,湿气,凝析气"'],
      showErrorMessage: true,
      errorStyle: 'stop',
      errorTitle: '天然气类型不正确',
      error: '天然气类型只能选择：干气、湿气或凝析气'
    })
    const buffer = await workbook.xlsx.writeBuffer()
    triggerDownload(
      new Blob([buffer], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
      }),
      '地层水数据模板.xlsx'
    )
  } catch (error) {
    ElMessage.error(error?.message || '地层水数据模板下载失败')
  }
}

// 校验文件并将文件及清洗配置交给父组件处理。
const confirmImport = () => {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择需要导入的文件')
    return
  }
  emit('confirm', {
    file: selectedFile.value,
    options: { ...importOptions },
    kind: props.importKind
  })
  close()
}

// 支持按 Escape 键关闭弹窗。
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
    <div v-if="modelValue" class="water-import-overlay" role="presentation" @mousedown.self="close">
      <section
          class="water-import-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="water-import-title"
      >
        <header class="water-import-header">
          <h2 id="water-import-title">
            {{ importKind === 'result' ? '导入地层水结果分析数据' : '导入地层水基础数据' }}
          </h2>
          <button type="button" class="water-import-close" aria-label="关闭导入窗口" @click="close">×</button>
        </header>

        <div class="water-import-body">
          <div class="water-import-downloads">
            <button type="button" @click="downloadTemplate(importKind)">
              {{ importKind === 'result' ? '结果数据模板下载' : '数据模板下载' }}
            </button>
          </div>

          <div class="water-import-options" aria-label="导入清洗选项">
            <label><input v-model="importOptions.removeEmptyRows" type="checkbox" />移除空行</label>
            <label><input v-model="importOptions.removeEmptyColumns" type="checkbox" />移除空列</label>
            <label><input v-model="importOptions.removeZeroRows" type="checkbox" />移除数据全是0的行</label>
            <label><input v-model="importOptions.removeZeroColumns" type="checkbox" />移除数据全是0的列</label>
            <label><input v-model="importOptions.fillEmptyWithZero" type="checkbox" />数据为空的填充0</label>
          </div>

          <div
              class="water-import-dropzone"
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
                class="water-import-file-input"
                type="file"
                :accept="acceptedExtensions"
                @change="handleFileChange"
            />
            <el-icon class="water-import-icon"><Document /></el-icon>
            <template v-if="selectedFile">
              <p class="water-import-file-name">{{ selectedFile.name }}</p>
              <button type="button" class="water-import-clear" @click="clearFile">重新选择</button>
            </template>
            <template v-else>
              <p class="water-import-prompt">点击或将文件拖拽到这里上传</p>
              <p class="water-import-hint">支持扩展名：.xlsx .xls .csv</p>
            </template>
          </div>
        </div>

        <footer class="water-import-footer">
          <button type="button" class="water-import-cancel" @click="close">取消</button>
          <button type="button" class="water-import-confirm" @click="confirmImport">确定</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style lang="scss" scoped>
.water-import-overlay {
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

.water-import-dialog {
  width: min(742px, calc(100vw - 40px));
  height: min(494px, calc(100vh - 32px));
  min-height: 410px;
  display: flex;
  flex-direction: column;
  background: #fff;
  color: #333;
  box-shadow: 0 2px 7px rgba(0, 0, 0, 0.14);
}

.water-import-header {
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

.water-import-close {
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

.water-import-body {
  flex: 1;
  min-height: 0;
  padding: 12px 24px 8px;
  box-sizing: border-box;
}

.water-import-downloads {
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

.water-import-options {
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

.water-import-dropzone {
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

.water-import-file-input {
  display: none;
}

.water-import-icon {
  width: 44px;
  height: 44px;
  color: #2495ef;
  font-size: 44px;
}

.water-import-prompt,
.water-import-file-name {
  margin: 51px 0 0;
  color: #666;
  font-size: 17px;
  line-height: 24px;
}

.water-import-file-name {
  margin-top: 28px;
  max-width: 80%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.water-import-hint {
  margin: 15px 0 0;
  color: #999;
  font-size: 14px;
  line-height: 20px;
}

.water-import-clear {
  margin-top: 12px;
  padding: 4px 12px;
  border: 0;
  background: transparent;
  color: #2495ef;
  font: inherit;
  cursor: pointer;
}

.water-import-footer {
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

.water-import-cancel {
  border: 1px solid #9b7278;
  background: #fff7f7;
  color: #72222a;

  &:hover,
  &:focus-visible {
    background: #fff0f0;
    outline: 2px solid rgba(155, 114, 120, 0.18);
  }
}

.water-import-confirm {
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
  .water-import-body {
    padding-inline: 14px;
  }

  .water-import-options {
    padding: 7px 0;
  }

  .water-import-dropzone {
    height: calc(100% - 96px);
  }

  .water-import-prompt {
    margin-top: 28px;
  }
}
</style>
