import { describe, expect, it } from 'vitest'
import { __i18nTables } from './i18n.js'
import { wifiPhaseView } from './wifiStatus.js'

// 副标题判定：busy（本应用发起的流程）与 phase（OS 接口瞬时相位）必须分开。
// 混为一谈的后果就是用户什么都没点，状态卡却显示「连接中…」。
describe('wifiPhaseView', () => {
  const cases = [
    // 已连接：副标题其实走信号强度那一行，这里只要 key 别跑偏
    ['已连接', { connected: true, phase: 'connected' }, 'wifi.status.connected', false],
    // 本应用正在连接（受理瞬间 OS 相位可能还是 idle）
    ['本应用连接中', { busy: true, phase: 'idle' }, 'wifi.status.connecting', false],
    ['本应用连接中(OS 已进 connecting)', { busy: true, phase: 'connecting' }, 'wifi.status.connecting', false],
    ['本应用断开中', { busy: true, phase: 'disconnecting' }, 'wifi.status.disconnecting', false],
    // 系统网卡自发重连：用户没点任何按钮
    ['系统自发连接中', { phase: 'connecting' }, 'wifi.status.osConnecting', true],
    ['系统自发断开中', { phase: 'disconnecting' }, 'wifi.status.osDisconnecting', true],
    // 稳态
    ['未连接', { phase: 'idle' }, 'wifi.status.idle', false],
    ['未知相位', { phase: 'unknown' }, 'wifi.status.idle', false],
    ['空状态', {}, 'wifi.status.idle', false]
  ]
  for (const [name, status, wantKey, wantTrying] of cases) {
    it(name, () => {
      const got = wifiPhaseView(status)
      expect(got.key).toBe(wantKey)
      expect(got.trying).toBe(wantTrying)
    })
  }

  it('空 / null / undefined 不炸', () => {
    for (const v of [null, undefined]) {
      expect(wifiPhaseView(v).key).toBe('wifi.status.idle')
    }
  })

  it('返回的 key 在中英文案表里都存在（缺 key 会把 key 本身甩给用户）', () => {
    const keys = cases.map(([, , k]) => k)
    for (const k of new Set(keys)) {
      expect(String(__i18nTables.zh[k] ?? '').trim(), `zh 缺 ${k}`).not.toBe('')
      expect(String(__i18nTables.en[k] ?? '').trim(), `en 缺 ${k}`).not.toBe('')
    }
  })
})
