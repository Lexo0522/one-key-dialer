package platform

import (
	"runtime"
	"syscall"
	"unsafe"
)

// SPI_GETWORKAREA：取主显示器工作区矩形（扣除任务栏与停靠窗口）。
const spiGetWorkArea = 0x0030

var (
	user32                    = syscall.NewLazyDLL("user32.dll")
	procSystemParametersInfoW = user32.NewProc("SystemParametersInfoW")
)

type winRect struct {
	Left, Top, Right, Bottom int32
}

// WorkArea 返回主显示器工作区的宽、高（已扣除任务栏）。
// 非 Windows 或调用失败时返回 (0, 0)，调用方应按“不限制”处理。
func WorkArea() (w, h int) {
	if runtime.GOOS != "windows" {
		return 0, 0
	}
	if err := procSystemParametersInfoW.Find(); err != nil {
		return 0, 0
	}
	var r winRect
	ret, _, _ := procSystemParametersInfoW.Call(
		uintptr(spiGetWorkArea),
		0,
		uintptr(unsafe.Pointer(&r)),
		0,
	)
	if ret == 0 {
		return 0, 0
	}
	return int(r.Right - r.Left), int(r.Bottom - r.Top)
}
