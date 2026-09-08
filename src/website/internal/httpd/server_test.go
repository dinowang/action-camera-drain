package httpd

import (
	"context"
	"encoding/json"
	"io"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"testing/fstest"
	"time"

	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/cleanup"
	"github.com/dinowang/action-camera-drain/src/website/internal/job"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
)

func TestDeleteContainerHandler(t *testing.T) {
	root := t.TempDir()
	lfs := localfs.New(root)
	mtime := time.Date(2026, 9, 8, 1, 2, 3, 0, time.UTC)
	path, err := lfs.LocalPath("camera", "clip.mp4")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("video"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, mtime, mtime); err != nil {
		t.Fatal(err)
	}
	blob := azblob.BlobInfo{
		Name:     "clip.mp4",
		Size:     5,
		ETag:     "etag-1",
		Metadata: map[string]string{"mtime": strconv.FormatInt(mtime.UnixMilli(), 10)},
	}
	store := &httpStorage{lists: [][]azblob.BlobInfo{{blob}, {blob}, {}}}
	handler := testServer(store, lfs)
	request := httptest.NewRequest(http.MethodDelete, "/api/containers/camera", nil)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	var body cleanup.Result
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Container != "camera" || store.deleted != "camera" {
		t.Fatalf("unexpected result=%+v deleted=%q", body, store.deleted)
	}
}

func TestDeleteContainerHandlerReturnsConflictForMissingLocalFile(t *testing.T) {
	lfs := localfs.New(t.TempDir())
	store := &httpStorage{lists: [][]azblob.BlobInfo{{
		{Name: "missing.mp4", Size: 5, Metadata: map[string]string{"mtime": "1788830000000"}},
	}}}
	handler := testServer(store, lfs)
	request := httptest.NewRequest(http.MethodDelete, "/api/containers/camera", nil)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusConflict {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	if store.deleted != "" {
		t.Fatal("container must not be deleted")
	}
}

func testServer(store azblob.Storage, lfs *localfs.FS) http.Handler {
	manager := job.NewManager(store, lfs, 1, 1)
	cleaner := cleanup.New(store, lfs, manager)
	frontend := fstest.MapFS{
		"index.html": &fstest.MapFile{Data: []byte("ok")},
	}
	var frontendFS fs.FS = frontend
	return New(store, lfs, manager, cleaner, frontendFS).Handler()
}

type httpStorage struct {
	lists     [][]azblob.BlobInfo
	listCalls int
	deleted   string
}

func (*httpStorage) ListContainers(context.Context) ([]string, error) {
	return []string{"camera"}, nil
}

func (s *httpStorage) ListBlobs(context.Context, string) ([]azblob.BlobInfo, error) {
	index := s.listCalls
	s.listCalls++
	if index >= len(s.lists) {
		return nil, nil
	}
	return s.lists[index], nil
}

func (*httpStorage) Download(context.Context, string, string, io.Writer) error {
	return nil
}

func (*httpStorage) DeleteBlobIfMatch(context.Context, string, string, string) error {
	return nil
}

func (s *httpStorage) DeleteContainer(_ context.Context, containerName string) error {
	s.deleted = containerName
	return nil
}
