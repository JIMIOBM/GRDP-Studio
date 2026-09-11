<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'

const store = useSoftwareIntegrationStore()
const { activeModel, activeVersion, activeVersionId, versions } = storeToRefs(store)
const inspection = computed(() => activeVersion.value?.inspection || null)

const changeVersion = async versionId => {
  try {
    await store.selectVersion(versionId)
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '版本加载失败')
  }
}
</script>

<template>
  <section v-if="activeModel" class="eclipse-inspection-overview">
    <header class="model-header">
      <div>
        <div class="title-line">
          <h1>{{ activeModel.name }}</h1>
          <el-tag :type="activeVersion?.status === 'READY' ? 'success' : 'warning'">{{ activeVersion?.status || '无版本' }}</el-tag>
        </div>
        <p>ECLIPSE 100 .DATA 模型</p>
      </div>
    </header>

    <div class="version-control">
      <span>模型版本</span>
      <el-select :model-value="activeVersionId" @change="changeVersion">
        <el-option v-for="version in versions" :key="version.id" :value="version.id" :label="`v${version.versionNo} · ${version.status}`" />
      </el-select>
    </div>

    <section class="overview-section">
      <h2>文件信息</h2>
      <dl class="metadata-grid">
        <div><dt>文件名</dt><dd>{{ activeVersion?.originalName || '-' }}</dd></div>
        <div><dt>版本</dt><dd>v{{ activeVersion?.versionNo || '-' }}</dd></div>
        <div><dt>SHA-256</dt><dd class="monospace">{{ activeVersion?.sha256 || '-' }}</dd></div>
        <div><dt>大小</dt><dd>{{ activeVersion?.sizeBytes ?? '-' }} bytes</dd></div>
        <div><dt>验证状态</dt><dd>{{ activeVersion?.status || '-' }}</dd></div>
        <div class="wide"><dt>验证消息</dt><dd>{{ activeVersion?.validationMessage || '-' }}</dd></div>
      </dl>
    </section>

    <section v-if="inspection" class="overview-section">
      <h2>DATA 检查概览</h2>
      <dl class="metadata-grid">
        <div><dt>架构版本</dt><dd>{{ inspection.schemaVersion }}</dd></div>
        <div><dt>算例名称</dt><dd>{{ inspection.caseName }}</dd></div>
        <div><dt>单位制</dt><dd>{{ inspection.unitSystem ?? '未知' }}</dd></div>
        <div class="wide"><dt>段</dt><dd><template v-if="inspection.sections?.length"><el-tag v-for="section in inspection.sections" :key="section" class="value-tag">{{ section }}</el-tag></template><span v-else>未识别到支持的段</span></dd></div>
        <div class="wide"><dt>相态</dt><dd><template v-if="inspection.phases?.length"><el-tag v-for="phase in inspection.phases" :key="phase" class="value-tag">{{ phase }}</el-tag></template><span v-else>未识别到支持的相态</span></dd></div>
        <template v-if="inspection.dimensions">
          <div><dt>NX</dt><dd>{{ inspection.dimensions.nx }}</dd></div>
          <div><dt>NY</dt><dd>{{ inspection.dimensions.ny }}</dd></div>
          <div><dt>NZ</dt><dd>{{ inspection.dimensions.nz }}</dd></div>
        </template>
        <div v-else><dt>网格维度</dt><dd>未知</dd></div>
      </dl>
    </section>
  </section>
</template>

<style lang="scss" scoped>
.eclipse-inspection-overview { min-width: 0; min-height: 0; padding: 22px 28px 30px; color: #303133; }
.model-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-bottom: 16px; border-bottom: 1px solid #e4e7ed; }
.title-line { display: flex; align-items: center; gap: 10px; }
h1 { margin: 0; font-size: 19px; font-weight: 600; }.model-header p { margin: 5px 0 0; color: #909399; font-size: 12px; }
.version-control { display: flex; align-items: center; gap: 12px; padding: 18px 0; }.version-control > span { color: #606266; font-size: 12px; }.version-control .el-select { width: 210px; }
.overview-section { margin-top: 16px; border: 1px solid #e4e7ed; }.overview-section h2 { margin: 0; padding: 12px 16px; border-bottom: 1px solid #e4e7ed; font-size: 14px; font-weight: 600; }
.metadata-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0; margin: 0; }.metadata-grid > div { min-width: 0; padding: 13px 16px; border-bottom: 1px solid #ebeef5; }.metadata-grid > div:nth-last-child(-n + 3) { border-bottom: 0; }.metadata-grid .wide { grid-column: span 3; }
dt { margin-bottom: 5px; color: #909399; font-size: 12px; } dd { min-width: 0; margin: 0; overflow-wrap: anywhere; color: #303133; font-size: 13px; }.monospace { font-family: Consolas, monospace; }.value-tag { margin-right: 6px; }
@media (max-width: 760px) { .eclipse-inspection-overview { padding: 16px; }.metadata-grid { grid-template-columns: 1fr; }.metadata-grid .wide { grid-column: auto; }.metadata-grid > div { border-bottom: 1px solid #ebeef5; }.metadata-grid > div:last-child { border-bottom: 0; } }
</style>
