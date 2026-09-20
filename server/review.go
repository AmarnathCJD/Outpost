package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

func (a *API) pullRequests(w http.ResponseWriter, r *http.Request) {
	state := r.URL.Query().Get("state")
	if state == "" {
		state = "open"
	}
	if state != "open" && state != "closed" && state != "merged" && state != "all" {
		fail(w, 400, errors.New("invalid pull request state"))
		return
	}
	args := []string{"pr", "list", "--state", state, "--limit", "100", "--json", "number,title,state,isDraft,author,headRefName,baseRefName,updatedAt,url"}
	if query := strings.TrimSpace(r.URL.Query().Get("q")); query != "" {
		if len(query) > 200 {
			fail(w, 400, errors.New("search query is too long"))
			return
		}
		args = append(args, "--search", query)
	}
	a.githubJSON(w, r, args...)
}

func pullNumber(r *http.Request) (string, error) {
	n, err := strconv.Atoi(r.PathValue("number"))
	if err != nil || n <= 0 {
		return "", errors.New("invalid pull request number")
	}
	return strconv.Itoa(n), nil
}

func (a *API) pullRequest(w http.ResponseWriter, r *http.Request) {
	number, err := pullNumber(r)
	if err != nil {
		fail(w, 400, err)
		return
	}
	a.githubJSON(w, r, "pr", "view", number, "--json", "number,title,body,state,isDraft,author,headRefName,baseRefName,additions,deletions,changedFiles,files,comments,statusCheckRollup,reviewDecision,url,updatedAt")
}

func (a *API) githubJSON(w http.ResponseWriter, r *http.Request, args ...string) {
	dir, err := a.project(r.URL.Query().Get("project"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 45*time.Second)
	defer cancel()
	out, err := a.command(ctx, dir, []string{"GH_PROMPT_DISABLED=1", "GH_PAGER=cat", "NO_COLOR=1"}, "gh", args...)
	if err != nil {
		fail(w, 400, fmt.Errorf("GitHub: connect your account in Settings > Git and check repository access: %w", err))
		return
	}
	if !json.Valid([]byte(out)) {
		fail(w, 502, errors.New("GitHub returned an incomplete response; narrow the search or open the PR on GitHub"))
		return
	}
	respond(w, json.RawMessage(out))
}

func (a *API) pullRequestDiff(w http.ResponseWriter, r *http.Request) {
	number, err := pullNumber(r)
	if err != nil {
		fail(w, 400, err)
		return
	}
	dir, err := a.project(r.URL.Query().Get("project"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 45*time.Second)
	defer cancel()
	out, err := a.command(ctx, dir, []string{"GH_PROMPT_DISABLED=1", "GH_PAGER=cat", "NO_COLOR=1"}, "gh", "pr", "diff", number, "--color", "never")
	if err != nil {
		fail(w, 400, err)
		return
	}
	respond(w, diffResponse(out))
}

func diffResponse(diff string) map[string]any {
	added, deleted := 0, 0
	inHunk := false
	for _, line := range strings.Split(diff, "\n") {
		if strings.HasPrefix(line, "diff --git ") {
			inHunk = false
		}
		if strings.HasPrefix(line, "@@ ") {
			inHunk = true
			continue
		}
		if inHunk && strings.HasPrefix(line, "+") {
			added++
		}
		if inHunk && strings.HasPrefix(line, "-") {
			deleted++
		}
	}
	return map[string]any{"diff": diff, "additions": added, "deletions": deleted, "truncated": len(diff) >= 1<<20}
}

func (a *API) fileDiff(w http.ResponseWriter, r *http.Request) {
	project, path := r.URL.Query().Get("project"), r.URL.Query().Get("path")
	dir, err := a.project(project)
	if err != nil {
		fail(w, 400, err)
		return
	}
	if path == "" {
		fail(w, 400, errors.New("select a file"))
		return
	}
	relative, err := cleanPath(project, path)
	if err != nil {
		fail(w, 400, err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	args := []string{"--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv", "--no-color"}
	staged := r.URL.Query().Get("staged") == "true"
	if staged {
		args = append(args, "--cached")
	}
	out, err := a.git(ctx, dir, append(args, "--", path)...)
	if err != nil {
		fail(w, 400, err)
		return
	}
	if out == "" && !staged {
		untracked, e := a.git(ctx, dir, "--literal-pathspecs", "ls-files", "--others", "--exclude-standard", "--", path)
		if e != nil {
			fail(w, 400, e)
			return
		}
		if strings.TrimSpace(untracked) != "" {
			if e := a.safeParents(relative); e != nil {
				fail(w, 400, e)
				return
			}
			info, e := a.disk.Lstat(relative)
			if e != nil {
				fail(w, 400, e)
				return
			}
			if !info.Mode().IsRegular() || info.Size() > 512<<10 {
				fail(w, 400, errors.New("untracked preview is limited to regular text files under 512 KiB"))
				return
			}
			content, e := a.disk.ReadFile(relative)
			if e != nil {
				fail(w, 400, e)
				return
			}
			if !utf8.Valid(content) || strings.ContainsRune(string(content), '\x00') {
				out = "Binary file added\n"
			} else if len(content) > 0 {
				lines := strings.Split(strings.TrimSuffix(string(content), "\n"), "\n")
				out = fmt.Sprintf("@@ -0,0 +1,%d @@\n+%s\n", len(lines), strings.Join(lines, "\n+"))
			} else {
				out = "Empty file added\n"
			}
		}
	}
	respond(w, diffResponse(out))
}
