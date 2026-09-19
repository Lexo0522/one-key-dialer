package util

import (
	"os"
	"path/filepath"
)

// WriteAtomicUTF8 原子写入 UTF-8 文本（写临时文件后替换）。
func WriteAtomicUTF8(target, content string) error {
	return WriteAtomic(target, []byte(content))
}

// WriteAtomic 先写同目录临时文件再原子替换，避免截断的配置文件。
func WriteAtomic(target string, data []byte) error {
	dir := filepath.Dir(target)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, filepath.Base(target)+".*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() {
		if tmpName != "" {
			_ = os.Remove(tmpName)
		}
	}()

	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}

	if err := os.Rename(tmpName, target); err != nil {
		// Windows 上目标被占用时回退为覆盖复制
		if cerr := os.Remove(target); cerr == nil {
			if rerr := os.Rename(tmpName, target); rerr == nil {
				tmpName = ""
				return nil
			}
		}
		return err
	}
	tmpName = ""
	return nil
}
