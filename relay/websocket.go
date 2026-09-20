package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Binary WebSocket messages carry a byte stream; SSH provides authentication
// and encryption inside the HTTPS connection.
type webSocketConn struct {
	socket  *websocket.Conn
	reader  io.Reader
	writeMu sync.Mutex
}

func (c *webSocketConn) Read(p []byte) (int, error) {
	for {
		if c.reader == nil {
			kind, r, e := c.socket.NextReader()
			if e != nil {
				return 0, e
			}
			if kind != websocket.BinaryMessage {
				return 0, errors.New("binary WebSocket messages required")
			}
			c.reader = r
		}
		n, e := c.reader.Read(p)
		if e == io.EOF {
			c.reader = nil
			if n > 0 {
				return n, nil
			}
			continue
		}
		return n, e
	}
}
func (c *webSocketConn) Write(p []byte) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	total := 0
	for len(p) > 0 {
		n := min(len(p), 32768)
		if e := c.socket.WriteMessage(websocket.BinaryMessage, p[:n]); e != nil {
			return total, e
		}
		total += n
		p = p[n:]
	}
	return total, nil
}
func (c *webSocketConn) Close() error         { return c.socket.Close() }
func (c *webSocketConn) LocalAddr() net.Addr  { return c.socket.LocalAddr() }
func (c *webSocketConn) RemoteAddr() net.Addr { return c.socket.RemoteAddr() }
func (c *webSocketConn) SetReadDeadline(t time.Time) error {
	return c.socket.UnderlyingConn().SetReadDeadline(t)
}
func (c *webSocketConn) SetWriteDeadline(t time.Time) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	return c.socket.SetWriteDeadline(t)
}
func (c *webSocketConn) SetDeadline(t time.Time) error {
	if e := c.SetReadDeadline(t); e != nil {
		return e
	}
	return c.SetWriteDeadline(t)
}

func startWebSocket(ctx context.Context, address, phoneAddress string, accept func(net.Conn), registry *registry) (*http.Server, error) {
	if address == "" {
		return nil, nil
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return nil, err
	}
	up := websocket.Upgrader{HandshakeTimeout: 10 * time.Second, CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" }}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /discover", registry.discover)
	slots := make(chan struct{}, 32)
	upgrade := func(w http.ResponseWriter, r *http.Request) *webSocketConn {
		socket, err := up.Upgrade(w, r, nil)
		if err != nil {
			return nil
		}
		socket.SetReadLimit(64 << 10)
		return &webSocketConn{socket: socket}
	}
	mux.HandleFunc("GET /laptop", func(w http.ResponseWriter, r *http.Request) {
		if c := upgrade(w, r); c != nil {
			accept(c)
		}
	})
	mux.HandleFunc("GET /phone", func(w http.ResponseWriter, r *http.Request) {
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
		default:
			http.Error(w, "Too many connections", 429)
			return
		}
		target := phoneAddress
		if id := r.URL.Query().Get("host"); id != "" {
			target = registry.route(id, strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
			if target == "" {
				http.Error(w, "Host unavailable or pairing expired", 503)
				return
			}
		}
		socket, err := (&net.Dialer{Timeout: 5 * time.Second}).DialContext(r.Context(), "tcp", target)
		if err != nil && r.URL.Query().Get("host") == "" {
			if fallback := registry.singleRoute(); fallback != "" {
				socket, err = (&net.Dialer{Timeout: 5 * time.Second}).DialContext(r.Context(), "tcp", fallback)
			}
		}
		if err != nil {
			http.Error(w, "Laptop is offline", 503)
			return
		}
		defer socket.Close()
		c := upgrade(w, r)
		if c == nil {
			return
		}
		defer c.Close()
		stop := context.AfterFunc(ctx, func() { _ = c.Close(); _ = socket.Close() })
		defer stop()
		done := make(chan struct{}, 2)
		go func() { _, _ = io.Copy(socket, c); done <- struct{}{} }()
		go func() { _, _ = io.Copy(c, socket); done <- struct{}{} }()
		select {
		case <-done:
		case <-ctx.Done():
		}
	})
	server := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 15 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	go server.Serve(listener)
	go func() { <-ctx.Done(); _ = server.Close() }()
	return server, nil
}
