package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

var identifier = regexp.MustCompile(`^[a-f0-9]{64}$`)

type hostRecord struct {
	HostID   string `json:"hostId"`
	PairID   string `json:"pairId"`
	Sealed   string `json:"sealed"`
	LastSeen int64  `json:"lastSeen"`
}
type registration struct {
	HostID    string `json:"hostId"`
	PairID    string `json:"pairId"`
	Sealed    string `json:"sealed"`
	PublicKey string `json:"publicKey"`
	Signature string `json:"signature"`
}
type liveHost struct {
	owner   *ssh.ServerConn
	address string
}
type registry struct {
	mu    sync.Mutex
	path  string
	hosts map[string]hostRecord
	live  map[string]liveHost
}

func loadRegistry(path string) (*registry, error) {
	r := &registry{path: path, hosts: map[string]hostRecord{}, live: map[string]liveHost{}}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return r, nil
	}
	if err != nil {
		return nil, err
	}
	if len(data) > 4<<20 {
		return nil, errors.New("relay registry exceeds size limit")
	}
	var records []hostRecord
	if err = json.Unmarshal(data, &records); err != nil {
		return nil, err
	}
	if len(records) > 128 {
		return nil, errors.New("too many registered hosts")
	}
	for _, h := range records {
		if !identifier.MatchString(h.HostID) || !identifier.MatchString(h.PairID) || len(h.Sealed) > 16384 {
			return nil, errors.New("invalid saved host record")
		}
		r.hosts[h.HostID] = h
	}
	return r, nil
}
func (r *registry) saveLocked() error {
	records := make([]hostRecord, 0, len(r.hosts))
	for _, h := range r.hosts {
		records = append(records, h)
	}
	data, err := json.Marshal(records)
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(r.path), 0700); err != nil {
		return err
	}
	if err = os.WriteFile(r.path+".tmp", data, 0600); err != nil {
		return err
	}
	return os.Rename(r.path+".tmp", r.path)
}
func (r *registry) register(conn *ssh.ServerConn, payload []byte) (string, error) {
	if len(payload) > 24576 {
		return "", errors.New("registration too large")
	}
	var request registration
	if json.Unmarshal(payload, &request) != nil || !identifier.MatchString(request.HostID) || !identifier.MatchString(request.PairID) || len(request.Sealed) > 16384 {
		return "", errors.New("invalid registration")
	}
	sealed, err := base64.StdEncoding.DecodeString(request.Sealed)
	if err != nil || len(sealed) < 29 {
		return "", errors.New("invalid encrypted profile")
	}
	raw, err := base64.StdEncoding.DecodeString(request.PublicKey)
	if err != nil {
		return "", err
	}
	key, err := ssh.ParsePublicKey(raw)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(key.Marshal())
	if hex.EncodeToString(digest[:]) != request.HostID {
		return "", errors.New("host identity mismatch")
	}
	signature, err := base64.StdEncoding.DecodeString(request.Signature)
	if err != nil {
		return "", err
	}
	var sig ssh.Signature
	if ssh.Unmarshal(signature, &sig) != nil {
		return "", errors.New("invalid host signature")
	}
	proof := append(append([]byte("outpost-register-v1\x00"), conn.SessionID()...), []byte(request.HostID+"\n"+request.PairID+"\n"+request.Sealed)...)
	if err = key.Verify(proof, &sig); err != nil {
		return "", errors.New("host proof rejected")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if live, ok := r.live[request.HostID]; ok && live.owner != conn {
		return "", errors.New("host already connected")
	}
	for id, h := range r.hosts {
		if id != request.HostID && h.PairID == request.PairID {
			return "", errors.New("pairing code is already used by another host")
		}
	}
	old, exists := r.hosts[request.HostID]
	if !exists && len(r.hosts) >= 128 {
		return "", errors.New("registered host limit reached")
	}
	r.hosts[request.HostID] = hostRecord{request.HostID, request.PairID, request.Sealed, time.Now().Unix()}
	if err = r.saveLocked(); err != nil {
		if exists {
			r.hosts[request.HostID] = old
		} else {
			delete(r.hosts, request.HostID)
		}
		return "", err
	}
	r.live[request.HostID] = liveHost{owner: conn}
	return request.HostID, nil
}
func (r *registry) online(id string, conn *ssh.ServerConn, address string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if h, ok := r.live[id]; ok && h.owner == conn {
		h.address = address
		r.live[id] = h
	}
}
func (r *registry) heartbeat(id string, conn *ssh.ServerConn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if h, ok := r.live[id]; ok && h.owner == conn {
		record := r.hosts[id]
		record.LastSeen = time.Now().Unix()
		r.hosts[id] = record
	}
}
func (r *registry) offline(id string, conn *ssh.ServerConn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if h, ok := r.live[id]; ok && h.owner == conn {
		delete(r.live, id)
		record := r.hosts[id]
		record.LastSeen = time.Now().Unix()
		r.hosts[id] = record
		_ = r.saveLocked()
	}
}
func (r *registry) route(id, credential string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	h, ok := r.hosts[id]
	if !ok || subtle.ConstantTimeCompare([]byte(h.PairID), []byte(credential)) != 1 {
		return ""
	}
	return r.live[id].address
}
func (r *registry) singleRoute() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.live) != 1 {
		return ""
	}
	for _, h := range r.live {
		return h.address
	}
	return ""
}
func (r *registry) discover(w http.ResponseWriter, req *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	req.Body = http.MaxBytesReader(w, req.Body, 8192)
	var input struct {
		IDs []string `json:"ids"`
	}
	if json.NewDecoder(req.Body).Decode(&input) != nil || len(input.IDs) == 0 || len(input.IDs) > 32 {
		http.Error(w, "Provide up to 32 pairing IDs", 400)
		return
	}
	allowed := map[string]bool{}
	for _, id := range input.IDs {
		if !identifier.MatchString(id) {
			http.Error(w, "Invalid pairing ID", 400)
			return
		}
		allowed[id] = true
	}
	type result struct {
		hostRecord
		Online bool `json:"online"`
	}
	results := []result{}
	r.mu.Lock()
	for id, h := range r.hosts {
		if allowed[h.PairID] {
			results = append(results, result{h, r.live[id].address != ""})
		}
	}
	r.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(results)
}
