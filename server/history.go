package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var objectID = regexp.MustCompile(`^[a-fA-F0-9]{40}([a-fA-F0-9]{24})?$`)

type stashOrigin struct {
	Project   string `json:"project"`
	Migration bool   `json:"migration"`
}

func (a *API) recordStash(id, project string, migration bool) error {
	if !objectID.MatchString(id) {
		return errors.New("could not record stash origin")
	}
	if err := a.disk.MkdirAll(".wfy/stashes", 0700); err != nil {
		return err
	}
	data, _ := json.Marshal(stashOrigin{project, migration})
	return a.disk.WriteFile(filepath.Join(".wfy/stashes", id+".json"), data, 0600)
}
func (a *API) stashOrigin(id string) (stashOrigin, bool) {
	var origin stashOrigin
	if !objectID.MatchString(id) {
		return origin, false
	}
	data, err := a.disk.ReadFile(filepath.Join(".wfy/stashes", id+".json"))
	if err != nil || json.Unmarshal(data, &origin) != nil {
		return origin, false
	}
	return origin, origin.Project != ""
}

func (a *API) history(w http.ResponseWriter, r *http.Request) {
	dir, err := a.project(r.URL.Query().Get("project"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	skip := 0
	if value := r.URL.Query().Get("skip"); value != "" {
		skip, err = strconv.Atoi(value)
		if err != nil || skip < 0 || skip > 10000 {
			fail(w, 400, errors.New("invalid history offset"))
			return
		}
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	args := []string{"log", "-z", "--max-count=40", "--skip=" + strconv.Itoa(skip), "--format=%H%x00%h%x00%an%x00%aI%x00%s"}
	if ref := r.URL.Query().Get("ref"); ref != "" {
		if !objectID.MatchString(ref) {
			fail(w, 400, errors.New("invalid history reference"))
			return
		}
		if _, err := a.git(ctx, dir, "merge-base", "--is-ancestor", ref, "HEAD"); err != nil {
			fail(w, 409, errors.New("branch history changed; refresh history before loading more"))
			return
		}
		args = append(args, ref)
	}
	if path := r.URL.Query().Get("path"); path != "" {
		if _, err = cleanPath(r.URL.Query().Get("project"), path); err != nil {
			fail(w, 400, err)
			return
		}
		args = append([]string{"--literal-pathspecs"}, args...)
		args = append(args, "--", path)
	}
	out, err := a.git(ctx, dir, args...)
	if err != nil {
		if _, e := a.git(ctx, dir, "rev-parse", "--verify", "HEAD"); e != nil {
			respond(w, []any{})
			return
		}
		fail(w, 400, err)
		return
	}
	fields := strings.Split(out, "\x00")
	items := []map[string]string{}
	for i := 0; i+4 < len(fields); i += 5 {
		if objectID.MatchString(fields[i]) {
			items = append(items, map[string]string{"id": fields[i], "short": fields[i+1], "author": fields[i+2], "date": fields[i+3], "title": fields[i+4]})
		}
	}
	respond(w, items)
}

func (a *API) commitDetail(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	if !objectID.MatchString(id) {
		fail(w, 400, errors.New("select a commit from history"))
		return
	}
	dir, err := a.project(r.URL.Query().Get("project"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	// Only commits reachable from this workspace's history may be inspected.
	if _, err = a.git(ctx, dir, "merge-base", "--is-ancestor", id, "HEAD"); err != nil {
		fail(w, 400, errors.New("commit is not in this workspace's history"))
		return
	}
	out, err := a.git(ctx, dir, "show", "--format=fuller", "--diff-merges=first-parent", "--no-ext-diff", "--no-textconv", "--no-color", id, "--")
	if err != nil {
		fail(w, 400, err)
		return
	}
	respond(w, diffResponse(out))
}

func (a *API) readStashes(ctx context.Context, dir string) ([]map[string]string, error) {
	out, err := a.git(ctx, dir, "stash", "list", "-z", "--format=%H%x00%gd%x00%gs")
	if err != nil {
		return nil, err
	}
	parts := strings.Split(out, "\x00")
	items := []map[string]string{}
	for i := 0; i+2 < len(parts); i += 3 {
		if objectID.MatchString(parts[i]) {
			origin, known := a.stashOrigin(parts[i])
			kind := "unknown"
			if known {
				kind = "feature"
				if origin.Migration {
					kind = "migration"
				}
			}
			items = append(items, map[string]string{"id": parts[i], "ref": parts[i+1], "title": parts[i+2], "origin": origin.Project, "kind": kind})
		}
	}
	return items, nil
}
func (a *API) stashes(w http.ResponseWriter, r *http.Request) {
	dir, err := a.project(r.URL.Query().Get("project"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	items, err := a.readStashes(ctx, dir)
	if err != nil {
		fail(w, 400, err)
		return
	}
	respond(w, items)
}
func (a *API) validStash(ctx context.Context, dir, id string) bool {
	if !objectID.MatchString(id) {
		return false
	}
	items, err := a.readStashes(ctx, dir)
	if err != nil {
		return false
	}
	for _, item := range items {
		if item["id"] == id {
			return true
		}
	}
	return false
}
func (a *API) stashDetail(w http.ResponseWriter, r *http.Request) {
	dir, err := a.project(r.URL.Query().Get("project"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	id := r.URL.Query().Get("id")
	if !a.validStash(ctx, dir, id) {
		fail(w, 404, errors.New("stash no longer exists; refresh the list"))
		return
	}
	out, err := a.git(ctx, dir, "stash", "show", "--patch", "--include-untracked", "--no-ext-diff", "--no-textconv", "--no-color", id)
	if err != nil {
		fail(w, 400, err)
		return
	}
	respond(w, diffResponse(out))
}
func (a *API) stashApply(w http.ResponseWriter, r *http.Request) {
	a.gitMu.Lock()
	defer a.gitMu.Unlock()
	var q request
	if !decode(w, r, &q) {
		return
	}
	dir, err := a.project(q.Project)
	if err != nil {
		fail(w, 400, err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	if a.isMigration(ctx, dir) {
		fail(w, 400, errors.New("use the migration terminal to apply migration stashes after reviewing their origin"))
		return
	}
	if branch := a.branch(ctx, dir); branch == "" || a.protected(branch) {
		fail(w, 400, errors.New("open a feature branch before applying a stash"))
		return
	}
	if !a.validStash(ctx, dir, q.ID) {
		fail(w, 404, errors.New("stash no longer exists; refresh the list"))
		return
	}
	origin, known := a.stashOrigin(q.ID)
	if !known || origin.Migration {
		fail(w, 400, errors.New("only stashes saved by Outpost from feature workspaces can be applied here; inspect other origins in the terminal"))
		return
	}
	dirty, err := a.git(ctx, dir, "status", "--porcelain")
	if err != nil || dirty != "" {
		fail(w, 409, errors.New("commit or stash current changes before applying a saved stash"))
		return
	}
	// Apply keeps the recovery entry; it is never popped or dropped automatically.
	out, err := a.git(ctx, dir, "stash", "apply", q.ID)
	if err != nil {
		fail(w, 409, err)
		return
	}
	respond(w, map[string]string{"output": out, "message": "Stash applied and retained in the stash list"})
}
