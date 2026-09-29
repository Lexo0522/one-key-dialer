package ipc

import (
	"encoding/json"
	"fmt"
	"reflect"
)

// Dispatcher 把一个 Go 对象的全部导出方法暴露为 IPC Handler,
// 参数按位置以 JSON 解码(与 Wails 绑定的参数顺序一致)。
// 代理进程用它直接暴露 *App,无需手写方法表。
type Dispatcher struct {
	methods map[string]reflect.Value
}

// NewDispatcher 建立方法表;obj 必须为指针(取指针方法集)。
func NewDispatcher(obj any) *Dispatcher {
	v := reflect.ValueOf(obj)
	t := v.Type()
	d := &Dispatcher{methods: make(map[string]reflect.Value, t.NumMethod())}
	for i := 0; i < t.NumMethod(); i++ {
		m := t.Method(i)
		d.methods[m.Name] = v.Method(i)
	}
	return d
}

// Handler 返回可用作 ipc.Server 的处理函数。
func (d *Dispatcher) Handler() Handler {
	return d.Call
}

// Call 反射调用一个方法:params 为按位置排列的 JSON 参数。
func (d *Dispatcher) Call(method string, params []json.RawMessage) (any, error) {
	m, ok := d.methods[method]
	if !ok {
		return nil, fmt.Errorf("unknown method: %s", method)
	}
	mt := m.Type()
	if mt.IsVariadic() {
		return nil, fmt.Errorf("method %s: variadic unsupported", method)
	}
	if mt.NumIn() != len(params) {
		return nil, fmt.Errorf("method %s wants %d args, got %d", method, mt.NumIn(), len(params))
	}
	in := make([]reflect.Value, len(params))
	for i, raw := range params {
		arg := reflect.New(mt.In(i))
		if err := json.Unmarshal(raw, arg.Interface()); err != nil {
			return nil, fmt.Errorf("method %s arg %d: %w", method, i, err)
		}
		in[i] = arg.Elem()
	}
	out := m.Call(in)
	switch len(out) {
	case 0:
		return nil, nil
	case 1:
		return out[0].Interface(), nil
	default:
		return nil, fmt.Errorf("method %s: multiple return values unsupported", method)
	}
}
