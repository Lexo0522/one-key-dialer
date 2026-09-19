package update

import (
	"archive/zip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/Lexo0522/one-key-dialer/internal/i18n"
	"github.com/Lexo0522/one-key-dialer/internal/model"
	"github.com/Lexo0522/one-key-dialer/internal/platform"
)

// Prepare 把已校验的安装包展开/生成更新脚本。
func (m *Module) Prepare(pkg *VerifiedPackage, progress Progress) (*PreparedUpdate, error) {
	if pkg == nil || pkg.File == "" {
		return nil, ErrNoPackageFile
	}
	if _, err := os.Stat(pkg.File); err != nil {
		return nil, ErrNoPackageFile
	}
	installDir := platform.InstallDir()
	staged := filepath.Join(m.updatesDir, StagedDirPrefix+strconv.FormatInt(time.Now().UnixMilli(), 10))
	if err := os.MkdirAll(staged, 0o755); err != nil {
		return nil, err
	}
	pid := os.Getpid()
	lower := strings.ToLower(pkg.File)
	switch {
	case strings.HasSuffix(lower, ".zip"):
		if progress != nil {
			progress.OnStatus(i18n.T("update.unzipping2"))
		}
		if err := unzip(pkg.File, staged, progress); err != nil {
			return nil, err
		}
		payloadRoot := findPayloadRoot(staged)
		script, err := m.writeZipApplyScript(installDir, payloadRoot, pid)
		if err != nil {
			return nil, err
		}
		return &PreparedUpdate{ApplyScript: script, Kind: "zip"}, nil
	case strings.HasSuffix(lower, ".msi"):
		script, err := m.writeMsiApplyScript(pkg.File, installDir, pid)
		if err != nil {
			return nil, err
		}
		return &PreparedUpdate{ApplyScript: script, Kind: "msi"}, nil
	case strings.HasSuffix(lower, ".exe"):
		script, err := m.writeExeApplyScript(pkg.File, installDir, pid)
		if err != nil {
			return nil, err
		}
		return &PreparedUpdate{ApplyScript: script, Kind: "exe"}, nil
	}
	return nil, errors.New(i18n.Tf("update.badType", filepath.Base(pkg.File)))
}

// LaunchInstall 以隐藏控制台的方式启动更新脚本；仅当确认启动成功才返回 true。
func (m *Module) LaunchInstall(prepared *PreparedUpdate) bool {
	if prepared == nil || prepared.ApplyScript == "" {
		return false
	}
	if _, err := os.Stat(prepared.ApplyScript); err != nil {
		return false
	}
	err := platform.LaunchDetached([]string{"cmd.exe", "/c", prepared.ApplyScript},
		filepath.Dir(prepared.ApplyScript))
	return err == nil
}

// unzip 解压 zip 到目标目录（含路径逃逸检查），每 256KB 上报一次进度。
func unzip(zipPath, dest string, progress Progress) error {
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer r.Close()

	var total int64
	for _, f := range r.File {
		if !f.FileInfo().IsDir() {
			total += int64(f.UncompressedSize64)
		}
	}
	var done, lastReport int64

	for _, f := range r.File {
		name := strings.ReplaceAll(f.Name, "\\", "/")
		out := filepath.Join(dest, filepath.FromSlash(name))
		if !strings.HasPrefix(filepath.Clean(out), filepath.Clean(dest)+string(os.PathSeparator)) &&
			filepath.Clean(out) != filepath.Clean(dest) {
			return errors.New(i18n.Tf("update.badZipPath", f.Name))
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(out, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
			return err
		}
		src, err := f.Open()
		if err != nil {
			return err
		}
		dst, err := os.OpenFile(out, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
		if err != nil {
			src.Close()
			return err
		}
		buf := make([]byte, CopyBufferBytes)
		for {
			n, rerr := src.Read(buf)
			if n > 0 {
				if _, werr := dst.Write(buf[:n]); werr != nil {
					src.Close()
					dst.Close()
					return werr
				}
				done += int64(n)
				if total > 0 && done-lastReport >= ProgressReportBytes {
					if progress != nil {
						progress.OnProgress(done, total)
					}
					lastReport = done
				}
			}
			if rerr == io.EOF {
				break
			}
			if rerr != nil {
				src.Close()
				dst.Close()
				return rerr
			}
		}
		src.Close()
		dst.Close()
	}
	if progress != nil && total > 0 {
		progress.OnProgress(total, total)
	}
	return nil
}

// findPayloadRoot 定位解压后的有效负载根目录。
func findPayloadRoot(staged string) string {
	entries, err := os.ReadDir(staged)
	if err != nil || len(entries) == 0 {
		return staged
	}
	dirCount := 0
	var onlyDir string
	for _, e := range entries {
		if e.IsDir() {
			dirCount++
			onlyDir = e.Name()
		}
	}
	if dirCount == 1 && len(entries) == 1 {
		return filepath.Join(staged, onlyDir)
	}
	for _, e := range entries {
		if e.IsDir() {
			candidate := filepath.Join(staged, e.Name())
			if _, err := os.Stat(filepath.Join(candidate, model.AppName)); err == nil {
				return candidate
			}
		}
	}
	if _, err := os.Stat(filepath.Join(staged, model.AppName)); err == nil {
		return staged
	}
	if onlyDir != "" {
		return filepath.Join(staged, onlyDir)
	}
	return staged
}

func (m *Module) writeApplyScript(body func(w *scriptWriter)) (string, error) {
	scriptPath := filepath.Join(m.updatesDir, ApplyScriptName)
	f, err := os.Create(scriptPath)
	if err != nil {
		return "", err
	}
	w := &scriptWriter{f: f}
	w.line("@echo off")
	w.line("chcp 65001 >nul")
	body(w)
	w.line("exit /b 0")
	if err := w.err(); err != nil {
		f.Close()
		return "", err
	}
	if err := f.Close(); err != nil {
		return "", err
	}
	platform.RestrictToOwner(scriptPath)
	return scriptPath, nil
}

func (m *Module) writeZipApplyScript(installDir, payloadRoot string, pid int) (string, error) {
	return m.writeApplyScript(func(w *scriptWriter) {
		writeWaitForAppExit(w, pid)
		wline(w, `set "SRC=`+payloadRoot+`"`)
		wline(w, `set "DST=`+installDir+`"`)
		wline(w, `if not exist "%SRC%\" (`)
		wline(w, "  echo Source missing")
		wline(w, "  pause")
		wline(w, "  exit /b 1")
		wline(w, ")")
		wline(w, `if not exist "%DST%\" (`)
		wline(w, "  echo Install dir missing: %DST%")
		wline(w, "  pause")
		wline(w, "  exit /b 1")
		wline(w, ")")
		wline(w, `rem Writability probe - a Program Files install must use the MSI`)
		wline(w, `copy /y nul "%DST%\`+WritabilityProbeFile+`" >nul 2>nul`)
		wline(w, "if errorlevel 1 (")
		wline(w, "  echo Install dir is not writable. Use the MSI package instead.")
		wline(w, "  pause")
		wline(w, "  exit /b 1")
		wline(w, ")")
		wline(w, `del "%DST%\`+WritabilityProbeFile+`" >nul 2>nul`)
		wline(w, `xcopy "%SRC%\*" "%DST%\" /E /Y /I /Q`)
		wline(w, "if errorlevel 1 (")
		wline(w, "  echo Copy failed")
		wline(w, "  pause")
		wline(w, "  exit /b 1")
		wline(w, ")")
		wline(w, `echo Applying PPoEDialer update...`)
		writeRelaunch(w, installDir)
		wline(w, "endlocal")
	})
}

func (m *Module) writeMsiApplyScript(msiPath, installDir string, pid int) (string, error) {
	return m.writeApplyScript(func(w *scriptWriter) {
		wline(w, "echo Installing MSI update...")
		writeWaitForAppExit(w, pid)
		wline(w, `msiexec /i "`+msiPath+`"`)
		wline(w, "if errorlevel 1 if not errorlevel 3010 goto msi_failed")
		wline(w, `if exist "`+filepath.Join(installDir, model.AppName)+`" (`)
		writeRelaunch(w, installDir)
		wline(w, ")")
		wline(w, "exit /b 0")
		wline(w, ":msi_failed")
		wline(w, "echo MSI install failed (exit code %errorlevel%). The previous version is unchanged.")
		writeRelaunch(w, installDir)
		wline(w, "echo.")
		wline(w, "pause")
		wline(w, "exit /b 1")
	})
}

func (m *Module) writeExeApplyScript(exePath, installDir string, pid int) (string, error) {
	return m.writeApplyScript(func(w *scriptWriter) {
		wline(w, "echo Launching installer...")
		writeWaitForAppExit(w, pid)
		wline(w, `start "" /D "`+installDir+`" "`+exePath+`"`)
	})
}

func writeWaitForAppExit(w *scriptWriter, pid int) {
	wline(w, fmt.Sprintf("rem Wait up to %ds for the running app to exit (PID %d)", WaitForExitLoopCount, pid))
	wline(w, fmt.Sprintf("for /L %%%%i in (1,1,%d) do (", WaitForExitLoopCount))
	wline(w, fmt.Sprintf(`  tasklist /FI "PID eq %d" 2>nul | find /I "%d" >nul 2>nul && timeout /t 1 /nobreak >nul`, pid, pid))
	wline(w, ")")
}

func writeRelaunch(w *scriptWriter, installDir string) {
	exe := filepath.Join(installDir, model.AppName)
	wline(w, `start "" /D "`+installDir+`" "`+exe+`"`)
}

func wline(w *scriptWriter, s string) { w.line(s) }

// scriptWriter 包装文件写入并记住首个错误。
type scriptWriter struct {
	f     io.Writer
	first error
}

func (w *scriptWriter) line(s string) {
	if w.first != nil {
		return
	}
	_, err := io.WriteString(w.f, s+"\r\n")
	if err != nil && w.first == nil {
		w.first = err
	}
}

func (w *scriptWriter) err() error { return w.first }
