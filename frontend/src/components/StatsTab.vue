<template>
  <div class="stats">
    <div class="top">
      <button class="btn" @click="refresh">{{ t('stats.refresh') }}</button>
      <span class="hint">{{ t('stats.hint') }}</span>
    </div>
    <div class="console area">{{ report || ' ' }}</div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../bridge'
import { t } from '../i18n'

const report = ref('')

async function refresh() {
  const s = await api.GetStats()
  report.value = s.ReportText || ''
}

onMounted(refresh)
</script>

<style scoped>
.stats {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  height: 100%;
}

.top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.area {
  flex: 1;
  overflow: auto;
}
</style>
