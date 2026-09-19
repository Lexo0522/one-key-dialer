<template>
  <div class="status-bar" :class="online ? 'on' : 'off'">
    <div class="left">
      <span class="dot">●</span>
      <span class="label">{{ online ? t('home.status.connected') : t('home.status.disconnected') }}</span>
    </div>
    <div class="right">
      <span>{{ speed }}</span>
      <span>{{ uptime }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { state, formatSpeed, formatDuration } from '../store'
import { t } from '../i18n'

const online = computed(() => state.online)
const speed = computed(() =>
  state.online ? `↓ ${formatSpeed(state.downSpeed)} ↑ ${formatSpeed(state.upSpeed)}` : t('home.status.speed')
)
const uptime = computed(() =>
  state.uptimeSeconds >= 0 ? `时长: ${formatDuration(state.uptimeSeconds)}` : t('home.status.uptimeNone')
)
</script>

<style scoped>
.status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 15px;
  color: #fff;
  flex: 0 0 auto;
  transition: background .25s;
}

.status-bar.on {
  background: var(--c-status-online);
}

.status-bar.off {
  background: var(--c-info);
}

.left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot {
  font-size: 16px;
  font-weight: 700;
  line-height: 1;
}

.label {
  font-weight: 700;
}

.right {
  display: flex;
  gap: 15px;
  font-size: 11px;
  opacity: .85;
}
</style>
