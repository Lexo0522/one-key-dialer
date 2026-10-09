// WiFi 状态卡副标题/图标样式判定。
//
// 后端下发的 status 里有两个容易混淆的字段：
//   - busy  仅表达「本应用发起的连接/断开流程」，是按钮可用性的唯一依据；
//   - phase 是 OS 接口的瞬时相位（idle/connecting/connected/disconnecting），
//     Windows 的 WLAN AutoConfig 自己也会进 connecting（重连已保存网络、
//     AP 漫游、关联失败重试），用户可能什么都没点。
//
// 早年副标题只认 phase，于是「未连接 + 连接中…」会同时出现，看起来像本应用
// 卡住了。这里把两者分开：busy 走本应用的话术，只是系统在动时走另一套。

/** @typedef {{connected?: boolean, busy?: boolean, phase?: string}} WifiStatusLike */

/**
 * 状态副标题的 i18n key，以及图标是否该显示「系统在尝试」的样式。
 * @param {WifiStatusLike} status
 * @returns {{key: string, trying: boolean}}
 */
export function wifiPhaseView(status) {
  const s = status || {}
  if (s.connected) {
    return { key: 'wifi.status.connected', trying: false }
  }
  if (s.busy) {
    // 本应用的流程：受理时 OS 相位可能还没翻页，此时按受理的动作算。
    return s.phase === 'disconnecting'
      ? { key: 'wifi.status.disconnecting', trying: false }
      : { key: 'wifi.status.connecting', trying: false }
  }
  if (s.phase === 'disconnecting') {
    return { key: 'wifi.status.osDisconnecting', trying: true }
  }
  if (s.phase === 'connecting') {
    return { key: 'wifi.status.osConnecting', trying: true }
  }
  return { key: 'wifi.status.idle', trying: false }
}
