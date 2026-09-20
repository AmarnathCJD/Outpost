package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"path/filepath"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

func (a *API) search(w http.ResponseWriter, r *http.Request) {
	dir, err := a.project(r.URL.Query().Get("project"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	query := r.URL.Query().Get("q")
	if utf8.RuneCountInString(query) < 2 || utf8.RuneCountInString(query) > 200 {
		fail(w, 400, errors.New("search text must contain 2–200 characters"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	// Fixed-string search prevents pathological regexes; Git ignores and hidden credentials stay excluded.
	args := []string{"--json", "--fixed-strings", "--max-count", "10", "--max-filesize", "1M", "--glob=!.wfy*"}
	if r.URL.Query().Get("case") != "true" {
		args = append(args, "--ignore-case")
	}
	if r.URL.Query().Get("word") == "true" {
		args = append(args, "--word-regexp")
	}
	if glob := r.URL.Query().Get("glob"); glob != "" {
		if utf8.RuneCountInString(glob) > 200 {
			fail(w, 400, errors.New("file filter is too long"))
			return
		}
		args = append(args, "--glob="+glob)
	}
	output, err := a.command(ctx, dir, nil, "rg", append(args, "--", query, ".")...)
	results := []map[string]any{}
	for _, line := range strings.Split(output, "\n") {
		var event struct {
			Type string `json:"type"`
			Data struct {
				Path struct {
					Text string `json:"text"`
				} `json:"path"`
				Lines struct {
					Text string `json:"text"`
				} `json:"lines"`
				Line int `json:"line_number"`
			} `json:"data"`
		}
		if json.Unmarshal([]byte(line), &event) == nil && event.Type == "match" {
			path := strings.TrimPrefix(filepath.ToSlash(event.Data.Path.Text), "./")
			if !searchablePath(path) {
				continue
			}
			results = append(results, map[string]any{"path": path, "line": event.Data.Line, "text": strings.TrimSpace(event.Data.Lines.Text)})
			if len(results) == 100 {
				break
			}
		}
	}
	if err != nil && len(results) == 0 && !strings.Contains(err.Error(), "exit status 1") {
		fail(w, 400, err)
		return
	}
	respond(w, results)
}

func (a *API) quickOpen(w http.ResponseWriter, r *http.Request) {
	dir, err := a.project(r.URL.Query().Get("project"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	query := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	if utf8.RuneCountInString(query) > 200 {
		fail(w, 400, errors.New("file query is too long"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	out, err := a.command(ctx, dir, nil, "rg", "--files", "--null", "--glob=!.wfy*")
	if err != nil && !strings.Contains(err.Error(), "exit status 1") {
		fail(w, 400, err)
		return
	}
	paths := strings.Split(out, "\x00")
	if len(paths) > 0 {
		paths = paths[:len(paths)-1]
	} // Ignore a potentially truncated last record.
	result := []string{}
	for _, path := range paths {
		path = filepath.ToSlash(path)
		if !searchablePath(path) {
			continue
		}
		matches := true
		for _, part := range strings.Fields(query) {
			if !strings.Contains(strings.ToLower(path), part) {
				matches = false
				break
			}
		}
		if matches {
			result = append(result, path)
		}
	}
	sort.Slice(result, func(i, j int) bool {
		if len(result[i]) != len(result[j]) {
			return len(result[i]) < len(result[j])
		}
		return result[i] < result[j]
	})
	if len(result) > 100 {
		result = result[:100]
	}
	respond(w, result)
}

func searchablePath(path string) bool {
	if !utf8.ValidString(path) {
		return false
	}
	for _, part := range strings.Split(path, "/") {
		if strings.HasPrefix(part, ".") {
			return false
		}
	}
	return path != ""
}
