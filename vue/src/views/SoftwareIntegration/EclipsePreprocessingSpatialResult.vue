<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi as api } from '@/api/softwareIntegration'
import EclipseGrid3D from './EclipseGrid3D.vue'
import {
  preprocessingSpatialResult,
  unwrapPreprocessing as unwrap,
} from './eclipsePreprocessing.js'

const props = defineProps({ build: { type: Object, required: true } })
const spatial = ref(null),
  loading = ref(false),
  error = ref('')
let generation = 0
watch(
  () => props.build,
  async (build) => {
    const token = ++generation
    spatial.value = null
    error.value = ''
    loading.value = true
    try {
      const [run, project] = await Promise.all([
        api.getRun(build.runId),
        api.getProject(build.projectId),
      ])
      if (token !== generation) return
      spatial.value = preprocessingSpatialResult(unwrap(run), build, unwrap(project))
    } catch (failure) {
      if (token === generation)
        error.value = failure?.message || '本次三维结果读取失败，请重新打开。'
    } finally {
      if (token === generation) loading.value = false
    }
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  generation++
})
</script>

<template>
  <section
    v-loading="loading"
    class="preprocessing-spatial-result"
    aria-label="前处理真实三维计算结果"
  >
    <header>
      <div>
        <small>真实计算结果 · RUN #{{ build.runId }}</small>
        <h2>查看储层场值与报告步演变</h2>
      </div>
      <el-tag type="success">原生 EGRID / INIT / UNRST</el-tag>
    </header>
    <p>几何和场值直接读取本次计算文件。这里展示原始结果；修改输入请返回表格，再生成新版本计算。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <EclipseGrid3D
      v-if="spatial"
      :key="build.runId"
      v-bind="spatial"
      :run-id="build.runId"
      read-only
    />
  </section>
</template>

<style scoped>
.preprocessing-spatial-result {
  margin: 12px 0;
  padding: 12px;
  border: 1px solid #deded9;
  background: #fff;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin: -12px -12px 10px;
  padding: 7px 12px;
  background: #f5f5f1;
  border-bottom: 1px solid #deded9;
}
header > div {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
small {
  color: #777;
  font-size: 11px;
}
h2 {
  margin: 0;
  color: #333;
  font-size: 13px;
  font-weight: 600;
}
p {
  margin: 8px 0;
  color: #777;
  font-size: 12px;
  line-height: 1.7;
}
</style>
