// 后端桥接层：Wails 环境下走真实绑定，浏览器 dev 模式下走内存 mock，
// 便于脱离 Windows 环境做界面校验。
import * as AppApi from '../wailsjs/go/main/App.js'
import { EventsOn } from '../wailsjs/runtime/runtime.js'

export const isWails = typeof window !== 'undefined' && !!window.go && !!window.runtime

// 事件名（与 Go 侧常量保持一致）
export const EV = {
  log: 'app:log',
  status: 'app:status',
  speed: 'app:speed',
  uptime: 'app:uptime',
  accounts: 'app:accounts',
  settings: 'app:settings',
  update: 'app:update',
  notify: 'app:notify'
}

/** 订阅后端事件；返回取消订阅函数。 */
export function on(event, handler) {
  if (!isWails) {
    mockBus.on(event, handler)
    return () => mockBus.off(event, handler)
  }
  EventsOn(event, handler)
  return () => {}
}

// ---------------------------------------------------------------- mock ----

const MOCK_STATE = {
  version: '1.1.11',
  displayVersion: 'V1.1.11',
  settings: {
    intervalSeconds: 30,
    autoReconnect: false,
    autoStart: false,
    startMinimized: false,
    accountIndex: 0,
    disconnectOnNoInternet: false,
    updateCheckEnabled: true,
    uiTheme: 'system'
  },
  accounts: [
    { name: '默认账号', username: '', remark: '', hasPassword: false },
    { name: '宿舍宽带', username: '20210001', remark: '主用', hasPassword: true }
  ],
  currentIndex: 0,
  online: false,
  logs: [
    { time: '20:41:02', level: 'info', message: 'PPPoE校园网拨号工具 V1.1.11 已启动' },
    { time: '20:41:03', level: 'success', message: '拨号成功！' }
  ],
  autoStartEnabled: false,
  theme: 'light',
  lang: 'zh',
  dataDir: 'C:\\Users\\Xing\\AppData\\Roaming\\PPoEDialer',
  updatesDir: 'C:\\Users\\Xing\\AppData\\Roaming\\PPoEDialer\\updates'
}

class Bus {
  constructor() {
    this.map = new Map()
  }

  on(name, fn) {
    if (!this.map.has(name)) this.map.set(name, new Set())
    this.map.get(name).add(fn)
  }

  off(name, fn) {
    const s = this.map.get(name)
    if (s) s.delete(fn)
  }

  emit(name, payload) {
    const s = this.map.get(name)
    if (s) for (const fn of s) fn(payload)
  }
}

const mockBus = new Bus()
let mockSettings = { ...MOCK_STATE.settings }
let mockAccounts = MOCK_STATE.accounts.map((a) => ({ ...a }))
// mock 侧按账号名保存的密码（真实后端永不把明文密码发给前端）
let mockPasswords = new Map()
if (MOCK_STATE.accounts[1]) mockPasswords.set(MOCK_STATE.accounts[1].username, '123456')
let mockOnline = false
let mockConnectedAt = 0
let mockSpeedTimer = null

function mockLog(message, level = 'info') {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  mockBus.emit(EV.log, {
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`,
    level,
    message
  })
}

/** 连接期间以 1s 节流模拟后端速率事件，供首页折线图/统计卡联调。 */
function startMockSpeed() {
  stopMockSpeed()
  mockSpeedTimer = setInterval(() => {
    if (!mockOnline) return
    const wave = (base, amp) => Math.max(0, Math.round(base + (Math.random() - 0.5) * amp))
    mockBus.emit(EV.speed, { down: wave(3 * 1024 * 1024, 5 * 1024 * 1024), up: wave(512 * 1024, 700 * 1024) })
    mockBus.emit(EV.uptime, Math.floor((Date.now() - mockConnectedAt) / 1000))
  }, 1000)
}

function stopMockSpeed() {
  if (mockSpeedTimer) {
    clearInterval(mockSpeedTimer)
    mockSpeedTimer = null
  }
}

const mockApi = {
  async Bootstrap() {
    return { ...MOCK_STATE, settings: { ...mockSettings }, accounts: mockAccounts.map((a) => ({ ...a })) }
  },
  SaveSettings(next) {
    mockSettings = { ...next }
    mockBus.emit(EV.settings, { ...mockSettings })
  },
  SetAutoStart(enabled) {
    mockSettings.autoStart = enabled
    mockBus.emit(EV.settings, { ...mockSettings })
    return true
  },
  async GetAccounts() {
    return mockAccounts.map((a) => ({ ...a }))
  },
  SaveAccounts(rows) {
    // 复现后端语义：快照不含明文；空密码 + hasPassword 时按账号名继承
    const next = rows.map((r) => {
      const key = (r.username || '').trim()
      let pw = (r.password || '').trim()
      if (!pw && r.hasPassword) pw = mockPasswords.get(key) || ''
      if (pw) mockPasswords.set(key, pw)
      else mockPasswords.delete(key)
      return {
        name: r.name || '',
        username: r.username || '',
        remark: r.remark || '',
        hasPassword: !!pw
      }
    })
    mockAccounts = next
    mockBus.emit(EV.accounts, { accounts: mockAccounts.map((a) => ({ ...a })), currentIndex: mockSettings.accountIndex })
  },
  SwitchAccount(index) {
    mockSettings.accountIndex = index
    mockLog(`已切换账号: ${mockAccounts[index]?.name || ''}`)
    mockBus.emit(EV.accounts, { accounts: mockAccounts.map((a) => ({ ...a })), currentIndex: index })
  },
  async ExportAccounts() {
    mockLog('导出成功！', 'success')
    return 'pppoe_accounts_export.csv'
  },
  async ImportAccounts() {
    // 复现后端语义：成功返回导入条数（-1 为用户取消），并推送账号变更
    mockAccounts = mockAccounts.concat([
      { name: '导入账号', username: '20220002', remark: 'CSV', hasPassword: false }
    ])
    mockBus.emit(EV.accounts, { accounts: mockAccounts.map((a) => ({ ...a })), currentIndex: mockSettings.accountIndex })
    mockLog('导入成功！', 'success')
    return 1
  },
  DialCurrentAccount() {
    const acc = mockAccounts[mockSettings.accountIndex]
    if (acc && !acc.hasPassword) {
      mockLog('当前账号未设置密码，请先在账号配置中保存密码', 'warn')
      mockBus.emit(EV.notify, { title: '拨号失败', body: '当前账号未设置密码，请先在账号配置中保存密码', tone: 'error' })
      return false
    }
    mockLog('连接中…')
    mockConnectedAt = Date.now()
    setTimeout(() => {
      mockOnline = true
      mockBus.emit(EV.status, { online: true, phase: 'connected' })
      mockLog('拨号成功！', 'success')
      mockBus.emit(EV.notify, { title: '连接成功', body: '已连接到校园网', tone: 'success' })
      startMockSpeed()
    }, 900)
    return true
  },
  Disconnect() {
    mockOnline = false
    stopMockSpeed()
    mockBus.emit(EV.status, { online: false, phase: 'disconnected' })
    mockBus.emit(EV.speed, { down: 0, up: 0 })
    mockBus.emit(EV.uptime, 0)
    mockLog('网络已断开')
    mockBus.emit(EV.notify, { title: '已断开', body: '网络连接已断开', tone: 'info' })
    return true
  },
  async DiagListDevices() {
    return [
      { port: 'PPPoE5-0', device: 'WAN Miniport (PPPOE)', existing: true, default: false },
      { port: 'PPPoE1-0', device: 'Realtek PCIe GbE', existing: false, default: true }
    ]
  },
  async DiagSelectDevice(port, device, rewrite) {
    return rewrite
      ? `电话簿条目已重写 → ${device} / ${port}`
      : `已记住设备 ${device} / ${port}（下次创建条目时使用）`
  },
  CheckUpdate() {
    mockBus.emit(EV.update, { kind: 'checking', message: '正在检查更新…' })
    setTimeout(() => {
      mockBus.emit(EV.update, {
        kind: 'result',
        updateAvailable: true,
        canInstall: true,
        title: '发现新版本 V1.2.0',
        body: '当前 V1.1.11',
        assetName: 'PPoEDialer-1.2.0.zip',
        assetSize: 12 * 1024 * 1024,
        releaseUrl: 'https://example.invalid/releases/latest'
      })
    }, 800)
  },
  DownloadUpdate() {
    mockBus.emit(EV.update, { kind: 'status', message: '准备下载…' })
  },
  CancelUpdateDownload() {},
  InstallUpdate() {},
  OpenReleasePage() {},
  ShowWindow() {},
  HideWindow() {},
  async IsWindowVisible() {
    return true
  },
  ExitProgram() {},
  async UpdateBusy() {
    return false
  }
}

/** 统一出口：Wails 或 mock。 */
export const api = isWails ? AppApi : mockApi
