// 界面文案：与旧版 messages.properties / messages_en.properties 一一对应。
const zh = {
  'app.title': 'PPPoE校园网拨号工具',

  'home.status.connected': '已连接',
  'home.status.disconnected': '未连接',
  'home.dial.connect': '连接宽带',
  'home.dial.disconnect': '断开连接',
  'home.dial.dialing': '连接中…',
  'home.dial.disconnecting': '断开中…',
  'home.account.label': '账号:',
  'home.account.config': '账号配置',
  'home.nickname.label': '昵称:',
  'home.username.label': '学号/账号:',
  'home.password.label': '密 码:',
  'home.interval.label': '检测间隔(秒):',
  'home.autoReconnect': '断网自动重连',
  'home.autoStart': '开机自动启动',
  'home.startMinimized': '启动时最小化到托盘',
  'home.disconnectNoInternet': '无外网时自动断开宽带',
  'home.updateCheck': '启动时检查更新',
  'home.autostartHint': '开机自启以注册表为准（非仅配置文件）',
  'home.log.title': '运行日志',
  'home.status.speed': '↓ -- ↑ --',
  'home.status.uptime': '时长: --',
  'home.status.uptimeNone': '时长: 未连接',

  'theme.label': '主题:',
  'theme.system': '跟随系统',
  'theme.light': '浅色',
  'theme.dark': '深色',
  'theme.liveHint': '（切换后立即生效）',

  'tab.home': '主页',
  'tab.schedule': '定时任务',
  'tab.probe': '网络探测',
  'tab.history': '历史记录',
  'tab.stats': '统计',
  'tab.diag': '网络诊断',

  'schedule.dial': '定时自动拨号',
  'schedule.disconnect': '定时自动断开',
  'schedule.everyDay': '每天',
  'schedule.hour': '时',
  'schedule.minute': '分',

  'probe.mode': '探测模式:',
  'probe.host': 'ICMP 主机:',
  'probe.url': 'HTTP URL:',
  'probe.attempts': '尝试次数:',
  'probe.delay': '间隔(ms):',
  'probe.test': '测试连通',
  'probe.testing': '测试中…',
  'probe.result': '结果: ',
  'probe.hint':
    'auto：每次先 ICMP/ping，失败再 HTTP。\n' +
    'icmp：仅探测主机（默认 223.5.5.5）。\n' +
    'http：仅访问 URL（默认 generate_204）。\n' +
    '校园网禁 ICMP 时请用 auto 或 http。\n' +
    '「测试连通」只跑探测、不拨号。\n' +
    '「无外网时自动断开」在主页选项中设置。',

  'history.title': '拨号历史记录',
  'history.col.time': '时间',
  'history.col.op': '操作',
  'history.col.account': '账号',
  'history.col.result': '结果',
  'history.col.duration': '连接时长',
  'history.col.traffic': '流量总和',
  'history.export': '导出CSV',
  'history.clear': '清空记录',
  'history.confirmClear': '确定要清空所有历史记录吗？',
  'history.confirmTitle': '确认清空',

  'stats.refresh': '刷新统计',
  'stats.hint': '基于历史记录汇总（成功/失败/常见结果）',

  'diag.ping': 'Ping测试',
  'diag.ipconfig': 'IP配置',
  'diag.trace': '路由追踪',
  'diag.dns': 'DNS刷新',
  'diag.status': '连接状态',
  'diag.phonebook': '电话簿/探测',
  'diag.device': '选择设备',
  'diag.rewrite': '重写电话簿',
  'diag.clear': '清空',
  'diag.selectDeviceTitle': '选择用于写入电话簿的 PPPoE 设备：',
  'diag.deviceDlgTitle': 'PPPoE 设备',
  'diag.rewriteConfirm': '是否立即重写电话簿连接条目？\n（否则仅记住选择，下次自动创建时生效）',
  'diag.rewriteTitle': '重写电话簿',
  'diag.rewriteWarn': '将删除并重建本程序使用的 RAS 连接条目，是否继续？',

  'account.managerTitle': '账号管理',
  'account.unset': '未设置',
  'account.col.id': 'ID',
  'account.col.nickname': '昵称',
  'account.col.user': '账号',
  'account.col.pass': '密码',
  'account.col.remark': '备注',
  'account.add': '增加',
  'account.edit': '修改',
  'account.delete': '删除',
  'account.up': '上移',
  'account.down': '下移',
  'account.export': '导出',
  'account.import': '导入',
  'account.masked': '********',
  'account.formAdd': '新增账号',
  'account.formEdit': '修改账号',
  'account.nickname': '昵称:',
  'account.user': '账号:',
  'account.pass': '密码:',
  'account.remark': '备注:',
  'account.nameHint': '昵称为空时自动使用账号，无账号则显示"未设置"',
  'account.cancel': '取消',
  'account.ok': '确定',
  'account.selectFirst': '请先选择一个账号',
  'account.keepOne': '至少保留一个账号',
  'account.confirmDelete': '确定删除该账号？',
  'account.exportTitle': '导出账号',
  'account.exportMsg':
    '默认导出不含密码（推荐）。\n若需导出密码，请选择「含密码导出」并妥善保管文件。',
  'account.exportSafe': '安全导出（无密码）',
  'account.exportWithPass': '含密码导出',
  'account.warnTitle': '安全警告',
  'account.warnMsg': '导出文件将包含明文密码，确定继续？',
  'account.importOk': '导入成功！',
  'account.importFail': '导入失败: ',
  'account.exportOk': '导出成功！',
  'account.exportFail': '导出失败: ',

  'update.title': '更新',
  'update.checkTitle': '检查更新',
  'update.download': '下载并安装',
  'update.openPage': '打开发布页',
  'update.later': '稍后',
  'update.close': '关闭',
  'update.cancel': '取消',
  'update.install': '立即安装并重启',
  'update.keepOnly': '仅保留文件',
  'update.check': '检查更新',
  'update.checking': '正在检查更新…',
  'update.upToDate': '已是最新版本',
  'update.newVersion': '发现新版本',
  'update.exitNote': '安装时程序会退出并由脚本覆盖/启动安装包。',
  'update.downloading': '下载中',
  'update.preparing': '正在准备更新，请稍候…',

  'common.confirm': '确认',
  'common.ok': '确定',
  'common.cancel': '取消',
  'common.close': '关闭'
}

const en = {
  'app.title': 'PPPoE Campus Dialer',
  'home.status.connected': 'Connected',
  'home.status.disconnected': 'Disconnected',
  'home.dial.connect': 'Connect',
  'home.dial.disconnect': 'Disconnect',
  'home.dial.dialing': 'Connecting…',
  'home.dial.disconnecting': 'Disconnecting…',
  'home.account.label': 'Account:',
  'home.account.config': 'Manage',
  'home.nickname.label': 'Nickname:',
  'home.username.label': 'Username:',
  'home.password.label': 'Password:',
  'home.interval.label': 'Interval (s):',
  'home.autoReconnect': 'Auto reconnect',
  'home.autoStart': 'Launch at startup',
  'home.startMinimized': 'Start minimized to tray',
  'home.disconnectNoInternet': 'Hang up when no Internet',
  'home.updateCheck': 'Check updates on startup',
  'home.log.title': 'Log',
  'theme.label': 'Theme:',
  'theme.system': 'System',
  'theme.light': 'Light',
  'theme.dark': 'Dark',
  'tab.home': 'Home',
  'tab.schedule': 'Schedule',
  'tab.probe': 'Probe',
  'tab.history': 'History',
  'tab.stats': 'Stats',
  'tab.diag': 'Diagnose',
  'history.title': 'Dial history',
  'history.export': 'Export CSV',
  'history.clear': 'Clear',
  'stats.refresh': 'Refresh',
  'diag.ping': 'Ping',
  'diag.ipconfig': 'IP config',
  'diag.trace': 'Trace',
  'diag.dns': 'Flush DNS',
  'diag.status': 'Status',
  'diag.phonebook': 'Phonebook',
  'diag.device': 'Device',
  'diag.rewrite': 'Rewrite',
  'diag.clear': 'Clear',
  'account.managerTitle': 'Accounts',
  'account.unset': 'Unset',
  'account.add': 'Add',
  'account.edit': 'Edit',
  'account.delete': 'Delete',
  'account.up': 'Up',
  'account.down': 'Down',
  'account.export': 'Export',
  'account.import': 'Import',
  'update.download': 'Download & install',
  'update.openPage': 'Open release page',
  'update.later': 'Later',
  'update.close': 'Close',
  'common.confirm': 'Confirm',
  'common.ok': 'OK',
  'common.cancel': 'Cancel'
}

let current = 'zh'

export function setLang(lang) {
  current = lang === 'en' ? 'en' : 'zh'
}

export function lang() {
  return current
}

export function t(key) {
  if (current === 'en' && en[key] !== undefined) return en[key]
  return zh[key] !== undefined ? zh[key] : key
}

/** {0} {1} 占位替换，与旧版 MessageFormat 行为一致。 */
export function tf(key, ...args) {
  let s = t(key)
  for (let i = 0; i < args.length; i++) {
    s = s.replace(new RegExp('\\{' + i + '\\}', 'g'), String(args[i]))
  }
  return s
}
