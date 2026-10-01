package azblob

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/service"
)

func TestDownloadIfMatchSendsETagCondition(t *testing.T) {
	var ifMatch string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ifMatch = r.Header.Get("If-Match")
		w.Header().Set("Content-Length", "5")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("video"))
	}))
	defer server.Close()

	svc, err := service.NewClientWithNoCredential(server.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	client := &Client{svc: svc}
	var body strings.Builder

	if err := client.DownloadIfMatch(
		context.Background(),
		"camera",
		"clip.mp4",
		`"etag-1"`,
		&body,
	); err != nil {
		t.Fatal(err)
	}
	if ifMatch != `"etag-1"` {
		t.Fatalf("If-Match = %q", ifMatch)
	}
	if body.String() != "video" {
		t.Fatalf("body=%q", body.String())
	}
}

func TestDownloadIfMatchRejectsMissingETag(t *testing.T) {
	client := &Client{}
	err := client.DownloadIfMatch(context.Background(), "camera", "clip.mp4", "", io.Discard)
	if err == nil || !strings.Contains(err.Error(), "missing ETag") {
		t.Fatalf("unexpected error: %v", err)
	}
}
