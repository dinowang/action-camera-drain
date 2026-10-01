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
	"strings"
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

func TestJobDiscoveryAndTerminalCancellation(t *testing.T) {
	lfs := localfs.New(t.TempDir())
	store := &httpStorage{}
	manager := job.NewManager(store, lfs, 1, 1)
	cleaner := cleanup.New(store, lfs, manager)
	frontend := fstest.MapFS{
		"index.html": &fstest.MapFile{Data: []byte("ok")},
	}
	handler := New(store, lfs, manager, cleaner, frontend).Handler()
	started, err := manager.Start(context.Background(), []string{"camera"})
	if err != nil {
		t.Fatal(err)
	}
	waitForTerminalSnapshot(t, manager, started.ID)

	listRequest := httptest.NewRequest(http.MethodGet, "/api/jobs", nil)
	listResponse := httptest.NewRecorder()
	handler.ServeHTTP(listResponse, listRequest)
	if listResponse.Code != http.StatusOK {
		t.Fatalf("list status = %d", listResponse.Code)
	}
	var list []job.Snapshot
	if err := json.NewDecoder(listResponse.Body).Decode(&list); err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 || list[0].ID != started.ID || list[0].State != job.StateDone {
		t.Fatalf("unexpected job list: %+v", list)
	}

	detailRequest := httptest.NewRequest(http.MethodGet, "/api/jobs/"+started.ID, nil)
	detailResponse := httptest.NewRecorder()
	handler.ServeHTTP(detailResponse, detailRequest)
	if detailResponse.Code != http.StatusOK {
		t.Fatalf("detail status = %d", detailResponse.Code)
	}

	cancelRequest := httptest.NewRequest(http.MethodDelete, "/api/jobs/"+started.ID, nil)
	cancelResponse := httptest.NewRecorder()
	handler.ServeHTTP(cancelResponse, cancelRequest)
	if cancelResponse.Code != http.StatusConflict {
		t.Fatalf("terminal cancel status = %d", cancelResponse.Code)
	}

	eventsRequest := httptest.NewRequest(
		http.MethodGet,
		"/api/jobs/"+started.ID+"/events",
		nil,
	)
	eventsResponse := httptest.NewRecorder()
	handler.ServeHTTP(eventsResponse, eventsRequest)
	if eventsResponse.Code != http.StatusOK ||
		!strings.Contains(eventsResponse.Body.String(), "event: job-done") {
		t.Fatalf(
			"terminal SSE replay status=%d body=%q",
			eventsResponse.Code,
			eventsResponse.Body.String(),
		)
	}
}

func TestStartJobReturnsConflictForReservedContainer(t *testing.T) {
	lfs := localfs.New(t.TempDir())
	store := &httpStorage{}
	manager := job.NewManager(store, lfs, 1, 1)
	release, err := manager.ReserveDeletion("camera")
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	cleaner := cleanup.New(store, lfs, manager)
	frontend := fstest.MapFS{
		"index.html": &fstest.MapFile{Data: []byte("ok")},
	}
	handler := New(store, lfs, manager, cleaner, frontend).Handler()
	request := httptest.NewRequest(
		http.MethodPost,
		"/api/jobs",
		strings.NewReader(`{"containers":["camera"]}`),
	)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusConflict {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
}

func TestClearTerminalJobRecords(t *testing.T) {
	lfs := localfs.New(t.TempDir())
	store := &httpStorage{}
	manager := job.NewManager(store, lfs, 1, 1)
	cleaner := cleanup.New(store, lfs, manager)
	frontend := fstest.MapFS{
		"index.html": &fstest.MapFile{Data: []byte("ok")},
	}
	handler := New(store, lfs, manager, cleaner, frontend).Handler()
	first, err := manager.Start(context.Background(), []string{"camera"})
	if err != nil {
		t.Fatal(err)
	}
	waitForTerminalSnapshot(t, manager, first.ID)
	second, err := manager.Start(context.Background(), []string{"secondary"})
	if err != nil {
		t.Fatal(err)
	}
	waitForTerminalSnapshot(t, manager, second.ID)

	clearOneRequest := httptest.NewRequest(
		http.MethodDelete,
		"/api/jobs/"+first.ID+"/record",
		nil,
	)
	clearOneResponse := httptest.NewRecorder()
	handler.ServeHTTP(clearOneResponse, clearOneRequest)
	if clearOneResponse.Code != http.StatusNoContent {
		t.Fatalf("single clear status = %d", clearOneResponse.Code)
	}

	clearAllRequest := httptest.NewRequest(
		http.MethodDelete,
		"/api/jobs?state=terminal",
		nil,
	)
	clearAllResponse := httptest.NewRecorder()
	handler.ServeHTTP(clearAllResponse, clearAllRequest)
	if clearAllResponse.Code != http.StatusOK {
		t.Fatalf("bulk clear status = %d", clearAllResponse.Code)
	}
	var result struct {
		Cleared int `json:"cleared"`
	}
	if err := json.NewDecoder(clearAllResponse.Body).Decode(&result); err != nil {
		t.Fatal(err)
	}
	if result.Cleared != 1 {
		t.Fatalf("cleared = %d", result.Cleared)
	}
}

func TestClearRunningJobRecordReturnsConflict(t *testing.T) {
	lfs := localfs.New(t.TempDir())
	store := &blockingHTTPStorage{
		started: make(chan struct{}),
		release: make(chan struct{}),
	}
	manager := job.NewManager(store, lfs, 1, 1)
	cleaner := cleanup.New(store, lfs, manager)
	frontend := fstest.MapFS{
		"index.html": &fstest.MapFile{Data: []byte("ok")},
	}
	handler := New(store, lfs, manager, cleaner, frontend).Handler()
	started, err := manager.Start(context.Background(), []string{"camera"})
	if err != nil {
		t.Fatal(err)
	}
	<-store.started

	request := httptest.NewRequest(
		http.MethodDelete,
		"/api/jobs/"+started.ID+"/record",
		nil,
	)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusConflict {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}

	close(store.release)
	waitForTerminalSnapshot(t, manager, started.ID)
}

func TestClearMissingJobRecordReturnsNotFound(t *testing.T) {
	handler := testServer(&httpStorage{}, localfs.New(t.TempDir()))
	request := httptest.NewRequest(
		http.MethodDelete,
		"/api/jobs/missing/record",
		nil,
	)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusNotFound {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
}

func waitForTerminalSnapshot(t *testing.T, manager *job.Manager, id string) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		snapshot, ok := manager.GetSnapshot(id)
		if ok && snapshot.State != job.StateQueued && snapshot.State != job.StateRunning {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("job did not reach a terminal state")
}

type blockingHTTPStorage struct {
	started chan struct{}
	release chan struct{}
}

func (*blockingHTTPStorage) ListContainers(context.Context) ([]string, error) {
	return []string{"camera"}, nil
}

func (s *blockingHTTPStorage) ListBlobs(ctx context.Context, _ string) ([]azblob.BlobInfo, error) {
	close(s.started)
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-s.release:
		return nil, nil
	}
}

func (*blockingHTTPStorage) Download(context.Context, string, string, io.Writer) error {
	return nil
}

func (*blockingHTTPStorage) DeleteBlobIfMatch(context.Context, string, string, string) error {
	return nil
}

func (*blockingHTTPStorage) DeleteContainer(context.Context, string) error {
	return nil
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
