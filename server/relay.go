package main

import (
	"context"
	"crypto/subtle"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

type laptopRelay struct {
	cancel    context.CancelFunc
	mu        sync.Mutex
	state     string
	lastError string
	done      chan struct{}
	pairing   *laptopPairing
}

func (r *laptopRelay) status() map[string]string {
	if r == nil {
		return map[string]string{"state": "disabled"}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	result := map[string]string{"state": r.state, "error": r.lastError}
	if r.pairing != nil {
		result["hostId"] = r.pairing.request.HostID
		result["discovery"] = "enabled"
	}
	return result
}
func (r *laptopRelay) set(state string, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.state = state
	r.lastError = ""
	if err != nil {
		r.lastError = err.Error()
	}
}
func (r *laptopRelay) Close() {
	if r != nil {
		r.cancel()
		<-r.done
	}
}
func startLaptopRelay(apiToken string) (*laptopRelay, error) {
	address := os.Getenv("WFY_RELAY_ADDRESS")
	if address == "" {
		return nil, nil
	}
	if _, _, err := net.SplitHostPort(address); err != nil {
		return nil, errors.New("WFY_RELAY_ADDRESS needs host:port")
	}
	token := os.Getenv("WFY_RELAY_TOKEN")
	fingerprint := os.Getenv("WFY_RELAY_FINGERPRINT")
	if len(token) < 32 || len(fingerprint) < 40 {
		return nil, errors.New("Configure the VPS relay token and SSH fingerprint")
	}
	publicPort, err := strconv.Atoi(os.Getenv("WFY_RELAY_PUBLIC_PORT"))
	if err != nil || publicPort < 1 || publicPort > 65535 {
		return nil, errors.New("Invalid VPS phone port")
	}
	_, sshPort, err := net.SplitHostPort(os.Getenv("WFY_SSH_LISTEN"))
	if err != nil {
		return nil, errors.New("Embedded laptop SSH is required for the VPS relay")
	}
	config := &ssh.ClientConfig{User: "laptop", Auth: []ssh.AuthMethod{ssh.Password(token)}, Timeout: 10 * time.Second,
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			if subtle.ConstantTimeCompare([]byte(fingerprint), []byte(ssh.FingerprintSHA256(key))) != 1 {
				return errors.New("VPS relay SSH fingerprint does not match")
			}
			return nil
		},
	}
	pairing, err := preparePairing(apiToken)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	r := &laptopRelay{cancel: cancel, state: "connecting", done: make(chan struct{}), pairing: pairing}
	go func() {
		defer close(r.done)
		backoff := time.Second
		for ctx.Err() == nil {
			r.set("connecting", nil)
			started := time.Now()
			err := r.run(ctx, address, net.JoinHostPort("127.0.0.1", sshPort), publicPort, config)
			if ctx.Err() != nil {
				break
			}
			r.set("retrying", err)
			if time.Since(started) > time.Minute {
				backoff = time.Second
			}
			timer := time.NewTimer(backoff)
			select {
			case <-timer.C:
			case <-ctx.Done():
				timer.Stop()
			}
			if backoff < 20*time.Second {
				backoff *= 2
			}
		}
		r.set("stopped", nil)
	}()
	return r, nil
}
func (r *laptopRelay) run(ctx context.Context, address, local string, port int, config *ssh.ClientConfig) error {
	raw, err := dialRelay(ctx, address)
	if err != nil {
		return fmt.Errorf("Reach VPS relay: %w", err)
	}
	defer raw.Close()
	stop := context.AfterFunc(ctx, func() { _ = raw.Close() })
	defer stop()
	_ = raw.SetDeadline(time.Now().Add(10 * time.Second))
	connection, channels, requests, err := ssh.NewClientConn(raw, address, config)
	if err != nil {
		return fmt.Errorf("VPS relay SSH: %w", err)
	}
	_ = raw.SetDeadline(time.Time{})
	client := ssh.NewClient(connection, channels, requests)
	defer client.Close()
	forwardAddress := net.JoinHostPort("0.0.0.0", strconv.Itoa(port))
	if r.pairing != nil {
		if err = r.pairing.register(client); err != nil {
			return err
		}
		forwardAddress = "127.0.0.1:0"
	}
	listener, err := client.Listen("tcp", forwardAddress)
	if err != nil {
		return errors.New("VPS rejected phone forwarding: check the configured phone port or another connected laptop")
	}
	defer listener.Close()
	r.set("connected", nil)
	connectionCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() {
		ticker := time.NewTicker(20 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-connectionCtx.Done():
				return
			case <-ticker.C:
				_ = raw.SetReadDeadline(time.Now().Add(15 * time.Second))
				ok, _, e := client.SendRequest("keepalive@openssh.com", true, nil)
				if e != nil || !ok {
					_ = client.Close()
					return
				}
				_ = raw.SetReadDeadline(time.Time{})
			}
		}
	}()
	var wg sync.WaitGroup
	defer wg.Wait()
	// Cancel active forward copies before waiting for them on return.
	defer cancel()
	for {
		incoming, err := listener.Accept()
		if err != nil {
			return errors.New("VPS connection interrupted; reconnecting")
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer incoming.Close()
			outgoing, e := (&net.Dialer{Timeout: 5 * time.Second}).DialContext(connectionCtx, "tcp", local)
			if e != nil {
				return
			}
			defer outgoing.Close()
			closed := context.AfterFunc(connectionCtx, func() { _ = outgoing.Close(); _ = incoming.Close() })
			defer closed()
			done := make(chan struct{}, 2)
			go func() {
				_, _ = io.Copy(outgoing, incoming)
				if tcp, ok := outgoing.(*net.TCPConn); ok {
					_ = tcp.CloseWrite()
				}
				done <- struct{}{}
			}()
			go func() {
				_, _ = io.Copy(incoming, outgoing)
				if half, ok := incoming.(interface{ CloseWrite() error }); ok {
					_ = half.CloseWrite()
				}
				done <- struct{}{}
			}()
			select {
			case <-done:
			case <-connectionCtx.Done():
				return
			}
			select {
			case <-done:
			case <-connectionCtx.Done():
			}
		}()
	}
}
