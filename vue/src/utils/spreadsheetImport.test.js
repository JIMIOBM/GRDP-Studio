import test from 'node:test'
import assert from 'node:assert/strict'
import { assertNoSpreadsheetFormulaCells } from './spreadsheetImport.js'

const XLSX = {
  utils: {
    decode_range: () => ({ s: { r: 0, c: 0 }, e: { r: 1, c: 1 } }),
    encode_cell: ({ r, c }) => `${String.fromCharCode(65 + c)}${r + 1}`
  }
}

test('rejects formulas even when Excel stores a cached numeric result', () => {
  const sheet = {
    '!ref': 'A1:B2',
    A1: { t: 's', v: '日期' },
    B1: { t: 's', v: '实测静压' },
    A2: { t: 'n', v: 45292, f: 'DATE(2024,1,1)' },
    B2: { t: 'n', v: 20.5 }
  }

  assert.throws(() => assertNoSpreadsheetFormulaCells(XLSX, sheet), /A2.*公式/)
})

test('rejects shared formulas and Excel error cells', () => {
  assert.throws(() => assertNoSpreadsheetFormulaCells(XLSX, {
    '!ref': 'A1:B2', B2: { t: 'n', v: 20.5, F: 'B2:B3' }
  }), /B2.*公式/)

  assert.throws(() => assertNoSpreadsheetFormulaCells(XLSX, {
    '!ref': 'A1:B2', A2: { t: 'e', v: 42 }
  }), /A2.*错误值/)
})

test('accepts a non-empty sheet containing literal values only', () => {
  assert.doesNotThrow(() => assertNoSpreadsheetFormulaCells(XLSX, {
    '!ref': 'A1:B2', A1: { t: 's', v: '井号' }, B1: { t: 's', v: '日期' },
    A2: { t: 's', v: 'X-1' }, B2: { t: 'n', v: 45292 }
  }))
})
