<template>
  <div class="mask" @click.self="close">
    <div class="dialog">
      <div class="dialog-title">{{ u.title || t('update.title') }}</div>

      <div class="dialog-body">
        <template v-if="u.downloading">
          <div class="status">{{ u.status || t('update.downloading') }}</div>
          <div class="bar">
            <div class="bar-fill" :style="{ width: progressWidth }"></div>
          </div>
          <div class="progress-text">{{ progressText }}</div>
          <div class="hint">{{ t('update.exitNote') }}</div>
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
          <button class="btn" @click="cancelDownload">{{ t('update.cancel') }}</button>
        </template>
        <template v-else-if="u.path">
          <button class="btn btn-primary" @click="install">{{ t('update.install') }}</button>
          <button class="btn" @click="close">{{ t('update.keepOnly') }}</button>
        </template>
        <template v-else>
          <button v-if="u.canInstall" class="btn btn-primary" @click="download">{{ t('update.download') }}</button>
          <button v-if="u.releaseUrl" class="btn" @click="openPage">{{ t('update.openPage') }}</button>
          <button class="btn" @click="close">{{ u.available ? t('update.later') : t('update.close') }}</button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { state } from '../store'
import { api } from '../bridge'
import { t } from '../i18n'
import { formatBytes } from '../format'

const emit = defineEmits(['close'])
const u = computed(() => state.update)

const progressWidth = computed(() => `${u.value.progress || 0}%`)
const assetName = computed(() => u.value.assetName || '')
const assetSize = computed(() => (u.value.assetSize > 0 ? formatBytes(u.value.assetSize) : ''))
const progressText = computed(() => {
  const p = u.value
  if (p.total > 0) {
    return `${formatBytes(p.downloaded)} / ${formatBytes(p.total)} (${p.progress}%)`
  }
  return formatBytes(p.downloaded)
})

function close() {
  u.value.visible = false
  emit('close')
}

async function download() {
  await api.DownloadUpdate()
}

async function cancelDownload() {
  await api.CancelUpdateDownload()
  u.value.downloading = false
}

async function install() {
  await api.InstallUpdate()
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
