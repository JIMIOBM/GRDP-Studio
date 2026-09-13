// 只负责缺少拖拽功能的参数栏，不修改输入、计算或保存状态。
const panels = new WeakMap()
export const parameterPanelMaxWidth = available => Math.max(238, Math.min(520, available - 160))
export const clampParameterPanelWidth = (width, available) => Math.max(238, Math.min(parameterPanelMaxWidth(available), width))

export function mountResizablePanel(panel, collapsed = false) {
  const win = panel.ownerDocument.defaultView
  const doc = panel.ownerDocument
  const handle = doc.createElement('div')
  handle.className = 'parameter-resize-handle'
  handle.tabIndex = 0
  handle.setAttribute('role', 'separator')
  handle.setAttribute('aria-label', '调整参数栏宽度')
  handle.setAttribute('aria-orientation', 'vertical')
  handle.setAttribute('aria-valuemin', '238')
  handle.title = '左右拖动调整参数栏宽度；方向键微调，Home / End 调整到最小 / 最大'
  const original = Object.fromEntries(['width', 'minWidth', 'flexBasis', 'position'].map(key => [key, panel.style[key]]))
  panel.style.position = 'relative'
  panel.append(handle)
  let width = 238
  let dragging = null
  let frame = 0
  let disposed = false
  const available = () => panel.parentElement?.clientWidth || 780
  const notifyResize = () => {
    if (frame || disposed) return
    frame = win.requestAnimationFrame(() => {
      frame = 0
      win.dispatchEvent(new win.Event('resize'))
    })
  }
  const positionHandle = () => {
    // PVT 部分面板自身滚动，拖拽条仍覆盖当前可见高度。
    handle.style.top = `${panel.scrollTop}px`
    handle.style.height = `${panel.clientHeight}px`
  }
  const setWidth = next => {
    if (collapsed) return
    const value = clampParameterPanelWidth(next, available())
    const changed = panel.style.width !== `${value}px`
    width = value
    panel.style.width = panel.style.minWidth = panel.style.flexBasis = `${value}px`
    handle.setAttribute('aria-valuenow', String(value))
    handle.setAttribute('aria-valuemax', String(parameterPanelMaxWidth(available())))
    positionHandle()
    if (changed) notifyResize()
  }
  const stop = () => {
    if (!dragging) return
    doc.body.style.userSelect = dragging.userSelect
    doc.body.style.cursor = dragging.cursor
    dragging = null
    handle.classList.remove('dragging')
    win.removeEventListener('pointermove', move)
    win.removeEventListener('pointerup', stop)
    win.removeEventListener('pointercancel', stop)
    win.removeEventListener('blur', stop)
  }
  const move = event => {
    if (!dragging || event.pointerId !== dragging.pointerId) return
    setWidth(dragging.width + event.clientX - dragging.x)
  }
  const start = event => {
    if (collapsed || dragging || event.button !== 0) return
    event.preventDefault()
    handle.focus()
    dragging = { x: event.clientX, width, pointerId: event.pointerId, userSelect: doc.body.style.userSelect, cursor: doc.body.style.cursor }
    doc.body.style.userSelect = 'none'
    doc.body.style.cursor = 'col-resize'
    handle.classList.add('dragging')
    win.addEventListener('pointermove', move)
    win.addEventListener('pointerup', stop)
    win.addEventListener('pointercancel', stop)
    win.addEventListener('blur', stop)
  }
  const keyboard = event => {
    if (collapsed || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    setWidth(event.key === 'Home' ? 238 : event.key === 'End' ? parameterPanelMaxWidth(available()) : width + (event.key === 'ArrowRight' ? 20 : -20))
  }
  const resize = () => { if (!collapsed) setWidth(width) }
  const update = value => {
    collapsed = Boolean(value)
    if (!panel.contains(handle)) panel.append(handle)
    handle.hidden = collapsed
    if (collapsed) {
      stop()
      for (const key of ['width', 'minWidth', 'flexBasis']) panel.style[key] = original[key]
      notifyResize()
    } else setWidth(width)
  }
  handle.addEventListener('pointerdown', start)
  handle.addEventListener('keydown', keyboard)
  panel.addEventListener('scroll', positionHandle)
  win.addEventListener('resize', resize)
  const observer = new win.ResizeObserver(() => { resize(); positionHandle() })
  observer.observe(panel)
  if (panel.parentElement) observer.observe(panel.parentElement)
  update(collapsed)
  return {
    update,
    destroy() {
      disposed = true
      stop()
      if (frame) win.cancelAnimationFrame(frame)
      observer.disconnect()
      handle.removeEventListener('pointerdown', start)
      handle.removeEventListener('keydown', keyboard)
      panel.removeEventListener('scroll', positionHandle)
      win.removeEventListener('resize', resize)
      handle.remove()
      Object.assign(panel.style, original)
    }
  }
}

export default {
  mounted(panel, binding) { panels.set(panel, mountResizablePanel(panel, binding.value)) },
  updated(panel, binding) { panels.get(panel)?.update(binding.value) },
  beforeUnmount(panel) { panels.get(panel)?.destroy(); panels.delete(panel) }
}
