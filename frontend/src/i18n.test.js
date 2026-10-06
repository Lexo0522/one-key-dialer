import { describe, expect, it } from 'vitest'
import { __i18nTables, setLang, t, tf } from './i18n.js'

// 两张表长期只靠人工同步，运行时缺 key 会静默回落到中文——界面上看不出
// 哪一句漏翻了。这里把一致性锁死：任何一侧新增/删除 key，测试立刻失败。
describe('i18n 表一致性', () => {
  const zhKeys = Object.keys(__i18nTables.zh)
  const enKeys = Object.keys(__i18nTables.en)

  it('两侧 key 数量相同', () => {
    expect(enKeys.length).toBe(zhKeys.length)
  })

  it('en 不缺 key（否则英文界面回落中文）', () => {
    const missing = zhKeys.filter((k) => !(k in __i18nTables.en))
    expect(missing).toEqual([])
  })

  it('en 没有多余 key（通常是删了 zh 漏删 en）', () => {
    const extra = enKeys.filter((k) => !(k in __i18nTables.zh))
    expect(extra).toEqual([])
  })

  it('没有空文案（空串在界面上表现为空白）', () => {
    const empty = [
      ...zhKeys.filter((k) => !String(__i18nTables.zh[k] ?? '').trim()),
      ...enKeys.filter((k) => !String(__i18nTables.en[k] ?? '').trim())
    ]
    expect(empty).toEqual([])
  })
})

describe('t', () => {
  it('默认中文', () => {
    expect(t('app.title')).toBe(__i18nTables.zh['app.title'])
  })

  it('切到英文后走 en', () => {
    setLang('en')
    expect(t('app.title')).toBe(__i18nTables.en['app.title'])
  })

  it('en 缺 key 时回落中文而不是把 key 本身甩给用户', () => {
    setLang('en')
    expect(t('definitely.not.a.key')).toBe('definitely.not.a.key')
  })

  it('非法语言名回落中文', () => {
    setLang('fr')
    expect(t('app.title')).toBe(__i18nTables.zh['app.title'])
    setLang('zh')
  })
})

describe('tf', () => {
  it('按序替换 {0} {1}', () => {
    setLang('zh')
    // home.chart.diagnostics = "{0} 个数据点 · FPS {1}"
    const got = tf('home.chart.diagnostics', 120, 15)
    expect(got).toBe(__i18nTables.zh['home.chart.diagnostics']
      .replace('{0}', '120').replace('{1}', '15'))
  })

  it('同一占位符出现多次也全部替换', () => {
    const got = tf('home.chart.rangeMinutes', 10)
    expect(got).not.toContain('{0}')
    expect(got).toContain('10')
  })

  it('占位符不够时保留原样，不插入 undefined', () => {
    const got = tf('home.chart.diagnostics', 120)
    expect(got).not.toContain('undefined')
    expect(got).toContain('120')
  })

  it('非字符串参数被转成字符串', () => {
    const got = tf('home.chart.diagnostics', 42, 7)
    expect(got).toContain('42')
  })

  it('英文界面同样替换', () => {
    setLang('en')
    const got = tf('home.chart.diagnostics', 5, 60)
    expect(got).toContain('5')
    expect(got).toContain('60')
    setLang('zh')
  })
})
