import { describe, expect, it } from 'vitest'
import { PORTAL_PRESETS, getPreset } from './portalPresets.js'

// WifiTab 依赖 getPreset「永远不返回 undefined」，这里把该契约与预设的
// 字段完整性一起锁住。
describe('getPreset', () => {
  it('按 key 命中', () => {
    expect(getPreset('srun').key).toBe('srun')
    expect(getPreset('drcom').fill.method).toBe('POST')
  })

  it('未知 key 回落 generic，不返回 undefined', () => {
    expect(getPreset('nope')).toBeDefined()
    expect(getPreset('nope').key).toBe('generic')
  })

  it('空 / 非法输入也回落', () => {
    expect(getPreset('').key).toBe('generic')
    expect(getPreset(undefined).key).toBe('generic')
    expect(getPreset(null).key).toBe('generic')
  })
})

describe('PORTAL_PRESETS 字段完整性', () => {
  it('每个预设都带齐表单字段', () => {
    for (const p of PORTAL_PRESETS) {
      for (const f of ['loginUrl', 'method', 'body', 'headers', 'successHint']) {
        expect(p.fill, `${p.key} 缺 ${f}`).toHaveProperty(f)
      }
    }
  })

  it('key 唯一，否则下拉选中项会错乱', () => {
    const keys = PORTAL_PRESETS.map((p) => p.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('generic 在首位（回落后端用它作为初始态）', () => {
    expect(PORTAL_PRESETS[0].key).toBe('generic')
  })
})
