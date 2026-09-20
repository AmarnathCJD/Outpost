//go:build !windows

package main

import (
	"context"
	"os/exec"
	"syscall"
)

func refreshUserEnvironment(values map[string]string) {}
func platformToolCommand(ctx context.Context, path string, args []string) *exec.Cmd {
	return exec.CommandContext(ctx, path, args...)
}
func prepareToolProcess(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error {
		if cmd.Process == nil {
			return nil
		}
		return syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
	}
}
