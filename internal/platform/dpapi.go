package platform

import (
	"runtime"
	"syscall"
	"unsafe"
)

// DATA_BLOB（crypt32）
type dataBlob struct {
	cbData uint32
	pbData *byte
}

var (
	crypt32                = syscall.NewLazyDLL("crypt32.dll")
	procCryptProtectData   = crypt32.NewProc("CryptProtectData")
	procCryptUnprotectData = crypt32.NewProc("CryptUnprotectData")
	kernel32               = syscall.NewLazyDLL("kernel32.dll")
	procLocalFree          = kernel32.NewProc("LocalFree")
	procRtlZeroMemory      = kernel32.NewProc("RtlSecureZeroMemory")
)

const cryptProtectUIForbidden = 0x1

// DPAPIAvailable 指示本机 DPAPI 是否可用。
func DPAPIAvailable() bool {
	if runtime.GOOS != "windows" {
		return false
	}
	return procCryptProtectData.Find() == nil && procCryptUnprotectData.Find() == nil
}

// DPAPIProtect 用当前用户的 DPAPI（CurrentUser）加密；不可用返回 nil。
func DPAPIProtect(plain []byte) []byte {
	if !DPAPIAvailable() || len(plain) == 0 {
		return nil
	}
	in := dataBlob{cbData: uint32(len(plain))}
	if len(plain) > 0 {
		in.pbData = &plain[0]
	}
	var out dataBlob
	ret, _, _ := procCryptProtectData.Call(
		uintptr(unsafe.Pointer(&in)),
		0, 0, 0, 0,
		uintptr(cryptProtectUIForbidden),
		uintptr(unsafe.Pointer(&out)),
	)
	if ret == 0 || out.cbData == 0 || out.pbData == nil {
		return nil
	}
	defer localFree(out.pbData)
	buf := make([]byte, out.cbData)
	copy(buf, unsafe.Slice(out.pbData, int(out.cbData)))
	return buf
}

// DPAPIUnprotect 用当前用户的 DPAPI 解密；失败返回 nil。
func DPAPIUnprotect(protected []byte) []byte {
	if !DPAPIAvailable() || len(protected) == 0 {
		return nil
	}
	in := dataBlob{cbData: uint32(len(protected)), pbData: &protected[0]}
	var out dataBlob
	ret, _, _ := procCryptUnprotectData.Call(
		uintptr(unsafe.Pointer(&in)),
		0, 0, 0, 0,
		uintptr(cryptProtectUIForbidden),
		uintptr(unsafe.Pointer(&out)),
	)
	if ret == 0 || out.cbData == 0 || out.pbData == nil {
		return nil
	}
	defer func() {
		zeroMemory(out.pbData, uintptr(out.cbData))
		localFree(out.pbData)
	}()
	buf := make([]byte, out.cbData)
	copy(buf, unsafe.Slice(out.pbData, int(out.cbData)))
	return buf
}

func localFree(p *byte) {
	if p != nil {
		_, _, _ = procLocalFree.Call(uintptr(unsafe.Pointer(p)))
	}
}

func zeroMemory(p *byte, size uintptr) {
	if p == nil || size == 0 {
		return
	}
	if procRtlZeroMemory.Find() == nil {
		_, _, _ = procRtlZeroMemory.Call(uintptr(unsafe.Pointer(p)), size)
		return
	}
	for i := uintptr(0); i < size; i++ {
		*(*byte)(unsafe.Pointer(uintptr(unsafe.Pointer(p)) + i)) = 0
	}
}
