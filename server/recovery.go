package main

import (
	"encoding/json"
	"errors"
	"io/fs"
	"net/http"
	"path/filepath"
	"regexp"
	"sort"
	"time"
)

type recoveryItem struct {
	ID        string `json:"id"`
	Project   string `json:"project"`
	Path      string `json:"path"`
	Deleted   int64  `json:"deleted"`
	Directory bool   `json:"directory"`
}

var recoveryID = regexp.MustCompile(`^[a-f0-9]{24}$`)

// Called under a.mu. Metadata is written before the source is moved.
func (a *API) moveToRecovery(project, path, source string) error {
	info, err := a.disk.Lstat(source)
	if err != nil {
		return err
	}
	item := recoveryItem{randomID(), project, path, time.Now().Unix(), info.IsDir()}
	folder := filepath.Join(".wfy/trash", item.ID)
	if err = a.disk.Mkdir(folder, 0700); err != nil {
		return err
	}
	data, _ := json.Marshal(item)
	if err = a.disk.WriteFile(filepath.Join(folder, "info.json"), data, 0600); err == nil {
		err = a.disk.Rename(source, filepath.Join(folder, "content"))
	}
	if err != nil {
		_ = a.disk.Remove(filepath.Join(folder, "info.json"))
		_ = a.disk.Remove(folder)
	}
	return err
}

func (a *API) recoveryList(w http.ResponseWriter, r *http.Request) {
	project := r.URL.Query().Get("project")
	if _, err := a.project(project); err != nil {
		fail(w, 400, err)
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	folder, err := a.disk.Open(".wfy/trash")
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer folder.Close()
	entries, err := folder.ReadDir(-1)
	if err != nil {
		fail(w, 500, err)
		return
	}
	items := []recoveryItem{}
	for _, e := range entries {
		if !e.IsDir() || !recoveryID.MatchString(e.Name()) {
			continue
		}
		data, err := a.disk.ReadFile(filepath.Join(".wfy/trash", e.Name(), "info.json"))
		var item recoveryItem
		if err != nil || json.Unmarshal(data, &item) != nil || item.ID != e.Name() || item.Project != project {
			continue
		}
		if _, err := a.disk.Lstat(filepath.Join(".wfy/trash", item.ID, "content")); err == nil {
			items = append(items, item)
		}
	}
	sort.Slice(items, func(i, j int) bool {
		if items[i].Deleted == items[j].Deleted {
			return items[i].ID < items[j].ID
		}
		return items[i].Deleted > items[j].Deleted
	})
	respond(w, items)
}

func (a *API) recoveryRestore(w http.ResponseWriter, r *http.Request) {
	a.gitMu.Lock()
	defer a.gitMu.Unlock()
	var q request
	if !decode(w, r, &q) {
		return
	}
	if !recoveryID.MatchString(q.ID) {
		fail(w, 400, errors.New("invalid recovery item"))
		return
	}
	if _, err := a.project(q.Project); err != nil {
		fail(w, 400, err)
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	folder := filepath.Join(".wfy/trash", q.ID)
	data, err := a.disk.ReadFile(filepath.Join(folder, "info.json"))
	var item recoveryItem
	if err != nil || json.Unmarshal(data, &item) != nil || item.Project != q.Project || item.ID != q.ID {
		fail(w, 404, errors.New("recovery item not found in this workspace"))
		return
	}
	target := q.Target
	if target == "" {
		target = item.Path
	}
	if target == "" || target == "." {
		fail(w, 400, errors.New("choose a file or folder destination"))
		return
	}
	destination, err := cleanPath(q.Project, target)
	if err == nil {
		err = a.safeParents(destination)
	}
	if err != nil {
		fail(w, 400, err)
		return
	}
	if _, err := a.disk.Lstat(destination); !errors.Is(err, fs.ErrNotExist) {
		fail(w, 409, errors.New("destination already exists; choose a different name"))
		return
	}
	if err = a.disk.MkdirAll(filepath.Dir(destination), 0700); err != nil {
		fail(w, 400, err)
		return
	}
	if err = a.renameNoReplace(filepath.Join(folder, "content"), destination); err != nil {
		code := 400
		if errors.Is(err, fs.ErrExist) {
			code = 409
		}
		fail(w, code, err)
		return
	}
	_ = a.disk.Remove(filepath.Join(folder, "info.json"))
	_ = a.disk.Remove(folder)
	respond(w, map[string]string{"path": target})
}
