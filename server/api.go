package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type Settings struct {
	GitName         string            `json:"gitName"`
	GitEmail        string            `json:"gitEmail"`
	PRBase          string            `json:"prBase"`
	MigrationBranch string            `json:"migrationBranch"`
	Protected       []string          `json:"protected"`
	Env             map[string]string `json:"env"`
	Tasks           map[string]string `json:"tasks"`
}
type API struct {
	root     string
	disk     *os.Root
	mu       sync.Mutex
	gitMu    sync.Mutex
	settings Settings
	platform *platformState
	stopHost func()
	relay    *laptopRelay
	ai       *assistantState
}
type Project struct {
	Name      string `json:"name"`
	Branch    string `json:"branch"`
	Migration bool   `json:"migration"`
}
type Entry struct {
	Name      string `json:"name"`
	Path      string `json:"path"`
	Directory bool   `json:"directory"`
	Size      int64  `json:"size"`
	Modified  int64  `json:"modified"`
}
type request struct {
	Project  string   `json:"project"`
	Name     string   `json:"name"`
	URL      string   `json:"url"`
	Path     string   `json:"path"`
	Content  string   `json:"content"`
	Revision string   `json:"revision"`
	Action   string   `json:"action"`
	Branch   string   `json:"branch"`
	Message  string   `json:"message"`
	Paths    []string `json:"paths"`
	Target   string   `json:"target"`
	Approved bool     `json:"approved"`
	Title    string   `json:"title"`
	Body     string   `json:"body"`
	Kind     string   `json:"kind"`
	ID       string   `json:"id"`
	Command  string   `json:"command"`
}

var namePattern = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$`)
var envPattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

func NewAPI(root string) (*API, error) {
	root, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	root, err = filepath.EvalSymlinks(root)
	if err != nil {
		return nil, err
	}
	d, err := os.OpenRoot(root)
	if err != nil {
		return nil, err
	}
	a := &API{root: root, disk: d, platform: newPlatformState(), settings: Settings{PRBase: "main", MigrationBranch: "migration", Protected: []string{"main", "master"}, Env: map[string]string{}}}
	if err = d.MkdirAll(".wfy/trash", 0700); err != nil {
		d.Close()
		return nil, err
	}
	b, err := d.ReadFile(".wfy/settings.json")
	if err == nil {
		if err = json.Unmarshal(b, &a.settings); err != nil {
			d.Close()
			return nil, fmt.Errorf("invalid saved settings: %w", err)
		}
	} else if !errors.Is(err, fs.ErrNotExist) {
		d.Close()
		return nil, err
	}
	if err = a.initAssistants(); err != nil {
		d.Close()
		return nil, err
	}
	return a, nil
}
func (a *API) Close() { a.closeAssistants(); a.platform.close(); _ = a.disk.Close() }
func (a *API) routes() http.Handler {
	m := http.NewServeMux()
	if runtime.GOOS == "windows" && a.stopHost != nil {
		m.HandleFunc("POST /api/host/stop", func(w http.ResponseWriter, r *http.Request) {
			if _, err := io.Copy(io.Discard, http.MaxBytesReader(w, r.Body, 4096)); err != nil {
				fail(w, http.StatusBadRequest, errors.New("invalid stop request body"))
				return
			}
			w.Header().Set("Content-Length", "12")
			respond(w, map[string]bool{"ok": true})
			// Deliver the acknowledgement before closing SSH forwards at shutdown.
			if flush, ok := w.(http.Flusher); ok {
				flush.Flush()
			}
			a.stopHost()
		})
	}
	m.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) {
		respond(w, map[string]any{"version": "0.4.1", "status": "ready", "platform": runtime.GOOS, "shell": platformShellName(), "sshEnabled": os.Getenv("WFY_SSH_LISTEN") != "", "relay": a.relay.status()})
	})
	m.HandleFunc("GET /api/projects", a.projects)
	m.HandleFunc("POST /api/projects", a.clone)
	m.HandleFunc("POST /api/worktree", a.worktree)
	m.HandleFunc("GET /api/tree", a.tree)
	m.HandleFunc("GET /api/search", a.search)
	m.HandleFunc("GET /api/quick-open", a.quickOpen)
	m.HandleFunc("GET /api/recovery", a.recoveryList)
	m.HandleFunc("POST /api/recovery/restore", a.recoveryRestore)
	m.HandleFunc("GET /api/git/history", a.history)
	m.HandleFunc("GET /api/git/commit", a.commitDetail)
	m.HandleFunc("GET /api/git/stashes", a.stashes)
	m.HandleFunc("GET /api/git/stash", a.stashDetail)
	m.HandleFunc("POST /api/git/stash/apply", a.stashApply)
	m.HandleFunc("GET /api/file", a.readFile)
	m.HandleFunc("PUT /api/file", a.writeFile)
	m.HandleFunc("POST /api/files", a.files)
	m.HandleFunc("GET /api/git", a.gitStatus)
	m.HandleFunc("POST /api/git", a.gitAction)
	m.HandleFunc("GET /api/git/diff", a.fileDiff)
	m.HandleFunc("GET /api/pulls", a.pullRequests)
	m.HandleFunc("GET /api/pulls/{number}", a.pullRequest)
	m.HandleFunc("GET /api/pulls/{number}/diff", a.pullRequestDiff)
	m.HandleFunc("GET /api/settings", a.getSettings)
	m.HandleFunc("PUT /api/settings", a.putSettings)
	m.HandleFunc("POST /api/session", a.session)
	m.HandleFunc("GET /api/sessions", a.listSessions)
	m.HandleFunc("POST /api/session/end", a.endSession)
	m.HandleFunc("GET /api/terminal/{id}", a.terminal)
	m.HandleFunc("GET /api/ai/providers", a.aiProviders)
	m.HandleFunc("GET /api/ai/chats", a.aiChats)
	m.HandleFunc("POST /api/ai/chats", a.aiCreate)
	m.HandleFunc("GET /api/ai/chats/{id}", a.aiChat)
	m.HandleFunc("POST /api/ai/chats/{id}/message", a.aiMessage)
	m.HandleFunc("POST /api/ai/chats/{id}/stop", a.aiStop)
	m.HandleFunc("DELETE /api/ai/chats/{id}", a.aiDelete)
	return m
}
func respond(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}
func fail(w http.ResponseWriter, code int, err error) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
}
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	limit := int64(3 << 20)
	if r.Method == "PUT" && r.URL.Path == "/api/file" {
		limit = 13 << 20
	} // JSON escaping can expand 2 MiB of text sixfold.
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		fail(w, 400, err)
		return false
	}
	if err := d.Decode(new(any)); err != io.EOF {
		fail(w, 400, errors.New("expected one JSON object"))
		return false
	}
	return true
}
func (a *API) config() Settings {
	a.mu.Lock()
	defer a.mu.Unlock()
	s := a.settings
	s.Protected = append([]string{}, s.Protected...)
	s.Env = map[string]string{}
	for k, v := range a.settings.Env {
		s.Env[k] = v
	}
	s.Tasks = map[string]string{}
	for k, v := range a.settings.Tasks {
		s.Tasks[k] = v
	}
	return s
}
func (a *API) project(name string) (string, error) {
	if !namePattern.MatchString(name) {
		return "", errors.New("invalid project name")
	}
	p := filepath.Join(a.root, name)
	resolved, err := filepath.EvalSymlinks(p)
	if err != nil {
		return "", err
	}
	if resolved != p && !(runtime.GOOS == "windows" && strings.EqualFold(resolved, p)) {
		return "", errors.New("project symlinks are not permitted")
	}
	if _, err = a.disk.Stat(name + "/.git"); err != nil {
		return "", errors.New("project is not a Git workspace")
	}
	return p, nil
}
func cleanPath(project, path string) (string, error) {
	if !namePattern.MatchString(project) {
		return "", errors.New("invalid project")
	}
	path = filepath.ToSlash(path)
	if strings.Contains(path, "\\") || strings.HasPrefix(path, "/") || strings.Contains(path, ":") {
		return "", errors.New("invalid path")
	}
	for _, part := range strings.Split(path, "/") {
		if runtime.GOOS == "windows" && part != "" && part != "." {
			base := strings.ToUpper(strings.SplitN(part, ".", 2)[0])
			reserved := base == "CON" || base == "PRN" || base == "AUX" || base == "NUL" || base == "CONIN$" || base == "CONOUT$"
			if len(base) == 4 && (strings.HasPrefix(base, "COM") || strings.HasPrefix(base, "LPT")) && base[3] >= '0' && base[3] <= '9' {
				reserved = true
			}
			if reserved || strings.HasSuffix(part, ".") || strings.HasSuffix(part, " ") || strings.ContainsAny(part, "<>\"|?*") {
				return "", errors.New("reserved or ambiguous Windows filename")
			}
		}
		if part == ".." || strings.EqualFold(part, ".git") || strings.HasPrefix(strings.ToLower(part), ".wfy") {
			return "", errors.New("restricted path")
		}
	}
	return filepath.Join(project, filepath.FromSlash(path)), nil
}
func (a *API) filePath(project, path string) (string, error) {
	if _, err := a.project(project); err != nil {
		return "", err
	}
	p, err := cleanPath(project, path)
	if err != nil {
		return "", err
	}
	if err = a.safeParents(p); err != nil {
		return "", err
	}
	return p, nil
}

// File actions never traverse directory symlinks, including links to .git or another workspace.
func (a *API) safeParents(path string) error {
	parent := filepath.Dir(path)
	for parent != "." {
		info, err := a.disk.Lstat(parent)
		if err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
		if err == nil && !info.IsDir() {
			return errors.New("open the original directory; directory symlinks are not supported by file actions")
		}
		parent = filepath.Dir(parent)
	}
	return nil
}
func (a *API) command(ctx context.Context, dir string, env []string, program string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, program, args...)
	configureProcess(cmd)
	cmd.Dir = dir
	cmd.Env = append(a.toolEnvironment(), "GIT_TERMINAL_PROMPT=0")
	cmd.Env = append(cmd.Env, env...)
	var buf limitedBuffer
	cmd.Stdout = &buf
	cmd.Stderr = &buf
	err := cmd.Run()
	out := buf.String()
	if err != nil {
		return out, fmt.Errorf("%s: %s", program, strings.TrimSpace(out+" "+err.Error()))
	}
	return out, nil
}

type limitedBuffer struct {
	mu sync.Mutex
	b  []byte
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	n := len(p)
	space := (1 << 20) - len(b.b)
	if space > 0 {
		if len(p) > space {
			p = p[:space]
		}
		b.b = append(b.b, p...)
	}
	return n, nil
}
func (b *limitedBuffer) String() string { b.mu.Lock(); defer b.mu.Unlock(); return string(b.b) }
func (a *API) git(ctx context.Context, dir string, args ...string) (string, error) {
	return a.command(ctx, dir, nil, "git", args...)
}
func (a *API) branch(ctx context.Context, dir string) string {
	o, _ := a.git(ctx, dir, "branch", "--show-current")
	return strings.TrimSpace(o)
}
func (a *API) workspaceRef(ctx context.Context, dir string) string {
	if branch := a.branch(ctx, dir); branch != "" {
		return branch
	}
	id, err := a.git(ctx, dir, "rev-parse", "--verify", "HEAD")
	if err != nil {
		return ""
	}
	return "detached:" + strings.TrimSpace(id)
}
func (a *API) projects(w http.ResponseWriter, r *http.Request) {
	f, err := a.disk.Open(".")
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer f.Close()
	entries, err := f.ReadDir(-1)
	if err != nil {
		fail(w, 500, err)
		return
	}
	out := []Project{}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	for _, e := range entries {
		if !e.IsDir() || !namePattern.MatchString(e.Name()) {
			continue
		}
		dir, err := a.project(e.Name())
		if err != nil {
			continue
		}
		branch := a.branch(ctx, dir)
		out = append(out, Project{e.Name(), branch, a.isMigration(ctx, dir)})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	respond(w, out)
}
func (a *API) clone(w http.ResponseWriter, r *http.Request) {
	var q request
	if !decode(w, r, &q) {
		return
	}
	if !namePattern.MatchString(q.Name) {
		fail(w, 400, errors.New("use letters, digits, dots, underscores or dashes for the project name"))
		return
	}
	// Only GitHub HTTPS and SSH remotes. No local paths, option injection, or git ext helpers.
	if !regexp.MustCompile(`^(https://github\.com/|git@github\.com:)[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?$`).MatchString(q.URL) {
		fail(w, 400, errors.New("enter a GitHub HTTPS or git@github.com SSH repository URL"))
		return
	}
	a.mu.Lock()
	err := a.disk.Mkdir(q.Name, 0700)
	a.mu.Unlock()
	if err != nil {
		fail(w, 409, errors.New("project already exists or cannot be created"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Minute)
	defer cancel()
	_, err = a.git(ctx, filepath.Join(a.root, q.Name), "clone", "--", q.URL, ".")
	if err != nil {
		_ = a.disk.Rename(q.Name, filepath.Join(".wfy/trash", "clone-failed-"+randomID()+"-"+q.Name))
		fail(w, 400, err)
		return
	}
	if err = a.installHook(ctx, filepath.Join(a.root, q.Name)); err != nil {
		fail(w, 500, err)
		return
	}
	respond(w, map[string]string{"name": q.Name})
}
func (a *API) tree(w http.ResponseWriter, r *http.Request) {
	p, err := a.filePath(r.URL.Query().Get("project"), r.URL.Query().Get("path"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	info, err := a.disk.Lstat(p)
	if err != nil || !info.IsDir() {
		fail(w, 400, errors.New("open an existing directory; directory symlinks are not supported"))
		return
	}
	f, err := a.disk.Open(p)
	if err != nil {
		fail(w, 404, err)
		return
	}
	defer f.Close()
	entries, err := f.ReadDir(-1)
	if err != nil {
		fail(w, 400, err)
		return
	}
	out := []Entry{}
	for _, e := range entries {
		if e.Name() == ".git" || strings.HasPrefix(e.Name(), ".wfy") {
			continue
		}
		i, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, Entry{e.Name(), filepath.ToSlash(filepath.Join(r.URL.Query().Get("path"), e.Name())), e.IsDir(), i.Size(), i.ModTime().Unix()})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Directory != out[j].Directory {
			return out[i].Directory
		}
		return out[i].Name < out[j].Name
	})
	respond(w, out)
}
func revision(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func (a *API) readFile(w http.ResponseWriter, r *http.Request) {
	a.gitMu.Lock()
	defer a.gitMu.Unlock()
	p, err := a.filePath(r.URL.Query().Get("project"), r.URL.Query().Get("path"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	info, err := a.disk.Lstat(p)
	if err != nil {
		fail(w, 404, err)
		return
	}
	if !info.Mode().IsRegular() {
		fail(w, 415, errors.New("open the original regular file; symlinks and special files are not editable"))
		return
	}
	f, err := a.disk.Open(p)
	if err != nil {
		fail(w, 404, err)
		return
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, (2<<20)+1))
	if err != nil {
		fail(w, 500, err)
		return
	}
	if len(b) > 2<<20 {
		fail(w, 413, errors.New("editor supports files up to 2 MiB; use the terminal for larger files"))
		return
	}
	if strings.ContainsRune(string(b), 0) || !utf8.Valid(b) {
		fail(w, 415, errors.New("binary files cannot be edited as text"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	dir, _ := a.project(r.URL.Query().Get("project"))
	respond(w, map[string]string{"content": string(b), "revision": revision(b), "branch": a.workspaceRef(ctx, dir)})
}
func (a *API) writeFile(w http.ResponseWriter, r *http.Request) {
	a.gitMu.Lock()
	defer a.gitMu.Unlock()
	var q request
	if !decode(w, r, &q) {
		return
	}
	if len(q.Content) > 2<<20 {
		fail(w, 413, errors.New("editor supports files up to 2 MiB"))
		return
	}
	if strings.ContainsRune(q.Content, 0) {
		fail(w, 415, errors.New("binary content cannot be saved by the text editor"))
		return
	}
	p, err := a.filePath(q.Project, q.Path)
	if err != nil {
		fail(w, 400, err)
		return
	}
	if q.Branch != "" {
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		dir, _ := a.project(q.Project)
		if a.workspaceRef(ctx, dir) != q.Branch {
			fail(w, 409, errors.New("workspace branch changed; return to the draft's original branch before saving"))
			return
		}
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	info, err := a.disk.Lstat(p)
	exists := err == nil
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		fail(w, 400, err)
		return
	}
	var old []byte
	if exists {
		if !info.Mode().IsRegular() {
			fail(w, 415, errors.New("symlinks and special files cannot be replaced by the editor"))
			return
		}
		f, e := a.disk.Open(p)
		if e != nil {
			fail(w, 400, e)
			return
		}
		old, e = io.ReadAll(io.LimitReader(f, (2<<20)+1))
		_ = f.Close()
		if e != nil {
			fail(w, 400, e)
			return
		}
		if len(old) > 2<<20 {
			fail(w, 413, errors.New("server file is now larger than the editor limit"))
			return
		}
	}
	if exists && q.Revision != revision(old) {
		// A lost response may be retried with the old revision. Identical content is a safe no-op.
		if q.Revision != "" && bytes.Equal(old, []byte(q.Content)) {
			respond(w, map[string]string{"revision": revision(old)})
			return
		}
		fail(w, 409, errors.New("file changed on the server; reload before saving to avoid overwriting remote edits"))
		return
	}
	if !exists && q.Revision != "" {
		fail(w, 409, errors.New("file was removed remotely"))
		return
	}
	mode := fs.FileMode(0600)
	if stat, e := a.disk.Stat(p); e == nil {
		mode = stat.Mode().Perm()
	}
	tmp := p + ".wfy-save-" + randomID()
	if err = a.disk.WriteFile(tmp, []byte(q.Content), mode); err == nil {
		if exists {
			err = a.disk.Rename(tmp, p)
		} else {
			err = a.renameNoReplace(tmp, p)
		}
	}
	if err != nil {
		_ = a.disk.Remove(tmp)
		fail(w, 400, err)
		return
	}
	respond(w, map[string]string{"revision": revision([]byte(q.Content))})
}
func (a *API) files(w http.ResponseWriter, r *http.Request) {
	a.gitMu.Lock()
	defer a.gitMu.Unlock()
	var q request
	if !decode(w, r, &q) {
		return
	}
	if q.Path == "" || filepath.Clean(q.Path) == "." {
		fail(w, 400, errors.New("select a file or subdirectory"))
		return
	}
	p, err := a.filePath(q.Project, q.Path)
	if err != nil {
		fail(w, 400, err)
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	switch q.Action {
	case "mkdir":
		err = a.disk.Mkdir(p, 0700)
	case "rename":
		var dst string
		dst, err = cleanPath(q.Project, q.Target)
		if err == nil {
			err = a.safeParents(dst)
		}
		if err == nil {
			if _, e := a.disk.Lstat(dst); !errors.Is(e, fs.ErrNotExist) {
				err = errors.New("destination exists or is inaccessible")
			} else {
				err = a.renameNoReplace(p, dst)
			}
		}
	case "delete":
		err = a.moveToRecovery(q.Project, q.Path, p)
	default:
		err = errors.New("unknown file action")
	}
	if err != nil {
		fail(w, 400, err)
		return
	}
	respond(w, map[string]bool{"ok": true})
}
func randomID() string                                            { b := make([]byte, 12); _, _ = rand.Read(b); return hex.EncodeToString(b) }
func (a *API) getSettings(w http.ResponseWriter, r *http.Request) { respond(w, a.config()) }
func (a *API) putSettings(w http.ResponseWriter, r *http.Request) {
	var s Settings
	if !decode(w, r, &s) {
		return
	}
	if s.PRBase == "" || s.MigrationBranch == "" {
		fail(w, 400, errors.New("PR target and migration branch are required"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	for _, branch := range append([]string{s.PRBase, s.MigrationBranch}, s.Protected...) {
		if branch == "" || len(branch) > 200 {
			fail(w, 400, errors.New("branch rules require valid branch names"))
			return
		}
		if _, err := a.git(ctx, a.root, "check-ref-format", "--branch", branch); err != nil {
			fail(w, 400, fmt.Errorf("invalid branch rule: %s", branch))
			return
		}
	}
	if s.PRBase == s.MigrationBranch || s.MigrationBranch == "main" || s.MigrationBranch == "master" {
		fail(w, 400, errors.New("migration branch must be separate from the PR target and main/master"))
		return
	}
	envNames := map[string]bool{}
	for k, v := range s.Env {
		name := k
		if runtime.GOOS == "windows" {
			name = strings.ToUpper(name)
		}
		if envNames[name] {
			fail(w, 400, errors.New("environment variable names must be unique on this host"))
			return
		}
		envNames[name] = true
		if !envPattern.MatchString(k) || strings.HasPrefix(name, "WFY_") || name == "HOME" || name == "LD_PRELOAD" || name == "BASH_ENV" || strings.HasPrefix(name, "GIT_") {
			fail(w, 400, fmt.Errorf("environment variable %s is reserved or invalid", k))
			return
		}
		if strings.ContainsRune(v, 0) {
			fail(w, 400, fmt.Errorf("environment variable %s contains a null character", k))
			return
		}
	}
	unique := map[string]bool{}
	protected := []string{}
	for _, branch := range append(s.Protected, "main", "master") {
		if !unique[branch] {
			protected = append(protected, branch)
			unique[branch] = true
		}
	}
	s.Protected = protected
	b, _ := json.MarshalIndent(s, "", "  ")
	a.mu.Lock()
	defer a.mu.Unlock()
	err := a.disk.WriteFile(".wfy/settings.json.tmp", b, 0600)
	if err == nil {
		err = a.disk.Rename(".wfy/settings.json.tmp", ".wfy/settings.json")
	}
	if err != nil {
		fail(w, 500, err)
		return
	}
	a.settings = s
	respond(w, map[string]bool{"ok": true})
}
