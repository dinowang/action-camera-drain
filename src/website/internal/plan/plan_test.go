package plan

import (
	"crypto/md5"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
	"time"
)

func TestBuild_PendingAndSkipped(t *testing.T) {
	d := t.TempDir()
	fs := localfs.New(d)

	// existing local file that matches blob "vids/exists.mp4"
	local := filepath.Join(d, "vids", "exists.mp4")
	_ = os.MkdirAll(filepath.Dir(local), 0o755)
	_ = os.WriteFile(local, []byte("12345"), 0o644)
	mt := time.Date(2024, 1, 2, 3, 4, 5, 0, time.UTC)
	_ = os.Chtimes(local, mt, mt)

	blobs := []azblob.BlobInfo{
		{Name: "exists.mp4", Size: 5, Metadata: map[string]string{"mtime": strconv.FormatInt(mt.UnixMilli(), 10)}},
		{Name: "new.mp4", Size: 100, Metadata: map[string]string{"mtime": "1700000000000"}},
		{Name: "no-meta.mp4", Size: 50, ETag: "etag-no-meta", Metadata: map[string]string{}},
	}
	sum, items, err := Build(fs, "vids", blobs)
	if err != nil {
		t.Fatal(err)
	}

	if sum.RemoteCount != 3 || sum.SkippedCount != 1 || sum.PendingCount != 2 {
		t.Fatalf("summary: %+v", sum)
	}
	if items[0].Status != StatusSkipped {
		t.Fatalf("exists.mp4 should be skipped, got %q (%s)", items[0].Status, items[0].SkipReason)
	}
	if items[1].Status != StatusPending {
		t.Fatalf("new.mp4 should be pending, got %q", items[1].Status)
	}
	if items[2].Status != StatusPending {
		t.Fatalf("no-meta.mp4 should be pending, got %q (%s)", items[2].Status, items[2].SkipReason)
	}
}

func TestBuildUsesReceiptAndMarksUnverifiedContent(t *testing.T) {
	d := t.TempDir()
	fs := localfs.New(d)
	verifiedPath := filepath.Join(d, "vids", "verified.mp4")
	verifyPath := filepath.Join(d, "vids", "verify.mp4")
	if err := os.MkdirAll(filepath.Dir(verifiedPath), 0o755); err != nil {
		t.Fatal(err)
	}
	content := []byte("video")
	if err := os.WriteFile(verifiedPath, content, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(verifyPath, content, 0o644); err != nil {
		t.Fatal(err)
	}
	sumMD5 := md5.Sum(content)
	remote := localfs.RemoteIdentity{
		Size:       int64(len(content)),
		ETag:       "etag-verified",
		ContentMD5: sumMD5[:],
	}
	if _, _, err := fs.VerifyOrReplace(verifiedPath, remote, func(io.Writer) error {
		return errors.New("unexpected download")
	}); err != nil {
		t.Fatal(err)
	}

	sum, items, err := Build(fs, "vids", []azblob.BlobInfo{
		{
			Name:       "verified.mp4",
			Size:       int64(len(content)),
			ETag:       remote.ETag,
			ContentMD5: sumMD5[:],
			Metadata:   map[string]string{},
		},
		{
			Name:     "verify.mp4",
			Size:     int64(len(content)),
			ETag:     "etag-verify",
			Metadata: map[string]string{},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if items[0].Status != StatusSkipped || items[1].Status != StatusVerify {
		t.Fatalf("unexpected statuses: %+v", items)
	}
	if sum.SkippedCount != 1 || sum.PendingCount != 1 || sum.VerifyCount != 1 {
		t.Fatalf("unexpected summary: %+v", sum)
	}
}

func TestBuildDoesNotTreatInvalidMtimeAsMissing(t *testing.T) {
	d := t.TempDir()
	fs := localfs.New(d)
	path := filepath.Join(d, "vids", "clip.mp4")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("video"), 0o644); err != nil {
		t.Fatal(err)
	}

	_, items, err := Build(fs, "vids", []azblob.BlobInfo{{
		Name:     "clip.mp4",
		Size:     5,
		ETag:     "etag-1",
		Metadata: map[string]string{"mtime": "invalid"},
	}})
	if err != nil {
		t.Fatal(err)
	}
	if items[0].Status != StatusPending || items[0].MtimeMeta != "invalid" {
		t.Fatalf("invalid mtime should remain an ordinary pending item: %+v", items[0])
	}
}

func TestBuildRejectsBlobPathTraversal(t *testing.T) {
	fs := localfs.New(t.TempDir())
	_, _, err := Build(fs, "vids", []azblob.BlobInfo{{
		Name:     "../outside.mp4",
		Size:     1,
		Metadata: map[string]string{"mtime": "1700000000000"},
	}})
	if err == nil {
		t.Fatal("expected unsafe blob path error")
	}
}

func TestParseMillis(t *testing.T) {
	if parseMillis("1700000000000") != 1700000000000 {
		t.Fatal("happy path failed")
	}
	if parseMillis("") != 0 {
		t.Fatal("empty should be 0")
	}
	if parseMillis("abc") != 0 {
		t.Fatal("garbage should be 0")
	}
}
