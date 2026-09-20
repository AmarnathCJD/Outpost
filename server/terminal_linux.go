//go:build linux

package main

import (
	"encoding/json"
	"net/http"
	"os/exec"
	"sync"
	"time"

	"github.com/creack/pty"
	"github.com/gorilla/websocket"
)

func (a *API) terminal(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validSession(id) {
		http.Error(w, "invalid session", 400)
		return
	}
	// No shell interpolation: tmux target is validated and selected exactly.
	cmd := exec.Command("tmux", "attach-session", "-t", "="+id)
	term, err := pty.StartWithSize(cmd, &pty.Winsize{Rows: 30, Cols: 100})
	if err != nil {
		http.Error(w, "terminal unavailable", 500)
		return
	}
	defer func() {
		_ = term.Close()
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		_ = cmd.Wait()
	}()
	up := websocket.Upgrader{ReadBufferSize: 4096, WriteBufferSize: 4096, CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" }}
	ws, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer ws.Close()
	ws.SetReadLimit(64 << 10)
	var writeMu sync.Mutex
	done := make(chan struct{})
	defer close(done)
	go func() {
		buf := make([]byte, 8192)
		for {
			n, err := term.Read(buf)
			if n > 0 {
				writeMu.Lock()
				_ = ws.SetWriteDeadline(time.Now().Add(15 * time.Second))
				e := ws.WriteMessage(websocket.BinaryMessage, buf[:n])
				writeMu.Unlock()
				if e != nil {
					return
				}
			}
			if err != nil {
				_ = ws.Close()
				return
			}
		}
	}()
	go func() {
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				writeMu.Lock()
				err := ws.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second))
				writeMu.Unlock()
				if err != nil {
					_ = ws.Close()
					return
				}
			}
		}
	}()
	for {
		kind, b, err := ws.ReadMessage()
		if err != nil {
			return
		}
		if kind == websocket.BinaryMessage {
			if _, err = term.Write(b); err != nil {
				return
			}
		} else if kind == websocket.TextMessage {
			var msg struct {
				Cols uint16 `json:"cols"`
				Rows uint16 `json:"rows"`
			}
			if json.Unmarshal(b, &msg) == nil && msg.Cols >= 10 && msg.Cols <= 500 && msg.Rows >= 2 && msg.Rows <= 300 {
				_ = pty.Setsize(term, &pty.Winsize{Cols: msg.Cols, Rows: msg.Rows})
			}
		}
	}
}
