package platform

import (
	"runtime"
	"testing"
	"unsafe"
)

// MIB_IFROW 布局若与系统不一致,GetIfTable 的行偏移解析就会错位,
// 这里锁定总长(856)与关键字段偏移(InOctets=552/OutOctets=556)。
func TestMibIfRowLayout(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("windows only")
	}
	if mibIfRowSize != 856 {
		t.Fatalf("MIB_IFROW size = %d, want 856", mibIfRowSize)
	}
	row := mibIfRow{}
	if off := unsafe.Offsetof(row.InOctets); off != 552 {
		t.Fatalf("InOctets offset = %d, want 552", off)
	}
	if off := unsafe.Offsetof(row.OutOctets); off != 556 {
		t.Fatalf("OutOctets offset = %d, want 556", off)
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

// 回环地址必须可达(不依赖外部网络)。
func TestIcmpReachableLoopback(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("windows only")
	}
	if !IcmpReachable("127.0.0.1", 1000) {
		t.Fatal("127.0.0.1 should be reachable")
	}
}
