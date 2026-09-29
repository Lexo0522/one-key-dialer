package platform

import (
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"unsafe"
)

// MIB_IFROW 布局若与系统不一致,GetIfTable 的行偏移解析就会错位,
// 这里锁定总长(860)与关键字段偏移(InOctets=552/OutOctets=576)。
// InOctets 之后还有 5 个入向包计数器,OutOctets 并不紧随其后:
// 曾按 netstat -e 的列序错排成 OutOctets=556,读到的实为 InUcastPkts
// (Win10+ 上常为 0),导致上行速度恒为 0。
func TestMibIfRowLayout(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("windows only")
	}
	if mibIfRowSize != 860 {
		t.Fatalf("MIB_IFROW size = %d, want 860", mibIfRowSize)
	}
	row := mibIfRow{}
	if off := unsafe.Offsetof(row.InOctets); off != 552 {
		t.Fatalf("InOctets offset = %d, want 552", off)
	}
	if off := unsafe.Offsetof(row.OutOctets); off != 576 {
		t.Fatalf("OutOctets offset = %d, want 576", off)
	}
}

// HostTrafficCounters 必须能读到非零计数器(本机只要有网卡即成立)。
func TestHostTrafficCounters(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("windows only")
	}
	recv, sent, ok := HostTrafficCounters()
	if !ok {
		t.Fatal("HostTrafficCounters failed")
	}
	t.Logf("recv=%d sent=%d", recv, sent)
}

// 端到端对照:HostTrafficCounters 与 netstat -e 同源于 GetIfTable,
// 字节聚合理应一致。曾因字段错排导致 sent 恒为 0,这里防回归。
// 两次采样间隔数毫秒,允许少量漂移。
func TestHostTrafficCountersMatchesNetstat(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("windows only")
	}
	if testing.Short() {
		t.Skip("short mode")
	}
	out, err := exec.Command("netstat", "-e").Output()
	if err != nil {
		t.Fatalf("netstat -e failed: %v", err)
	}
	nsRecv, nsSent, ok := parseNetstatBytes(string(out))
	if !ok {
		t.Fatalf("cannot parse netstat output:\n%s", out)
	}
	recv, sent, ok := HostTrafficCounters()
	if !ok {
		t.Fatal("HostTrafficCounters failed")
	}
	t.Logf("netstat recv=%d sent=%d | api recv=%d sent=%d", nsRecv, nsSent, recv, sent)
	// 容忍两类偏差:采样间隙内的正常流量漂移(取 2% 与 4MiB 中的较大者),
	// 以及 32 位计数器的 4GiB 回绕——netstat 按 32 位回绕显示,本实现
	// 用 int64 累加得到真实总量,比对前需按回绕归一。
	tol := func(v int64) int64 {
		slack := v / 50
		if slack < 4<<20 {
			slack = 4 << 20
		}
		return slack
	}
	const wrap = int64(1) << 32
	norm := func(d int64) int64 {
		d %= wrap
		if d > wrap/2 {
			d -= wrap
		}
		if d < -wrap/2 {
			d += wrap
		}
		return d
	}
	if d := norm(recv - nsRecv); d < -tol(nsRecv) || d > tol(nsRecv) {
		t.Errorf("recv mismatch: api=%d netstat=%d", recv, nsRecv)
	}
	if d := norm(sent - nsSent); d < -tol(nsSent) || d > tol(nsSent) {
		t.Errorf("sent mismatch: api=%d netstat=%d", sent, nsSent)
	}
}

// parseNetstatBytes 从 netstat -e 输出取首个含两个整数的行(即 Bytes 行,
// 标签随系统语言变化,不能按关键词匹配)。
func parseNetstatBytes(out string) (recv, sent int64, ok bool) {
	for _, line := range strings.Split(out, "\n") {
		fields := strings.Fields(line)
		nums := make([]int64, 0, 2)
		for _, f := range fields {
			if v, err := strconv.ParseInt(f, 10, 64); err == nil {
				nums = append(nums, v)
			}
		}
		if len(nums) == 2 {
			return nums[0], nums[1], true
		}
	}
	return 0, 0, false
}

// 回环地址必须可达(不依赖外部网络)。
func TestIcmpReachableLoopback(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("windows only")
	}
	if !IcmpReachable("127.0.0.1", 1000) {
		t.Fatal("127.0.0.1 should be reachable")
	}
}
