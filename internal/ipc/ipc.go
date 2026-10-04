// Package ipc 提供代理进程与 UI 进程之间的命名管道通信。
//
// 架构:代理进程(纯 Go,托盘+拨号+全部服务)作为 Server 常驻;
// UI 进程(Wails 窗口)按需拉起,作为 Client 连接后把前端绑定方法
// 转发给代理,并把代理事件桥接进 Wails 事件总线。
//
// 协议:NDJSON(newline-delimited JSON),四类消息:
//
//	{"type":"hello","version":1,"pid":1234}          连接握手(UI→代理,首条)
//	{"type":"req","id":1,"method":"Bootstrap","params":[...]}
//	{"type":"res","id":1,"ok":true,"result":{...}}
//	{"type":"evt","event":"app:speed","payload":{...}}  代理→UI 单向广播
package ipc

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/Microsoft/go-winio"
)

// ProtocolVersion 协议版本;UI 与代理版本不一致时拒绝服务,避免字段错配。
const ProtocolVersion = 1

// PipePath 代理进程监听的规范命名管道路径。
const PipePath = `\\.\pipe\PPoEDialerAgent`

// NameFile 记录当前活动管道名的文件路径(为空则只用规范名)。
// 代理启动时若规范名被占用但确无存活代理(幽灵管道/句柄泄漏),
// 会改用备选名并把实际名字写入该文件;UI 侧优先读取。
// 由 main 在 ipc 使用前设置一次。
var NameFile string

// pipeNames 返回服务端可尝试的管道名(规范名优先,最多 3 个)。
// 环境变量 PPPOEDIALER_PIPE 可整体覆盖(测试/多实例隔离用):
// 代理与 UI 同环境变量启动即在同一管道上会合,完全绕开规范名。
// 覆盖值允许省略 \\.\pipe\ 前缀(裸名字自动补全)。
func pipeNames() []string {
	if override := strings.TrimSpace(os.Getenv("PPPOEDIALER_PIPE")); override != "" {
		if !strings.HasPrefix(override, `\\`) {
			override = `\\.\pipe\` + strings.TrimPrefix(override, `\`)
		}
		return []string{override}
	}
	return []string{PipePath, PipePath + ".2", PipePath + ".3"}
}

// hello / message 结构。
type message struct {
	Type    string          `json:"type"`
	ID      uint64          `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
	OK      bool            `json:"ok,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   string          `json:"error,omitempty"`
	Event   string          `json:"event,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
	Version int             `json:"version,omitempty"`
	PID     int             `json:"pid,omitempty"`
}

// maxMessageSize 单条消息上限(Bootstrap 会携带全部日志快照,留足余量)。
const maxMessageSize = 16 << 20

var ErrClosed = errors.New("ipc: connection closed")

// ---------------------------------------------------------------- server ----

// Handler 处理一次方法调用;返回值会被 JSON 序列化后回给客户端。
type Handler func(method string, params []json.RawMessage) (any, error)

// Server 命名管道服务端:多客户端、事件广播、断开自动清理。
type Server struct {
	handler Handler
	// activeName 实际监听的管道名(规范名或备选名)。
	activeName string

	mu      sync.Mutex
	ln      net.Listener
	clients map[*serverConn]struct{}
	onCount func(n int) // 连接数变化回调(供代理判断 UI 是否在线)
	closed  bool
}

type serverConn struct {
	conn    net.Conn
	writeMu sync.Mutex
	pid     int
}

// NewServer 创建并监听管道;返回 ErrAlreadyRunning 表示确有存活代理在监听。
// 规范名被占用但探测不到存活代理(幽灵管道/句柄泄漏,进程崩溃残留等)时,
// 依次尝试备选名并把实际名字写入 NameFile,供 UI 侧解析。
func NewServer(handler Handler, onCount func(int)) (*Server, error) {
	// SDDL 限制为:SYSTEM、Administrators、当前用户(交互式登录用户)可访问,
	// 其它会话的用户无法连接本用户的管道。
	sd := "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;IU)"
	cfg := &winio.PipeConfig{SecurityDescriptor: sd}

	var ln net.Listener
	chosen := ""
	for _, name := range pipeNames() {
		l, err := winio.ListenPipe(name, cfg)
		if err == nil {
			ln, chosen = l, name
			break
		}
		if !isPipeInUse(err) {
			return nil, err
		}
		// 名字被占用:区分「存活代理」与「幽灵管道」
		if LiveAgent(name) {
			return nil, ErrAlreadyRunning
		}
	}
	if ln == nil {
		return nil, errors.New("ipc: all pipe names occupied and no live agent")
	}
	s := &Server{handler: handler, activeName: chosen,
		clients: make(map[*serverConn]struct{}), onCount: onCount, ln: ln}
	s.recordName()
	go s.acceptLoop()
	return s, nil
}

// recordName 把实际监听的管道名写入 NameFile(供 UI 解析);失败不致命。
func (s *Server) recordName() {
	if NameFile == "" {
		return
	}
	if s.activeName == PipePath {
		// 规范名正常:清掉历史备选名记录
		os.Remove(NameFile)
		return
	}
	if err := os.WriteFile(NameFile, []byte(s.activeName), 0o600); err != nil {
		return
	}
}

// clearNameFile 移除活动名记录。
func clearNameFile() {
	if NameFile != "" {
		os.Remove(NameFile)
	}
}

// LiveAgent 探测某管道名后端是否有可应答的代理进程:
// 连接后发送握手,必须在限时内收到协议应答。
func LiveAgent(name string) bool {
	d := 800 * time.Millisecond
	conn, err := winio.DialPipe(name, &d)
	if err != nil {
		return false
	}
	defer conn.Close()
	return clientHandshake(bufio.NewReader(conn), conn, 2*time.Second) == nil
}

// ErrAlreadyRunning 已有代理实例在监听同一管道。
var ErrAlreadyRunning = errors.New("ipc: agent already running")

func isPipeInUse(err error) bool {
	if errors.Is(err, os.ErrPermission) {
		return true
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "busy") || strings.Contains(msg, "access is denied") ||
		strings.Contains(msg, "already exists")
}

func (s *Server) acceptLoop() {
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			s.mu.Lock()
			closed := s.closed
			s.mu.Unlock()
			if closed {
				return
			}
			continue
		}
		c := &serverConn{conn: conn}
		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			conn.Close()
			return
		}
		s.clients[c] = struct{}{}
		n := len(s.clients)
		s.mu.Unlock()
		s.notifyCount(n)
		go s.serveConn(c)
	}
}

func (s *Server) notifyCount(n int) {
	if s.onCount != nil {
		s.onCount(n)
	}
}

// serveConn 读取并分发一条客户端连接;hello 之前不接收请求。
func (s *Server) serveConn(c *serverConn) {
	defer func() {
		s.mu.Lock()
		delete(s.clients, c)
		n := len(s.clients)
		s.mu.Unlock()
		c.conn.Close()
		s.notifyCount(n)
	}()

	scanner := bufio.NewScanner(c.conn)
	scanner.Buffer(make([]byte, 64*1024), maxMessageSize)
	for scanner.Scan() {
		var msg message
		if err := json.Unmarshal(scanner.Bytes(), &msg); err != nil {
			continue // 坏行忽略,保持连接
		}
		switch msg.Type {
		case "hello":
			s.mu.Lock()
			c.pid = msg.PID
			s.mu.Unlock()
			// 握手应答,告知协议版本
			s.writeTo(c, message{Type: "hello", Version: ProtocolVersion})
		case "req":
			go s.handleReq(c, &msg)
		default:
			// UI→代理方向不该出现 res/evt,忽略
		}
	}
}

func (s *Server) handleReq(c *serverConn, msg *message) {
	var params []json.RawMessage
	if len(msg.Params) > 0 {
		if err := json.Unmarshal(msg.Params, &params); err != nil {
			s.writeTo(c, message{Type: "res", ID: msg.ID, Error: "bad params: " + err.Error()})
			return
		}
	}
	// handler 内的 panic 只影响本次调用:代理进程崩溃同样会留下幽灵托盘图标
	result, err := func() (result any, err error) {
		defer func() {
			if r := recover(); r != nil {
				err = fmt.Errorf("handler panic: %v", r)
			}
		}()
		return s.handler(msg.Method, params)
	}()
	res := message{Type: "res", ID: msg.ID, OK: err == nil}
	if err != nil {
		res.Error = err.Error()
	} else if result != nil {
		raw, merr := json.Marshal(result)
		if merr != nil {
			res.OK = false
			res.Error = merr.Error()
		} else {
			res.Result = raw
		}
	} else {
		res.Result = json.RawMessage("null")
	}
	s.writeTo(c, res)
}

func (s *Server) writeTo(c *serverConn, msg message) {
	raw, err := json.Marshal(msg)
	if err != nil {
		return
	}
	raw = append(raw, '\n')
	c.writeMu.Lock()
	_, werr := c.conn.Write(raw)
	c.writeMu.Unlock()
	if werr != nil {
		c.conn.Close() // 触发 serveConn 退出与清理
	}
}

// Broadcast 向所有已连接客户端广播事件;无客户端时直接返回(省流)。
func (s *Server) Broadcast(event string, payload any) {
	raw, err := json.Marshal(payload)
	if err != nil {
		raw = json.RawMessage("null")
	}
	msg := message{Type: "evt", Event: event, Payload: raw}
	s.mu.Lock()
	targets := make([]*serverConn, 0, len(s.clients))
	for c := range s.clients {
		targets = append(targets, c)
	}
	s.mu.Unlock()
	for _, c := range targets {
		s.writeTo(c, msg)
	}
}

// ClientCount 当前已连接(UI)客户端数量。
func (s *Server) ClientCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.clients)
}

// ClientPIDs 返回全部客户端进程 PID(供更新流程等待退出)。
func (s *Server) ClientPIDs() []int {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]int, 0, len(s.clients))
	for c := range s.clients {
		if c.pid > 0 {
			out = append(out, c.pid)
		}
	}
	return out
}

// Close 停止监听并断开全部客户端。
func (s *Server) Close() error {
	clearNameFile()
	s.mu.Lock()
	s.closed = true
	clients := make([]*serverConn, 0, len(s.clients))
	for c := range s.clients {
		clients = append(clients, c)
	}
	s.clients = make(map[*serverConn]struct{})
	s.mu.Unlock()
	for _, c := range clients {
		c.conn.Close()
	}
	return s.ln.Close()
}

// ---------------------------------------------------------------- client ----

// Client UI 进程侧客户端:Call 转发方法,OnEvent 接收代理广播。
type Client struct {
	conn    net.Conn
	br      *bufio.Reader
	nextID  uint64
	idMu    sync.Mutex
	pending map[uint64]chan message
	pendMu  sync.Mutex
	writeMu sync.Mutex

	OnEvent      func(event string, payload json.RawMessage)
	OnDisconnect func()

	mu        sync.Mutex
	closed    bool
	closeOnce sync.Once
}

// dialName 按超时连接指定管道名。
func dialName(name string, timeout time.Duration) (net.Conn, error) {
	d := timeout
	return winio.DialPipe(name, &d)
}

// resolvePipeName 解析客户端应尝试的管道名:NameFile 记录的优先,再依次试规范名与备选名。
func resolvePipeName() []string {
	var names []string
	if NameFile != "" {
		if raw, err := os.ReadFile(NameFile); err == nil {
			if name := strings.TrimSpace(string(raw)); name != "" {
				names = append(names, name)
			}
		}
	}
	names = append(names, pipeNames()...)
	// 去重保序
	seen := make(map[string]bool, len(names))
	out := names[:0]
	for _, n := range names {
		if !seen[n] {
			seen[n] = true
			out = append(out, n)
		}
	}
	return out
}

// Dial 连接代理;agent 未启动时按重试间隔等待,总时长不超过 timeout。
func Dial(timeout time.Duration) (*Client, error) {
	deadline := time.Now().Add(timeout)
	var lastErr error
	for {
		for _, name := range resolvePipeName() {
			conn, err := dialName(name, time.Second)
			if err == nil {
				return newClient(conn)
			}
			lastErr = err
		}
		if time.Now().After(deadline) {
			return nil, errors.New("ipc: dial agent: " + lastErr.Error())
		}
		time.Sleep(200 * time.Millisecond)
	}
}

// clientHandshake 发送握手并等待应答;对端必须在限时内确认协议版本。
// 借此区分「可应答的存活代理」与「接受连接但永不应答的幽灵管道」
// (句柄泄漏残留;真实机器上管道随进程消亡,此处是兜底防御)。
func clientHandshake(br *bufio.Reader, conn net.Conn, timeout time.Duration) error {
	conn.SetDeadline(time.Now().Add(timeout))
	defer conn.SetDeadline(time.Time{})
	hello, err := json.Marshal(message{Type: "hello", Version: ProtocolVersion, PID: os.Getpid()})
	if err != nil {
		return err
	}
	if _, err := conn.Write(append(hello, '\n')); err != nil {
		return err
	}
	line, err := br.ReadString('\n')
	if err != nil {
		return err
	}
	var reply message
	if err := json.Unmarshal([]byte(line), &reply); err != nil {
		return err
	}
	if reply.Type != "hello" {
		return errors.New("ipc: unexpected handshake reply")
	}
	if reply.Version != ProtocolVersion {
		return errors.New("ipc: protocol version mismatch")
	}
	return nil
}

func newClient(conn net.Conn) (*Client, error) {
	c := &Client{conn: conn, pending: make(map[uint64]chan message)}
	c.br = bufio.NewReader(conn)
	// 握手通过才开始读循环,保证连接背后是真正的代理进程
	if err := clientHandshake(c.br, conn, 3*time.Second); err != nil {
		conn.Close()
		return nil, err
	}
	go c.readLoop()
	return c, nil
}

func (c *Client) readLoop() {
	scanner := bufio.NewScanner(c.br)
	scanner.Buffer(make([]byte, 64*1024), maxMessageSize)
	for scanner.Scan() {
		var msg message
		if err := json.Unmarshal(scanner.Bytes(), &msg); err != nil {
			continue
		}
		switch msg.Type {
		case "evt":
			if c.OnEvent != nil {
				c.OnEvent(msg.Event, msg.Payload)
			}
		case "res", "hello":
			c.pendMu.Lock()
			ch := c.pending[msg.ID]
			c.pendMu.Unlock()
			if ch != nil {
				select {
				case ch <- msg:
				default:
				}
			}
		}
	}
	c.closeOnce.Do(func() { c.markClosed() })
	if c.OnDisconnect != nil {
		c.OnDisconnect()
	}
}

func (c *Client) markClosed() {
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
	c.pendMu.Lock()
	for id, ch := range c.pending {
		select {
		case ch <- message{Type: "res", ID: id, Error: ErrClosed.Error()}:
		default:
		}
	}
	c.pendMu.Unlock()
}

func (c *Client) allocID() uint64 {
	c.idMu.Lock()
	defer c.idMu.Unlock()
	c.nextID++
	return c.nextID
}

// Call 转发一次方法调用;args 为位置参数(与 Wails 绑定一致的参数顺序)。
func (c *Client) Call(method string, args ...any) (json.RawMessage, error) {
	return c.CallTimeout(defaultCallTimeout, method, args...)
}

// defaultCallTimeout 常规调用超时:同步探测/大状态回包最长可到数秒。
const defaultCallTimeout = 20 * time.Second

// CallTimeout 带显式超时的调用(退出类调用不等应答时用短超时)。
func (c *Client) CallTimeout(timeout time.Duration, method string, args ...any) (json.RawMessage, error) {
	c.mu.Lock()
	closed := c.closed
	c.mu.Unlock()
	if closed {
		return nil, ErrClosed
	}
	params := make([]json.RawMessage, 0, len(args))
	for _, a := range args {
		raw, err := json.Marshal(a)
		if err != nil {
			return nil, err
		}
		params = append(params, raw)
	}
	var paramsRaw json.RawMessage
	if len(params) > 0 {
		raw, err := json.Marshal(params)
		if err != nil {
			return nil, err
		}
		paramsRaw = raw
	}

	id := c.allocID()
	ch := make(chan message, 1)
	c.pendMu.Lock()
	c.pending[id] = ch
	c.pendMu.Unlock()
	defer func() {
		c.pendMu.Lock()
		delete(c.pending, id)
		c.pendMu.Unlock()
	}()

	req, err := json.Marshal(message{Type: "req", ID: id, Method: method, Params: paramsRaw})
	if err != nil {
		return nil, err
	}
	c.writeMu.Lock()
	_, werr := c.conn.Write(append(req, '\n'))
	c.writeMu.Unlock()
	if werr != nil {
		return nil, werr
	}

	select {
	case res := <-ch:
		if res.Error != "" {
			return nil, errors.New(res.Error)
		}
		return res.Result, nil
	case <-time.After(timeout):
		return nil, errors.New("ipc: call timeout: " + method)
	}
}

// Close 断开连接。
func (c *Client) Close() {
	c.closeOnce.Do(func() {
		c.mu.Lock()
		c.closed = true
		c.mu.Unlock()
		c.conn.Close()
	})
}

// Probe 探测代理管道是否可用。
func Probe() bool {
	for _, name := range resolvePipeName() {
		conn, err := dialName(name, 500*time.Millisecond)
		if err == nil {
			conn.Close()
			return true
		}
	}
	return false
}
