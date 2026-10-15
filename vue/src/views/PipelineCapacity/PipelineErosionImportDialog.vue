<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Document } from '@element-plus/icons-vue'
import { createBoundaryImportGuard } from '@/utils/pipelineBoundaryImport'
import { erosionImportContextStamp, createErosionImportWorkbook, parseErosionImportWorkbook } from '@/utils/pipelineErosionImport'

const props = defineProps({ modelValue: Boolean, graph: { type: Object, default: null },
  topologyRevision: { type: Number, default: 0 }, context: { type: Object, required: true },
  cases: { type: Array, default: () => [] }, settings: { type: Object, default: () => ({}) } })
const emit = defineEmits(['update:modelValue', 'imported'])
const fileInput = ref(null), selectedFile = ref(null), dragging = ref(false), busy = ref(false), error = ref('')
const guard = createBoundaryImportGuard()
const contextStamp = computed(() => erosionImportContextStamp(props.graph, props.topologyRevision, props.context, props.cases))
const current = token => guard.isCurrent(token, contextStamp.value, props.modelValue)
const close = () => { guard.cancel(); busy.value = false; emit('update:modelValue', false) }
const chooseFile = () => { if (!busy.value) fileInput.value?.click() }
function setFile(file) {
  if (!file || busy.value) return
  guard.cancel(); error.value = ''; selectedFile.value = null
  if (!/\.(xlsx|xls)$/i.test(file.name)) { error.value = '仅支持 .xlsx、.xls 文件，请保留模板的全部工作表。'; return }
  if (file.size > 20 * 1024 * 1024) { error.value = '文件不能超过 20 MB，请移除无关内容后重试。'; return }
  selectedFile.value = file
}
function fileChanged(event) { setFile(event.target.files?.[0]); event.target.value = '' }
function dropped(event) { dragging.value = false; setFile(event.dataTransfer?.files?.[0]) }
function clearFile(event) { event.stopPropagation(); if (!busy.value) { selectedFile.value = null; error.value = ''; guard.cancel() } }
function snapshot() { return JSON.parse(JSON.stringify({ graph: props.graph, revision: props.topologyRevision,
  context: props.context, cases: props.cases, settings: props.settings })) }
async function downloadTemplate() {
  const token = guard.begin(contextStamp.value), source = snapshot()
  busy.value = true; error.value = ''
  try {
    const XLSX = await import('xlsx')
    if (!current(token)) return
    const workbook = createErosionImportWorkbook(XLSX, source.graph, source.revision, source.context, source.cases, source.settings)
    const bytes = XLSX.write(workbook, { type: 'array', bookType: 'xlsx' })
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
    const anchor = document.createElement('a')
    anchor.href = url; anchor.download = `${(source.context.wellName || '当前井').replace(/[\\/:*?"<>|]/g, '_')}-冲蚀参数模板.xlsx`
    try { anchor.click() } finally { URL.revokeObjectURL(url) }
  } catch (cause) { if (current(token)) error.value = cause?.message || '模板下载失败，请重试。' }
  finally { if (current(token)) busy.value = false }
}
async function confirmImport() {
  if (!selectedFile.value) { error.value = '请先选择需要导入的文件。'; return }
  const token = guard.begin(contextStamp.value), source = snapshot(), file = selectedFile.value
  busy.value = true; error.value = ''
  try {
    const [XLSX, bytes] = await Promise.all([import('xlsx'), file.arrayBuffer()])
    if (!current(token)) return
    const workbook = XLSX.read(bytes, { type: 'array', cellFormula: true, cellDates: false })
    const settings = parseErosionImportWorkbook(XLSX, workbook, source.graph, source.revision, source.context, source.cases)
    if (!current(token)) return
    emit('imported', { ...settings, fileName: file.name, contextStamp: token.stamp })
    close()
  } catch (cause) { if (current(token)) error.value = cause?.message || '文件读取失败，请检查文件格式。' }
  finally { if (current(token)) busy.value = false }
}
function keydown(event) { if (event.key === 'Escape' && props.modelValue) close() }
watch(() => [props.modelValue, contextStamp.value], ([visible, stamp], previous) => {
  guard.cancel(); busy.value = false; dragging.value = false; selectedFile.value = null
  error.value = visible && previous?.[0] && previous[1] !== stamp ? '当前井、拓扑或工况已变化，请重新下载模板。' : ''
  if (visible) window.addEventListener('keydown', keydown)
  else window.removeEventListener('keydown', keydown)
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { guard.cancel(); window.removeEventListener('keydown', keydown) })
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="erosion-import-overlay" @mousedown.self="close">
      <section class="erosion-import-dialog" role="dialog" aria-modal="true" aria-labelledby="erosion-import-title">
        <header><h2 id="erosion-import-title">本地导入</h2><button type="button" aria-label="关闭导入窗口" @click="close">×</button></header>
        <div class="import-body">
          <div class="downloads"><button type="button" :disabled="busy" @click="downloadTemplate">数据模板下载</button><span>{{ graph?.edges?.length || 0 }} 条管道 · {{ cases.length }} 组工况</span></div>
          <p class="description">模板包含“管道共用参数”和“工况冲蚀参数”，持液率、含砂率和砂粒密度均由你填写，系统不预设数值。工况参数留空时采用你填写的管道共用值；两处都未填时不能计算。管道名称和工况时间自动填入，请保留原顺序及隐藏的校验工作表。</p>
          <div class="dropzone" :class="{ dragging, selected: selectedFile, busy }" role="button" :tabindex="busy ? -1 : 0" :aria-disabled="busy"
            @click="chooseFile" @keydown.enter.prevent="chooseFile" @keydown.space.prevent="chooseFile"
            @dragenter.prevent="dragging = !busy" @dragover.prevent="dragging = !busy" @dragleave.prevent="dragging = false" @drop.prevent="dropped">
            <input ref="fileInput" type="file" accept=".xls,.xlsx" :disabled="busy" @change="fileChanged" />
            <el-icon class="import-icon"><Document /></el-icon>
            <template v-if="selectedFile"><p class="file-name">{{ selectedFile.name }}</p><button type="button" class="clear" :disabled="busy" @click="clearFile">重新选择</button></template>
            <template v-else><p class="prompt">点击或将文件拖拽到这里上传</p><p class="file-hint">支持扩展名：.xlsx .xls</p></template>
          </div>
          <p v-if="error" class="import-error" role="alert">{{ error }}</p>
          <p v-else class="description">导入将更新全部冲蚀输入参数。气体密度、液体密度及流速由计算获取，不从文件导入；液相来源在左侧选择。</p>
        </div>
        <footer><button type="button" class="cancel" @click="close">取消</button><button type="button" class="confirm" :disabled="busy" @click="confirmImport">{{ busy ? '处理中…' : '确定' }}</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.erosion-import-overlay{position:fixed;inset:0;z-index:3000;display:flex;align-items:center;justify-content:center;padding:16px 20px;box-sizing:border-box;background:rgba(0,0,0,.18);font-family:"Microsoft YaHei","Segoe UI",sans-serif}.erosion-import-dialog{width:min(742px,calc(100vw - 40px));max-height:calc(100vh - 32px);display:flex;flex-direction:column;background:#fff;color:#333;box-shadow:0 2px 7px rgba(0,0,0,.14)}header{height:38px;flex:0 0 38px;display:flex;align-items:center;justify-content:space-between;padding:0 11px 0 13px;box-sizing:border-box;background:#353535;color:#fff}header h2{margin:0;font-size:15px;line-height:1}header button{width:24px;height:24px;padding:0;border:0;background:transparent;color:#fff;font:22px/22px Arial,sans-serif;cursor:pointer}.import-body{min-height:0;padding:12px 24px 8px;box-sizing:border-box;overflow:auto}.downloads{display:flex;align-items:center;gap:14px}.downloads span{font-size:12px;color:#777}.downloads button{height:32px;padding:0 12px;border:1px solid #8f8f8f;border-radius:4px;background:#fff;color:#222;font:14px/30px "Microsoft YaHei",sans-serif;cursor:pointer}.description{margin:10px 0;font-size:12px;line-height:1.8;color:#777}.dropzone{min-height:250px;border:1px dashed #dedede;box-sizing:border-box;display:flex;flex-direction:column;align-items:center;justify-content:center;cursor:pointer}.dropzone:hover,.dropzone:focus-visible,.dropzone.dragging{border-color:#2495ef;background:#f8fcff;outline:none}.dropzone.busy{cursor:wait;opacity:.7}.dropzone>input{display:none}.import-icon{width:44px;height:44px;color:#2495ef;font-size:44px}.prompt,.file-name{margin:35px 0 0;color:#666;font-size:17px}.file-name{margin-top:28px;max-width:90%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.file-hint{margin:15px 0 0;color:#999;font-size:14px}.clear{margin-top:12px;border:0;background:transparent;color:#2495ef;cursor:pointer}.import-error{margin:10px 0 0;padding:7px 9px;background:#fff2f0;color:#bd3e35;font-size:12px;line-height:1.8;overflow-wrap:anywhere}footer{height:60px;flex:0 0 60px;display:flex;align-items:center;justify-content:flex-end;gap:8px;padding:0 10px;border-top:1px solid #888;box-sizing:border-box}footer button{min-width:72px;height:30px;border-radius:3px;cursor:pointer}.cancel{border:1px solid #9b7278;background:#fff7f7;color:#72222a}.confirm{border:1px solid #3d53e5;background:#3d53e5;color:#fff}button:disabled{cursor:not-allowed;opacity:.55}
</style>
