<script setup>
import { computed, onBeforeUnmount } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { storageCatalogApi } from '@/api/storageCatalog'
import { createStorageForm } from '@/utils/storageCreationForm'
import '@/style/storageCatalogDialog.css'

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
  <el-dialog class="storage-catalog-dialog storage-create-dialog" :model-value="visible" title="新建储气库" width="600px" append-to-body
    :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving"
    @update:model-value="value => { if (!value) close() }">
    <el-form label-position="left" label-width="98px" hide-required-asterisk :disabled="saving" @submit.prevent="submit">
      <el-form-item label="储气库名称" required>
        <el-input v-model="name" maxlength="100" placeholder="请输入储气库名称" aria-required="true" />
      </el-form-item>
      <el-form-item label="包含的单井" required>
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索井名" aria-label="搜索可选井名" />
      </el-form-item>
      <div class="storage-well-picker">
        <div class="storage-well-toolbar">
          <el-checkbox :model-value="allFilteredSelected" :disabled="loading || !filtered.length"
            aria-label="选择当前搜索结果" @change="selectFiltered">{{ keyword.trim() ? '全选搜索结果' : '全选' }}</el-checkbox>
          <span class="storage-well-count">已选 {{ selectedIds.length }} / {{ candidates.length }} 口井</span>
        </div>
        <div v-loading="loading" class="storage-well-list" :aria-busy="loading">
          <el-checkbox-group v-model="selectedIds" aria-label="选择储气库包含的单井">
            <el-checkbox v-for="well in filtered" :key="well.id" :value="well.id" class="storage-well-option">
              {{ well.wellName }}
            </el-checkbox>
          </el-checkbox-group>
          <p v-if="!loading && !error && !filtered.length" class="storage-well-empty">
            {{ candidates.length ? '没有匹配的单井' : '当前项目范围下没有可选单井' }}
          </p>
        </div>
      </div>
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
.storage-well-picker { width: 100%; overflow: hidden; border: 1px solid #d7d7d7; border-radius: 3px; }
.storage-well-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 4px 12px; min-height: 42px; padding: 4px 16px; box-sizing: border-box; border-bottom: 1px solid #e5e5e5; background: #f7f7f7; }
.storage-well-toolbar :deep(.el-checkbox) { margin: 0; }
.storage-well-count { color: #707070; font-size: 13px; white-space: nowrap; }
.storage-well-list { min-height: 132px; max-height: 264px; overflow-y: auto; }
.storage-well-list :deep(.el-checkbox-group) { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.storage-well-list :deep(.el-checkbox) { box-sizing: border-box; margin: 0; min-width: 0; min-height: 44px; height: auto; padding: 10px 16px; border-bottom: 1px solid #e9e9e9; }
.storage-well-list :deep(.el-checkbox:nth-child(odd)) { border-right: 1px solid #e9e9e9; }
.storage-well-list :deep(.el-checkbox:nth-last-child(1)),
.storage-well-list :deep(.el-checkbox:nth-last-child(2):nth-child(odd)) { border-bottom: 0; }
.storage-well-list :deep(.el-checkbox__label) { padding-left: 12px; white-space: normal; overflow-wrap: anywhere; }
.storage-well-list :deep(.el-checkbox:not(.is-disabled):hover) { background: #fafafa; }
.storage-well-empty { margin: 0; padding: 44px 16px; text-align: center; color: #888; font-size: 12px; }
.storage-create-error { margin-top: 12px; color: #333; border-left: 3px solid #f4d000; background: #f5f5f5; padding: 8px 10px; font-size: 13px; }
@media (max-width: 480px) {
  .storage-well-list :deep(.el-checkbox-group) { grid-template-columns: minmax(0, 1fr); }
  .storage-well-list :deep(.el-checkbox:nth-child(odd)) { border-right: 0; }
  .storage-well-list :deep(.el-checkbox:nth-last-child(2):nth-child(odd)) { border-bottom: 1px solid #e9e9e9; }
}
</style>
