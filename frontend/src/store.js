// 全局响应式状态：Bootstrap 拉首帧，之后靠后端事件增量更新。
import { reactive, watch } from 'vue'
import { api, on, EV } from './bridge'
import { resolveTheme, applyPalette } from './theme'
import { setLang, t } from './i18n'
import { formatSpeed, formatBytes, formatDuration } from './format'
import { showToast, toastPromise } from './toast'

const PREFS_KEY = 'okd.ui.prefs.v1'
const SNIFF_WINDOW_MS = 10 * 60 * 1000 // 折线图统计窗口：10 分钟
const SNIFF_MAX_POINTS = 620

/** 本地偏好（无后端字段支持的界面设置）：语言覆盖 / 流量嗅探 / 轻量化。
 *  代理已迁移为后端设置（settings.json，随 SaveSettings 持久化并实时生效），
 *  这里只在加载时清理旧版本残留的本地代理偏好。 */
function defaultPrefs() {
  return {
    lang: '', // '' = 跟随后端探测到的语言
    sniffing: true,
    lightweight: false
  }
}

function loadPrefs() {
  const base = defaultPrefs()
  try {
    const raw = localStorage.getItem(PREFS_KEY)
    if (!raw) return base
    const saved = JSON.parse(raw)
    // 旧版本把代理存在本地偏好且不生效；丢弃残留避免与新设置混淆
    const { proxy: _legacyProxy, ...rest } = saved || {}
    return { ...base, ...rest }
  } catch (e) {
    return base
  }
}

export const state = reactive({
  ready: false,
  version: '',
  displayVersion: '',
  settings: null,
  accounts: [],
  currentIndex: 0,
  online: false,
  dialBusy: false,
  dialLabel: '',
  logs: [],
  autoStartEnabled: false,
  themePref: 'system',
  theme: 'light',
  systemLang: 'zh',
  dataDir: '',
  updatesDir: '',

  // 状态栏 / 首页
  downSpeed: 0,
  upSpeed: 0,
  uptimeSeconds: -1,

  // 流量嗅探：近 10 分钟采样序列 {t, up, down}
  samples: [],
  // 本次开启嗅探以来的会话统计（断开即清零）
  sessionUp: 0,
  sessionDown: 0,
  peakUp: 0,
  peakDown: 0,

  // WiFi：状态由后端 app:wifi 事件推送，网络列表由 WiFi 页按需拉取
  wifi: {
    available: false,
    status: { available: false, connected: false, ssid: '', signalQuality: 0, phase: 'idle' },
    networks: [],
    scannedAt: 0
  },

  // 本地偏好
  prefs: loadPrefs(),

  // 重绘节拍：轻量化模式下由 HomeTab 降低图表刷新频率
  renderTick: 0,

  // 更新
  // downloading = 传输阶段（可取消）；installing = 准备/安装阶段（不可取消）。
  // 两者不能同时为真，否则解压进度会被当成下载进度渲染。
  // stage 记录当前阶段，用于判断是否允许进度回退。
  update: {
    visible: false,
    checking: false,
    available: false,
    canInstall: false,
    busy: false,
    title: '',
    body: '',
    assetName: '',
    assetSize: 0,
    releaseUrl: '',
    progress: 0,
    downloaded: 0,
    total: 0,
    status: '',
    path: '',
    stage: '',
    downloading: false,
    installing: false
  }
})

let renderTimer = null

// 结果结算器：doDial / doDisconnect 受理操作后挂起，由后端结果事件或超时来结算，
// 驱动「进行中 → 成功/失败」的 Promise Toast 形变切换。
const OUTCOME_TIMEOUT_MS = 90 * 1000
let dialOutcome = null
let disconnectOutcome = null

/** 拨号结果 Promise：直到后端结果事件（成功/失败/无外网）到达或超时才落定。 */
function dialOutcomePromise() {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      dialOutcome = null
      reject(new Error('dial outcome timeout'))
    }, OUTCOME_TIMEOUT_MS)
    dialOutcome = { resolve, reject, timer }
  })
}

/** 断开结果 Promise：直到后端"已断开"通知（info 语气）到达或超时才落定。 */
function disconnectOutcomePromise() {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      disconnectOutcome = null
      reject(new Error('disconnect outcome timeout'))
    }, OUTCOME_TIMEOUT_MS)
    disconnectOutcome = { resolve, reject, timer }
  })
}

// Toast 入口转发（组件从 store 引入，实现统一在 toast.js）
export { showToast, toastPromise }

/** 当前生效设置（未就绪时返回 null，调用方自行判空）。 */
export function settings() {
  return state.settings
}

/** 版本号展示文案：后端 displayVersion 已带 v 前缀，这里归一化后统一加 V，
 *  避免模板里再拼一次前缀出现「Vv1.x.x」。 */
export function versionLabel() {
  const raw = String(state.displayVersion || state.version || '').trim()
  const digits = raw.replace(/^[vV]/, '')
  return digits ? 'V' + digits : ''
}

/** 把前端的语言覆盖同步给后端（托盘菜单 / 通知 / 日志文案）。
 *  '' / 'auto' / 'system' 表示跟随系统；dev mock 或旧版后端缺失该方法时静默忽略。 */
export function syncLangToBackend() {
  try {
    if (typeof api.SetUILang !== 'function') return
    // 返回 Promise，失败（旧版后端未绑定该方法）静默吞掉，避免未捕获拒绝
    Promise.resolve(api.SetUILang(state.prefs.lang || '')).catch(() => {})
  } catch (e) {
    /* 非 Wails 环境时忽略 */
  }
}

/** 图表重绘间隔（毫秒）：轻量化模式下降频。 */
export function renderIntervalMs() {
  return state.prefs.lightweight ? 4000 : 1000
}

function startRenderTicker() {
  clearInterval(renderTimer)
  renderTimer = setInterval(() => {
    state.renderTick++
  }, renderIntervalMs())
}

watch(
  () => state.prefs.lightweight,
  () => startRenderTicker()
)

function persistPrefs() {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(state.prefs))
  } catch (e) {
    /* 隐私模式等场景下静默失败 */
  }
  document.documentElement.setAttribute('data-lite', state.prefs.lightweight ? 'on' : 'off')
}

/** 合并本地偏好并持久化。 */
export function patchPrefs(patch) {
  state.prefs = { ...state.prefs, ...patch }
  persistPrefs()
}

export async function bootstrap() {
  const s = await api.Bootstrap()
  state.version = s.version
  state.displayVersion = s.displayVersion
  state.settings = s.settings
  state.accounts = s.accounts || []
  state.currentIndex = s.currentIndex
  state.online = s.online
  state.logs = s.logs || []
  state.autoStartEnabled = s.autoStartEnabled
  state.themePref = s.settings.uiTheme || 'system'
  state.dataDir = s.dataDir
  state.updatesDir = s.updatesDir
  // 语言优先级：本地覆盖 > 后端探测
  state.systemLang = s.lang || 'zh'
  setLang(state.prefs.lang || state.systemLang)
  // 前端本地保存的语言覆盖需要回传给后端，否则托盘/通知文案会停在系统语言
  syncLangToBackend()
  state.theme = s.theme && s.theme !== 'system' ? s.theme : resolveTheme(state.themePref)
  applyPalette(state.theme)
  persistPrefs()
  state.ready = true
  return s
}

// ------------------------------------------------------------- 事件挂载 ----

export function bindEvents() {
  startRenderTicker()
  on(EV.log, (line) => {
    if (!line) return
    state.logs.push(line)
    if (state.logs.length > 500) state.logs.splice(0, state.logs.length - 500)
  })

  // 系统语言在运行期变化（后端每 2s 轮询探测）：未显式指定语言时跟随切换
  on(EV.lang, (p) => {
    if (!p || !p.lang) return
    state.systemLang = p.system || p.lang
    if (!state.prefs.lang) setLang(state.systemLang)
  })

  on(EV.status, (p) => {
    if (!p) return
    state.online = !!p.online
    state.dialBusy = false
    state.dialLabel = ''
    if (!p.online) {
      state.downSpeed = 0
      state.upSpeed = 0
      state.uptimeSeconds = -1
      // 断开即结束本次嗅探会话：清空滚动窗口与会话统计
      resetSniffing()
    }
  })

  on(EV.speed, (p) => {
    if (!p) return
    state.downSpeed = p.down || 0
    state.upSpeed = p.up || 0
    if (!state.online || !state.prefs.sniffing) return
    const now = Date.now()
    state.samples.push({ t: now, up: state.upSpeed, down: state.downSpeed })
    // 丢弃窗口外样本，兜底截断长度
    const cutoff = now - SNIFF_WINDOW_MS
    while (state.samples.length && state.samples[0].t < cutoff) state.samples.shift()
    if (state.samples.length > SNIFF_MAX_POINTS) {
      state.samples.splice(0, state.samples.length - SNIFF_MAX_POINTS)
    }
    state.sessionUp += state.upSpeed
    state.sessionDown += state.downSpeed
    if (state.upSpeed > state.peakUp) state.peakUp = state.upSpeed
    if (state.downSpeed > state.peakDown) state.peakDown = state.downSpeed
  })

  on(EV.uptime, (sec) => {
    state.uptimeSeconds = typeof sec === 'number' ? sec : -1
  })

  on(EV.accounts, (p) => {
    if (!p) return
    state.accounts = p.accounts || []
    state.currentIndex = p.currentIndex || 0
  })

  on(EV.settings, (s) => {
    if (!s) return
    // patchSettings 会先乐观更新 state.settings；因此不能只比较旧 settings，
    // 否则后端回推相同设置时会漏掉主题应用。
    const themeChanged = state.themePref !== (s.uiTheme || 'system') || state.settings?.uiTheme !== s.uiTheme
    state.settings = s
    state.currentIndex = s.accountIndex
    if (themeChanged) {
      state.themePref = s.uiTheme || 'system'
      applyTheme()
    }
  })

  on(EV.notify, (p) => {
    if (!p) return
    // 拨号结果事件由 doDial 的 Promise Toast 承载（连接中 → 成功/失败形变），
    // 此处只结算、不重复弹条，避免同一次拨号出现两条提示
    if (dialOutcome && (p.tone === 'success' || p.tone === 'error' || p.tone === 'warning')) {
      const pending = dialOutcome
      dialOutcome = null
      clearTimeout(pending.timer)
      if (p.tone === 'success') pending.resolve(p)
      else pending.reject(p)
      return
    }
    // 断开结果由 doDisconnect 的 Promise Toast 承载（断开中 → 已断开形变）。
    // 当前 info 语气通知仅"已断开"一类，按语气结算是安全的
    if (disconnectOutcome && p.tone === 'info') {
      const pending = disconnectOutcome
      disconnectOutcome = null
      clearTimeout(pending.timer)
      pending.resolve(p)
      return
    }
    showToast(p.title || '', p.tone || 'info')
  })

  on(EV.update, (p) => {
    if (!p) return
    applyUpdatePayload(p)
  })

  // WiFi 状态变化（连接中/已连接/断开等）由后端推送，直接覆盖状态视图
  on(EV.wifi, (p) => {
    if (!p) return
    state.wifi.available = !!p.available
    state.wifi.status = p
  })
}

// ------------------------------------------------------------ 更新事件 ----

// 更新阶段标识，与后端 app.go 的 UpdateStage* 一一对应
const STAGE_DOWNLOAD = 'download'
const STAGE_PREPARE = 'prepare'
const STAGE_INSTALL = 'install'

/** 清空上一次的进度数值，避免重试或再次打开时残留旧百分比。 */
export function resetUpdateProgress() {
  const u = state.update
  u.progress = 0
  u.downloaded = 0
  u.total = 0
  lastProgressStage = ''
  lastDownloaded = 0
}

// 进度单调保护：同一阶段内字节数不应该变小。续传遇到 HTTP 416 时服务端会清空本地
// 分片重来，否则界面会出现「80% → 0%」的倒退。
let lastProgressStage = ''
let lastDownloaded = 0

/** 点击「下载并安装」后的乐观状态：后端要等第一个 progress 事件才会有反馈，
 *  这中间可能隔着一次清单下载和一次握手，必须先给界面一个确定的下载态。 */
export function beginUpdateDownload() {
  const u = state.update
  resetUpdateProgress()
  u.busy = true
  u.checking = false
  u.installing = false
  u.downloading = true
  u.stage = STAGE_DOWNLOAD
  u.status = t('update.preparing')
  u.visible = true
}

/** RPC 请求本身没送达后端时的收尾：后续不会有事件回来，必须在本地方复位，
 *  否则按钮会永久停留在禁用态。 */
export function abortUpdateRequest(toastMessage) {
  const u = state.update
  u.checking = false
  u.busy = false
  u.downloading = false
  u.installing = false
  u.status = ''
  resetUpdateProgress()
  if (toastMessage) showToast(toastMessage, 'error')
}

/** 点击「立即安装」后的乐观状态：解压/安装期间不可再点，也不可取消。 */
export function beginUpdateInstall() {
  const u = state.update
  u.busy = true
  u.checking = false
  u.downloading = false
  u.installing = true
  u.stage = STAGE_PREPARE
  u.status = t('update.installing')
  u.visible = true
}

/**
 * 应用后端 app:update 事件负载（全 kind：checking/result/status/progress/canceled/error/done/installing）。
 * 对话框是更新流程的主界面；无可用更新、取消等瞬时结果走 Toast，不占对话框。
 * stage 决定 landed 的阶段：下载阶段可取消，准备/安装阶段不可取消。
 */
function applyUpdatePayload(p) {
  const u = state.update
  const stage = p.stage || ''
  switch (p.kind) {
    case 'checking':
      u.checking = true
      u.stage = stage
      u.status = p.message || ''
      break
    case 'result':
      u.checking = false
      u.busy = false
      u.downloading = false
      u.installing = false
      u.stage = stage
      resetUpdateProgress()
      u.available = !!p.updateAvailable
      u.canInstall = !!p.canInstall
      u.title = p.title || ''
      u.body = p.body || ''
      u.assetName = p.assetName || ''
      u.assetSize = p.assetSize || 0
      u.releaseUrl = p.releaseUrl || ''
      u.path = ''
      u.status = ''
      if (p.updateAvailable) {
        u.visible = true
      } else {
        // 无可用更新（仅交互式检查会到达此处）：后端消息自带版本号，作为胶囊标题
        showToast(p.message || t('settings.update.upToDate'), 'success')
      }
      break
    case 'status':
      u.busy = false
      u.status = p.message || ''
      // 解压/安装阶段的状态文本要显示出来，必须在同一分支里切到安装态
      if (stage === STAGE_PREPARE || stage === STAGE_INSTALL) {
        u.downloading = false
        u.installing = true
        u.stage = stage
      }
      break
    case 'progress': {
      u.busy = false
      const preparing = stage === STAGE_PREPARE
      u.downloading = !preparing
      u.installing = preparing
      u.stage = stage
      let downloaded = p.downloaded || 0
      if (stage !== lastProgressStage) {
        lastProgressStage = stage
        lastDownloaded = 0
      }
      if (downloaded < lastDownloaded) downloaded = lastDownloaded
      lastDownloaded = downloaded
      u.downloaded = downloaded
      u.total = p.total || 0
      u.progress = u.total > 0 ? Math.min(100, Math.max(0, Math.round((downloaded / u.total) * 100))) : 0
      break
    }
    case 'canceled':
      // 用户主动取消不是失败：复位到可操作态，用中性语气提示
      u.checking = false
      u.busy = false
      u.downloading = false
      u.installing = false
      resetUpdateProgress()
      showToast(p.message || t('update.canceled'), 'info')
      break
    case 'error':
      // 检查/下载/安装各阶段失败：Toast 明示，并把对话框从进行中态复位到可操作态
      u.checking = false
      u.busy = false
      u.downloading = false
      u.installing = false
      resetUpdateProgress()
      showToast(p.message || t('update.error'), 'error')
      break
    case 'done':
      u.checking = false
      u.busy = false
      u.downloading = false
      u.installing = false
      u.status = ''
      resetUpdateProgress()
      u.path = p.path || ''
      u.visible = true
      // 对话框提供安装入口；此处仅作完成提示（胶囊形态不嵌按钮）
      showToast(t('update.doneTitle'), 'success')
      break
    case 'installing':
      u.busy = false
      u.downloading = false
      u.installing = true
      u.stage = STAGE_INSTALL
      u.status = p.message || t('update.installing')
      break
    default:
      break
  }
}

/** 清空本次嗅探会话数据（断开 / 暂停开关时调用）。 */
export function resetSniffing() {
  state.samples = []
  state.sessionUp = 0
  state.sessionDown = 0
  state.peakUp = 0
  state.peakDown = 0
}

// --------------------------------------------------------------- 动作 ----

export function applyTheme() {
  state.theme = resolveTheme(state.themePref)
  applyPalette(state.theme)
}

/** 合并设置并保存（防抖由后端负责）。 */
export function patchSettings(patch) {
  if (!state.settings) return
  const next = { ...state.settings, ...patch }
  state.settings = next
  // 主题需要在保存请求返回前立即应用，避免后端事件回推时因乐观更新
  // 导致主题变更判断失效。
  if (Object.prototype.hasOwnProperty.call(patch, 'uiTheme')) {
    state.themePref = patch.uiTheme || 'system'
    applyTheme()
  }
  // Wails RPC 异步执行；失败时静默丢失用户操作，需明确反馈
  Promise.resolve(api.SaveSettings(next)).catch(() => {
    showToast(t('settings.saveFail'), 'error')
  })
}

export function setDialBusy(label) {
  state.dialBusy = true
  state.dialLabel = label
}

export async function doDial() {
  if (state.dialBusy) return
  setDialBusy(t('home.dial.dialing'))
  let accepted = false
  try {
    // 凭据由后端用当前账号已保存的密码完成，密码不经过前端
    accepted = await api.DialCurrentAccount()
  } catch (e) {
    accepted = false
  }
  // 后端拒绝（忙/预检失败）时不进入拨号队列、不发阶段事件，立即复位按钮；
  // 失败原因由后端 notify 事件（error 语气）提示
  if (!accepted) {
    state.dialBusy = false
    state.dialLabel = ''
    return
  }
  // 受理成功：拨号在后台队列进行，结果经 notify 事件回来。
  // 用 Promise Toast 承载「连接中 → 成功/失败」的样式切换（胶囊形态）。
  toastPromise(dialOutcomePromise(), {
    loading: t('home.dial.dialing'),
    success: (p) => (p && p.title) || t('toast.dial.success'),
    error: (err) => (err && err.title) || t('toast.dial.failed')
  })
}

export async function doDisconnect() {
  if (state.dialBusy) return
  setDialBusy(t('home.dial.disconnecting'))
  let accepted = false
  try {
    accepted = await api.Disconnect()
  } catch (e) {
    accepted = false
  }
  // 后端拒绝（忙）时不进入断开队列、不发阶段事件，立即复位按钮
  if (!accepted) {
    state.dialBusy = false
    state.dialLabel = ''
    return
  }
  // 受理成功：断开在后台队列进行，结果经 notify 事件回来。
  // 用 Promise Toast 承载「断开中… → 已断开」的样式切换（胶囊形态）。
  toastPromise(disconnectOutcomePromise(), {
    loading: t('home.dial.disconnecting'),
    success: (p) => (p && p.title) || t('toast.disconnect.done'),
    error: (err) => (err && err.title) || t('toast.disconnect.failed')
  })
}

// --------------------------------------------------------------- WiFi ----

/** 拉取 WiFi 状态（WiFi 页挂载与手动刷新时调用）。 */
export async function refreshWifiStatus() {
  try {
    const st = await api.WifiStatus()
    if (!st) return
    state.wifi.available = !!st.available
    state.wifi.status = st
  } catch (e) {
    /* 后端不可达时保持原状态 */
  }
}

/** 扫描周边网络；force 为 true 时触发刷新扫描。 */
export async function scanWifi(force = false) {
  try {
    const nets = await api.WifiScan(!!force)
    state.wifi.networks = Array.isArray(nets) ? nets : []
    state.wifi.scannedAt = Date.now()
  } catch (e) {
    showToast(t('wifi.scan.empty'), 'info')
  }
  return state.wifi.networks
}

/** 连接 WiFi：受理即返回，结果经 app:wifi 事件回报。
 *  失败提示放在 phase 变化后的超时兜底里，这里只处理未受理。 */
export async function connectWifi(ssid, password) {
  let accepted = false
  try {
    accepted = await api.WifiConnect(ssid, password || '')
  } catch (e) {
    accepted = false
  }
  if (!accepted) {
    showToast(t('wifi.connectFail'), 'error')
  }
  return accepted
}

/** 断开无线连接。 */
export async function disconnectWifi() {
  try {
    await api.WifiDisconnect()
  } catch (e) {
    showToast(t('wifi.connectFail'), 'error')
  }
}

/** 读取门户认证凭据视图（明文不出后端）。 */
export async function loadPortalCredential() {
  try {
    return (await api.GetPortalCredential()) || { username: '', hasPassword: false }
  } catch (e) {
    return { username: '', hasPassword: false }
  }
}

/** 保存门户认证凭据。 */
export async function savePortalCredential(username, password) {
  const ok = await api.SavePortalCredential(username || '', password || '')
  showToast(t(ok ? 'wifi.auth.saved' : 'wifi.auth.saveFail'), ok ? 'success' : 'error')
  return ok
}

/** 手动测试门户认证（后端同步执行约 3-10 秒）。 */
export async function testPortalAuth() {
  try {
    return (await api.TestPortalAuth()) || { ok: false, detail: '' }
  } catch (e) {
    return { ok: false, detail: String(e && e.message ? e.message : e) }
  }
}

export { formatSpeed, formatBytes, formatDuration }
