//go:build windows

package service

import "os/exec"

func lookPath(name string) (string, error) { return exec.LookPath(name) }
