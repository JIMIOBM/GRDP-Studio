<script setup>
import { computed } from 'vue'

const props = defineProps({
  inspection: { type: Object, default: null }
})

const files = computed(() => {
  const value = props.inspection
  if (!value || !['pipesim-well-inspection/2', 'pipesim-well-inspection/3', 'pipesim-network-inspection/2', 'pipesim-network-inspection/3'].includes(value.schemaVersion) ||
    !Array.isArray(value.packageFiles) || value.packageFiles.length < 1 || value.packageFiles.length > 4096) return []
  const seen = new Set()
  const normalized = value.packageFiles.map(file => {
    const path = file?.relativePath
    const sha256 = file?.sha256
    if (typeof path !== 'string' || !path || path.length > 512 || path.startsWith('/') || path.includes('\\') ||
      path.includes('//') || path.includes(':') || path.split('/').some(part => !part || part === '.' || part === '..') ||
      seen.has(path) || !Number.isSafeInteger(file?.sizeBytes) || file.sizeBytes < 0 || file.sizeBytes > 512 * 1024 * 1024 ||
      typeof sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(sha256)) return null
    seen.add(path)
    return { relativePath: path, sizeBytes: file.sizeBytes, sha256 }
  })
  return normalized.some(file => !file) ? [] : normalized
})

const totalBytes = computed(() => files.value.reduce((sum, file) => sum + file.sizeBytes, 0))
const formatBytes = value => {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MiB`
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GiB`
}
</script>

<template>
  <section class="package-overview" aria-label="PIPESIM 模型工程包依赖">
    <div class="package-head">
      <div>
        <h3>模型工程包依赖</h3>
        <p>验证时冻结的 PIPESIM 主模型及伴随文件清单；运行前后会校验文件大小与 SHA-256。</p>
      </div>
      <el-tag type="success" effect="plain">{{ files.length }} 个文件 · {{ formatBytes(totalBytes) }}</el-tag>
    </div>
    <div v-if="files.length" class="package-table-scroll">
      <el-table :data="files" border size="small" class="package-table">
        <el-table-column prop="relativePath" label="相对路径" min-width="240" show-overflow-tooltip />
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column prop="sha256" label="SHA-256" min-width="310" show-overflow-tooltip />
      </el-table>
    </div>
    <p v-else class="package-empty">当前版本没有可展示的完整性清单，请重新验证模型版本。</p>
  </section>
</template>

<style scoped>
.package-overview { padding: 10px 0; }
.package-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 10px; }
.package-head h3 { margin: 0 0 5px; color: #303133; font-size: 14px; }
.package-head p { margin: 0; color: #73777d; font-size: 12px; }
.package-table-scroll { overflow-x: auto; }
.package-table { min-width: 680px; }
.package-empty { margin: 0; padding: 12px; background: #f5f5f4; color: #909399; font-size: 12px; }
@media (max-width: 760px) { .package-head { align-items: stretch; flex-direction: column; } }
</style>
