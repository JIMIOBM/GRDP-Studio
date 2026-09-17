<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { storageCatalogApi } from '@/api/storageCatalog'
import '@/style/storageCatalogDialog.css'

const props = defineProps({ scope: { type: Object, default: null } })
const visible = ref(false)
const storage = ref(null)
const keyword = ref('')
const wells = ref([])
const loading = ref(false)
const error = ref('')
let version = 0
let active = true
const filtered = computed(() => wells.value.filter(well =>
  String(well.wellName ?? '').toLowerCase().includes(keyword.value.trim().toLowerCase())))
const close = () => { visible.value = false; loading.value = false; version++ }
const load = async () => {
  if (!visible.value || !storage.value) return
  const current = storage.value
  const requestVersion = ++version
  loading.value = true
  error.value = ''
  wells.value = []
  try {
    const response = await storageCatalogApi.wells(current.storageId, current.projectId, current.gasReservoirId)
    if (!active || requestVersion !== version) return
    const rows = response?.data ?? response
    if (!Array.isArray(rows)) throw new Error('单井列表格式不正确')
    wells.value = rows
  } catch (failure) {
    if (active && requestVersion === version) {
      error.value = failure?.response?.data?.msg || failure?.msg || failure?.message || '单井列表加载失败，请重试'
    }
  } finally {
    if (active && requestVersion === version) loading.value = false
  }
}
const open = node => {
  close()
  // 复制右击目标的完整归属，不依赖当前选中井/库，也不改变主页面选择。
  const target = { storageId: Number(node.storageId), projectId: Number(node.projectId),
    gasReservoirId: Number(node.gasReservoirId), name: node.label }
  if (![target.storageId, target.projectId, target.gasReservoirId].every(id => Number.isSafeInteger(id) && id > 0)) return
  storage.value = target
  keyword.value = ''
  visible.value = true
  return load()
}
watch([() => props.scope?.projectId, () => props.scope?.gasReservoirId], close, { flush: 'sync' })
onBeforeUnmount(() => { active = false; close() })
defineExpose({ open })
</script>

<template>
  <el-dialog class="storage-catalog-dialog storage-members-dialog" :model-value="visible" title="查看包含单井" width="600px" align-center append-to-body
    @update:model-value="value => { if (!value) close() }">
    <el-form label-position="left" label-width="98px" @submit.prevent>
      <el-form-item label="储气库名称">
        <el-input :model-value="storage?.name ?? ''" readonly aria-label="储气库名称" />
      </el-form-item>
      <el-form-item label="包含的单井">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索井名" aria-label="搜索库内井名" />
      </el-form-item>
      <div class="storage-members-picker">
        <div class="storage-members-toolbar">
          <span>井名</span>
          <span v-if="!loading && !error" class="storage-members-count">共 {{ wells.length }} 口井</span>
        </div>
        <div v-loading="loading" class="storage-members-list" :aria-busy="loading">
          <div v-if="error" role="alert" class="storage-members-empty">
            <p>{{ error }}</p><el-button size="small" @click="load">重新加载</el-button>
          </div>
          <template v-else-if="!loading">
            <ul v-if="filtered.length" class="storage-members-grid" aria-label="储气库包含的单井">
              <li v-for="well in filtered" :key="well.id" class="storage-member-item">{{ well.wellName }}</li>
            </ul>
            <p v-else class="storage-members-empty">{{ wells.length ? '没有匹配的单井' : '该储气库暂无关联单井' }}</p>
          </template>
        </div>
      </div>
    </el-form>
    <template #footer><el-button type="primary" @click="close">关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.storage-members-picker { width: 100%; overflow: hidden; border: 1px solid #d7d7d7; border-radius: 3px; }
.storage-members-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 42px; box-sizing: border-box; padding: 4px 16px; border-bottom: 1px solid #e5e5e5; background: #f7f7f7; color: #444; font-size: 14px; }
.storage-members-count { flex-shrink: 0; color: #707070; font-size: 13px; white-space: nowrap; }
.storage-members-list { min-height: 132px; max-height: 264px; overflow-y: auto; }
.storage-members-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); list-style: none; margin: 0; padding: 0; }
.storage-member-item { display: flex; align-items: center; box-sizing: border-box; min-width: 0; min-height: 44px; padding: 10px 16px; border-bottom: 1px solid #e9e9e9; color: #444; font-size: 14px; line-height: 22px; overflow-wrap: anywhere; }
.storage-member-item:nth-child(odd) { border-right: 1px solid #e9e9e9; }
.storage-member-item:last-child,
.storage-member-item:nth-last-child(2):nth-child(odd) { border-bottom: 0; }
.storage-members-empty { padding: 44px 16px; margin: 0; text-align: center; color: #888; font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 480px) {
  .storage-members-grid { grid-template-columns: minmax(0, 1fr); }
  .storage-member-item:nth-child(odd) { border-right: 0; }
  .storage-member-item:nth-last-child(2):nth-child(odd) { border-bottom: 1px solid #e9e9e9; }
}
</style>
