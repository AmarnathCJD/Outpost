//go:build !linux && !windows

package main

import (
	"errors"
	"io/fs"
)

// Non-Linux builds are for local development. Callers serialize file mutations.
func (a *API) renameNoReplace(source, target string) error {
	if _, err := a.disk.Lstat(target); err == nil {
		return fs.ErrExist
	} else if !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return a.disk.Rename(source, target)
}
