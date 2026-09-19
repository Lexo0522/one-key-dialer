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
  history: 'app:history',
  accounts: 'app:accounts',
  settings: 'app:settings',
  diag: 'app:diag',
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
    scheduledDial: false,
    scheduledDialHour: 8,
    scheduledDialMinute: 0,
    scheduledDisconnect: false,
    scheduledDisconnectHour: 23,
    scheduledDisconnectMinute: 0,
    probeMode: 'auto',
    probeHost: '223.5.5.5',
    probeHttpUrl: 'http://connectivitycheck.gstatic.com/generate_204',
    probeAttempts: 3,
    probeDelayMs: 1000,
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
  history: [
    {
      time: '2026-09-19 20:41:02',
      operation: '拨号',
      account: '20210001',
      result: '成功',
      duration: '01:23:45',
      traffic: '1.2 GB'
    },
    {
      time: '2026-09-19 18:02:11',
      operation: '断开',
      account: '20210001',
      result: '完成',
      duration: '—',
      traffic: '—'
    }
  ],
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
let mockOnline = false

function mockLog(message, level = 'info') {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  mockBus.emit(EV.log, {
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`,
    level,
    message
  })
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
    mockAccounts = rows.map((r) => ({ ...r }))
    mockBus.emit(EV.accounts, { accounts: mockAccounts.map((a) => ({ ...a })), currentIndex: mockSettings.accountIndex })
  },
  SwitchAccount(index) {
    mockSettings.accountIndex = index
    mockLog(`已切换账号: ${mockAccounts[index]?.name || ''}`)
    mockBus.emit(EV.accounts, { accounts: mockAccounts.map((a) => ({ ...a })), currentIndex: index })
  },
  UpdateHomeFields() {},
  async ExportAccounts() {
    mockLog('导出成功！', 'success')
    return 'pppoe_accounts_export.csv'
  },
  async ImportAccounts() {
    return 0
  },
  Dial() {
    mockLog('连接中…')
    setTimeout(() => {
      mockOnline = true
      mockBus.emit(EV.status, { online: true, phase: 'connected' })
      mockLog('拨号成功！', 'success')
      mockBus.emit(EV.speed, { down: 1048576, up: 131072 })
      mockBus.emit(EV.uptime, 0)
    }, 900)
  },
  Disconnect() {
    mockOnline = false
    mockBus.emit(EV.status, { online: false, phase: 'disconnected' })
    mockBus.emit(EV.speed, { down: 0, up: 0 })
    mockLog('网络已断开')
  },
  async GetHistory() {
    return MOCK_STATE.history.slice()
  },
  ClearHistory() {
    mockBus.emit(EV.history, null)
  },
  async ExportHistory() {
    mockLog('历史记录已导出: pppoe_history_export.csv', 'success')
    return 'pppoe_history_export.csv'
  },
  async GetStats() {
    return {
      TotalOps: 12,
      DialAttempts: 8,
      DialSuccess: 6,
      DialFail: 2,
      Disconnects: 4,
      TopErrors: [{ Result: '691', Count: 2 }],
      ReportText:
        '拨号次数: 8\n成功: 6\n失败: 2\n断开: 4\n成功率: 75.0%\n\n常见失败:\n  691 × 2'
    }
  },
  async TestConnectivity() {
    mockLog('外网探测…')
    return { ok: true, line: 'auto: ping 223.5.5.5 ok (12ms)', mode: 'auto', error: '' }
  },
  async GetProbeSummary() {
    return `mode=auto host=${mockSettings.probeHost} attempts=${mockSettings.probeAttempts} delay=${mockSettings.probeDelayMs}ms`
  },
  async DiagAction(action) {
    mockLog(`执行命令: ${action}`)
    const lines = ['═══════════════════════════════════════', '执行命令: ' + action, '']
    let i = 0
    const timer = setInterval(() => {
      if (i >= lines.length) {
        mockBus.emit(EV.diag, '\n═══════════════════════════════════════\n执行完毕\n')
        clearInterval(timer)
        return
      }
      mockBus.emit(EV.diag, lines[i++] + '\n')
    }, 220)
    return true
  },
  async DiagListDevices() {
    return [
      { port: 'PPPoE5-0', device: 'WAN Miniport (PPPOE)', existing: true, default: false },
      { port: 'PPPoE1-0', device: 'Realtek PCIe GbE', existing: false, default: true }
    ]
  },
  async DiagSelectDevice(port, device, rewrite) {
    return `已记住设备 ${device} / ${port}（下次创建条目时使用）`
  },
  async DiagRewritePhonebook() {
    return '电话簿条目已重写 → WAN Miniport (PPPOE) / PPPoE5-0'
  },
  DiagClear() {},
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
