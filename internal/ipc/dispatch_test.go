package ipc

import (
	"encoding/json"
	"strings"
	"testing"
)

// dispatcherFixture 覆盖 Call 的各类分支：正常、多返回值、变参、参数不匹配。
type dispatcherFixture struct{}

func (dispatcherFixture) Echo(s string) string { return s }

func (dispatcherFixture) Add(a, b int) int { return a + b }

func (dispatcherFixture) NoReturn() {}

func (dispatcherFixture) TwoReturns() (string, error) { return "", nil }

func (dispatcherFixture) Variadic(parts ...string) string { return strings.Join(parts, ",") }

func raw(t *testing.T, v any) json.RawMessage {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return b
}

// TestDispatcherCallErrors 反射调用的 4 类错误路径。
// 这是 UI→代理全部调用的入口，静默失败会让调用方只看到"超时"而毫无线索。
func TestDispatcherCallErrors(t *testing.T) {
	d := NewDispatcher(dispatcherFixture{})

	t.Run("未知方法", func(t *testing.T) {
		_, err := d.Call("Nope", nil)
		if err == nil || !strings.Contains(err.Error(), "unknown method") {
			t.Fatalf("err = %v, want unknown method", err)
		}
	})

	t.Run("参数个数不匹配", func(t *testing.T) {
		_, err := d.Call("Add", []json.RawMessage{raw(t, 1)})
		if err == nil || !strings.Contains(err.Error(), "wants 2 args, got 1") {
			t.Fatalf("err = %v, want arg count mismatch", err)
		}
	})

	t.Run("参数 JSON 解码失败", func(t *testing.T) {
		_, err := d.Call("Add", []json.RawMessage{raw(t, 1), json.RawMessage(`"notanumber"`)})
		if err == nil || !strings.Contains(err.Error(), "arg 1") {
			t.Fatalf("err = %v, want arg decode error", err)
		}
	})

	t.Run("多返回值被拒", func(t *testing.T) {
		_, err := d.Call("TwoReturns", nil)
		if err == nil || !strings.Contains(err.Error(), "multiple return values") {
			t.Fatalf("err = %v, want multiple-return rejection", err)
		}
	})

	t.Run("变参被拒", func(t *testing.T) {
		_, err := d.Call("Variadic", nil)
		if err == nil || !strings.Contains(err.Error(), "variadic") {
			t.Fatalf("err = %v, want variadic rejection", err)
		}
	})
}

// TestDispatcherCallSuccess 成功路径的参数解码与返回值传递。
func TestDispatcherCallSuccess(t *testing.T) {
	d := NewDispatcher(dispatcherFixture{})

	got, err := d.Call("Add", []json.RawMessage{raw(t, 2), raw(t, 3)})
	if err != nil {
		t.Fatalf("Add: %v", err)
	}
	if got != 5 {
		t.Errorf("Add(2,3) = %v, want 5", got)
	}

	// void 方法返回 nil 值而非 error。
	got, err = d.Call("NoReturn", nil)
	if err != nil || got != nil {
		t.Errorf("NoReturn() = (%v, %v), want (nil, nil)", got, err)
	}

	got, err = d.Call("Echo", []json.RawMessage{raw(t, "hi")})
	if err != nil {
		t.Fatalf("Echo: %v", err)
	}
	if got != "hi" {
		t.Errorf("Echo(hi) = %v, want hi", got)
	}
}
