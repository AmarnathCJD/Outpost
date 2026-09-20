//go:build linux

package main

import (
	"os"
	"path/filepath"

	"golang.org/x/sys/unix"
)

// Directory descriptors retain os.Root's jail; RENAME_NOREPLACE closes the
// exists-check/rename race when a terminal creates the destination concurrently.
func (a *API) renameNoReplace(source, target string) error {
	from, err := a.disk.Open(filepath.Dir(source))
	if err != nil {
		return err
	}
	defer from.Close()
	to, err := a.disk.Open(filepath.Dir(target))
	if err != nil {
		return err
	}
	defer to.Close()
	if err = unix.Renameat2(int(from.Fd()), filepath.Base(source), int(to.Fd()), filepath.Base(target), unix.RENAME_NOREPLACE); err != nil {
		return &os.LinkError{Op: "rename", Old: source, New: target, Err: err}
	}
	return nil
}
