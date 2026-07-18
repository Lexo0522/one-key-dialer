# PPPoE校园网自动拨号工具

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-v1.1.0-blue.svg)](https://github.com/Lexo0522/one-key-dialer)
[![Platform](https://img.shields.io/badge/platform-Windows%2010%2F11-lightgrey.svg)](https://github.com/Lexo0522/one-key-dialer)
[![CI](https://github.com/Lexo0522/one-key-dialer/actions/workflows/ci.yml/badge.svg)](https://github.com/Lexo0522/one-key-dialer/actions/workflows/ci.yml)

Windows 校园网 PPPoE 图形拨号工具（Swing + `rasdial`）：一键拨号/断开、自动重连、定时任务、多账号、托盘与诊断。

仓库：<https://github.com/Lexo0522/one-key-dialer>

正式入口：`com.lexo0522.ppoe.PPoEDialer`（默认包下仅保留兼容转发 `PPoEDialer`）。装配在 `AppServices`，懒加载 Tab 在 `ui.MainTabsController`，账号 UI 在 `ui.AccountUiController`，设置接线 `SettingsWiring`，更新 UI `UpdateCheckUi`，拨号前校验 `DialPrecheck`/`DialUiActions`，退出 `ShellShutdown`，进程入口 `AppLauncher`；业务在 `service/*`，持久化在 `storage/*`。版本号以 `model.AppVersion` 为准。

## 功能

- 拨号/断开，固定 RAS 连接名 `pppoe_native_java`（UI「昵称」仅账号显示名）
- 断网自动重连、定时拨号/断开
- **统一网络探测**：自动重连 / 拨号后确认 /「网络探测」Tab 共用 `probe.*` 配置（icmp / http / auto）
- 多账号；密码 AES-GCM（`char[]` 内存路径 + 磁盘加密；密钥：`%APPDATA%\PPoEDialer\master.key`）
- Windows 下 `master.key` 优先 **DPAPI**（`DP1:`）；失败回退明文密钥文件
- **网络探测** tab：icmp / http / auto，以及 **「测试连通」**（不拨号）
- 拨号成功后外网确认；可选无外网自动断开；历史可记 `RAS成功无外网`
- 历史记录、**统计** tab、网络诊断（共享后台调度）、系统托盘（切换账号 / 拨号 / 检查更新）
- 诊断页可 **选择 PPPoE 设备** 并 **重写电话簿**
- 开机自启动（`HKCU\...\Run`，以注册表为准）
- 启动后可选静默检查 GitHub Releases（主页「启动时检查更新」/ INI `update.check`）
- **在线更新**：比较 `AppVersion` 与最新 Release tag；可下载 zip/msi/exe 到 `%APPDATA%\PPoEDialer\updates\`，确认后退出并由脚本覆盖/安装并重启
- 托盘「检查更新」始终可用；无匹配安装包时回退到打开发布页
- 可选 **FlatLaf**（`lib/flatlaf-3.5.4.jar`）；缺失则用系统 L&F

## 系统要求

- Windows 10/11
- 运行：JRE/JDK 11+（推荐 17+）
- 打包 EXE / MSI：JDK **26**（`jpackage` + `jlink --compress=zip-6`；最低建议 21+）
- MSI 安装包：部分环境另需 WiX
- 可选：Maven 3.9+（`pom.xml` + JUnit 5 under `src-test/`；`compiler.release=11`）

## 快速开始

无 Maven（默认）：

```bat
compile_and_run.bat
run_tests.bat
build_jpackage.bat
build_msi.bat
运行程序.bat
```

有 Maven 时：

```bat
mvn -q test
mvn -q package
```

推荐运行 JVM 参数（启动脚本 / jpackage 已写入，可降低默认大堆下的 Working Set）：

```text
-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -Dfile.encoding=UTF-8
```

版本号：**v1.1.0**（`AppVersion.DISPLAY` / `APP_VERSION` 为 `v1.1.0`；`pom.xml` / `jpackage --app-version` / HTTP User-Agent 为 `1.1.0`，以 `model.AppVersion` 为单一来源）

无 Maven 时以 `run_tests.bat` 的 **SelfTest** 为准；本机有 `mvn` 时会顺带跑 JUnit。

可选 FlatLaf：

```bat
curl -fsSL -o lib\flatlaf-3.5.4.jar https://repo1.maven.org/maven2/com/formdev/flatlaf/3.5.4/flatlaf-3.5.4.jar
```

## 数据与安全

- 模板：`pppoe_accounts.ini.example`、`pppoe_settings.ini.example`（含 `probe.*`）
- 真实 `pppoe_*.ini/csv/txt` 与 `master.key` 已被 `.gitignore` 忽略，**请勿提交**
- 账号导出默认**不含密码**；含密码导出需二次确认
- 旧版 XOR 密码首次加载自动迁移为 AES
- 拨号使用 `ProcessBuilder` 参数数组，避免 `cmd /c` 拼接密码
- 进程内密码尽量 `char[]` 并用后清零；**rasdial 子进程 argv 仍为 String**，本地进程列表在子进程存活期间可能可见（Windows 边界）
- 日志对 `password=` / `pwd=` 等模式做简单脱敏；托盘提示账号尾号遮罩
- DPAPI 仅保护本机当前用户密钥；同用户恶意进程仍可能读取——比硬编码强，不是 HSM
- 若账号文件曾泄露，请在学校/运营商侧修改密码

## 路线图（v1.1 已推进）

- [x] 统一自动重连与 probe 配置
- [x] 版本号单一来源（`AppVersion`）
- [x] GitHub Actions CI
- [x] 拨号中按钮状态机
- [x] Settings UI bridge 拆分
- [x] 日志脱敏 + 历史上限（既有 500/1000）
- [x] 网卡/设备选择 + 托盘多账号
- [x] 统计面板、失败引导（诊断重写电话簿）、更新检查、MSI 脚本
- [x] 主类迁入正式包 `com.lexo0522.ppoe`（默认包 launcher 兼容旧脚本）
- [x] 进一步瘦身 shell：`AppServices` / `ShellDialHost` / `MainTabsController` / `AccountUiController`
- [x] 完整在线更新：检测 + 下载 + zip/msi/exe 应用脚本（v1.1.0）
- [ ] Credential Manager 可选存储（v1.2+）

## License

本项目采用 [MIT License](LICENSE)，Copyright (c) 2026 Lexo0522。
