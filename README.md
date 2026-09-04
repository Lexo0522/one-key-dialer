# PPPoE校园网自动拨号工具

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-v1.1.7-blue.svg)](https://github.com/Lexo0522/one-key-dialer)
[![Platform](https://img.shields.io/badge/platform-Windows%2010%2F11-lightgrey.svg)](https://github.com/Lexo0522/one-key-dialer)
[![CI](https://github.com/Lexo0522/one-key-dialer/actions/workflows/ci.yml/badge.svg)](https://github.com/Lexo0522/one-key-dialer/actions/workflows/ci.yml)


Windows 校园网 PPPoE 图形拨号工具（Swing + RAS）：一键拨号/断开、自动重连、定时任务、多账号、托盘与诊断。拨号优先走 Win32 `RasDial` API（密码不出进程命令行），不可用时回退 `rasdial.exe`。

仓库：<https://github.com/Lexo0522/one-key-dialer>

唯一入口：`com.lexo0522.ppoe.PPoEDialer`。装配在 `AppServices`，懒加载 Tab 在 `ui.MainTabsController`，账号 UI 在 `ui.AccountUiController`，更新 UI `UpdateCheckUi`，拨号前校验 `DialPrecheck`/`DialUiActions`，退出 `ShellShutdown`，进程入口 `AppLauncher`。拨号协调通过窄接口 `DialPort` / `DialView` / `DialEnvironment`（`DialOrchestrator`），RAS 封装在 `WindowsRasModule`，在线更新在 `UpdateModule`，业务在 `service/*`，持久化在 `storage/*`。版本号以 `.mvn/maven.config` 的 `revision` 为唯一来源。

## 功能

- 拨号/断开，固定 RAS 连接名 `pppoe_native_java`（UI「昵称」仅账号显示名）
- 断网自动重连、定时拨号/断开
- **统一网络探测**：自动重连 / 拨号后确认 /「网络探测」Tab 共用探测配置（icmp / http / auto）
- 多账号；密码仅以 **Windows DPAPI**（CurrentUser）保护后存入 `accounts.json`；DPAPI 不可用或解密失败时不持久化、不加载密码，拨号前需重新输入
- **网络探测** tab：icmp / http / auto，以及 **「测试连通」**（不拨号）
- 拨号成功后外网确认；可选无外网自动断开；历史可记 `RAS成功无外网`
- 历史记录、**统计** tab、网络诊断（共享后台调度）、系统托盘（切换账号 / 拨号 / 检查更新）
- 诊断页可 **选择 PPPoE 设备** 并 **重写电话簿**
- 开机自启动（`HKCU\...\Run`，以注册表为准）
- 启动后可选静默检查 GitHub Releases（主页「启动时检查更新」）
- **在线更新**：比较 `AppVersion` 与最新 Release tag；仅下载带 `SHA256SUMS.txt` 且哈希校验通过的 zip/msi/exe 到 `%APPDATA%\PPoEDialer\updates\`；安装进程确认启动后才退出
- 托盘「检查更新」始终可用；无匹配安装包时回退到打开发布页
- 可选 **FlatLaf**（Maven 依赖 `com.formdev:flatlaf`）；缺失则用系统 L&F
- **主题**：跟随系统 / 浅色 / 深色（主页设置，重启后生效；跟随系统读取 Windows 应用深浅色）
- **界面语言**：简体中文（默认）与英文（跟随系统语言；基于 ResourceBundle，`i18n/`）

## 数据存储

应用数据（`settings.json`、`accounts.json`、`history.json`，含 `schemaVersion`）位于数据目录（打包版为程序目录，开发时为工作目录，均写入失败时回退 `%APPDATA%\PPoEDialer`）。全部写入为原子替换；非法 JSON、未知 `schemaVersion` 或 I/O 失败会明确报告并安全回退默认值。旧版 `pppoe_settings.ini` / `pppoe_accounts.ini` / `pppoe_history.csv` / `master.key` 不会被读取、转换或删除。

## 系统要求

- Windows 10/11
- 构建：Maven 3.9+（`pom.xml` + JUnit 5 under `src-test/`；`compiler.release=17`）
- 打包 EXE / MSI：JDK **26**（`jpackage` + `jlink --compress=zip-6`；最低建议 21+）
- MSI 安装包：部分环境另需 WiX

## 自动发布

推送标签 `v<revision>`（如 `v1.1.5`）会触发 `.github/workflows/release.yml`：

1. 校验标签与 `.mvn/maven.config` 中的 revision 一致
2. windows-latest + JDK 26 上先跑全量测试，再用 `prepare_release.bat` 构建 ZIP / MSI / `SHA256SUMS.txt`
3. 自动创建 GitHub Release 并上传三件套（`--generate-notes`）

也可用 `workflow_dispatch` 只构建校验产物不发布。手动本地发布（`prepare_release.bat`）仍然可用。

## 在线更新验证

发布新版本后，可按以下步骤验证在线更新闭环：


1. 托盘 →「检查更新」：应提示发现新版本并推荐安装包
   - 便携版（安装目录可写）推荐 ZIP；MSI 安装版（Program Files）推荐 MSI
2. 「下载并安装」→ 进度条 → SHA-256 校验 → 程序退出 → `apply_update.bat` 应用更新并重启
3. 重启后主页标题应显示新版本号

注意：安装目录不可写时 ZIP 更新会被脚本拒绝并提示改用 MSI。

## 快速开始

```bat
compile_and_run.bat      :: Maven 打包并运行
run_tests.bat            :: mvn test
build_jpackage.bat       :: Maven package + jpackage app-image
build_msi.bat            :: MSI（需要 WiX）
prepare_release.bat      :: ZIP + MSI + SHA256SUMS.txt
运行程序.bat
```

或直接使用 Maven：

```bat
mvn -q test
mvn -q package
```

推荐运行 JVM 参数（启动脚本 / jpackage 已写入，可降低默认大堆下的 Working Set）：

```text
-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -Dfile.encoding=UTF-8
```

工程化入口：`mvn -Pcoverage test` 生成 JaCoCo 覆盖率报告（CI 在 JDK 17 腿自动上传）；`mvn -Perrorprone compile` 运行 Error Prone 静态分析（需要 JDK 17–25，发现项以 warning 呈现）。

版本号：由 `.mvn/maven.config` 中的 `-Drevision=…` 统一定义；Maven、jpackage 脚本和运行时 `AppVersion` 均从该值获得。发布标签必须是 `v<revision>`，例如 `v1.1.5`。

## 发布产物约定

执行 `prepare_release.bat` 会按当前 `revision` 构建 `release/` 下的三个文件：

```text
PPoEDialer-<revision>-windows.zip
PPoEDialer-<revision>-windows.msi
SHA256SUMS.txt
```

该脚本依赖 Windows PowerShell、JDK 21+（推荐 26）以及 MSI 打包所需的 WiX；它会先在 `release/.staging/` 构建，所有文件成功后才发布到 `release/`，只生成本地文件，不会创建或发布 GitHub Release。推送 `v<revision>` 标签即可由 GitHub Actions 自动创建 Release（见「自动发布」）。手动创建 GitHub Release 时，标签必须为 `v<revision>`，并且必须同时上传这三个文件。`SHA256SUMS.txt` 使用标准 SHA-256 格式，每行是两个空格分隔的 `哈希值  文件名`；更新器会在下载完成后以它校验安装包，缺失、格式不正确或不包含目标文件时会拒绝自动安装。

## 数据与安全

- 账号导出默认**不含密码**；含密码导出需二次确认
- 密码持久化仅经 DPAPI（`DPAPI1:` 前缀 blob）；不生成任何明文密钥文件
- 拨号优先通过 **JNA 调用 Win32 `RasDialW`**（`RASDIALPARAMS` 结构体传参，密码不出进程命令行）；原生绑定不可用时回退 `rasdial.exe`，并使用 `ProcessBuilder` 参数数组避免 `cmd /c` 拼接
- `accounts.json` / `settings.json` 写入后按 **NTFS ACL** 限制为 所有者 + SYSTEM + Administrators（POSIX 文件系统走等价标志位）
- 进程内密码尽量 `char[]` 并用后清零（`DialCredentials` 为一次性凭据；结构体在拨号后清零）
- 日志对 `password=` / `pwd=` 等模式做简单脱敏；托盘提示账号尾号遮罩
- 更新下载强制 HTTPS（不跟随降级重定向），仅安装通过 `SHA256SUMS.txt` 校验的安装包
- DPAPI 仅保护本机当前用户密钥；同用户恶意进程仍可能读取——比硬编码强，不是 HSM
- 若账号文件曾泄露，请在学校/运营商侧修改密码

## License

本项目采用 [MIT License](LICENSE)，Copyright (c) 2026 Lexo0522。
