<template>
  <div class="page home">
    <!-- 顶部：左账号选择 / 右连接按钮 -->
    <div class="card home-header">
      <div class="h left">
        <span class="status-dot" :class="state.online ? 'on' : 'off'"
              :title="state.online ? t('home.status.connected') : t('home.status.disconnected')"></span>
        <select v-model.number="currentIndex" class="account-select" @change="onAccountChange">
          <option v-for="(a, i) in state.accounts" :key="i" :value="i">{{ accountLabel(a, i) }}</option>
        </select>
        <span v-if="state.online && state.uptimeSeconds >= 0" class="uptime-chip">
          <i class="far fa-clock"></i>{{ t('home.uptime') }} {{ formatDuration(state.uptimeSeconds) }}
        </span>
      </div>
      <div class="h right">
        <button class="btn connect-btn" :class="state.online ? 'btn-danger' : 'btn-primary'"
                :disabled="state.dialBusy" @click="onDialToggle">
          <i :class="state.online ? 'fas fa-power-off' : 'fas fa-plug'"></i>
          {{ dialButtonText }}
        </button>
      </div>
    </div>

    <!-- 近 10 分钟流量折线图 -->
    <div class="card chart-card">
      <div class="card-head">
        <div class="card-title"><i class="fas fa-chart-line"></i>{{ t('home.chart.title') }}</div>
        <div class="legend">
          <span class="lg up">{{ t('home.chart.up') }}</span>
          <span class="lg down">{{ t('home.chart.down') }}</span>
          <span v-if="!state.prefs.sniffing" class="lg paused">
            <i class="fas fa-pause"></i>{{ t('home.chart.paused') }}
          </span>
        </div>
      </div>

      <div class="chart-wrap">
        <svg ref="chartWrap" class="chart" :viewBox="`0 0 ${vbW} 250`" preserveAspectRatio="xMidYMid meet" role="img"
             :aria-label="t('home.chart.title')">
          <!-- 网格与 Y 轴 -->
          <g>
            <line v-for="g in gridLines" :key="'g' + g.v"
                  x1="58" :x2="g.x2" :y1="g.y" :y2="g.y"
                  :stroke="g.v === 0 ? 'var(--c-chart-axis)' : 'var(--c-chart-grid)'"
                  stroke-width="1" :stroke-dasharray="g.v === 0 ? '' : '4 4'"/>
            <text v-for="g in gridLines" :key="'yl' + g.v"
                  x="50" :y="g.y + 4" text-anchor="end" class="axis-label">{{ g.label }}</text>
          </g>
          <!-- X 轴 -->
          <g>
            <text v-for="(x, i) in xTicks" :key="'xt' + i"
                  :x="x.x" y="244" text-anchor="middle" class="axis-label">{{ x.label }}</text>
          </g>
          <!-- 下载（蓝） / 上传（橙） -->
          <path v-if="snap.downArea" :d="snap.downArea" fill="var(--c-chart-down)" opacity="0.10"/>
          <polyline v-if="snap.down.length" :points="snap.downPoints"
                    fill="none" stroke="var(--c-chart-down)" stroke-width="2"
                    stroke-linejoin="round" stroke-linecap="round"/>
          <path v-if="snap.upArea" :d="snap.upArea" fill="var(--c-chart-up)" opacity="0.10"/>
          <polyline v-if="snap.up.length" :points="snap.upPoints"
                    fill="none" stroke="var(--c-chart-up)" stroke-width="2"
                    stroke-linejoin="round" stroke-linecap="round"/>
          <circle v-if="snap.lastDown" :cx="snap.lastDown.x" :cy="snap.lastDown.y" r="3.5" fill="var(--c-chart-down)"/>
          <circle v-if="snap.lastUp" :cx="snap.lastUp.x" :cy="snap.lastUp.y" r="3.5" fill="var(--c-chart-up)"/>
          <!-- 空态 / 暂停提示 -->
          <text v-if="!snap.hasData" :x="vbW / 2" y="130" text-anchor="middle" class="empty-label">
            {{ t('home.chart.empty') }}
          </text>
          <text v-else-if="!state.prefs.sniffing" :x="vbW / 2" y="130" text-anchor="middle" class="empty-label">
            {{ t('home.chart.paused') }}
          </text>
        </svg>
      </div>
    </div>

    <!-- 本次嗅探会话统计 -->
    <div class="stat-grid">
      <div v-for="c in statCards" :key="c.key" class="card stat-card">
        <div class="stat-icon" :style="{ background: c.tint, color: c.color }">
          <i :class="c.icon"></i>
        </div>
        <div class="stat-body">
          <div class="stat-label">{{ c.label }}</div>
          <div class="stat-value">{{ c.value }}</div>
          <div class="stat-sub">{{ c.sub }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { state, doDial, doDisconnect, showToast, formatSpeed, formatBytes, formatDuration } from '../store'
import { api } from '../bridge'
import { t } from '../i18n'

const SNIFF_WINDOW_MS = 10 * 60 * 1000
const H = 250, PAD_L = 58, PAD_R = 14, PAD_T = 14, PAD_B = 26

// ------------------------------------------------------------ 账号与拨号 ----

const currentIndex = computed({
  get: () => state.currentIndex,
  set: (v) => { state.currentIndex = v }
})

function accountLabel(a, i) {
  const name = (a.name || '').trim()
  const base = name || a.username || t('account.unset')
  return `${i + 1}. ${base}`
}

const dialButtonText = computed(() => {
  if (state.dialBusy) return state.dialLabel || t('home.dial.dialing')
  return state.online ? t('home.dial.disconnect') : t('home.dial.connect')
})

function onDialToggle() {
  if (state.dialBusy) return
  if (state.online) doDisconnect()
  else doDial()
}

function onAccountChange() {
  const acc = state.accounts[state.currentIndex]
  const name = (acc && (acc.name || acc.username)) || t('account.unset')
  api.SwitchAccount(state.currentIndex)
  // 在线时后端会先断开再用新账号重拨，随后的连接系列 Toast 会接续呈现
  showToast(`${t('account.switchTitle')}：${name}`, 'info')
}

// ------------------------------------------------------------ 折线图 ----

const chartWrap = ref(null)
const vbW = ref(680)
let resizeObs = null

onMounted(() => {
  if (typeof ResizeObserver === 'undefined' || !chartWrap.value) return
  resizeObs = new ResizeObserver((entries) => {
    const w = Math.round(entries[0].contentRect.width)
    if (w > 0 && Math.abs(w - vbW.value) > 2) {
      vbW.value = Math.max(360, w)
      rebuild()
    }
  })
  resizeObs.observe(chartWrap.value)
})

onUnmounted(() => {
  if (resizeObs) resizeObs.disconnect()
})

function niceMax(v) {
  if (!(v > 0)) return 1024
  const pow = Math.pow(10, Math.floor(Math.log10(v)))
  const n = v / pow
  const m = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10
  return m * pow
}

function unitOf(max) {
  if (max >= 1e9) return { div: 1e9, suffix: 'G' }
  if (max >= 1e6) return { div: 1e6, suffix: 'M' }
  if (max >= 1e3) return { div: 1e3, suffix: 'K' }
  return { div: 1, suffix: '' }
}

function pad2(n) {
  return String(n).padStart(2, '0')
}

const snap = ref({ up: [], down: [], upPoints: '', downPoints: '', upArea: '', downArea: '', lastUp: null, lastDown: null, hasData: false })
const unit = ref({ div: 1, suffix: '' })
const gridLines = ref([])
const xTicks = ref([])

function rebuild() {
  const W = vbW.value
  const plotW = W - PAD_L - PAD_R
  const plotH = H - PAD_T - PAD_B
  const now = Date.now()
  const t0 = now - SNIFF_WINDOW_MS
  const samples = state.samples
  const hasData = samples.length > 0

  let max = 0
  for (const s of samples) {
    if (s.up > max) max = s.up
    if (s.down > max) max = s.down
  }
  const yM = niceMax(max)
  unit.value = unitOf(yM)

  const yOf = (v) => PAD_T + (1 - v / yM) * plotH
  const xOf = (t) => PAD_L + Math.min(1, Math.max(0, (t - t0) / SNIFF_WINDOW_MS)) * plotW

  gridLines.value = [0, 0.25, 0.5, 0.75, 1].map((f) => {
    const v = yM * f
    return {
      v,
      x2: W - PAD_R,
      y: yOf(v).toFixed(1),
      label: (v / unit.value.div).toFixed(unit.value.div === 1 ? 0 : 1) + unit.value.suffix
    }
  })

  xTicks.value = [0, 0.25, 0.5, 0.75, 1].map((f) => {
    const ts = new Date(t0 + SNIFF_WINDOW_MS * f)
    return { x: (PAD_L + plotW * f).toFixed(1), label: `${pad2(ts.getHours())}:${pad2(ts.getMinutes())}` }
  })

  if (!hasData) {
    snap.value = { up: [], down: [], upPoints: '', downPoints: '', upArea: '', downArea: '', lastUp: null, lastDown: null, hasData: false }
    return
  }

  // 超过 200 点时抽稀，保证渲染开销稳定
  const step = Math.max(1, Math.ceil(samples.length / 200))
  const ups = []
  const downs = []
  for (let i = 0; i < samples.length; i += step) {
    const s = samples[i]
    ups.push([xOf(s.t).toFixed(1), yOf(s.up).toFixed(1)])
    downs.push([xOf(s.t).toFixed(1), yOf(s.down).toFixed(1)])
  }
  const last = samples[samples.length - 1]
  const lastPt = [xOf(last.t).toFixed(1), yOf(last.up).toFixed(1)]
  const lastPtD = [xOf(last.t).toFixed(1), yOf(last.down).toFixed(1)]
  ups.push(lastPt)
  downs.push(lastPtD)

  const base = (PAD_T + plotH).toFixed(1)
  snap.value = {
    up: ups,
    down: downs,
    upPoints: ups.map((p) => p.join(',')).join(' '),
    downPoints: downs.map((p) => p.join(',')).join(' '),
    upArea: ups.length > 1 ? `M ${ups[0][0]},${base} L ${ups.map((p) => p.join(',')).join(' L ')} L ${ups[ups.length - 1][0]},${base} Z` : '',
    downArea: downs.length > 1 ? `M ${downs[0][0]},${base} L ${downs.map((p) => p.join(',')).join(' L ')} L ${downs[downs.length - 1][0]},${base} Z` : '',
    lastUp: { x: Number(lastPt[0]), y: Number(lastPt[1]) },
    lastDown: { x: Number(lastPtD[0]), y: Number(lastPtD[1]) },
    hasData: true
  }
}

// 图表随 store 的重绘节拍刷新（轻量化模式下自动降频）
watch(() => state.renderTick, rebuild, { immediate: true })
watch(() => state.prefs.sniffing, rebuild)

// ------------------------------------------------------------ 状态卡片 ----

const statCards = computed(() => {
  const live = state.online && state.prefs.sniffing
  const speedUp = live ? formatSpeed(state.upSpeed) : '--'
  const speedDown = live ? formatSpeed(state.downSpeed) : '--'
  const peakUp = state.peakUp > 0 ? formatSpeed(state.peakUp) : '--'
  const peakDown = state.peakDown > 0 ? formatSpeed(state.peakDown) : '--'
  const upTraffic = state.sessionUp > 0 ? formatBytes(state.sessionUp) : '--'
  const downTraffic = state.sessionDown > 0 ? formatBytes(state.sessionDown) : '--'
  return [
    {
      key: 'upSpeed',
      label: t('home.card.upSpeed'),
      icon: 'fas fa-arrow-up',
      color: 'var(--c-chart-up)',
      tint: 'rgba(249, 115, 22, .12)',
      value: speedUp,
      sub: `${t('home.card.peak')} ${peakUp}`
    },
    {
      key: 'downSpeed',
      label: t('home.card.downSpeed'),
      icon: 'fas fa-arrow-down',
      color: 'var(--c-chart-down)',
      tint: 'rgba(59, 130, 246, .12)',
      value: speedDown,
      sub: `${t('home.card.peak')} ${peakDown}`
    },
    {
      key: 'upTraffic',
      label: t('home.card.upTraffic'),
      icon: 'fas fa-cloud-upload-alt',
      color: 'var(--c-chart-up)',
      tint: 'rgba(249, 115, 22, .12)',
      value: upTraffic,
      sub: t('home.card.session')
    },
    {
      key: 'downTraffic',
      label: t('home.card.downTraffic'),
      icon: 'fas fa-cloud-download-alt',
      color: 'var(--c-chart-down)',
      tint: 'rgba(59, 130, 246, .12)',
      value: downTraffic,
      sub: t('home.card.session')
    }
  ]
})
</script>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.home-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  flex: 0 0 auto;
  padding: 12px 16px;
}

.home-header .h {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
}

/* 右侧连接按钮不参与压缩，宽度不足时整体换行 */
.home-header .h.right {
  flex: 0 0 auto;
  margin-left: auto;
}

.status-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex: 0 0 auto;
}

.status-dot.on {
  background: var(--c-status-online);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--c-status-online) 25%, transparent);
}

.status-dot.off {
  background: var(--c-hint);
}

.account-select {
  min-width: 180px;
  max-width: 320px;
  height: 32px;
  font-weight: 600;
}

.uptime-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--c-text-sub);
  background: var(--c-accent-soft);
  border-radius: 999px;
  padding: 3px 10px;
  white-space: nowrap;
}

.connect-btn {
  min-width: 118px;
  height: 34px;
  font-weight: 700;
}

/* 图表卡片 */
.chart-card {
  flex: 1 1 auto;
  min-height: 210px;
  display: flex;
  flex-direction: column;
}

.legend {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 11px;
  color: var(--c-text-sub);
}

.lg {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.lg::before {
  content: "";
  width: 14px;
  height: 3px;
  border-radius: 2px;
}

.lg.up::before { background: var(--c-chart-up); }
.lg.down::before { background: var(--c-chart-down); }
.lg.paused { color: var(--c-warning); }
.lg.paused::before { display: none; }

.chart-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
}

.chart {
  width: 100%;
  height: 100%;
  min-height: 170px;
}

.axis-label {
  font-size: 10px;
  fill: var(--c-chart-axis);
  font-family: Consolas, monospace;
}

.empty-label {
  font-size: 12px;
  fill: var(--c-hint);
}

/* 状态卡片：列数随宽度自适应（4 → 2 → 1），窄窗口换行而非挤压 */
.stat-grid {
  flex: 0 0 auto;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 14px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
}

.stat-icon {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex: 0 0 auto;
}

.stat-body {
  min-width: 0;
}

.stat-label {
  font-size: 11px;
  color: var(--c-text-sub);
}

.stat-value {
  font-size: 17px;
  font-weight: 800;
  margin-top: 2px;
  white-space: nowrap;
}

.stat-sub {
  font-size: 10px;
  color: var(--c-hint);
  margin-top: 2px;
}
</style>
