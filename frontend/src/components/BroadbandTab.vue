<template>
  <div class="page broadband">
    <div class="page-head">
      <div class="page-title">
        <i class="fas fa-network-wired"></i>{{ t('broadband.title') }}
      </div>
    </div>

    <div class="bb-grid">
      <!-- 宽带账号 -->
      <section class="card settings-card">
        <div class="card-title"><i class="fas fa-user"></i>{{ t('broadband.group.account') }}</div>

        <div class="row">
          <label class="field-label">{{ t('broadband.username') }}</label>
          <input v-model.trim="form.username" type="text" autocomplete="off"/>
        </div>
        <div class="row">
          <label class="field-label">{{ t('broadband.password') }}</label>
          <input v-model="form.password" type="password" autocomplete="new-password"
                 :placeholder="form.hasPassword ? t('broadband.pwKeepHint') : ''"/>
        </div>
        <div class="row actions">
          <button class="btn btn-primary" @click="save">{{ t('broadband.save') }}</button>
        </div>
        <div class="hint"><i class="fas fa-circle-info"></i>{{ t('broadband.hint') }}</div>
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
          <button class="btn refresh-btn" :disabled="deviceBusy" :title="t('broadband.device.refresh')" @click="loadDevices">
            <span class="refresh-icon" :class="{ spinning: deviceBusy }" aria-hidden="true">⟳</span>
          </button>
        </div>
        <div class="hint" :class="{ ok: !!deviceNote }">{{ deviceNote || t('broadband.device.hint') }}</div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { state, saveBroadband, showToast } from '../store'
import { api } from '../bridge'
import { t } from '../i18n'

// ------------------------------------------------------------ 宽带账号 ----

const form = reactive({
  username: '',
  password: '',
  hasPassword: false
})

async function save() {
  const ok = await saveBroadband(form.username, form.password)
  if (ok) {
    form.hasPassword = form.hasPassword || !!form.password
    form.password = ''
  }
}

// ------------------------------------------------------------ 拨号设备 ----
// 下拉列表选择即应用：选中后立即记住设备并重写电话簿条目，无需弹窗确认。

const devices = ref([])
const selectedPort = ref('')
const deviceBusy = ref(false)
const deviceNote = ref('')

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

async function applyDevice() {
  const d = devices.value.find((x) => x.port === selectedPort.value)
  if (!d) return
  deviceBusy.value = true
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
  }
}

onMounted(() => {
  form.username = state.broadband?.username || ''
  form.hasPassword = !!state.broadband?.hasPassword
  loadDevices()
})
</script>

<style scoped>
.broadband {
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

/* 两卡片并排：账号 / 设备等宽 */
.bb-grid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
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

@media (max-width: 900px) {
  .bb-grid {
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
</style>
