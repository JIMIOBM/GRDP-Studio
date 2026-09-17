<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Document } from '@element-plus/icons-vue'
import { innerImportTemplateRows, parseInnerImportRows } from '@/utils/pipelineInnerImport'
import { wallImportTemplateRows, parseWallImportRows } from '@/utils/pipelineWallImport'
import { outerImportTemplateRows, parseOuterImportRows, overallImportTemplateRows, parseOverallImportRows } from '@/utils/pipelineThermalParameterImport'
import { gasConditionTemplateRows, parseGasConditionRows } from '@/utils/pipelineGasConditionImport'
import { assertTemperatureImportSheet, parseTemperatureImportCsv } from '@/utils/pipelineTemperatureImport'
import { pvtModelTemplateRows, parsePvtModelRows } from '@/utils/pipelinePvtModel'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  graph: { type: Object, default: null },
  wellName: { type: String, default: '' },
  kind: { type: String, default: 'inner', validator: value => ['inner', 'wall', 'outer', 'overall', 'z', 'cp', 'pvt'].includes(value) },
  catalog: { type: Array, default: () => [] },
  composition: { type: Array, default: () => [] },
  settings: { type: Object, default: () => ({ segments: [] }) },
  point: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'imported'])
const fileInput = ref(null), selectedFile = ref(null), dragging = ref(false), busy = ref(false), error = ref('')
let operation = 0
const gasConditionModel = {
  singlePoint: true, sheetName: '温压参数',
  templateRows: gasConditionTemplateRows, parseRows: parseGasConditionRows,
  widths: [10, 28, 24],
  description: '当前 PVT 使用一组温压，仅填写序号 1 的压力和温度，未确定时可留空。气体组成和计算方法自动读取当前井已保存的 PVT 模型。'
}
const importModels = {
  pvt: { label: 'PVT气体组成', sheetName: '气体组成', singleComposition: true,
    templateRows: pvtModelTemplateRows, parseRows: parsePvtModelRows, widths: [8,16,20,22,24,24,20,28],
    description: '每个纯组分一行，仅编辑摩尔含量（%），不含的组分填写 0，保存时合计必须为 100%。组分名称、代码及常数为只读；C₇⁺ 等馏分不能当作纯组分导入。' },
  inner: {
    label: '内壁参数', sheetName: '内壁参数',
    templateRows: innerImportTemplateRows, parseRows: parseInnerImportRows,
    widths: [8, 28, 22, 28, 30, 26, 32],
    description: '按序号和管道名称对应全部管道。管内径与气体导热系数 λ 必填且大于 0，流量、压力和温度可留空。密度、Cp、黏度由当前井 PVT 模型提供，不从文件导入。'
  },
  wall: {
    label: '管道导热系数', sheetName: '材料层参数',
    templateRows: wallImportTemplateRows, parseRows: parseWallImportRows,
    widths: [12, 28, 20, 12, 24, 20, 30],
    description: '每个材料层一行，层序号从 1 连续填写，每管最多 12 层。管内径必须与已保存拓扑一致，厚度和导热系数可留空。'
  },
  outer: {
    label: '外部放热系数', sheetName: '外部放热参数',
    templateRows: outerImportTemplateRows, parseRows: parseOuterImportRows,
    widths: [8, 28, 20, 42, 24, 30, 34],
    description: '每个管道一行，管内径必须与已保存拓扑一致。换热方法填写第一类边界或第二类边界，未确定的参数可留空。导入仅更新本页环境参数。'
  },
  overall: {
    label: '总传热系数', sheetName: '总传热参数',
    templateRows: overallImportTemplateRows, parseRows: parseOverallImportRows,
    widths: [8, 28, 20, 30, 26, 24, 32],
    description: '每个管道一行，管内径必须与已保存拓扑一致。气体导热系数 λ 必填且大于 0，与内壁页共享；物性计算温压及环境温度可留空。密度、Cp、黏度不从文件导入，材料层及敷设参数保持原值。'
  },
  z: { ...gasConditionModel, label: '压缩因子温压' },
  cp: { ...gasConditionModel, label: '定压比热容温压' }
}
const importModel = computed(() => importModels[props.kind])
const topologyStamp = computed(() => importModel.value?.singleComposition ? JSON.stringify(props.catalog) : importModel.value?.singlePoint ? '' : JSON.stringify((props.graph?.edges || []).map(edge => [edge.id, edge.name, edge.parameters?.diameterMm])))
const close = () => { operation++; busy.value = false; emit('update:modelValue', false) }
const chooseFile = () => { if (!busy.value) fileInput.value?.click() }
function setFile(file) {
  if (!file || busy.value) return
  operation++; error.value = ''; selectedFile.value = null
  if (!['xlsx', 'xls', 'csv'].includes(file.name.split('.').pop()?.toLowerCase())) {
    error.value = '仅支持 .xlsx、.xls、.csv 表格文件。'; return
  }
  selectedFile.value = file
}
function fileChanged(event) { setFile(event.target.files?.[0]); event.target.value = '' }
function dropped(event) { dragging.value = false; setFile(event.dataTransfer?.files?.[0]) }
function clearFile(event) { event.stopPropagation(); if (!busy.value) { selectedFile.value = null; error.value = ''; operation++ } }

async function downloadTemplate() {
  const current = ++operation, stamp = topologyStamp.value, well = props.wellName, kind = props.kind, model = importModel.value
  busy.value = true; error.value = ''
  try {
    const rows = model.singleComposition ? model.templateRows(props.catalog,props.composition) : model.singlePoint ? model.templateRows(props.point) : model.templateRows(props.graph, props.settings)
    const XLSX = await import('xlsx')
    if (current !== operation || !props.modelValue || kind !== props.kind) return
    if (well !== props.wellName || (!model.singlePoint && stamp !== topologyStamp.value)) throw new Error(model.singlePoint
      ? '当前井已变化，请重新打开导入窗口。' : '当前管网拓扑已变化，请重新下载模板。')
    const sheet = XLSX.utils.aoa_to_sheet(rows)
    // Pipe names and layer names are text, even when they begin with '='.
    rows.forEach((row, r) => row.forEach((value, c) => {
      if (typeof value === 'string') sheet[XLSX.utils.encode_cell({ r, c })] = { t: 's', v: value }
    }))
    sheet['!cols'] = model.widths.map(wch => ({ wch }))
    const workbook = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(workbook, sheet, model.sheetName)
    const bytes = XLSX.write(workbook, { type: 'array', bookType: 'xlsx' })
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
    const anchor = document.createElement('a')
    anchor.href = url; anchor.download = `${(well || '当前井').replace(/[\\/:*?"<>|]/g, '_')}-${model.label}模板.xlsx`
    anchor.click(); URL.revokeObjectURL(url)
  } catch (cause) { if (current === operation) error.value = cause?.message || '模板下载失败，请重试。' }
  finally { if (current === operation) busy.value = false }
}

async function confirmImport() {
  if (!selectedFile.value) { error.value = '请先选择需要导入的文件。'; return }
  const current = ++operation, file = selectedFile.value, stamp = topologyStamp.value, well = props.wellName, kind = props.kind, model = importModel.value
  busy.value = true; error.value = ''
  try {
    let rows
    if (file.name.toLowerCase().endsWith('.csv')) {
      rows = parseTemperatureImportCsv(await file.text())
    } else {
      const XLSX = await import('xlsx')
      const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array', cellFormula: true, cellDates: true })
      const sheet = workbook.Sheets[workbook.SheetNames[0]]
      assertTemperatureImportSheet(sheet)
      rows = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: null, blankrows: true })
    }
    if (current !== operation || !props.modelValue || kind !== props.kind) return
    if (well !== props.wellName || (!model.singlePoint && stamp !== topologyStamp.value)) throw new Error(model.singlePoint
      ? '当前井已变化，请重新打开导入窗口。' : '当前管网拓扑已变化，请重新下载模板并导入。')
    const payload = model.singleComposition ? { composition: model.parseRows(rows,props.catalog) } : model.singlePoint ? { point: model.parseRows(rows) } : model.parseRows(rows, props.graph)
    emit('imported', { ...payload, fileName: file.name, kind })
    close()
  } catch (cause) { if (current === operation) error.value = cause?.message || '文件读取失败，请检查文件格式。' }
  finally { if (current === operation) busy.value = false }
}

function keydown(event) { if (event.key === 'Escape' && props.modelValue) close() }
watch(() => [props.modelValue, props.kind], ([visible]) => {
  operation++; busy.value = false; dragging.value = false
  if (visible) { selectedFile.value = null; error.value = ''; window.addEventListener('keydown', keydown) }
  else window.removeEventListener('keydown', keydown)
}, { immediate: true })
onBeforeUnmount(() => { operation++; window.removeEventListener('keydown', keydown) })
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="temperature-import-overlay" @mousedown.self="close">
      <section class="temperature-import-dialog" role="dialog" aria-modal="true" aria-labelledby="temperature-import-title">
        <header class="temperature-import-header"><h2 id="temperature-import-title">本地导入</h2><button type="button" aria-label="关闭导入窗口" @click="close">×</button></header>
        <div class="temperature-import-body">
          <div class="temperature-import-downloads"><button type="button" :disabled="busy" @click="downloadTemplate">数据模板下载</button><span>{{ importModel?.singleComposition ? catalog.length+' 个纯组分' : importModel?.singlePoint ? '1 组温压' : `${graph?.edges?.length || 0} 个管道` }}</span></div>
          <p class="temperature-import-description">{{ importModel?.description }}Excel 文件读取第一个工作表，CSV 请使用 UTF-8 编码。</p>
          <div class="temperature-import-dropzone" :class="{ dragging, selected: selectedFile, busy }" role="button" :tabindex="busy ? -1 : 0" :aria-disabled="busy"
            @click="chooseFile" @keydown.enter.prevent="chooseFile" @keydown.space.prevent="chooseFile"
            @dragenter.prevent="dragging = !busy" @dragover.prevent="dragging = !busy" @dragleave.prevent="dragging = false" @drop.prevent="dropped">
            <input ref="fileInput" type="file" accept=".xls,.xlsx,.csv" :disabled="busy" @change="fileChanged" />
            <el-icon class="temperature-import-icon"><Document /></el-icon>
            <template v-if="selectedFile"><p class="temperature-import-file-name">{{ selectedFile.name }}</p><button type="button" class="temperature-import-clear" :disabled="busy" @click="clearFile">重新选择</button></template>
            <template v-else><p class="temperature-import-prompt">点击或将文件拖拽到这里上传</p><p class="temperature-import-hint">支持扩展名：.xlsx .xls .csv</p></template>
          </div>
          <p v-if="error" class="temperature-import-error" role="alert">{{ error }}</p>
          <p v-else class="temperature-import-note">导入成功后更新当前草稿，点击页面“保存”后存入数据库。</p>
        </div>
        <footer class="temperature-import-footer"><button type="button" class="cancel" @click="close">取消</button><button type="button" class="confirm" :disabled="busy" @click="confirmImport">{{ busy ? '处理中…' : '确定' }}</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style lang="scss" scoped>
.temperature-import-overlay{position:fixed;inset:0;z-index:3000;display:flex;align-items:center;justify-content:center;padding:16px 20px;box-sizing:border-box;background:rgba(0,0,0,.18);font-family:"Microsoft YaHei","Segoe UI",sans-serif}
.temperature-import-dialog{width:min(742px,calc(100vw - 40px));max-height:calc(100vh - 32px);display:flex;flex-direction:column;background:#fff;color:#333;box-shadow:0 2px 7px rgba(0,0,0,.14)}
.temperature-import-header{height:38px;flex:0 0 38px;display:flex;align-items:center;justify-content:space-between;padding:0 11px 0 13px;box-sizing:border-box;background:#353535;color:#fff;h2{margin:0;font-size:15px;line-height:1}button{width:24px;height:24px;padding:0;border:0;background:transparent;color:#fff;font:22px/22px Arial,sans-serif;cursor:pointer}}
.temperature-import-body{min-height:0;padding:12px 24px 8px;box-sizing:border-box;overflow:auto}
.temperature-import-downloads{display:flex;align-items:center;gap:14px;span{font-size:12px;color:#777}button{height:32px;padding:0 12px;border:1px solid #8f8f8f;border-radius:4px;background:#fff;color:#222;font:14px/30px "Microsoft YaHei",sans-serif;cursor:pointer}}
.temperature-import-description,.temperature-import-note{margin:10px 0;font-size:12px;line-height:1.8;color:#777}.temperature-import-note{margin-bottom:0}
.temperature-import-dropzone{min-height:250px;border:1px dashed #dedede;box-sizing:border-box;display:flex;flex-direction:column;align-items:center;justify-content:center;cursor:pointer;&:hover,&:focus-visible,&.dragging{border-color:#2495ef;background:#f8fcff;outline:none}&.busy{cursor:wait;opacity:.7}>input{display:none}}
.temperature-import-icon{width:44px;height:44px;color:#2495ef;font-size:44px}.temperature-import-prompt,.temperature-import-file-name{margin:35px 0 0;color:#666;font-size:17px}.temperature-import-file-name{margin-top:28px;max-width:90%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.temperature-import-hint{margin:15px 0 0;color:#999;font-size:14px}.temperature-import-clear{margin-top:12px;border:0;background:transparent;color:#2495ef;cursor:pointer}.temperature-import-error{margin:10px 0 0;padding:7px 9px;background:#fff2f0;color:#bd3e35;font-size:12px;line-height:1.8;overflow-wrap:anywhere}
.temperature-import-footer{height:60px;flex:0 0 60px;display:flex;align-items:center;justify-content:flex-end;gap:8px;padding:0 10px;border-top:1px solid #888;box-sizing:border-box;button{min-width:72px;height:30px;border-radius:3px;cursor:pointer}.cancel{border:1px solid #9b7278;background:#fff7f7;color:#72222a}.confirm{border:1px solid #3d53e5;background:#3d53e5;color:#fff}}
button:disabled{cursor:not-allowed;opacity:.55}
</style>

