package main

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

// Child tools use the hosting user's existing credentials, config and environment.
// Invocation-only markers must not make a new Claude/Codex process think it is nested.
func (a *API) toolEnvironment() []string {
	values := map[string]string{}
	key := func(s string) string {
		if runtime.GOOS == "windows" {
			return strings.ToUpper(s)
		}
		return s
	}
	for _, item := range os.Environ() {
		if i := strings.IndexByte(item, '='); i > 0 {
			values[key(item[:i])] = item[i+1:]
		}
	}
	refreshUserEnvironment(values)
	for k, v := range a.config().Env {
		values[key(k)] = v
	}
	for _, k := range []string{"CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CODEX_THREAD_ID"} {
		delete(values, k)
	}
	values["TERM"] = "xterm-256color"
	values["COLORTERM"] = "truecolor"
	home, _ := os.UserHomeDir()
	extra := []string{filepath.Join(home, ".local", "bin"), filepath.Join(home, "go", "bin")}
	if runtime.GOOS == "windows" {
		extra = append(extra, filepath.Join(os.Getenv("APPDATA"), "npm"), filepath.Join(home, "scoop", "shims"))
	} else {
		extra = append(extra, "/usr/local/bin", filepath.Join(home, ".npm-global", "bin"))
	}
	values[key("PATH")] += string(os.PathListSeparator) + strings.Join(extra, string(os.PathListSeparator))
	// A desktop that refreshes environment settings can inherit duplicate PATHs.
	// Deduplicate entries before npm wrappers invoke cmd.exe (which has an 8191 limit).
	seen := map[string]bool{}
	paths := []string{}
	for _, path := range filepath.SplitList(values[key("PATH")]) {
		path = strings.Trim(strings.TrimSpace(path), "\"")
		if path != "" && !seen[key(path)] {
			seen[key(path)] = true
			paths = append(paths, path)
		}
	}
	values[key("PATH")] = strings.Join(paths, string(os.PathListSeparator))
	keys := make([]string, 0, len(values))
	for k := range values {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	env := make([]string, 0, len(values))
	for _, k := range keys {
		env = append(env, k+"="+values[k])
	}
	return env
}
func envValue(env []string, key string) string {
	for _, v := range env {
		if i := strings.IndexByte(v, '='); i > 0 && (v[:i] == key || runtime.GOOS == "windows" && strings.EqualFold(v[:i], key)) {
			return v[i+1:]
		}
	}
	return ""
}
func toolPath(provider string, env []string) (string, error) {
	override := envValue(env, "OUTPOST_"+strings.ToUpper(provider)+"_PATH")
	candidates := []string{}
	if override != "" {
		candidates = append(candidates, override)
	} else {
		suffixes := []string{""}
		if runtime.GOOS == "windows" {
			suffixes = []string{".exe", ".cmd", ".bat", ".ps1"}
		}
		for _, dir := range filepath.SplitList(envValue(env, "PATH")) {
			if !filepath.IsAbs(dir) {
				continue
			}
			for _, suffix := range suffixes {
				candidates = append(candidates, filepath.Join(dir, provider+suffix))
			}
		}
	}
	for _, path := range candidates {
		if st, err := os.Stat(path); err == nil && st.Mode().IsRegular() && (runtime.GOOS == "windows" || st.Mode()&0111 != 0) {
			return path, nil
		}
	}
	return "", errors.New(provider + " is not installed or not on the host PATH. Install it for the hosting user, or set OUTPOST_" + strings.ToUpper(provider) + "_PATH in Git & environment.")
}
func toolCommand(ctx context.Context, path, dir string, env []string, args ...string) *exec.Cmd {
	cmd := platformToolCommand(ctx, path, args)
	cmd.Dir = dir
	cmd.Env = env
	cmd.WaitDelay = 3 * time.Second
	prepareToolProcess(cmd)
	return cmd
}
