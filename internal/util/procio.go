package util

import (
	"bufio"
	"bytes"
	"context"
	"io"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

// DefaultMaxOutputChars 限制子进程输出，避免异常子进程耗尽内存。
const DefaultMaxOutputChars = 64 * 1024

// ProcResult 一次有界子进程执行的结果。
type ProcResult struct {
	ExitCode int
	Output   string
	TimedOut bool
}

// RunProcess 在真实 deadline 下执行子进程，并并发排空其合并输出。
func RunProcess(command []string, timeout time.Duration, lineConsumer func(string)) (*ProcResult, error) {
	if len(command) == 0 {
		return nil, context.DeadlineExceeded
	}
	cmd := exec.Command(command[0], command[1:]...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		return nil, err
	}

	var (
		mu     sync.Mutex
		output strings.Builder
	)
	done := make(chan struct{})
	go func() {
		defer close(done)
		dec := newDecoder(stdout)
		r := bufio.NewReader(dec)
		for {
			line, rerr := r.ReadString('\n')
			line = strings.TrimRight(line, "\r\n")
			if line != "" {
				mu.Lock()
				if output.Len() < DefaultMaxOutputChars {
					remain := DefaultMaxOutputChars - output.Len()
					if len(line) > remain {
						line = line[:remain]
					}
					output.WriteString(line)
					if output.Len() < DefaultMaxOutputChars {
						output.WriteByte('\n')
					}
				}
				mu.Unlock()
				if lineConsumer != nil {
					func() {
						defer func() { _ = recover() }()
						lineConsumer(line)
					}()
				}
			}
			if rerr != nil {
				return
			}
		}
	}()

	timedOut := false
	exitCode := 0
	if err := waitTimeout(cmd, timeout); err != nil {
		if _, ok := err.(timeoutError); ok {
			timedOut = true
			_ = killTree(cmd)
			exitCode = -1
		} else {
			_ = killTree(cmd)
			<-done
			return nil, err
		}
	} else {
		exitCode = cmd.ProcessState.ExitCode()
	}
	<-done

	mu.Lock()
	out := output.String()
	mu.Unlock()
	return &ProcResult{ExitCode: exitCode, Output: out, TimedOut: timedOut}, nil
}

type timeoutError struct{}

func (timeoutError) Error() string { return "process timeout" }

func waitTimeout(cmd *exec.Cmd, timeout time.Duration) error {
	ch := make(chan error, 1)
	go func() { ch <- cmd.Wait() }()
	select {
	case err := <-ch:
		return err
	case <-time.After(timeout):
		return timeoutError{}
	}
}

func killTree(cmd *exec.Cmd) error {
	if cmd.Process == nil {
		return nil
	}
	return cmd.Process.Kill()
}

// newDecoder 按 Windows ANSI 代码页解码子进程输出（中文 Windows 为 GBK）。
func newDecoder(r io.Reader) io.Reader {
	if cp := ansiCodePage(); cp == 936 {
		return transform.NewReader(r, simplifiedchinese.GBK.NewDecoder())
	}
	return r
}

// DecodeBytes 按系统 ANSI 代码页解码字节。
func DecodeBytes(b []byte) string {
	if cp := ansiCodePage(); cp == 936 {
		out, _, err := transform.Bytes(simplifiedchinese.GBK.NewDecoder(), b)
		if err == nil {
			return string(out)
		}
	}
	return string(bytes.Trim(b, "\x00"))
}
