<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { Document, DocumentAdd, Folder, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'
import PipesimModelRunPage from './PipesimModelRunPage.vue'

const store = useSoftwareIntegrationStore()
const {
  projects,
  projectDetails,
  activeProject,
  activeProjectDetail,
  activeProjectId,
  activeModel,
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
let workspaceMounted = false

const activeModels = computed(() => activeProjectDetail.value?.models || [])
const resourceTree = computed(() => projects.value
  .map(project => projectDetails.value[project.id] || { project, models: [] })
  .filter(detail => detail.project.name.toLowerCase().includes(treeKeyword.value.trim().toLowerCase()) ||
    (detail.models || []).some(model => model.name.toLowerCase().includes(treeKeyword.value.trim().toLowerCase())))
  .map(detail => ({
    id: `project-${detail.project.id}`,
    label: detail.project.name,
    type: 'project',
    projectId: detail.project.id,
    defaultExpanded: true,
    children: [{
      id: `models-${detail.project.id}`,
      label: '井筒模型',
      type: 'model-category',
      defaultExpanded: true,
      children: (detail.models || []).map(model => ({
        id: `model-${model.id}`,
        label: model.name,
        type: 'model',
        projectId: detail.project.id,
        modelId: model.id,
        activatable: true
      }))
    }]
  })))
const defaultExpandedTreeIds = computed(() => resourceTree.value.flatMap(project => [project.id, project.children[0].id]))

const loadProjects = async () => {
  try {
    await store.loadProjects()
    if (activeProjectId.value) {
      activeTreeId.value = `project-${activeProjectId.value}`
      selectedTreeProjectId.value = activeProjectId.value
    }
    if (workspaceMounted && store.activeModelId && store.activeVersionId) {
      await store.loadRunHistory(store.activeVersionId)
    }
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '软件项目加载失败')
  }
}

const selectResource = async (node) => {
  activeTreeId.value = node.id
  if (node.type === 'project') {
    selectedModelId.value = ''
    selectedTreeProjectId.value = node.projectId
    return store.selectProject(node.projectId)
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
    ElMessage.error(error?.msg || error?.message || '模型页面加载失败')
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
    ElMessage.success('软件项目已创建')
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '软件项目创建失败')
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
    ElMessage.error(error?.msg || error?.message || '软件项目删除失败')
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
    ElMessage.error(error?.msg || error?.message || '软件项目加载失败')
  }
}
const revalidateModel = async (versionId) => {
  try {
    await store.revalidateModel(activeProject.value.id, versionId)
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '重新验证请求失败')
  }
}
const uploadModel = async (event) => {
  const [file] = event.target.files
  event.target.value = ''
  if (!file || !activeProject.value) return
  if (!/\.(pips|zip)$/i.test(file.name)) return ElMessage.error('仅支持 .pips 或 ZIP 模型包')
  if (file.size > 500 * 1024 * 1024) return ElMessage.error('模型文件不能超过500MB')
  uploading.value = true
  const projectId = activeProject.value.id
  try {
    const detail = await store.uploadModel(projectId, file)
    if (activeProjectId.value === projectId) activeTreeId.value = `project-${detail.project.id}`
    ElMessage.success('模型已保存，等待 Worker 异步验证')
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '模型上传失败')
  } finally { uploading.value = false }
}

onMounted(() => {
  workspaceMounted = true
  loadProjects()
})
onBeforeUnmount(() => {
  workspaceMounted = false
  store.cleanup()
})
defineExpose({ openCreateDialog, openImportModel })
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
              </span>
            </template>
          </el-tree>
          <div v-if="!loadingProjects && !resourceTree.length" class="tree-empty">暂无项目资源</div>
        </div>
      </template>
    </aside>
    <main class="software-content">
    <PipesimModelRunPage v-if="activeModel" />
    <template v-else>
    <header class="workspace-header">
      <div>
        <h1>{{ activeProject?.name || '项目概览' }}</h1>
        <p class="description">PIPESIM 模型、版本、Study 和运行结果独立管理。</p>
      </div>
      <div class="header-actions">
        <el-button :disabled="!activeProject" type="danger" plain @click="removeProject">删除项目</el-button>
      </div>
    </header>

    <div v-if="!activeProject" class="empty-state">
      <p>未选择软件项目</p>
      <span>请使用顶部“新建项目”创建项目，或在左侧资源树中选择已有项目。</span>
    </div>

    <template v-else>
      <div class="workspace-toolbar">
        <div><strong>PIPESIM 井筒模型</strong><span>双击模型或点击“进入计算”；支持单个 .pips 或包含依赖文件的 ZIP 包</span></div>
        <input ref="fileInput" accept=".pips,.zip" class="hidden-input" type="file" @change="uploadModel" />
        <el-button :loading="uploading" plain @click="chooseModel"><el-icon><UploadFilled /></el-icon>导入模型</el-button>
      </div>
      <div v-if="!activeModels.length" class="empty-models">
        <el-icon><DocumentAdd /></el-icon>
        <p>暂无模型资源。导入模型后 Worker 将异步读取 Study 并验证兼容性。</p>
      </div>
      <el-table v-else :data="activeModels" row-key="id" class="models-table" :row-class-name="({ row }) => row.id === selectedModelId ? 'selected-model-row' : ''" @row-dblclick="row => activateResource({ type: 'model', id: `model-${row.id}`, projectId: activeProject.id, modelId: row.id })">
        <el-table-column label="模型" min-width="220"><template #default="{ row }"><strong>{{ row.name }}</strong><small>{{ row.simulatorType }}</small></template></el-table-column>
        <el-table-column label="版本" min-width="90"><template #default="{ row }">v{{ row.versions?.[0]?.versionNo || '-' }}</template></el-table-column>
        <el-table-column label="验证状态" min-width="150"><template #default="{ row }"><el-tooltip :content="row.versions?.[0]?.validationMessage || '等待 Worker 验证'" placement="top"><el-tag :type="row.versions?.[0]?.status === 'READY' ? 'success' : row.versions?.[0]?.status === 'VALIDATING' ? 'primary' : 'warning'">{{ row.versions?.[0]?.status || 'UPLOADED' }}</el-tag></el-tooltip></template></el-table-column>
        <el-table-column label="Study" min-width="260"><template #default="{ row }"><span v-if="row.versions?.[0]?.studies?.length">{{ row.versions[0].studies.join('、') }}</span><span v-else class="muted">等待 Worker 验证</span></template></el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button
              v-if="row.versions?.[0]?.status === 'READY'"
              link
              type="primary"
              @click.stop="activateResource({ type: 'model', id: `model-${row.id}`, projectId: activeProject.id, modelId: row.id })"
            >进入计算</el-button>
            <el-button v-else-if="row.versions?.[0]" link type="primary" @click.stop="revalidateModel(row.versions[0].id)">重新验证</el-button>
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
.software-integration-workspace { flex: 1; min-width: 0; min-height: 0; display: flex; background: #fff; color: #2d2d2d; }
.software-resource-panel { width: 230px; min-width: 230px; display: flex; flex-direction: column; border-right: 1px solid #e0e0e0; background: #fff; transition: width .16s ease, min-width .16s ease; }
.software-resource-panel.collapsed { width: 22px; min-width: 22px; border-right: 0; }
.collapsed-tab { width: 22px; height: 54px; padding: 0; border: 0; background: transparent; color: #333; cursor: pointer; writing-mode: vertical-rl; font-size: 13px; }.collapsed-tab:hover { background: #eef4ff; color: #1677ff; }
.tree-search { padding: 6px 6px 4px; display: flex; align-items: center; gap: 4px; border-bottom: 1px solid #e0e0e0; }
.tree-toggle { width: 20px; height: 20px; padding: 0; border: 0; border-radius: 2px; background: transparent; color: #777; cursor: pointer; font-size: 9px; }.tree-toggle:hover { background: #eef4ff; }
.resource-tree-wrap { flex: 1; min-height: 0; overflow: auto; padding: 6px 4px; }
.resource-tree-wrap :deep(.el-tree-node__content) { height: 24px; font-size: 13px; }.resource-tree-wrap :deep(.el-tree-node__content:hover) { background: #f0f6ff; }.resource-tree-wrap :deep(.el-tree-node.is-current > .el-tree-node__content) { background: #e3effd; color: #4084d9; }
.software-tree-node { min-width: 0; display: flex; align-items: center; gap: 5px; }.software-tree-node .el-icon { flex: 0 0 auto; color: #d9a300; }.software-tree-node span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-empty { padding: 18px 8px; color: #909399; text-align: center; font-size: 12px; }
.software-content { flex: 1; min-width: 0; min-height: 0; overflow: auto; }
.software-content > .workspace-header, .software-content > .workspace-toolbar, .software-content > .empty-state, .software-content > .empty-models, .software-content > .models-table { margin-left: 34px; margin-right: 34px; }
.workspace-header, .workspace-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.workspace-header { padding-top: 28px; padding-bottom: 22px; border-bottom: 1px solid #e8e8e8; }
h1 { margin: 0; font-size: 18px; font-weight: 500; }.description { margin: 6px 0 0; color: #909399; font-size: 13px; }
.header-actions { display: flex; gap: 10px; }
.workspace-toolbar { margin: 26px 0 16px; }.workspace-toolbar span { margin-left: 12px; color: #909399; font-size: 13px; }.hidden-input { display: none; }
.empty-state, .empty-models { min-height: 380px; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; color: #909399; }
.empty-state p { margin: 0 0 8px; color: #606266; font-size: 14px; }.empty-state span { font-size: 13px; }.empty-models .el-icon { color: #4084d9; font-size: 48px; }.models-table { border-top: 3px solid #f4d000; } small { display: block; margin-top: 4px; color: #909399; }.muted { color: #909399; }
:deep(.selected-model-row > td.el-table__cell) { background: #eef5ff !important; }
@media (max-width: 900px) { .software-content > .workspace-header, .software-content > .workspace-toolbar, .software-content > .empty-state, .software-content > .empty-models, .software-content > .models-table { margin-left: 20px; margin-right: 20px; }.workspace-header, .workspace-toolbar { align-items: flex-start; flex-direction: column; }.header-actions { width: 100%; } }
</style>
