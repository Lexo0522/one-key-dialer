package platform

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

// ---------------------------------------------------------------------------
// 压测负载
// ---------------------------------------------------------------------------

// burnCPU 占满一个逻辑核心，直到 stopFlag 变为非零。
//
// 用原子标志而不是 select+channel：压测要的就是把 P 死死占住，让窗口线程只能
// 拿到时间片的边角料；select 配空 default 会被 staticcheck 当成失误模式拦下来
// （SA5004），而这里空转恰恰是目的本身。
func burnCPU(stopFlag *int32) {
	for atomic.LoadInt32(stopFlag) == 0 {
	}
}

// burnUntil 起 n 个 burnCPU，返回停止函数。
func burnUntil(n int) func() {
	var stopFlag int32
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			burnCPU(&stopFlag)
		}()
	}
	return func() {
		atomic.StoreInt32(&stopFlag, 1)
		wg.Wait()
	}
}

// TestSessionEndWindowBurnerChild 只被压测父进程拉起来纯烧 CPU。单独跑它
// 什么都不做；父进程靠 PPOE_SESSIONEND_BURNER=1 加 -test.run 选中它。
func TestSessionEndWindowBurnerChild(t *testing.T) {
	if os.Getenv("PPOE_SESSIONEND_BURNER") != "1" {
		t.Skip("burner child, only spawned by the stress test")
	}
	deadline := time.Now().Add(5 * time.Minute) // 兜底：父进程异常时也会自己退出
	x := 0
	for time.Now().Before(deadline) {
		x++
	}
	_ = x
}

// startBurners 拉起 NumCPU+2 个独立烧 CPU 的进程，模拟「整机被别的构建任务
// 占满」——会话结束窗口的抖动只在那种负载下被抓到过：冷构建那一轮，go test
// 一边编译其余包的测试二进制、一边跑本包，窗口线程被调度饿着。
//
// 进程内 busy loop 只是补充：Go 调度器对同进程 goroutine 仍然公平（10ms 级
// 就能切回来），复现不了跨进程的饥饿，所以主力必须是独立进程。
//
// 返回的清理函数必须被调用，否则会留下一圈吃 CPU 的测试二进制。
func startBurners(t *testing.T) func() {
	t.Helper()
	var cmds []*exec.Cmd
	for i := 0; i < runtime.NumCPU()+2; i++ {
		cmd := exec.Command(os.Args[0], "-test.run=^TestSessionEndWindowBurnerChild$", "-test.timeout=6m")
		cmd.Env = append(os.Environ(), "PPOE_SESSIONEND_BURNER=1")
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
		if err := cmd.Start(); err != nil {
			t.Fatalf("start burner: %v", err)
		}
		cmds = append(cmds, cmd)
	}
	return func() {
		for _, cmd := range cmds {
			if cmd.Process != nil {
				_ = cmd.Process.Kill()
			}
		}
		for _, cmd := range cmds {
			_ = cmd.Wait()
		}
	}
}

// ---------------------------------------------------------------------------
// 单轮：子进程
// ---------------------------------------------------------------------------

// TestSessionEndWindowRoundChild 子进程：跑一轮完整的会话结束窗口生命周期，
// 用退出码汇报结果（0 通过，1 失败并带诊断到 stderr）。
//
// 之所以用子进程而不是在父进程里循环：StartSessionEndWindow 一个进程只建一个
// 窗口（进程级 sync.Once + 窗口类只注册一次），要反复跑完整生命周期只能靠多
// 进程；而且每个子进程走的都是生产代码的真实路径——Once、类注册、消息循环、
// 诊断输出全都不绕路，父进程还能原样收到 stderr。
func TestSessionEndWindowRoundChild(t *testing.T) {
	if os.Getenv("PPOE_SESSIONEND_ROUND") != "1" {
		t.Skip("round child, only spawned by the stress test")
	}
	hold := 1500 * time.Millisecond
	if v := strings.TrimSpace(os.Getenv("PPOE_SESSIONEND_ROUND_HOLD_MS")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			hold = time.Duration(n) * time.Millisecond
		}
	}
	fail := func(format string, args ...any) {
		fmt.Fprintf(os.Stderr, "ROUND FAIL: "+format+"\n", args...)
		os.Exit(1)
	}

	fired := make(chan struct{}, 1)
	StartSessionEndWindow(func() { fired <- struct{}{} })

	// 不额外等消息循环就绪：窗口一经 CreateWindowExW 返回就可被枚举到，而此时
	// 窗口线程可能还没进 GetMessage——这正是出问题时的形态。
	hwnd := findSessionEndWindow()
	if hwnd == 0 {
		fail("窗口始终没出现（消息循环线程没起来？）")
	}
	if !sessionEndWindowAlive(hwnd) {
		fail("窗口刚枚举到就已失效")
	}
	// WM_QUERYENDSESSION 必须答复"可以关"，否则重启管理器会放弃关闭本进程。
	if r, _, _ := procSendMessageW.Call(uintptr(hwnd), wmQueryEndSession, 0, 0); r == 0 {
		fail("WM_QUERYENDSESSION 未答复 TRUE")
	}
	procSendMessageW.Call(uintptr(hwnd), wmEndSession, 1, 0)
	if !sessionEndWindowAlive(hwnd) {
		fail("WM_ENDSESSION 一发完窗口就失效（窗口过程问题）")
	}
	select {
	case <-fired:
	case <-time.After(3 * time.Second):
		fail("回调 3 秒未触发（窗口存活=%v）", sessionEndWindowAlive(hwnd))
	}
	// 静置段：窗口继续存活，只轮询不碰它。消息循环线程退出、窗口被回收都发生
	// 在这一段里——原始故障正是这个形态。
	deadline := time.Now().Add(hold)
	for time.Now().Before(deadline) {
		if !sessionEndWindowAlive(hwnd) {
			fail("静置 %v 后窗口被回收（消息循环线程退出）", hold)
		}
		time.Sleep(20 * time.Millisecond)
	}
	os.Exit(0)
}

// ---------------------------------------------------------------------------
// 压测：父进程
// ---------------------------------------------------------------------------

// TestSessionEndWindowSurvivesUnderLoad 压测：外部进程满载 + 进程内超订的
// 负载下并行跑很多轮完整生命周期，复现「消息循环线程退出、Windows 回收它创建
// 的窗口」。
//
// 那意味着重启管理器找不到可关闭的窗口，卸载/升级又会卡在「收集信息」——正是
// StartSessionEndWindow 想修掉的问题。该故障至今只抓到过一次（冷构建 + 并行
// 编译把整机占满的那轮），之后几十次复跑未再现，所以这里把唯一已知的触发
// 条件（满载下的调度压力）固化下来，用轮数换捕捉概率。每轮的失败形态都会
// 原样带出来，消息循环退出时的诊断输出（GetMessageW 返回值、panic）也会随
// 子进程 stderr 一起到父进程。
//
// 默认跳过，显式开启：
//
//	$env:PPOE_SESSIONEND_STRESS=1
//	go test ./internal/platform -run TestSessionEndWindowSurvivesUnderLoad -count=1
//
// 之所以默认不开：它本身时序敏感，还会拉起 NumCPU+2 个烧 CPU 的子进程，进
// 默认门禁只会把 CI 弄红，违背「抓抖动」的初衷。
// 轮数/并发/静置可用 PPOE_SESSIONEND_STRESS_ROUNDS / _PARALLEL / _HOLD_MS 覆盖。
// -short 同样跳过。
func TestSessionEndWindowSurvivesUnderLoad(t *testing.T) {
	if testing.Short() {
		t.Skip("stress test, skipped in -short mode")
	}
	if strings.TrimSpace(os.Getenv("PPOE_SESSIONEND_STRESS")) != "1" {
		t.Skip("stress test, set PPOE_SESSIONEND_STRESS=1 to run")
	}
	rounds, parallel, holdMs := 40, 8, 1500
	for _, cfg := range []struct {
		env string
		dst *int
	}{
		{"PPOE_SESSIONEND_STRESS_ROUNDS", &rounds},
		{"PPOE_SESSIONEND_STRESS_PARALLEL", &parallel},
		{"PPOE_SESSIONEND_STRESS_HOLD_MS", &holdMs},
	} {
		if v := strings.TrimSpace(os.Getenv(cfg.env)); v != "" {
			n, err := strconv.Atoi(v)
			if err != nil || n <= 0 {
				t.Fatalf("%s=%q is not a positive integer", cfg.env, v)
			}
			*cfg.dst = n
		}
	}

	// 外部进程满载（主力负载）+ 进程内超订（补充）。
	defer startBurners(t)()
	defer burnUntil(runtime.GOMAXPROCS(0) + 2)()

	sem := make(chan struct{}, parallel)
	var wg sync.WaitGroup
	var mu sync.Mutex
	var failures []string
	for i := 0; i < rounds; i++ {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int) {
			defer wg.Done()
			defer func() { <-sem }()
			cmd := exec.Command(os.Args[0], "-test.run=^TestSessionEndWindowRoundChild$", "-test.timeout=90s")
			cmd.Env = append(os.Environ(),
				"PPOE_SESSIONEND_ROUND=1",
				fmt.Sprintf("PPOE_SESSIONEND_ROUND_HOLD_MS=%d", holdMs))
			cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
			var stderr bytes.Buffer
			cmd.Stderr = &stderr
			err := cmd.Run()
			if err != nil {
				mu.Lock()
				msg := strings.TrimSpace(stderr.String())
				if msg == "" {
					msg = err.Error()
				}
				failures = append(failures, fmt.Sprintf("round %d: %s", i, msg))
				mu.Unlock()
			}
		}(i)
	}
	wg.Wait()

	if len(failures) > 0 {
		t.Fatalf("%d/%d 轮失败：\n%s", len(failures), rounds, strings.Join(failures, "\n"))
	}
}
