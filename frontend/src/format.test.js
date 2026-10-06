import { describe, expect, it } from 'vitest'
import { formatBytes, formatDuration, formatSpeed } from './format.js'

// 这三个函数是界面所有速率/流量/时长的出口，分档边界写错会直接显示成
// "0.0 KB/s" 或 "1023 B" 这类错值。覆盖重点是分档切换点与非法输入。
describe('formatSpeed', () => {
  it('按单位分档', () => {
    expect(formatSpeed(0)).toBe('0 B/s')
    expect(formatSpeed(1023)).toBe('1023 B/s')
    expect(formatSpeed(1024)).toBe('1.0 KB/s')
    expect(formatSpeed(1536)).toBe('1.5 KB/s')
    expect(formatSpeed(1024 * 1024)).toBe('1.00 MB/s')
    expect(formatSpeed(1024 * 1024 * 1024)).toBe('1.00 GB/s')
    expect(formatSpeed(1024 ** 4)).toBe('1.00 TB/s')
  })

  it('小数位随单位收敛', () => {
    expect(formatSpeed(1048576 * 1.5)).toBe('1.50 MB/s')
    // toFixed 是四舍五入，2.345 在浮点下恰好可表示，进位到 2.35
    expect(formatSpeed(1048576 * 2.345)).toBe('2.35 MB/s')
  })

  it('非法输入回落到 0，不显示 NaN', () => {
    expect(formatSpeed(NaN)).toBe('0 B/s')
    expect(formatSpeed(undefined)).toBe('0 B/s')
    expect(formatSpeed(null)).toBe('0 B/s')
    expect(formatSpeed('abc')).toBe('0 B/s')
    expect(formatSpeed({})).toBe('0 B/s')
  })

  it('负速率按 0 处理而不是负值', () => {
    expect(formatSpeed(-5)).toBe('0 B/s')
  })
})

describe('formatBytes', () => {
  it('按单位分档', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1024)).toBe('1.0 KB')
    expect(formatBytes(1024 * 1024)).toBe('1.00 MB')
    expect(formatBytes(1024 ** 3)).toBe('1.00 GB')
    expect(formatBytes(1024 ** 4)).toBe('1.00 TB')
  })

  it('非法输入回落到 0', () => {
    expect(formatBytes(NaN)).toBe('0 B')
    expect(formatBytes('x')).toBe('0 B')
    expect(formatBytes(-1)).toBe('0 B')
  })
})

describe('formatDuration', () => {
  it('不满一天只给时分秒', () => {
    expect(formatDuration(0)).toBe('00:00:00')
    expect(formatDuration(59)).toBe('00:00:59')
    expect(formatDuration(60)).toBe('00:01:00')
    expect(formatDuration(3600)).toBe('01:00:00')
    expect(formatDuration(86399)).toBe('23:59:59')
  })

  it('超过一天带天数前缀', () => {
    expect(formatDuration(86400)).toBe('1天 00:00:00')
    expect(formatDuration(90061)).toBe('1天 01:01:01')
    expect(formatDuration(86400 * 7)).toBe('7天 00:00:00')
  })

  it('小数秒向下取整，不出现 60 这种进位残留', () => {
    expect(formatDuration(59.999)).toBe('00:00:59')
    expect(formatDuration(3600 * 25 + 59)).toBe('1天 01:00:59')
  })

  it('负数与非数字按 0', () => {
    expect(formatDuration(-10)).toBe('00:00:00')
    expect(formatDuration(undefined)).toBe('00:00:00')
    expect(formatDuration('x')).toBe('00:00:00')
  })
})
