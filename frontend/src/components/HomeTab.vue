<template>
  <div class="home">
    <!-- 账号行 -->
    <div class="card account-row">
      <label class="field-label">{{ t('home.account.label') }}</label>
      <select v-model.number="currentIndex" @change="onAccountChange">
        <option v-for="(a, i) in state.accounts" :key="i" :value="i">{{ accountLabel(a, i) }}</option>
      </select>
      <button class="btn" @click="emit('open-accounts')">{{ t('home.account.config') }}</button>
    </div>

    <!-- 配置区 -->
    <div class="card config">
      <div class="row">
        <label class="field-label">{{ t('home.nickname.label') }}</label>
        <input v-model="state.home.name" type="text" @change="pushHomeFields"/>
      </div>
      <div class="row">
        <label class="field-label">{{ t('home.username.label') }}</label>
        <input v-model="state.home.username" type="text" @change="pushHomeFields"/>
      </div>
      <div class="row">
        <label class="field-label">{{ t('home.password.label') }}</label>
        <input v-model="state.home.password" type="password" @change="pushHomeFields" :placeholder="pwPlaceholder"/>
      </div>
      <div class="row">
        <label class="field-label">{{ t('home.interval.label') }}</label>
        <input v-model.number="intervalSeconds" type="number" min="5" max="3600" step="5" @change="pushInterval"/>
      </div>
    </div>

    <!-- 选项区 -->
    <div class="card options">
      <div class="theme-row">
        <label class="field-label">{{ t('theme.label') }}</label>
        <select v-model="themePref" @change="onThemeChange">
          <option value="system">{{ t('theme.system') }}</option>
          <option value="light">{{ t('theme.light') }}</option>
          <option value="dark">{{ t('theme.dark') }}</option>
        </select>
        <span class="hint">{{ t('theme.liveHint') }}</span>
      </div>
      <label class="chk"><input v-model="autoReconnect" type="checkbox" @change="push"/>{{ t('home.autoReconnect') }}</label>
      <label class="chk"><input v-model="autoStart" type="checkbox" @change="onAutoStart"/>{{ t('home.autoStart') }}</label>
      <label class="chk"><input v-model="startMinimized" type="checkbox" @change="push"/>{{ t('home.startMinimized') }}</label>
      <label class="chk" :title="noInternetTip"><input v-model="disconnectOnNoInternet" type="checkbox" @change="push"/>{{ t('home.disconnectNoInternet') }}</label>
      <label class="chk" :title="updateCheckTip"><input v-model="updateCheckEnabled" type="checkbox" @change="push"/>{{ t('home.updateCheck') }}</label>
      <div class="hint">{{ t('home.autostartHint') }}</div>
    </div>

    <!-- 日志 -->
    <div class="log-wrapper">
      <div ref="logBox" class="console log-box"><span v-for="(l, i) in state.logs" :key="i" :class="'lv-' + l.level">{{ logLine(l) }}</span></div>
    </div>

    <!-- 拨号按钮 -->
    <div class="dial-row">
      <button class="btn dial-btn" :class="state.online ? 'btn-danger' : 'btn-primary'"
              :disabled="state.dialBusy" @click="onDialToggle">
        {{ dialButtonText }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { state, patchSettings, showToast, doDial, doDisconnect } from '../store'
import { api } from '../bridge'
import { t } from '../i18n'

const emit = defineEmits(['open-accounts'])

const noInternetTip = '拨号 RAS 成功但外网探测失败时自动 rasdial 断开；默认关闭以保留校园内网'
const updateCheckTip = '启动数秒后静默查询 Releases；关闭后仍可在托盘「检查更新」手动检查'

const logBox = ref(null)

const currentIndex = computed({
  get: () => state.currentIndex,
  set: (v) => { state.currentIndex = v }
})

const intervalSeconds = ref(30)
const autoReconnect = ref(false)
const autoStart = ref(false)
const startMinimized = ref(false)
const disconnectOnNoInternet = ref(false)
const updateCheckEnabled = ref(true)
const themePref = ref('system')

function syncFromSettings() {
  const s = state.settings
  if (!s) return
  intervalSeconds.value = s.intervalSeconds
  autoReconnect.value = s.autoReconnect
  autoStart.value = state.autoStartEnabled || s.autoStart
  startMinimized.value = s.startMinimized
  disconnectOnNoInternet.value = s.disconnectOnNoInternet
  updateCheckEnabled.value = s.updateCheckEnabled
  themePref.value = s.uiTheme || 'system'
}

watch(() => state.settings, syncFromSettings, { immediate: true, deep: true })

function accountLabel(a, i) {
  const name = (a.name || '').trim()
  const base = name || a.username || t('account.unset')
  return `${i + 1}. ${base}`
}

const pwPlaceholder = computed(() => {
  const cur = state.accounts[state.currentIndex]
  return cur && cur.hasPassword ? '（已保存，留空沿用）' : ''
})

const dialButtonText = computed(() => {
  if (state.dialBusy) return state.dialLabel || t('home.dial.dialing')
  return state.online ? t('home.dial.disconnect') : t('home.dial.connect')
})

function onDialToggle() {
  if (state.dialBusy) return
  if (state.online) {
    doDisconnect()
  } else {
    doDial()
  }
}

function onAccountChange() {
  api.SwitchAccount(state.currentIndex)
  const cur = state.accounts[state.currentIndex]
  if (cur) {
    state.home.name = cur.name || ''
    state.home.username = cur.username || ''
    state.home.password = ''
  }
}

function pushHomeFields() {
  api.UpdateHomeFields(state.home.name, state.home.username, state.home.password)
}

function push() {
  patchSettings({
    autoReconnect: autoReconnect.value,
    startMinimized: startMinimized.value,
    disconnectOnNoInternet: disconnectOnNoInternet.value,
    updateCheckEnabled: updateCheckEnabled.value
  })
}

function pushInterval() {
  const v = Math.min(3600, Math.max(5, Number(intervalSeconds.value) || 30))
  intervalSeconds.value = v
  patchSettings({ intervalSeconds: v })
}

function onAutoStart() {
  const ok = api.SetAutoStart(autoStart.value)
  if (!ok) {
    autoStart.value = !autoStart.value
    showToast(t('home.autoStart'), '注册失败，请使用打包后的 PPoEDialer.exe 运行', 'error')
  }
}

function onThemeChange() {
  patchSettings({ uiTheme: themePref.value })
}

function logLine(l) {
  return `[${l.time}] ${l.message}\n`
}

watch(
  () => state.logs.length,
  () => {
    nextTick(() => {
      if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight
    })
  }
)
</script>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  height: 100%;
  overflow: hidden;
}

.account-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
}

.account-row select {
  flex: 1;
  min-width: 0;
  height: 30px;
}

.config {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.config .row {
  display: grid;
  grid-template-columns: 92px 1fr;
  align-items: center;
  gap: 10px;
}

.config .row input {
  width: 100%;
}

.options {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.theme-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.log-wrapper {
  flex: 1;
  min-height: 90px;
  background: var(--c-card);
  border: 1px solid var(--c-border-light);
  border-radius: 8px;
  padding: 4px;
  position: relative;
}

.log-wrapper::before {
  content: attr(data-title);
}

.log-box {
  height: 100%;
  border-radius: 6px;
}

.lv-info { color: var(--c-console-fg); }
.lv-success { color: #7ee787; }
.lv-warn, .lv-warning { color: #ffd479; }
.lv-error { color: #ff9b9b; }

.dial-row {
  display: flex;
  justify-content: center;
  padding: 4px 0 8px;
  flex: 0 0 auto;
}

.dial-btn {
  width: 300px;
  height: 45px;
  font-size: 15px;
  font-weight: 700;
  border-radius: 6px;
}
</style>
