<template>
  <div class="page settings">
    <div class="page-head">
      <div class="page-title">
        <i class="fas fa-cog"></i>{{ t('settings.title') }}
      </div>
    </div>

    <div class="settings-grid">
      <!-- 基本设置 -->
      <section class="card settings-card">
        <div class="card-title"><i class="fas fa-sliders-h"></i>{{ t('settings.group.basic') }}</div>

        <div class="row">
          <label class="field-label">{{ t('settings.theme') }}</label>
          <select v-model="themePref" @change="onThemeChange">
            <option value="system">{{ t('settings.theme.system') }}</option>
            <option value="light">{{ t('settings.theme.light') }}</option>
            <option value="dark">{{ t('settings.theme.dark') }}</option>
          </select>
        </div>

        <div class="row">
          <label class="field-label">{{ t('settings.lang') }}</label>
          <select v-model="langPref" @change="onLangChange">
            <option value="">{{ t('settings.lang.system') }}</option>
            <option value="zh">{{ t('settings.lang.zh') }}</option>
            <option value="en">{{ t('settings.lang.en') }}</option>
          </select>
        </div>

        <div class="switch-row">
          <label class="label">
            <span class="t">{{ t('settings.autostart') }}</span>
            <span class="d">{{ t('settings.autostart.hint') }}</span>
          </label>
          <span class="switch"><input v-model="autoStart" type="checkbox" @change="onAutoStart"/><span class="track"></span></span>
        </div>
        <div class="switch-row">
          <label class="label">
            <span class="t">{{ t('settings.startMinimized') }}</span>
          </label>
          <span class="switch"><input v-model="startMinimized" type="checkbox" @change="onStartMinimized"/><span class="track"></span></span>
        </div>

        <div class="switch-row">
          <label class="label">
            <span class="t">{{ t('settings.sniffing') }}</span>
            <span class="d">{{ t('settings.sniffing.hint') }}</span>
          </label>
          <span class="switch"><input v-model="sniffing" type="checkbox" @change="onSniffing"/><span class="track"></span></span>
        </div>

        <div class="switch-row">
          <label class="label">
            <span class="t">{{ t('settings.reconnect') }}</span>
            <span class="d">{{ t('settings.reconnect.hint') }}</span>
          </label>
          <span class="switch"><input v-model="autoReconnect" type="checkbox" @change="pushReconnect"/><span class="track"></span></span>
        </div>
        <div class="row">
          <label class="field-label">{{ t('settings.interval') }}</label>
          <input v-model.number="intervalSeconds" type="number" min="5" max="3600" step="5" @change="pushInterval"/>
          <span class="hint">{{ t('settings.interval.unit') }}</span>
        </div>

        <div class="switch-row">
          <label class="label">
            <span class="t">{{ t('settings.lite') }}</span>
            <span class="d">{{ t('settings.lite.hint') }}</span>
          </label>
          <span class="switch"><input v-model="lightweight" type="checkbox" @change="onLightweight"/><span class="track"></span></span>
        </div>

        <div class="switch-row">
          <label class="label">
            <span class="t">{{ t('settings.lowMem') }}</span>
            <span class="d">{{ t('settings.lowMem.hint') }}</span>
          </label>
          <span class="switch"><input v-model="lowMemRender" type="checkbox" @change="pushLowMem"/><span class="track"></span></span>
        </div>

        <div class="switch-row">
          <label class="label">
            <span class="t">{{ t('settings.noInternet') }}</span>
            <span class="d">{{ t('settings.noInternet.hint') }}</span>
          </label>
          <span class="switch"><input v-model="disconnectOnNoInternet" type="checkbox" @change="pushDisconnect"/><span class="track"></span></span>
        </div>
      </section>

      <!-- 代理设置 -->
      <section class="card settings-card">
        <div class="card-title"><i class="fas fa-globe"></i>{{ t('settings.group.proxy') }}</div>

        <div class="switch-row">
          <label class="label"><span class="t">{{ t('settings.proxy.enable') }}</span></label>
          <span class="switch"><input v-model="proxy.enabled" type="checkbox" @change="pushProxy"/><span class="track"></span></span>
        </div>

        <div class="row">
          <label class="field-label">{{ t('settings.proxy.url') }}</label>
          <input v-model="proxy.address" class="proxy-address" type="text" autocomplete="off" spellcheck="false"
                 :placeholder="t('settings.proxy.urlPlaceholder')" @input="proxy.addressDirty = true" @change="saveProxyAddress"/>
        </div>
        <div class="row">
          <label class="field-label">{{ t('settings.proxy.bypass') }}</label>
          <input v-model="proxy.bypass" type="text" placeholder="localhost;192.168.*" @change="pushProxy"/>
        </div>
        <div class="hint">{{ t('settings.proxy.urlHint') }}</div>
        <div class="hint">{{ t('settings.proxy.hint') }}</div>
      </section>

      <!-- 更新 -->
      <section class="card settings-card">
        <div class="card-title"><i class="fas fa-sync-alt"></i>{{ t('settings.group.update') }}</div>

        <div class="switch-row">
          <label class="label"><span class="t">{{ t('settings.update.enabled') }}</span></label>
          <span class="switch"><input v-model="updateCheckEnabled" type="checkbox" @change="pushUpdateCheck"/><span class="track"></span></span>
        </div>

        <div class="switch-row">
          <label class="label"><span class="t">{{ t('settings.update.autoInstall') }}</span></label>
          <span class="switch"><input v-model="autoInstallUpdate" type="checkbox" @change="pushAutoInstall"/><span class="track"></span></span>
        </div>
        <div class="hint">{{ t('settings.update.autoInstallHint') }}</div>

        <div class="row">
          <label class="field-label">{{ t('settings.update.current') }}</label>
          <span class="ver-value">{{ versionLabel() }}</span>
        </div>

        <div class="row">
          <button class="btn" :disabled="state.update.checking" @click="checkNow">
            <i class="fas fa-search" :class="{ 'fa-spin': state.update.checking }"></i>
            {{ state.update.checking ? t('settings.update.checking') : t('settings.update.now') }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { state, patchSettings, patchPrefs, showToast, syncLangToBackend, versionLabel } from '../store'
import { api } from '../bridge'
import { setLang, t } from '../i18n'

// ------------------------------------------------------------ 本地偏好 ----

const langPref = ref(state.prefs.lang || '')
const sniffing = ref(state.prefs.sniffing)
const lightweight = ref(state.prefs.lightweight)

function onLangChange() {
  patchPrefs({ lang: langPref.value })
  setLang(langPref.value || state.systemLang || 'zh')
  // 同步给后端：托盘菜单 / 通知 / 日志文案随之一并切换
  syncLangToBackend()
  showToast(t('settings.lang.changed'), 'success')
}

function onSniffing() {
  patchPrefs({ sniffing: sniffing.value })
  showToast(t(sniffing.value ? 'settings.sniffing.enabled' : 'settings.sniffing.disabled'), 'success')
}

function onLightweight() {
  patchPrefs({ lightweight: lightweight.value })
  showToast(t(lightweight.value ? 'settings.lite.enabled' : 'settings.lite.disabled'), 'success')
}

// ------------------------------------------------------------ 后端设置 ----

const themePref = ref(state.settings?.uiTheme || 'system')
// 开机自启以 state.settings.autoStart 为唯一事实源:每次 app:settings
// 事件回推都会刷新它。不能再 OR 上 state.autoStartEnabled——那是
// Bootstrap 时读一次的陈旧快照,不会随后续事件更新,会把用户刚关掉的
// 开关又掰回开启态(表现为要点两下才生效)。
const autoStart = ref(!!state.settings?.autoStart)
const startMinimized = ref(!!state.settings?.startMinimized)
const autoReconnect = ref(!!state.settings?.autoReconnect)
const intervalSeconds = ref(state.settings?.intervalSeconds ?? 30)
const disconnectOnNoInternet = ref(!!state.settings?.disconnectOnNoInternet)
const updateCheckEnabled = ref(state.settings?.updateCheckEnabled ?? true)
const autoInstallUpdate = ref(state.settings?.autoInstallUpdate ?? true)
// 低内存渲染:UI 进程启动时读取,改动需下次打开窗口生效
const lowMemRender = ref(!!state.settings?.lowMemRender)

// 代理：后端设置项，经 patchSettings 持久化到 settings.json 并实时生效
// （仅作用于本应用自身的 HTTP 请求：更新检查、HTTP 外网探测、首页测速与 IP 查询）
const MASKED_PROXY_PASSWORD = '••••••••'
const proxy = reactive({
  enabled: !!state.settings?.proxyEnabled,
  type: state.settings?.proxyType || 'http',
  host: state.settings?.proxyHost || '',
  port: state.settings?.proxyPort || '',
  address: '',
  addressDirty: false,
  bypass: state.settings?.proxyBypass || '',
  authEnabled: !!state.settings?.proxyAuthEnabled
})
const proxyCredential = reactive({ username: '', savedUsername: '', hasPassword: false })

function proxyDefaultPort(type) {
  if (type === 'https') return '443'
  if (type === 'socks5') return '1080'
  return '80'
}

function formatProxyAddress(type, host, port) {
  if (!host) return ''
  const normalizedType = ['http', 'https', 'socks5'].includes(type) ? type : 'http'
  const cleanHost = String(host).replace(/^\[|\]$/g, '')
  const urlHost = cleanHost.includes(':') ? `[${cleanHost}]` : cleanHost
  const urlPort = port || proxyDefaultPort(normalizedType)
  const username = proxyCredential.savedUsername || proxyCredential.username
  const userInfo = proxy.authEnabled && proxyCredential.hasPassword && username
    ? `${encodeURIComponent(username)}:${encodeURIComponent(MASKED_PROXY_PASSWORD)}@`
    : ''
  return `${normalizedType}://${userInfo}${urlHost}:${urlPort}`
}

function decodeProxyUserInfo(value) {
  try {
    return decodeURIComponent(value)
  } catch (e) {
    throw new Error('invalid user info encoding')
  }
}

function parseProxyAddress(value) {
  const raw = String(value || '').trim()
  if (!raw) return { type: proxy.type || 'http', host: '', port: '', hasCredentials: false }
  if (!/^[a-z][a-z\d+.-]*:\/\//i.test(raw)) throw new Error('missing scheme')

  const parsed = new URL(raw)
  const type = parsed.protocol.slice(0, -1).toLowerCase()
  if (!['http', 'https', 'socks5'].includes(type) || !parsed.hostname) throw new Error('invalid proxy URL')
  if ((parsed.pathname && parsed.pathname !== '/') || parsed.search || parsed.hash) throw new Error('proxy URL cannot include a path')

  const username = decodeProxyUserInfo(parsed.username)
  const password = decodeProxyUserInfo(parsed.password)
  if (parsed.port && (+parsed.port < 1 || +parsed.port > 65535)) throw new Error('invalid port')
  const hasCredentials = !!username || !!password
  if (hasCredentials && (!username || !password)) throw new Error('incomplete proxy credentials')

  const host = parsed.hostname.replace(/^\[|\]$/g, '')
  return {
    type,
    host,
    port: parsed.port || proxyDefaultPort(type),
    username,
    password,
    hasCredentials
  }
}

function syncProxyAddress() {
  proxy.address = formatProxyAddress(proxy.type, proxy.host, proxy.port)
}

onMounted(async () => {
  try {
    const cred = await api.GetProxyCredential()
    proxyCredential.username = cred?.username || ''
    proxyCredential.savedUsername = cred?.username || ''
    proxyCredential.hasPassword = !!cred?.hasPassword
    if (proxyCredential.savedUsername && (!proxy.authEnabled || !proxyCredential.hasPassword)) {
      if (await api.ClearProxyCredential()) {
        proxyCredential.username = ''
        proxyCredential.savedUsername = ''
        proxyCredential.hasPassword = false
        proxy.authEnabled = false
      } else {
        showToast(t('settings.proxy.credentialClearFail'), 'error')
      }
    } else if (proxy.authEnabled && (!proxyCredential.savedUsername || !proxyCredential.hasPassword)) {
      proxy.authEnabled = false
      patchSettings({ proxyAuthEnabled: false })
    }
    if (!proxy.addressDirty) syncProxyAddress()
  } catch (e) {
    showToast(t('settings.proxy.credentialLoadFail'), 'error')
  }
})

// 全部以 state.settings 为唯一事实源(Bootstrap 与每次 app:settings
// 事件都刷新它),不再读 state.autoStartEnabled:后者是启动时的快照,
// 不随后续事件更新,曾导致自启开关被陈旧值覆盖、需要点两下才生效。
watch(
  () => state.settings,
  (s) => {
    if (!s) return
    themePref.value = s.uiTheme || 'system'
    autoStart.value = s.autoStart
    startMinimized.value = s.startMinimized
    autoReconnect.value = s.autoReconnect
    intervalSeconds.value = s.intervalSeconds
    disconnectOnNoInternet.value = s.disconnectOnNoInternet
    updateCheckEnabled.value = s.updateCheckEnabled
    autoInstallUpdate.value = s.autoInstallUpdate ?? true
    lowMemRender.value = !!s.lowMemRender
    proxy.enabled = !!s.proxyEnabled
    proxy.type = s.proxyType || 'http'
    proxy.host = s.proxyHost || ''
    proxy.port = s.proxyPort || ''
    proxy.bypass = s.proxyBypass || ''
    proxy.authEnabled = !!s.proxyAuthEnabled
    if (!proxy.addressDirty) syncProxyAddress()
  },
  { immediate: true, deep: true }
)

function onThemeChange() {
  patchSettings({ uiTheme: themePref.value })
  showToast(t('settings.theme.changed'), 'success')
}

function onStartMinimized() {
  patchSettings({ startMinimized: startMinimized.value })
  showToast(
    t(startMinimized.value ? 'settings.startMinimized.enabled' : 'settings.startMinimized.disabled'),
    'success'
  )
}

async function onAutoStart() {
  const requested = autoStart.value
  try {
    // Wails RPC 返回 Promise；必须等待真实结果，不能直接判断 Promise 本身。
    const ok = await api.SetAutoStart(requested)
    if (!ok) {
      autoStart.value = !requested
      showToast(t('settings.autostart.fail'), 'error')
      return
    }
    // 后端已在 SetAutoStart 内把最终值写回 settings 并广播 app:settings,
    // watch 会据此同步开关。这里只补一次本地乐观更新,防止事件乱序时
    // 开关短暂停在旧值;不可反向覆盖(seen 控制见 watch 上方注释)。
    if (state.settings) state.settings.autoStart = requested
    autoStart.value = requested
    showToast(
      t(requested ? 'settings.autostart.enabled' : 'settings.autostart.disabled'),
      'success'
    )
  } catch (e) {
    autoStart.value = !requested
    showToast(t('settings.autostart.fail'), 'error')
  }
}

function clamp(v, lo, hi, dflt) {
  const n = Number(v)
  if (!Number.isFinite(n)) return dflt
  return Math.min(hi, Math.max(lo, Math.trunc(n)))
}

function pushReconnect() {
  patchSettings({ autoReconnect: autoReconnect.value })
  showToast(
    t(autoReconnect.value ? 'settings.reconnect.enabled' : 'settings.reconnect.disabled'),
    'success'
  )
}

function pushInterval() {
  intervalSeconds.value = clamp(intervalSeconds.value, 5, 3600, 30)
  patchSettings({ intervalSeconds: intervalSeconds.value })
}

function pushDisconnect() {
  patchSettings({ disconnectOnNoInternet: disconnectOnNoInternet.value })
  showToast(
    t(disconnectOnNoInternet.value ? 'settings.noInternet.enabled' : 'settings.noInternet.disabled'),
    'success'
  )
}

function pushUpdateCheck() {
  patchSettings({ updateCheckEnabled: updateCheckEnabled.value })
}

function pushAutoInstall() {
  patchSettings({ autoInstallUpdate: autoInstallUpdate.value })
}

// 低内存渲染写入后端设置;UI 进程在窗口创建前读取 settings.json,
// 因此本次改动要等下一次打开窗口才生效。
function pushLowMem() {
  patchSettings({ lowMemRender: lowMemRender.value })
  showToast(t(lowMemRender.value ? 'settings.lowMem.enabled' : 'settings.lowMem.disabled'), 'success')
}

// 保存开关与绕过列表。代理地址由 saveProxyAddress 解析成旧版兼容字段。
function pushProxy(showStatus = true) {
  if (proxy.enabled && !proxy.host) {
    proxy.enabled = false
    showToast(t('settings.proxy.needHost'), 'error')
    return
  }
  patchSettings({
    proxyEnabled: proxy.enabled,
    proxyType: proxy.type,
    proxyHost: proxy.host,
    proxyPort: proxy.port,
    proxyBypass: (proxy.bypass || '').trim(),
    proxyAuthEnabled: proxy.authEnabled
  })
  if (showStatus) showToast(t(proxy.enabled ? 'settings.proxy.enabled' : 'settings.proxy.disabled'), 'success')
}

async function saveProxyAddress() {
  if (!proxy.addressDirty) return
  let parsed
  try {
    parsed = parseProxyAddress(proxy.address)
  } catch (e) {
    showToast(t('settings.proxy.urlInvalid'), 'error')
    return
  }
  if (proxy.enabled && !parsed.host) {
    showToast(t('settings.proxy.needHost'), 'error')
    return
  }

  const isSavedPassword = parsed.hasCredentials &&
    proxyCredential.hasPassword &&
    parsed.username === proxyCredential.savedUsername &&
    parsed.password === MASKED_PROXY_PASSWORD
  if (parsed.password === MASKED_PROXY_PASSWORD && !isSavedPassword) {
    showToast(t('settings.proxy.passwordRequired'), 'warning')
    return
  }

  if (parsed.hasCredentials && !isSavedPassword) {
    try {
      if (!await api.SaveProxyCredential(parsed.username, parsed.password)) {
        showToast(t('settings.proxy.credentialSaveFail'), 'error')
        return
      }
      proxyCredential.username = parsed.username
      proxyCredential.savedUsername = parsed.username
      proxyCredential.hasPassword = true
    } catch (e) {
      showToast(t('settings.proxy.credentialSaveFail'), 'error')
      return
    }
  } else if (!parsed.hasCredentials && (proxyCredential.hasPassword || proxyCredential.savedUsername)) {
    try {
      if (!await api.ClearProxyCredential()) {
        showToast(t('settings.proxy.credentialClearFail'), 'error')
        return
      }
      proxyCredential.username = ''
      proxyCredential.savedUsername = ''
      proxyCredential.hasPassword = false
      proxy.authEnabled = false
    } catch (e) {
      showToast(t('settings.proxy.credentialClearFail'), 'error')
      return
    }
  } else if (!parsed.hasCredentials) {
    proxy.authEnabled = false
  }
  if (parsed.hasCredentials) proxy.authEnabled = true

  proxy.type = parsed.type
  proxy.host = parsed.host
  proxy.port = parsed.port || proxyDefaultPort(parsed.type)
  pushProxy(false)
  proxy.addressDirty = false
  syncProxyAddress()
  showToast(t('settings.proxy.saved'), 'success')
}

function checkNow() {
  if (state.update.checking) return
  api.CheckUpdate(true)
}
</script>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 0 0 auto;
}

.page-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 800;
}

.page-title i {
  color: var(--c-info);
}

/* 三大卡片左右布局：基本设置更宽，代理 / 更新等宽并列 */
.settings-grid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  align-items: stretch;
  padding-bottom: 6px;
}

.settings-card {
  display: flex;
  flex-direction: column;
  padding: 16px 18px;
}

.settings-card > .card-title {
  padding-bottom: 10px;
  margin-bottom: 4px;
  border-bottom: 1px dashed var(--c-table-grid);
}

@media (max-width: 1120px) {
  .settings-grid {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
}

@media (max-width: 760px) {
  .settings-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}

.row .field-label {
  min-width: 64px;
}

.row select,
.row input[type="text"],
.row input[type="password"] {
  flex: 1;
  min-width: 0;
  max-width: none;
}

.row input[type="number"] {
  width: 76px;
}

.proxy-address {
  font-family: ui-monospace, Consolas, monospace;
}

.hint.ok {
  color: var(--c-success);
}

.ver-value {
  font-weight: 700;
}
</style>
