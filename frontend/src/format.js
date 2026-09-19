// 数值格式化：与 Go 侧 util.Format* 保持一致的输出风格。
const KB = 1024
const MB = KB * 1024
const GB = MB * 1024
const TB = GB * 1024

/** 速率：字节/秒 → 人类可读（B/s, KB/s, MB/s, GB/s）。 */
export function formatSpeed(bytesPerSec) {
  const v = Number(bytesPerSec) || 0
  if (v < KB) return `${v} B/s`
  if (v < MB) return `${(v / KB).toFixed(1)} KB/s`
  if (v < GB) return `${(v / MB).toFixed(2)} MB/s`
  if (v < TB) return `${(v / GB).toFixed(2)} GB/s`
  return `${(v / TB).toFixed(2)} TB/s`
}

/** 流量总量：字节 → 人类可读。 */
export function formatBytes(bytes) {
  const v = Number(bytes) || 0
  if (v < KB) return `${v} B`
  if (v < MB) return `${(v / KB).toFixed(1)} KB`
  if (v < GB) return `${(v / MB).toFixed(2)} MB`
  if (v < TB) return `${(v / GB).toFixed(2)} GB`
  return `${(v / TB).toFixed(2)} TB`
}

/** 时长：秒 → HH:MM:SS（超过一天时带上天数）。 */
export function formatDuration(totalSeconds) {
  let s = Math.max(0, Math.floor(Number(totalSeconds) || 0))
  const days = Math.floor(s / 86400)
  s -= days * 86400
  const h = Math.floor(s / 3600)
  s -= h * 3600
  const m = Math.floor(s / 60)
  const sec = s - m * 60
  const pad = (n) => String(n).padStart(2, '0')
  const base = `${pad(h)}:${pad(m)}:${pad(sec)}`
  return days > 0 ? `${days}天 ${base}` : base
}
