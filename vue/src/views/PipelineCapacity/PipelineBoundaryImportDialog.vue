<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Document } from '@element-plus/icons-vue'
import { boundaryImportContextStamp, createBoundaryImportGuard, createBoundaryImportWorkbook,
  parseBoundaryImportWorkbook } from '@/utils/pipelineBoundaryImport'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  graph: { type: Object, default: null },
  topologyRevision: { type: Number, default: 0 },
  context: { type: Object, required: true }
})
const emit = defineEmits(['update:modelValue', 'imported'])
const fileInput = ref(null), selectedFile = ref(null), dragging = ref(false), busy = ref(false), error = ref('')
const guard = createBoundaryImportGuard()
const contextStamp = computed(() => boundaryImportContextStamp(props.graph, props.topologyRevision, props.context))
const current = token => guard.isCurrent(token, contextStamp.value, props.modelValue)
const close = () => { guard.cancel(); busy.value = false; emit('update:modelValue', false) }
const chooseFile = () => { if (!busy.value) fileInput.value?.click() }
function setFile(file) {
  if (!file || busy.value) return
  guard.cancel(); error.value = ''; selectedFile.value = null
  if (!/\.(xlsx|xls)$/i.test(file.name)) { error.value = '仅支持 .xlsx、.xls 文件，请保留模板中的拓扑校验工作表。'; return }
  if (file.size > 20 * 1024 * 1024) { error.value = '文件不能超过 20 MB，请移除无关内容后重试。'; return }
  selectedFile.value = file
}
function fileChanged(event) { setFile(event.target.files?.[0]); event.target.value = '' }
function dropped(event) { dragging.value = false; setFile(event.dataTransfer?.files?.[0]) }
function clearFile(event) { event.stopPropagation(); if (!busy.value) { selectedFile.value = null; error.value = ''; guard.cancel() } }
function snapshot() {
  return { graph: JSON.parse(JSON.stringify(props.graph)), topologyRevision: props.topologyRevision,
    context: { projectId: props.context?.projectId, gasReservoirId: props.context?.gasReservoirId, wellName: props.context?.wellName } }
}

async function downloadTemplate() {
  const token = guard.begin(contextStamp.value)
  busy.value = true; error.value = ''
  try {
    const source = snapshot(), XLSX = await import('xlsx')
    if (!current(token)) return
    const workbook = createBoundaryImportWorkbook(XLSX, source.graph, source.topologyRevision, source.context)
    const bytes = XLSX.write(workbook, { type: 'array', bookType: 'xlsx' })
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${(source.context.wellName || '当前井').replace(/[\\/:*?"<>|]/g, '_')}-边界条件模板.xlsx`
    try { anchor.click() } finally { URL.revokeObjectURL(url) }
  } catch (cause) { if (current(token)) error.value = cause?.message || '模板下载失败，请重试。' }
  finally { if (current(token)) busy.value = false }
}

async function confirmImport() {
  if (!selectedFile.value) { error.value = '请先选择需要导入的文件。'; return }
  const token = guard.begin(contextStamp.value), file = selectedFile.value
  busy.value = true; error.value = ''
  try {
    const source = snapshot()
    const [XLSX, bytes] = await Promise.all([import('xlsx'), file.arrayBuffer()])
    if (!current(token)) return
    // Read serial dates directly; JS Date conversion can shift an Excel wall-clock value by timezone.
    const workbook = XLSX.read(bytes, { type: 'array', cellFormula: true, cellDates: false })
    const cases = parseBoundaryImportWorkbook(XLSX, workbook, source.graph, source.topologyRevision, source.context)
    if (!current(token)) return
    emit('imported', { cases, fileName: file.name })
    close()
  } catch (cause) { if (current(token)) error.value = cause?.message || '文件读取失败，请检查文件格式。' }
  finally { if (current(token)) busy.value = false }
}

function keydown(event) { if (event.key === 'Escape' && props.modelValue) close() }
watch(() => [props.modelValue, contextStamp.value], ([visible, stamp], previous) => {
  guard.cancel(); busy.value = false; dragging.value = false; selectedFile.value = null
  error.value = visible && previous?.[0] && previous[1] !== stamp ? '当前井或管网拓扑已变化，请重新下载模板并选择文件。' : ''
  if (visible) window.addEventListener('keydown', keydown)
  else window.removeEventListener('keydown', keydown)
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { guard.cancel(); window.removeEventListener('keydown', keydown) })
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="boundary-import-overlay" @mousedown.self="close">
      <section class="boundary-import-dialog" role="dialog" aria-modal="true" aria-labelledby="boundary-import-title">
        <header class="boundary-import-header"><h2 id="boundary-import-title">本地导入</h2><button type="button" aria-label="关闭导入窗口" @click="close">×</button></header>
        <div class="boundary-import-body">
          <div class="boundary-import-downloads"><button type="button" :disabled="busy" @click="downloadTemplate">数据模板下载</button><span>{{ graph?.nodes?.length || 0 }} 个节点</span></div>
          <p class="boundary-import-description">每行一个工况，最多 744 行。工况时间格式为 YYYY/MM/DD HH:mm，支持任意分钟和 Excel 日期时间。模板按当前已保存拓扑生成井口和各节点列；请保留两层表头及隐藏的拓扑校验工作表。数值未确定时可留空，未取气可填写 0。仅支持 Excel 文件。</p>
          <div class="boundary-import-dropzone" :class="{ dragging, selected: selectedFile, busy }" role="button" :tabindex="busy ? -1 : 0" :aria-disabled="busy"
            @click="chooseFile" @keydown.enter.prevent="chooseFile" @keydown.space.prevent="chooseFile"
            @dragenter.prevent="dragging = !busy" @dragover.prevent="dragging = !busy" @dragleave.prevent="dragging = false" @drop.prevent="dropped">
            <input ref="fileInput" type="file" accept=".xls,.xlsx" :disabled="busy" @change="fileChanged" />
            <el-icon class="boundary-import-icon"><Document /></el-icon>
            <template v-if="selectedFile"><p class="boundary-import-file-name">{{ selectedFile.name }}</p><button type="button" class="boundary-import-clear" :disabled="busy" @click="clearFile">重新选择</button></template>
            <template v-else><p class="boundary-import-prompt">点击或将文件拖拽到这里上传</p><p class="boundary-import-hint">支持扩展名：.xlsx .xls</p></template>
          </div>
          <p v-if="error" class="boundary-import-error" role="alert">{{ error }}</p>
          <p v-else class="boundary-import-note">时间文本可先导入到表格修改，保存时检查时间格式。请核对各节点的实测数据，未知值可留空。</p>
        </div>
        <footer class="boundary-import-footer"><button type="button" class="cancel" @click="close">取消</button><button type="button" class="confirm" :disabled="busy" @click="confirmImport">{{ busy ? '处理中…' : '确定' }}</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style lang="scss" scoped>
.boundary-import-overlay{position:fixed;inset:0;z-index:3000;display:flex;align-items:center;justify-content:center;padding:16px 20px;box-sizing:border-box;background:rgba(0,0,0,.18);font-family:"Microsoft YaHei","Segoe UI",sans-serif}
.boundary-import-dialog{width:min(742px,calc(100vw - 40px));max-height:calc(100vh - 32px);display:flex;flex-direction:column;background:#fff;color:#333;box-shadow:0 2px 7px rgba(0,0,0,.14)}
.boundary-import-header{height:38px;flex:0 0 38px;display:flex;align-items:center;justify-content:space-between;padding:0 11px 0 13px;box-sizing:border-box;background:#353535;color:#fff;h2{margin:0;font-size:15px;line-height:1}button{width:24px;height:24px;padding:0;border:0;background:transparent;color:#fff;font:22px/22px Arial,sans-serif;cursor:pointer}}
.boundary-import-body{min-height:0;padding:12px 24px 8px;box-sizing:border-box;overflow:auto}
.boundary-import-downloads{display:flex;align-items:center;gap:14px;span{font-size:12px;color:#777}button{height:32px;padding:0 12px;border:1px solid #8f8f8f;border-radius:4px;background:#fff;color:#222;font:14px/30px "Microsoft YaHei",sans-serif;cursor:pointer}}
.boundary-import-description,.boundary-import-note{margin:10px 0;font-size:12px;line-height:1.8;color:#777}.boundary-import-note{margin-bottom:0}
.boundary-import-dropzone{min-height:250px;border:1px dashed #dedede;box-sizing:border-box;display:flex;flex-direction:column;align-items:center;justify-content:center;cursor:pointer;&:hover,&:focus-visible,&.dragging{border-color:#2495ef;background:#f8fcff;outline:none}&.busy{cursor:wait;opacity:.7}>input{display:none}}
.boundary-import-icon{width:44px;height:44px;color:#2495ef;font-size:44px}.boundary-import-prompt,.boundary-import-file-name{margin:35px 0 0;color:#666;font-size:17px}.boundary-import-file-name{margin-top:28px;max-width:90%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.boundary-import-hint{margin:15px 0 0;color:#999;font-size:14px}.boundary-import-clear{margin-top:12px;border:0;background:transparent;color:#2495ef;cursor:pointer}.boundary-import-error{margin:10px 0 0;padding:7px 9px;background:#fff2f0;color:#bd3e35;font-size:12px;line-height:1.8;overflow-wrap:anywhere}
.boundary-import-footer{height:60px;flex:0 0 60px;display:flex;align-items:center;justify-content:flex-end;gap:8px;padding:0 10px;border-top:1px solid #888;box-sizing:border-box;button{min-width:72px;height:30px;border-radius:3px;cursor:pointer}.cancel{border:1px solid #9b7278;background:#fff7f7;color:#72222a}.confirm{border:1px solid #3d53e5;background:#3d53e5;color:#fff}}
button:disabled{cursor:not-allowed;opacity:.55}
</style>
