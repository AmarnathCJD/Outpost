package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
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

func dialRelay(ctx context.Context, address string) (net.Conn, error) {
	rawURL := os.Getenv("WFY_RELAY_URL")
	if rawURL == "" {
		return (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 20 * time.Second}).DialContext(ctx, "tcp", address)
	}
	parsed, err := url.Parse(rawURL)
	if err != nil {
		return nil, err
	}
	ip := net.ParseIP(parsed.Hostname())
	if parsed.User != nil || parsed.Host == "" || (parsed.Scheme != "wss" && !(parsed.Scheme == "ws" && ip != nil && ip.IsLoopback())) {
		return nil, errors.New("Relay WebSocket URL must use wss:// (ws:// is allowed only on loopback)")
	}
	dialer := websocket.Dialer{Proxy: http.ProxyFromEnvironment, HandshakeTimeout: 10 * time.Second}
	socket, response, err := dialer.DialContext(ctx, rawURL, nil)
	if err != nil {
		if response != nil && response.Body != nil {
			response.Body.Close()
		}
		return nil, err
	}
	socket.SetReadLimit(64 << 10)
	return &webSocketConn{socket: socket}, nil
}
