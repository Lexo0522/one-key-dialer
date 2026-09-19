// Package model 承载与旧版 Java model 包等价的领域结构与纯函数。
package model

import (
	"strconv"
	"strings"
)

// version 为数字版本号（打包时可用 -ldflags 覆盖）。
var version = "1.1.11"

// SetVersion 由构建脚本通过 -ldflags 注入。
func SetVersion(v string) {
	if strings.TrimSpace(v) != "" {
		version = strings.TrimSpace(v)
	}
}

// Version 返回数字版本号，例如 1.1.11。
func Version() string { return version }

// Display 返回展示版本号，例如 v1.1.11。
func Display() string { return "v" + version }

// UserAgent 返回出站请求的 UA。
func UserAgent() string { return "PPoEDialer/" + version }

const (
	// GitHubRepo 更新检查用的仓库（owner/name）。
	GitHubRepo = "Lexo0522/one-key-dialer"
	// GitHubURL 发布页。
	GitHubURL = "https://github.com/" + GitHubRepo
	// ReleasesAPI GitHub latest release 接口。
	ReleasesAPI = "https://api.github.com/repos/" + GitHubRepo + "/releases/latest"
	// GiteeReleasesAPI Gitee 镜像 latest release 接口。
	GiteeReleasesAPI = "https://gitee.com/api/v5/repos/kate522/one-key-dialer/releases/latest"
	// AppName 主程序可执行文件名（更新脚本钉住此名）。
	AppName = "PPPoEDialer.exe"
	// ConnectionName 固定 RAS 连接名。
	ConnectionName = "pppoe_native_java"
)

// StripV 去掉可选的前导 v / V（仅当长度 ≥ 2 时）。
func StripV(v string) string {
	s := strings.TrimSpace(v)
	if len(s) >= 2 && (s[0] == 'v' || s[0] == 'V') {
		return s[1:]
	}
	return s
}

// CompareNumeric 比较点分数字版本号；a<b 返回负，相等返回 0，a>b 返回正。
// 只取每段前导数字，非数字开头或溢出按 0 计（与旧版一致）。
func CompareNumeric(a, b string) int {
	pa := strings.Split(StripV(a), ".")
	pb := strings.Split(StripV(b), ".")
	n := len(pa)
	if len(pb) > n {
		n = len(pb)
	}
	for i := 0; i < n; i++ {
		var ia, ib int
		if i < len(pa) {
			ia = parsePart(pa[i])
		}
		if i < len(pb) {
			ib = parsePart(pb[i])
		}
		if ia != ib {
			if ia < ib {
				return -1
			}
			return 1
		}
	}
	return 0
}

func parsePart(part string) int {
	if part == "" {
		return 0
	}
	end := 0
	for end < len(part) && part[end] >= '0' && part[end] <= '9' {
		end++
	}
	if end == 0 {
		return 0
	}
	v, err := strconv.Atoi(part[:end])
	if err != nil {
		return 0
	}
	return v
}
