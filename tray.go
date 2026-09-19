package main

import (
	_ "embed"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
	"github.com/Lexo0522/one-key-dialer/internal/util"
	"github.com/getlantern/systray"
)

// 托盘菜单最多显示的账号数量（超出部分不再列出）。
const maxTrayAccounts = 10

//go:embed build/windows/icon.ico
var trayIcon []byte

var (
	trayMu       sync.Mutex
	trayApp      *App
	trayReady    bool
	windowShown  bool
	mItemShow    *systray.MenuItem
	mItemDial    *systray.MenuItem
	mItemHangup  *systray.MenuItem
	mItemSwitch  *systray.MenuItem
	mItemUpdate  *systray.MenuItem
	mItemExit    *systray.MenuItem
	accountItems []*systray.MenuItem
)

// initTray 启动托盘（独立 goroutine 中运行消息循环）。
func initTray(a *App) {
	trayMu.Lock()
	trayApp = a
	trayMu.Unlock()
	go systray.Run(onTrayReady, onTrayExit)
}

func onTrayReady() {
	trayMu.Lock()
	app := trayApp
	trayMu.Unlock()
	if app == nil {
		return
	}
	systray.SetIcon(trayIcon)
	systray.SetTitle(i18n.T("app.title"))

	mItemShow = systray.AddMenuItem(i18n.T("tray.showWindow"), "")
	mItemDial = systray.AddMenuItem(i18n.T("home.dial.connect"), "")
	mItemHangup = systray.AddMenuItem(i18n.T("home.dial.disconnect"), "")
	systray.AddSeparator()
	mItemSwitch = systray.AddMenuItem(i18n.T("tray.switchAccount"), "")
	for i := 0; i < maxTrayAccounts; i++ {
		item := mItemSwitch.AddSubMenuItem("", "")
		item.Hide()
		accountItems = append(accountItems, item)
		go func(it *systray.MenuItem) {
			for range it.ClickedCh {
				trayMu.Lock()
				a := trayApp
				trayMu.Unlock()
				if a == nil {
					continue
				}
				for idx, other := range accountItems {
					if other == it {
						a.SwitchAccount(idx)
						break
					}
				}
			}
		}(item)
	}
	systray.AddSeparator()
	mItemUpdate = systray.AddMenuItem(i18n.T("tray.checkUpdates"), "")
	systray.AddSeparator()
	mItemExit = systray.AddMenuItem(i18n.T("tray.exit"), "")

	go func() {
		for range mItemShow.ClickedCh {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil {
				windowShown = true
				a.ShowWindow()
			}
		}
	}()
	go func() {
		for range mItemDial.ClickedCh {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil && !a.isOnline() && !a.lifecycle.IsBusy() {
				a.Dial("", "")
			}
		}
	}()
	go func() {
		for range mItemHangup.ClickedCh {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil && a.isOnline() && !a.lifecycle.IsBusy() {
				a.Disconnect()
			}
		}
	}()
	go func() {
		for range mItemUpdate.ClickedCh {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil {
				a.CheckUpdate(true)
			}
		}
	}()
	go func() {
		for range mItemExit.ClickedCh {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a != nil {
				a.ExitProgram()
			}
		}
	}()

	trayMu.Lock()
	trayReady = true
	trayMu.Unlock()
	app.refreshTray()
	go func() {
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			trayMu.Lock()
			a := trayApp
			trayMu.Unlock()
			if a == nil {
				return
			}
			a.refreshTray()
		}
	}()
}

func onTrayExit() {
	trayMu.Lock()
	trayReady = false
	trayMu.Unlock()
}

// stopTray 退出托盘。
func stopTray() { systray.Quit() }

// showTrayNotification 弹出托盘气泡。
func showTrayNotification(title, message string) {
	trayMu.Lock()
	ready := trayReady
	trayMu.Unlock()
	if !ready {
		return
	}
	platform.ShowNotification(title, message)
}

// refreshTray 刷新托盘图标、tooltip、菜单项与账号子菜单。
func (a *App) refreshTray() {
	trayMu.Lock()
	ready := trayReady
	trayMu.Unlock()
	if !ready {
		return
	}
	a.mu.Lock()
	online := a.online
	conn := a.connectTimeMs
	down := a.sessionDown
	up := a.sessionUp
	a.mu.Unlock()

	var sb strings.Builder
	sb.WriteString(i18n.T("app.title"))
	sb.WriteString("\n")
	if online {
		sb.WriteString(i18n.T("tray.status") + i18n.T("status.connected"))
		if acc := a.accounts.CurrentOrNil(); acc != nil && acc.Username != "" {
			sb.WriteString("\n" + i18n.T("tray.account") + util.MaskAccount(acc.Username, 2))
		}
		if conn > 0 {
			sb.WriteString("\n" + i18n.T("tray.uptime") +
				util.FormatDuration((time.Now().UnixMilli()-conn)/1000))
		}
		a.mu.Lock()
		ds, us := a.downSpeed, a.upSpeed
		a.mu.Unlock()
		sb.WriteString("\n↓ " + util.FormatSpeed(ds))
		sb.WriteString("\n↑ " + util.FormatSpeed(us))
		sb.WriteString("\n" + i18n.T("tray.total") + util.FormatBytes(down+up))
	} else {
		sb.WriteString(i18n.T("tray.status") + i18n.T("status.disconnected"))
	}
	systray.SetTooltip(sb.String())

	busy := a.lifecycle.IsBusy()
	if mItemDial != nil {
		if !online && !busy {
			mItemDial.Enable()
		} else {
			mItemDial.Disable()
		}
	}
	if mItemHangup != nil {
		if online && !busy {
			mItemHangup.Enable()
		} else {
			mItemHangup.Disable()
		}
	}

	accounts := a.accounts.Accounts()
	current := a.accounts.CurrentIndex()
	for i, item := range accountItems {
		if i >= len(accounts) || i >= maxTrayAccounts {
			item.Hide()
			continue
		}
		acc := accounts[i]
		label := acc.Name
		if strings.TrimSpace(label) == "" {
			label = acc.Username
		}
		if label == "" {
			label = i18n.T("account.unnamed")
		}
		if i == current {
			label = "✓ " + label
		}
		item.SetTitle(label)
		item.Show()
	}
	if len(accounts) == 0 && len(accountItems) > 0 {
		accountItems[0].SetTitle(i18n.T("tray.noAccount"))
		accountItems[0].Disable()
		accountItems[0].Show()
	}
}

// trayPidHint 供 apply 脚本等待退出（当前进程 PID）。
func trayPidHint() string { return strconv.Itoa(os.Getpid()) }

var _ = model.ConnectionName
