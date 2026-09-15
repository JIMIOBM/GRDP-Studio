<script setup>
import { computed, onBeforeUnmount } from 'vue'
import { storageCatalogApi } from '@/api/storageCatalog'
import { createStorageForm } from '@/utils/storageCreationForm'

const props = defineProps({ scope: { type: Object, default: null } })
const emit = defineEmits(['created'])
const form = createStorageForm({ scope: computed(() => props.scope), api: storageCatalogApi,
  onCreated: (storage, scope) => emit('created', storage, scope) })
const { visible, name, keyword, selectedIds, candidates, loading, saving, error,
  filtered, allFilteredSelected, canSubmit, close, loadCandidates, selectFiltered, submit } = form
defineExpose({ open: form.open })
onBeforeUnmount(form.dispose)
</script>

<template>
  <el-dialog :model-value="visible" title="新建储气库" width="560px" append-to-body
    :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving"
    @update:model-value="value => { if (!value) close() }">
    <el-form label-position="top" :disabled="saving" @submit.prevent="submit">
      <el-form-item label="储气库名称" required>
        <el-input v-model="name" maxlength="100" show-word-limit placeholder="请输入储气库名称" />
      </el-form-item>
      <el-form-item label="包含的单井" required>
        <div class="storage-well-picker">
          <el-input v-model="keyword" clearable placeholder="搜索井名或井ID" aria-label="搜索可选单井" />
          <div class="storage-well-toolbar">
            <el-checkbox :model-value="allFilteredSelected" :disabled="loading || !filtered.length"
              @change="selectFiltered">选择当前搜索结果</el-checkbox>
            <span>已选 {{ selectedIds.length }} / {{ candidates.length }} 口井</span>
          </div>
          <div v-loading="loading" class="storage-well-list">
            <el-checkbox-group v-model="selectedIds" aria-label="选择储气库包含的单井">
              <el-checkbox v-for="well in filtered" :key="well.id" :value="well.id">
                {{ well.wellName }} <span class="storage-well-id">ID {{ well.id }}</span>
              </el-checkbox>
            </el-checkbox-group>
            <p v-if="!loading && !error && !filtered.length" class="storage-well-empty">
              {{ candidates.length ? '没有匹配的单井' : '当前项目范围下没有可选单井' }}
            </p>
          </div>
          <p class="storage-well-hint">仅显示当前项目的单井，同一口井可以加入多个储气库。</p>
        </div>
      </el-form-item>
      <div v-if="error" role="alert" class="storage-create-error">
        {{ error }} <el-button text size="small" :disabled="saving" @click="loadCandidates">重新加载单井</el-button>
      </div>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="close">取消</el-button>
      <el-button type="primary" :disabled="!canSubmit" :loading="saving" @click="submit">创建储气库</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.storage-well-picker { width: 100%; }
.storage-well-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #606266; font-size: 12px; }
.storage-well-list { min-height: 100px; max-height: 300px; overflow-y: auto; padding: 8px 12px; border: 1px solid #dcdfe6; border-radius: 4px; }
.storage-well-list :deep(.el-checkbox-group) { display: flex; flex-direction: column; }
.storage-well-list :deep(.el-checkbox) { margin-right: 0; flex-shrink: 0; }
.storage-well-id, .storage-well-hint, .storage-well-empty { color: #909399; font-size: 12px; }
.storage-well-id { margin-left: 8px; }
.storage-well-hint { margin: 6px 0 0; line-height: 1.5; }
.storage-create-error { color: #f56c6c; font-size: 13px; }
</style>
