package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func (a *API) isMigration(ctx context.Context, dir string) bool {
	v, _ := a.git(ctx, dir, "config", "--worktree", "--get", "wfy.migration")
	return strings.TrimSpace(v) == "true" || a.branch(ctx, dir) == a.config().MigrationBranch
}
func (a *API) protected(branch string) bool {
	if branch == "main" || branch == "master" {
		return true
	}
	for _, b := range a.config().Protected {
		if branch == b {
			return true
		}
	}
	return false
}
func (a *API) installHook(ctx context.Context, dir string) error {
	_, err := a.git(ctx, dir, "config", "extensions.worktreeConfig", "true")
	if err != nil {
		return err
	}
	hookDir := filepath.Join(a.root, ".wfy", "hooks")
	if err = os.MkdirAll(hookDir, 0700); err != nil {
		return err
	}
	// Defense in depth only: terminal users can bypass local hooks. GitHub rules are authoritative.
	hook := `#!/bin/sh
set -eu
migration=$(git config --worktree --get wfy.migration || true)
while read -r local_ref local_sha remote_ref remote_sha; do
 case "$remote_ref" in refs/heads/main|refs/heads/master) echo 'Wfy: direct pushes to main/master are blocked. Open a PR.' >&2; exit 1;; esac
 if [ "$migration" = true ] && [ "${WFY_MIGRATION_APPROVED:-}" != yes ]; then
  echo 'Wfy: migration push requires explicit team approval in the app.' >&2; exit 1
 fi
done
`
	if err = os.WriteFile(filepath.Join(hookDir, "pre-push"), []byte(hook), 0700); err != nil {
		return err
	}
	_, err = a.git(ctx, dir, "config", "core.hooksPath", filepath.ToSlash(hookDir))
	return err
}
func (a *API) worktree(w http.ResponseWriter, r *http.Request) {
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
	if !namePattern.MatchString(q.Name) || q.Name == q.Project {
		fail(w, 400, errors.New("choose a distinct workspace name"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	s := a.config()
	migration := q.Kind == "migration"
	if !migration && a.isMigration(ctx, dir) {
		fail(w, 400, errors.New("open a feature or base workspace first; a feature workspace cannot inherit migration commits"))
		return
	}
	if err = a.installHook(ctx, dir); err != nil {
		fail(w, 500, err)
		return
	}
	branch := q.Branch
	if migration {
		branch = s.MigrationBranch
	}
	if _, err = a.git(ctx, dir, "check-ref-format", "--branch", branch); err != nil {
		fail(w, 400, errors.New("invalid branch name"))
		return
	}
	if !migration && (a.protected(branch) || branch == s.MigrationBranch) {
		fail(w, 400, errors.New("choose a feature branch name"))
		return
	}
	if migration {
		_, err = a.git(ctx, dir, "show-ref", "--verify", "--quiet", "refs/heads/"+branch)
		if err == nil {
			_, err = a.git(ctx, dir, "worktree", "add", "--", filepath.Join(a.root, q.Name), branch)
		} else {
			_, err = a.git(ctx, dir, "show-ref", "--verify", "--quiet", "refs/remotes/origin/"+branch)
			if err != nil {
				fail(w, 400, errors.New("migration branch must already exist locally or on origin; fetch first"))
				return
			}
			_, err = a.git(ctx, dir, "worktree", "add", "-b", branch, "--", filepath.Join(a.root, q.Name), "origin/"+branch)
		}
	} else {
		_, err = a.git(ctx, dir, "worktree", "add", "-b", branch, "--", filepath.Join(a.root, q.Name), "HEAD")
	}
	if err != nil {
		fail(w, 400, err)
		return
	}
	newDir := filepath.Join(a.root, q.Name)
	_, err = a.git(ctx, newDir, "config", "--worktree", "wfy.migration", fmt.Sprint(migration))
	if err != nil {
		fail(w, 500, err)
		return
	}
	respond(w, map[string]string{"name": q.Name})
}
func (a *API) gitStatus(w http.ResponseWriter, r *http.Request) {
	dir, err := a.project(r.URL.Query().Get("project"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	status, err := a.git(ctx, dir, "status", "--porcelain=v1", "-z")
	if err != nil {
		fail(w, 400, err)
		return
	}
	files := []map[string]string{}
	parts := strings.Split(status, "\x00")
	for i := 0; i < len(parts); i++ {
		p := parts[i]
		if len(p) < 4 {
			continue
		}
		files = append(files, map[string]string{"status": p[:2], "path": p[3:]})
		if strings.ContainsAny(p[:2], "RC") {
			i++
		}
	}
	branches, _ := a.git(ctx, dir, "branch", "--format=%(refname:short)")
	// Full patches are fetched on demand, rather than on every sync interval.
	var diff, staged string
	if r.URL.Query().Get("diffs") == "true" {
		diff, _ = a.git(ctx, dir, "diff", "--no-ext-diff", "--no-color", "--")
		staged, _ = a.git(ctx, dir, "diff", "--cached", "--no-ext-diff", "--no-color", "--")
	}
	log, _ := a.git(ctx, dir, "log", "-8", "--format=%h %s")
	branch := a.branch(ctx, dir)
	respond(w, map[string]any{"branch": branch, "protected": a.protected(branch), "migration": a.isMigration(ctx, dir), "files": files, "branches": strings.Fields(branches), "diff": diff, "stagedDiff": staged, "log": log})
}
func (a *API) gitAction(w http.ResponseWriter, r *http.Request) {
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
	ctx, cancel := context.WithTimeout(r.Context(), 90*time.Second)
	defer cancel()
	branch := a.branch(ctx, dir)
	migration := a.isMigration(ctx, dir)
	s := a.config()
	var out string
	switch q.Action {
	case "fetch":
		out, err = a.git(ctx, dir, "fetch", "--prune", "origin")
	case "pull":
		out, err = a.git(ctx, dir, "pull", "--ff-only")
	case "stage", "unstage":
		if len(q.Paths) == 0 {
			err = errors.New("select files first")
			break
		}
		for _, p := range q.Paths {
			if p == "" || p == "." {
				err = errors.New("select individual files")
				break
			}
			if _, e := cleanPath(q.Project, p); e != nil {
				err = e
				break
			}
		}
		if err != nil {
			break
		}
		args := []string{"--literal-pathspecs", "add", "--"}
		if q.Action == "unstage" {
			args = []string{"--literal-pathspecs", "restore", "--staged", "--"}
		}
		out, err = a.git(ctx, dir, append(args, q.Paths...)...)
	case "commit":
		if a.protected(branch) || branch == "" {
			err = errors.New("create or switch to a feature workspace before committing")
			break
		}
		if strings.TrimSpace(q.Message) == "" {
			err = errors.New("commit message is required")
			break
		}
		args := []string{}
		if s.GitName != "" {
			args = append(args, "-c", "user.name="+s.GitName)
		}
		if s.GitEmail != "" {
			args = append(args, "-c", "user.email="+s.GitEmail)
		}
		out, err = a.git(ctx, dir, append(args, "commit", "-m", q.Message)...)
	case "switch", "branch":
		if migration {
			err = errors.New("migration workspaces stay on the migration branch; open a feature workspace instead")
			break
		}
		if q.Branch == s.MigrationBranch {
			err = errors.New("open the migration branch in a separate migration workspace")
			break
		}
		if _, err = a.git(ctx, dir, "check-ref-format", "--branch", q.Branch); err != nil {
			break
		}
		dirty, _ := a.git(ctx, dir, "status", "--porcelain")
		if dirty != "" {
			err = errors.New("commit or stash your changes before switching branches")
			break
		}
		args := []string{"switch"}
		if q.Action == "branch" {
			if a.protected(q.Branch) {
				err = errors.New("cannot create a protected branch")
				break
			}
			args = append(args, "-c")
		}
		out, err = a.git(ctx, dir, append(args, "--", q.Branch)...)
	case "stash":
		before, _ := a.git(ctx, dir, "rev-parse", "--verify", "refs/stash")
		label := strings.TrimSpace(q.Message)
		if label == "" {
			label = "Saved work"
		}
		out, err = a.git(ctx, dir, "stash", "push", "--include-untracked", "-m", label)
		if err == nil {
			after, e := a.git(ctx, dir, "rev-parse", "--verify", "refs/stash")
			if e == nil && before != after {
				err = a.recordStash(strings.TrimSpace(after), q.Project, migration)
			}
		}
	case "stash-pop":
		err = errors.New("open Stashes, inspect an entry, then apply it; automatic pop is disabled")
	case "push":
		if a.protected(branch) || branch == "" {
			err = errors.New("direct pushes to protected branches are blocked; open a PR")
			break
		}
		if migration && !q.Approved {
			err = errors.New("confirm TM/TL approval before pushing migrations")
			break
		}
		env := []string{}
		if migration {
			env = append(env, "WFY_MIGRATION_APPROVED=yes")
		}
		out, err = a.command(ctx, dir, env, "git", "push", "--set-upstream", "origin", "HEAD:refs/heads/"+branch)
	case "pr":
		if migration || branch == s.MigrationBranch {
			err = errors.New("migration workspaces cannot create feature PRs")
			break
		}
		if a.protected(branch) || branch == "" || branch == s.PRBase {
			err = errors.New("PRs must originate from a feature branch")
			break
		}
		if strings.TrimSpace(q.Title) == "" {
			err = errors.New("PR title is required")
			break
		}
		out, err = a.command(ctx, dir, nil, "gh", "pr", "create", "--base", s.PRBase, "--head", branch, "--title", q.Title, "--body", q.Body)
	default:
		err = errors.New("unknown Git action")
	}
	if err != nil {
		fail(w, 400, err)
		return
	}
	respond(w, map[string]string{"output": out})
}
