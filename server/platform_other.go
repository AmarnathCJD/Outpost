//go:build !windows

package main

import "os/exec"

type platformState struct{}

func newPlatformState() *platformState { return &platformState{} }
func (*platformState) close()          {}
func configureProcess(cmd *exec.Cmd)   {}
func platformShellName() string        { return "Bash" }
