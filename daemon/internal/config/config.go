// Package config reads/writes the daemon's local state file, created once at registration.
package config

import (
	"encoding/json"
	"os"
	"path/filepath"
)

const DefaultPath = "/etc/clele-print-daemon/config.json"

type Config struct {
	BackendURL string `json:"backendUrl"`
	DaemonID   int64  `json:"daemonId"`
	APIKey     string `json:"apiKey"`
}

func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var c Config
	if err := json.Unmarshal(data, &c); err != nil {
		return nil, err
	}
	return &c, nil
}

// Save writes the config with 0600 permissions (the API key is a bearer secret).
func Save(path string, c *Config) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}
