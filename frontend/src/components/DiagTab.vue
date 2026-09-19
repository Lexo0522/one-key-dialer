<template>
  <div class="diag">
    <div class="buttons">
      <button v-for="b in buttons" :key="b.action" class="btn" :disabled="busy" @click="run(b.action)">
        {{ b.label }}
      </button>
      <button class="btn" :disabled="busy" @click="selectDevice">{{ t('diag.device') }}</button>
      <button class="btn" :disabled="busy" @click="rewritePhonebook">{{ t('diag.rewrite') }}</button>
      <button class="btn" @click="clear">{{ t('diag.clear') }}</button>
    </div>

    <div ref="box" class="console out">{{ state.diagText }}</div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { state } from '../store'
import { api } from '../bridge'
import { t } from '../i18n'
import { confirmDialog, pickDialog } from '../dialog'

const box = ref(null)
const busy = computed(() => state.diagBusy)

const buttons = computed(() => [
  { action: 'ping', label: t('diag.ping') },
  { action: 'ipconfig', label: t('diag.ipconfig') },
  { action: 'tracert', label: t('diag.trace') },
  { action: 'flushdns', label: t('diag.dns') },
  { action: 'status', label: t('diag.status') },
  { action: 'phonebook', label: t('diag.phonebook') }
])

watch(
  () => state.diagText,
  () => {
    nextTick(() => {
      if (box.value) box.value.scrollTop = box.value.scrollHeight
    })
  }
)

async function run(action) {
  if (state.diagBusy) return
  state.diagBusy = true
  try {
    await api.DiagAction(action)
  } finally {
    state.diagBusy = false
  }
}

function clear() {
  state.diagText = ''
  api.DiagClear()
}

async function selectDevice() {
  const devices = await api.DiagListDevices()
  if (!devices || !devices.length) {
    state.diagText += `\n[${t('diag.noDevice') || '未找到可用 PPPoE 设备提示'}]\n`
    return
  }
  const labels = devices.map((d) => `${d.device}  [${d.port}]${d.existing ? '' : ' (默认)'}`)
  const idx = await pickDialog(t('diag.deviceDlgTitle'), t('diag.selectDeviceTitle'), labels)
  if (idx < 0) return
  const rewrite = await confirmDialog(t('diag.rewriteTitle'), t('diag.rewriteConfirm'), true)
  const msg = await api.DiagSelectDevice(devices[idx].port, devices[idx].device, rewrite === true)
  state.diagText += `\n[设备] ${msg}\n`
}

async function rewritePhonebook() {
  const ok = await confirmDialog(t('diag.rewriteTitle'), t('diag.rewriteWarn'))
  if (ok) {
    const msg = await api.DiagRewritePhonebook()
    state.diagText += `\n[电话簿] ${msg}\n`
  }
}

</script>

<style scoped>
.diag {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  height: 100%;
}

.buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.out {
  flex: 1;
  overflow: auto;
  border: 1px solid var(--c-border);
}
</style>
