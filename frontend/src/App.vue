<template>
  <div v-if="!state.ready" class="loading">…</div>

  <div v-else class="app-root">
    <StatusBar/>

    <div class="tabs">
      <button v-for="tab in tabs" :key="tab.key"
              class="tab" :class="{ active: active === tab.key }"
              @click="active = tab.key">
        {{ tab.label }}
      </button>
      <span class="version">V{{ state.version }}</span>
    </div>

    <div class="content">
      <HomeTab v-show="active === 'home'" @open-accounts="showAccounts = true"/>
      <ScheduleTab v-if="active === 'schedule'"/>
      <ProbeTab v-if="active === 'probe'"/>
      <HistoryTab v-if="active === 'history'"/>
      <StatsTab v-if="active === 'stats'"/>
      <DiagTab v-if="active === 'diag'"/>
    </div>

    <AccountDialog v-if="showAccounts" @close="showAccounts = false"/>
    <UpdateDialog v-if="state.update.visible"/>

    <transition name="fade">
      <div v-if="state.toast.visible" class="toast" :class="'tone-' + state.toast.tone">
        <div class="toast-title">{{ state.toast.title }}</div>
        <div class="toast-body">{{ state.toast.body }}</div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import StatusBar from './components/StatusBar.vue'
import HomeTab from './components/HomeTab.vue'
import ScheduleTab from './components/ScheduleTab.vue'
import ProbeTab from './components/ProbeTab.vue'
import HistoryTab from './components/HistoryTab.vue'
import StatsTab from './components/StatsTab.vue'
import DiagTab from './components/DiagTab.vue'
import AccountDialog from './components/AccountDialog.vue'
import UpdateDialog from './components/UpdateDialog.vue'
import { state, bootstrap, bindEvents } from './store'
import { t } from './i18n'

const active = ref('home')
const showAccounts = ref(false)

const tabs = computed(() => [
  { key: 'home', label: t('tab.home') },
  { key: 'schedule', label: t('tab.schedule') },
  { key: 'probe', label: t('tab.probe') },
  { key: 'history', label: t('tab.history') },
  { key: 'stats', label: t('tab.stats') },
  { key: 'diag', label: t('tab.diag') }
])

onMounted(async () => {
  bindEvents()
  await bootstrap()
})
</script>

<style scoped>
.app-root {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--c-bg);
}

.loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--c-hint);
}

.tabs {
  display: flex;
  align-items: stretch;
  gap: 2px;
  padding: 6px 8px 0;
  border-bottom: 1px solid var(--c-border);
  flex: 0 0 auto;
}

.tab {
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--c-text-sub);
  font-family: inherit;
  font-size: 13px;
  padding: 6px 12px;
  cursor: pointer;
}

.tab:hover {
  color: var(--c-text);
}

.tab.active {
  color: var(--c-info);
  border-bottom-color: var(--c-info);
  font-weight: 700;
}

.version {
  margin-left: auto;
  align-self: center;
  font-size: 11px;
  color: var(--c-hint);
  padding-right: 4px;
}

.content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.toast {
  position: fixed;
  right: 14px;
  bottom: 14px;
  max-width: 320px;
  background: var(--c-card);
  border: 1px solid var(--c-border);
  border-left: 4px solid var(--c-info);
  border-radius: 6px;
  padding: 9px 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, .18);
  z-index: 800;
}

.toast.tone-success { border-left-color: var(--c-success); }
.toast.tone-error { border-left-color: var(--c-error); }

.toast-title {
  font-weight: 700;
  margin-bottom: 3px;
}

.toast-body {
  font-size: 12px;
  color: var(--c-text-sub);
  white-space: pre-wrap;
}

.fade-enter-active, .fade-leave-active { transition: opacity .2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
