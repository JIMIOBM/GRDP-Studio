<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { templateCreationApi as api } from '@/api/templateCreation'
import PipesimProfileResult from './PipesimProfileResult.vue'
import { creationPayload, experimentInputs, templateFields, terminalCreation } from './templateCreationForm'

const props = defineProps({ projectId: { type: Number, required: true } })
const emit = defineEmits(['registered'])
const visible = ref(false)
const well = ref('')
const study = ref('')
const values = ref(Object.fromEntries(templateFields.map(([key]) => [key, null])))
const job = ref(null)
const requestId = ref('')
const busy = ref(false)
const error = ref('')
const capable = ref(false)
const records = ref([])
const recordsBusy = ref(false)
const recordsError = ref('')
let listRevision = 0
let generation = 0
let readRevision = 0
let timer
async function checkCapabilities() {
  const expected = generation
  capable.value = false
  try {
    const result = envelope(await api.capabilities(props.projectId))
    if (expected !== generation) return
    capable.value = result.template === 'Simple vertical' && result.unitsSystem === 'PIPESIM_FIELD'
    if (!capable.value) error.value = '当前计算服务不支持模板建井，请更新 Worker 后再试'
  } catch (exception) { if (expected === generation) error.value = `建井服务未就绪：${message(exception)}` }
}
const active = computed(() => requestId.value && !terminalCreation(job.value?.state))
const labels = { CLAIMED: '已接收，等待计算服务', PREPARING: '正在真实建井与计算', UNCERTAIN: '状态待确认（不会自动重建）', REJECTED: '执行前已拒绝（未启动建井）', SUCCEEDED: '原生计算成功', FAILED: '建井失败', CANCELLED: '已取消', TIMED_OUT: '计算超时', INTERRUPTED: '计算服务重启，任务中断' }
const storageKey = projectId => `pipesim-template-creation:${projectId}`
const envelope = response => {
  if (response?.code !== 200) throw new Error(response?.msg || '请求未成功')
  return response.data
}
const message = exception => exception?.response?.data?.msg || exception?.msg || exception?.message || '请求失败'

async function loadRecords() {
  const expected = generation
  const revision = ++listRevision
  recordsBusy.value = true; recordsError.value = ''
  try {
    const result = envelope(await api.list(props.projectId))
    if (expected === generation && revision === listRevision) records.value = result
  } catch (exception) {
    if (expected === generation && revision === listRevision) recordsError.value = message(exception)
  } finally {
    if (expected === generation && revision === listRevision) recordsBusy.value = false
  }
}

function selectRecord(id) {
  if (busy.value || !id) return
  readRevision++; clearTimeout(timer)
  requestId.value = id; job.value = null; error.value = ''
  sessionStorage.setItem(storageKey(props.projectId), id)
  refresh()
  // Switching the observed record never creates or cancels another member's task.
}

async function refresh() {
  clearTimeout(timer)
  if (!requestId.value) return
  const expected = generation
  const revision = ++readRevision
  const projectId = props.projectId
  const id = requestId.value
  try {
    const result = envelope(await api.get(projectId, id))
    if (expected !== generation || revision !== readRevision) return
    job.value = result
    error.value = ''
    if (!terminalCreation(result.state)) timer = setTimeout(refresh, 2000)
  } catch (exception) {
    if (expected === generation && revision === readRevision) error.value = `${message(exception)}。请求 ID 已保留，请查询恢复，不要重新提交。`
  }
}

async function create() {
  if (busy.value || active.value || !capable.value) return
  let payload
  try { payload = creationPayload(well.value, study.value, values.value, crypto.randomUUID()) }
  catch (exception) { error.value = exception.message; return }
  const expected = generation
  const projectId = props.projectId
  requestId.value = payload.requestId
  // Store only a non-secret request ID before POST, so a lost response never creates a new ID on refresh.
  sessionStorage.setItem(storageKey(projectId), requestId.value)
  busy.value = true; error.value = ''; job.value = null
  try {
    const result = envelope(await api.create(projectId, payload))
    if (expected !== generation) return
    job.value = result
    await refresh()
  } catch (exception) {
    if (expected === generation) {
      const status = exception?.response?.status
      if ([400, 401, 403, 409].includes(status)) {
        // These creation-route rejections occur before dispatch; allow correcting the form.
        requestId.value = ''; sessionStorage.removeItem(storageKey(projectId))
        error.value = message(exception)
      } else error.value = `${message(exception)}。请先查询该请求，勿重复建井。`
    }
  } finally { if (expected === generation) busy.value = false }
}

async function operate(action) {
  if (busy.value || !requestId.value) return
  const expected = generation
  const projectId = props.projectId
  const id = requestId.value
  readRevision++; clearTimeout(timer)
  busy.value = true; error.value = ''
  try {
    if (action === 'cancel') {
      const result = envelope(await api.cancel(projectId, id))
      if (expected === generation) { job.value = result; await refresh() }
    } else if (action === 'register') {
      const versionId = envelope(await api.register(projectId, id))
      if (expected === generation) { job.value = { ...job.value, versionId }; emit('registered', { projectId, versionId }) }
    } else {
      const blob = await api.download(projectId, id)
      if (!(blob instanceof Blob) || expected !== generation) return
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${job.value.well}.pips`; anchor.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
  } catch (exception) { if (expected === generation) error.value = message(exception) }
  finally { if (expected === generation) busy.value = false }
}

function newTask() {
  if (active.value || busy.value) return
  generation++; clearTimeout(timer)
  requestId.value = ''; job.value = null; error.value = ''
  sessionStorage.removeItem(storageKey(props.projectId))
  recordsBusy.value = false
  if (visible.value) { checkCapabilities(); loadRecords() }
}
watch(() => props.projectId, projectId => {
  generation++; clearTimeout(timer); busy.value = false; job.value = null; error.value = ''
  records.value = []; recordsBusy.value = false; recordsError.value = ''
  requestId.value = sessionStorage.getItem(storageKey(projectId)) || ''
  if (requestId.value) refresh()
  if (visible.value) { checkCapabilities(); loadRecords() }
}, { immediate: true })
watch(visible, open => { if (open) { checkCapabilities(); loadRecords() } })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>

<template>
  <div class="template-entry">
    <el-button plain :disabled="!projectId" @click="visible = true">新建 PIPESIM 模板井</el-button>
    <span v-if="requestId">{{ labels[job?.state] || '请求待查询' }} <el-button link @click="visible = true">查看</el-button></span>
  </div>
  <el-dialog v-model="visible" title="新建模板井 · 团队共享项目" width="min(960px, 95vw)" :close-on-click-modal="false">
    <el-alert type="info" :closable="false" title="官方 Simple vertical 模板：继承井筒几何，显式设置黑油流体及边界。FIELD 为输入单位；结果单位未由接口提供时不推测。不是任意井建模或已校准 PVT。" />
    <div class="template-actions">
      <el-select :model-value="requestId || undefined" aria-label="选择团队建井记录" placeholder="选择团队建井记录" :disabled="busy" @change="selectRecord">
        <el-option v-for="record in records" :key="record.requestId" :value="record.requestId"
          :label="`${record.well} · ${labels[record.state] || record.state} · ${record.requestId}`" />
      </el-select>
      <el-button :loading="recordsBusy" @click="loadRecords">刷新团队记录</el-button>
    </div>
    <p>显示本项目最近50条持久记录；选择只查询状态，不会重新建井或取消原任务。</p>
    <el-alert v-if="recordsError" :title="`团队记录读取失败：${recordsError}`" type="error" :closable="false" />
    <el-form label-position="top" :disabled="Boolean(requestId) || busy">
      <div class="template-fields">
        <el-form-item label="井名"><el-input v-model="well" placeholder="例如 ConsoleWell" maxlength="64" /></el-form-item>
        <el-form-item label="Study 名称"><el-input v-model="study" placeholder="例如 Study 1" maxlength="64" /></el-form-item>
        <el-form-item v-for="[key, label] in templateFields" :key="key" :label="label">
          <el-input-number v-model="values[key]" :controls="false" />
        </el-form-item>
      </div>
      <el-button @click="values = { ...experimentInputs }; study = 'Study 1'">填入演示实验参数（非官方原始数据）</el-button>
    </el-form>
    <p>采用 SDK 默认相关式与模板继承设置；演示参数不用于工程决策。关闭窗口不会取消后台计算。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="requestId">请求 ID：{{ requestId }}<br />{{ labels[job?.state] || '请求待查询' }}{{ job?.cancellationRequested ? ' · 已请求取消，等待进程退出' : '' }}{{ job?.errorCode ? ` · ${job.errorCode}` : '' }}</p>
    <p v-if="job?.cancellationRequested">取消意图已保存；刷新、换成员或服务重启后，查询会继续跟进取消。只有计算服务确认真实终态后才解除任务保护。</p>
    <div class="template-actions">
      <el-button type="primary" :loading="busy" :disabled="Boolean(requestId) || !capable" @click="create">创建并真实计算</el-button>
      <el-button v-if="!capable" :disabled="busy" @click="checkCapabilities">检查建井服务</el-button>
      <el-button :disabled="!requestId || busy" @click="refresh">查询 / 恢复状态</el-button>
      <el-button :disabled="!active || busy" @click="operate('cancel')">取消任务</el-button>
      <el-button :disabled="job?.state !== 'SUCCEEDED' || busy" @click="operate('download')">下载 .pips</el-button>
      <el-button :disabled="job?.state !== 'SUCCEEDED' || busy || Boolean(job?.versionId)" @click="operate('register')">保存到项目并验证</el-button>
      <el-button :disabled="active || busy" @click="newTask">开始另一口井</el-button>
    </div>
    <p v-if="job?.versionId">已登记模型版本 #{{ job.versionId }}。请在项目目录等待 READY，再进入 PT 剖面计算；原生成功不等于平台复算验收完成。</p>
    <PipesimProfileResult v-if="job?.state === 'SUCCEEDED' && job.profile?.length" :result="{ profile: job.profile }" source-label="建井原生 Profile" />
  </el-dialog>
</template>

<style scoped>
.template-entry, .template-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.template-entry { padding: 8px 20px; }
.template-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 16px; margin-top: 16px; }
.template-fields .el-input-number { width: 100%; }
@media (max-width: 700px) { .template-fields { grid-template-columns: 1fr; } }
</style>
