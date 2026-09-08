<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import NaturalGasImportDialog from '@/views/DataManagement/NaturalGasImportDialog.vue'
import { geologicalLossApi } from '@/api/geologicalLoss'
import { workspaceTreeData } from '@/utils/workspaceTreeState'
import { upsertReservoirLossRecordNode } from '@/utils/reservoirGeologicalLossTree'

const props = defineProps({ reservoir: { type: Object, default: null }, command: { type: Object, default: null } })
const route = useRoute()
const router = useRouter()
const isMicroscopic = computed(() => props.command?.name === '微观损耗')
const title = computed(() => isMicroscopic.value ? '微观损耗' : '逸散性损耗')
const projectId = computed(() => Number(props.reservoir?.projectId ?? route.query.projectId))
const gasReservoirId = computed(() => Number(props.reservoir?.gasReservoirId ?? route.query.gasReservoirId))
const recordId = computed(() => route.query.lossRecordId ? Number(route.query.lossRecordId) : null)
const importDialogVisible = ref(false)
const calculating = ref(false)
const saving = ref(false)
const importedFileName = ref('')
const microscopicCalculation = ref(null)
const escapeCalculation = ref(null)

const microscopicForm = reactive({
  poreVolume: '', previousResidualSaturation: '', currentResidualSaturation: '', lowerLimitPressure: '',
  formationTemperature: '', gasType: 0, specificGravity: '', h2SMoleFraction: '', co2MoleFraction: '',
  n2MoleFraction: '', modificationMethod: 0, deviationFactorMethod: 0, viscosityMethod: 0
})
const escapeForm = reactive({ previousCushionGasVolume: '', movableCushionGasVolume: '', unusedInventoryVolume: '', injectionVolume: '', predictedChangeRate: '' })
const outputValue = computed(() => isMicroscopic.value ? microscopicCalculation.value?.microscopicLossVolume : escapeCalculation.value?.escapeLossVolume)
const formatResult = value => Number.isFinite(Number(value)) ? Number(value).toFixed(4) : ''
const numericObject = source => Object.fromEntries(Object.entries(source).map(([key, value]) => [key, Number(value)]))
const responseData = response => response?.data ?? response

const resetMicroscopic = () => {
  Object.assign(microscopicForm, { poreVolume: '', previousResidualSaturation: '', currentResidualSaturation: '', lowerLimitPressure: '', formationTemperature: '', gasType: 0, specificGravity: '', h2SMoleFraction: '', co2MoleFraction: '', n2MoleFraction: '', modificationMethod: 0, deviationFactorMethod: 0, viscosityMethod: 0 })
  importedFileName.value = ''
  microscopicCalculation.value = null
}
const resetEscape = () => { Object.keys(escapeForm).forEach(key => { escapeForm[key] = '' }); escapeCalculation.value = null }
// “重置”仅撤销本次计算结果，保留用户已经填写或导入的全部参数。
const resetCalculationResult = () => {
  if (isMicroscopic.value) microscopicCalculation.value = null
  else escapeCalculation.value = null
}
const microscopicInput = () => ({ ...numericObject(microscopicForm), importedFileName: importedFileName.value || null })
const validateNumbers = values => Object.values(values).every(value => value === null || Number.isFinite(value))

const calculate = async () => {
  const rawForm = isMicroscopic.value ? microscopicForm : escapeForm
  if (Object.values(rawForm).some(value => value === '' || value === null || value === undefined)) {
    ElMessage.warning('请完整填写计算参数')
    return
  }
  const input = isMicroscopic.value ? microscopicInput() : numericObject(escapeForm)
  // importedFileName 只是导入来源快照，不参与公式，不能按数值参数校验。
  const calculationValues = Object.fromEntries(
    Object.entries(input).filter(([key]) => key !== 'importedFileName')
  )
  if (!validateNumbers(calculationValues)) { ElMessage.warning('请完整填写计算参数'); return }
  calculating.value = true
  try {
    if (isMicroscopic.value) {
      const response = await geologicalLossApi.calculateMicroscopic({ projectId: projectId.value, gasReservoirId: gasReservoirId.value, input })
      microscopicCalculation.value = responseData(response)
    } else {
      const response = await geologicalLossApi.calculateEscape({ projectId: projectId.value, gasReservoirId: gasReservoirId.value, input })
      escapeCalculation.value = responseData(response)
    }
    ElMessage.success('计算完成')
  } finally { calculating.value = false }
}

const save = async () => {
  const calculation = isMicroscopic.value ? microscopicCalculation.value : escapeCalculation.value
  if (!calculation) { ElMessage.warning('请先完成计算再保存'); return }
  saving.value = true
  try {
    // 记录直接归属于当前项目和储气库，不再创建额外的“库1”中间层。
    const payload = { recordId: recordId.value, projectId: projectId.value, gasReservoirId: gasReservoirId.value, input: isMicroscopic.value ? microscopicInput() : numericObject(escapeForm), calculation }
    const response = isMicroscopic.value ? await geologicalLossApi.saveMicroscopic(payload) : await geologicalLossApi.saveEscape(payload)
    const saved = responseData(response)
    upsertReservoirLossRecordNode({ treeData: workspaceTreeData, projectId: projectId.value,
      gasReservoirId: gasReservoirId.value, lossType: isMicroscopic.value ? 'microscopic' : 'escape', record: saved })
    const query = { ...route.query, lossRecordId: saved.id }
    delete query.lossLibraryId
    await router.replace({ query })
    ElMessage.success(recordId.value ? '修改已保存' : `${saved.recordName}已保存`)
  } finally { saving.value = false }
}
const loadDetail = async () => {
  if (!recordId.value) return
  const response = isMicroscopic.value ? await geologicalLossApi.getMicroscopic(recordId.value, projectId.value, gasReservoirId.value) : await geologicalLossApi.getEscape(recordId.value, projectId.value, gasReservoirId.value)
  const detail = responseData(response)
  if (isMicroscopic.value) { Object.assign(microscopicForm, detail.input); importedFileName.value = detail.input.importedFileName || ''; microscopicCalculation.value = detail.calculation }
  else { Object.assign(escapeForm, detail.input); escapeCalculation.value = detail.calculation }
}

const handleGasImport = async ({ file, options }) => {
  try {
    const extension = file.name.split('.').pop()?.toLowerCase()
    if (!['xlsx', 'xls', 'csv'].includes(extension)) throw new Error('仅支持 .xlsx、.xls、.csv 表格文件')
    const XLSX = await import('xlsx')
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' })
    const sheet = workbook.Sheets[workbook.SheetNames[0]]
    let rows = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: '' })
    if (options?.removeEmptyRows) rows = rows.filter(row => row.some(value => String(value).trim()))
    const row = rows.slice(1).find(item => item.some(value => String(value).trim()))
    if (!row) throw new Error('文件中没有可导入的天然气基础数据')
    const gasTypes = { 干气: 0, 湿气: 1, 凝析气: 2 }
    if (!(String(row[0]).trim() in gasTypes)) throw new Error('天然气类型只能为干气、湿气或凝析气')
    const numbers = row.slice(1, 5).map(Number)
    if (!validateNumbers(numbers)) throw new Error('天然气比重及气体组分必须是有效数字')
    Object.assign(microscopicForm, { gasType: gasTypes[String(row[0]).trim()], specificGravity: numbers[0], h2SMoleFraction: numbers[1], co2MoleFraction: numbers[2], n2MoleFraction: numbers[3] })
    importedFileName.value = file.name
    microscopicCalculation.value = null
    ElMessage.success('PVT基础数据导入成功')
  } catch (error) { ElMessage.error(error?.message || 'PVT数据导入失败') }
}

watch(() => [props.command?.name, recordId.value], async () => { isMicroscopic.value ? resetMicroscopic() : resetEscape(); await loadDetail() })
watch(microscopicForm, () => { microscopicCalculation.value = null }, { deep: true })
watch(escapeForm, () => { escapeCalculation.value = null }, { deep: true })
onMounted(loadDetail)
</script>

<template>
  <section class="geological-loss">
    <header class="result-tabs">
      <div class="result-tab">{{ title }}计算结果</div>
      <div class="header-actions">
        <button v-if="isMicroscopic" class="secondary" type="button" @click="importDialogVisible = true">导入PVT</button>
        <button class="primary" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </header>
    <main class="form-canvas">
      <div class="form-title">请输入计算参数</div>
      <div class="parameter-grid">
        <template v-if="isMicroscopic">
          <!-- 业务界面只展示中文名称和单位，公式符号保留在后端计算及设计文档中。 -->
          <label class="field"><span>气水过渡带孔隙体积（10⁴m³）</span><input v-model="microscopicForm.poreVolume" placeholder="请输入" /></label>
          <label class="field"><span>上周期残余气饱和度（%）</span><input v-model="microscopicForm.previousResidualSaturation" placeholder="请输入" /></label>
          <label class="field"><span>本周期残余气饱和度（%）</span><input v-model="microscopicForm.currentResidualSaturation" placeholder="请输入" /></label>
          <label class="field"><span>下限压力（MPa）</span><input v-model="microscopicForm.lowerLimitPressure" placeholder="请输入" /></label>
          <label class="field"><span>温度（℃）</span><input v-model="microscopicForm.formationTemperature" placeholder="请输入" /></label>
          <label class="field"><span>天然气类型</span><select v-model="microscopicForm.gasType"><option :value="0">干气</option><option :value="1">湿气</option><option :value="2">凝析气</option></select></label>
          <label class="field"><span>天然气比重（dless）</span><input v-model="microscopicForm.specificGravity" placeholder="请输入" /></label>
          <label class="field"><span>H₂S摩尔百分含量（%）</span><input v-model="microscopicForm.h2SMoleFraction" placeholder="请输入" /></label>
          <label class="field"><span>CO₂摩尔百分含量（%）</span><input v-model="microscopicForm.co2MoleFraction" placeholder="请输入" /></label>
          <label class="field"><span>N₂摩尔百分含量（%）</span><input v-model="microscopicForm.n2MoleFraction" placeholder="请输入" /></label>
          <label class="field"><span>非烃气体修正方法</span><select v-model="microscopicForm.modificationMethod"><option :value="0">Wichert-Aziz 修正方法</option><option :value="1">Carr-Kobayashi-Burrows 方法</option></select></label>
          <label class="field"><span>天然气偏差系数计算方法</span><select v-model="microscopicForm.deviationFactorMethod"><option :value="0">Dranchuk-Abu-Kassem 方法</option><option :value="1">Dranchuk-Purvis-Robinson 方法</option><option :value="2">Hall-Yarborough 方法</option></select></label>
          <label class="field"><span>天然气黏度计算方法</span><select v-model="microscopicForm.viscosityMethod"><option :value="0">Lee-Gonzalez-Eakin 方法</option><option :value="1">Carr-Kobayashi-Burrows 方法</option><option :value="2">Sutton 方法</option></select></label>
        </template>
        <template v-else>
          <label class="field"><span>上一周期储气库垫气量（10⁴m³）</span><input v-model="escapeForm.previousCushionGasVolume" placeholder="请输入" /></label>
          <label class="field"><span>本周期储气库可动垫气量（10⁴m³）</span><input v-model="escapeForm.movableCushionGasVolume" placeholder="请输入" /></label>
          <label class="field"><span>本周期储气库未动用库存量（10⁴m³）</span><input v-model="escapeForm.unusedInventoryVolume" placeholder="请输入" /></label>
          <label class="field"><span>储气库注气量（10⁴m³）</span><input v-model="escapeForm.injectionVolume" placeholder="请输入" /></label>
          <label class="field"><span>本周期预测垫气变化率（%）</span><input v-model="escapeForm.predictedChangeRate" placeholder="请输入" /></label>
        </template>
      </div>
      <div v-if="isMicroscopic && importedFileName" class="import-note">已导入：{{ importedFileName }}</div>
      <div class="calculation-actions"><button class="calculate" type="button" :disabled="calculating" @click="calculate">{{ calculating ? '计算中…' : '计 算' }}</button><button class="reset" type="button" @click="resetCalculationResult">重 置</button></div>
      <section class="result-card"><h3>计算结果</h3><div class="result-line"><span>{{ isMicroscopic ? '微观损耗气量（10⁴m³）' : '逸散性损耗气量（10⁴m³）' }}：</span><strong>{{ formatResult(outputValue) || '—' }}</strong></div></section>
    </main>
    <NaturalGasImportDialog v-model="importDialogVisible" import-kind="data" @confirm="handleGasImport" />
  </section>
</template>

<style scoped>
/* 与单井产能工作台使用同一字体基线，表单、按钮和结果区均通过继承保持一致。 */
.geological-loss { height: 100%; min-width: 760px; overflow: auto; background: #fff; color: #202020; font-family: Arial, sans-serif; font-size: 13px; }
.result-tabs { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; height: 34px; padding: 0 8px 0 0; border-bottom: 1px solid #e4e7ed; background: #fafafa; box-sizing: border-box; }
.result-tab { display: flex; align-self: stretch; align-items: center; justify-content: center; max-width: 430px; min-width: 190px; padding: 0 12px; overflow: hidden; border: 0; border-right: 1px solid #e4e7ed; background: #f4d000; color: #202020; font: 600 13px Arial, sans-serif; text-align: center; text-overflow: ellipsis; white-space: nowrap; box-sizing: border-box; }
.header-actions, .calculation-actions { display: flex; gap: 10px; }
button { height: 27px; padding: 0 16px; border: 1px solid #c9cdd3; border-radius: 4px; background: #fff; color: #292929; font: inherit; cursor: pointer; }
button:hover { border-color: #b49a00; }
button:disabled { cursor: wait; opacity: .65; }
button.primary { min-width: 74px; border-color: #202020; background: #202020; color: #fff; }
.form-canvas { padding: 20px 18px 34px; }
.form-title { margin-bottom: 20px; font-size: 13px; font-weight: 600; }
.parameter-grid { display: grid; grid-template-columns: repeat(4, minmax(170px, 1fr)); gap: 20px 24px; }
.field { display: grid; gap: 8px; min-width: 0; color: #333; }
.field input, .field select { width: 100%; height: 34px; padding: 0 11px; border: 1px solid #d4d7dc; border-radius: 4px; background: #fff; box-sizing: border-box; color: #303133; font: inherit; outline: none; }
.field input:focus, .field select:focus { border-color: #b49a00; box-shadow: 0 0 0 2px rgba(244,208,0,.14); }
.import-note { margin-top: 14px; color: #777; font-size: 12px; }
.calculation-actions { margin-top: 24px; }
.calculation-actions button { height: 32px; }
.calculate { min-width: 72px; border-color: #d5b900; background: #f4d000; }
.reset { min-width: 72px; }
.result-card { min-height: 142px; margin-top: 36px; padding: 18px 18px 26px; border: 1px solid #ececec; border-radius: 4px; background: #f5f5f5; box-sizing: border-box; }
.result-card h3 { margin: 0 0 24px; font-size: 13px; }
.result-line { display: flex; align-items: baseline; gap: 6px; color: #333; }
.result-line strong { font-size: 15px; font-weight: 500; }
@media (max-width: 1250px) { .parameter-grid { grid-template-columns: repeat(3, minmax(170px, 1fr)); } }
@media (max-width: 920px) { .parameter-grid { grid-template-columns: repeat(2, minmax(170px, 1fr)); } }
</style>
