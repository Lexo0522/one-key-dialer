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

    <!-- 灵动岛 Toast：顶部居中小圆角胶囊堆叠排队，hover 暂停倒计时 -->
    <GooeyToaster
      position="top-center"
      :theme="state.theme"
      :duration="TOAST_DURATION"
      :gap="10"
      offset="16px"
      :max-queue="3"
      queue-overflow="drop-oldest"
      preset="smooth"
      close-button="top-right"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { GooeyToaster } from 'vue-goey-toast'
import HomeTab from './components/HomeTab.vue'
import AccountsTab from './components/AccountsTab.vue'
import LogTab from './components/LogTab.vue'
import SettingsTab from './components/SettingsTab.vue'
import UpdateDialog from './components/UpdateDialog.vue'
import { state, bootstrap, bindEvents } from './store'
import { TOAST_DURATION } from './toast'
import { installToastFoldAnimator } from './toast-fold'
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
  installToastFoldAnimator()
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

/* 窗口较窄时侧栏收敛为纯图标栏，把宽度让给主内容区，
   避免主区被固定 208px 侧栏挤到无法正常展示 */
@media (max-width: 720px) {
  .sidebar {
    width: 56px;
    flex-basis: 56px;
    padding: 16px 8px 12px;
  }

  .brand {
    justify-content: center;
    padding: 0 0 14px;
  }

  .brand-text,
  .nav-label {
    display: none;
  }

  .nav-item {
    justify-content: center;
    padding: 9px 0;
  }

  /* 图标栏下隐藏激活指示条，保持图标居中 */
  .nav-item.active::before {
    display: none;
  }

  .conn {
    justify-content: center;
  }

  .conn span:last-child,
  .ver {
    display: none;
  }
}
</style>
