package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

type assistantMessage struct {
	ID   string `json:"id"`
	Role string `json:"role"`
	Text string `json:"text"`
}
type assistantChat struct {
	ID       string             `json:"id"`
	Provider string             `json:"provider"`
	Project  string             `json:"project"`
	Title    string             `json:"title"`
	ThreadID string             `json:"threadId"`
	Mode     string             `json:"mode"`
	Updated  int64              `json:"updated"`
	Running  bool               `json:"running"`
	Status   string             `json:"status,omitempty"`
	Error    string             `json:"error,omitempty"`
	Messages []assistantMessage `json:"messages"`
	cancel   context.CancelFunc
	streamID string
	lastSave time.Time
}
type assistantState struct {
	mu     sync.Mutex
	chats  map[string]*assistantChat
	wg     sync.WaitGroup
	closed bool
}

var threadPattern = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$`)

func (a *API) initAssistants() error {
	a.ai = &assistantState{chats: map[string]*assistantChat{}}
	if err := a.disk.MkdirAll(".wfy/chats", 0700); err != nil {
		return err
	}
	dir, err := a.disk.Open(".wfy/chats")
	if err != nil {
		return err
	}
	defer dir.Close()
	entries, err := dir.ReadDir(-1)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasPrefix(entry.Name(), "chat-") || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		info, e := entry.Info()
		if e != nil || info.Size() > 4<<20 {
			continue
		}
		data, e := a.disk.ReadFile(".wfy/chats/" + entry.Name())
		if e != nil {
			return e
		}
		var c assistantChat
		if json.Unmarshal(data, &c) != nil || !namePattern.MatchString(c.ID) || entry.Name() != c.ID+".json" {
			continue
		}
		if c.Running {
			c.Running = false
			c.Error = "Hosting stopped during this reply. Send another message to resume the saved conversation."
		}
		a.ai.chats[c.ID] = &c
	}
	return nil
}
func (a *API) closeAssistants() {
	if a.ai == nil {
		return
	}
	a.ai.mu.Lock()
	a.ai.closed = true
	for _, c := range a.ai.chats {
		if c.cancel != nil {
			c.cancel()
		}
	}
	a.ai.mu.Unlock()
	a.ai.wg.Wait()
}

// Writes use an atomic replacement: a restart never leaves a half-written chat.
func (a *API) saveChat(c *assistantChat) error {
	c.lastSave = time.Now()
	c.Updated = time.Now().UnixMilli()
	b, err := json.Marshal(c)
	if err != nil {
		return err
	}
	if len(b) > 4<<20 {
		return errors.New("conversation display history reached 4 MiB; start another chat or resume its ID in the laptop CLI")
	}
	path := ".wfy/chats/" + c.ID + ".json"
	if err = a.disk.WriteFile(path+".tmp", b, 0600); err != nil {
		return err
	}
	return a.disk.Rename(path+".tmp", path)
}
func (a *API) aiChats(w http.ResponseWriter, r *http.Request) {
	a.ai.mu.Lock()
	defer a.ai.mu.Unlock()
	items := []assistantChat{}
	for _, c := range a.ai.chats {
		if p := r.URL.Query().Get("project"); p == "" || p == c.Project {
			v := *c
			v.Messages = nil
			items = append(items, v)
		}
	}
	sort.Slice(items, func(i, j int) bool { return items[i].Updated > items[j].Updated })
	respond(w, items)
}
func (a *API) aiCreate(w http.ResponseWriter, r *http.Request) {
	var q struct{ Project, Provider, Title, ThreadID, Mode string }
	if !decode(w, r, &q) {
		return
	}
	if q.Provider != "codex" && q.Provider != "claude" {
		fail(w, 400, errors.New("choose Codex or Claude"))
		return
	}
	if _, err := a.project(q.Project); err != nil {
		fail(w, 400, err)
		return
	}
	if q.ThreadID != "" && !threadPattern.MatchString(q.ThreadID) {
		fail(w, 400, errors.New("invalid conversation ID"))
		return
	}
	if q.Mode == "" {
		q.Mode = "workspace-write"
	}
	if q.Mode != "workspace-write" && q.Mode != "read-only" {
		fail(w, 400, errors.New("invalid assistant mode"))
		return
	}
	a.ai.mu.Lock()
	defer a.ai.mu.Unlock()
	if a.ai.closed {
		fail(w, 503, errors.New("host is stopping"))
		return
	}
	if len(a.ai.chats) >= 256 {
		fail(w, 409, errors.New("remove an old chat before creating more (256 chat limit)"))
		return
	}
	c := &assistantChat{ID: "chat-" + randomID(), Provider: q.Provider, Project: q.Project, Title: strings.TrimSpace(q.Title), ThreadID: q.ThreadID, Mode: q.Mode, Messages: []assistantMessage{}}
	if c.Title == "" {
		c.Title = map[string]string{"codex": "Codex", "claude": "Claude"}[c.Provider] + " · " + c.Project
	}
	c.Title = clip(c.Title, 160)
	if err := a.saveChat(c); err != nil {
		fail(w, 500, err)
		return
	}
	a.ai.chats[c.ID] = c
	respond(w, c)
}
func (a *API) aiChat(w http.ResponseWriter, r *http.Request) {
	a.ai.mu.Lock()
	defer a.ai.mu.Unlock()
	c := a.ai.chats[r.PathValue("id")]
	if c == nil {
		fail(w, 404, errors.New("conversation not found"))
		return
	}
	respond(w, c)
}
func (a *API) aiDelete(w http.ResponseWriter, r *http.Request) {
	a.ai.mu.Lock()
	defer a.ai.mu.Unlock()
	c := a.ai.chats[r.PathValue("id")]
	if c == nil {
		fail(w, 404, errors.New("conversation not found"))
		return
	}
	if c.Running {
		fail(w, 409, errors.New("stop the reply before removing this chat"))
		return
	}
	if err := a.disk.Remove(".wfy/chats/" + c.ID + ".json"); err != nil {
		fail(w, 500, err)
		return
	}
	delete(a.ai.chats, c.ID)
	respond(w, map[string]bool{"ok": true})
}
func (a *API) aiStop(w http.ResponseWriter, r *http.Request) {
	a.ai.mu.Lock()
	defer a.ai.mu.Unlock()
	c := a.ai.chats[r.PathValue("id")]
	if c == nil {
		fail(w, 404, errors.New("conversation not found"))
		return
	}
	if c.cancel != nil {
		c.cancel()
	}
	respond(w, map[string]bool{"ok": true})
}
func (a *API) aiMessage(w http.ResponseWriter, r *http.Request) {
	var q struct{ Message, RequestID string }
	if !decode(w, r, &q) {
		return
	}
	if strings.TrimSpace(q.Message) == "" || len(q.Message) > 64<<10 || !threadPattern.MatchString(q.RequestID) {
		fail(w, 400, errors.New("send a message up to 64 KiB with a request ID"))
		return
	}
	a.ai.mu.Lock()
	defer a.ai.mu.Unlock()
	c := a.ai.chats[r.PathValue("id")]
	if c == nil {
		fail(w, 404, errors.New("conversation not found"))
		return
	}
	for _, m := range c.Messages {
		if m.Role == "user" && m.ID == q.RequestID {
			respond(w, c)
			return
		}
	}
	if a.ai.closed {
		fail(w, 503, errors.New("host is stopping"))
		return
	}
	if c.Running {
		fail(w, 409, errors.New("wait for this reply or stop it first"))
		return
	}
	for _, other := range a.ai.chats {
		if other.Running && (other.Project == c.Project || c.ThreadID != "" && other.Provider == c.Provider && other.ThreadID == c.ThreadID) {
			fail(w, 409, errors.New("another assistant is running in this workspace or conversation; stop it first"))
			return
		}
	}
	active := 0
	for _, other := range a.ai.chats {
		if other.Running {
			active++
		}
	}
	if active >= 4 {
		fail(w, 409, errors.New("four assistants are already running"))
		return
	}
	if len(c.Messages) >= 1500 {
		fail(w, 409, errors.New("chat display history is full; resume its conversation ID in a new chat"))
		return
	}
	dir, err := a.project(c.Project)
	if err != nil {
		fail(w, 400, err)
		return
	}
	env := a.toolEnvironment()
	path, err := toolPath(c.Provider, env)
	if err != nil {
		fail(w, 409, err)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	c.Running = true
	c.Status = "Starting " + c.Provider
	c.streamID = ""
	c.Error = ""
	c.cancel = cancel
	c.Messages = append(c.Messages, assistantMessage{ID: q.RequestID, Role: "user", Text: q.Message})
	if err = a.saveChat(c); err != nil {
		cancel()
		c.cancel = nil
		c.Running = false
		c.Messages = c.Messages[:len(c.Messages)-1]
		fail(w, 500, err)
		return
	}
	a.ai.wg.Add(1)
	go a.runAssistant(ctx, c, path, dir, env, q.Message, q.RequestID)
	respond(w, c)
}
func clip(s string, limit int) string {
	if len(s) > limit {
		return string([]rune(s[:limit])) + "\n[Output shortened]"
	}
	return s
}
func (a *API) runAssistant(ctx context.Context, c *assistantChat, path, dir string, env []string, prompt, turn string) {
	defer a.ai.wg.Done()
	args := []string{}
	if c.Provider == "codex" {
		args = []string{"-a", "never", "-s", c.Mode, "exec", "--json"}
		if c.ThreadID != "" {
			args = append(args, "resume", c.ThreadID)
		}
		args = append(args, "-")
	} else {
		mode := "acceptEdits"
		if c.Mode == "read-only" {
			mode = "plan"
		}
		args = []string{"--print", "--verbose", "--output-format", "stream-json", "--include-partial-messages", "--permission-mode", mode}
		if c.ThreadID != "" {
			args = append(args, "--resume", c.ThreadID)
		}
	}
	cmd := toolCommand(ctx, path, dir, env, args...)
	cmd.Stdin = strings.NewReader(prompt)
	var stderr limitedBuffer
	cmd.Stderr = &stderr
	stdout, err := cmd.StdoutPipe()
	if err == nil {
		err = cmd.Start()
	}
	received := false
	if err == nil {
		scanner := bufio.NewScanner(stdout)
		scanner.Buffer(make([]byte, 8192), 2<<20)
		for scanner.Scan() {
			var event map[string]any
			if json.Unmarshal(scanner.Bytes(), &event) != nil {
				continue
			}
			received = true
			a.assistantEvent(c, event, turn)
		}
		scanErr := scanner.Err()
		if scanErr != nil {
			_ = cmd.Cancel()
		}
		err = cmd.Wait()
		if scanErr != nil {
			err = scanErr
		}
	}
	a.ai.mu.Lock()
	defer a.ai.mu.Unlock()
	contextErr := ctx.Err()
	c.Running = false
	c.Status = ""
	c.cancel()
	c.cancel = nil
	if contextErr != nil && c.Error == "" {
		c.Error = "Reply stopped. The saved conversation can be resumed with your next message."
	} else if err != nil && c.Error == "" {
		details := clip(strings.TrimSpace(stderr.String()), 3000)
		if details == "" {
			details = err.Error()
		}
		c.Error = c.Provider + ": " + details
	} else if !received && c.Error == "" {
		c.Error = "The CLI returned no conversation events. Check its installation and sign-in on the host."
	}
	if e := a.saveChat(c); e != nil {
		c.Error = "Could not save conversation: " + e.Error()
	}
}
func (a *API) assistantEvent(c *assistantChat, event map[string]any, turn string) {
	a.ai.mu.Lock()
	defer a.ai.mu.Unlock()
	previousThread := c.ThreadID
	str := func(m map[string]any, k string) string { v, _ := m[k].(string); return v }
	put := func(id, role, text string) {
		if text == "" {
			return
		}
		text = clip(text, 128<<10)
		id = turn + ":" + id
		for i := range c.Messages {
			if c.Messages[i].ID == id {
				c.Messages[i].Text = text
				return
			}
		}
		if len(c.Messages) < 1800 {
			c.Messages = append(c.Messages, assistantMessage{ID: id, Role: role, Text: text})
		}
	}
	if c.Provider == "codex" {
		switch str(event, "type") {
		case "thread.started":
			c.Status = "Connected to Codex; waiting for its reply"
			id := str(event, "thread_id")
			if threadPattern.MatchString(id) {
				if c.ThreadID != "" && c.ThreadID != id {
					c.Error = "Codex returned a different conversation ID; reply stopped."
					c.cancel()
				} else {
					c.ThreadID = id
				}
			}
		case "item.started", "item.updated", "item.completed":
			item, _ := event["item"].(map[string]any)
			id := str(item, "id")
			typ := str(item, "type")
			switch typ {
			case "agent_message":
				c.Status = "Writing a reply"
				put(id, "assistant", str(item, "text"))
			case "command_execution":
				c.Status = "Running a command on the host"
				put(id, "tool", str(item, "command")+"\n"+str(item, "aggregated_output"))
			case "file_change":
				b, _ := json.Marshal(item["changes"])
				put(id, "tool", "File changes\n"+string(b))
			case "error":
				c.Error = str(item, "message")
			}
		case "turn.failed":
			e, _ := event["error"].(map[string]any)
			c.Error = str(e, "message")
		case "error":
			c.Error = str(event, "message")
		}
	} else {
		if id := str(event, "session_id"); threadPattern.MatchString(id) {
			if c.ThreadID != "" && c.ThreadID != id {
				c.Error = "Claude returned a different conversation ID; reply stopped."
				c.cancel()
			} else {
				c.ThreadID = id
			}
		}
		switch str(event, "type") {
		case "system":
			if str(event, "subtype") == "init" {
				c.Status = "Connected to Claude; waiting for its reply"
			}
			if str(event, "subtype") == "api_retry" {
				c.Status = "Claude is retrying the provider request"
			}
		case "stream_event":
			inner, _ := event["event"].(map[string]any)
			if str(inner, "type") == "message_start" {
				m, _ := inner["message"].(map[string]any)
				c.streamID = str(m, "id")
			}
			if str(inner, "type") == "content_block_delta" && c.streamID != "" {
				delta, _ := inner["delta"].(map[string]any)
				if text := str(delta, "text"); text != "" {
					id := c.streamID + fmt.Sprint(inner["index"])
					prior := ""
					for _, m := range c.Messages {
						if m.ID == turn+":"+id {
							prior = m.Text
							break
						}
					}
					put(id, "assistant", prior+text)
					c.Status = "Writing a reply"
				}
			}
		case "assistant":
			message, _ := event["message"].(map[string]any)
			blocks, _ := message["content"].([]any)
			for i, raw := range blocks {
				block, _ := raw.(map[string]any)
				id := str(message, "id") + fmt.Sprint(i)
				switch str(block, "type") {
				case "text":
					put(id, "assistant", str(block, "text"))
				case "tool_use":
					b, _ := json.Marshal(block["input"])
					put(id, "tool", str(block, "name")+"\n"+string(b))
				}
			}
		case "result":
			if bad, _ := event["is_error"].(bool); bad {
				c.Error = str(event, "result")
				if c.Error == "" {
					b, _ := json.Marshal(event["errors"])
					c.Error = string(b)
				}
			}
			if denials, ok := event["permission_denials"].([]any); ok && len(denials) > 0 {
				put("permissions", "notice", "Some tools need approval. Open this conversation in the host terminal to approve them, then continue here.")
			}
		}
	}
	if previousThread == c.ThreadID && time.Since(c.lastSave) < 500*time.Millisecond && c.Error == "" {
		return
	}
	if err := a.saveChat(c); err != nil {
		c.Error = "Could not save conversation: " + err.Error()
		c.cancel()
	}
}
func (a *API) aiProviders(w http.ResponseWriter, r *http.Request) {
	env := a.toolEnvironment()
	items := make([]map[string]any, 2)
	var wg sync.WaitGroup
	for i, provider := range []string{"codex", "claude"} {
		wg.Add(1)
		go func(i int, provider string) {
			defer wg.Done()
			item := map[string]any{"id": provider, "installed": false, "status": "Not installed", "ready": false}
			items[i] = item
			path, err := toolPath(provider, env)
			if err != nil {
				return
			}
			item["installed"] = true
			ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
			defer cancel()
			args := []string{"login", "status"}
			if provider == "claude" {
				args = []string{"auth", "status"}
			}
			cmd := toolCommand(ctx, path, a.root, env, args...)
			var output limitedBuffer
			cmd.Stdout = &output
			cmd.Stderr = &output
			err = cmd.Run()
			text := output.String()
			ready := err == nil
			if provider == "claude" {
				var v struct {
					LoggedIn bool `json:"loggedIn"`
				}
				if json.Unmarshal([]byte(text), &v) == nil {
					ready = v.LoggedIn
				} else {
					ready = false
				}
			}
			item["ready"] = ready
			if provider == "codex" && !ready && strings.Contains(strings.ToLower(text), "not logged in") && codexProviderConfigured(env) {
				item["ready"] = true
				item["status"] = "Using host provider configuration"
			} else if ready {
				item["status"] = "Using laptop login"
			} else if ctx.Err() != nil {
				item["status"] = "Status check timed out; try a message or open the CLI"
			} else {
				item["status"] = "Sign in on the host, or use Sign in below"
			}
		}(i, provider)
	}
	wg.Wait()
	respond(w, items)
}
