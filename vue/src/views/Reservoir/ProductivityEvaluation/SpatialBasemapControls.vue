<script setup>
/** 底图属于当前浏览器的当前库，不写入单井/库产能计算记录。图片仅按坐标范围贴图，不自动提取属性值。 */
import { computed, markRaw, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElDialog, ElMessage } from 'element-plus'
import { buildSimulatedGeology, paintSpatialMap, validBounds } from '@/utils/storageSpatialChart'

const props = defineProps({ scopeKey: { type: String, required: true }, bounds: { type: Object, default: null },
  showOpacity: { type: Boolean, default: true }, showCoordinateSettings: { type: Boolean, default: true } })
const emit = defineEmits(['change'])
const picker = ref(null), dialog = ref(false), busy = ref(false), name = ref(''), opacity = ref(85)
const kind = ref('simulation'), preview = ref(''), storageError = ref('')
const hasSaved = ref(false)
const form = reactive({ minX: '', maxX: '', minY: '', maxY: '', flipX: false, flipY: false, attribute: '厚度', unit: 'm', crs: '' })
let saved = null, decoded = null, pendingBlob = null, pendingImage = null, generation = 0, alive = true
const urls = new Set()
const releaseUrl = result => { if (result?.url) { URL.revokeObjectURL(result.url); urls.delete(result.url) } }
const cancelRegistration = () => {
  if (pendingImage && pendingImage !== decoded) releaseUrl(pendingImage)
  pendingImage = null; pendingBlob = null; preview.value = ''; name.value = saved?.name || ''
}
const canUseSaved = computed(() => hasSaved.value)
// 导入的图片仅存当前浏览器 IndexedDB，并以项目/气藏/库作用域隔离，不上传服务器。
const dbName = 'grdp-storage-spatial-basemaps-v1'
const openDatabase = () => new Promise((resolve, reject) => {
  const request = indexedDB.open(dbName, 1)
  request.onupgradeneeded = () => request.result.createObjectStore('maps', { keyPath: 'key' })
  request.onerror = () => reject(request.error)
  request.onblocked = () => reject(new Error('底图存储被其他页面占用'))
  request.onsuccess = () => resolve(request.result)
})
const accessRecord = async (key, record) => {
  const db = await openDatabase()
  try {
    return await new Promise((resolve, reject) => {
      const transaction = db.transaction('maps', record ? 'readwrite' : 'readonly')
      const store = transaction.objectStore('maps'), request = record ? store.put({ ...record, key }) : store.get(key)
      let value
      request.onsuccess = () => { value = request.result }
      transaction.oncomplete = () => resolve(value)
      transaction.onerror = () => reject(transaction.error)
      transaction.onabort = () => reject(transaction.error || new Error('底图存储中断'))
    })
  } finally { db.close() }
}
const decode = blob => new Promise((resolve, reject) => {
  const image = new Image(), url = URL.createObjectURL(blob); urls.add(url)
  image.onload = () => {
    if (image.naturalWidth * image.naturalHeight > 24000000 || image.naturalWidth > 12000 || image.naturalHeight > 12000) {
      URL.revokeObjectURL(url); urls.delete(url); reject(new Error('图片过大，请将图片缩小到 2400 万像素以内')); return
    }
    resolve({ image: markRaw(image), url })
  }
  image.onerror = () => { URL.revokeObjectURL(url); urls.delete(url); reject(new Error('无法读取图片，请选择有效的 PNG、JPEG 或 WebP 文件')) }
  image.src = url
})
const publish = () => emit('change', { image: kind.value === 'image' ? decoded?.image : null,
  ...(saved || {}), kind: kind.value, opacity: opacity.value / 100 })
const persist = async () => {
  const key = props.scopeKey, record = { ...(saved || {}), kind: kind.value, opacity: opacity.value }
  if (!key) return
  try { await accessRecord(key, record); if (key === props.scopeKey) storageError.value = '' }
  catch { if (key === props.scopeKey) storageError.value = '浏览器保存失败；本次仍可使用，刷新后需重新导入。' }
}
watch(() => props.scopeKey, async key => {
  const sequence = ++generation
  dialog.value = false; busy.value = false; kind.value = 'simulation'; opacity.value = 85
  saved = null; decoded = null; hasSaved.value = false; pendingBlob = null; pendingImage = null; preview.value = ''; name.value = ''; storageError.value = ''
  for (const url of urls) URL.revokeObjectURL(url)
  urls.clear(); publish()
  if (!key) return
  try {
    const record = await accessRecord(key)
    if (!alive || sequence !== generation || !record) return
    const result = record.blob ? await decode(record.blob) : null
    if (!alive || sequence !== generation) { if (result) { URL.revokeObjectURL(result.url); urls.delete(result.url) } return }
    if (record.blob && (!validBounds(record) || !result)) throw new Error('底图坐标记录无效')
    saved = record; decoded = result; hasSaved.value = Boolean(record.blob && result); opacity.value = Number.isFinite(record.opacity) ? Math.min(100, Math.max(0, record.opacity)) : 85
    kind.value = result && record.kind === 'image' ? 'image' : 'simulation'; name.value = record.name || ''; publish()
  } catch { if (sequence === generation && alive) storageError.value = '未能读取已保存底图，当前使用模拟底图。' }
}, { immediate: true })

const editRegistration = () => {
  if (!saved?.blob || !decoded) return picker.value.click()
  pendingBlob = saved.blob; pendingImage = decoded; preview.value = decoded.url; name.value = saved.name || ''
  Object.assign(form, { flipX: false, flipY: false, attribute: '厚度', unit: 'm', crs: '', ...saved }); dialog.value = true
}
const chooseImage = async event => {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file) return
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) return ElMessage.warning('仅支持 PNG、JPEG、WebP 图片')
  if (file.size > 10 * 1024 * 1024) return ElMessage.warning('图片不能超过 10 MB')
  const sequence = ++generation; busy.value = true
  try {
    const result = await decode(file)
    if (!alive || sequence !== generation) { URL.revokeObjectURL(result.url); urls.delete(result.url); return }
    if (pendingImage && pendingImage !== decoded) releaseUrl(pendingImage)
    pendingBlob = file; pendingImage = result; preview.value = result.url; name.value = file.name
    Object.assign(form, { minX: '', maxX: '', minY: '', maxY: '', flipX: false, flipY: false, attribute: '厚度', unit: 'm', crs: '' })
    dialog.value = true
  } catch (error) { if (sequence === generation && alive) ElMessage.error(error.message) }
  finally { if (sequence === generation && alive) busy.value = false }
}
const useWellRange = () => { if (props.bounds) Object.assign(form, props.bounds) }
const apply = async () => {
  const bounds = validBounds(form)
  if (!bounds) return ElMessage.warning('请填写有效坐标范围，最大值必须大于最小值')
  if (!form.crs.trim()) return ElMessage.warning('请填写并确认底图与井坐标使用同一坐标系')
  if (!pendingBlob || !pendingImage) return ElMessage.warning('请先选择底图')
  saved = { ...bounds, flipX: form.flipX, flipY: form.flipY, attribute: form.attribute.trim() || '地质属性',
    unit: form.unit.trim(), crs: form.crs.trim(), name: name.value, blob: pendingBlob }
  if (decoded && decoded !== pendingImage) releaseUrl(decoded)
  decoded = pendingImage; hasSaved.value = true; kind.value = 'image'; dialog.value = false; publish(); await persist()
}
const switchSource = async value => { kind.value = value; publish(); await persist() }
const updateOpacity = () => { publish(); persist() }
const downloadMock = () => {
  const canvas = paintSpatialMap({ field: buildSimulatedGeology(), simulated: true })
  const link = document.createElement('a'); link.href = canvas.toDataURL('image/png'); link.download = '模拟厚度底图-非真实地质数据.png'; link.click()
}
onBeforeUnmount(() => { alive = false; generation++; for (const url of urls) URL.revokeObjectURL(url); urls.clear() })
</script>

<template>
  <div class="basemap-controls">
    <input ref="picker" hidden type="file" accept="image/png,image/jpeg,image/webp" @change="chooseImage" />
    <button type="button" :disabled="busy || !scopeKey" @click="picker.click()">{{ busy ? '读取中…' : '导入底图' }}</button>
    <button v-if="showCoordinateSettings" type="button" :disabled="!canUseSaved || busy" @click="editRegistration">坐标设置</button>
    <button type="button" :class="{ selected: kind === 'simulation' }" @click="switchSource('simulation')">模拟底图</button>
    <button v-if="canUseSaved" type="button" :class="{ selected: kind === 'image' }" @click="switchSource('image')">已导入底图</button>
    <button type="button" @click="downloadMock">下载模拟图</button>
    <label v-if="showOpacity">不透明度 <input v-model.number="opacity" type="range" min="0" max="100" aria-label="底图不透明度" @input="publish" @change="updateOpacity" />{{ opacity }}%</label>
    <span v-if="kind === 'image'" class="map-name" :title="name">{{ name }}（仅本机保存）</span>
    <span v-if="storageError" class="storage-error" role="status">{{ storageError }}</span>
    <ElDialog v-model="dialog" title="底图坐标设置" width="640px" :close-on-click-modal="false" append-to-body @closed="cancelRegistration">
      <div class="registration">
        <p>坐标单位为 m，必须与井坐标使用同一坐标系；不会自动转换坐标系。仅支持已校正的矩形地图，旋转、倾斜图请先完成外部配准。</p>
        <img v-if="preview" :src="preview" class="map-preview" alt="待定位底图预览" />
        <div class="registration-grid">
          <label>最小 X（m）<input v-model="form.minX" inputmode="decimal" /></label>
          <label>最大 X（m）<input v-model="form.maxX" inputmode="decimal" /></label>
          <label>最小 Y（m）<input v-model="form.minY" inputmode="decimal" /></label>
          <label>最大 Y（m）<input v-model="form.maxY" inputmode="decimal" /></label>
          <label>底图属性<input v-model="form.attribute" maxlength="40" /></label>
          <label>属性单位<input v-model="form.unit" maxlength="20" /></label>
          <label class="full">坐标系说明（与井坐标一致）<input v-model="form.crs" maxlength="120" placeholder="例如：项目使用的平面坐标系名称" /></label>
        </div>
        <div class="map-orientation">
          <label><input v-model="form.flipX" type="checkbox" />图片右侧为 X 最小值</label>
          <label><input v-model="form.flipY" type="checkbox" />图片上侧为 Y 最小值</label>
        </div>
        <p>默认：X 向右增大，Y 向上增大；导入整幅图片（含图框时请先裁剪）。</p>
        <button type="button" :disabled="!bounds" @click="useWellRange">填入当前井位范围（仅供模拟图练习）</button>
      </div>
      <template #footer><button type="button" @click="dialog = false">取消</button><button type="button" class="apply-button" @click="apply">应用并保存到本机</button></template>
    </ElDialog>
  </div>
</template>

<style scoped>
.basemap-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 7px 10px; }
button { height: 27px; padding: 0 9px; border: 1px solid #d5d7da; border-radius: 3px; background: #fff; color: #333; font: inherit; cursor: pointer; }
button.selected, .apply-button { background: #fff8cf; border-color: #d6b82e; }
button:disabled { color: #aaa; cursor: not-allowed; }
.basemap-controls > label { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; }
input[type=range] { width: 90px; accent-color: #d4b400; }
.map-name { max-width: 230px; text-overflow: ellipsis; overflow: hidden; white-space: nowrap; font-size: 12px; color: #737373; }
.storage-error { color: #ad6c19; font-size: 12px; }
.registration { font: 13px/1.6 "Microsoft YaHei", sans-serif; }
.registration p { color: #777; margin: 0 0 12px; }
.map-preview { display: block; max-width: 100%; max-height: 190px; margin: 0 auto 14px; border: 1px solid #ddd; object-fit: contain; }
.registration-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 14px; }
.registration-grid label { display: flex; flex-direction: column; gap: 3px; }
.registration-grid input { height: 28px; border: 1px solid #ccc; border-radius: 3px; padding: 0 8px; min-width: 0; font: inherit; }
.registration-grid .full { grid-column: 1 / -1; }
.map-orientation { display: flex; flex-wrap: wrap; gap: 16px; margin: 14px 0 8px; }
.apply-button { margin-left: 10px; }
button:focus-visible, input:focus-visible { outline: 2px solid #a58e00; outline-offset: 1px; }
</style>
