<template>
  <div class="page wifi">
    <div class="page-head">
      <div class="page-title">
        <i class="fas fa-wifi"></i>{{ t('wifi.title') }}
      </div>
    </div>

    <div class="wifi-grid">
      <!-- 左列：状态 + 网络列表 -->
      <div class="col">
        <!-- 当前状态 -->
        <section class="card wifi-card">
          <div class="card-title"><i class="fas fa-signal"></i>{{ t('wifi.title') }}</div>
          <div v-if="!available" class="hint warn-hint">
            <i class="fas fa-triangle-exclamation"></i>{{ t('wifi.status.unavailable') }}
          </div>
          <template v-else>
            <div class="status-line">
              <i class="fas fa-wifi status-icon" :class="phaseClass"></i>
              <div class="status-text">
                <div class="ssid">{{ status.ssid || t('wifi.status.idle') }}</div>
                <div class="sub" v-if="status.connected">{{ t('wifi.status.signal') }} {{ status.signalQuality }}%</div>
                <div class="sub" v-else>{{ phaseLabel }}</div>
              </div>
              <button v-if="status.connected || busy"
                      class="btn" :disabled="busy" @click="onDisconnect">
                {{ t('wifi.disconnect') }}
              </button>
            </div>
          </template>
        </section>

        <!-- 网络列表 -->
        <section class="card wifi-card grow">
          <div class="card-title list-title">
            <span><i class="fas fa-tower-broadcast"></i>{{ t('wifi.group.network') }}</span>
            <button class="btn icon-btn" :disabled="scanBusy || !available" :title="t('wifi.scan')" @click="onRefresh">
              <span class="refresh-icon" :class="{ spinning: scanBusy }" aria-hidden="true">⟳</span>
            </button>
          </div>

          <div v-if="available" class="net-list">
            <div v-if="!networks.length" class="hint">{{ t('wifi.scan.empty') }}</div>
            <div v-for="n in networks" :key="n.ssid" class="net-item" :class="{ current: n.connected }">
              <div class="net-row" @click="toggleExpand(n)">
                <span class="net-icon" :class="signalClass(n.signalQuality)">
                  <i class="fas" :class="n.secured ? 'fa-wifi' : 'fa-wifi'"></i>
                  <i v-if="n.secured" class="fas fa-lock lock"></i>
                </span>
                <span class="net-ssid">{{ n.ssid }}</span>
                <span v-if="n.connected" class="badge on">{{ t('wifi.status.connected') }}</span>
                <span class="badge" v-else>{{ n.auth === 'Open' ? t('wifi.authOpen') : n.auth }}</span>
                <button class="btn sm" :disabled="busy" @click.stop="onConnect(n)">
                  <i v-if="isConnectingTo(n.ssid)" class="fas fa-circle-notch fa-spin"></i>
                  {{ t('wifi.connect') }}
                </button>
              </div>
              <div v-if="expanded === n.ssid && n.secured" class="pw-row">
                <input v-model="passwords[n.ssid]" type="password" :placeholder="t('wifi.pwPlaceholder')"
                       @keyup.enter="onConnect(n)"/>
                <span class="hint">{{ n.hasProfile ? t('wifi.auth.pwKeepHint') : '' }}</span>
              </div>
            </div>
            <div class="hint pw-hint">{{ t('wifi.pwHint') }}</div>
          </div>
        </section>
      </div>

      <!-- 右列：自动连接 + 门户自动认证 -->
      <div class="col">
        <section class="card wifi-card">
          <div class="card-title"><i class="fas fa-rotate"></i>{{ t('wifi.group.autoConnect') }}</div>
          <div class="switch-row">
            <label class="label">
              <span class="t">{{ t('wifi.autoConnect.enable') }}</span>
              <span class="d">{{ t('wifi.autoConnect.enableHint') }}</span>
            </label>
            <span class="switch"><input v-model="autoConnect" type="checkbox" @change="pushAutoConnect"/><span class="track"></span></span>
          </div>
          <div class="row">
            <label class="field-label">{{ t('wifi.autoConnect.preferred') }}</label>
            <select v-model="preferredSsid" :disabled="!available" @change="pushAutoConnect">
              <option value="">{{ t('wifi.autoConnect.preferredNone') }}</option>
              <option v-for="n in preferredOptions" :key="n" :value="n">{{ n }}</option>
            </select>
          </div>
        </section>

        <section class="card wifi-card grow">
          <div class="card-title"><i class="fas fa-user-shield"></i>{{ t('wifi.group.auth') }}</div>

          <div class="switch-row">
            <label class="label">
              <span class="t">{{ t('wifi.auth.enable') }}</span>
              <span class="d">{{ t('wifi.auth.enableHint') }}</span>
            </label>
            <span class="switch"><input v-model="authEnabled" type="checkbox" @change="pushAuth"/><span class="track"></span></span>
          </div>

          <div class="row">
            <label class="field-label">{{ t('wifi.auth.preset') }}</label>
            <select v-model="portal.preset" @change="applyPreset">
              <option v-for="p in PORTAL_PRESETS" :key="p.key" :value="p.key">{{ t('wifi.auth.preset.' + p.key) }}</option>
            </select>
          </div>
          <div class="hint">{{ t('wifi.auth.presetHint') }}</div>

          <div class="row">
            <label class="field-label">{{ t('wifi.auth.loginUrl') }}</label>
            <input v-model="portal.loginUrl" type="text" placeholder="http://10.1.1.55/login" @change="pushAuth"/>
          </div>
          <div class="hint">{{ t('wifi.auth.loginUrlHint') }}</div>

          <div class="row">
            <label class="field-label">{{ t('wifi.auth.method') }}</label>
            <select v-model="portal.method" @change="pushAuth">
              <option value="POST">POST</option>
              <option value="GET">GET</option>
              <option value="SRUN">SRUN</option>
            </select>
          </div>

          <div class="row">
            <label class="field-label">{{ t('wifi.auth.body') }}</label>
            <textarea v-model="portal.body" rows="3" spellcheck="false" @change="pushAuth"></textarea>
          </div>
          <div class="hint" v-if="portal.method === 'SRUN'">{{ t('wifi.auth.bodySrunHint') }}</div>
          <div class="hint" v-else>{{ t('wifi.auth.bodyHint') }}</div>

          <div class="row">
            <label class="field-label">{{ t('wifi.auth.headers') }}</label>
            <textarea v-model="portal.headers" rows="2" spellcheck="false" @change="pushAuth"></textarea>
          </div>
          <div class="hint">{{ t('wifi.auth.headersHint') }}</div>

          <div class="row">
            <label class="field-label">{{ t('wifi.auth.successHint') }}</label>
            <input v-model="portal.successHint" type="text" @change="pushAuth"/>
          </div>
          <div class="hint">{{ t('wifi.auth.successHintHint') }}</div>

          <div class="divider"></div>

          <div class="row">
            <label class="field-label">{{ t('wifi.auth.username') }}</label>
            <input v-model="portal.username" type="text" autocomplete="off"/>
          </div>
          <div class="row">
            <label class="field-label">{{ t('wifi.auth.password') }}</label>
            <input v-model="portal.password" type="password" autocomplete="new-password"
                   :placeholder="portal.hasPassword ? t('wifi.auth.pwKeepHint') : ''"/>
          </div>
          <div class="row actions">
            <button class="btn btn-primary" @click="saveCredential">{{ t('common.confirm') }}</button>
            <button class="btn" :disabled="testing" @click="runTest">
              <i v-if="testing" class="fas fa-circle-notch fa-spin"></i>
              {{ testing ? t('wifi.auth.testing') : t('wifi.auth.test') }}
            </button>
          </div>
          <div v-if="testResult" class="test-result" :class="{ ok: testResult.ok }">
            <div class="test-title">{{ testResult.ok ? t('wifi.auth.testOk') : t('wifi.auth.testFail') }}</div>
            <pre class="test-detail">{{ testResult.detail }}</pre>
          </div>
          <div class="hint howto"><i class="fas fa-circle-info"></i>{{ t('wifi.auth.howTo') }}</div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  state, patchSettings, refreshWifiStatus, scanWifi, connectWifi, disconnectWifi,
  loadPortalCredential, savePortalCredential, testPortalAuth, showToast
} from '../store'
import { t } from '../i18n'
import { PORTAL_PRESETS, getPreset } from '../portalPresets'

// ------------------------------------------------------------ WiFi 状态 ----

const available = computed(() => state.wifi.available)
const status = computed(() => state.wifi.status || {})
const networks = computed(() => state.wifi.networks)
const scanBusy = ref(false)

/** 连接/断开流程进行中（由后端 busy 字段表达：仅本应用发起的动作才锁定界面）。
 *  不用 OS 的 phase 判定：网卡自发重连/漫游时 phase 也会是 connecting，
 *  照它禁用会把整页按钮卡死。 */
const busy = computed(() => !!(status.value && status.value.busy))

const phaseLabel = computed(() => {
  const map = {
    connecting: t('wifi.status.connecting'),
    disconnecting: t('wifi.status.disconnecting'),
    connected: t('wifi.status.connected'),
    idle: t('wifi.status.idle')
  }
  return map[status.value.phase] || t('wifi.status.idle')
})

const phaseClass = computed(() => ({
  on: status.value.connected,
  dim: !status.value.connected
}))

/** 点击行展开密码输入；开放网络直接连。 */
const expanded = ref('')
const passwords = reactive({})
const connectingTo = ref('')

function isConnectingTo(ssid) {
  return busy.value && connectingTo.value === ssid
}

function toggleExpand(n) {
  expanded.value = expanded.value === n.ssid ? '' : n.ssid
}

function signalClass(q) {
  if (q >= 75) return 'sig-4'
  if (q >= 50) return 'sig-3'
  if (q >= 25) return 'sig-2'
  return 'sig-1'
}

async function onRefresh() {
  if (scanBusy.value) return
  scanBusy.value = true
  try {
    await Promise.all([refreshWifiStatus(), scanWifi(true)])
  } finally {
    scanBusy.value = false
  }
}

async function onConnect(n) {
  if (busy.value) return
  const pw = (passwords[n.ssid] || '').trim()
  if (n.secured && !pw && !n.hasProfile) {
    expanded.value = n.ssid
    showToast(t('wifi.pwPlaceholder'), 'info')
    return
  }
  connectingTo.value = n.ssid
  const ok = await connectWifi(n.ssid, pw)
  if (!ok) connectingTo.value = ''
}

async function onDisconnect() {
  await disconnectWifi()
}

// 连接失败兜底：busy 从 true 回落到 false 且仍未连接时提示。
// 只针对手动发起的连接（connectingTo 非空），自动连接的后台尝试失败不弹条。
watch(
  [() => status.value.busy, () => status.value.connected],
  ([b, c], was) => {
    if (was[0] && !b && !c && connectingTo.value) {
      showToast(t('wifi.connectFail'), 'error')
    }
    if (!b) connectingTo.value = ''
  }
)

// 兜底看门狗：busy 正常由后端在动作收尾时复位；事件丢失等极端情况下
// 按钮会被钉死在禁用，定期向后端索要一次真实状态自愈。
let busyWatchdog = null
watch(
  () => status.value.busy,
  (b) => {
    clearTimeout(busyWatchdog)
    if (b) busyWatchdog = setTimeout(() => refreshWifiStatus(), 40000)
  }
)
onUnmounted(() => clearTimeout(busyWatchdog))

// ------------------------------------------------------------ 自动连接 ----

const autoConnect = ref(!!state.settings?.wifiAutoConnect)
const preferredSsid = ref(state.settings?.wifiPreferredSsid || '')

/** 下拉选项 = 扫描结果 ∪ 当前已保存值。 */
const preferredOptions = computed(() => {
  const set = new Set(networks.value.map((n) => n.ssid))
  if (preferredSsid.value) set.add(preferredSsid.value)
  return Array.from(set)
})

function pushAutoConnect() {
  patchSettings({
    wifiAutoConnect: autoConnect.value,
    wifiPreferredSsid: (preferredSsid.value || '').trim()
  })
}

// ------------------------------------------------------------ 门户认证 ----

const authEnabled = ref(!!state.settings?.portalAuthEnabled)
const portal = reactive({
  preset: state.settings?.portalPreset || 'generic',
  loginUrl: state.settings?.portalLoginUrl || '',
  method: state.settings?.portalMethod || 'POST',
  body: state.settings?.portalBody || '',
  headers: state.settings?.portalHeaders || '',
  successHint: state.settings?.portalSuccessHint || '',
  username: '',
  password: '',
  hasPassword: false
})

const testing = ref(false)
const testResult = ref(null)

/** 切换预设:按模板覆盖五个配置字段(通用档 = 清空回初始状态),字段仍可手动修改。 */
function applyPreset() {
  const p = getPreset(portal.preset)
  if (p.fill) {
    portal.loginUrl = p.fill.loginUrl
    portal.method = p.fill.method
    portal.body = p.fill.body
    portal.headers = p.fill.headers
    portal.successHint = p.fill.successHint
  }
  pushAuth()
}

function pushAuth() {
  patchSettings({
    portalAuthEnabled: authEnabled.value,
    portalPreset: portal.preset,
    portalLoginUrl: (portal.loginUrl || '').trim(),
    portalMethod: portal.method,
    portalBody: (portal.body || '').trim(),
    portalHeaders: (portal.headers || '').trim(),
    portalSuccessHint: (portal.successHint || '').trim()
  })
}

async function saveCredential() {
  const ok = await savePortalCredential(portal.username, portal.password)
  if (ok) {
    portal.hasPassword = portal.hasPassword || !!portal.password
    portal.password = ''
  }
}

async function runTest() {
  if (testing.value) return
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testPortalAuth()
  } finally {
    testing.value = false
  }
}

// ------------------------------------------------------------ 初始化 ----

/** 后端设置被别处更新时（事件回推），同步本页的开关/下拉。 */
watch(
  () => state.settings,
  (s) => {
    if (!s) return
    autoConnect.value = !!s.wifiAutoConnect
    preferredSsid.value = s.wifiPreferredSsid || ''
    authEnabled.value = !!s.portalAuthEnabled
    portal.preset = s.portalPreset || 'generic'
    portal.loginUrl = s.portalLoginUrl || ''
    portal.method = s.portalMethod || 'POST'
    portal.body = s.portalBody || ''
    portal.headers = s.portalHeaders || ''
    portal.successHint = s.portalSuccessHint || ''
  },
  { deep: true }
)

onMounted(async () => {
  await Promise.all([refreshWifiStatus(), scanWifi(false)])
  const cred = await loadPortalCredential()
  portal.username = cred.username || ''
  portal.hasPassword = !!cred.hasPassword
})
</script>

<style scoped>
.wifi {
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

.wifi-grid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  padding-bottom: 6px;
}

.col {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.wifi-card {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wifi-card.grow {
  flex: 1;
}

.wifi-card > .card-title {
  padding-bottom: 8px;
  margin-bottom: 2px;
  border-bottom: 1px dashed var(--c-table-grid);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.warn-hint {
  color: var(--c-warning, var(--c-hint));
}

/* 状态行 */
.status-line {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
}

.status-icon {
  font-size: 22px;
  color: var(--c-hint);
}

.status-icon.on {
  color: var(--c-success);
}

.status-text {
  flex: 1;
  min-width: 0;
}

.ssid {
  font-weight: 700;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub {
  font-size: 11px;
  color: var(--c-hint);
}

/* 网络列表 */
.list-title {
  gap: 8px;
}

.icon-btn {
  width: 30px;
  height: 26px;
  padding: 0;
}

.refresh-icon {
  display: inline-block;
  font-family: Arial, sans-serif;
  font-size: 20px;
  line-height: 1;
  transform: translateY(-1px);
}

.refresh-icon.spinning {
  animation: refresh-rotate .8s linear infinite;
}

@keyframes refresh-rotate {
  to {
    transform: translateY(-1px) rotate(360deg);
  }
}

.net-list {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.net-item {
  border-radius: var(--radius-md);
  transition: background .15s var(--ease);
}

.net-item:hover {
  background: var(--c-hover);
}

.net-item.current {
  background: color-mix(in srgb, var(--c-success) 8%, transparent);
}

.net-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 6px;
  cursor: pointer;
  min-width: 0;
}

.net-icon {
  position: relative;
  width: 26px;
  display: inline-flex;
  justify-content: center;
  font-size: 15px;
  flex: 0 0 auto;
}

.net-icon .lock {
  position: absolute;
  right: -3px;
  bottom: -2px;
  font-size: 8px;
  color: var(--c-hint);
}

.sig-4 { opacity: 1; }
.sig-3 { opacity: .75; }
.sig-2 { opacity: .5; }
.sig-1 { opacity: .3; }

.net-ssid {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badge {
  flex: 0 0 auto;
  font-size: 10px;
  color: var(--c-hint);
  border: 1px solid var(--c-table-grid);
  border-radius: 999px;
  padding: 1px 8px;
}

.badge.on {
  color: var(--c-success);
  border-color: var(--c-success);
}

.btn.sm {
  padding: 4px 10px;
  font-size: 12px;
  flex: 0 0 auto;
}

.pw-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 6px 8px 42px;
}

.pw-row input {
  flex: 1;
  min-width: 0;
  max-width: 260px;
}

.pw-row .hint {
  flex: 1;
}

.pw-hint {
  padding: 4px 6px 0;
}

/* 表单 */
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 0;
}

.row .field-label {
  min-width: 72px;
  flex: 0 0 auto;
}

.row select,
.row input[type="text"],
.row input[type="password"],
.row textarea {
  flex: 1;
  min-width: 0;
  max-width: none;
}

.row textarea {
  resize: vertical;
  font-family: inherit;
  line-height: 1.5;
}

.divider {
  border-top: 1px dashed var(--c-table-grid);
  margin: 8px 0 4px;
}

.actions {
  padding-top: 8px;
}

.test-result {
  border: 1px solid var(--c-table-grid);
  border-radius: var(--radius-md);
  padding: 8px 10px;
  margin-top: 6px;
  font-size: 12px;
}

.test-result.ok {
  border-color: var(--c-success);
}

.test-title {
  font-weight: 700;
  margin-bottom: 4px;
}

.test-detail {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--c-hint);
  font-family: inherit;
  max-height: 140px;
  overflow-y: auto;
}

.howto {
  margin-top: 6px;
}

@media (max-width: 980px) {
  .wifi-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
