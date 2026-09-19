<template>
  <div class="probe">
    <div class="card form">
      <div class="row">
        <label class="field-label">{{ t('probe.mode') }}</label>
        <select v-model="mode" @change="push">
          <option value="auto">auto</option>
          <option value="icmp">icmp</option>
          <option value="http">http</option>
        </select>
      </div>
      <div class="row">
        <label class="field-label">{{ t('probe.host') }}</label>
        <input v-model="host" type="text" @change="push"/>
      </div>
      <div class="row">
        <label class="field-label">{{ t('probe.url') }}</label>
        <input v-model="httpUrl" type="text" @change="push"/>
      </div>
      <div class="row">
        <label class="field-label">{{ t('probe.attempts') }}</label>
        <input v-model.number="attempts" type="number" min="1" max="10" @change="push"/>
      </div>
      <div class="row">
        <label class="field-label">{{ t('probe.delay') }}</label>
        <input v-model.number="delayMs" type="number" min="0" max="10000" step="100" @change="push"/>
      </div>
      <div class="test-row">
        <button class="btn" :disabled="testing" @click="runTest">{{ t('probe.test') }}</button>
        <span class="result" :class="resultTone">{{ resultText }}</span>
      </div>
    </div>

    <div class="card">
      <pre class="hint-text">{{ t('probe.hint') }}</pre>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { state, patchSettings } from '../store'
import { api } from '../bridge'
import { t } from '../i18n'

const mode = ref('auto')
const host = ref('223.5.5.5')
const httpUrl = ref('http://connectivitycheck.gstatic.com/generate_204')
const attempts = ref(3)
const delayMs = ref(1000)

const testing = ref(false)
const resultText = ref(' ')
const resultTone = ref('')

function sync() {
  const s = state.settings
  if (!s) return
  mode.value = s.probeMode || 'auto'
  host.value = s.probeHost || ''
  httpUrl.value = s.probeHttpUrl || ''
  attempts.value = s.probeAttempts
  delayMs.value = s.probeDelayMs
}

watch(() => state.settings, sync, { immediate: true, deep: true })

function clamp(v, lo, hi, dflt) {
  const n = Number(v)
  if (!Number.isFinite(n)) return dflt
  return Math.min(hi, Math.max(lo, Math.trunc(n)))
}

function push() {
  host.value = (host.value || '').trim() || '223.5.5.5'
  httpUrl.value = (httpUrl.value || '').trim() || 'http://connectivitycheck.gstatic.com/generate_204'
  attempts.value = clamp(attempts.value, 1, 10, 3)
  delayMs.value = clamp(delayMs.value, 0, 10000, 1000)
  patchSettings({
    probeMode: mode.value,
    probeHost: host.value,
    probeHttpUrl: httpUrl.value,
    probeAttempts: attempts.value,
    probeDelayMs: delayMs.value
  })
}

async function runTest() {
  if (testing.value) return
  push()
  testing.value = true
  resultText.value = `${t('probe.testing')} mode=${mode.value}`
  resultTone.value = 'info'
  try {
    const r = await api.TestConnectivity()
    resultText.value = `${t('probe.result')}${r.line || (r.ok ? 'ok' : 'fail')}`
    resultTone.value = r.ok ? 'ok' : 'bad'
  } catch (e) {
    resultText.value = `${t('probe.result')}${String(e)}`
    resultTone.value = 'bad'
  } finally {
    testing.value = false
  }
}
</script>

<style scoped>
.probe {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 15px;
  height: 100%;
  overflow: auto;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.row {
  display: grid;
  grid-template-columns: 92px 1fr;
  align-items: center;
  gap: 10px;
}

.row input {
  width: 100%;
}

.test-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.result {
  font-size: 12px;
}

.result.ok { color: var(--c-success); }
.result.bad { color: var(--c-error); }
.result.info { color: var(--c-info); }

.hint-text {
  margin: 0;
  color: var(--c-hint);
  font-family: inherit;
  font-size: 11px;
  line-height: 1.7;
  white-space: pre-wrap;
}
</style>
