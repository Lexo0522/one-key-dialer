<template>
  <div v-if="!state.ready" class="loading">
    <i class="fas fa-circle-notch fa-spin"></i>
  </div>

  <div v-else class="shell">
    <!-- 左侧栏 -->
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-logo"><img :src="logoUrl" alt=""></div>
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
        <SideTraffic/>
        <div class="conn" :class="state.online ? 'on' : 'off'">
          <span class="dot"></span>
          <span>{{ state.online ? t('home.status.connected') : t('home.status.disconnected') }}</span>
        </div>
        <!-- 有新版本时在版本号右侧给出快捷入口：默认只剩圆图标，
             hover 平滑展开成蓝色胶囊，点按直接进入下载并安装流程 -->
        <div class="ver-row">
          <div class="ver">{{ versionLabel() }}</div>
          <button v-if="state.update.available && state.update.canInstall"
                  class="ver-update" :class="{ spinning: state.update.checking || state.update.busy }"
                  :title="t('settings.update.action.title')"
                  :aria-label="t('settings.update.action')"
                  @click="onSidebarUpdate">
            <span class="ver-update-icon"><i class="fas fa-download"></i></span>
            <span class="ver-update-text">{{ t('settings.update.action') }}</span>
          </button>
        </div>
      </div>
    </aside>

    <!-- 右侧主内容区 -->
    <main class="main">
      <HomeTab v-show="active === 'home'"/>
      <WifiTab v-if="active === 'wifi'"/>
      <BroadbandTab v-if="active === 'broadband'"/>
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
import WifiTab from './components/WifiTab.vue'
import BroadbandTab from './components/BroadbandTab.vue'
import LogTab from './components/LogTab.vue'
import SettingsTab from './components/SettingsTab.vue'
import UpdateDialog from './components/UpdateDialog.vue'
import SideTraffic from './components/SideTraffic.vue'
import { state, bootstrap, bindEvents, versionLabel,
         beginUpdateDownload, abortUpdateRequest } from './store'
import { TOAST_DURATION } from './toast'
import { installToastFoldAnimator } from './toast-fold'
import { t } from './i18n'
import { api } from './bridge'
import logoUrl from './assets/logo.png'

const active = ref('home')

const navItems = computed(() => [
  { key: 'home', label: t('nav.home'), icon: 'fas fa-home' },
  { key: 'wifi', label: t('nav.wifi'), icon: 'fas fa-wifi' },
  { key: 'broadband', label: t('nav.broadband'), icon: 'fas fa-network-wired' },
  { key: 'log', label: t('nav.log'), icon: 'fas fa-file-alt' },
  { key: 'settings', label: t('nav.settings'), icon: 'fas fa-cog' }
])

/** 侧栏更新按钮：直接开下载。版本信息已由静默检查或结果事件带入
 *  state.update，此处不必再查一次，省掉一轮网络往返。 */
function onSidebarUpdate() {
  const u = state.update
  if (u.checking || u.busy || u.downloading || u.installing) return
  if (!u.canInstall) return
  beginUpdateDownload()
  Promise.resolve(api.DownloadUpdate()).catch(() => abortUpdateRequest(t('update.error')))
}

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
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}

.brand-logo img {
  width: 100%;
  height: 100%;
  object-fit: contain;
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

/* 侧栏版本行：版本号 + 更新快捷按钮（有新版本时才出现） */
.ver-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
}

.ver-row .ver {
  padding: 0;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 收起态是直径 22px 的圆按钮，hover 时图标收起、文字展开成胶囊：
    图标与文字都从 0 宽过渡，避免出现「先撑开再显示内容」的跳动。 */
.ver-update {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: 11px;
  background: var(--c-info);
  color: #fff;
  font-family: inherit;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  overflow: hidden;
  transition: padding .22s var(--ease), box-shadow .22s var(--ease), filter .15s var(--ease);
}

.ver-update:hover {
  padding: 0 9px 0 9px;
  filter: brightness(1.08);
  box-shadow: 0 2px 8px rgba(0, 123, 255, .35);
}

.ver-update:active {
  filter: brightness(.94);
}

/* 图标格收起态占满整个按钮（22px 圆），hover 时宽度归零让位给文字 */
.ver-update-icon {
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  opacity: 1;
  transition: flex-basis .22s var(--ease), width .22s var(--ease),
              opacity .18s var(--ease), margin .22s var(--ease);
}

.ver-update:hover .ver-update-icon {
  flex-basis: 0;
  width: 0;
  opacity: 0;
}

/* 文字初始宽度为 0，hover 时随最大宽过渡展开 */
.ver-update-text {
  display: inline-block;
  max-width: 0;
  opacity: 0;
  white-space: nowrap;
  overflow: hidden;
  transition: max-width .22s var(--ease), opacity .18s var(--ease);
}

.ver-update:hover .ver-update-text {
  max-width: 60px;
  opacity: 1;
}

/* 检查 / 下载进行中：图标转圈，按钮暂时不响应（状态由对话框承载） */
.ver-update.spinning {
  cursor: default;
  filter: saturate(.7) brightness(.95);
}

.ver-update.spinning .ver-update-icon i {
  animation: ver-update-spin 1s linear infinite;
}

@keyframes ver-update-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .ver-update,
  .ver-update-icon,
  .ver-update-text {
    transition-duration: .01ms;
  }

  .ver-update.spinning .ver-update-icon i {
    animation-duration: 2.4s;
  }
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

  /* 图标栏下版本号隐藏，更新按钮独占一行并居中 */
  .ver-row {
    justify-content: center;
    padding: 4px 0 0;
  }

  .ver-row .ver {
    display: none;
  }
}
</style>
