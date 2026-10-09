<template>
  <div class="page logs">
    <div class="page-head">
      <div class="page-title">
        <i class="fas fa-file-alt"></i>{{ t('log.title') }}
        <span class="count-chip">{{ tf('log.count', filtered.length) }}</span>
      </div>
      <div class="toolbar">
        <div class="search">
          <i class="fas fa-search"></i>
          <input v-model="keyword" type="text" :placeholder="t('log.search')"/>
        </div>
        <label class="chk"><input v-model="autoScroll" type="checkbox"/>{{ t('log.autoScroll') }}</label>
        <button class="btn icon-btn" @click="clear">
          <i class="fas fa-eraser"></i>{{ t('log.clear') }}
        </button>
      </div>
    </div>

    <div class="card filter-card">
      <button v-for="lv in levels" :key="lv.key"
              class="chip" :class="{ active: level === lv.key }"
              @click="level = lv.key">
        <span class="chip-dot" :class="'lv-' + lv.key"></span>
        {{ lv.label }}
        <span class="chip-num">{{ counts[lv.key] }}</span>
      </button>
    </div>

    <div class="card log-card">
      <div ref="box" class="log-box">
        <div v-if="!filtered.length" class="empty">
          <i class="far fa-folder-open"></i>
          <span>{{ t('log.empty') }}</span>
        </div>
        <!-- key 用 time+message 而不是数组下标：日志是头插/尾插混合的，
             下标 key 在 splice 后全部错位，Vue 会把整列 DOM 复用出错误内容。 -->
        <div v-for="(l, i) in filtered" :key="l.time + '|' + l.message + '|' + i" class="log-row"
             :class="'lv-' + l.level" :title="t('log.copyHint')"
             @click="copyRow(l)">
          <span class="log-time">{{ l.time }}</span>
          <span class="log-badge" :class="'lv-' + l.level">{{ levelLabel(l.level) }}</span>
          <span class="log-msg">{{ l.message }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { state, showToast } from '../store'
import { t, tf } from '../i18n'

const level = ref('all')
const keyword = ref('')
const autoScroll = ref(true)
const box = ref(null)

const levels = computed(() => [
  { key: 'all', label: t('log.filter.all') },
  { key: 'info', label: t('log.level.info') },
  { key: 'success', label: t('log.level.success') },
  { key: 'warn', label: t('log.level.warn') },
  { key: 'error', label: t('log.level.error') }
])

function normLevel(l) {
  if (l.level === 'warning') return 'warn'
  return l.level || 'info'
}

function levelLabel(lv) {
  switch (lv) {
    case 'success': return t('log.level.success')
    case 'warn':
    case 'warning': return t('log.level.warn')
    case 'error': return t('log.level.error')
    default: return t('log.level.info')
  }
}

// 单击复制整条日志（时间 + 级别 + 消息）。与首页 IP 复制同一套降级路径：
// WebView2 未授予剪贴板权限时 navigator.clipboard 会 reject，退回 execCommand。
async function copyRow(l) {
  const text = `${l.time} [${levelLabel(l.level)}] ${l.message}`
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    showToast(t('log.copied'), 'success')
  } catch (e) {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    ta.remove()
    showToast(ok ? t('log.copied') : t('toast.copyFail'), ok ? 'success' : 'error')
  }
}

// counts 与 filtered 合成一次遍历：原来两个 computed 各扫一遍 500 条，
// 每条新日志到达要走两轮全量。现在聚合结果与过滤结果一起产出。
// 关键词走防抖值：每敲一个字符都重算 500 条 filter 会明显掉帧。
const debouncedKeyword = ref('')
let keywordTimer = null
watch(keyword, (v) => {
  if (keywordTimer) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => {
    debouncedKeyword.value = v.trim().toLowerCase()
  }, 200)
})
onBeforeUnmount(() => {
  if (keywordTimer) clearTimeout(keywordTimer)
})

const logView = computed(() => {
  const kw = debouncedKeyword.value
  const lv = level.value
  const c = { all: 0, info: 0, success: 0, warn: 0, error: 0 }
  const out = []
  for (const l of state.logs) {
    const n = normLevel(l)
    c.all++
    c[n]++
    if (lv !== 'all' && n !== lv) continue
    if (kw && !String(l.message || '').toLowerCase().includes(kw)) continue
    out.push(l)
  }
  return { counts: c, rows: out }
})

const counts = computed(() => logView.value.counts)
const filtered = computed(() => logView.value.rows)

function clear() {
  state.logs = []
}

watch(
  () => filtered.value.length,
  async () => {
    if (!autoScroll.value) return
    await nextTick()
    if (box.value) box.value.scrollTop = box.value.scrollHeight
  }
)
</script>

<style scoped>
.logs {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  flex: 0 0 auto;
}

.page-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 800;
}

.page-title i {
  color: var(--c-info);
}

.count-chip {
  font-size: 11px;
  font-weight: 700;
  color: var(--c-info);
  background: var(--c-accent-soft);
  border-radius: 999px;
  padding: 1px 9px;
}

.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.search {
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--c-card);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  padding: 0 8px;
  height: 28px;
  flex: 1 1 140px;
  min-width: 0;
  max-width: 260px;
}

.search i {
  color: var(--c-hint);
  font-size: 11px;
}

.search input {
  border: none;
  background: transparent;
  padding: 0;
  height: 100%;
  flex: 1;
  min-width: 60px;
  width: auto;
  outline: none;
}

/* 上面的 outline:none 会盖掉全局焦点环（scoped 选择器特异性更高），
   键盘用户需要这一条才能看见焦点。 */
.search input:focus-visible {
  outline: 2px solid var(--c-info);
  outline-offset: -2px;
}

.filter-card {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 14px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: var(--c-bg);
  color: var(--c-text-sub);
  font-family: inherit;
  font-size: 12px;
  padding: 4px 12px;
  cursor: pointer;
  transition: all .15s var(--ease);
}

.chip:hover {
  color: var(--c-text);
}

.chip.active {
  background: var(--c-accent-soft);
  border-color: color-mix(in srgb, var(--c-info) 45%, transparent);
  color: var(--c-info);
  font-weight: 700;
}

.chip-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-hint);
}

.chip-dot.lv-info { background: var(--c-info); }
.chip-dot.lv-success { background: var(--c-success); }
.chip-dot.lv-warn { background: var(--c-warning); }
.chip-dot.lv-error { background: var(--c-error); }

.chip-num {
  font-size: 11px;
  color: var(--c-hint);
}

/* 日志主体 */
.log-card {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  padding: 0;
  overflow: hidden;
}

.log-box {
  flex: 1;
  overflow: auto;
  background: var(--c-log-bg);
  border-radius: var(--radius-lg);
  padding: 8px 4px;
  font-family: Consolas, "Microsoft YaHei", monospace;
  font-size: 12px;
  user-select: text;
}

.log-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 2px 12px;
  line-height: 1.7;
  cursor: pointer;
}

.log-row:hover {
  background: var(--c-hover);
}

.log-time {
  color: var(--c-hint);
  flex: 0 0 auto;
  font-size: 11px;
}

.log-badge {
  flex: 0 0 auto;
  font-size: 10px;
  font-weight: 700;
  border-radius: 4px;
  padding: 0 6px;
  line-height: 16px;
  text-align: center;
}

.log-badge.lv-info { color: var(--c-info); background: color-mix(in srgb, var(--c-info) 12%, transparent); }
.log-badge.lv-success { color: var(--c-success); background: color-mix(in srgb, var(--c-success) 12%, transparent); }
.log-badge.lv-warn { color: #b45309; background: color-mix(in srgb, var(--c-warning) 18%, transparent); }
.log-badge.lv-error { color: var(--c-error); background: color-mix(in srgb, var(--c-error) 12%, transparent); }

.log-msg {
  min-width: 0;
  word-break: break-all;
  white-space: pre-wrap;
}

.log-row.lv-error .log-msg { color: var(--c-error); }
.log-row.lv-warn .log-msg { color: #b45309; }
.log-row.lv-success .log-msg { color: var(--c-success); }

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 40px 0;
  color: var(--c-hint);
  font-family: inherit;
}

.empty i {
  font-size: 26px;
}
</style>
