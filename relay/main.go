// Outpost relay carries encrypted phone SSH connections to an outbound-connected laptop.
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
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/crypto/ssh"
)

type idleConn struct {
	net.Conn
	authenticated atomic.Bool
}

func (c *idleConn) Read(b []byte) (int, error) {
	if c.authenticated.Load() {
		_ = c.SetReadDeadline(time.Now().Add(75 * time.Second))
	}
	return c.Conn.Read(b)
}
func env(name, fallback string) string {
	if s := os.Getenv(name); s != "" {
		return s
	}
	return fallback
}
func identity(path string) (ssh.Signer, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		if err = os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			return nil, err
		}
		_, key, e := ed25519.GenerateKey(rand.Reader)
		if e != nil {
			return nil, e
		}
		block, e := ssh.MarshalPrivateKey(key, "Outpost relay")
		if e != nil {
			return nil, e
		}
		data = pem.EncodeToMemory(block)
		f, e := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if e != nil {
			return nil, e
		}
		_, err = f.Write(data)
		e = f.Close()
		if err == nil {
			err = e
		}
	}
	if err != nil {
		return nil, err
	}
	return ssh.ParsePrivateKey(data)
}
func main() {
	signer, err := identity(env("OUTPOST_RELAY_IDENTITY", "/var/lib/outpost-relay/host_ed25519"))
	if err != nil {
		log.Fatal(err)
	}
	if len(os.Args) == 2 && os.Args[1] == "fingerprint" {
		fmt.Println(ssh.FingerprintSHA256(signer.PublicKey()))
		return
	}
	file := os.Getenv("OUTPOST_RELAY_TOKEN_FILE")
	token, err := os.ReadFile(file)
	if err != nil {
		log.Fatal("Cannot read OUTPOST_RELAY_TOKEN_FILE")
	}
	token = []byte(strings.TrimSpace(string(token)))
	if len(token) < 32 {
		log.Fatal("Relay token must contain at least 32 characters")
	}
	public := env("OUTPOST_RELAY_PUBLIC", "0.0.0.0:2223")
	host, portText, err := net.SplitHostPort(public)
	if err != nil {
		log.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		log.Fatal("Invalid public port")
	}
	config := &ssh.ServerConfig{ServerVersion: "SSH-2.0-OutpostRelay_0.4.0", MaxAuthTries: 3,
		PasswordCallback: func(c ssh.ConnMetadata, supplied []byte) (*ssh.Permissions, error) {
			if c.User() != "laptop" || subtle.ConstantTimeCompare(token, supplied) != 1 {
				return nil, errors.New("Invalid relay credentials")
			}
			return nil, nil
		},
	}
	config.AddHostKey(signer)
	listener, err := net.Listen("tcp", env("OUTPOST_RELAY_CONTROL", "0.0.0.0:8443"))
	if err != nil {
		log.Fatal(err)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	registry, err := loadRegistry(env("OUTPOST_RELAY_REGISTRY", filepath.Join(filepath.Dir(env("OUTPOST_RELAY_IDENTITY", "/var/lib/outpost-relay/host_ed25519")), "hosts.json")))
	if err != nil {
		log.Fatal("Cannot load relay registry: ", err)
	}
	var mu sync.Mutex
	clients := map[net.Conn]bool{}
	var wg sync.WaitGroup
	go func() {
		<-ctx.Done()
		_ = listener.Close()
		mu.Lock()
		for c := range clients {
			_ = c.Close()
		}
		mu.Unlock()
	}()
	log.Printf("Relay control listening on %s; phone port %s opens while the laptop is connected", listener.Addr(), public)
	accept := func(raw net.Conn) {
		mu.Lock()
		if ctx.Err() != nil || len(clients) >= 32 {
			mu.Unlock()
			_ = raw.Close()
			return
		}
		clients[raw] = true
		wg.Add(1)
		mu.Unlock()
		go func() {
			defer wg.Done()
			defer func() { _ = raw.Close(); mu.Lock(); delete(clients, raw); mu.Unlock() }()
			serve(ctx, raw, config, host, uint32(port), registry)
		}()
	}
	web, err := startWebSocket(ctx, os.Getenv("OUTPOST_RELAY_HTTP"), net.JoinHostPort("127.0.0.1", portText), accept, registry)
	if err != nil {
		log.Fatal(err)
	}
	if web != nil {
		defer web.Close()
	}
	for {
		raw, err := listener.Accept()
		if err != nil {
			break
		}
		accept(raw)
	}
	wg.Wait()
}
func serve(parent context.Context, raw net.Conn, config *ssh.ServerConfig, host string, port uint32, registry *registry) {
	_ = raw.SetDeadline(time.Now().Add(10 * time.Second))
	wrapped := &idleConn{Conn: raw}
	conn, channels, requests, err := ssh.NewServerConn(wrapped, config)
	if err != nil {
		return
	}
	defer conn.Close()
	wrapped.authenticated.Store(true)
	_ = raw.SetDeadline(time.Time{})
	_ = raw.SetReadDeadline(time.Now().Add(75 * time.Second))
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	go func() {
		for ch := range channels {
			_ = ch.Reject(ssh.Prohibited, "Relay accepts remote forwarding only")
		}
	}()
	var registered string
	defer func() {
		if registered != "" {
			registry.offline(registered, conn)
		}
	}()
	var forwarded net.Listener
	defer func() {
		if forwarded != nil {
			_ = forwarded.Close()
			log.Print("Laptop disconnected; phone forwarding closed")
		}
	}()
	for req := range requests {
		var address struct {
			Host string
			Port uint32
		}
		switch req.Type {
		case "outpost-register@v1":
			if registered != "" || forwarded != nil {
				_ = req.Reply(false, nil)
				continue
			}
			id, e := registry.register(conn, req.Payload)
			if e != nil {
				_ = req.Reply(false, nil)
				continue
			}
			registered = id
			_ = req.Reply(true, nil)
		case "keepalive@openssh.com":
			if registered != "" {
				registry.heartbeat(registered, conn)
			}
			_ = req.Reply(true, nil)
		case "tcpip-forward":
			valid := ssh.Unmarshal(req.Payload, &address) == nil && forwarded == nil
			if registered != "" {
				valid = valid && address.Host == "127.0.0.1" && address.Port == 0
			} else {
				valid = valid && address.Host == host && address.Port == port
			}
			if !valid {
				_ = req.Reply(false, nil)
				continue
			}
			forwarded, err = net.Listen("tcp", net.JoinHostPort(address.Host, strconv.Itoa(int(address.Port))))
			if err != nil {
				_ = req.Reply(false, nil)
				continue
			}
			actualPort := uint32(forwarded.Addr().(*net.TCPAddr).Port)
			var reply []byte
			if registered != "" {
				registry.online(registered, conn, forwarded.Addr().String())
				reply = ssh.Marshal(struct{ Port uint32 }{actualPort})
			}
			_ = req.Reply(true, reply)
			log.Print("Laptop connected; phone forwarding ready")
			go acceptPhones(ctx, forwarded, conn, address.Host, actualPort)
		case "cancel-tcpip-forward":
			expectedHost := host
			if registered != "" {
				expectedHost = "127.0.0.1"
			}
			valid := ssh.Unmarshal(req.Payload, &address) == nil && forwarded != nil && address.Host == expectedHost && address.Port == uint32(forwarded.Addr().(*net.TCPAddr).Port)
			if valid {
				_ = forwarded.Close()
				forwarded = nil
				if registered != "" {
					registry.online(registered, conn, "")
				}
			}
			_ = req.Reply(valid, nil)
		default:
			_ = req.Reply(false, nil)
		}
	}
}
func acceptPhones(ctx context.Context, listener net.Listener, conn *ssh.ServerConn, host string, port uint32) {
	slots := make(chan struct{}, 32)
	for {
		phone, err := listener.Accept()
		if err != nil {
			return
		}
		select {
		case slots <- struct{}{}:
		default:
			_ = phone.Close()
			continue
		}
		go func() {
			defer func() { _ = phone.Close(); <-slots }()
			origin, originPort, _ := net.SplitHostPort(phone.RemoteAddr().String())
			n, _ := strconv.Atoi(originPort)
			payload := ssh.Marshal(struct {
				Host       string
				Port       uint32
				Origin     string
				OriginPort uint32
			}{host, port, origin, uint32(n)})
			// A stalled laptop must not hold unauthenticated phone sockets forever.
			requestCtx, stop := context.WithTimeout(ctx, 10*time.Second)
			defer stop()
			type opened struct {
				channel  ssh.Channel
				requests <-chan *ssh.Request
				err      error
			}
			result := make(chan opened)
			go func() {
				ch, r, e := conn.OpenChannel("forwarded-tcpip", payload)
				select {
				case result <- opened{ch, r, e}:
				case <-requestCtx.Done():
					if ch != nil {
						_ = ch.Close()
					}
				}
			}()
			var ch ssh.Channel
			select {
			case r := <-result:
				if r.err != nil {
					return
				}
				ch = r.channel
				go ssh.DiscardRequests(r.requests)
			case <-requestCtx.Done():
				_ = conn.Close()
				return
			}
			defer ch.Close()
			done := make(chan error, 2)
			go func() { _, e := io.Copy(ch, phone); _ = ch.CloseWrite(); done <- e }()
			go func() {
				_, e := io.Copy(phone, ch)
				if tcp, ok := phone.(*net.TCPConn); ok {
					_ = tcp.CloseWrite()
				}
				done <- e
			}()
			for i := 0; i < 2; i++ {
				select {
				case e := <-done:
					if e != nil {
						return
					}
				case <-ctx.Done():
					return
				}
			}
		}()
	}
}
