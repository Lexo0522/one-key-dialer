package main

import (
	_ "embed"
	"strings"
	"sync"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
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
				a.DialCurrentAccount()
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

// refreshTrayLabels 语言切换后重写托盘标题与静态菜单文案。
// 账号子菜单与 tooltip 由 refreshTray 的 3s 轮询负责，这里不重复处理。
func refreshTrayLabels() {
	trayMu.Lock()
	ready := trayReady
	show, dial, hangup, sw, upd, exit := mItemShow, mItemDial, mItemHangup, mItemSwitch, mItemUpdate, mItemExit
	trayMu.Unlock()
	if !ready {
		return
	}
	systray.SetTitle(i18n.T("app.title"))
	if show != nil {
		show.SetTitle(i18n.T("tray.showWindow"))
	}
	if dial != nil {
		dial.SetTitle(i18n.T("home.dial.connect"))
	}
	if hangup != nil {
		hangup.SetTitle(i18n.T("home.dial.disconnect"))
	}
	if sw != nil {
		sw.SetTitle(i18n.T("tray.switchAccount"))
	}
	if upd != nil {
		upd.SetTitle(i18n.T("tray.checkUpdates"))
	}
	if exit != nil {
		exit.SetTitle(i18n.T("tray.exit"))
	}
}

// tooltipMask 把账号掩码收敛到 8 字符内,避免长用户名把 tooltip 顶超 64 字符。
func tooltipMask(username string) string {
	m := util.MaskAccount(username, 2)
	if r := []rune(m); len(r) > 8 {
		m = string(r[len(r)-8:])
	}
	return m
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
	down := a.sessionDown
	up := a.sessionUp
	a.mu.Unlock()

	// Windows 11 任务栏把托盘 tooltip 截断在 64 字符(含结尾 NUL),
	// 文案必须紧凑:去掉标题与时长,上下行速率并作一行,账号掩码
	// 收敛到 8 字符,保证 ↓/↑ 行永远不会被截掉。
	var sb strings.Builder
	if online {
		sb.WriteString(i18n.T("status.connected"))
		if acc := a.accounts.CurrentOrNil(); acc != nil && acc.Username != "" {
			sb.WriteString("\n" + i18n.T("tray.account") + tooltipMask(acc.Username))
		}
		a.mu.Lock()
		ds, us := a.downSpeed, a.upSpeed
		a.mu.Unlock()
		sb.WriteString("\n↓ " + util.FormatSpeed(ds) + " ↑ " + util.FormatSpeed(us))
		sb.WriteString("\n" + i18n.T("tray.totalShort") + util.FormatBytes(down+up))
	} else {
		sb.WriteString(i18n.T("status.disconnected"))
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
