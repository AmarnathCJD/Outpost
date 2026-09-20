//go:build windows

package main

import (
	"golang.org/x/sys/windows"
	"path/filepath"
)

func (a *API) renameNoReplace(source, target string) error {
	if err := a.safeParents(source); err != nil {
		return err
	}
	if err := a.safeParents(target); err != nil {
		return err
	}
	from, err := windows.UTF16PtrFromString(filepath.Join(a.root, source))
	if err != nil {
		return err
	}
	to, err := windows.UTF16PtrFromString(filepath.Join(a.root, target))
	if err != nil {
		return err
	}
	// Omitting MOVEFILE_REPLACE_EXISTING makes destination collision refusal atomic.
	return windows.MoveFileEx(from, to, windows.MOVEFILE_WRITE_THROUGH)
}
