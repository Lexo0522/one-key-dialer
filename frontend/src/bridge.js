// 后端桥接层：Wails 环境下走真实绑定，浏览器 dev 模式下走内存 mock，
// 便于脱离 Windows 环境做界面校验。
// 绑定名来自 UI 进程绑定的 UIApp 结构（方法名与代理侧 App 一致）。
// @ts-check
import * as AppApi from '../wailsjs/go/main/UIApp.js'
import { EventsOn } from '../wailsjs/runtime/runtime.js'
/**
 * Settings 的字段契约。
 *
 * 本该从 wailsjs/go/models.ts 的 main.Settings 导入，但 TS 在 allowJs 下
 * 解析 .ts 里的 `export namespace` 嵌套成员有缺陷（TS2694），实测三条
 * moduleResolution 都一样，所以这里手写一份等价的 typedef。
 *
 * 代价是多一处要同步；收益是后端改字段名/删字段时，下面的 MOCK_SETTINGS
 * 仍然会在 tsc 报错——mock 与真实绑定契约的一致性仍然有人看守。
 * 后端动 Settings 时（internal/model/settings.go）记得同步这里。
 *
 * @typedef {Object} Settings
 * @property {number} intervalSeconds
 * @property {boolean} autoReconnect
 * @property {boolean} autoStart
 * @property {boolean} startMinimized
 * @property {boolean} disconnectOnNoInternet
 * @property {boolean} updateCheckEnabled
 * @property {boolean} autoInstallUpdate
 * @property {string} uiTheme
 * @property {string} pppoePort
 * @property {string} pppoeDevice
 * @property {boolean} proxyEnabled
 * @property {boolean} wifiAutoConnect
 * @property {string} wifiPreferredSsid
 * @property {boolean} portalAuthEnabled
 * @property {string} portalPreset
 * @property {string} portalLoginUrl
 * @property {string} portalMethod
 * @property {string} portalBody
 * @property {string} portalHeaders
 * @property {string} portalSuccessHint
 * @property {string} portalPreset
 * @property {boolean} lowMemRender
 * @property {boolean} proxyEnabled
 * @property {string} proxyType
 * @property {string} proxyHost
 * @property {string} proxyPort
 * @property {string} proxyBypass
 * @property {?Array<*>} speedSites
 */

/**
 * Wails 运行时注入的全局对象。浏览器 dev 下不存在，所以各处都要判空。
 * 显式走 window 取值再判定，代码形状不变，交给 ts-check 时不再报
 * "Property 'go' does not exist"——重点是让类型检查覆盖到真正的契约上。
 */
const wailsWindow = /** @type {any} */ (window)

export const isWails = typeof window !== 'undefined' && !!wailsWindow.go && !!wailsWindow.runtime

// 事件名（与 Go 侧常量保持一致）
export const EV = {
  log: 'app:log',
  status: 'app:status',
  speed: 'app:speed',
  uptime: 'app:uptime',
  settings: 'app:settings',
  update: 'app:update',
  notify: 'app:notify',
  lang: 'app:lang',
  wifi: 'app:wifi',
  dial: 'app:dial',
  diag: 'app:diag'
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

/** @type {Settings} */
const MOCK_SETTINGS = {
  intervalSeconds: 30,
  autoReconnect: false,
  autoStart: false,
  startMinimized: false,
  disconnectOnNoInternet: false,
  updateCheckEnabled: true,
  autoInstallUpdate: true,
  uiTheme: 'system',
  wifiAutoConnect: false,
  wifiPreferredSsid: '',
  portalAuthEnabled: false,
  portalPreset: 'generic',
  portalLoginUrl: '',
  portalMethod: 'POST',
  portalBody: '',
  portalHeaders: '',
  portalSuccessHint: '',
  speedSites: null,
  pppoePort: '',
  pppoeDevice: '',
  proxyEnabled: false,
  proxyType: 'system',
  proxyHost: '',
  proxyPort: '',
  proxyBypass: '',
  lowMemRender: false
}

const MOCK_STATE = {
  version: '1.2.1',
  displayVersion: 'v1.2.1',
  settings: MOCK_SETTINGS,
  broadband: { username: '20210001', hasPassword: true },
  online: false,
  logs: [
    { time: '20:41:02', level: 'info', message: 'PPPoE校园网拨号工具 V1.2.0 已启动' },
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
// mock 侧保存的宽带密码（真实后端永不把明文密码发给前端）
let mockBroadband = { ...MOCK_STATE.broadband }
let mockBroadbandPassword = '123456'
let mockOnline = false
let mockConnectedAt = 0
let mockSpeedTimer = null
let mockDiagBusy = false

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
    return { ...MOCK_STATE, settings: { ...mockSettings }, broadband: { ...mockBroadband } }
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
  async GetBroadband() {
    return { ...mockBroadband }
  },
  SaveBroadband(username, password) {
    if (username) mockBroadband.username = username
    if (password) {
      mockBroadbandPassword = password
      mockBroadband.hasPassword = true
    }
    mockLog('宽带账号已保存', 'success')
    return true
  },
  Dial() {
    if (!mockBroadband.hasPassword || !mockBroadband.username) {
      const body = '尚未设置宽带账号或密码，请先在「宽带」页保存'
      mockLog(body, 'warn')
      mockBus.emit(EV.notify, { title: '拨号失败', body, tone: 'error' })
      return false
    }
    mockLog('连接中…')
    mockConnectedAt = Date.now()
    setTimeout(() => {
      mockOnline = true
      mockBus.emit(EV.status, { online: true, phase: 'connected' })
      mockLog('拨号成功！', 'success')
      mockBus.emit(EV.notify, { title: '连接成功', body: '已连接到校园网', tone: 'success' })
      mockBus.emit(EV.dial, { ok: true, code: 0, detail: '连通 icmp 12ms src=post-dial', at: Date.now() })
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
    const cur = mockSettings.pppoePort || 'PPPoE5-0'
    const curDev = mockSettings.pppoeDevice || 'WAN Miniport (PPPOE)'
    const list = [
      { port: 'PPPoE5-0', device: 'WAN Miniport (PPPOE)', existing: true, default: true },
      { port: 'PPPoE1-0', device: 'Realtek PCIe GbE', existing: true, default: false }
    ]
    if (!list.some((x) => x.port === cur)) list.push({ port: cur, device: curDev, existing: true, default: false })
    return list.map((x) => ({ ...x, current: x.port === cur && x.device === curDev }))
  },
  async DiagSelectDevice(port, device, rewrite) {
    mockSettings.pppoePort = port
    mockSettings.pppoeDevice = device
    mockBus.emit(EV.settings, { ...mockSettings })
    return rewrite
      ? `电话簿条目已重写 → ${device} / ${port}`
      : `已记住设备 ${device} / ${port}（下次创建条目时使用）`
  },
  CheckUpdate(interactive) {
    mockBus.emit(EV.update, { kind: 'checking', message: '正在检查更新…', interactive: !!interactive })
    setTimeout(() => {
      // 与后端一致：有新版与无新版都要回 result，前端靠它复位 checking 态；
      // interactive 决定无新版时是否弹提示（静默检查不打扰用户）。
      mockBus.emit(EV.update, {
        kind: 'result',
        interactive: !!interactive,
        updateAvailable: true,
        canInstall: true,
        // 与后端字段对应：message = 生成的状态行，body = 渠道发布说明正文
        message: '发现新版本 1.2.1（当前 1.2.0，线路 Gitee）\n可下载: PPPoEDialer-1.2.1-windows.zip',
        body: '## 1.2.1\n\n- 修复：托盘左键误弹菜单\n- 新增：网站测速与 IP 信息卡片',
        assetName: 'PPoEDialer-1.2.1-windows.zip',
        assetSize: 12 * 1024 * 1024,
        releaseUrl: 'https://example.invalid/releases/latest'
      })
    }, 800)
  },
  DownloadUpdate() {
    mockBus.emit(EV.update, { kind: 'status', message: '准备下载…' })
    // 与后端一致：下载过程推 progress，校验通过后推 done，
    // 前端据此进入「安装」态（自动安装开启时无需再点一次）
    const total = 12 * 1024 * 1024
    let done = 0
    const timer = setInterval(() => {
      done = Math.min(total, done + total / 8)
      mockBus.emit(EV.update, {
        kind: 'progress',
        stage: 'download',
        downloaded: Math.round(done),
        total
      })
      if (done >= total) {
        clearInterval(timer)
        mockBus.emit(EV.update, {
          kind: 'done',
          stage: 'download',
          path: 'C:\\Users\\mock\\AppData\\Roaming\\PPoEDialer\\updates\\PPoEDialer-1.2.1-windows.zip'
        })
      }
    }, 250)
  },
  CancelUpdateDownload() {
    mockBus.emit(EV.update, { kind: 'canceled', stage: 'download', message: '已取消下载' })
  },
  InstallUpdate() {
    mockBus.emit(EV.update, { kind: 'installing', stage: 'install', message: '正在安装更新…' })
  },
  ShowWindow() {},
  HideWindow() {},
  async IsWindowVisible() {
    return true
  },
  ExitProgram() {},
  async UpdateBusy() {
    return false
  },
  // 语言：'' / 'auto' / 'system' 表示跟随系统，'zh' / 'en' 为显式覆盖
  async SetUILang(lang) {
    return lang || 'zh'
  },
  async GetUILang() {
    return { lang: 'zh', system: 'zh', auto: true }
  },

  // ---------------- WiFi / 门户认证（浏览器 dev mock） ----------------
  async WifiAvailable() {
    return true
  },
  async WifiStatus() {
    return {
      available: true,
      connected: mockWifi.connected,
      ssid: mockWifi.ssid,
      signalQuality: mockWifi.signal,
      // phase 只是 OS 瞬时阶段，仅用于展示；busy 才是按钮可用性依据
      phase: mockWifi.phase,
      busy: mockWifi.busy,
      autoConnect: !!mockSettings.wifiAutoConnect,
      preferredSsid: mockSettings.wifiPreferredSsid || ''
    }
  },
  async WifiScan(force) {
    if (mockWifi.scanBusy) return []
    mockWifi.scanBusy = true
    setTimeout(() => (mockWifi.scanBusy = false), 600)
    return mockWifi.networks.map((n) => ({ ...n }))
  },
  WifiConnect(ssid, password) {
    if (mockWifi.busy) return false
    mockLog(`正在连接 WiFi: ${ssid}`)
    mockWifi.busy = true
    mockBus.emit(mockWifiPayload('connecting', ssid, 0))
    setTimeout(() => {
      mockWifi.connected = true
      mockWifi.ssid = ssid
      mockWifi.signal = 76
      mockWifi.phase = 'connected'
      mockWifi.busy = false
      if (password) mockLog(`已保存 WiFi 密码: ${ssid}`, 'success')
      mockLog(`已连接 WiFi: ${ssid}`, 'success')
      mockBus.emit(mockWifiPayload('connected', ssid, 76))
    }, 1500)
    return true
  },
  WifiDisconnect() {
    if (mockWifi.busy) return false
    mockWifi.busy = true
    mockWifi.phase = 'disconnecting'
    mockLog('正在断开 WiFi')
    mockBus.emit(mockWifiPayload('disconnecting', mockWifi.ssid, mockWifi.signal))
    setTimeout(() => {
      mockWifi.connected = false
      mockWifi.ssid = ''
      mockWifi.phase = 'idle'
      mockWifi.busy = false
      mockLog('已断开 WiFi')
      mockBus.emit(mockWifiPayload('idle', '', 0))
    }, 600)
    return true
  },
  async GetPortalCredential() {
    return { username: mockPortal.username, hasPassword: !!mockPortal.password }
  },
  SavePortalCredential(username, password) {
    if (username) mockPortal.username = username
    if (password) mockPortal.password = password
    mockLog('认证账号已保存', 'success')
    return true
  },
  async TestPortalAuth() {
    await new Promise((r) => setTimeout(r, 1200))
    if (!mockSettings.portalLoginUrl) {
      return { ok: false, detail: '未检测到认证门户，当前网络无需认证' }
    }
    return { ok: true, detail: '检测到认证门户: http://10.1.1.55\n认证提交: HTTP 200 | login_ok\n测试通过：门户已放行' }
  },

  // ---------------- 网站测速 / IP 信息（浏览器 dev mock） ----------------
  async SiteLatencyCheck(urls) {
    const latency = {
      'https://www.bilibili.com': 23,
      'https://www.baidu.com': 12,
      'https://github.com': 189,
      'https://www.bing.com': 45,
      'https://www.douyin.com': 67,
      'https://www.google.com': 88
    }
    await new Promise((r) => setTimeout(r, 600 + Math.random() * 500))
    return (Array.isArray(urls) ? urls : []).map((url) => ({
      url,
      latencyMs: latency[url] !== undefined ? latency[url] : 40 + Math.floor(Math.random() * 200)
    }))
  },
  async GetIPInfo() {
    await new Promise((r) => setTimeout(r, 500 + Math.random() * 500))
    return {
      ip: '203.0.113.7',
      country: '中国',
      regionName: '广东',
      city: '深圳',
      isp: '中国电信',
      as: 'AS4134',
      timezone: 'Asia/Shanghai',
      viaProxy: false,
      localIp: '10.16.8.66'
    }
  },

  // ---------------- 宽带页：诊断 / 连接详情（浏览器 dev mock） ----------------
  ClearBroadband() {
    mockBroadband = { username: '', hasPassword: false }
    mockBroadbandPassword = ''
    mockLog('已清除宽带账号与密码', 'success')
    return true
  },
  async PppStats() {
    if (!mockOnline) return { available: true, connected: false }
    return {
      available: true,
      connected: true,
      localIp: '10.16.8.66',
      serverIp: '10.16.0.1',
      bps: 1000 * 1000 * 1000,
      bytesUp: 182536110,
      bytesDown: 1024753902,
      errTotal: 0,
      durationSec: Math.max(1, Math.floor((Date.now() - mockConnectedAt) / 1000))
    }
  },
  async EthLinks() {
    return [
      { descr: 'Realtek PCIe GbE Family Controller', up: true, speedMbps: 1000 },
      { descr: 'Intel(R) I211 Gigabit Network Connection', up: false, speedMbps: 0 }
    ]
  },
  DiagRun() {
    if (mockDiagBusy) return false
    mockDiagBusy = true
    const credOk = !!mockBroadband.username && mockBroadband.hasPassword
    const steps = [
      { ok: credOk, text: credOk ? `宽带凭据已配置（${mockBroadband.username}）` : '宽带凭据未配置，请先在宽带页填写账号密码' },
      { ok: true, text: '电话簿条目正常（WAN Miniport (PPPOE) / PPPoE5-0）' },
      { ok: true, text: '物理网口 2 个，其中 1 个已连接' },
      mockOnline
        ? { ok: true, text: '外网探测: 连通 icmp 12ms src=diag' }
        : { ok: true, text: '当前离线，跳过外网探测' }
    ]
    const emit = (p) => mockBus.emit(EV.diag, p)
    let i = 0
    const tick = () => {
      if (i < steps.length) {
        emit({ phase: 'step', index: i + 1, ok: steps[i].ok, text: steps[i].text })
        i++
        setTimeout(tick, 400)
        return
      }
      const okAll = steps.every((s) => s.ok)
      emit({ phase: 'done', ok: okAll, text: okAll ? '诊断完成：未发现问题' : '诊断完成：发现以下问题',
             detail: steps.map((s) => s.text).join('\n') })
      mockDiagBusy = false
    }
    setTimeout(tick, 250)
    return true
  }
}

// mock 的 WiFi / 门户状态
const mockWifi = {
  connected: false,
  ssid: '',
  signal: 0,
  phase: 'idle',
  // busy：仅本应用发起的流程为真，与后端 wifiStatus().busy 同义
  busy: false,
  scanBusy: false,
  networks: [
    { ssid: 'Campus-WiFi', signalQuality: 82, secured: false, connected: false, hasProfile: false, auth: 'Open' },
    { ssid: 'Campus-5G', signalQuality: 64, secured: true, connected: false, hasProfile: true, auth: 'WPA2-PSK' },
    { ssid: 'Dorm-2F', signalQuality: 40, secured: true, connected: false, hasProfile: false, auth: 'WPA2-PSK' }
  ]
}

/** 组一条 app:wifi 事件负载（字段与后端 WifiStatusDTO 一致）。 */
function mockWifiPayload(phase, ssid, signalQuality) {
  return {
    available: true,
    connected: phase === 'connected',
    ssid,
    signalQuality,
    phase,
    busy: mockWifi.busy,
    autoConnect: !!mockSettings.wifiAutoConnect,
    preferredSsid: mockSettings.wifiPreferredSsid || ''
  }
}

const mockPortal = { username: '20210001', password: '123456' }

/** 统一出口：Wails 或 mock。 */
export const api = isWails ? AppApi : mockApi
