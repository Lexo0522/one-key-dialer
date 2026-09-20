// 主题色板：与旧版 Swing UiTheme 一一对应（浅色 / 深色两套）。
export const PALETTES = {
  light: {
    success: '#22c55e',
    error: '#dc3545',
    info: '#007bff',
    warning: '#ffc107',
    bg: '#f3f5f9',
    card: '#ffffff',
    border: '#d1d5db',
    borderLight: '#dadce0',
    hint: '#969696',
    text: '#1f2328',
    textSub: '#5b6470',
    tableGrid: '#e6e6e6',
    tableSel: '#e8f0fe',
    tableHeader: '#f5f5f5',
    tableSelFg: '#000000',
    viewportBg: '#ffffff',
    consoleBg: '#282c34',
    consoleFg: '#ffffff',
    statusOnline: '#16a34a',
    titledBorder: '#646464',

    // —— 新版左右布局扩展 ——
    sidebar: '#ffffff',
    sidebarSub: '#6b7280',
    sidebarActive: '#007bff',
    sidebarActiveBg: 'rgba(0, 123, 255, .10)',
    hover: 'rgba(0, 0, 0, .045)',
    accentSoft: 'rgba(0, 123, 255, .10)',
    shadow: '0 1px 2px rgba(16, 24, 40, .05), 0 4px 16px rgba(16, 24, 40, .07)',
    sidebarShadow: '4px 0 16px rgba(16, 24, 40, .05)',
    logBg: '#f6f8fa',
    switchOff: '#cbd2d9',
    chartUp: '#f97316',
    chartDown: '#3b82f6',
    chartGrid: 'rgba(0, 0, 0, .07)',
    chartAxis: '#9aa3af'
  },
  dark: {
    success: '#34c77b',
    error: '#ef5350',
    info: '#4da3ff',
    warning: '#ffca2c',
    bg: '#1e1f22',
    card: '#2b2d31',
    border: '#404349',
    borderLight: '#383b41',
    hint: '#8a8f98',
    text: '#d6d9e0',
    textSub: '#a2a8b3',
    tableGrid: '#383b41',
    tableSel: '#263e5c',
    tableHeader: '#2f3237',
    tableSelFg: '#ffffff',
    viewportBg: '#2b2d31',
    consoleBg: '#18191c',
    consoleFg: '#d4d7dd',
    statusOnline: '#22c55e',
    titledBorder: '#8a8f98',

    // —— 新版左右布局扩展 ——
    sidebar: '#26282c',
    sidebarSub: '#9aa0aa',
    sidebarActive: '#4da3ff',
    sidebarActiveBg: 'rgba(77, 163, 255, .16)',
    hover: 'rgba(255, 255, 255, .06)',
    accentSoft: 'rgba(77, 163, 255, .14)',
    shadow: '0 1px 2px rgba(0, 0, 0, .35), 0 4px 16px rgba(0, 0, 0, .30)',
    sidebarShadow: '4px 0 16px rgba(0, 0, 0, .30)',
    logBg: '#18191c',
    switchOff: '#4a4f57',
    chartUp: '#fb923c',
    chartDown: '#60a5fa',
    chartGrid: 'rgba(255, 255, 255, .08)',
    chartAxis: '#6b7280'
  }
}

const VAR_MAP = {
  success: '--c-success',
  error: '--c-error',
  info: '--c-info',
  warning: '--c-warning',
  bg: '--c-bg',
  card: '--c-card',
  border: '--c-border',
  borderLight: '--c-border-light',
  hint: '--c-hint',
  text: '--c-text',
  textSub: '--c-text-sub',
  tableGrid: '--c-table-grid',
  tableSel: '--c-table-sel',
  tableHeader: '--c-table-header',
  tableSelFg: '--c-table-sel-fg',
  viewportBg: '--c-viewport',
  consoleBg: '--c-console-bg',
  consoleFg: '--c-console-fg',
  statusOnline: '--c-status-online',
  titledBorder: '--c-titled-border',
  sidebar: '--c-sidebar',
  sidebarSub: '--c-sidebar-sub',
  sidebarActive: '--c-sidebar-active',
  sidebarActiveBg: '--c-sidebar-active-bg',
  hover: '--c-hover',
  accentSoft: '--c-accent-soft',
  shadow: '--c-shadow',
  sidebarShadow: '--c-sidebar-shadow',
  logBg: '--c-log-bg',
  switchOff: '--c-switch-off',
  chartUp: '--c-chart-up',
  chartDown: '--c-chart-down',
  chartGrid: '--c-chart-grid',
  chartAxis: '--c-chart-axis'
}

/** 把色板写到 :root CSS 变量，并同步 <html data-theme>。 */
export function applyPalette(theme) {
  const p = PALETTES[theme] || PALETTES.light
  const root = document.documentElement
  for (const key of Object.keys(VAR_MAP)) {
    root.style.setProperty(VAR_MAP[key], p[key])
  }
  root.setAttribute('data-theme', theme)
}

/** system 主题时读取 Windows「应用使用浅色主题」偏好；失败按浅色处理。 */
export function systemPrefersDark() {
  try {
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
  } catch (e) {
    return false
  }
}

/** 把 system|light|dark 偏好折算成实际生效的浅/深。 */
export function resolveTheme(pref) {
  if (pref === 'dark') return 'dark'
  if (pref === 'light') return 'light'
  return systemPrefersDark() ? 'dark' : 'light'
}
