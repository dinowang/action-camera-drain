package cleanup

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/job"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
)

func TestDeleteContainerVerifiesAndDeletes(t *testing.T) {
	root := t.TempDir()
	fs := localfs.New(root)
	mtime := time.Date(2026, 9, 8, 1, 2, 3, 0, time.UTC)
	writeVerifiedFile(t, mustLocalPath(t, fs, "camera", "clip.mp4"), []byte("video"), mtime)
	blob := azblob.BlobInfo{
		Name: "clip.mp4",
		Size: 5,
		ETag: "etag-1",
		Metadata: map[string]string{
			"mtime": strconv.FormatInt(mtime.UnixMilli(), 10),
		},
	}
	store := &cleanupStorage{listResponses: [][]azblob.BlobInfo{{blob}, {blob}, {}}}
	service := New(store, fs, job.NewManager(store, fs, 1, 1))

	result, err := service.DeleteContainer(context.Background(), "camera")
	if err != nil {
		t.Fatal(err)
	}
	if store.deleted != "camera" {
		t.Fatalf("deleted container = %q", store.deleted)
	}
	if len(store.deletedBlobs) != 1 || store.deletedBlobs[0] != "clip.mp4@etag-1" {
		t.Fatalf("unexpected conditional blob deletes: %v", store.deletedBlobs)
	}
	if result.VerifiedFiles != 1 || result.VerifiedBytes != 5 {
		t.Fatalf("unexpected result: %+v", result)
	}
}

func TestDeleteContainerRejectsIncompleteLocalCopy(t *testing.T) {
	root := t.TempDir()
	fs := localfs.New(root)
	blob := azblob.BlobInfo{
		Name:     "missing.mp4",
		Size:     5,
		Metadata: map[string]string{"mtime": "1788830000000"},
	}
	store := &cleanupStorage{listResponses: [][]azblob.BlobInfo{{blob}}}
	service := New(store, fs, job.NewManager(store, fs, 1, 1))

	_, err := service.DeleteContainer(context.Background(), "camera")

	var verificationErr *VerificationError
	if !errors.As(err, &verificationErr) {
		t.Fatalf("expected VerificationError, got %v", err)
	}
	if len(verificationErr.Failures) != 1 ||
		verificationErr.Failures[0].BlobName != "missing.mp4" {
		t.Fatalf("unexpected failures: %+v", verificationErr.Failures)
	}
	if store.deleted != "" {
		t.Fatalf("container must not be deleted")
	}
}

func TestDeleteContainerRejectsChangedRemoteSnapshot(t *testing.T) {
	root := t.TempDir()
	fs := localfs.New(root)
	mtime := time.Date(2026, 9, 8, 1, 2, 3, 0, time.UTC)
	writeVerifiedFile(t, mustLocalPath(t, fs, "camera", "clip.mp4"), []byte("video"), mtime)
	first := azblob.BlobInfo{
		Name:     "clip.mp4",
		Size:     5,
		ETag:     "etag-before",
		Metadata: map[string]string{"mtime": strconv.FormatInt(mtime.UnixMilli(), 10)},
	}
	second := first
	second.Size = 6
	store := &cleanupStorage{listResponses: [][]azblob.BlobInfo{{first}, {second}}}
	service := New(store, fs, job.NewManager(store, fs, 1, 1))

	_, err := service.DeleteContainer(context.Background(), "camera")

	if !errors.Is(err, ErrRemoteChanged) {
		t.Fatalf("expected ErrRemoteChanged, got %v", err)
	}
	if store.deleted != "" {
		t.Fatalf("container must not be deleted")
	}
}

func TestDeleteContainerRejectsSameSizeOverwriteDetectedByETag(t *testing.T) {
	root := t.TempDir()
	fs := localfs.New(root)
	mtime := time.Date(2026, 9, 8, 1, 2, 3, 0, time.UTC)
	writeVerifiedFile(t, mustLocalPath(t, fs, "camera", "clip.mp4"), []byte("video"), mtime)
	first := azblob.BlobInfo{
		Name:     "clip.mp4",
		Size:     5,
		ETag:     "etag-before",
		Metadata: map[string]string{"mtime": strconv.FormatInt(mtime.UnixMilli(), 10)},
	}
	second := first
	second.ETag = "etag-after"
	store := &cleanupStorage{listResponses: [][]azblob.BlobInfo{{first}, {second}}}
	service := New(store, fs, job.NewManager(store, fs, 1, 1))

	_, err := service.DeleteContainer(context.Background(), "camera")

	if !errors.Is(err, ErrRemoteChanged) {
		t.Fatalf("expected ErrRemoteChanged, got %v", err)
	}
	if store.deleted != "" {
		t.Fatal("container must not be deleted")
	}
}

func TestDeleteContainerKeepsContainerWhenNewBlobAppears(t *testing.T) {
	root := t.TempDir()
	fs := localfs.New(root)
	mtime := time.Date(2026, 9, 8, 1, 2, 3, 0, time.UTC)
	writeVerifiedFile(t, mustLocalPath(t, fs, "camera", "clip.mp4"), []byte("video"), mtime)
	verified := azblob.BlobInfo{
		Name:     "clip.mp4",
		Size:     5,
		ETag:     "etag-1",
		Metadata: map[string]string{"mtime": strconv.FormatInt(mtime.UnixMilli(), 10)},
	}
	newBlob := azblob.BlobInfo{
		Name:     "new.mp4",
		Size:     1,
		ETag:     "etag-2",
		Metadata: map[string]string{"mtime": "1788830000000"},
	}
	store := &cleanupStorage{
		listResponses: [][]azblob.BlobInfo{{verified}, {verified}, {newBlob}},
	}
	service := New(store, fs, job.NewManager(store, fs, 1, 1))

	_, err := service.DeleteContainer(context.Background(), "camera")

	if !errors.Is(err, ErrContainerNotEmpty) {
		t.Fatalf("expected ErrContainerNotEmpty, got %v", err)
	}
	if store.deleted != "" {
		t.Fatal("container with a newly arrived blob must not be deleted")
	}
}

func TestDeleteContainerPropagatesAzureDeleteFailure(t *testing.T) {
	root := t.TempDir()
	fs := localfs.New(root)
	store := &cleanupStorage{
		listResponses: [][]azblob.BlobInfo{{}, {}, {}},
		deleteErr:     errors.New("azure rejected delete"),
	}
	service := New(store, fs, job.NewManager(store, fs, 1, 1))

	_, err := service.DeleteContainer(context.Background(), "camera")

	if err == nil || err.Error() != "azure rejected delete" {
		t.Fatalf("unexpected error: %v", err)
	}
}

func writeVerifiedFile(t *testing.T, path string, content []byte, mtime time.Time) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, content, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, mtime, mtime); err != nil {
		t.Fatal(err)
	}
}

func mustLocalPath(t *testing.T, fs *localfs.FS, containerName, blobName string) string {
	t.Helper()
	path, err := fs.LocalPath(containerName, blobName)
	if err != nil {
		t.Fatal(err)
	}
	return path
}

type cleanupStorage struct {
	listResponses [][]azblob.BlobInfo
	listCalls     int
	deleted       string
	deletedBlobs  []string
	deleteErr     error
}

func (s *cleanupStorage) ListContainers(context.Context) ([]string, error) {
	return nil, nil
}

func (s *cleanupStorage) ListBlobs(context.Context, string) ([]azblob.BlobInfo, error) {
	index := s.listCalls
	s.listCalls++
	if index >= len(s.listResponses) {
		return nil, errors.New("unexpected list call")
	}
	return s.listResponses[index], nil
}

func (s *cleanupStorage) Download(context.Context, string, string, io.Writer) error {
	return errors.New("unexpected download")
}

func (s *cleanupStorage) DeleteBlobIfMatch(
	_ context.Context,
	_ string,
	blobName string,
	etag string,
) error {
	s.deletedBlobs = append(s.deletedBlobs, blobName+"@"+etag)
	return nil
}

func (s *cleanupStorage) DeleteContainer(_ context.Context, containerName string) error {
	s.deleted = containerName
	return s.deleteErr
}
