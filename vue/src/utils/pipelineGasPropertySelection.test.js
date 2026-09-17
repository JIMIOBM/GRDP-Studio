import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import * as Vue from 'vue'
import * as serverRenderer from 'vue/server-renderer'
import { parse } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-ssr'

// Compile the actual sidebar so a background-loading disabled binding cannot regress silently.
const file = new URL('../views/PipelineCapacity/PipelineGasPropertyPage.vue', import.meta.url)
const { descriptor } = parse(fs.readFileSync(file, 'utf8'))
function findPanel(node) {
  if (node.type === 1 && node.tag === 'fieldset'
    && node.props.some(prop => prop.name === 'class' && prop.value?.content === 'parameter-form')) return node
  for (const child of node.children || []) {
    const found = findPanel(child)
    if (found) return found
  }
}
const panel = findPanel(descriptor.template.ast)
assert.ok(panel, 'The PVT parameter sidebar must exist')
const ssrRender = new Function('require', compile(panel.loc.source, { mode: 'function' }).code)(name => {
  if (name === 'vue') return Vue
  if (name === 'vue/server-renderer') return serverRenderer
  throw new Error(`Unexpected render dependency: ${name}`)
})
async function sidebar(kind, busy, sourceBusy, pvtId = 14) {
  return serverRenderer.renderToString(Vue.createSSRApp({
    ssrRender,
    setup: () => ({
      kind, busy, sourceBusy, actionBusy: busy || sourceBusy, pvtId, method: 'PR',
      pvtName: pvtId ? '当前井气体物性模型' : '尚未保存 PVT 模型',
      importedFileNames: {}, importVisible: false, loadPvt() {}, calculate() {}
    })
  }))
}
for (const kind of ['z', 'cp']) {
  test(`${kind}: source model and method stay read-only during background composition reads`, async () => {
    const html = await sidebar(kind, false, true)
    assert.doesNotMatch(html.match(/^<fieldset[^>]*>/)[0], /\bdisabled\b/)
    assert.doesNotMatch(html, /<select|type="radio"/)
    assert.match(html, /value="当前井气体物性模型" readonly/)
    assert.match(html, /value="PR" readonly/)
    assert.match(html, /<button[^>]* disabled[^>]*>计算<\/button>/)
    assert.match(html, /<button[^>]* disabled[^>]*>保存<\/button>/)
  })
  test(`${kind}: foreground calculation and save still lock the PVT fieldset`, async () => {
    const html = await sidebar(kind, true, false)
    assert.match(html.match(/^<fieldset[^>]*>/)[0], /\bdisabled\b/)
  })
  test(`${kind}: missing model explains the complete-composition entry without reverting to legacy PVT`, async () => {
    const html = await sidebar(kind, false, false, null)
    assert.match(html, /管束能力的 PVT 模型页/)
    assert.doesNotMatch(html, /数据管理|<select/)
  })
  test(`${kind}: completing the background read re-enables calculation and save`, async () => {
    const html = await sidebar(kind, false, false)
    assert.doesNotMatch(html.match(/<button[^>]*>计算<\/button>/)[0], /\bdisabled\b/)
    assert.doesNotMatch(html.match(/<button[^>]*>保存<\/button>/)[0], /\bdisabled\b/)
  })
}
