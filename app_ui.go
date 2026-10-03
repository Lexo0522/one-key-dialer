package main

import (
	"context"
	"encoding/json"
	"os"
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/ipc"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

// UIApp 是 UI 进程的 Wails 绑定门面:方法名/签名与代理进程的 App 完全一致,
// 全部经命名管道转发给代理执行;代理事件桥接进 Wails 事件总线。
// 前端通过自动生成的绑定调用本结构,无需任何改动。
type UIApp struct {
	ctx    context.Context
	client *ipc.Client

	quitOnce    sync.Once
	windowShown bool
}

func newUIApp(client *ipc.Client) *UIApp {
	return &UIApp{client: client}
}

// ============================ 生命周期 ============================

// startup Wails 启动回调:接入事件桥。
func (u *UIApp) startup(ctx context.Context) {
	u.ctx = ctx
	u.windowShown = true

	u.client.OnEvent = func(event string, payload json.RawMessage) {
		switch event {
		case SysEventShow:
			u.windowShown = true
			runtime.WindowShow(u.ctx)
			runtime.WindowUnminimise(u.ctx)
		case SysEventQuit:
			u.quit()
		default:
			runtime.EventsEmit(u.ctx, event, payload)
		}
	}
	// 代理退出(含托盘退出/更新重启/崩溃)后,UI 进程没有存在意义,随即退出
	u.client.OnDisconnect = u.quit

	u.syncLocalLang()
}

// shutdown Wails 停机回调:断开管道。
func (u *UIApp) shutdown(ctx context.Context) {
	u.client.Close()
}

// beforeClose 关闭窗口 = 退出 UI 进程(代理继续在托盘驻留)。
// 返回 false 放行默认关闭,wails.Run 随之返回、进程结束。
func (u *UIApp) beforeClose(ctx context.Context) bool {
	u.windowShown = false
	return false
}

// quit 退出 UI 进程(幂等)。
func (u *UIApp) quit() {
	u.quitOnce.Do(func() {
		if u.ctx != nil {
			runtime.Quit(u.ctx)
			return
		}
		os.Exit(0)
	})
}

// syncLocalLang 让 UI 进程内跑的后端文案(文件对话框标题等)与代理的语言一致。
func (u *UIApp) syncLocalLang() {
	raw, err := u.client.Call("GetUILang")
	if err != nil {
		return
	}
	var lp LangPayload
	if json.Unmarshal(raw, &lp) != nil {
		return
	}
	if lp.Auto {
		i18n.SetLang("auto")
	} else if lp.Lang != "" {
		i18n.SetLang(lp.Lang)
	}
}

// ============================ 管道转发辅助 ============================

func (u *UIApp) call(method string, args ...any) (json.RawMessage, error) {
	return u.client.Call(method, args...)
}

// callInto 调用并把结果解码进 out。
func (u *UIApp) callInto(method string, out any, args ...any) error {
	raw, err := u.client.Call(method, args...)
	if err != nil {
		return err
	}
	if raw == nil || string(raw) == "null" {
		return nil
	}
	return json.Unmarshal(raw, out)
}

// ============================ 绑定方法转发 ============================

func (u *UIApp) Bootstrap() AppState {
	var st AppState
	if err := u.callInto("Bootstrap", &st); err != nil {
		return AppState{}
	}
	return st
}

func (u *UIApp) SetUILang(lang string) string {
	i18n.SetLang(lang) // UI 进程本地文案(对话框标题)同步切换
	var out string
	if err := u.callInto("SetUILang", &out, lang); err != nil {
		return ""
	}
	return out
}

func (u *UIApp) GetUILang() LangPayload {
	var lp LangPayload
	if err := u.callInto("GetUILang", &lp); err != nil {
		return LangPayload{}
	}
	return lp
}

func (u *UIApp) SaveSettings(next model.Settings) {
	_, _ = u.call("SaveSettings", next)
}

func (u *UIApp) SetAutoStart(enabled bool) bool {
	var ok bool
	if err := u.callInto("SetAutoStart", &ok, enabled); err != nil {
		return false
	}
	return ok
}

// ============================ 宽带账号 ============================

func (u *UIApp) GetBroadband() BroadbandCredentialDTO {
	var out BroadbandCredentialDTO
	if err := u.callInto("GetBroadband", &out); err != nil {
		return BroadbandCredentialDTO{}
	}
	return out
}

func (u *UIApp) SaveBroadband(username, password string) bool {
	var ok bool
	if err := u.callInto("SaveBroadband", &ok, username, password); err != nil {
		return false
	}
	return ok
}

// Dial 用已保存的宽带凭据拨号（代理侧完成，密码不出后端）。
func (u *UIApp) Dial() bool {
	var ok bool
	if err := u.callInto("Dial", &ok); err != nil {
		return false
	}
	return ok
}

func (u *UIApp) Disconnect() bool {
	var ok bool
	if err := u.callInto("Disconnect", &ok); err != nil {
		return false
	}
	return ok
}

// ============================ 拨号设备 ============================

func (u *UIApp) DiagListDevices() []DeviceOption {
	var out []DeviceOption
	if err := u.callInto("DiagListDevices", &out); err != nil {
		return []DeviceOption{}
	}
	return out
}

func (u *UIApp) DiagSelectDevice(port, device string, rewrite bool) string {
	var out string
	if err := u.callInto("DiagSelectDevice", &out, port, device, rewrite); err != nil {
		return ""
	}
	return out
}

// ============================ WiFi / 门户认证 ============================

func (u *UIApp) WifiStatus() WifiStatusDTO {
	var out WifiStatusDTO
	if err := u.callInto("WifiStatus", &out); err != nil {
		return WifiStatusDTO{}
	}
	return out
}

func (u *UIApp) WifiAvailable() bool {
	var ok bool
	if err := u.callInto("WifiAvailable", &ok); err != nil {
		return false
	}
	return ok
}

func (u *UIApp) WifiScan(force bool) []WifiNetworkDTO {
	var out []WifiNetworkDTO
	if err := u.callInto("WifiScan", &out, force); err != nil {
		return []WifiNetworkDTO{}
	}
	return out
}

// WifiConnect 连接耗时最长约 15 秒,代理侧异步执行,这里立即返回受理结果。
func (u *UIApp) WifiConnect(ssid, password string) bool {
	var ok bool
	if err := u.callInto("WifiConnect", &ok, ssid, password); err != nil {
		return false
	}
	return ok
}

func (u *UIApp) WifiDisconnect() bool {
	var ok bool
	if err := u.callInto("WifiDisconnect", &ok); err != nil {
		return false
	}
	return ok
}

func (u *UIApp) GetPortalCredential() PortalCredentialDTO {
	var out PortalCredentialDTO
	if err := u.callInto("GetPortalCredential", &out); err != nil {
		return PortalCredentialDTO{}
	}
	return out
}

func (u *UIApp) SavePortalCredential(username, password string) bool {
	var ok bool
	if err := u.callInto("SavePortalCredential", &ok, username, password); err != nil {
		return false
	}
	return ok
}

// TestPortalAuth 同步执行一次完整认证流程(约 3-10 秒,低于管道 20 秒超时)。
func (u *UIApp) TestPortalAuth() PortalTestResult {
	var out PortalTestResult
	if err := u.callInto("TestPortalAuth", &out); err != nil {
		return PortalTestResult{}
	}
	return out
}

func (u *UIApp) CheckUpdate(interactive bool) {
	_, _ = u.call("CheckUpdate", interactive)
}

func (u *UIApp) DownloadUpdate() {
	_, _ = u.call("DownloadUpdate")
}

func (u *UIApp) CancelUpdateDownload() {
	_, _ = u.call("CancelUpdateDownload")
}

func (u *UIApp) InstallUpdate() {
	_, _ = u.call("InstallUpdate")
}

func (u *UIApp) OpenReleasePage(url string) {
	_, _ = u.call("OpenReleasePage", url)
}

// UpdateBusy 查询代理侧更新是否进行中。
func (u *UIApp) UpdateBusy() bool {
	var busy bool
	if err := u.callInto("UpdateBusy", &busy); err != nil {
		return false
	}
	return busy
}

// ============================ 窗口 / 退出 ============================

// ShowWindow 显示本进程的窗口。
func (u *UIApp) ShowWindow() {
	u.windowShown = true
	if u.ctx == nil {
		return
	}
	runtime.WindowShow(u.ctx)
	runtime.WindowUnminimise(u.ctx)
}

// HideWindow 窗口消失即 UI 进程退出:直接结束本进程,
// 代理进程继续在托盘驻留(内存随之释放)。
func (u *UIApp) HideWindow() { u.quit() }

// IsWindowVisible 窗口是否可见。
func (u *UIApp) IsWindowVisible() bool { return u.windowShown }

// ExitProgram 通知代理有序退出(托盘退出/更新安装),随后本进程退出。
func (u *UIApp) ExitProgram() {
	// 代理退出前不会应答,用短超时 fire-and-forget
	_, _ = u.client.CallTimeout(2*time.Second, "ExitProgram")
	u.quit()
}
