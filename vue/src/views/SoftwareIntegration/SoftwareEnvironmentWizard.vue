<script setup>
import { onMounted, ref } from 'vue'
import { softwareEnvironmentApi } from '@/api/softwareEnvironment'

const emit = defineEmits(['configured'])
const visible = ref(false)
const busy = ref(false)
const error = ref('')
const snapshot = ref(null)
const form = ref({ pipesimHome: '', pythonPath: '', pipesimPtkPath: '', eclrunPath: '' })
const fields = {
  pipesim: [ ['pipesimHome', 'PIPESIM 安装目录'], ['pythonPath', 'Python 解释器（python.exe）'], ['pipesimPtkPath', 'Python Toolkit（PythonToolkitModules.zip）'] ],
  eclipse: [ ['eclrunPath', 'ECLIPSE 启动器（eclrun.exe）'] ]
}
const label = simulator => simulator === 'pipesim' ? 'PIPESIM / PIPESIM-NET' : 'ECLIPSE'
const statusText = { READY: '环境检测通过', MISSING: '尚未找到', FAILED: '检测未通过', UNSUPPORTED: '版本或启动器不支持' }

function accept(data, simulator = null) {
  snapshot.value = data
  for (const key of Object.keys(form.value)) {
    if (simulator && !fields[simulator].some(([field]) => field === key)) continue
    const existing = data.paths?.[key]
    const candidates = data.candidates?.[key] || []
    form.value[key] = candidates.includes(existing) ? existing : candidates[0] || existing || ''
  }
}
async function inspect(initial = false) {
  busy.value = true
  error.value = ''
  try {
    accept((await softwareEnvironmentApi.inspect()).data)
    if (initial && (!snapshot.value.configured || snapshot.value.configurationWarning
      || (snapshot.value.configuredSimulators || []).some(key => snapshot.value[key]?.status !== 'READY'))) visible.value = true
  } catch (e) {
    error.value = e?.msg || e?.message || '检测失败，请确认计算服务已启动。'
    if (initial) visible.value = true
  } finally { busy.value = false }
}
async function save(simulator) {
  busy.value = true
  error.value = ''
  try {
    accept((await softwareEnvironmentApi.save({ simulator, paths: { ...form.value } })).data, simulator)
    emit('configured')
  } catch (e) { error.value = e?.msg || e?.message || '验证或保存失败，原配置未更改。' }
  finally { busy.value = false }
}
function open() { visible.value = true; inspect() }
onMounted(() => inspect(true))
</script>

<template>
  <div class="environment-entry">
    <span v-if="busy">正在检测计算环境…</span>
    <span v-else-if="error">计算环境需要检查</span>
    <span v-else-if="snapshot">计算主机：{{ snapshot.host }}</span>
    <el-button plain size="small" @click="open">环境设置</el-button>
  </div>
  <el-dialog v-model="visible" title="软件集成 · 环境发现与配置" top="5vh" width="min(780px, 94vw)" :close-on-click-modal="false">
    <div class="environment-body">
    <p>这里配置的是运行 Worker 的计算主机，不是访问网页的电脑。PIPESIM 单井与管网共用一套环境；两种软件可分别配置。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="snapshot?.configurationWarning" :title="snapshot.configurationWarning" type="warning" :closable="false" />
    <p v-if="!snapshot && !busy">请先启动计算服务，然后重新检测。不配置也可以浏览已有项目和结果。</p>
    <template v-if="snapshot">
      <p>计算主机：{{ snapshot.host }} · {{ snapshot.configured ? '已有本机配置' : '首次使用，请确认并保存所需软件' }}</p>
      <section v-for="simulator in ['pipesim', 'eclipse']" :key="simulator" class="environment-card" :aria-label="`${label(simulator)} 环境配置`">
        <h3>{{ label(simulator) }} <el-tag :type="snapshot[simulator].status === 'READY' ? 'success' : 'warning'">{{ statusText[snapshot[simulator].status] }}</el-tag></h3>
        <p>{{ snapshot[simulator].message }}</p>
        <p v-if="snapshot.configuredSimulators?.includes(simulator)">已保存本机配置</p>
        <el-form label-position="top">
          <el-form-item v-for="[key, title] in fields[simulator]" :key="key" :label="title">
            <el-select v-model="form[key]" filterable allow-create default-first-option :aria-label="title" :disabled="busy" placeholder="选择检测结果，或输入计算主机上的完整路径" style="width: 100%">
              <el-option v-for="path in snapshot.candidates?.[key] || []" :key="path" :label="path" :value="path" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="busy" :disabled="busy || fields[simulator].some(([key]) => !form[key]?.trim())" @click="save(simulator)">验证并保存 {{ simulator === 'pipesim' ? 'PIPESIM' : 'ECLIPSE' }}</el-button>
      </section>
      <p class="environment-note">{{ snapshot.licenseNotice }}</p>
      <p class="environment-note">自动搜索常见安装目录及 PATH；未找到时可手动输入。保存成功后立即生效，并在本机保留，重启无需重复填写。运行计算期间不能修改配置。远程访问者请联系计算主机管理员配置。</p>
    </template>
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="inspect()">重新检测</el-button>
      <el-button @click="visible = false">稍后配置 / 继续浏览</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.environment-entry { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin: 12px 20px; color: #606266; font-size: 12px; }
.environment-card { padding: 16px; border: 1px solid #dcdfe6; border-radius: 6px; margin-top: 16px; }
.environment-card h3 { margin-top: 0; display: flex; gap: 12px; align-items: center; }
.environment-note { color: #606266; font-size: 12px; line-height: 1.7; }
.environment-body { max-height: calc(90vh - 140px); overflow-y: auto; }
</style>
