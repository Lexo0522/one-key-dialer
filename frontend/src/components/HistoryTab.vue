<template>
  <div class="history">
    <div class="title">{{ t('history.title') }}</div>

    <div class="table-box">
      <table class="grid">
        <thead>
        <tr>
          <th style="width:140px">{{ t('history.col.time') }}</th>
          <th style="width:55px">{{ t('history.col.op') }}</th>
          <th style="width:90px">{{ t('history.col.account') }}</th>
          <th style="width:55px">{{ t('history.col.result') }}</th>
          <th style="width:75px">{{ t('history.col.duration') }}</th>
          <th style="width:100px">{{ t('history.col.traffic') }}</th>
        </tr>
        </thead>
        <tbody>
        <tr v-if="!state.history.length">
          <td colspan="6" class="empty">—</td>
        </tr>
        <tr v-for="(r, i) in rows" :key="i">
          <td>{{ r.time }}</td>
          <td>{{ r.operation }}</td>
          <td>{{ r.account }}</td>
          <td :class="toneOf(r.result)">{{ r.result }}</td>
          <td>{{ r.duration }}</td>
          <td>{{ r.traffic }}</td>
        </tr>
        </tbody>
      </table>
    </div>

    <div class="actions">
      <button class="btn" @click="onExport">{{ t('history.export') }}</button>
      <button class="btn" @click="onClear">{{ t('history.clear') }}</button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { state } from '../store'
import { api } from '../bridge'
import { t } from '../i18n'
import { confirmDialog } from '../dialog'

const rows = computed(() => state.history.slice().reverse())

function toneOf(result) {
  if (!result) return ''
  if (result.includes('成功')) return 'ok'
  if (result.includes('失败')) return 'bad'
  return ''
}

async function onExport() {
  await api.ExportHistory()
}

async function onClear() {
  const ok = await confirmDialog(t('history.confirmTitle'), t('history.confirmClear'))
  if (ok) {
    await api.ClearHistory()
    state.history = []
  }
}
</script>

<style scoped>
.history {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 15px;
  height: 100%;
}

.title {
  font-weight: 700;
}

.table-box {
  flex: 1;
  overflow: auto;
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
  background: var(--c-viewport);
}

.empty {
  text-align: center;
  color: var(--c-hint);
  padding: 18px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.ok { color: var(--c-success); }
.bad { color: var(--c-error); }
</style>
