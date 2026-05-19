// Package plan computes the diff between remote container state and local
// disk: which blobs are already present (skipped), which need to be fetched.
package plan

import (
	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
)

// ItemStatus is one of:
//
//	pending — needs download
//	skipped — size+mtime match remote
type ItemStatus string

const (
	StatusPending ItemStatus = "pending"
	StatusSkipped ItemStatus = "skipped"
)

// Item is one file in the diff.
type Item struct {
	Container   string
	BlobName    string
	LocalPath   string
	Size        int64
	MtimeMillis int64 // 0 = unknown
	Status      ItemStatus
	SkipReason  string // populated when Status==StatusSkipped or when not skipping; explains decision
}

// Summary is per-container counters.
type Summary struct {
	Container    string
	RemoteCount  int
	PendingCount int
	SkippedCount int
	PendingBytes int64
}

// Build computes the diff for one container.
func Build(fs *localfs.FS, containerName string, blobs []azblob.BlobInfo) (Summary, []Item) {
	sum := Summary{Container: containerName, RemoteCount: len(blobs)}
	items := make([]Item, 0, len(blobs))
	for _, b := range blobs {
		local := fs.LocalPath(containerName, b.Name)
		mtimeStr := b.Metadata["mtime"]
		dec := fs.ShouldSkip(local, b.Size, mtimeStr)
		item := Item{
			Container:   containerName,
			BlobName:    b.Name,
			LocalPath:   local,
			Size:        b.Size,
			MtimeMillis: parseMillis(mtimeStr),
			SkipReason:  dec.Reason,
		}
		if dec.Skip {
			item.Status = StatusSkipped
			sum.SkippedCount++
		} else {
			item.Status = StatusPending
			sum.PendingCount++
			sum.PendingBytes += b.Size
		}
		items = append(items, item)
	}
	return sum, items
}

func parseMillis(s string) int64 {
	var n int64
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0
		}
		n = n*10 + int64(c-'0')
	}
	return n
}
