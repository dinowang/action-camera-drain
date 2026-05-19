// Package config loads and validates Action Camera Catch's runtime configuration.
//
// Two Azure auth modes are supported:
//   - Connection String (AZURE_STORAGE_CONNECTION_STRING)
//   - Account name + SAS token (AZURE_STORAGE_ACCOUNT_NAME + AZURE_STORAGE_SAS_TOKEN)
//
// The SAS token, when used, must be an account-level SAS with List Containers
// permission. Service SAS is not enough.
package config

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
)

// AzureMode selects which credential mode is in use.
type AzureMode int

const (
	AzureModeConnectionString AzureMode = iota
	AzureModeSAS
)

// Config is the validated runtime configuration.
type Config struct {
	AzureMode             AzureMode
	AzureConnectionString string
	AzureAccountName      string
	AzureSASToken         string

	DownloadRoot string
	HTTPPort     int

	MinConcurrency int
	MaxConcurrency int

	LogLevel string
}

// Load reads from environment variables and validates required fields.
func Load() (*Config, error) {
	cfg := &Config{
		DownloadRoot:   envOr("DOWNLOAD_ROOT", "/data"),
		HTTPPort:       envInt("HTTP_PORT", 8080),
		MinConcurrency: envInt("MIN_CONCURRENCY", 2),
		MaxConcurrency: envInt("MAX_CONCURRENCY", 8),
		LogLevel:       envOr("LOG_LEVEL", "info"),
	}

	conn := strings.TrimSpace(os.Getenv("AZURE_STORAGE_CONNECTION_STRING"))
	acct := strings.TrimSpace(os.Getenv("AZURE_STORAGE_ACCOUNT_NAME"))
	sas := strings.TrimSpace(os.Getenv("AZURE_STORAGE_SAS_TOKEN"))

	switch {
	case conn != "":
		cfg.AzureMode = AzureModeConnectionString
		cfg.AzureConnectionString = conn
	case acct != "" && sas != "":
		cfg.AzureMode = AzureModeSAS
		cfg.AzureAccountName = acct
		cfg.AzureSASToken = strings.TrimPrefix(sas, "?")
	default:
		return nil, errors.New(
			"missing Azure credentials: set AZURE_STORAGE_CONNECTION_STRING " +
				"or AZURE_STORAGE_ACCOUNT_NAME + AZURE_STORAGE_SAS_TOKEN")
	}

	if cfg.DownloadRoot == "" {
		return nil, errors.New("DOWNLOAD_ROOT must be a non-empty path")
	}
	if cfg.HTTPPort < 1 || cfg.HTTPPort > 65535 {
		return nil, fmt.Errorf("HTTP_PORT out of range: %d", cfg.HTTPPort)
	}
	if cfg.MinConcurrency < 1 {
		return nil, fmt.Errorf("MIN_CONCURRENCY must be >= 1, got %d", cfg.MinConcurrency)
	}
	if cfg.MaxConcurrency < cfg.MinConcurrency {
		return nil, fmt.Errorf("MAX_CONCURRENCY (%d) must be >= MIN_CONCURRENCY (%d)",
			cfg.MaxConcurrency, cfg.MinConcurrency)
	}

	return cfg, nil
}

// Redacted returns a string suitable for log output (no secrets).
func (c *Config) Redacted() string {
	auth := "<connection-string>"
	if c.AzureMode == AzureModeSAS {
		auth = fmt.Sprintf("sas(%s)", c.AzureAccountName)
	}
	return fmt.Sprintf("Catch{auth=%s, root=%s, port=%d, conc=[%d,%d], log=%s}",
		auth, c.DownloadRoot, c.HTTPPort, c.MinConcurrency, c.MaxConcurrency, c.LogLevel)
}

func envOr(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int) int {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}
