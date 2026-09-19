// 全局响应式状态：Bootstrap 拉首帧，之后靠后端事件增量更新。
import { reactive } from 'vue'
import { api, on, EV } from './bridge'
import { resolveTheme, applyPalette } from './theme'
import { setLang } from './i18n'
import { formatSpeed, formatBytes, formatDuration } from './format'

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
  history: [],
  logs: [],
  autoStartEnabled: false,
  themePref: 'system',
  theme: 'light',
  dataDir: '',
  updatesDir: '',

  // 主页输入
  home: { name: '', username: '', password: '' },

  // 状态栏
  downSpeed: 0,
  upSpeed: 0,
  uptimeSeconds: -1,

  // 诊断输出
  diagText: '',
  diagBusy: false,

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

export async function bootstrap() {
  const s = await api.Bootstrap()
  state.version = s.version
  state.displayVersion = s.displayVersion
  state.settings = s.settings
  state.accounts = s.accounts || []
  state.currentIndex = s.currentIndex
  state.online = s.online
  state.history = s.history || []
  state.logs = s.logs || []
  state.autoStartEnabled = s.autoStartEnabled
  state.themePref = s.settings.uiTheme || 'system'
  state.dataDir = s.dataDir
  state.updatesDir = s.updatesDir
  setLang(s.lang || 'zh')
  state.theme = s.theme && s.theme !== 'system' ? s.theme : resolveTheme(state.themePref)
  applyPalette(state.theme)

  const cur = state.accounts[state.currentIndex]
  if (cur) {
    state.home.name = cur.name || ''
    state.home.username = cur.username || ''
    state.home.password = ''
  }
  state.ready = true
  return s
}

// ------------------------------------------------------------- 事件挂载 ----

export function bindEvents() {
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
    }
  })

  on(EV.speed, (p) => {
    if (!p) return
    state.downSpeed = p.down || 0
    state.upSpeed = p.up || 0
  })

  on(EV.uptime, (sec) => {
    state.uptimeSeconds = typeof sec === 'number' ? sec : -1
  })

  on(EV.history, () => {
    api.GetHistory().then((rows) => {
      state.history = rows || []
    })
  })

  on(EV.accounts, (p) => {
    if (!p) return
    state.accounts = p.accounts || []
    state.currentIndex = p.currentIndex || 0
    const cur = state.accounts[state.currentIndex]
    if (cur) {
      state.home.name = cur.name || ''
      state.home.username = cur.username || ''
    }
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

  on(EV.diag, (line) => {
    if (typeof line !== 'string') return
    state.diagText += line
    if (state.diagText.length > 200000) {
      state.diagText = state.diagText.slice(-160000)
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

export function applyUpdatePayload(p) {
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
      u.visible = !!p.updateAvailable
      if (!p.updateAvailable) {
        showToast('检查更新', p.message || '已是最新版本', 'success')
      }
      break
    case 'status':
      u.status = p.message || ''
      u.downloading = true
      u.visible = true
      break
    case 'progress':
      u.downloaded = p.downloaded || 0
      u.total = p.total || 0
      u.progress = p.total > 0 ? Math.floor((p.downloaded / p.total) * 100) : 0
      break
    case 'error':
      u.checking = false
      u.downloading = false
      showToast('更新', p.message || '', 'error')
      break
    case 'done':
      u.downloading = false
      u.progress = 100
      u.path = p.path || ''
      u.status = '下载完成'
      break
    case 'installing':
      u.status = '正在安装…'
      break
    default:
      break
  }
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
  setDialBusy('连接中…')
  api.UpdateHomeFields(state.home.name, state.home.username, state.home.password)
  try {
    await api.Dial(state.home.username, state.home.password)
  } finally {
    // 密码只使用一次，立即从内存清除
    state.home.password = ''
  }
}

export async function doDisconnect() {
  if (state.dialBusy) return
  setDialBusy('断开中…')
  await api.Disconnect()
}

export function statusBarText() {
  return {
    status: state.online ? '已连接' : '未连接',
    speed: state.online ? `↓ ${formatSpeed(state.downSpeed)} ↑ ${formatSpeed(state.upSpeed)}` : '↓ -- ↑ --',
    uptime: state.uptimeSeconds >= 0 ? `时长: ${formatDuration(state.uptimeSeconds)}` : '时长: 未连接'
  }
}

export { formatSpeed, formatBytes, formatDuration }
