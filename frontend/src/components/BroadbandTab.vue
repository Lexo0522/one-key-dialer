<template>
  <div class="page broadband">
    <div class="page-head">
      <div class="page-title">
        <i class="fas fa-network-wired"></i>{{ t('broadband.title') }}
      </div>
    </div>

    <!-- 连接状态条：状态 / 在线时长 / 会话流量 / PPP 详情（IP、协商速率） -->
    <section class="card status-card">
      <div class="s-left">
        <span class="status-dot" :class="state.online ? 'on' : 'off'"></span>
        <span class="s-text" :class="{ on: state.online }">{{ statusText }}</span>
        <span v-if="state.online && state.uptimeSeconds >= 0" class="chip">
          <i class="far fa-clock"></i>{{ formatDuration(state.uptimeSeconds) }}
        </span>
        <span v-if="state.online" class="chip">
          <i class="fas fa-arrow-up"></i>{{ formatSpeed(state.upSpeed) }}
          <i class="fas fa-arrow-down"></i>{{ formatSpeed(state.downSpeed) }}
        </span>
        <template v-if="state.online && ppp && ppp.available && ppp.connected">
          <span v-if="ppp.localIp" class="chip mono" :title="t('broadband.status.ip')">
            <i class="fas fa-globe"></i>{{ ppp.localIp }}
          </span>
          <span v-if="ppp.serverIp" class="chip mono" :title="t('broadband.status.serverIp')">
            <i class="fas fa-server"></i>{{ ppp.serverIp }}
          </span>
          <span v-if="linkSpeed" class="chip mono" :title="t('broadband.status.linkSpeed')">{{ linkSpeed }}</span>
          <span v-if="ppp.errTotal > 0" class="chip mono warn" :title="t('broadband.status.linkSpeed')">
            <i class="fas fa-exclamation-triangle"></i>{{ ppp.errTotal }}
          </span>
        </template>
        <span v-else-if="state.online && ppp && !ppp.available" class="chip dim">
          {{ t('broadband.status.unavailable') }}
        </span>
      </div>
      <div class="s-right">
        <button class="btn connect-btn" :class="state.online ? 'btn-danger' : 'btn-primary'"
                :disabled="state.dialBusy" @click="toggleDial">
          <i :class="state.online ? 'fas fa-power-off' : 'fas fa-plug'"></i>
          {{ dialButtonText }}
        </button>
      </div>
    </section>

    <div class="bb-grid">
      <!-- 宽带账号 -->
      <section class="card settings-card">
        <div class="card-title"><i class="fas fa-user"></i>{{ t('broadband.group.account') }}</div>

        <div class="row">
          <label class="field-label">{{ t('broadband.username') }}</label>
          <input v-model.trim="form.username" type="text" autocomplete="off" @keydown.enter.prevent="save"/>
        </div>
        <div class="row">
          <label class="field-label">{{ t('broadband.password') }}</label>
          <div class="pw-wrap">
            <input v-model="form.password" :type="showPw ? 'text' : 'password'" autocomplete="new-password"
                   :placeholder="form.hasPassword ? t('broadband.pwKeepHint') : ''" @keydown.enter.prevent="save"/>
            <button class="btn eye-btn" :title="showPw ? t('broadband.password') : t('broadband.password')"
                    @click="showPw = !showPw">
              <i :class="showPw ? 'fas fa-eye-slash' : 'fas fa-eye'"></i>
            </button>
          </div>
          <span v-if="form.hasPassword" class="pw-badge"><i class="fas fa-check-circle"></i></span>
        </div>
        <div class="row actions">
          <button class="btn btn-primary" :disabled="!dirty || !form.username.trim()" @click="save">
            {{ t('broadband.save') }}
          </button>
          <button class="btn" :disabled="state.dialBusy || !form.username.trim()" @click="onSaveAndDial">
            <i class="fas fa-plug"></i>{{ t('broadband.account.saveDial') }}
          </button>
          <button v-if="form.hasPassword || savedUsername" class="btn btn-danger clear-btn" @click="onClear">
            {{ t('broadband.account.clear') }}
          </button>
        </div>
        <div v-if="form.hasPassword && !form.password" class="pw-saved-hint">
          <i class="fas fa-check-circle"></i>{{ t('broadband.account.pwSavedBadge') }}
        </div>
        <div class="hint"><i class="fas fa-info-circle"></i>{{ t('broadband.hint') }}</div>

        <!-- 最近一次拨号结果（结构化回显，RAS 错误码与处理建议） -->
        <div v-if="lastDial" class="dial-result" :class="lastDial.ok ? 'ok' : 'fail'">
          <div class="dr-title">
            <i :class="lastDial.ok ? 'fas fa-check-circle' : 'fas fa-times-circle'"></i>
            {{ t('broadband.dial.lastTitle') }} ·
            {{ lastDial.ok ? t('broadband.dial.resultOk') : t('broadband.dial.resultFail') }}
            <span v-if="!lastDial.ok && lastDial.code > 0" class="dr-code">({{ lastDial.code }})</span>
          </div>
          <div v-if="lastDial.detail" class="dr-detail">{{ lastDial.detail }}</div>
        </div>
      </section>

      <!-- 拨号设备 -->
      <section class="card settings-card">
        <div class="card-title"><i class="fas fa-ethernet"></i>{{ t('broadband.group.device') }}</div>

        <div class="row">
          <label class="field-label">{{ t('broadband.device.label') }}</label>
          <select v-model="selectedPort" :disabled="deviceBusy || !devices.length" @change="applyDevice">
            <option v-if="!devices.length" value="">{{ t('broadband.device.none') }}</option>
            <option v-for="d in devices" :key="d.port" :value="d.port">{{ d.device }} [{{ d.port }}]</option>
          </select>
          <button class="btn refresh-btn" :disabled="deviceBusy" :title="t('broadband.device.refresh')" @click="refreshDevices">
            <span class="refresh-icon" :class="{ spinning: deviceBusy }" aria-hidden="true">⟳</span>
          </button>
        </div>
        <div class="hint" :class="{ ok: !!deviceNote && !applying }">
          <i class="fas fa-info-circle"></i>{{ deviceHintText }}
        </div>

        <!-- 物理网口插线状态：RAS 端口与网卡无系统级映射，独立展示作选卡参考 -->
        <div v-if="ethLinks.length" class="eth-block">
          <div class="eth-title">{{ t('broadband.device.ethLinks') }}</div>
          <div v-for="l in ethLinks" :key="l.descr" class="eth-row">
            <i :class="l.up ? 'fas fa-check-circle ok' : 'fas fa-times-circle dim'"></i>
            <span class="eth-descr" :title="l.descr">{{ l.descr }}</span>
            <span class="eth-state" :class="l.up ? 'ok' : 'dim'">
              {{ l.up ? tf('broadband.device.ethUp', l.speedMbps) : t('broadband.device.ethDown') }}
            </span>
          </div>
        </div>
      </section>
    </div>

    <!-- 一键诊断 -->
    <section class="card diag-card">
      <div class="diag-head">
        <div class="card-title"><i class="fas fa-stethoscope"></i>{{ t('broadband.diag.title') }}</div>
        <button class="btn btn-primary" :disabled="diag.running" @click="runDiag">
          <i v-if="diag.running" class="fas fa-circle-notch fa-spin"></i>
          {{ diag.running ? t('broadband.diag.running') : t('broadband.diag.run') }}
        </button>
      </div>
      <div class="hint"><i class="fas fa-info-circle"></i>{{ t('broadband.diag.hint') }}</div>

      <div v-if="diag.steps.length" class="diag-steps">
        <div v-for="(s, i) in diag.steps" :key="i" class="diag-step">
          <i :class="s.ok ? 'fas fa-check-circle ok' : 'fas fa-times-circle fail'"></i>
          <span>{{ s.text }}</span>
        </div>
        <div v-if="!diag.running && diag.ok !== null" class="diag-summary" :class="diag.ok ? 'ok' : 'fail'">
          {{ diag.ok ? t('broadband.diag.summaryOk') : t('broadband.diag.summaryFail') }}
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  state, doDial, doDisconnect, saveBroadband, saveAndDial, clearBroadband,
  runDiag, refreshPpp, formatSpeed, formatDuration, showToast
} from '../store'
import { api } from '../bridge'
import { t, tf } from '../i18n'

// ------------------------------------------------------------ 连接状态条 ----

const ppp = computed(() => state.ppp)
const diag = computed(() => state.diag)

const statusText = computed(() => {
  if (state.dialBusy) return state.dialLabel || t('home.dial.dialing')
  return state.online ? t('broadband.status.connected') : t('broadband.status.offline')
})

const dialButtonText = computed(() => {
  if (state.dialBusy) return state.dialLabel || t('home.dial.dialing')
  return state.online ? t('home.dial.disconnect') : t('home.dial.connect')
})

function toggleDial() {
  if (state.dialBusy) return
  if (state.online) doDisconnect()
  else doDial()
}

function formatBps(bps) {
  const mbps = bps / 1e6
  if (mbps >= 1000) return (mbps / 1000).toFixed(1) + ' Gbps'
  return Math.round(mbps) + ' Mbps'
}

const linkSpeed = computed(() => (ppp.value && ppp.value.bps > 0 ? formatBps(ppp.value.bps) : ''))

// 在线时轮询 PPP 详情；轻量化模式自动降频，页面销毁即停
let pppTimer = null
function startPppPolling() {
  stopPppPolling()
  refreshPpp()
  pppTimer = setInterval(refreshPpp, state.prefs.lightweight ? 6000 : 3000)
}
function stopPppPolling() {
  if (pppTimer) {
    clearInterval(pppTimer)
    pppTimer = null
  }
}
watch(() => state.online, (online) => {
  if (online) startPppPolling()
  else stopPppPolling()
})
watch(() => state.prefs.lightweight, startPppPolling)

// ------------------------------------------------------------ 宽带账号 ----

const form = reactive({
  username: '',
  password: '',
  hasPassword: false
})
const showPw = ref(false)

const savedUsername = computed(() => (state.broadband?.username || '').trim())
// 脏状态：账号有改动或填了新密码才允许保存
const dirty = computed(() => form.username !== savedUsername.value || !!form.password)

const lastDial = computed(() => state.lastDial)

function validate() {
  if (!form.username.trim()) {
    showToast(t('broadband.account.usernameRequired'), 'warning')
    return false
  }
  return true
}

async function save() {
  if (!validate()) return
  const ok = await saveBroadband(form.username, form.password)
  if (ok) {
    form.hasPassword = form.hasPassword || !!form.password
    form.password = ''
  }
}

async function onSaveAndDial() {
  if (!validate()) return
  const hadPw = !!form.password
  await saveAndDial(form.username, form.password)
  form.hasPassword = form.hasPassword || hadPw
  form.password = ''
}

async function onClear() {
  await clearBroadband()
  form.password = ''
}

// 后端侧账号变化（清除/他处保存）同步回表单
watch(() => state.broadband, () => {
  form.username = state.broadband?.username || ''
  form.hasPassword = !!state.broadband?.hasPassword
}, { deep: true })

// ------------------------------------------------------------ 拨号设备 ----
// 下拉列表选择即应用：选中后立即记住设备并重写电话簿条目，无需弹窗确认。

const devices = ref([])
const selectedPort = ref('')
const deviceBusy = ref(false)
const applying = ref(false)
const deviceNote = ref('')
const ethLinks = ref([])

const deviceHintText = computed(() => {
  if (applying.value) return t('broadband.device.applying')
  return deviceNote.value || t('broadband.device.hint')
})

// 选中项回显优先级：后端标记的当前设备 → 内置默认设备 → 列表首项。
// 三者必居其一（后端保证列表非空），因此下拉框不会再出现空白。
function pickPort(list) {
  if (!list.length) return ''
  const cur = list.find((x) => x.current)
  if (cur) return cur.port
  const dflt = list.find((x) => x.default)
  if (dflt) return dflt.port
  return list[0].port
}

async function loadDevices() {
  deviceBusy.value = true
  try {
    const list = await api.DiagListDevices()
    devices.value = list || []
    if (!devices.value.length) {
      selectedPort.value = ''
      deviceNote.value = t('broadband.device.none')
      return
    }
    // 已选值仍在列表中则保持不变（刷新、切页不丢选择），否则回显后端当前值
    const stillThere = devices.value.some((x) => x.port === selectedPort.value)
    if (!stillThere) selectedPort.value = pickPort(devices.value)
  } finally {
    deviceBusy.value = false
  }
}

async function loadEthLinks() {
  try {
    ethLinks.value = (await api.EthLinks()) || []
  } catch (e) {
    ethLinks.value = []
  }
}

async function refreshDevices() {
  await Promise.all([loadDevices(), loadEthLinks()])
}

async function applyDevice() {
  const d = devices.value.find((x) => x.port === selectedPort.value)
  if (!d) return
  deviceBusy.value = true
  applying.value = true
  try {
    const msg = await api.DiagSelectDevice(d.port, d.device, true)
    deviceNote.value = msg || t('broadband.device.done')
    // 同步 current 标记，避免下次刷新时回显被旧数据覆盖
    devices.value = devices.value.map((x) => ({ ...x, current: x.port === d.port && x.device === d.device }))
    showToast(t('broadband.device.switched'), 'success')
  } catch (e) {
    deviceNote.value = t('broadband.device.switchFail')
    showToast(t('broadband.device.switchFail'), 'error')
    await loadDevices()
  } finally {
    deviceBusy.value = false
    applying.value = false
  }
}

onMounted(() => {
  form.username = state.broadband?.username || ''
  form.hasPassword = !!state.broadband?.hasPassword
  refreshDevices()
  startPppPolling()
})

onUnmounted(stopPppPolling)
</script>

<style scoped>
.broadband {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
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

/* 连接状态条 */
.status-card {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px 16px;
}

.s-left,
.s-right {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.s-right {
  flex: 0 0 auto;
  margin-left: auto;
}

.status-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex: 0 0 auto;
}

.status-dot.on {
  background: var(--c-status-online);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--c-status-online) 25%, transparent);
}

.status-dot.off {
  background: var(--c-hint);
}

.s-text {
  font-size: 13px;
  font-weight: 700;
}

.s-text.on {
  color: var(--c-status-online);
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--c-text-sub);
  background: var(--c-card);
  border: 1px solid var(--c-border);
  border-radius: 999px;
  padding: 3px 10px;
  white-space: nowrap;
}

.chip i {
  font-size: 10px;
  color: var(--c-info);
}

.chip.mono {
  font-family: Consolas, monospace;
}

.chip.warn,
.chip.warn i {
  color: var(--c-warning);
}

.chip.dim {
  color: var(--c-hint);
}

.connect-btn {
  min-width: 104px;
  height: 32px;
  font-weight: 700;
}

/* 双卡片并排：账号 / 设备等宽 */
.bb-grid {
  flex: 0 0 auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  align-items: stretch;
}

@media (max-width: 900px) {
  .bb-grid {
    grid-template-columns: minmax(0, 1fr);
  }
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

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}

.row .field-label {
  min-width: 64px;
  white-space: nowrap;
}

.row input[type="text"],
.row input[type="password"],
.row select {
  flex: 1;
  min-width: 0;
  max-width: none;
}

.row.actions {
  padding-top: 10px;
  flex-wrap: wrap;
}

.row.actions .btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.clear-btn {
  margin-left: auto;
}

/* 密码框 + 可见性切换 */
.pw-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.pw-wrap input {
  flex: 1;
  min-width: 0;
}

.eye-btn {
  flex: 0 0 auto;
  width: 30px;
  height: 30px;
  padding: 0;
}

.eye-btn i {
  font-size: 12px;
  color: var(--c-hint);
}

.pw-badge {
  flex: 0 0 auto;
  color: var(--c-success);
  font-size: 12px;
}

.pw-saved-hint {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--c-success);
}

.pw-saved-hint i {
  font-size: 10px;
}

/* 最近一次拨号结果 */
.dial-result {
  margin-top: 10px;
  border: 1px solid var(--c-border);
  border-left: 3px solid var(--c-hint);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  font-size: 12px;
}

.dial-result.ok {
  border-left-color: var(--c-success);
}

.dial-result.fail {
  border-left-color: var(--c-error);
  background: color-mix(in srgb, var(--c-error) 5%, transparent);
}

.dr-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 700;
  flex-wrap: wrap;
}

.dial-result.ok .dr-title {
  color: var(--c-success);
}

.dial-result.fail .dr-title {
  color: var(--c-error);
}

.dr-code {
  font-family: Consolas, monospace;
}

.dr-detail {
  margin-top: 4px;
  color: var(--c-text-sub);
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.hint {
  margin-top: 8px;
}

.hint.ok {
  color: var(--c-success);
}

.hint i {
  margin-right: 5px;
  color: var(--c-info);
}

.refresh-btn {
  width: 32px;
  height: 30px;
  padding: 0;
  flex: 0 0 32px;
}

.refresh-icon {
  display: inline-block;
  font-family: Arial, sans-serif;
  font-size: 23px;
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

/* 物理网口插线状态 */
.eth-block {
  margin-top: 10px;
  border-top: 1px dashed var(--c-table-grid);
  padding-top: 8px;
}

.eth-title {
  font-size: 11px;
  color: var(--c-hint);
  margin-bottom: 4px;
}

.eth-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 2px 0;
  min-width: 0;
}

.eth-row i {
  flex: 0 0 auto;
  font-size: 11px;
}

.eth-row .ok {
  color: var(--c-success);
}

.eth-row .fail,
.eth-row .dim {
  color: var(--c-hint);
}

.eth-descr {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--c-text-sub);
}

.eth-state {
  flex: 0 0 auto;
  font-size: 11px;
}

.eth-state.ok {
  color: var(--c-success);
}

/* 一键诊断 */
.diag-card {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  padding: 14px 18px;
}

.diag-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.diag-steps {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.diag-step {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--c-text-sub);
  min-width: 0;
}

.diag-step i {
  flex: 0 0 auto;
  font-size: 12px;
}

.diag-step .ok {
  color: var(--c-success);
}

.diag-step .fail {
  color: var(--c-error);
}

.diag-summary {
  margin-top: 6px;
  font-size: 12px;
  font-weight: 700;
}

.diag-summary.ok {
  color: var(--c-success);
}

.diag-summary.fail {
  color: var(--c-warning);
}
</style>
