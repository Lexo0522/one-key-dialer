<template>
  <div class="page home">
    <!-- 顶部：左状态与账号 / 右连接按钮 -->
    <div class="card home-header">
      <div class="h left">
        <span class="status-dot" :class="state.online ? 'on' : 'off'"
              :title="state.online ? t('home.status.connected') : t('home.status.disconnected')"></span>
        <span class="account-chip" :class="{ unset: !hasAccount }" :title="accountText">
          <i class="fas fa-user"></i>{{ accountText }}
        </span>
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

    <!-- 流量统计图：Clash Verge 同款 Canvas 折线图（平滑贝塞尔 + 渐变填充 + 悬停十字线） -->
    <div class="card chart-card">
      <div class="card-head">
        <div class="card-title"><i class="fas fa-chart-line"></i>{{ t('home.chart.title') }}</div>
        <div class="chart-hints">
          <span v-if="!state.prefs.sniffing" class="hint-chip paused">
            <i class="fas fa-pause"></i>{{ t('home.chart.paused') }}
          </span>
          <span v-else-if="!displayData.length" class="hint-chip">{{ t('home.chart.empty') }}</span>
        </div>
      </div>

      <div ref="chartWrap" class="chart-wrap" @click="toggleStyle"
           @mousemove="onGraphMove" @mouseleave="onGraphLeave">
        <canvas ref="canvasRef" class="chart-canvas"></canvas>
        <canvas v-if="tooltip.visible" ref="hoverCanvasRef" class="chart-canvas chart-hover"></canvas>

        <!-- 叠加层：时间范围（点击循环 1/5/10 分钟） -->
        <span class="cv-range" @click.stop="cycleRange">{{ tf('home.chart.rangeMinutes', timeRange) }}</span>
        <!-- 叠加层：图例 -->
        <div class="cv-legend">
          <span class="cv-up">{{ t('home.chart.up') }}</span>
          <span class="cv-down">{{ t('home.chart.down') }}</span>
        </div>
        <!-- 叠加层：图表样式与诊断 -->
        <span class="cv-style">{{ chartStyle === 'bezier' ? t('home.chart.styleSmooth') : t('home.chart.styleLinear') }}</span>
        <span class="cv-diag">{{ tf('home.chart.diagnostics', displayData.length, TARGET_FPS) }}</span>

        <!-- 悬停数值提示 -->
        <div v-if="tooltip.visible" class="cv-tooltip" :style="tooltipStyle">
          <div class="cv-tt-time">{{ tooltip.time }}</div>
          <div class="cv-tt-up">↑ {{ tooltip.upSpeed }}</div>
          <div class="cv-tt-down">↓ {{ tooltip.downSpeed }}</div>
        </div>
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
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { state, doDial, doDisconnect, formatSpeed, formatBytes, formatDuration } from '../store'
import { t, tf } from '../i18n'

// ------------------------------------------------------------ 宽带账号与拨号 ----

// 只读展示当前宽带账号；未设置时提示去「宽带」页配置
const hasAccount = computed(() => !!(state.broadband?.username || '').trim())
const accountText = computed(() => {
  const name = (state.broadband?.username || '').trim()
  return name || t('home.account.unset')
})

const dialButtonText = computed(() => {
  if (state.dialBusy) return state.dialLabel || t('home.dial.dialing')
  return state.online ? t('home.dial.disconnect') : t('home.dial.connect')
})

function onDialToggle() {
  if (state.dialBusy) return
  if (state.online) doDisconnect()
  else doDial()
}

// ------------------------------------------------------ 流量图（Clash Verge 同款） ----
// 1:1 移植 Clash Verge Rev 的 EnhancedCanvasTrafficGraph：
// 常量、Y 轴动态标定（min/max + 10% 余量）、中点二次贝塞尔、渐变面积填充、
// 时间轴自适应标注、悬停虚线十字线与数值浮层、点击切换平滑/直线、
// 时间范围 1/5/10 分钟循环、DPR 缩放、失焦/数据过期暂停重绘。
const MAX_POINTS = 300
const TARGET_FPS = 15
const LINE_WIDTH_UP = 2.5
const LINE_WIDTH_DOWN = 2.5
const LINE_WIDTH_GRID = 0.5
const ALPHA_GRADIENT = 0.15
const ALPHA_LINE = 0.9
const PADDING_TOP = 16
const PADDING_RIGHT = 16
const PADDING_BOTTOM = 32
const PADDING_LEFT = 35
const STALE_DATA_THRESHOLD = 2500 // 超时无新数据 => 暂停重绘，保持最后一帧

const chartWrap = ref(null)
const canvasRef = ref(null)
const hoverCanvasRef = ref(null)

const timeRange = ref(10) // 1 | 5 | 10（分钟）
const chartStyle = ref('bezier') // bezier | line
const tooltip = reactive({
  visible: false,
  x: 0,
  y: 0,
  upSpeed: '',
  downSpeed: '',
  time: '',
  dataIndex: -1,
  highlightY: 0,
})

// 主题色从 CSS 变量解析（CV 用 MUI palette，随主题变化；本应用同理）
const colors = reactive({ up: '', down: '', grid: '', text: '', bg: '' })
const COLOR_VARS = {
  up: '--c-chart-up',
  down: '--c-chart-down',
  grid: '--c-chart-grid',
  text: '--c-chart-axis',
  bg: '--c-card',
}
function resolveColors() {
  const cs = getComputedStyle(document.documentElement)
  for (const [key, v] of Object.entries(COLOR_VARS)) {
    colors[key] = cs.getPropertyValue(v).trim()
  }
}

// 展示数据：按时间范围截取 + 超出 300 点时均匀抽稀（保留首尾点）
const displayData = computed(() => {
  const cutoff = Date.now() - timeRange.value * 60 * 1000
  const src = state.samples.filter((s) => s.t >= cutoff)
  if (src.length <= MAX_POINTS) return src
  const step = (src.length - 1) / (MAX_POINTS - 1)
  const out = []
  for (let i = 0; i < MAX_POINTS; i++) out.push(src[Math.round(i * step)])
  return out
})

// Y 轴动态标定：极值外加 10% 余量，贴地曲线更好看
function computeYScale(data) {
  if (data.length === 0) return { topValue: 1024, bottomValue: 0 }
  let maxValue = 0
  let minValue = Infinity
  for (const p of data) {
    if (p.up > maxValue) maxValue = p.up
    if (p.down > maxValue) maxValue = p.down
    if (p.up < minValue) minValue = p.up
    if (p.down < minValue) minValue = p.down
  }
  if (!isFinite(minValue)) minValue = 0
  if (maxValue === 0) return { topValue: 1024, bottomValue: 0 }
  const range = maxValue - minValue
  if (range === 0) return { topValue: maxValue * 1.2, bottomValue: 0 }
  return {
    topValue: maxValue + range * 0.1,
    bottomValue: Math.max(0, minValue - range * 0.1),
  }
}
const yScale = computed(() => computeYScale(displayData.value))

function calculateY(value, height, topValue, bottomValue) {
  const topY = PADDING_TOP + 10
  const bottomY = height - PADDING_BOTTOM - 5
  if (topValue === bottomValue) return bottomY
  const ratio = (value - bottomValue) / (topValue - bottomValue)
  return bottomY - ratio * (bottomY - topY)
}

/** 流量格式化（与 CV parse-traffic 一致）：3 位有效数字 + 二进制单位。 */
const UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB']
function parseTraffic(num) {
  if (typeof num !== 'number') return ['NaN', '']
  const exp = num < 1 ? 0 : Math.min(Math.floor(Math.log2(num) / 10), UNITS.length - 1)
  const dat = num / Math.pow(1024, exp)
  const ret = Math.round(dat) >= 1000 ? dat.toFixed(0) : dat.toPrecision(3)
  return [ret, UNITS[exp]]
}

const pad2 = (n) => String(n).padStart(2, '0')
const fmtHourMinute = (ts) => {
  const d = new Date(ts)
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}
const fmtMinuteSecond = (ts) => {
  const d = new Date(ts)
  return `${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}
const fmtHourMinuteSecond = (ts) => {
  const d = new Date(ts)
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}

// ------------------------------------------------------------ Canvas 绘制 ----

function syncCanvasSize(canvas) {
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  const rect = canvas.getBoundingClientRect()
  const dpr = window.devicePixelRatio || 1
  const pixelWidth = Math.max(1, Math.floor(rect.width * dpr))
  const pixelHeight = Math.max(1, Math.floor(rect.height * dpr))
  if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
    canvas.width = pixelWidth
    canvas.height = pixelHeight
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.scale(dpr, dpr)
  }
  return { ctx, cssWidth: rect.width, cssHeight: rect.height }
}

function clearCanvas(canvas) {
  if (!canvas) return
  const synced = syncCanvasSize(canvas)
  if (!synced) return
  synced.ctx.clearRect(0, 0, synced.cssWidth, synced.cssHeight)
}

/** Y 轴：底/中/顶 3 个刻度，底顶画横线，非 0 标签垫底色块盖住网格。 */
function drawYAxis(ctx, width, height, topValue, bottomValue) {
  const topY = PADDING_TOP + 10
  const bottomY = height - PADDING_BOTTOM - 5
  const middleY = (topY + bottomY) / 2
  const middleValue = (bottomValue + topValue) / 2

  const formatTrafficValue = (bytes) => {
    if (bytes === 0) return '0'
    if (bytes < 1024) return `${Math.round(bytes)}B`
    if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)}KB`
    return `${(bytes / (1024 * 1024)).toFixed(1)}MB`
  }

  const ticks = [
    { label: formatTrafficValue(bottomValue), y: bottomY },
    { label: formatTrafficValue(middleValue), y: middleY },
    { label: formatTrafficValue(topValue), y: topY },
  ]

  ctx.save()
  ticks.forEach((tick, index) => {
    const isBottomTick = index === 0
    const isTopTick = index === ticks.length - 1
    if (isBottomTick || isTopTick) {
      ctx.strokeStyle = colors.grid
      ctx.lineWidth = isBottomTick ? 0.8 : 0.4
      ctx.globalAlpha = isBottomTick ? 0.25 : 0.15
      ctx.beginPath()
      ctx.moveTo(PADDING_LEFT, tick.y)
      ctx.lineTo(width - PADDING_RIGHT, tick.y)
      ctx.stroke()
    }
    ctx.fillStyle = colors.text
    ctx.font = "8px -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif"
    ctx.globalAlpha = 0.9
    ctx.textAlign = 'right'
    ctx.textBaseline = 'middle'
    if (tick.label !== '0') {
      const labelWidth = ctx.measureText(tick.label).width
      ctx.globalAlpha = 0.15
      ctx.fillStyle = colors.bg
      ctx.fillRect(PADDING_LEFT - labelWidth - 8, tick.y - 5, labelWidth + 4, 10)
    }
    ctx.globalAlpha = 0.9
    ctx.fillStyle = colors.text
    ctx.fillText(tick.label, PADDING_LEFT - 4, tick.y)
  })
  ctx.restore()
}

/** 网格：横向 4 条 + 纵向 6 条。 */
function drawGrid(ctx, width, height) {
  const effectiveWidth = width - PADDING_LEFT - PADDING_RIGHT
  const effectiveHeight = height - PADDING_TOP - PADDING_BOTTOM

  ctx.save()
  ctx.strokeStyle = colors.grid
  ctx.lineWidth = LINE_WIDTH_GRID
  ctx.globalAlpha = 0.7

  const horizontalLines = 4
  for (let i = 1; i <= horizontalLines; i++) {
    const y = PADDING_TOP + (effectiveHeight / (horizontalLines + 1)) * i
    ctx.beginPath()
    ctx.moveTo(PADDING_LEFT, y)
    ctx.lineTo(width - PADDING_RIGHT, y)
    ctx.stroke()
  }
  const verticalLines = 6
  for (let i = 1; i <= verticalLines; i++) {
    const x = PADDING_LEFT + (effectiveWidth / (verticalLines + 1)) * i
    ctx.beginPath()
    ctx.moveTo(x, PADDING_TOP)
    ctx.lineTo(x, height - PADDING_BOTTOM)
    ctx.stroke()
  }
  ctx.restore()
}

/** 时间轴标注策略：范围越短标签越密、精度越高。 */
function getTimeDisplayStrategy(minutes) {
  switch (minutes) {
    case 1:
      return { maxLabels: 6, formatTime: fmtMinuteSecond, minPixelDistance: 35 }
    case 5:
      return { maxLabels: 6, formatTime: fmtHourMinute, minPixelDistance: 38 }
    default:
      return { maxLabels: 8, formatTime: fmtHourMinute, minPixelDistance: 40 }
  }
}

function drawTimeAxis(ctx, width, height, data) {
  if (data.length === 0) return
  const effectiveWidth = width - PADDING_LEFT - PADDING_RIGHT
  const timeAxisY = height - PADDING_BOTTOM + 14
  const strategy = getTimeDisplayStrategy(timeRange.value)

  ctx.save()
  ctx.fillStyle = colors.text
  ctx.font = "10px -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif"
  ctx.globalAlpha = 0.7

  const targetLabels = Math.min(strategy.maxLabels, data.length)
  const step = Math.max(1, Math.floor(data.length / (targetLabels - 1)))
  const minPixelDistance = strategy.minPixelDistance || 45
  const actualStep = Math.max(step, Math.ceil((data.length * minPixelDistance) / effectiveWidth))

  const timePoints = []
  if (data[0].t) {
    timePoints.push({ x: PADDING_LEFT, label: strategy.formatTime(data[0].t) })
  }
  for (let i = actualStep; i < data.length - actualStep; i += actualStep) {
    const x = PADDING_LEFT + (i / (data.length - 1)) * effectiveWidth
    timePoints.push({ x, label: strategy.formatTime(data[i].t) })
  }
  if (data.length > 1 && data[data.length - 1].t) {
    const lastX = width - PADDING_RIGHT
    const lastPoint = timePoints[timePoints.length - 1]
    if (!lastPoint || lastX - lastPoint.x >= minPixelDistance) {
      timePoints.push({ x: lastX, label: strategy.formatTime(data[data.length - 1].t) })
    }
  }

  timePoints.forEach((point, index) => {
    if (index === 0) {
      ctx.textAlign = 'left'
    } else if (index === timePoints.length - 1) {
      ctx.textAlign = 'right'
    } else {
      ctx.textAlign = 'center'
    }
    ctx.fillText(point.label, point.x, timeAxisY)
  })
  ctx.restore()
}

/** 流量曲线：贝塞尔模式下先画渐变面积，再描 2.5px 圆角线条；直线模式仅描线。 */
function drawTrafficLine(ctx, data, valueKey, width, height, color, topValue, bottomValue) {
  if (data.length < 2) return

  const effectiveWidth = width - PADDING_LEFT - PADDING_RIGHT
  const lastIndex = data.length - 1
  const getX = (index) => PADDING_LEFT + (index / lastIndex) * effectiveWidth
  const getY = (index) => calculateY(data[index][valueKey], height, topValue, bottomValue)

  ctx.save()

  if (chartStyle.value === 'bezier') {
    const gradient = ctx.createLinearGradient(0, PADDING_TOP, 0, height - PADDING_BOTTOM)
    gradient.addColorStop(
      0,
      `${color}${Math.round(ALPHA_GRADIENT * 255).toString(16).padStart(2, '0')}`,
    )
    gradient.addColorStop(1, `${color}00`)

    ctx.beginPath()
    ctx.moveTo(getX(0), getY(0))
    for (let i = 1; i < data.length; i++) {
      const currentX = getX(i)
      const currentY = getY(i)
      const nextIndex = Math.min(i + 1, lastIndex)
      const controlX = (currentX + getX(nextIndex)) / 2
      const controlY = (currentY + getY(nextIndex)) / 2
      ctx.quadraticCurveTo(currentX, currentY, controlX, controlY)
    }
    ctx.lineTo(getX(lastIndex), height - PADDING_BOTTOM)
    ctx.lineTo(getX(0), height - PADDING_BOTTOM)
    ctx.closePath()
    ctx.fillStyle = gradient
    ctx.fill()
  }

  ctx.beginPath()
  ctx.strokeStyle = color
  ctx.lineWidth = valueKey === 'up' ? LINE_WIDTH_UP : LINE_WIDTH_DOWN
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  ctx.globalAlpha = ALPHA_LINE

  ctx.moveTo(getX(0), getY(0))
  if (chartStyle.value === 'bezier') {
    for (let i = 1; i < data.length; i++) {
      const currentX = getX(i)
      const currentY = getY(i)
      const nextIndex = Math.min(i + 1, lastIndex)
      const controlX = (currentX + getX(nextIndex)) / 2
      const controlY = (currentY + getY(nextIndex)) / 2
      ctx.quadraticCurveTo(currentX, currentY, controlX, controlY)
    }
  } else {
    for (let i = 1; i < data.length; i++) {
      ctx.lineTo(getX(i), getY(i))
    }
  }
  ctx.stroke()
  ctx.restore()
}

function drawGraph() {
  const canvas = canvasRef.value
  if (!canvas || displayData.value.length === 0) {
    clearCanvas(canvas)
    clearCanvas(hoverCanvasRef.value)
    return
  }
  const synced = syncCanvasSize(canvas)
  if (!synced) return
  const { ctx, cssWidth, cssHeight } = synced

  ctx.clearRect(0, 0, cssWidth, cssHeight)
  const { topValue, bottomValue } = yScale.value

  drawYAxis(ctx, cssWidth, cssHeight, topValue, bottomValue)
  drawGrid(ctx, cssWidth, cssHeight)
  drawTimeAxis(ctx, cssWidth, cssHeight, displayData.value)
  drawTrafficLine(ctx, displayData.value, 'down', cssWidth, cssHeight, colors.down, topValue, bottomValue)
  drawTrafficLine(ctx, displayData.value, 'up', cssWidth, cssHeight, colors.up, topValue, bottomValue)

  clearCanvas(hoverCanvasRef.value)
}

/** 悬停层：过数据点的虚线十字线（竖线全高，横线在较大值一侧）。 */
function drawHoverOverlay() {
  const canvas = hoverCanvasRef.value
  if (!canvas || displayData.value.length < 2 || !tooltip.visible || tooltip.dataIndex < 0) {
    clearCanvas(canvas)
    return
  }
  const synced = syncCanvasSize(canvas)
  if (!synced) return
  const { ctx, cssWidth, cssHeight } = synced
  ctx.clearRect(0, 0, cssWidth, cssHeight)

  const effectiveWidth = cssWidth - PADDING_LEFT - PADDING_RIGHT
  const dataX =
    PADDING_LEFT + (tooltip.dataIndex / (displayData.value.length - 1)) * effectiveWidth

  ctx.save()
  ctx.strokeStyle = colors.text
  ctx.lineWidth = 1
  ctx.globalAlpha = 0.6
  ctx.setLineDash([4, 4])
  ctx.beginPath()
  ctx.moveTo(dataX, PADDING_TOP)
  ctx.lineTo(dataX, cssHeight - PADDING_BOTTOM)
  ctx.stroke()
  ctx.beginPath()
  ctx.moveTo(PADDING_LEFT, tooltip.highlightY)
  ctx.lineTo(cssWidth - PADDING_RIGHT, tooltip.highlightY)
  ctx.stroke()
  ctx.restore()
}

// ---- 重绘调度：rAF 节流 + 数据签名去重 + 失焦/数据过期跳过 ----

let drawFrame = null
let hoverFrame = null
let moveFrame = null
let lastSig = null
let lastDataTs = 0

function scheduleDrawGraph() {
  if (drawFrame !== null) return
  drawFrame = requestAnimationFrame(() => {
    drawFrame = null
    if (document.hidden) return

    const d = displayData.value
    if (d.length === 0) {
      lastSig = ''
      lastDataTs = 0
      drawGraph()
      return
    }
    // 数据过期（断开/暂停嗅探）时保持最后一帧，与 CV 行为一致
    const lastTs = d[d.length - 1].t
    if (lastDataTs > 0 && Date.now() - lastTs > STALE_DATA_THRESHOLD) return
    lastDataTs = lastTs

    const sig = `${d.length}:${d[0].t}:${lastTs}:${chartStyle.value}:${timeRange.value}:${colors.down}`
    if (sig === lastSig) return
    lastSig = sig

    drawGraph()
    drawHoverOverlay()
  })
}

function scheduleHoverDraw() {
  if (hoverFrame !== null) return
  hoverFrame = requestAnimationFrame(() => {
    hoverFrame = null
    drawHoverOverlay()
  })
}

watch(displayData, scheduleDrawGraph)
watch(chartStyle, () => {
  lastSig = null
  scheduleDrawGraph()
})
watch(timeRange, () => {
  lastSig = null
  scheduleDrawGraph()
})
watch(() => state.theme, () => {
  resolveColors()
  lastSig = null
  scheduleDrawGraph()
})
watch(tooltip, scheduleHoverDraw)

// ---- 交互：悬停 tooltip / 点击切样式 / 范围循环 ----

function onGraphMove(event) {
  const data = displayData.value
  if (data.length === 0) return
  const { clientX, clientY } = event
  if (moveFrame !== null) return
  moveFrame = requestAnimationFrame(() => {
    moveFrame = null
    const canvas = canvasRef.value
    if (!canvas || !displayData.value.length) return
    const rect = canvas.getBoundingClientRect()
    const mouseX = clientX - rect.left
    const mouseY = clientY - rect.top
    const effectiveWidth = rect.width - PADDING_LEFT - PADDING_RIGHT
    if (effectiveWidth <= 0) return
    const ratio = Math.max(0, Math.min(1, (mouseX - PADDING_LEFT) / effectiveWidth))
    const dataIndex = Math.round(ratio * (displayData.value.length - 1))
    if (dataIndex < 0 || dataIndex >= displayData.value.length) return
    const point = displayData.value[dataIndex]
    const [upValue, upUnit] = parseTraffic(point.up)
    const [downValue, downUnit] = parseTraffic(point.down)
    const { topValue, bottomValue } = yScale.value
    const upY = calculateY(point.up, rect.height, topValue, bottomValue)
    const downY = calculateY(point.down, rect.height, topValue, bottomValue)
    const highlightY = Math.max(point.up, point.down) === point.up ? upY : downY

    tooltip.x = mouseX
    tooltip.y = mouseY
    tooltip.upSpeed = `${upValue}${upUnit}/s`
    tooltip.downSpeed = `${downValue}${downUnit}/s`
    tooltip.time = point.t ? fmtHourMinuteSecond(point.t) : ''
    tooltip.dataIndex = dataIndex
    tooltip.highlightY = highlightY
    tooltip.visible = true
  })
}

function onGraphLeave() {
  if (moveFrame !== null) {
    cancelAnimationFrame(moveFrame)
    moveFrame = null
  }
  tooltip.visible = false
}

function toggleStyle() {
  chartStyle.value = chartStyle.value === 'bezier' ? 'line' : 'bezier'
}

function cycleRange() {
  timeRange.value = timeRange.value === 1 ? 5 : timeRange.value === 5 ? 10 : 1
}

const tooltipStyle = computed(() => ({
  left: `${tooltip.x + 8}px`,
  top: `${tooltip.y - 8}px`,
  transform: tooltip.x > 200 ? 'translateX(-100%)' : 'translateX(0)',
}))

let resizeObs = null
onMounted(() => {
  resolveColors()
  lastSig = null
  scheduleDrawGraph()
  if (typeof ResizeObserver !== 'undefined' && chartWrap.value) {
    resizeObs = new ResizeObserver(() => {
      lastSig = null
      scheduleDrawGraph()
    })
    resizeObs.observe(chartWrap.value)
  }
})
onBeforeUnmount(() => {
  if (resizeObs) resizeObs.disconnect()
  if (drawFrame !== null) cancelAnimationFrame(drawFrame)
  if (hoverFrame !== null) cancelAnimationFrame(hoverFrame)
  if (moveFrame !== null) cancelAnimationFrame(moveFrame)
})

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

.account-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: 320px;
  height: 32px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  background: var(--c-card);
  border: 1px solid var(--c-border);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.account-chip i {
  color: var(--c-info);
  font-size: 12px;
}

.account-chip.unset {
  color: var(--c-hint);
  font-weight: 400;
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
  min-height: 200px;
  display: flex;
  flex-direction: column;
}

.chart-hints {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--c-hint);
}

.hint-chip.paused {
  color: var(--c-warning);
}

/* 图表区：CV 的绘图盒子（圆角 + 悬停底色） */
.chart-wrap {
  flex: 1;
  min-height: 0;
  position: relative;
  overflow: hidden;
  border-radius: var(--radius-sm);
  background: var(--c-hover);
  cursor: pointer;
}

.chart-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
}

.chart-hover {
  pointer-events: none;
}

/* 叠加层：时间范围 chip（点击循环 1/5/10 分钟） */
.cv-range {
  position: absolute;
  top: 6px;
  left: 40px;
  font-size: 11px;
  font-weight: 700;
  color: var(--c-text-sub);
  background: var(--c-card);
  border: 1px solid var(--c-border);
  border-radius: 4px;
  padding: 2px 8px;
  cursor: pointer;
  user-select: none;
}

.cv-range:hover {
  background: var(--c-accent-soft);
}

/* 叠加层：图例（右上，上行/下行各占一行） */
.cv-legend {
  position: absolute;
  top: 6px;
  right: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  pointer-events: none;
}

.cv-legend span {
  font-size: 11px;
  font-weight: 700;
  text-align: right;
}

.cv-legend .cv-up {
  color: var(--c-chart-up);
}

.cv-legend .cv-down {
  color: var(--c-chart-down);
}

/* 叠加层：样式标签（右下）与诊断（左下） */
.cv-style {
  position: absolute;
  bottom: 6px;
  right: 8px;
  font-size: 10px;
  color: var(--c-hint);
  opacity: 0.7;
  pointer-events: none;
}

.cv-diag {
  position: absolute;
  bottom: 6px;
  left: 8px;
  font-size: 9px;
  color: var(--c-hint);
  opacity: 0.6;
  line-height: 1.2;
  pointer-events: none;
}

/* 悬停数值浮层 */
.cv-tooltip {
  position: absolute;
  background: var(--c-card);
  border: 1px solid var(--c-border);
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 10px;
  line-height: 1.2;
  z-index: 1000;
  pointer-events: none;
  white-space: nowrap;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.cv-tt-time {
  color: var(--c-text-sub);
  margin-bottom: 2px;
}

.cv-tt-up {
  color: var(--c-chart-up);
  font-weight: 500;
}

.cv-tt-down {
  color: var(--c-chart-down);
  font-weight: 500;
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
