package main

import (
	"context"
	"crypto/subtle"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
)

func main() {
	token := os.Getenv("WFY_TOKEN")
	if file := os.Getenv("WFY_TOKEN_FILE"); file != "" {
		b, err := os.ReadFile(file)
		if err != nil {
			log.Fatal("Cannot read WFY_TOKEN_FILE: ", err)
		}
		token = strings.TrimSpace(string(b))
	}
	if len(os.Args) == 2 && os.Args[1] == "healthcheck" {
		client := &http.Client{Timeout: 4 * time.Second}
		address := os.Getenv("WFY_LISTEN")
		if address == "" {
			address = "127.0.0.1:8787"
		}
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			os.Exit(1)
		}
		if host == "" || host == "0.0.0.0" || host == "::" {
			host = "127.0.0.1"
		}
		req, err := http.NewRequest("GET", "http://"+net.JoinHostPort(host, port)+"/api/health", nil)
		if err != nil {
			os.Exit(1)
		}
		req.Header.Set("Authorization", "Bearer "+token)
		res, err := client.Do(req)
		if err != nil {
			os.Exit(1)
		}
		res.Body.Close()
		if res.StatusCode != http.StatusOK {
			os.Exit(1)
		}
		return
	}
	if len(token) < 32 {
		log.Fatal("WFY_TOKEN must contain at least 32 characters")
	}
	root := os.Getenv("WFY_ROOT")
	if root == "" {
		root = "/workspaces"
		if runtime.GOOS == "windows" {
			home, _ := os.UserHomeDir()
			root = filepath.Join(home, "Outpost", "workspaces")
		}
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		log.Fatal(err)
	}
	root, _ = filepath.Abs(root)
	api, err := NewAPI(root)
	if err != nil {
		log.Fatal(err)
	}
	defer api.Close()
	sshServer, err := startSSH(token)
	if err != nil {
		log.Fatal(err)
	}
	defer sshServer.Close()
	api.relay, err = startLaptopRelay(token)
	if err != nil {
		log.Fatal(err)
	}
	defer api.relay.Close()
	address := os.Getenv("WFY_LISTEN")
	if address == "" {
		address = "0.0.0.0:8787"
		if runtime.GOOS == "windows" {
			address = "127.0.0.1:8787"
		}
	}
	stop, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	api.stopHost = cancel
	srv := &http.Server{Addr: address, Handler: authenticated(token, api.routes()), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 90 * time.Second, MaxHeaderBytes: 16 << 10}
	shutdownDone := make(chan struct{})
	go func() {
		defer close(shutdownDone)
		<-stop.Done()
		ctx, done := context.WithTimeout(context.Background(), 10*time.Second)
		defer done()
		if err := srv.Shutdown(ctx); err != nil {
			_ = srv.Close()
		}
	}()
	log.Printf("Outpost listening on %s (%s)", address, runtime.GOOS)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
	// Shutdown closes listeners before draining requests. Keep the workspace open
	// until in-flight operations finish (or the shutdown deadline cancels them).
	<-shutdownDone
}

func authenticated(token string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+token)) != 1 {
			http.Error(w, "Unauthorized", http.StatusUnauthorized)
			return
		}
		// Browsers may not invoke this private API cross-origin, even with credentials.
		if origin := r.Header.Get("Origin"); origin != "" {
			http.Error(w, "Cross-origin requests forbidden", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}
