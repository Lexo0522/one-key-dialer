<template>
  <div v-if="!state.ready" class="loading">
    <i class="fas fa-circle-notch fa-spin"></i>
  </div>

  <div v-else class="shell">
    <!-- 左侧栏 -->
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-logo"><i class="fas fa-bolt"></i></div>
        <div class="brand-text">
          <div class="brand-name">{{ t('nav.brand') }}</div>
          <div class="brand-sub">{{ t('nav.brandSub') }}</div>
        </div>
      </div>

      <nav class="nav">
        <button v-for="item in navItems" :key="item.key"
                class="nav-item" :class="{ active: active === item.key }"
                :title="item.label"
                @click="active = item.key">
          <span class="nav-icon"><i :class="item.icon"></i></span>
          <span class="nav-label">{{ item.label }}</span>
        </button>
      </nav>

      <div class="sidebar-footer">
        <div class="conn" :class="state.online ? 'on' : 'off'">
          <span class="dot"></span>
          <span>{{ state.online ? t('home.status.connected') : t('home.status.disconnected') }}</span>
        </div>
        <div class="ver">V{{ state.displayVersion || state.version }}</div>
      </div>
    </aside>

    <!-- 右侧主内容区 -->
    <main class="main">
      <HomeTab v-show="active === 'home'"/>
      <AccountsTab v-if="active === 'accounts'"/>
      <LogTab v-if="active === 'log'"/>
      <SettingsTab v-if="active === 'settings'"/>
    </main>

    <UpdateDialog v-if="state.update.visible" @close="state.update.visible = false"/>

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
import HomeTab from './components/HomeTab.vue'
import AccountsTab from './components/AccountsTab.vue'
import LogTab from './components/LogTab.vue'
import SettingsTab from './components/SettingsTab.vue'
import UpdateDialog from './components/UpdateDialog.vue'
import { state, bootstrap, bindEvents } from './store'
import { t } from './i18n'

const active = ref('home')

const navItems = computed(() => [
  { key: 'home', label: t('nav.home'), icon: 'fas fa-home' },
  { key: 'accounts', label: t('nav.accounts'), icon: 'fas fa-address-card' },
  { key: 'log', label: t('nav.log'), icon: 'fas fa-file-alt' },
  { key: 'settings', label: t('nav.settings'), icon: 'fas fa-cog' }
])

onMounted(async () => {
  bindEvents()
  await bootstrap()
})
</script>

<style scoped>
.shell {
  display: flex;
  height: 100%;
  background: var(--c-bg);
}

.loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--c-hint);
  font-size: 20px;
}

/* ---------------- 左侧栏 ---------------- */
.sidebar {
  width: var(--sidebar-w);
  flex: 0 0 var(--sidebar-w);
  display: flex;
  flex-direction: column;
  background: var(--c-sidebar);
  box-shadow: var(--c-sidebar-shadow);
  padding: 16px 10px 12px;
  z-index: 10;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px 14px;
}

.brand-logo {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--c-info), #6f42c1);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex: 0 0 auto;
}

.brand-name {
  font-weight: 800;
  font-size: 14px;
  letter-spacing: .5px;
}

.brand-sub {
  font-size: 10px;
  color: var(--c-sidebar-sub);
  margin-top: 1px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 12px;
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--c-sidebar-sub);
  font-family: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: background .15s var(--ease), color .15s var(--ease);
}

.nav-item:hover {
  background: var(--c-hover);
  color: var(--c-text);
}

.nav-item.active {
  background: var(--c-sidebar-active-bg);
  color: var(--c-sidebar-active);
  font-weight: 700;
}

.nav-item.active::before {
  content: "";
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 2px;
  background: var(--c-sidebar-active);
}

.nav-icon {
  width: 18px;
  display: inline-flex;
  justify-content: center;
  font-size: 14px;
}

.sidebar-footer {
  border-top: 1px dashed var(--c-table-grid);
  padding-top: 10px;
  margin-top: 8px;
}

.conn {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 6px;
}

.conn .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 auto;
}

.conn.on {
  color: var(--c-status-online);
}

.conn.on .dot {
  background: var(--c-status-online);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--c-status-online) 25%, transparent);
}

.conn.off {
  color: var(--c-hint);
}

.conn.off .dot {
  background: var(--c-hint);
}

.ver {
  font-size: 10px;
  color: var(--c-hint);
  padding: 2px 6px;
}

/* ---------------- 右侧主内容区 ---------------- */
.main {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* ---------------- 提示条 ---------------- */
.toast {
  position: fixed;
  right: 14px;
  bottom: 14px;
  max-width: 320px;
  background: var(--c-card);
  border: none;
  border-left: 4px solid var(--c-info);
  border-radius: var(--radius-md);
  padding: 10px 14px;
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
