//go:build !windows

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func shellQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\"'\"'") + "'" }
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
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	if _, err = a.command(ctx, dir, nil, "tmux", "has-session", "-t", "="+id); err == nil {
		respond(w, map[string]string{"id": id})
		return
	}
	command := "exec bash -i"
	switch q.Kind {
	case "codex":
		command = "codex; exec bash -i"
	case "claude":
		command = "claude; exec bash -i"
	case "codex-login":
		command = "codex login --device-auth; exec bash -i"
	case "claude-login":
		command = "claude auth login; exec bash -i"
	case "github-login":
		command = "gh auth login --hostname github.com --git-protocol https --web; gh auth setup-git; exec bash -i"
	case "task":
		if strings.TrimSpace(q.Command) == "" {
			fail(w, 400, errors.New("command is empty"))
			return
		}
		command = q.Command + "\nprintf '\\n[Task exited: %s]\\n' \"$?\"; exec bash -i"
	case "shell", "":
	default:
		fail(w, 400, errors.New("unknown session kind"))
		return
	}
	// A new session receives a snapshot of configured environment variables. Existing jobs keep their environment.
	settings := a.config()
	prefix := "unset CLAUDECODE CLAUDE_CODE_ENTRYPOINT CODEX_THREAD_ID; "
	for k, v := range settings.Env {
		prefix += "export " + k + "=" + shellQuote(v) + "; "
	}
	if settings.GitName != "" {
		prefix += "export GIT_AUTHOR_NAME=" + shellQuote(settings.GitName) + " GIT_COMMITTER_NAME=" + shellQuote(settings.GitName) + "; "
	}
	if settings.GitEmail != "" {
		prefix += "export GIT_AUTHOR_EMAIL=" + shellQuote(settings.GitEmail) + " GIT_COMMITTER_EMAIL=" + shellQuote(settings.GitEmail) + "; "
	}
	// Store secrets outside process arguments, readable only by the workspace user.
	script := filepath.Join(".wfy", "session-"+id+".sh")
	content := "#!/bin/bash\n" + prefix + "\n" + command + "\n"
	if err = a.disk.WriteFile(script, []byte(content), 0700); err != nil {
		fail(w, 500, err)
		return
	}
	meta, _ := json.Marshal(map[string]string{"id": id, "title": q.Title, "project": q.Project, "kind": q.Kind})
	if err = a.disk.WriteFile(".wfy/session-"+id+".json", meta, 0600); err != nil {
		fail(w, 500, err)
		return
	}
	_, err = a.command(ctx, dir, nil, "tmux", "new-session", "-d", "-s", id, "-c", dir, "-x", "100", "-y", "30", "bash "+shellQuote(filepath.Join(a.root, script)))
	if err != nil {
		_ = a.disk.Remove(script)
		fail(w, 500, fmt.Errorf("start session: %w", err))
		return
	}
	respond(w, map[string]string{"id": id})
}
func (a *API) listSessions(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	out, err := a.command(ctx, a.root, nil, "tmux", "list-sessions", "-F", "#{session_name}\t#{session_created}")
	sessions := []map[string]any{}
	if err == nil {
		for _, line := range strings.Split(strings.TrimSpace(out), "\n") {
			parts := strings.Split(line, "\t")
			if len(parts) != 2 || !validSession(parts[0]) {
				continue
			}
			meta := map[string]string{}
			if b, e := a.disk.ReadFile(".wfy/session-" + parts[0] + ".json"); e == nil {
				_ = json.Unmarshal(b, &meta)
			}
			created, _ := strconv.ParseInt(parts[1], 10, 64)
			title := meta["title"]
			if title == "" {
				title = "Terminal"
			}
			sessions = append(sessions, map[string]any{"id": parts[0], "title": title, "project": meta["project"], "created": created})
		}
	}
	respond(w, sessions)
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
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	if _, err := a.command(ctx, a.root, nil, "tmux", "has-session", "-t", "="+q.ID); err == nil {
		if _, err = a.command(ctx, a.root, nil, "tmux", "kill-session", "-t", "="+q.ID); err != nil {
			fail(w, 500, err)
			return
		}
	}
	_ = a.disk.Remove(".wfy/session-" + q.ID + ".sh")
	_ = a.disk.Remove(".wfy/session-" + q.ID + ".json")
	respond(w, map[string]bool{"ok": true})
}
