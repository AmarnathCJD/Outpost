//go:build windows

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/UserExistsError/conpty"
	"github.com/gorilla/websocket"
)

type platformState struct {
	mu       sync.Mutex
	sessions map[string]*nativeSession
	closed   bool
}
type nativeSession struct {
	id, title, project string
	created            int64
	pty                *conpty.ConPty
	mu                 sync.Mutex
	ioMu               sync.Mutex
	history            []byte
	listeners          map[chan []byte]bool
	running            atomic.Bool
	once               sync.Once
	cleanup            func()
}

func newPlatformState() *platformState { return &platformState{sessions: map[string]*nativeSession{}} }
func configureProcess(cmd *exec.Cmd)   { cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true} }
func platformShellName() string        { return "PowerShell" }
func (p *platformState) close() {
	p.mu.Lock()
	p.closed = true
	items := make([]*nativeSession, 0, len(p.sessions))
	for _, s := range p.sessions {
		items = append(items, s)
	}
	p.mu.Unlock()
	for _, s := range items {
		s.stop()
	}
}
func (s *nativeSession) stop() {
	s.once.Do(func() {
		s.running.Store(false)
		// Keep the reader draining output while ClosePseudoConsole terminates the console.
		_ = s.pty.Close()
		if s.cleanup != nil {
			s.cleanup()
		}
		s.mu.Lock()
		for ch := range s.listeners {
			close(ch)
			delete(s.listeners, ch)
		}
		s.mu.Unlock()
	})
}
func (s *nativeSession) collect() {
	buf := make([]byte, 8192)
	for {
		n, err := s.pty.Read(buf)
		if n > 0 {
			chunk := append([]byte(nil), buf[:n]...)
			s.mu.Lock()
			s.history = append(s.history, chunk...)
			if len(s.history) > 1<<20 {
				s.history = append([]byte(nil), s.history[len(s.history)-(1<<20):]...)
			}
			for ch := range s.listeners {
				select {
				case ch <- chunk:
				default:
					close(ch)
					delete(s.listeners, ch)
				}
			}
			s.mu.Unlock()
		}
		if err != nil {
			return
		}
	}
}
func (s *nativeSession) subscribe() ([]byte, chan []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	ch := make(chan []byte, 128)
	if s.running.Load() {
		s.listeners[ch] = true
	} else {
		close(ch)
	}
	return append([]byte(nil), s.history...), ch
}
func psQuote(text string) string { return "'" + strings.ReplaceAll(text, "'", "''") + "'" }
func (a *API) nativeEnvironment() []string {
	values := map[string]string{}
	for _, item := range a.toolEnvironment() {
		if i := strings.IndexByte(item, '='); i > 0 {
			values[strings.ToUpper(item[:i])] = item[i+1:]
		}
	}
	values["TERM"] = "xterm-256color"
	values["COLORTERM"] = "truecolor"
	settings := a.config()
	for key, value := range settings.Env {
		values[strings.ToUpper(key)] = value
	}
	if settings.GitName != "" {
		values["GIT_AUTHOR_NAME"] = settings.GitName
		values["GIT_COMMITTER_NAME"] = settings.GitName
	}
	if settings.GitEmail != "" {
		values["GIT_AUTHOR_EMAIL"] = settings.GitEmail
		values["GIT_COMMITTER_EMAIL"] = settings.GitEmail
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	env := make([]string, 0, len(keys))
	for _, key := range keys {
		env = append(env, key+"="+values[key])
	}
	return env
}
func (a *API) session(w http.ResponseWriter, r *http.Request) {
	var q request
	if !decode(w, r, &q) {
		return
	}
	dir := a.root
	var err error
	if q.Project != "" {
		dir, err = a.project(q.Project)
		if err != nil {
			fail(w, 400, err)
			return
		}
	}
	id := q.ID
	if id == "" {
		id = "wfy-" + randomID()
	}
	if !validSession(id) {
		fail(w, 400, errors.New("invalid session ID"))
		return
	}
	p := a.platform
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		fail(w, 503, errors.New("host is stopping"))
		return
	}
	if s := p.sessions[id]; s != nil && s.running.Load() {
		if s.project != q.Project {
			fail(w, 409, errors.New("session belongs to another workspace"))
			return
		}
		respond(w, map[string]string{"id": id})
		return
	}
	if len(p.sessions) >= 32 {
		fail(w, 409, errors.New("close an existing terminal before opening more (32 session limit)"))
		return
	}
	command := ""
	switch q.Kind {
	case "", "shell":
	case "codex":
		command = "& codex"
	case "claude":
		command = "& claude"
	case "codex-login":
		command = "& codex login --device-auth"
	case "claude-login":
		command = "& claude auth login"
	case "github-login":
		command = "& gh auth login --hostname github.com --git-protocol https --web; if ($LASTEXITCODE -eq 0) { & gh auth setup-git }"
	case "task":
		if strings.TrimSpace(q.Command) == "" || len(q.Command) > 128<<10 {
			fail(w, 400, errors.New("enter a command up to 128 KiB"))
			return
		}
		command = "$global:LASTEXITCODE = 0\r\n" + q.Command + "\r\nWrite-Host ('[Task exited: {0}]' -f $LASTEXITCODE)"
	default:
		fail(w, 400, errors.New("unknown session kind"))
		return
	}
	shell, err := exec.LookPath("pwsh.exe")
	if err != nil {
		shell, err = exec.LookPath("powershell.exe")
	}
	if err != nil {
		fail(w, 500, errors.New("PowerShell is not installed"))
		return
	}
	script := filepath.Join(".wfy", "session-"+id+"-"+randomID()+".ps1")
	content := "\ufeff[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)\r\nSet-Location -LiteralPath " + psQuote(dir) + "\r\n" + command + "\r\n"
	if err = a.disk.WriteFile(script, []byte(content), 0600); err != nil {
		fail(w, 500, err)
		return
	}
	line := syscall.EscapeArg(shell) + " -NoLogo -NoExit -ExecutionPolicy Bypass -File " + syscall.EscapeArg(filepath.Join(a.root, script))
	pty, err := conpty.Start(line, conpty.ConPtyDimensions(100, 30), conpty.ConPtyWorkDir(dir), conpty.ConPtyEnv(a.nativeEnvironment()))
	if err != nil {
		_ = a.disk.Remove(script)
		fail(w, 500, fmt.Errorf("start Windows terminal: %w", err))
		return
	}
	s := &nativeSession{id: id, title: q.Title, project: q.Project, created: time.Now().Unix(), pty: pty, listeners: map[chan []byte]bool{}}
	s.cleanup = func() { _ = a.disk.Remove(script) }
	if s.title == "" {
		s.title = "PowerShell"
	}
	s.running.Store(true)
	p.sessions[id] = s
	go s.collect()
	go func() {
		_, _ = pty.Wait(context.Background())
		s.stop()
		p.mu.Lock()
		if p.sessions[id] == s {
			delete(p.sessions, id)
		}
		p.mu.Unlock()
	}()
	respond(w, map[string]string{"id": id})
}
func (a *API) listSessions(w http.ResponseWriter, r *http.Request) {
	items := []map[string]any{}
	a.platform.mu.Lock()
	for _, s := range a.platform.sessions {
		if s.running.Load() {
			items = append(items, map[string]any{"id": s.id, "title": s.title, "project": s.project, "created": s.created})
		}
	}
	a.platform.mu.Unlock()
	sort.Slice(items, func(i, j int) bool { return items[i]["created"].(int64) < items[j]["created"].(int64) })
	respond(w, items)
}
func (a *API) endSession(w http.ResponseWriter, r *http.Request) {
	var q request
	if !decode(w, r, &q) {
		return
	}
	if !validSession(q.ID) {
		fail(w, 400, errors.New("invalid session ID"))
		return
	}
	a.platform.mu.Lock()
	s := a.platform.sessions[q.ID]
	delete(a.platform.sessions, q.ID)
	a.platform.mu.Unlock()
	if s != nil {
		s.stop()
	}
	respond(w, map[string]bool{"ok": true})
}
func (a *API) terminal(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validSession(id) {
		fail(w, 400, errors.New("invalid session ID"))
		return
	}
	a.platform.mu.Lock()
	s := a.platform.sessions[id]
	a.platform.mu.Unlock()
	if s == nil || !s.running.Load() {
		fail(w, 404, errors.New("session ended; start a new terminal"))
		return
	}
	up := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" }}
	ws, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer ws.Close()
	ws.SetReadLimit(64 << 10)
	history, ch := s.subscribe()
	defer func() { s.mu.Lock(); delete(s.listeners, ch); s.mu.Unlock() }()
	go func() {
		defer ws.Close()
		write := func(bytes []byte) error {
			_ = ws.SetWriteDeadline(time.Now().Add(15 * time.Second))
			return ws.WriteMessage(websocket.BinaryMessage, bytes)
		}
		if len(history) > 0 {
			if write(history) != nil {
				return
			}
		}
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case bytes, ok := <-ch:
				if !ok || write(bytes) != nil {
					return
				}
			case <-r.Context().Done():
				return
			case <-ticker.C:
				if ws.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second)) != nil {
					return
				}
			}
		}
	}()
	for {
		kind, data, err := ws.ReadMessage()
		if err != nil {
			return
		}
		s.ioMu.Lock()
		if kind == websocket.BinaryMessage && s.running.Load() {
			_, err = s.pty.Write(data)
		}
		if kind == websocket.TextMessage && s.running.Load() {
			var size struct {
				Cols int `json:"cols"`
				Rows int `json:"rows"`
			}
			if json.Unmarshal(data, &size) == nil && size.Cols >= 10 && size.Cols <= 500 && size.Rows >= 2 && size.Rows <= 300 {
				err = s.pty.Resize(size.Cols, size.Rows)
			}
		}
		s.ioMu.Unlock()
		if err != nil {
			return
		}
	}
}
