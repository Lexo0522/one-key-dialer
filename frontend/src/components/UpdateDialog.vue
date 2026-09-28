<template>
  <div class="mask" @click.self="onMaskClick">
    <div class="dialog">
      <div class="dialog-title">{{ u.title || t('update.title') }}</div>

      <div class="dialog-body">
        <template v-if="active">
          <div class="status">{{ u.status || stageStatus }}</div>
          <div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100"
               :aria-valuenow="indeterminate ? null : u.progress"
               :aria-label="t('update.title')">
            <div v-if="indeterminate" class="bar-fill bar-indeterminate"></div>
            <div v-else class="bar-fill" :style="{ width: progressWidth }"></div>
          </div>
          <div class="progress-text">{{ progressText }}</div>
          <div v-if="hint" class="hint">{{ hint }}</div>
        </template>
        <template v-else>
          <div>{{ u.body }}</div>
          <div v-if="u.assetName" class="asset">
            <div>{{ assetName }}</div>
            <div class="hint">{{ assetSize }}</div>
          </div>
        </template>
      </div>

      <div class="dialog-actions">
        <template v-if="u.downloading">
          <button class="btn" :disabled="u.busy" @click="cancelDownload">{{ t('update.cancel') }}</button>
        </template>
        <template v-else-if="u.installing">
          <button class="btn" disabled>{{ t('update.installing') }}</button>
        </template>
        <template v-else-if="u.path">
          <button class="btn btn-primary" :disabled="u.busy" @click="install">{{ t('update.install') }}</button>
          <button class="btn" @click="close">{{ t('update.keepOnly') }}</button>
        </template>
        <template v-else>
          <button v-if="u.canInstall" class="btn btn-primary" :disabled="u.busy" @click="download">{{ t('update.download') }}</button>
          <button v-if="u.releaseUrl" class="btn" @click="openPage">{{ t('update.openPage') }}</button>
          <button class="btn" @click="close">{{ u.available ? t('update.later') : t('update.close') }}</button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { state, beginUpdateDownload, beginUpdateInstall, abortUpdateRequest } from '../store'
import { api } from '../bridge'
import { t } from '../i18n'
import { formatBytes } from '../format'

const emit = defineEmits(['close'])
const u = computed(() => state.update)

// 下载与解压都处在「进行中」：区别只在于能否取消、以及提示文案
const active = computed(() => u.value.downloading || u.value.installing)

// 服务端未给出总大小（chunked / 资产 size 缺失）时无法算百分比，
// 用不确定态动画代替停在 0% 的假进度。
const indeterminate = computed(() => u.value.total <= 0)

const progressWidth = computed(() => `${u.value.progress || 0}%`)
const assetName = computed(() => u.value.assetName || '')
const assetSize = computed(() => (u.value.assetSize > 0 ? formatBytes(u.value.assetSize) : ''))

const stageStatus = computed(() =>
  u.value.installing ? t('update.installing') : t('update.downloading'))

const hint = computed(() => {
  // 「安装时会退出程序」属于安装阶段的提示，放在下载阶段是文案错位
  if (u.value.installing) return t('update.exitNote')
  return ''
})

const progressText = computed(() => {
  const p = u.value
  if (indeterminate.value) {
    return `${formatBytes(p.downloaded)} · ${t('update.unknownSize')}`
  }
  return `${formatBytes(p.downloaded)} / ${formatBytes(p.total)} (${p.progress}%)`
})

// 进行中的更新不能被遮罩点掉：关掉后后台仍在跑，用户却失去了返回入口
function onMaskClick() {
  if (active.value) return
  close()
}

function close() {
  u.value.visible = false
  emit('close')
}

async function download() {
  // 先本地进入下载态再发请求：后端要等清单下载完成才会推送第一个 progress
  beginUpdateDownload()
  try {
    await api.DownloadUpdate()
  } catch (e) {
    // 请求没到后端就不会有任何回程事件，只能在这里复位
    abortUpdateRequest(t('update.error'))
  }
}

async function cancelDownload() {
  u.value.status = t('update.cancelling')
  try {
    await api.CancelUpdateDownload()
  } catch (e) {
    abortUpdateRequest()
  }
  // 状态收敛交给后端的 canceled / error 事件，前端不再自行断言结果
}

async function install() {
  beginUpdateInstall()
  try {
    await api.InstallUpdate()
  } catch (e) {
    abortUpdateRequest(t('update.error'))
  }
}

function openPage() {
  if (u.value.releaseUrl) api.OpenReleasePage(u.value.releaseUrl)
}
</script>

<style scoped>
.status {
  margin-bottom: 8px;
}

.bar {
  height: 14px;
  background: var(--c-bg);
  border: 1px solid var(--c-border);
  border-radius: 7px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: var(--c-info);
  transition: width .2s;
}

/* 总大小未知时的不确定进度：流动的色块不断前进，而不是卡在 0% */
.bar-indeterminate {
  width: 40%;
  transition: none;
  animation: bar-slide 1.2s ease-in-out infinite;
}

@keyframes bar-slide {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(250%); }
}

@media (prefers-reduced-motion: reduce) {
  .bar-indeterminate {
    animation-duration: 2.4s;
  }
}

.progress-text {
  margin-top: 6px;
  font-size: 12px;
  color: var(--c-text-sub);
}

.asset {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-border);
}
</style>
