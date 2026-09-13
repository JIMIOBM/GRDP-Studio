import test from 'node:test'
import assert from 'node:assert/strict'
import { mountResizablePanel, clampParameterPanelWidth } from './resizableParameterPanel.js'

class Element extends EventTarget {
  style = { width: '', minWidth: '', flexBasis: '', position: '', cursor: '', userSelect: '' }
  children = []
  attributes = {}
  clientHeight = 600
  scrollTop = 0
  classList = { add() {}, remove() {} }
  setAttribute(key, value) { this.attributes[key] = value }
  append(child) { this.children.push(child); child.parentElement = this }
  contains(child) { return this.children.includes(child) }
  remove() { this.parentElement.children = this.parentElement.children.filter(child => child !== this) }
  focus() {}
}
const fire = (target, type, properties = {}) => {
  const event = new Event(type, { cancelable: true })
  Object.assign(event, properties)
  target.dispatchEvent(event)
}
function setup() {
  const win = new EventTarget()
  const pending = new Map()
  let sequence = 0
  let disconnected = false
  Object.assign(win, {
    Event,
    requestAnimationFrame(callback) { pending.set(++sequence, callback); return sequence },
    cancelAnimationFrame(id) { pending.delete(id) },
    ResizeObserver: class { observe() {} disconnect() { disconnected = true } }
  })
  const body = new Element()
  const doc = { defaultView: win, body, createElement: () => new Element() }
  const panel = new Element()
  panel.ownerDocument = doc
  panel.parentElement = { clientWidth: 1000 }
  const controller = mountResizablePanel(panel)
  return { panel, controller, win, body, handle: panel.children[0], pending, disconnected: () => disconnected }
}

test('拖拽宽度限制为238到520，小窗口给结果区域留出空间', () => {
  assert.equal(clampParameterPanelWidth(100, 1000), 238)
  assert.equal(clampParameterPanelWidth(900, 1000), 520)
  assert.equal(clampParameterPanelWidth(500, 600), 440)
})

test('鼠标拖动会改变实际宽度，松开/取消后停止并恢复文本选择', () => {
  const { panel, controller, win, body, handle } = setup()
  fire(handle, 'pointerdown', { button: 0, clientX: 238, pointerId: 1 })
  fire(win, 'pointermove', { clientX: 380, pointerId: 1 })
  assert.equal(panel.style.width, '380px')
  assert.equal(panel.style.flexBasis, '380px')
  assert.equal(body.style.userSelect, 'none')
  fire(win, 'pointercancel')
  fire(win, 'pointermove', { clientX: 450, pointerId: 1 })
  assert.equal(panel.style.width, '380px')
  assert.equal(body.style.userSelect, '')
  assert.equal(body.style.cursor, '')
  controller.destroy()
})

test('收起隐藏拖拽条，重新展开保留宽度，键盘调整可用', () => {
  const { panel, controller, handle } = setup()
  fire(handle, 'keydown', { key: 'End' })
  assert.equal(panel.style.width, '520px')
  controller.update(true)
  assert.equal(handle.hidden, true)
  assert.equal(panel.style.width, '')
  fire(handle, 'keydown', { key: 'Home' })
  controller.update(false)
  assert.equal(panel.style.width, '520px')
  fire(handle, 'keydown', { key: 'ArrowLeft' })
  assert.equal(panel.style.width, '500px')
  fire(handle, 'keydown', { key: 'Home' })
  assert.equal(panel.style.width, '238px')
  controller.destroy()
})

test('滚动时拖拽条跟随可见区域，卸载清理拖动监听和待处理图表缩放', () => {
  const { panel, controller, handle, win, body, pending, disconnected } = setup()
  panel.scrollTop = 300
  fire(panel, 'scroll')
  assert.equal(handle.style.top, '300px')
  assert.equal(handle.style.height, '600px')
  fire(handle, 'pointerdown', { button: 0, clientX: 238, pointerId: 1 })
  controller.destroy()
  assert.equal(disconnected(), true)
  assert.equal(pending.size, 0)
  assert.equal(panel.children.length, 0)
  assert.equal(body.style.userSelect, '')
  fire(win, 'pointermove', { clientX: 400, pointerId: 1 })
  assert.equal(panel.style.width, '')
})
