package config

import (
	"testing"
)

func TestLoad_ConnectionString(t *testing.T) {
	t.Setenv("AZURE_STORAGE_CONNECTION_STRING", "DefaultEndpointsProtocol=https;AccountName=foo;AccountKey=bar")
	t.Setenv("DOWNLOAD_ROOT", "/tmp/test")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AzureMode != AzureModeConnectionString {
		t.Fatalf("mode: got %v", cfg.AzureMode)
	}
	if cfg.MinConcurrency != 2 || cfg.MaxConcurrency != 8 {
		t.Fatalf("defaults: %d / %d", cfg.MinConcurrency, cfg.MaxConcurrency)
	}
}

func TestLoad_SAS(t *testing.T) {
	t.Setenv("AZURE_STORAGE_ACCOUNT_NAME", "myacct")
	t.Setenv("AZURE_STORAGE_SAS_TOKEN", "?sv=2024-01-01&sp=r")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AzureMode != AzureModeSAS {
		t.Fatalf("mode: got %v", cfg.AzureMode)
	}
	if cfg.AzureSASToken != "sv=2024-01-01&sp=r" {
		t.Fatalf("SAS should be trimmed: %q", cfg.AzureSASToken)
	}
}

func TestLoad_NoAuth(t *testing.T) {
	t.Setenv("AZURE_STORAGE_CONNECTION_STRING", "")
	t.Setenv("AZURE_STORAGE_ACCOUNT_NAME", "")
	t.Setenv("AZURE_STORAGE_SAS_TOKEN", "")
	if _, err := Load(); err == nil {
		t.Fatal("expected error when no auth provided")
	}
}

func TestLoad_BadConcurrency(t *testing.T) {
	t.Setenv("AZURE_STORAGE_CONNECTION_STRING", "x")
	t.Setenv("MIN_CONCURRENCY", "5")
	t.Setenv("MAX_CONCURRENCY", "3")
	if _, err := Load(); err == nil {
		t.Fatal("expected error on inverted concurrency bounds")
	}
}
