<template>
  <div class="side-traffic">
    <!-- 迷你流量图（参考 Clash Verge 的 canvas 实现） -->
    <div class="graph-box" :title="t('side.traffic.graphTip')" @click="toggleStyle">
      <canvas ref="canvasEl"></canvas>
      <div v-if="!state.prefs.sniffing" class="paused-mask">
        <i class="fas fa-pause"></i>
        <span>{{ t('side.traffic.paused') }}</span>
      </div>
    </div>

    <!-- 当前上下行速率 -->
    <div class="spd-row up" :title="t('home.chart.up')">
      <i class="fas fa-arrow-up"></i>
      <span class="val" :class="{ zero: !live || state.upSpeed <= 0 }">{{ upSpd[0] }}</span>
      <span class="unit">{{ upSpd[1] }}</span>
    </div>
    <div class="spd-row down" :title="t('home.chart.down')">
      <i class="fas fa-arrow-down"></i>
      <span class="val" :class="{ zero: !live || state.downSpeed <= 0 }">{{ downSpd[0] }}</span>
      <span class="unit">{{ downSpd[1] }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { state, formatSpeed } from '../store'
import { t } from '../i18n'

// 参考 Clash Verge Rev 侧栏流量图（layout/traffic-graph.tsx）的 canvas 绘制：
// 固定 32 点滚动窗口 + 7 档分段对数 Y 轴（0~10M 各占 1/7 高，带两条参考线）+
// 中点二次贝塞尔平滑（点击可切折线）+ 新样本自右缘匀速滑入（15fps 节流）。
// 与之的差异：数据源仍是全局 10 分钟嗅探窗口（state.samples），按 20s 一桶
// 取最大值聚合出 30 个可见槽位，右缘外再留 1 个正在写入的桶。
const SLOTS = 30
const WINDOW_MS = 10 * 60 * 1000
const SLOT_MS = WINDOW_MS / SLOTS
const POINTS = SLOTS + 2
const FRAME_MS = 1000 / 15

const canvasEl = ref(null)
let ctx = null
let resizeObs = null
let rafId = 0
let lastFrame = 0
const curveStyle = ref(true)

const live = computed(() => (state.online || state.sysOnline) && state.prefs.sniffing)

/** "12.3 KB/s" → ["12.3", "KB/s"]，数值与单位分行展示。 */
function splitSpeed(bytesPerSec) {
  const s = formatSpeed(bytesPerSec)
  const i = s.lastIndexOf(' ')
  return i > 0 ? [s.slice(0, i), s.slice(i + 1)] : [s, '']
}

const upSpd = computed(() => (live.value ? splitSpeed(state.upSpeed) : ['--', '']))
const downSpd = computed(() => (live.value ? splitSpeed(state.downSpeed) : ['--', '']))

function toggleStyle() {
  curveStyle.value = !curveStyle.value
  drawFrame()
}

// ------------------------------------------------------------ 绘制 ----

function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/** 分段对数刻度：10B/100B/1K/10K/100K/1M/10M 七档，每档占 1/7 高度。 */
function countY(v, h) {
  const dy = h / 7
  if (v <= 0) return h - 1
  if (v <= 10) return h - (v / 10) * dy
  if (v <= 100) return h - (v / 100 + 1) * dy
  if (v <= 1024) return h - (v / 1024 + 2) * dy
  if (v <= 10240) return h - (v / 10240 + 3) * dy
  if (v <= 102400) return h - (v / 102400 + 4) * dy
  if (v <= 1048576) return h - (v / 1048576 + 5) * dy
  if (v <= 10485760) return h - (v / 10485760 + 6) * dy
  return 1
}

/** 10 分钟窗口 → 20s 粒度桶（取桶内峰值）；j=31 为正在写入的当前桶。 */
function computeBuckets() {
  const now = Date.now()
  const base = Math.floor(now / SLOT_MS) - (POINTS - 1)
  const buckets = Array.from({ length: POINTS }, () => ({ up: 0, down: 0, seen: false }))
  const ss = state.samples
  for (let k = 0; k < ss.length; k++) {
    const s = ss[k]
    let idx = Math.floor(s.t / SLOT_MS) - base
    if (idx < 0) continue
    if (idx > POINTS - 1) idx = POINTS - 1
    const b = buckets[idx]
    b.seen = true
    if (s.up > b.up) b.up = s.up
    if (s.down > b.down) b.down = s.down
  }
  // 当前桶刚滚动、尚未收到首个采样时沿用上一桶值：
  // 在线时采样间隔短于桶宽，这只发生在滚动后的瞬间，
  // 否则曲线右缘会每隔 20s 出现一次跳水尖峰
  const cur = buckets[POINTS - 1]
  if (!cur.seen) {
    const prev = buckets[POINTS - 2]
    cur.up = prev.up
    cur.down = prev.down
  }
  return { now, buckets }
}

function drawFrame() {
  const canvas = canvasEl.value
  const box = canvas && canvas.parentElement
  if (!canvas || !ctx || !box) return
  const w = box.clientWidth
  const h = box.clientHeight
  if (w < 8 || h < 8) return
  const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1))
  const bw = Math.round(w * dpr)
  const bh = Math.round(h * dpr)
  if (canvas.width !== bw || canvas.height !== bh) {
    canvas.width = bw
    canvas.height = bh
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

  const { now, buckets } = computeBuckets()
  const phase = (now - Math.floor(now / SLOT_MS) * SLOT_MS) / SLOT_MS
  const dx = w / SLOTS
  const xOf = (j) => (j - 1 - phase) * dx + 3
  const yOf = (v) => countY(v, h)

  ctx.clearRect(0, 0, w, h)

  // 档位参考线：1/7 与 4/7 高度（与 Clash Verge 一致）
  const dy = h / 7
  ctx.beginPath()
  ctx.lineWidth = 1
  ctx.strokeStyle = cssVar('--c-chart-grid', 'rgba(0,0,0,.08)')
  ctx.moveTo(0, dy)
  ctx.lineTo(w, dy)
  ctx.moveTo(0, dy * 4)
  ctx.lineTo(w, dy * 4)
  ctx.stroke()

  // 离线/暂停时降低线条存在感，避免与"未连接"状态混淆
  const dim = live.value ? 1 : 0.45
  const lw = Math.max(2, Math.min(4, Math.round(w / 44)))
  drawSeries(buckets, 'up', cssVar('--c-chart-up', '#f97316'), 0.6 * dim, lw, xOf, yOf)
  drawSeries(buckets, 'down', cssVar('--c-chart-down', '#3b82f6'), dim, lw, xOf, yOf)
}

function drawSeries(buckets, key, color, alpha, lw, xOf, yOf) {
  ctx.beginPath()
  ctx.globalAlpha = alpha
  ctx.lineWidth = lw
  ctx.lineJoin = 'round'
  ctx.lineCap = 'round'
  ctx.strokeStyle = color
  if (curveStyle.value) bezierPath(buckets, key, xOf, yOf)
  else linePath(buckets, key, xOf, yOf)
  ctx.stroke()
  ctx.globalAlpha = 1
}

/** 中点二次贝塞尔：控制点 (x[i-1], y[i])，终点取相邻中点（Clash Verge 同款）。 */
function bezierPath(buckets, key, xOf, yOf) {
  const n = buckets.length
  if (!n) return
  ctx.moveTo(xOf(0), yOf(buckets[0][key]))
  for (let i = 1; i < n; i++) {
    const p1x = xOf(i - 1)
    const p1y = yOf(buckets[i][key])
    const hasNext = i + 1 < n
    const p2x = hasNext ? xOf(i) : p1x
    const p2y = hasNext ? yOf(buckets[i + 1][key]) : p1y
    ctx.quadraticCurveTo(p1x, p1y, (p1x + p2x) / 2, (p1y + p2y) / 2)
  }
}

function linePath(buckets, key, xOf, yOf) {
  ctx.moveTo(xOf(0), yOf(buckets[0][key]))
  for (let i = 1; i < buckets.length; i++) {
    ctx.lineTo(xOf(i - 1), yOf(buckets[i][key]))
  }
}

// ------------------------------------------------- 动画与生命周期 ----

function loop(ts) {
  rafId = requestAnimationFrame(loop)
  if (ts - lastFrame < FRAME_MS) return
  lastFrame = ts
  drawFrame()
}

/** 有效在线（拨号 ∨ 系统直连）且开启嗅探时匀速左移；轻量化/离线时退化为按重绘节拍静态刷新。 */
function syncLoop() {
  const should = !!ctx && (state.online || state.sysOnline) && state.prefs.sniffing && !state.prefs.lightweight
  if (should && !rafId) {
    lastFrame = 0
    rafId = requestAnimationFrame(loop)
  } else if (!should && rafId) {
    cancelAnimationFrame(rafId)
    rafId = 0
    drawFrame()
  }
}

onMounted(() => {
  ctx = canvasEl.value ? canvasEl.value.getContext('2d') : null
  if (typeof ResizeObserver !== 'undefined' && canvasEl.value) {
    resizeObs = new ResizeObserver(() => drawFrame())
    resizeObs.observe(canvasEl.value.parentElement)
  }
  drawFrame()
  syncLoop()
})

onUnmounted(() => {
  if (rafId) cancelAnimationFrame(rafId)
  rafId = 0
  if (resizeObs) resizeObs.disconnect()
  ctx = null
})

watch(() => state.renderTick, () => {
  if (!rafId) drawFrame()
})
watch(() => [state.online, state.sysOnline, state.prefs.sniffing, state.prefs.lightweight], syncLoop)
</script>

<style scoped>
.side-traffic {
  padding: 0 6px;
  margin-bottom: 8px;
  user-select: none;
}

.graph-box {
  position: relative;
  height: 60px;
  margin-bottom: 6px;
  cursor: pointer;
  border-radius: 6px;
  overflow: hidden;
}

.graph-box canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
}

.paused-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  font-size: 10px;
  color: var(--c-hint);
  background: color-mix(in srgb, var(--c-sidebar) 62%, transparent);
}

.paused-mask i {
  font-size: 9px;
}

.spd-row {
  display: flex;
  align-items: center;
  height: 16px;
  font-size: 11px;
  line-height: 1;
}

.spd-row i {
  width: 14px;
  font-size: 9px;
  flex: 0 0 auto;
}

.spd-row .val {
  flex: 1 1 auto;
  min-width: 0;
  text-align: center;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
}

.spd-row .unit {
  flex: 0 0 36px;
  text-align: right;
  font-size: 10px;
  color: var(--c-hint);
}

.spd-row.up i,
.spd-row.up .val {
  color: var(--c-chart-up);
}

.spd-row.down i,
.spd-row.down .val {
  color: var(--c-chart-down);
}

.spd-row .val.zero {
  color: var(--c-hint);
}

/* 图标栏模式：速率文字放不下，只保留迷你图 */
@media (max-width: 720px) {
  .spd-row {
    display: none;
  }

  .graph-box {
    height: 44px;
    margin-bottom: 4px;
  }
}
</style>
