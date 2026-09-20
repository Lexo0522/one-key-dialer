# PPPoE校园网自动拨号工具

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Windows%2010%2F11-lightgrey.svg)](https://github.com/Lexo0522/one-key-dialer)
[![CI](https://github.com/Lexo0522/one-key-dialer/actions/workflows/ci.yml/badge.svg)](https://github.com/Lexo0522/one-key-dialer/actions/workflows/ci.yml)

Windows 校园网 PPPoE 图形拨号工具：**Go + Wails v2 + Vue 3**。左右布局（侧栏导航 + 主内容区）、一键拨号/断开、自动重连、定时任务、多账号、托盘、近 10 分钟流量曲线与在线更新。拨号走原生 Win32 `RasDialW`（密码只存在于进程内存，不落到命令行）。

仓库：<https://github.com/Lexo0522/one-key-dialer>

## 架构

```
main.go            Wails 入口（窗口、托盘、--autostart）
app.go             Wails 绑定门面：Bootstrap / 拨号 / 设置 / 账号 / 诊断 / 更新
app_internal.go    事件推送、状态机、DialView / DialEnvironment 实现
tray.go            系统托盘菜单与气泡
internal/
  model/           设置 / 账号 / 历史 / 探测配置（JSON 字段与旧版一致）
  platform/        RAS、DPAPI、注册表、电话簿、气泡通知（纯 syscall，无 cgo）
  storage/         JSON 信封读写、原子替换、CSV 导入导出、ACL 收紧
  service/         拨号编排、自动重连、定时任务、流量采样、监控、诊断
  update/          双线路在线更新（Gitee 主 / GitHub 备）
  i18n/            中英文案表
  util/            格式化、脱敏、进程 IO
frontend/          Vue 3 + Vite；左右布局（侧栏：首页/账号配置/日志/设置），wailsjs/ 为自动生成的 Go 绑定
```

前端只做展示与输入采集，**全部业务逻辑在 Go 侧**：账号密码经 DPAPI 加密落盘、拨号凭据一次性使用后立即清零、更新包强制 HTTPS + SHA-256 校验。

## 功能

- 拨号/断开，固定 RAS 连接名 `pppoe_native_java`（界面「昵称」仅为显示名）
- 断网自动重连、定时拨号/断开（分钟对齐）
- **统一网络探测**：自动重连 / 拨号后确认共用一套探测配置（icmp / http / auto）
- 多账号管理；密码以 **Windows DPAPI**（CurrentUser）保护后存入 `accounts.json`，界面列表永不回传明文
- 拨号成功后外网确认；可选「无外网时自动断开宽带」；历史可记 `RAS成功无外网`
- 首页流量统计：近 10 分钟上传/下载速率折线图，以及本次连接的上传/下载速度与流量卡片
- 界面四个页面：首页（账号选择 + 连接 + 流量图表）、账号配置、日志（级别过滤/搜索/自动滚动）、设置（主题、语言、开机自启、选择设备、流量嗅探、断网自动重连、轻量化、无外网自动断开、定时任务、代理、检查更新）
- 拨号历史与统计由后端继续记录维护（界面入口收敛到上述四个页面）
- 系统托盘：显示窗口、拨号、断开、切换账号、检查更新、退出；关闭窗口仅隐藏到托盘
- 开机自启动（`HKCU\...\Run`，以注册表为准，设置项仅用于启动时修复）
- **主备双线路在线更新**：主线路 Gitee Release，备用线路 GitHub Release；串行执行、主线路失败自动降级、按线路熔断。仅下载带 `SHA256SUMS.txt` 且哈希校验通过的包到 `%APPDATA%\PPoEDialer\updates\`，支持断点续传与停滞看门狗；安装进程确认启动后才退出，全程失败保留当前版本
- 主题：跟随系统 / 浅色 / 深色（切换立即生效）；界面语言：简体中文 / 英文

## 数据存储

`settings.json`、`accounts.json`、`history.json` 位于数据目录（打包版为程序目录，开发时为工作目录，均不可写时回退 `%APPDATA%\PPoEDialer`）。写入为原子替换，JSON 根节点带 `schemaVersion`，非法内容会明确报告并安全回退默认值。

## 系统要求

- 运行：Windows 10/11（WebView2 Runtime）
- 构建：Go 1.24+、Node 22+（前端）、Wails CLI v2

## 快速开始

```bash
# 开发模式（热重载）
wails dev

# 构建 Windows 可执行文件
wails build                    # → build/bin/PPoEDialer.exe

# 单独构建前端（浏览器 dev 模式会自动降级到 mock 后端）
cd frontend && npm install && npm run dev

# 检查与测试
gofmt -l . && go vet ./... && go test ./...
```

`version.txt` 是版本号的唯一来源（裸 semver，如 `1.1.11`），构建时经 `-ldflags` 注入。发布标签必须是 `v<version.txt>`，例如 `v1.1.11`——CI 会校验一致性。

## 发布

推送 tag `v*` 触发 `.github/workflows/release.yml`，产出更新器依赖的三件套并发布 Release：

- `PPoEDialer-<version>-windows.zip`
- `PPoEDialer-<version>-windows.msi`
- `SHA256SUMS.txt`（严格格式 `<hash>  <filename>`）

**产物契约不可随意更改**：`internal/update` 按文件名打分选包（安装目录可写时优先 ZIP，否则 MSI），且拒绝安装未出现在 `SHA256SUMS.txt` 中的文件。

配置了 `GITEE_TOKEN` / `GITEE_REPO` 时，`scripts/sync_release_to_gitee.ps1` 会把 Release 镜像到 Gitee（`continue-on-error`，失败不阻断发布）。

## 在线更新验证

1. 托盘 →「检查更新」：应提示发现新版本并推荐安装包
   - 便携版（安装目录可写）推荐 ZIP；MSI 安装版（Program Files）推荐 MSI
2. 「下载并安装」→ 进度条 → SHA-256 校验 → 程序退出 → 更新脚本应用并重启
3. 重启后标题栏应显示新版本号

安装目录不可写时 ZIP 更新会被脚本拒绝并提示改用 MSI。

## 数据与安全

- 账号导出默认**不含密码**；含密码导出需二次确认
- 密码持久化仅经 DPAPI（`DPAPI1:` 前缀 blob）；不生成任何明文密钥文件
- 拨号调用原生 `RasDialW`，密码经结构体传入，不出现在命令行；凭据为一次性对象，用后清零
- `accounts.json` / `settings.json` 写入后经 NTFS ACL 限制为所有者 + SYSTEM + Administrators
- 日志对 `password=` / `pwd=` 等模式脱敏；托盘提示账号尾号遮罩
- 更新下载强制 HTTPS（不接受降级重定向），仅安装通过 `SHA256SUMS.txt` 校验的包
- DPAPI 仅保护本机当前用户密钥；同用户恶意进程仍可能读取——比硬编码强，不是 HSM
- 若账号文件曾泄露，请在学校/运营商侧修改密码

## License

本项目采用 [MIT License](LICENSE)，Copyright (c) 2026 Lexo0522。
