package ipc

import (
	"encoding/json"
	"errors"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// TestMain 把管道重定向到测试专用名,测试永不占用/污染规范管道
// (也避免与开发机上残留的常驻代理冲突)。
func TestMain(m *testing.M) {
	os.Setenv("PPPOEDIALER_PIPE", `\\.\pipe\PPoEDialerIpcTest`)
	os.Exit(m.Run())
}

// 端到端:Server 处理请求 + Client 调用 + 事件广播 + 断开清理。
func TestServerClientEndToEnd(t *testing.T) {
	var calls atomic.Int32
	handler := func(method string, params []json.RawMessage) (any, error) {
		calls.Add(1)
		switch method {
		case "Add":
			var nums []int
			for _, p := range params {
				var n int
				if err := json.Unmarshal(p, &n); err != nil {
					return nil, err
				}
				nums = append(nums, n)
			}
			return nums[0] + nums[1], nil
		case "Fail":
			return nil, errors.New("boom")
		case "NoResult":
			return nil, nil
		}
		return nil, errors.New("unknown method " + method)
	}

	srv, err := NewServer(handler, nil)
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	defer srv.Close()
	time.Sleep(100 * time.Millisecond) // 等监听就绪

	cli, err := Dial(2 * time.Second)
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}
	defer cli.Close()

	// 带参数调用
	raw, err := cli.Call("Add", 2, 3)
	if err != nil {
		t.Fatalf("Call Add: %v", err)
	}
	var sum int
	if err := json.Unmarshal(raw, &sum); err != nil || sum != 5 {
		t.Fatalf("Add result = %v err=%v, want 5", sum, err)
	}

	// 错误传播
	if _, err := cli.Call("Fail"); err == nil || err.Error() != "boom" {
		t.Fatalf("Fail err = %v, want boom", err)
	}

	// 无返回值调用
	if raw, err := cli.Call("NoResult"); err != nil || string(raw) != "null" {
		t.Fatalf("NoResult = %s, %v", raw, err)
	}

	// 事件广播
	var got sync.WaitGroup
	got.Add(1)
	var payload json.RawMessage
	cli.OnEvent = func(event string, p json.RawMessage) {
		if event == "app:test" {
			payload = p
			got.Done()
		}
	}
	srv.Broadcast("app:test", map[string]int{"down": 1024})
	waitTimeout(&got, 2*time.Second)
	if len(payload) == 0 {
		t.Fatal("event payload not received")
	}
	if !srv.ClientPIDsKnown() { // PID 随 hello 登记
		t.Fatal("client pid unknown")
	}
	if n := srv.ClientCount(); n != 1 {
		t.Fatalf("ClientCount = %d, want 1", n)
	}

	// 客户端关闭后服务端计数归零
	cli.Close()
	deadline := time.Now().Add(2 * time.Second)
	for srv.ClientCount() != 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if srv.ClientCount() != 0 {
		t.Fatal("client count did not drop to 0")
	}
}

func waitTimeout(wg *sync.WaitGroup, d time.Duration) {
	ch := make(chan struct{})
	go func() { wg.Wait(); close(ch) }()
	select {
	case <-ch:
	case <-time.After(d):
	}
}

// ClientPIDsKnown 仅为测试可读性封装。
func (s *Server) ClientPIDsKnown() bool {
	return len(s.ClientPIDs()) == 1
}
