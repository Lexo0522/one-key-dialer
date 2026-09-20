// 全局响应式状态：Bootstrap 拉首帧，之后靠后端事件增量更新。
import { reactive, watch } from 'vue'
import { api, on, EV } from './bridge'
import { resolveTheme, applyPalette } from './theme'
import { setLang, t } from './i18n'
import { formatSpeed, formatBytes, formatDuration } from './format'

const PREFS_KEY = 'okd.ui.prefs.v1'
const SNIFF_WINDOW_MS = 10 * 60 * 1000 // 折线图统计窗口：10 分钟
const SNIFF_MAX_POINTS = 620

/** 本地偏好（无后端字段支持的界面设置）：语言覆盖 / 流量嗅探 / 轻量化 / 代理。 */
function defaultPrefs() {
  return {
    lang: '', // '' = 跟随后端探测到的语言
    sniffing: true,
    lightweight: false,
    proxy: { enabled: false, type: 'http', host: '', port: '', bypass: '' }
  }
}

function loadPrefs() {
  const base = defaultPrefs()
  try {
    const raw = localStorage.getItem(PREFS_KEY)
    if (!raw) return base
    const saved = JSON.parse(raw)
    return {
      ...base,
      ...saved,
      proxy: { ...base.proxy, ...(saved.proxy || {}) }
    }
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
  },

  // 提示条
  toast: { visible: false, title: '', body: '', tone: 'info' }
})

let toastTimer = null
let renderTimer = null

export function showToast(title, body, tone = 'info') {
  state.toast = { visible: true, title, body, tone }
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    state.toast.visible = false
  }, 4200)
}

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
    const themeChanged = !state.settings || state.settings.uiTheme !== s.uiTheme
    state.settings = s
    state.currentIndex = s.accountIndex
    if (themeChanged) {
      state.themePref = s.uiTheme || 'system'
      applyTheme()
    }
  })

  on(EV.notify, (p) => {
    if (!p) return
    showToast(p.title || '', p.body || '', 'info')
  })

  on(EV.update, (p) => {
    if (!p) return
    applyUpdatePayload(p)
  })
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
  api.SaveSettings(next)
}

export function setDialBusy(label) {
  state.dialBusy = true
  state.dialLabel = label
}

export async function doDial() {
  if (state.dialBusy) return
  setDialBusy(t('home.dial.dialing'))
  try {
    // 凭据由后端用当前账号已保存的密码完成，密码不经过前端
    const accepted = await api.DialCurrentAccount()
    // 后端拒绝（忙/预检失败）时不进入拨号队列、不发阶段事件，立即复位按钮
    if (accepted === false) {
      state.dialBusy = false
      state.dialLabel = ''
    }
  } catch (e) {
    state.dialBusy = false
    state.dialLabel = ''
  }
}

export async function doDisconnect() {
  if (state.dialBusy) return
  setDialBusy(t('home.dial.disconnecting'))
  const accepted = await api.Disconnect()
  if (accepted === false) {
    state.dialBusy = false
    state.dialLabel = ''
  }
}

export { formatSpeed, formatBytes, formatDuration }
