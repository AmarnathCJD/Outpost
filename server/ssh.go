package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/subtle"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/ssh"
)

// The embedded SSH endpoint carries authenticated app and service forwards.
// Interactive terminals remain managed by the API (ConPTY or tmux).
type sshEndpoint struct {
	listener    net.Listener
	cancel      context.CancelFunc
	mu          sync.Mutex
	connections map[net.Conn]bool
	closed      bool
}
type idleSSHConn struct {
	net.Conn
	authenticated atomic.Bool
}

func (c *idleSSHConn) Read(b []byte) (int, error) {
	if c.authenticated.Load() {
		_ = c.Conn.SetReadDeadline(time.Now().Add(3 * time.Minute))
	}
	return c.Conn.Read(b)
}
func startSSH(token string) (*sshEndpoint, error) {
	address := os.Getenv("WFY_SSH_LISTEN")
	if address == "" {
		return nil, nil
	}
	dir := os.Getenv("WFY_SSH_DIR")
	if dir == "" {
		return nil, errors.New("WFY_SSH_DIR is required for a persistent SSH identity")
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	keyFile := filepath.Join(dir, "host_ed25519")
	key, err := os.ReadFile(keyFile)
	if errors.Is(err, os.ErrNotExist) {
		_, private, genErr := ed25519.GenerateKey(rand.Reader)
		if genErr != nil {
			return nil, genErr
		}
		block, genErr := ssh.MarshalPrivateKey(private, "Outpost laptop")
		if genErr != nil {
			return nil, genErr
		}
		key = pem.EncodeToMemory(block)
		file, genErr := os.OpenFile(keyFile, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if genErr != nil {
			return nil, genErr
		}
		_, err = file.Write(key)
		closeErr := file.Close()
		if err == nil {
			err = closeErr
		}
	}
	if err != nil {
		return nil, err
	}
	signer, err := ssh.ParsePrivateKey(key)
	if err != nil {
		return nil, fmt.Errorf("read SSH host identity: %w", err)
	}
	if err = os.WriteFile(keyFile+".pub", ssh.MarshalAuthorizedKey(signer.PublicKey()), 0644); err != nil {
		return nil, err
	}
	user := os.Getenv("WFY_SSH_USER")
	if user == "" {
		user = "outpost"
	}
	config := &ssh.ServerConfig{
		MaxAuthTries: 3, ServerVersion: "SSH-2.0-Outpost_0.3.1",
		PasswordCallback: func(c ssh.ConnMetadata, password []byte) (*ssh.Permissions, error) {
			if c.User() != user || subtle.ConstantTimeCompare(password, []byte(token)) != 1 {
				return nil, errors.New("invalid Outpost SSH credentials")
			}
			return nil, nil
		},
		PublicKeyCallback: func(c ssh.ConnMetadata, supplied ssh.PublicKey) (*ssh.Permissions, error) {
			if c.User() != user {
				return nil, errors.New("invalid Outpost SSH user")
			}
			data, readErr := os.ReadFile(filepath.Join(dir, "authorized_keys"))
			if readErr != nil || len(data) > 1<<20 {
				return nil, errors.New("SSH public key is not authorized")
			}
			for len(data) > 0 {
				key, _, options, rest, parseErr := ssh.ParseAuthorizedKey(data)
				if parseErr != nil {
					break
				}
				data = rest
				// OpenSSH key options are not silently ignored by this endpoint.
				if len(options) == 0 && subtle.ConstantTimeCompare(key.Marshal(), supplied.Marshal()) == 1 {
					return nil, nil
				}
			}
			return nil, errors.New("SSH public key is not authorized")
		},
	}
	config.AddHostKey(signer)
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return nil, fmt.Errorf("listen for laptop SSH: %w", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	endpoint := &sshEndpoint{listener: listener, cancel: cancel, connections: map[net.Conn]bool{}}
	go endpoint.serve(ctx, config)
	return endpoint, nil
}
func (e *sshEndpoint) Close() {
	if e == nil {
		return
	}
	e.cancel()
	_ = e.listener.Close()
	e.mu.Lock()
	defer e.mu.Unlock()
	e.closed = true
	for connection := range e.connections {
		_ = connection.Close()
	}
}
func (e *sshEndpoint) serve(ctx context.Context, config *ssh.ServerConfig) {
	for {
		raw, err := e.listener.Accept()
		if err != nil {
			return
		}
		e.mu.Lock()
		if e.closed || len(e.connections) >= 32 {
			e.mu.Unlock()
			_ = raw.Close()
			continue
		}
		e.connections[raw] = true
		e.mu.Unlock()
		go func() {
			defer func() { _ = raw.Close(); e.mu.Lock(); delete(e.connections, raw); e.mu.Unlock() }()
			_ = raw.SetDeadline(time.Now().Add(10 * time.Second))
			connection := &idleSSHConn{Conn: raw}
			server, channels, requests, err := ssh.NewServerConn(connection, config)
			if err != nil {
				return
			}
			defer server.Close()
			connection.authenticated.Store(true)
			_ = raw.SetDeadline(time.Time{})
			_ = raw.SetReadDeadline(time.Now().Add(3 * time.Minute))
			connectionCtx, cancel := context.WithCancel(ctx)
			defer cancel()
			go func() {
				for request := range requests {
					_ = request.Reply(request.Type == "keepalive@openssh.com" || request.Type == "no-more-sessions@openssh.com", nil)
				}
			}()
			slots := make(chan struct{}, 32)
			for channel := range channels {
				if channel.ChannelType() != "direct-tcpip" {
					_ = channel.Reject(ssh.UnknownChannelType, "use Outpost Sessions for interactive terminals")
					continue
				}
				select {
				case slots <- struct{}{}:
					go func() { defer func() { <-slots }(); forwardSSH(connectionCtx, channel) }()
				default:
					_ = channel.Reject(ssh.ResourceShortage, "too many active forwards")
				}
			}
		}()
	}
}
func forwardSSH(ctx context.Context, request ssh.NewChannel) {
	var target struct {
		Host       string
		Port       uint32
		Origin     string
		OriginPort uint32
	}
	if ssh.Unmarshal(request.ExtraData(), &target) != nil || target.Port == 0 || target.Port > 65535 {
		_ = request.Reject(ssh.ConnectionFailed, "invalid destination")
		return
	}
	parsed := net.ParseIP(target.Host)
	if !strings.EqualFold(target.Host, "localhost") && (parsed == nil || !parsed.IsLoopback()) {
		_ = request.Reject(ssh.Prohibited, "Outpost forwards to laptop localhost only")
		return
	}
	// Resolve localhost explicitly to avoid DNS changing the permitted destination.
	if strings.EqualFold(target.Host, "localhost") {
		target.Host = "127.0.0.1"
	}
	dialer := net.Dialer{Timeout: 5 * time.Second}
	socket, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(target.Host, strconv.Itoa(int(target.Port))))
	if err != nil {
		_ = request.Reject(ssh.ConnectionFailed, "local service is not listening")
		return
	}
	defer socket.Close()
	channel, requests, err := request.Accept()
	if err != nil {
		return
	}
	defer channel.Close()
	go ssh.DiscardRequests(requests)
	done := make(chan error, 2)
	go func() {
		_, err := io.Copy(socket, channel)
		if tcp, ok := socket.(*net.TCPConn); ok {
			_ = tcp.CloseWrite()
		}
		done <- err
	}()
	go func() { _, err := io.Copy(channel, socket); _ = channel.CloseWrite(); done <- err }()
	for count := 0; count < 2; count++ {
		select {
		case err := <-done:
			if err != nil {
				return
			}
		case <-ctx.Done():
			return
		}
	}
}
