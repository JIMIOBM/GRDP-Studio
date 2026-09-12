<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute } from 'vue-router'
import { CircleCheck, Document, DocumentAdd, Folder, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'
import EclipseDataInspectionOverview from './EclipseDataInspectionOverview.vue'
import PipesimModelRunPage from './PipesimModelRunPage.vue'

const store = useSoftwareIntegrationStore()
const route = useRoute()
const {
  projects,
  projectDetails,
  activeProject,
  activeProjectDetail,
  activeProjectId,
  activeModel,
  activeVersion,
  loadingProjects
} = storeToRefs(store)
const creating = ref(false)
const projectName = ref('')
const projectDescription = ref('')
const fileInput = ref()
const uploading = ref(false)
const createDialogVisible = ref(false)
const treeKeyword = ref('')
const treeCollapsed = ref(false)
const activeTreeId = ref('')
const selectedModelId = ref('')
const selectedTreeProjectId = ref(null)
const pendingExternalImport = ref(null)
let workspaceMounted = false
const safeRequestMessage = fallback => fallback

const importIntents = {
  'import-pipesim-well': {
    action: '导入 PIPESIM 井筒模型',
    guidance: '.pips 文件可在当前版本验证并运行；ZIP 可上传保存，但当前版本不能验证或运行。',
    accept: '.pips,.PIPS,.zip,.ZIP'
  },
  'import-pipesim-network': {
    action: '导入 PIPESIM 管网模型',
    guidance: '.pips 文件可在当前版本验证并运行；ZIP 可上传保存，但当前版本不能验证或运行。',
    accept: '.pips,.PIPS,.zip,.ZIP'
  },
  'import-eclipse-100': {
    action: '导入 ECLIPSE 100 DATA 文件',
    guidance: '仅选择一个 .DATA 文件。ECLIPSE MVP 不支持 INCLUDE 指令或 ZIP 依赖包，Worker 将验证文件。',
    accept: '.data,.DATA'
  }
}
const importIntent = computed(() => importIntents[route.query.intent] || null)
const importActionLabel = computed(() => importIntent.value?.action || '导入模型')
const importGuidance = computed(() => importIntent.value?.guidance || 'PIPESIM .pips 可验证并运行；ZIP 可上传保存，但当前版本不能验证或运行。ECLIPSE 仅支持单个 .DATA 文件。')
const fileAccept = computed(() => importIntent.value?.accept || '.pips,.PIPS,.data,.DATA,.zip,.ZIP')

const eclipseInspectionSchemas = new Set(['eclipse-data-inspection/1', 'eclipse-data-inspection/2'])
const eclipseSectionOrder = ['RUNSPEC', 'GRID', 'EDIT', 'PROPS', 'REGIONS', 'SOLUTION', 'SUMMARY', 'SCHEDULE']
const eclipsePhaseOrder = ['OIL', 'WATER', 'GAS']
const eclipseUnitSystems = new Set(['METRIC', 'FIELD', 'LAB', 'PVT-M'])
const hasOrderedValues = (value, allowed) => Array.isArray(value) && value.every((item, index) =>
  typeof item === 'string' && allowed.includes(item) && (index === 0 || allowed.indexOf(value[index - 1]) < allowed.indexOf(item)))
const isSafeEclipseInspection = value => {
  if (!value || typeof value !== 'object' || !eclipseInspectionSchemas.has(value.schemaVersion)) return false
  const expectedFields = value.schemaVersion === 'eclipse-data-inspection/2'
    ? ['schemaVersion', 'caseName', 'sections', 'unitSystem', 'phases', 'dimensions', 'wellNames', 'scheduleTimeline']
    : ['schemaVersion', 'caseName', 'sections', 'unitSystem', 'phases', 'dimensions']
  if (Object.keys(value).length !== expectedFields.length || !expectedFields.every(key => Object.hasOwn(value, key)) ||
    typeof value.caseName !== 'string' || !value.caseName || value.caseName.length > 255 || /[\\/]/.test(value.caseName) ||
    !hasOrderedValues(value.sections, eclipseSectionOrder) || !hasOrderedValues(value.phases, eclipsePhaseOrder) ||
    !(value.unitSystem === null || eclipseUnitSystems.has(value.unitSystem))) return false
  if (value.dimensions !== null && (!value.dimensions || typeof value.dimensions !== 'object' || Object.keys(value.dimensions).length !== 3 ||
    !['nx', 'ny', 'nz'].every(key => Number.isInteger(value.dimensions[key]) && value.dimensions[key] > 0 && value.dimensions[key] <= 1000000))) return false
  if (value.schemaVersion === 'eclipse-data-inspection/1') return true
  if (!Array.isArray(value.wellNames) || value.wellNames.length > 1000 || !Array.isArray(value.scheduleTimeline) || value.scheduleTimeline.length > 1000 ||
    !value.wellNames.every(name => typeof name === 'string' && name.length > 0 && name.length <= 1024 && !/[\\/:]/.test(name) && !name.includes('..') && !/[\u0000-\u001f\u007f]/.test(name))) return false
  let dateCount = 0
  let tstepCount = 0
  return value.scheduleTimeline.every(event => {
    if (!event || typeof event !== 'object') return false
    if (event.kind === 'DATES' && Array.isArray(event.records)) {
      if (Object.keys(event).length !== 2 || (dateCount += event.records.length) > 4000) return false
      return event.records.every(record => record && typeof record === 'object' && Object.keys(record).length === 4 &&
        typeof record.day === 'string' && /^(?:[1-9]|[12][0-9]|3[01])$/.test(record.day) &&
        typeof record.month === 'string' && /^(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)$/.test(record.month) &&
        typeof record.year === 'string' && /^\d{4}$/.test(record.year) &&
        (record.time == null || (typeof record.time === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(record.time))))
    }
    if (event.kind === 'TSTEP' && Array.isArray(event.steps)) {
      if (Object.keys(event).length !== 2 || (tstepCount += event.steps.length) > 8000) return false
      return event.steps.every(step => typeof step === 'string' && /^\+?\d+(?:\.\d+)?(?:[Ee][+-]?\d+)?$/.test(step) && Number.isFinite(Number(step)) && Number(step) >= 0)
    }
    return false
  })
}
const eclipseRunAvailable = computed(() => activeVersion.value?.status === 'READY' && activeVersion.value?.modelKind === 'eclipse_100' &&
  isSafeEclipseInspection(activeVersion.value?.inspection))

const activeModels = computed(() => activeProjectDetail.value?.models || [])
const latestVersion = model => [...(model?.versions || [])]
  .sort((left, right) => Number(right.versionNo || 0) - Number(left.versionNo || 0))[0]
const readyVersion = model => [...(model?.versions || [])]
  .sort((left, right) => Number(right.versionNo || 0) - Number(left.versionNo || 0))
  .find(version => version.status === 'READY')
const readyVersions = computed(() => activeModels.value.map(readyVersion).filter(Boolean))
const readyModelCount = computed(() => readyVersions.value.length)
const historicalReadyModelCount = computed(() => activeModels.value.filter(model => {
  const ready = readyVersion(model)
  return ready && ready.id !== latestVersion(model)?.id
}).length)
const latestVersions = computed(() => activeModels.value.map(latestVersion).filter(Boolean))
const pendingValidationCount = computed(() => latestVersions.value.filter(version =>
  version.status === 'UPLOADED' || version.status === 'VALIDATING').length)
const workflowNextStep = computed(() => {
  if (!activeProject.value) return 0
  if (!activeModels.value.length) return 1
  if (!readyModelCount.value) return 2
  return 3
})
const workflowSteps = computed(() => [
  { label: '项目', detail: activeProject.value ? '已选择项目' : '新建或选择项目' },
  { label: '导入', detail: activeModels.value.length ? `已导入 ${activeModels.value.length} 个模型` : '上传 .pips 或 .DATA' },
  {
    label: '验证',
    detail: readyModelCount.value ? `${readyModelCount.value} 个模型有 READY 版本可计算${historicalReadyModelCount.value ? `（含 ${historicalReadyModelCount.value} 个历史 READY 版本）` : ''}` :
      (pendingValidationCount.value ? `${pendingValidationCount.value} 个版本等待验证` : '等待可计算版本')
  },
  { label: '计算', detail: readyModelCount.value ? '选择模型后进入计算' : '验证通过后可用' },
  { label: '结果', detail: '在模型页查看真实结果' }
])
const wellModelKinds = new Set(['black_oil_liquid', 'basic_gas', 'legacy_well'])
const latestKnownModelKind = model => model?.versions?.find(version => version.status === 'READY' && version.modelKind)?.modelKind ||
  model?.versions?.find(version => version.modelKind)?.modelKind || ''
const modelFamily = model => {
  const kind = latestKnownModelKind(model)
  if (kind === 'network') return 'network'
  if (kind === 'eclipse_100' || model?.versions?.some(version => /\.data$/i.test(version.originalName || ''))) return 'eclipse'
  return wellModelKinds.has(kind) ? 'well' : 'unknown'
}
const simulatorTypeLabel = model => {
  const family = modelFamily(model)
  if (family === 'network') return 'PIPESIM 管网模型'
  if (family === 'eclipse') return 'ECLIPSE 100 模型'
  return family === 'well' ? 'PIPESIM 井筒模型' : '待识别模拟模型'
}
const resourceTree = computed(() => projects.value
  .map(project => projectDetails.value[project.id] || { project, models: [] })
  .filter(detail => detail.project.name.toLowerCase().includes(treeKeyword.value.trim().toLowerCase()) ||
    (detail.models || []).some(model => model.name.toLowerCase().includes(treeKeyword.value.trim().toLowerCase())))
  .map(detail => {
    const models = detail.models || []
    const categories = [
      { key: 'well-models', label: '井筒模型', models: models.filter(model => modelFamily(model) === 'well') },
      { key: 'network-models', label: '管网模型', models: models.filter(model => modelFamily(model) === 'network') },
      { key: 'eclipse-models', label: 'ECLIPSE 模型', models: models.filter(model => modelFamily(model) === 'eclipse') },
      { key: 'pending-models', label: '待识别模型', models: models.filter(model => modelFamily(model) === 'unknown') }
    ].filter(category => category.models.length)
    return {
      id: `project-${detail.project.id}`,
      label: detail.project.name,
      type: 'project',
      projectId: detail.project.id,
      defaultExpanded: true,
      children: categories.map(category => ({
        id: `${category.key}-${detail.project.id}`,
        label: `${category.label}（${category.models.length}）`,
        type: 'model-category',
        defaultExpanded: true,
        children: category.models.map(model => ({
          id: `model-${model.id}`,
          label: model.name,
          type: 'model',
          projectId: detail.project.id,
          modelId: model.id,
          activatable: true
        }))
      }))
    }
  }))
const defaultExpandedTreeIds = computed(() => resourceTree.value.flatMap(project => [project.id, ...project.children.map(category => category.id)]))

const loadProjects = async () => {
  try {
    await store.loadProjects()
    if (activeProjectId.value) {
      activeTreeId.value = `project-${activeProjectId.value}`
      selectedTreeProjectId.value = activeProjectId.value
    }
    if (workspaceMounted && store.activeModelId && store.activeVersionId && !store.isEclipseModel) {
      await store.loadRunHistory(store.activeVersionId)
    }
  } catch (error) {
    ElMessage.error(safeRequestMessage('软件项目加载失败，请稍后重试'))
  }
}

const selectResource = async (node) => {
  activeTreeId.value = node.id
  if (node.type === 'project') {
    selectedModelId.value = ''
    selectedTreeProjectId.value = node.projectId
    const detail = await store.selectProject(node.projectId)
    await flushPendingExternalImport()
    return detail
  }
  if (node.type === 'model') {
    selectedTreeProjectId.value = node.projectId
    selectedModelId.value = node.modelId
  }
}

const activateResource = async node => {
  if (node.type !== 'model') return
  activeTreeId.value = node.id
  selectedModelId.value = node.modelId
  selectedTreeProjectId.value = node.projectId
  try {
    await store.activateModel(node.projectId, node.modelId)
  } catch (error) {
    ElMessage.error(safeRequestMessage('模型页面加载失败，请稍后重试'))
  }
}

const createProject = async () => {
  if (!projectName.value.trim()) return ElMessage.warning('请输入软件项目名称')
  creating.value = true
  try {
    const project = await store.createProject({ name: projectName.value, description: projectDescription.value })
    projectName.value = ''; projectDescription.value = ''
    createDialogVisible.value = false
    if (activeProjectId.value === project.id) {
      activeTreeId.value = `project-${project.id}`
      selectedTreeProjectId.value = project.id
    }
    await flushPendingExternalImport()
    ElMessage.success('软件项目已创建')
  } catch (error) {
    ElMessage.error(safeRequestMessage('软件项目创建失败，请稍后重试'))
  } finally { creating.value = false }
}

const removeProject = async () => {
  if (!activeProject.value) return
  await ElMessageBox.confirm(`项目“${activeProject.value.name}”将进入30天回收站，是否继续？`, '删除软件项目', { type: 'warning' })
  try {
    await store.deleteProject(activeProject.value.id)
    activeTreeId.value = activeProjectId.value ? `project-${activeProjectId.value}` : ''
    selectedTreeProjectId.value = activeProjectId.value
    selectedModelId.value = ''
    ElMessage.success('项目已移入回收站')
  } catch (error) {
    ElMessage.error(safeRequestMessage('软件项目删除失败，请稍后重试'))
  }
}

const chooseModel = () => fileInput.value?.click()
const openCreateDialog = () => {
  projectName.value = ''
  projectDescription.value = ''
  createDialogVisible.value = true
}
const openImportModel = async () => {
  const projectId = selectedTreeProjectId.value || activeProject.value?.id
  if (!projectId) return ElMessage.warning('请先创建或在资源树中选择一个软件项目')
  try {
    if (activeProjectId.value !== projectId) {
      await store.selectProject(projectId)
      if (activeProjectId.value !== projectId) return
    }
    chooseModel()
  } catch (error) {
    ElMessage.error(safeRequestMessage('软件项目加载失败，请稍后重试'))
  }
}
const revalidateModel = async (versionId) => {
  try {
    await store.revalidateModel(activeProject.value.id, versionId)
  } catch (error) {
    ElMessage.error(safeRequestMessage('重新验证请求失败，请稍后重试'))
  }
}
const uploadFile = async (file, intent = route.query.intent) => {
  if (!file) return false
  const accepted = intent === 'import-eclipse-100' ? /\.data$/i : /\.(pips|zip)$/i
  const acceptedLabel = intent === 'import-eclipse-100' ? '.DATA' : '.pips 或 ZIP'
  if (!accepted.test(file.name)) {
    ElMessage.error(`当前入口仅支持 ${acceptedLabel} 文件`)
    return false
  }
  if (file.size > 500 * 1024 * 1024) {
    ElMessage.error('模型文件不能超过500MB')
    return false
  }
  if (!activeProject.value) {
    ElMessage.warning('请先创建或选择软件项目，再导入模型')
    return false
  }
  uploading.value = true
  const projectId = activeProject.value.id
  try {
    const detail = await store.uploadModel(projectId, file)
    if (activeProjectId.value === projectId) activeTreeId.value = `project-${detail.project.id}`
    ElMessage.success('模型已保存，等待 Worker 异步验证')
  } catch (error) {
    ElMessage.error(safeRequestMessage('模型上传失败，请稍后重试'))
    return false
  } finally {
    uploading.value = false
  }
  return true
}
const uploadModel = async (event) => {
  const [file] = event.target.files || []
  event.target.value = ''
  await uploadFile(file)
}
const importExternalFile = async (file, intent) => {
  for (let attempt = 0; attempt < 20 && loadingProjects.value; attempt += 1) {
    await new Promise(resolve => window.setTimeout(resolve, 100))
  }
  pendingExternalImport.value = { file, intent }
  if (!activeProject.value) {
    ElMessage.warning('文件已暂存，请先创建或选择软件项目')
    return true
  }
  const result = await uploadFile(file, intent)
  if (pendingExternalImport.value?.file === file) pendingExternalImport.value = null
  return result
}
const flushPendingExternalImport = async () => {
  const pending = pendingExternalImport.value
  if (!pending || !activeProject.value) return false
  const result = await uploadFile(pending.file, pending.intent)
  if (pendingExternalImport.value === pending) pendingExternalImport.value = null
  return result
}

onMounted(() => {
  workspaceMounted = true
  loadProjects()
})
onBeforeUnmount(() => {
  workspaceMounted = false
  store.cleanup()
})
defineExpose({ openCreateDialog, openImportModel, importExternalFile })
</script>

<template>
  <section v-loading="loadingProjects" class="software-integration-workspace">
    <aside class="software-resource-panel" :class="{ collapsed: treeCollapsed }">
      <button v-if="treeCollapsed" class="collapsed-tab" type="button" title="展开目录" @click="treeCollapsed = false">目录</button>
      <template v-else>
        <div class="tree-search">
          <el-input v-model="treeKeyword" size="small" clearable placeholder="搜索项目或模型" />
          <button class="tree-toggle" type="button" title="收起目录" @click="treeCollapsed = true">◀</button>
        </div>
        <div v-loading="loadingProjects" class="resource-tree-wrap">
          <el-tree
            :data="resourceTree"
            node-key="id"
            :current-node-key="activeTreeId"
            :default-expanded-keys="defaultExpandedTreeIds"
            :expand-on-click-node="false"
            highlight-current
            @node-click="selectResource"
          >
            <template #default="{ data }">
              <span class="software-tree-node" :title="data.type === 'model' ? '双击进入计算' : ''" @dblclick.stop="activateResource(data)">
                <el-icon><Document v-if="data.type === 'model'" /><Folder v-else /></el-icon>
                <span>{{ data.label }}</span>
                <button
                  v-if="data.type === 'model' && data.modelId === selectedModelId"
                  class="tree-model-action"
                  type="button"
                  @click.stop="activateResource(data)"
                >进入计算</button>
              </span>
            </template>
          </el-tree>
          <div v-if="!loadingProjects && !resourceTree.length" class="tree-empty">暂无项目资源</div>
        </div>
      </template>
    </aside>
    <main class="software-content">
    <template v-if="activeModel && store.isEclipseModel">
      <EclipseDataInspectionOverview />
      <PipesimModelRunPage v-if="eclipseRunAvailable" />
    </template>
    <PipesimModelRunPage v-else-if="activeModel" />
     <template v-else>
     <header class="workspace-header">
       <div>
         <h1>{{ activeProject?.name || '项目概览' }}</h1>
         <p class="description">模型版本、验证状态、计算与结果在同一工作台追踪。</p>
      </div>
      <div class="header-actions">
        <el-button :disabled="!activeProject" type="danger" plain @click="removeProject">删除项目</el-button>
      </div>
     </header>

     <section class="workflow-guide" aria-label="软件集成工作流程">
       <div class="workflow-guide-title">
         <strong>工作流程</strong>
         <span>下一步：{{ workflowSteps[workflowNextStep].detail }}</span>
       </div>
       <ol class="workflow-steps">
         <li v-for="(step, index) in workflowSteps" :key="step.label" :class="{ complete: index < workflowNextStep, current: index === workflowNextStep }">
           <span class="workflow-index"><el-icon v-if="index < workflowNextStep"><CircleCheck /></el-icon><template v-else>{{ index + 1 }}</template></span>
           <span><b>{{ step.label }}</b><small>{{ step.detail }}</small></span>
         </li>
       </ol>
     </section>

      <div v-if="!activeProject" class="empty-state">
        <el-icon><Folder /></el-icon>
        <p>从软件项目开始</p>
        <span>{{ importIntent ? `当前导入请求将保留；请先创建项目，再${importActionLabel}。` : '创建独立项目，或在左侧资源树选择已有项目。' }}</span>
        <el-button type="primary" @click="openCreateDialog">创建软件项目</el-button>
    </div>

    <template v-else>
        <div class="workspace-toolbar">
          <div><strong>模型资源</strong><span>{{ importGuidance }}</span></div>
         <input ref="fileInput" :accept="fileAccept" class="hidden-input" type="file" @change="uploadModel" />
         <el-button :loading="uploading" plain @click="chooseModel"><el-icon><UploadFilled /></el-icon>{{ importActionLabel }}</el-button>
       </div>
       <div v-if="!activeModels.length" class="empty-models">
         <el-icon><DocumentAdd /></el-icon>
          <p>{{ importIntent ? `暂无模型资源。${importGuidance}` : `暂无模型资源。${importGuidance}` }}</p>
         <el-button :loading="uploading" type="primary" @click="chooseModel">{{ importActionLabel }}</el-button>
      </div>
       <el-table v-else :data="activeModels" row-key="id" class="models-table" :row-class-name="({ row }) => row.id === selectedModelId ? 'selected-model-row' : ''" @row-dblclick="row => activateResource({ type: 'model', id: `model-${row.id}`, projectId: activeProject.id, modelId: row.id })">
         <el-table-column label="模型 / 类型" min-width="240"><template #default="{ row }"><strong>{{ row.name }}</strong><small>{{ simulatorTypeLabel(row) }}</small></template></el-table-column>
         <el-table-column label="最新版本" min-width="105"><template #default="{ row }">v{{ latestVersion(row)?.versionNo || '-' }}</template></el-table-column>
          <el-table-column label="验证" min-width="130"><template #default="{ row }"><el-tooltip :content="latestVersion(row)?.status === 'READY' ? '最新版本已通过 Worker 验证，可进入计算' : readyVersion(row) ? `最新版本等待 Worker 验证或需要重新验证；v${readyVersion(row).versionNo} READY 版本仍可进入计算` : '最新版本等待 Worker 验证或需要重新验证'" placement="top"><el-tag :type="latestVersion(row)?.status === 'READY' ? 'success' : latestVersion(row)?.status === 'VALIDATING' ? 'primary' : 'warning'">{{ latestVersion(row)?.status || 'UPLOADED' }}</el-tag></el-tooltip></template></el-table-column>
          <el-table-column label="可用 Study" min-width="240"><template #default="{ row }"><span v-if="readyVersion(row)?.studies?.length">{{ readyVersion(row).studies.join('、') }}</span><span v-else class="muted">{{ readyVersion(row) ? '该 READY 版本无需 Study' : '等待验证完成' }}</span></template></el-table-column>
          <el-table-column label="下一操作" width="170">
            <template #default="{ row }">
              <el-button
                v-if="readyVersion(row)"
               link
               type="primary"
               @click.stop="activateResource({ type: 'model', id: `model-${row.id}`, projectId: activeProject.id, modelId: row.id })"
             >进入计算</el-button>
              <el-button v-if="latestVersion(row)?.status !== 'READY'" link type="primary" @click.stop="revalidateModel(latestVersion(row).id)">重新验证</el-button>
            </template>
        </el-table-column>
      </el-table>
    </template>
    </template>
    </main>
    <el-dialog v-model="createDialogVisible" title="新建软件项目" width="500px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent="createProject">
        <el-form-item label="项目名称" required><el-input v-model="projectName" maxlength="100" autofocus /></el-form-item>
        <el-form-item label="项目说明"><el-input v-model="projectDescription" maxlength="500" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createDialogVisible = false">取消</el-button><el-button :loading="creating" type="primary" @click="createProject">创建</el-button></template>
    </el-dialog>
  </section>
</template>

<style lang="scss" scoped>
.software-integration-workspace { flex: 1; min-width: 0; min-height: 0; display: flex; background: #f6f6f4; color: #252525; }
.software-resource-panel { width: 238px; min-width: 238px; display: flex; flex-direction: column; border-right: 1px solid #deded9; background: #fff; transition: width .16s ease, min-width .16s ease; }
.software-resource-panel.collapsed { width: 22px; min-width: 22px; border-right: 0; }
.collapsed-tab { width: 22px; height: 54px; padding: 0; border: 0; background: transparent; color: #333; cursor: pointer; writing-mode: vertical-rl; font-size: 13px; }.collapsed-tab:hover { background: #fff7bf; color: #6d5900; }
.tree-search { padding: 8px 7px 6px; display: flex; align-items: center; gap: 4px; border-bottom: 1px solid #e7e7e2; }
.tree-toggle { width: 20px; height: 20px; padding: 0; border: 0; border-radius: 2px; background: transparent; color: #777; cursor: pointer; font-size: 9px; }.tree-toggle:hover { background: #fff7bf; }
.resource-tree-wrap { flex: 1; min-height: 0; overflow: auto; padding: 6px 4px; }
.resource-tree-wrap :deep(.el-tree-node__content) { height: 27px; font-size: 13px; }.resource-tree-wrap :deep(.el-tree-node__content:hover) { background: #f5f5f1; }.resource-tree-wrap :deep(.el-tree-node.is-current > .el-tree-node__content) { background: #fff3a6; color: #342c00; }
.software-tree-node { min-width: 0; width: 100%; display: flex; align-items: center; gap: 5px; }.software-tree-node .el-icon { flex: 0 0 auto; color: #b58b00; }.software-tree-node > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.tree-model-action { display: none; margin-left: auto; padding: 2px 5px; border: 1px solid #d7ad00; border-radius: 2px; background: #fff; color: #695400; cursor: pointer; font-size: 11px; line-height: 16px; white-space: nowrap; }.tree-model-action:hover { background: #f4d000; color: #1f1a00; }.resource-tree-wrap :deep(.el-tree-node.is-current) .tree-model-action { display: block; }
.tree-empty { padding: 18px 8px; color: #909399; text-align: center; font-size: 12px; }
.software-content { flex: 1; min-width: 0; min-height: 0; overflow: auto; background: #f6f6f4; }
.software-content > .workspace-header, .software-content > .workflow-guide, .software-content > .workspace-toolbar, .software-content > .empty-state, .software-content > .empty-models, .software-content > .models-table { margin-left: 28px; margin-right: 28px; }
.workspace-header, .workspace-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.workspace-header { padding-top: 20px; padding-bottom: 14px; }
h1 { margin: 0; font-size: 19px; font-weight: 650; letter-spacing: -.2px; }.description { margin: 5px 0 0; color: #777; font-size: 12px; }
.header-actions { display: flex; gap: 10px; }
.workflow-guide { border: 1px solid #deded9; border-top: 3px solid #f4d000; background: #fff; }.workflow-guide-title { display: flex; align-items: center; justify-content: space-between; padding: 8px 12px; border-bottom: 1px solid #ecece8; font-size: 12px; }.workflow-guide-title strong { font-size: 13px; }.workflow-guide-title span { color: #756000; }.workflow-steps { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); margin: 0; padding: 0; list-style: none; }.workflow-steps li { min-width: 0; display: flex; align-items: center; gap: 7px; padding: 10px 11px; border-right: 1px solid #ecece8; color: #8a8a85; }.workflow-steps li:last-child { border-right: 0; }.workflow-steps li.current { background: #fff7bf; color: #2c2600; }.workflow-steps li.complete { color: #52623c; }.workflow-index { width: 21px; height: 21px; flex: 0 0 21px; display: grid; place-items: center; border: 1px solid #cacac3; border-radius: 50%; font-size: 11px; }.complete .workflow-index { border-color: #8fa668; background: #edf4df; }.current .workflow-index { border-color: #b79500; background: #f4d000; }.workflow-steps b, .workflow-steps small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.workflow-steps b { font-size: 12px; }.workflow-steps small { margin-top: 2px; font-size: 11px; color: #8a8a85; }.current small { color: #6b5900; }
.workspace-toolbar { margin-top: 16px; margin-bottom: 10px; padding: 10px 12px; border: 1px solid #deded9; background: #fff; }.workspace-toolbar span { margin-left: 12px; color: #777; font-size: 12px; }.hidden-input { display: none; }
.empty-state, .empty-models { min-height: 300px; display: flex; flex-direction: column; align-items: center; justify-content: center; border: 1px dashed #d6d6ce; background: #fff; text-align: center; color: #777; }.empty-state > .el-icon, .empty-models .el-icon { color: #b58b00; font-size: 38px; }.empty-state p { margin: 10px 0 6px; color: #363630; font-size: 15px; font-weight: 600; }.empty-state span, .empty-models p { max-width: 520px; margin: 0; font-size: 12px; line-height: 1.65; }.empty-state .el-button, .empty-models .el-button { margin-top: 14px; }.models-table { border: 1px solid #deded9; border-top: 3px solid #f4d000; background: #fff; } small { display: block; margin-top: 4px; color: #888; }.muted { color: #909399; }
:deep(.models-table th.el-table__cell) { background: #f5f5f1; color: #555; font-size: 12px; font-weight: 600; }:deep(.models-table .el-table__cell) { padding-top: 9px; padding-bottom: 9px; }:deep(.selected-model-row > td.el-table__cell) { background: #fff9cc !important; }
@media (max-width: 900px) { .software-resource-panel { width: 205px; min-width: 205px; }.software-content > .workspace-header, .software-content > .workflow-guide, .software-content > .workspace-toolbar, .software-content > .empty-state, .software-content > .empty-models, .software-content > .models-table { margin-left: 16px; margin-right: 16px; }.workspace-header, .workspace-toolbar { align-items: flex-start; flex-direction: column; }.workflow-steps { grid-template-columns: 1fr; }.workflow-steps li { border-right: 0; border-bottom: 1px solid #ecece8; }.workflow-steps li:last-child { border-bottom: 0; }.header-actions { width: 100%; } }
@media (max-width: 640px) { .software-integration-workspace { display: block; }.software-resource-panel { width: 100%; min-width: 0; max-height: 178px; border-right: 0; border-bottom: 1px solid #deded9; }.software-resource-panel.collapsed { width: 100%; min-width: 0; height: 28px; min-height: 28px; }.collapsed-tab { width: 100%; height: 28px; writing-mode: horizontal-tb; }.resource-tree-wrap { padding-bottom: 4px; }.workspace-header { padding-top: 14px; }.workflow-guide-title { align-items: flex-start; gap: 5px; flex-direction: column; }.workspace-toolbar .el-button { width: 100%; }.tree-model-action { font-size: 10px; } }
</style>
