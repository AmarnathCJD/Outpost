//go:build !linux && !windows

package main

import "net/http"

func (a *API) terminal(w http.ResponseWriter, r *http.Request) {
	http.Error(w, "Persistent terminals require the Linux deployment", http.StatusNotImplemented)
}
