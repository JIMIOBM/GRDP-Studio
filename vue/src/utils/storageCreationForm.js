import { computed, ref, watch } from 'vue'

// 创建弹窗独立持有项目快照，避免异步加载期间切项目后把旧井保存到新项目。
export function createStorageForm({ scope, api, onCreated }) {
  const visible = ref(false)
  const name = ref('')
  const keyword = ref('')
  const selectedIds = ref([])
  const candidates = ref([])
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  let version = 0
  let active = true
  let openedScope = null
  const readScope = () => ({ projectId: Number(scope.value?.projectId), gasReservoirId: Number(scope.value?.gasReservoirId) })
  const matches = value => value?.projectId === readScope().projectId && value?.gasReservoirId === readScope().gasReservoirId
  const validScope = value => value.projectId > 0 && value.gasReservoirId > 0
  const filtered = computed(() => candidates.value.filter(well =>
    String(well.wellName ?? '').toLowerCase().includes(keyword.value.trim().toLowerCase())))
  const allFilteredSelected = computed(() => filtered.value.length > 0
    && filtered.value.every(well => selectedIds.value.includes(well.id)))
  const canSubmit = computed(() => !loading.value && !saving.value && candidates.value.length > 0
    && name.value.trim().length > 0 && name.value.trim().length <= 100
    && selectedIds.value.length > 0 && selectedIds.value.length <= 2000)
  const message = failure => failure?.response?.data?.msg || failure?.msg || failure?.message || '请求失败，请重试'
  const unpack = response => response?.data ?? response

  const loadCandidates = async () => {
    const requestVersion = ++version
    const snapshot = openedScope
    loading.value = true
    error.value = ''
    candidates.value = []
    selectedIds.value = []
    try {
      const rows = unpack(await api.candidateWells(snapshot.projectId, snapshot.gasReservoirId))
      if (!active || requestVersion !== version || !matches(snapshot)) return
      if (!Array.isArray(rows)) throw new Error('单井列表格式不正确')
      candidates.value = rows.map(well => ({ id: Number(well.id), wellName: well.wellName }))
    } catch (failure) {
      if (active && requestVersion === version) error.value = message(failure)
    } finally {
      if (active && requestVersion === version) loading.value = false
    }
  }
  const open = () => {
    if (saving.value) return
    openedScope = readScope()
    if (!validScope(openedScope)) return
    name.value = ''; keyword.value = ''; selectedIds.value = []; candidates.value = []; error.value = ''
    visible.value = true
    return loadCandidates()
  }
  const close = () => {
    if (saving.value) return
    visible.value = false
    loading.value = false
    version++
  }
  const selectFiltered = checked => {
    const ids = new Set(selectedIds.value)
    filtered.value.forEach(well => checked ? ids.add(well.id) : ids.delete(well.id))
    selectedIds.value = [...ids]
  }
  const submit = async () => {
    if (!canSubmit.value || !matches(openedScope)) return
    const available = new Set(candidates.value.map(well => well.id))
    const wellIds = [...new Set(selectedIds.value)]
    if (!wellIds.every(id => available.has(id))) { error.value = '所选单井已失效，请重新加载'; return }
    const payload = { ...openedScope, name: name.value.trim(), wellIds }
    const requestVersion = version
    error.value = ''
    saving.value = true
    let saved
    try {
      saved = unpack(await api.create(payload))
    } catch (failure) {
      if (active && requestVersion === version) error.value = message(failure)
      return
    } finally { saving.value = false }
    // 落库成功后立即关闭，目录刷新失败也不能让用户再次提交创建相同库。
    if (active && requestVersion === version) { visible.value = false; version++ }
    if (active) await onCreated(saved, payload)
  }
  const stop = watch([() => scope.value?.projectId, () => scope.value?.gasReservoirId], () => {
    version++; visible.value = false; loading.value = false
    selectedIds.value = []; candidates.value = []; error.value = ''
  }, { flush: 'sync' })
  const dispose = () => { active = false; version++; stop() }
  return { visible, name, keyword, selectedIds, candidates, loading, saving, error,
    filtered, allFilteredSelected, canSubmit, open, close, loadCandidates, selectFiltered, submit, dispose }
}
