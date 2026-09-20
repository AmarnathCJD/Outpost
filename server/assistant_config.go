package main

import (
	"github.com/pelletier/go-toml/v2"
	"io"
	"os"
	"path/filepath"
)

// `codex login status` checks OpenAI login, not custom providers in config.toml.
// Only inspect configuration presence; credentials are never returned by this API.
func codexProviderConfigured(env []string) bool {
	home := envValue(env, "CODEX_HOME")
	if home == "" {
		user, _ := os.UserHomeDir()
		home = filepath.Join(user, ".codex")
	}
	f, err := os.Open(filepath.Join(home, "config.toml"))
	if err != nil {
		return false
	}
	defer f.Close()
	var cfg struct {
		Provider  string `toml:"model_provider"`
		Profile   string `toml:"profile"`
		Providers map[string]struct {
			EnvKey       string            `toml:"env_key"`
			RequiresAuth bool              `toml:"requires_openai_auth"`
			Token        string            `toml:"experimental_bearer_token"`
			Headers      map[string]string `toml:"http_headers"`
		} `toml:"model_providers"`
		Profiles map[string]struct {
			Provider string `toml:"model_provider"`
		} `toml:"profiles"`
	}
	if toml.NewDecoder(io.LimitReader(f, 2<<20)).Decode(&cfg) != nil {
		return false
	}
	name := cfg.Provider
	if profile := cfg.Profiles[cfg.Profile]; profile.Provider != "" {
		name = profile.Provider
	}
	if name == "" || name == "openai" {
		return envValue(env, "CODEX_API_KEY") != ""
	}
	provider, ok := cfg.Providers[name]
	if !ok || provider.RequiresAuth {
		return false
	}
	return provider.EnvKey == "" || envValue(env, provider.EnvKey) != "" || provider.Token != "" || len(provider.Headers) > 0
}
