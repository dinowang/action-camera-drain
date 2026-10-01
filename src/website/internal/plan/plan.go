// Package plan computes the diff between remote container state and local
// disk: which blobs are already present (skipped), which need to be fetched.
package plan

import (
	"fmt"

	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
)

// ItemStatus is one of:
//
//	pending — needs download
//	verify  — same-size local file needs content verification
//	skipped — local identity matches remote
type ItemStatus string

const (
	StatusPending ItemStatus = "pending"
	StatusVerify  ItemStatus = "verify"
	StatusSkipped ItemStatus = "skipped"
)

// Item is one file in the diff.
type Item struct {
	Container   string
	BlobName    string
	LocalPath   string
	Size        int64
	MtimeMeta   string
	MtimeMillis int64 // 0 = unknown
	ETag        string
	ContentMD5  []byte
	Status      ItemStatus
	SkipReason  string // populated when Status==StatusSkipped or when not skipping; explains decision
}

// Summary is per-container counters.
type Summary struct {
	Container    string
	RemoteCount  int
	PendingCount int
	VerifyCount  int
	SkippedCount int
	PendingBytes int64
}

// Build computes the diff for one container.
func Build(
	fs *localfs.FS,
	containerName string,
	blobs []azblob.BlobInfo,
) (Summary, []Item, error) {
	sum := Summary{Container: containerName, RemoteCount: len(blobs)}
	items := make([]Item, 0, len(blobs))
	for _, b := range blobs {
		local, err := fs.LocalPath(containerName, b.Name)
		if err != nil {
			return Summary{}, nil, fmt.Errorf("unsafe blob path %q: %w", b.Name, err)
		}
		mtimeStr := b.Metadata["mtime"]
		remote := localfs.RemoteIdentity{
			Size:       b.Size,
			MtimeMeta:  mtimeStr,
			ETag:       b.ETag,
			ContentMD5: b.ContentMD5,
		}
		dec := fs.ShouldSkip(local, remote)
		item := Item{
			Container:   containerName,
			BlobName:    b.Name,
			LocalPath:   local,
			Size:        b.Size,
			MtimeMeta:   mtimeStr,
			MtimeMillis: parseMillis(mtimeStr),
			ETag:        b.ETag,
			ContentMD5:  append([]byte(nil), b.ContentMD5...),
			SkipReason:  dec.Reason,
		}
		if dec.Skip {
			item.Status = StatusSkipped
			sum.SkippedCount++
		} else {
			item.Status = StatusPending
			if dec.VerificationNeeded {
				item.Status = StatusVerify
				sum.VerifyCount++
			}
			sum.PendingCount++
			sum.PendingBytes += b.Size
		}
		items = append(items, item)
	}
	return sum, items, nil
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
