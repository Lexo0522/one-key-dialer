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

  // 本地偏好
  prefs: loadPrefs(),

  // 重绘节拍：轻量化模式下由 HomeTab 降低图表刷新频率
  renderTick: 0,

  // 更新
  update: {
    visible: false,
    checking: false,
    available: false,
    canInstall: false,
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
    downloading: false
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
}

// ------------------------------------------------------------ 更新事件 ----

/**
 * 应用后端 app:update 事件负载（全 kind：checking/result/status/progress/error/done/installing）。
 * 对话框是更新流程的主界面；无可用更新、检查/下载失败等瞬时结果走 Toast，不占对话框。
 */
function applyUpdatePayload(p) {
  const u = state.update
  switch (p.kind) {
    case 'checking':
      u.checking = true
      u.status = p.message || ''
      break
    case 'result':
      u.checking = false
      u.available = !!p.updateAvailable
      u.canInstall = !!p.canInstall
      u.title = p.title || ''
      u.body = p.body || ''
      u.assetName = p.assetName || ''
      u.assetSize = p.assetSize || 0
      u.releaseUrl = p.releaseUrl || ''
      if (p.updateAvailable) {
        u.visible = true
      } else {
        // 无可用更新（仅交互式检查会到达此处）：后端消息自带版本号，作为胶囊标题
        showToast(p.message || t('settings.update.upToDate'), 'success')
      }
      break
    case 'status':
      u.status = p.message || ''
      break
    case 'progress':
      u.downloading = true
      u.downloaded = p.downloaded || 0
      u.total = p.total || 0
      u.progress = u.total > 0 ? Math.min(100, Math.round((u.downloaded / u.total) * 100)) : 0
      break
    case 'error':
      // 检查/下载/安装各阶段失败：Toast 明示，并把对话框从下载中态复位到可操作态
      u.checking = false
      u.downloading = false
      showToast(p.message || t('update.error'), 'error')
      break
    case 'done':
      u.downloading = false
      u.path = p.path || ''
      u.visible = true
      // 对话框提供安装入口；此处仅作完成提示（胶囊形态不嵌按钮）
      showToast(t('update.doneTitle'), 'success')
      break
    case 'installing':
      u.status = t('update.installing')
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

export { formatSpeed, formatBytes, formatDuration }
