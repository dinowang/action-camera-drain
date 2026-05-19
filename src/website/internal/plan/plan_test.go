package plan

import (
	"path/filepath"
	"strconv"
	"testing"

	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
	"os"
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
		{Name: "no-meta.mp4", Size: 50, Metadata: map[string]string{}},
	}
	sum, items := Build(fs, "vids", blobs)

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
