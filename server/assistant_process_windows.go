//go:build windows

package main

import (
	"context"
	"golang.org/x/sys/windows/registry"
	"os/exec"
	"strconv"
	"strings"
)

func refreshUserEnvironment(values map[string]string) {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Environment`, registry.QUERY_VALUE)
	if err != nil {
		return
	}
	defer k.Close()
	names, _ := k.ReadValueNames(-1)
	for _, name := range names {
		upper := strings.ToUpper(name)
		if strings.HasPrefix(upper, "WFY_") || upper == "CLAUDECODE" || upper == "CODEX_THREAD_ID" {
			continue
		}
		v, t, err := k.GetStringValue(name)
		if err != nil {
			continue
		}
		if t == registry.EXPAND_SZ {
			v, _ = registry.ExpandString(v)
		}
		if upper == "PATH" {
			values[upper] += ";" + v
		} else {
			values[upper] = v
		}
	}
}
func platformToolCommand(ctx context.Context, path string, args []string) *exec.Cmd {
	// Load the normal user profile too: many laptops configure provider variables there.
	// The prompt is piped to stdin, never inserted into this PowerShell program.
	line := "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); $OutputEncoding = [Console]::OutputEncoding; Remove-Item Env:CLAUDECODE,Env:CLAUDE_CODE_ENTRYPOINT,Env:CODEX_THREAD_ID -ErrorAction SilentlyContinue; & " + psQuote(path)
	for _, arg := range args {
		line += " " + psQuote(arg)
	}
	shell, err := exec.LookPath("pwsh.exe")
	if err != nil {
		shell = "powershell.exe"
	}
	return exec.CommandContext(ctx, shell, "-NoLogo", "-ExecutionPolicy", "Bypass", "-Command", line+"; exit $LASTEXITCODE")
}

func prepareToolProcess(cmd *exec.Cmd) {
	configureProcess(cmd)
	cmd.Cancel = func() error {
		if cmd.Process == nil {
			return nil
		}
		kill := exec.Command("taskkill.exe", "/PID", strconv.Itoa(cmd.Process.Pid), "/T", "/F")
		configureProcess(kill)
		_ = kill.Run()
		return cmd.Process.Kill()
	}
}
