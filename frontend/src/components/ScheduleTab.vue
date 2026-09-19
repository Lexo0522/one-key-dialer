<template>
  <div class="schedule">
    <div class="card">
      <label class="chk title"><input v-model="dialOn" type="checkbox" @change="push"/>{{ t('schedule.dial') }}</label>
      <div class="time-row">
        <span>{{ t('schedule.everyDay') }}</span>
        <input v-model.number="dialHour" type="number" min="0" max="23" @change="push"/>
        <span>{{ t('schedule.hour') }}</span>
        <input v-model.number="dialMinute" type="number" min="0" max="59" @change="push"/>
        <span>{{ t('schedule.minute') }}</span>
      </div>
    </div>

    <div class="card">
      <label class="chk title"><input v-model="discOn" type="checkbox" @change="push"/>{{ t('schedule.disconnect') }}</label>
      <div class="time-row">
        <span>{{ t('schedule.everyDay') }}</span>
        <input v-model.number="discHour" type="number" min="0" max="23" @change="push"/>
        <span>{{ t('schedule.hour') }}</span>
        <input v-model.number="discMinute" type="number" min="0" max="59" @change="push"/>
        <span>{{ t('schedule.minute') }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { state, patchSettings } from '../store'
import { t } from '../i18n'

const dialOn = ref(false)
const dialHour = ref(8)
const dialMinute = ref(0)
const discOn = ref(false)
const discHour = ref(23)
const discMinute = ref(0)

function sync() {
  const s = state.settings
  if (!s) return
  dialOn.value = s.scheduledDial
  dialHour.value = s.scheduledDialHour
  dialMinute.value = s.scheduledDialMinute
  discOn.value = s.scheduledDisconnect
  discHour.value = s.scheduledDisconnectHour
  discMinute.value = s.scheduledDisconnectMinute
}

watch(() => state.settings, sync, { immediate: true, deep: true })

function clamp(v, lo, hi, dflt) {
  const n = Number(v)
  if (!Number.isFinite(n)) return dflt
  return Math.min(hi, Math.max(lo, Math.trunc(n)))
}

function push() {
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
</script>

<style scoped>
.schedule {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 15px;
}

.chk.title {
  font-weight: 700;
  margin-bottom: 6px;
}

.time-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 4px;
}

.time-row input {
  width: 58px;
  font-weight: 700;
  text-align: center;
}
</style>
