// 应用内灵动岛 Toast：基于 vue-goey-toast 封装。
// 形态：顶部居中小圆角胶囊（图标 + 单行标题，超长省略）；多条消息压缩排队，
// 同语气 + 同标题的连续消息合并为一条；自动计时消失。
// 关闭按钮悬浮时从胶囊右缘“脱出”到胶囊外侧（样式见 style.css），不与文字争位。
// 本模块是全项目唯一 Toast 入口（store 转发、组件直接用），不保留旧实现。
import { gooeyToast } from 'vue-goey-toast'

/** 默认展示时长（毫秒）。 */
export const TOAST_DURATION = 4200

const TONE_METHOD = {
  info: 'info',
  success: 'success',
  warning: 'warning',
  error: 'error'
}

/**
 * 弹出一条胶囊提示。
 * 同语气 + 同标题的连续消息共用稳定 id（sonner 对已存在 id 原地更新），
 * 重复提示合并为一条而非堆叠——如自动重连连续失败只保留最新一条。
 * @param {string} title 标题（胶囊唯一内容，单行，超长省略）
 * @param {'info'|'success'|'warning'|'error'} [tone] 语气（决定配色与图标）
 * @param {object} [options] 透传给底层库的额外选项；显式传入 id 时不纳入合并
 * @returns {string|number} toast id，可用于 dismissToast
 */
export function showToast(title, tone = 'info', options = {}) {
  const method = gooeyToast[TONE_METHOD[tone]] || gooeyToast
  const base = {
    duration: TOAST_DURATION,
    showTimestamp: false
  }
  if (options.id) {
    return method(title, { ...base, ...options })
  }
  return method(title, { ...base, ...options, id: `${tone}::${title}` })
}

/**
 * Promise 回调 Toast：pending 期间常驻 loading 胶囊，resolve 时切换为成功样式、
 * reject 时切换为失败样式，并开始自动计时消失。
 * 只透传 loading/success/error 三个字段：胶囊形态下不传 description/action，
 * 传入会导致库展开为卡片，与本模块的形态约定冲突。
 *
 * 库缺陷兜底：gooeyToast.promise 在传入 timing 时 loading 态 duration 为 Infinity
 * （等待结算，设计如此），但 vue-sonner 的 remainingTime 只在挂载时初始化一次，
 * 结算时 duration 的更新无法重置它，startTimer 直接 return——结算后的 Toast 永不
 * 自动消失（实测挂住 13.9s+）。故在结算后自行补一个消失定时器，时长与库设计的
 * displayDuration + 900ms（收起形变时长）一致。
 * @param {Promise} promise 被包裹的 Promise
 * @param {{loading: string, success: string|Function, error: string|Function}} data 各阶段标题
 * @returns {string} toast id
 */
export function toastPromise(promise, data) {
  const id = gooeyToast.promise(promise, {
    loading: data.loading,
    success: data.success,
    error: data.error,
    showTimestamp: false,
    timing: { displayDuration: TOAST_DURATION }
  })
  const scheduleDismiss = () => {
    setTimeout(() => dismissToast(id), TOAST_DURATION + 900)
  }
  promise.then(scheduleDismiss, scheduleDismiss)
  return id
}

/** 关闭 Toast：传 id 关单条；传 { type } 按语气关；不传参关全部。 */
export function dismissToast(idOrFilter) {
  return gooeyToast.dismiss(idOrFilter)
}
