# PPPoE校园网自动拨号工具

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Windows%2010%2F11-lightgrey.svg)](https://github.com/Lexo0522/one-key-dialer)
[![CI](https://github.com/Lexo0522/one-key-dialer/actions/workflows/ci.yml/badge.svg)](https://github.com/Lexo0522/one-key-dialer/actions/workflows/ci.yml)

Windows 校园网 PPPoE 图形拨号工具：**Go + Wails v2 + Vue 3**。左右布局（侧栏导航 + 主内容区）、一键拨号/断开、自动重连、托盘、近 10 分钟流量曲线与在线更新。拨号走原生 Win32 `RasDialW`（密码只存在于进程内存，不落到命令行）。

仓库：<https://github.com/Lexo0522/one-key-dialer>

## 架构

```
main.go            入口与模式解析:--agent 代理模式 / 无参或 --ui 为 UI 模式
app.go             代理核心:Bootstrap / 拨号 / 宽带凭据 / 设置 / 更新(经管道暴露给 UI)
app_ui.go          UI 进程绑定门面:同名方法经命名管道转发,事件桥接进 Wails
app_internal.go    事件广播、状态机、DialView / DialEnvironment 实现
agent.go           代理内存紧致化(GC 调优 + 周期归还)
tray.go            系统托盘菜单与气泡(驻留在代理进程)
internal/
  ipc/             代理↔UI 命名管道通信(NDJSON 握手/请求/事件广播 + 反射分发)
  model/           设置 / 宽带凭据 / 探测配置
  platform/        RAS、DPAPI、注册表、电话簿、气泡通知、iphlpapi 原生流量/ICMP（纯 syscall,无 cgo）
  storage/         JSON 信封读写、原子替换、ACL 收紧
  service/         拨号编排、拨号设备选择、自动重连、流量采样、监控
  update/          双线路在线更新（Gitee 主 / GitHub 备）
  i18n/            中英文案表
  util/            格式化、脱敏、进程 IO
frontend/          Vue 3 + Vite；左右布局（侧栏：首页/WiFi/宽带/日志/设置），wailsjs/ 为自动生成的 Go 绑定
```

### 进程模型(常驻内存 ~10MB)

同一个 exe 按参数分两种运行形态:

- **代理模式 `PPPoEDialer.exe --agent`**(开机自启动即此模式):纯 Go 常驻进程,承载全部服务(托盘、拨号、监控、自动重连、存储、更新),**不启动 WebView**。经 GC 调优与周期内存归还,任务管理器口径的常驻内存约 **9-15MB**。
- **UI 模式 `PPPoEDialer.exe`**(双击/托盘「显示窗口」):按需拉起,经命名管道(`\\.\pipe\PPoEDialerAgent`)调用代理,窗口外观与操作和单进程时代完全一致,前端 Vue 代码零改动。**关闭窗口即 UI 进程退出**,WebView2 与渲染内存立刻归还系统;代理继续在托盘驻留。
- UI 崩溃不影响代理;代理退出后 UI 进程自动随之退出。更新安装前,更新脚本会等待代理与全部 UI 进程退出后再覆盖文件。

### 其它实现要点

- 流量采样用 iphlpapi `GetIfTable`、连通探测用 `IcmpSendEcho`,不再每 3 秒拉起 `netstat`/`ping` 子进程。
- `frontend/src/assets/fontawesome/` 本地打包 FA5 图标,离线启动不依赖 CDN。

前端只做展示与输入采集，**全部业务逻辑在 Go 侧**：账号密码经 DPAPI 加密落盘、拨号凭据一次性使用后立即清零、更新包强制 HTTPS + SHA-256 校验。

## 功能

- 拨号/断开，固定 RAS 连接名 `pppoe_native_java`
- 断网自动重连（按检测间隔重拨）
- **统一网络探测**：自动重连 / 拨号后确认共用一套探测配置（icmp / http / auto）
- **宽带账号**（校园网一人一号）：密码以 **Windows DPAPI**（CurrentUser）保护后存入 `broadband.json`，明文永不回传界面
- 拨号成功后外网确认；可选「无外网时自动断开宽带」
- 首页流量统计：近 10 分钟上传/下载速率折线图，以及本次连接的上传/下载速度与流量卡片
- 界面五个页面：首页（连接 + 只读宽带账号 + 流量图表）、WiFi（扫描/连接/门户自动认证）、宽带（账号凭据 + 拨号设备下拉选择）、日志（级别过滤/搜索/自动滚动）、设置（基本 / 代理 / 更新三大卡片；含主题、语言、开机自启、流量嗅探、断网自动重连、轻量化、无外网自动断开、代理、检查更新）
- **应用内代理**：设置页可配置 HTTP/HTTPS/SOCKS5 代理，**仅作用于本应用自身的 HTTP 请求**（在线更新检查与下载、HTTP 模式外网探测），不修改系统代理设置、不影响其他程序；支持绕过列表（精确主机、`*.example.com` 后缀、通配符、CIDR、`<local>` 私网/环回），未启用时回退系统环境变量代理
- 拨号设备选择：列出电话簿中的 PPPoE 设备（WAN Miniport 等），选中即写入电话簿并持久化
- 系统托盘：显示窗口、拨号、断开、检查更新、退出；关闭窗口即退出界面进程并释放渲染内存（常驻内存 ~10MB），代理继续驻留托盘
- 设置项「低内存渲染」：UI 窗口改用 CPU 软渲染（`WebviewGpuIsDisabled`），打开窗口期间再省一个 GPU 子进程
- 开机自启动（`HKCU\...\Run`，以注册表为准，设置项仅用于启动时修复）
- **主备双线路在线更新**：主线路 Gitee Release，备用线路 GitHub Release；串行执行、主线路失败自动降级、按线路熔断。仅下载带 `SHA256SUMS.txt` 且哈希校验通过的包到 `%APPDATA%\PPoEDialer\updates\`，支持断点续传与停滞看门狗；安装进程确认启动后才退出，全程失败保留当前版本
- 主题：跟随系统 / 浅色 / 深色（切换立即生效）；界面语言：跟随系统（默认，运行期随系统显示语言自动切换）/ 简体中文 / 英文

## 数据存储

`settings.json`、`broadband.json`、`portal.json`、`wifi.json` 位于数据目录（打包版为程序目录，开发时为工作目录，均不可写时回退 `%APPDATA%\PPoEDialer`）。写入为原子替换，JSON 根节点带 `schemaVersion`，非法内容会明确报告并安全回退默认值。

## 系统要求

- 运行：Windows 10/11（WebView2 Runtime）
- 构建：Go 1.24+、Node 22+（前端）、Wails CLI v2

## 安装

Release 提供两种包，按需选用：

- `PPPoEDialer-<版本>-windows.zip`：便携版，解压即用，安装目录可写时由程序内更新直接覆盖。
- `PPPoEDialer-<版本>-windows.msi`：安装版，装到 `Program Files`。

MSI 首个界面（欢迎 + 许可协议页）上有两个默认勾选的选项：

| 选项 | 作用 |
| --- | --- |
| 创建桌面快捷方式 | 在桌面放一个入口；取消勾选则只保留开始菜单项 |
| 立即启动应用 | 安装结束后自动启动程序 |

取消「立即启动应用」不影响安装本身。应用内更新走 `msiexec /i ... /qn`，是完全没有界面的静默安装，不会出现这两个复选框，装完由更新脚本自行重启应用。

本地用 `wails build --nsis` 打出的 NSIS 安装包提供同样两个选项，分布位置略有不同：「桌面快捷方式」在选择安装目录之后的组件页，「立即启动应用」在最后的完成页。NSIS 安装包不参与应用内更新流程，更新只会用到 MSI 或 ZIP。

## 快速开始

```bash
# 开发模式（热重载）
wails dev

# 构建 Windows 可执行文件（版本号从 version.txt 注入）
wails build -ldflags "-X github.com/Lexo0522/one-key-dialer/internal/model.version=$(cat version.txt)"
# → build/bin/PPPoEDialer.exe

# 单独构建前端（浏览器 dev 模式会自动降级到 mock 后端）
cd frontend && npm install && npm run dev

# 检查与测试
gofmt -l . && go vet ./... && go test ./...
```

`version.txt` 是版本号的唯一来源（裸 semver，如 `1.1.11`），必须经 `-ldflags` 注入 `internal/model` 的 `version` 变量。发布标签必须是 `v<version.txt>`，例如 `v1.1.11`——CI 会校验一致性，并校验 `version.go` 兜底值、`wails.json` 的 `productVersion`、`frontend/package.json` 三处声明都与它一致。

不注入也能构建，此时界面版本号停在 `version.go` 的兜底值；但该值同时是更新比较的「当前版本」基准，发版时务必注入，否则新版 exe 会认为自己是旧版。


## 在线更新验证

1. 托盘 →「检查更新」：应提示发现新版本并推荐安装包
   - 便携版（安装目录可写）推荐 ZIP；MSI 安装版（Program Files）推荐 MSI
2. 「下载并安装」→ 进度条 → SHA-256 校验 → 程序退出 → 更新脚本应用并重启
3. 重启后标题栏应显示新版本号

安装目录不可写时 ZIP 更新会被脚本拒绝并提示改用 MSI。

## 数据与安全

- 密码持久化仅经 DPAPI（`DPAPI1:` 前缀 blob）；不生成任何明文密钥文件
- 拨号调用原生 `RasDialW`，密码经结构体传入，不出现在命令行；凭据为一次性对象，用后清零
- `broadband.json` / `settings.json` 写入后经 NTFS ACL 限制为所有者 + SYSTEM + Administrators
- 日志对 `password=` / `pwd=` 等模式脱敏；托盘提示账号尾号遮罩
- 更新下载强制 HTTPS（不接受降级重定向），仅安装通过 `SHA256SUMS.txt` 校验的包
- DPAPI 仅保护本机当前用户密钥；同用户恶意进程仍可能读取——比硬编码强，不是 HSM
- 若凭据文件曾泄露，请在学校/运营商侧修改密码

## License

本项目采用 [MIT License](LICENSE)，Copyright (c) 2026 Lexo0522。
