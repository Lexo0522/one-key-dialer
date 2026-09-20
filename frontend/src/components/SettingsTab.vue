<template>
  <div class="page settings">
    <div class="page-head">
      <div class="page-title">
        <i class="fas fa-cog"></i>{{ t('settings.title') }}
      </div>
      <span class="hint">V{{ state.displayVersion || state.version }}</span>
    </div>

    <div class="settings-grid">
      <!-- 主题 -->
      <div class="card">
        <div class="card-title"><i class="fas fa-palette"></i>{{ t('settings.group.appearance') }}</div>
        <div class="row">
          <label class="field-label">{{ t('settings.theme') }}</label>
          <select v-model="themePref" @change="onThemeChange">
            <option value="system">{{ t('settings.theme.system') }}</option>
            <option value="light">{{ t('settings.theme.light') }}</option>
            <option value="dark">{{ t('settings.theme.dark') }}</option>
          </select>
        </div>
        <div class="hint">{{ t('settings.theme.hint') }}</div>
      </div>

      <!-- 语言 -->
      <div class="card">
        <div class="card-title"><i class="fas fa-language"></i>{{ t('settings.group.language') }}</div>
        <div class="row">
          <label class="field-label">{{ t('settings.lang') }}</label>
          <select v-model="langPref" @change="onLangChange">
            <option value="">{{ t('settings.theme.system') }}</option>
            <option value="zh">{{ t('settings.lang.zh') }}</option>
            <option value="en">{{ t('settings.lang.en') }}</option>
          </select>
        </div>
        <div class="hint">{{ t('settings.lang.hint') }}</div>
      </div>

      <!-- 开机自启 -->
      <div class="card">
        <div class="card-title"><i class="fas fa-rocket"></i>{{ t('settings.group.startup') }}</div>
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
      </div>

      <!-- 选择设备 -->
      <div class="card">
        <div class="card-title"><i class="fas fa-network-wired"></i>{{ t('settings.group.device') }}</div>
        <div class="row">
          <span class="field-label">{{ t('settings.device') }}</span>
          <button class="btn" :disabled="deviceBusy" @click="selectDevice">
            <i class="fas fa-server"></i>{{ t('settings.device.action') }}
          </button>
        </div>
        <div class="hint" :class="{ ok: !!deviceNote }">{{ deviceNote || t('settings.device.hint') }}</div>
      </div>

      <!-- 流量嗅探 -->
      <div class="card">
        <div class="card-title"><i class="fas fa-satellite-dish"></i>{{ t('settings.group.sniffing') }}</div>
        <div class="switch-row">
          <label class="label">
            <span class="t">{{ t('settings.sniffing') }}</span>
            <span class="d">{{ t('settings.sniffing.hint') }}</span>
          </label>
          <span class="switch"><input v-model="sniffing" type="checkbox" @change="onSniffing"/><span class="track"></span></span>
        </div>
      </div>

      <!-- 断网自动重连 -->
      <div class="card">
        <div class="card-title"><i class="fas fa-redo"></i>{{ t('settings.group.reconnect') }}</div>
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
      </div>

      <!-- 轻量化 -->
      <div class="card">
        <div class="card-title"><i class="fas fa-feather-alt"></i>{{ t('settings.group.lite') }}</div>
        <div class="switch-row">
          <label class="label">
            <span class="t">{{ t('settings.lite') }}</span>
            <span class="d">{{ t('settings.lite.hint') }}</span>
          </label>
          <span class="switch"><input v-model="lightweight" type="checkbox" @change="onLightweight"/><span class="track"></span></span>
        </div>
      </div>

      <!-- 无外网自动断开 -->
      <div class="card">
        <div class="card-title"><i class="fas fa-unlink"></i>{{ t('settings.group.disconnect') }}</div>
        <div class="switch-row">
          <label class="label">
            <span class="t">{{ t('settings.noInternet') }}</span>
            <span class="d">{{ t('settings.noInternet.hint') }}</span>
          </label>
          <span class="switch"><input v-model="disconnectOnNoInternet" type="checkbox" @change="pushDisconnect"/><span class="track"></span></span>
        </div>
      </div>

      <!-- 定时任务 -->
      <div class="card">
        <div class="card-title"><i class="far fa-clock"></i>{{ t('settings.group.schedule') }}</div>
        <div class="switch-row">
          <label class="label"><span class="t">{{ t('settings.schedule.dial') }}</span></label>
          <span class="switch"><input v-model="dialOn" type="checkbox" @change="pushSchedule"/><span class="track"></span></span>
        </div>
        <div class="time-row">
          <span class="hint">{{ t('settings.schedule.everyDay') }}</span>
          <input v-model.number="dialHour" type="number" min="0" max="23" @change="pushSchedule"/>
          <span class="hint">{{ t('settings.schedule.hour') }}</span>
          <input v-model.number="dialMinute" type="number" min="0" max="59" @change="pushSchedule"/>
          <span class="hint">{{ t('settings.schedule.minute') }}</span>
        </div>
        <div class="switch-row">
          <label class="label"><span class="t">{{ t('settings.schedule.disconnect') }}</span></label>
          <span class="switch"><input v-model="discOn" type="checkbox" @change="pushSchedule"/><span class="track"></span></span>
        </div>
        <div class="time-row">
          <span class="hint">{{ t('settings.schedule.everyDay') }}</span>
          <input v-model.number="discHour" type="number" min="0" max="23" @change="pushSchedule"/>
          <span class="hint">{{ t('settings.schedule.hour') }}</span>
          <input v-model.number="discMinute" type="number" min="0" max="59" @change="pushSchedule"/>
          <span class="hint">{{ t('settings.schedule.minute') }}</span>
        </div>
      </div>

      <!-- 代理设置 -->
      <div class="card">
        <div class="card-title"><i class="fas fa-globe"></i>{{ t('settings.group.proxy') }}</div>
        <div class="switch-row">
          <label class="label"><span class="t">{{ t('settings.proxy.enable') }}</span></label>
          <span class="switch"><input v-model="proxy.enabled" type="checkbox" @change="pushProxy"/><span class="track"></span></span>
        </div>
        <div class="row">
          <label class="field-label">{{ t('settings.proxy.type') }}</label>
          <select v-model="proxy.type" @change="pushProxy">
            <option value="http">HTTP</option>
            <option value="https">HTTPS</option>
            <option value="socks5">SOCKS5</option>
          </select>
        </div>
        <div class="row">
          <label class="field-label">{{ t('settings.proxy.host') }}</label>
          <input v-model="proxy.host" type="text" placeholder="127.0.0.1" @change="pushProxy"/>
        </div>
        <div class="row">
          <label class="field-label">{{ t('settings.proxy.port') }}</label>
          <input v-model="proxy.port" type="text" placeholder="7890" @change="pushProxy"/>
        </div>
        <div class="row">
          <label class="field-label">{{ t('settings.proxy.bypass') }}</label>
          <input v-model="proxy.bypass" type="text" placeholder="localhost;192.168.*" @change="pushProxy"/>
        </div>
        <div class="hint">{{ t('settings.proxy.hint') }}</div>
      </div>

      <!-- 检查更新 -->
      <div class="card">
        <div class="card-title"><i class="fas fa-sync-alt"></i>{{ t('settings.group.update') }}</div>
        <div class="switch-row">
          <label class="label"><span class="t">{{ t('settings.update.enabled') }}</span></label>
          <span class="switch"><input v-model="updateCheckEnabled" type="checkbox" @change="pushUpdateCheck"/><span class="track"></span></span>
        </div>
        <div class="row">
          <button class="btn" :disabled="state.update.checking" @click="checkNow">
            <i class="fas fa-search" :class="{ 'fa-spin': state.update.checking }"></i>
            {{ state.update.checking ? t('settings.update.checking') : t('settings.update.now') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { state, patchSettings, patchPrefs, showToast } from '../store'
import { api } from '../bridge'
import { setLang, t } from '../i18n'
import { confirmDialog, pickDialog } from '../dialog'

// ------------------------------------------------------------ 本地偏好 ----

const langPref = ref(state.prefs.lang || '')
const sniffing = ref(state.prefs.sniffing)
const lightweight = ref(state.prefs.lightweight)
const proxy = reactive({ ...state.prefs.proxy })

function onLangChange() {
  patchPrefs({ lang: langPref.value })
  setLang(langPref.value || state.systemLang || 'zh')
}

function onSniffing() {
  patchPrefs({ sniffing: sniffing.value })
}

function onLightweight() {
  patchPrefs({ lightweight: lightweight.value })
}

function pushProxy() {
  patchPrefs({ proxy: { ...proxy } })
}

// ------------------------------------------------------------ 后端设置 ----

const themePref = ref(state.settings?.uiTheme || 'system')
const autoStart = ref(!!(state.autoStartEnabled || state.settings?.autoStart))
const startMinimized = ref(!!state.settings?.startMinimized)
const autoReconnect = ref(!!state.settings?.autoReconnect)
const intervalSeconds = ref(state.settings?.intervalSeconds ?? 30)
const disconnectOnNoInternet = ref(!!state.settings?.disconnectOnNoInternet)
const updateCheckEnabled = ref(state.settings?.updateCheckEnabled ?? true)

const dialOn = ref(false)
const dialHour = ref(8)
const dialMinute = ref(0)
const discOn = ref(false)
const discHour = ref(23)
const discMinute = ref(0)

watch(
  () => state.settings,
  (s) => {
    if (!s) return
    themePref.value = s.uiTheme || 'system'
    autoStart.value = state.autoStartEnabled || s.autoStart
    startMinimized.value = s.startMinimized
    autoReconnect.value = s.autoReconnect
    intervalSeconds.value = s.intervalSeconds
    disconnectOnNoInternet.value = s.disconnectOnNoInternet
    updateCheckEnabled.value = s.updateCheckEnabled
    dialOn.value = s.scheduledDial
    dialHour.value = s.scheduledDialHour
    dialMinute.value = s.scheduledDialMinute
    discOn.value = s.scheduledDisconnect
    discHour.value = s.scheduledDisconnectHour
    discMinute.value = s.scheduledDisconnectMinute
  },
  { immediate: true, deep: true }
)

function onThemeChange() {
  patchSettings({ uiTheme: themePref.value })
}

function onStartMinimized() {
  patchSettings({ startMinimized: startMinimized.value })
}

function onAutoStart() {
  const ok = api.SetAutoStart(autoStart.value)
  if (!ok) {
    autoStart.value = !autoStart.value
    showToast(t('settings.autostart'), t('settings.autostart.fail'), 'error')
  }
}

function clamp(v, lo, hi, dflt) {
  const n = Number(v)
  if (!Number.isFinite(n)) return dflt
  return Math.min(hi, Math.max(lo, Math.trunc(n)))
}

function pushReconnect() {
  patchSettings({ autoReconnect: autoReconnect.value })
}

function pushInterval() {
  intervalSeconds.value = clamp(intervalSeconds.value, 5, 3600, 30)
  patchSettings({ intervalSeconds: intervalSeconds.value })
}

function pushDisconnect() {
  patchSettings({ disconnectOnNoInternet: disconnectOnNoInternet.value })
}

function pushUpdateCheck() {
  patchSettings({ updateCheckEnabled: updateCheckEnabled.value })
}

function pushSchedule() {
  dialHour.value = clamp(dialHour.value, 0, 23, 8)
  dialMinute.value = clamp(dialMinute.value, 0, 59, 0)
  discHour.value = clamp(discHour.value, 0, 23, 23)
  discMinute.value = clamp(discMinute.value, 0, 59, 0)
  patchSettings({
    scheduledDial: dialOn.value,
    scheduledDialHour: dialHour.value,
    scheduledDialMinute: dialMinute.value,
    scheduledDisconnect: discOn.value,
    scheduledDisconnectHour: discHour.value,
    scheduledDisconnectMinute: discMinute.value
  })
}

function checkNow() {
  if (state.update.checking) return
  api.CheckUpdate(true)
}

// ------------------------------------------------------------ 选择设备 ----

const deviceBusy = ref(false)
const deviceNote = ref('')

async function selectDevice() {
  if (deviceBusy.value) return
  deviceBusy.value = true
  try {
    const devices = await api.DiagListDevices()
    if (!devices || !devices.length) {
      deviceNote.value = t('settings.device.none')
      return
    }
    const labels = devices.map((d) => `${d.device}  [${d.port}]`)
    const idx = await pickDialog(t('settings.device.action'), t('settings.device.hint'), labels)
    if (idx < 0) return
    const rewrite = await confirmDialog(t('common.confirm'), t('settings.device.rewriteConfirm'), true)
    const msg = await api.DiagSelectDevice(devices[idx].port, devices[idx].device, rewrite === true)
    deviceNote.value = msg || t('settings.device.done')
  } finally {
    deviceBusy.value = false
  }
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

.settings-grid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
  align-content: start;
  padding-bottom: 6px;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}

.row .field-label {
  min-width: 76px;
}

.row input[type="text"], .row input[type="number"] {
  flex: 1;
  min-width: 0;
  max-width: 220px;
}

.row select {
  flex: 1;
  max-width: 220px;
}

.time-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0 8px;
}

.time-row input {
  width: 58px;
  text-align: center;
  font-weight: 700;
}

.hint.ok {
  color: var(--c-success);
}
</style>
