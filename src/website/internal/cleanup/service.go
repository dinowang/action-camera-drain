// Package cleanup verifies a complete NAS copy before deleting its Azure
// container source.
package cleanup

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/job"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
)

// ErrRemoteChanged means the container changed during local verification.
var ErrRemoteChanged = errors.New("remote container changed during verification")

// ErrContainerNotEmpty means verified blobs were removed, but new remote
// content appeared before the final container delete.
var ErrContainerNotEmpty = errors.New("container received new content during cleanup")

// VerificationError reports blobs whose NAS copy is not complete.
type VerificationError struct {
	Failures []Failure
}

func (e *VerificationError) Error() string {
	return fmt.Sprintf("%d blob(s) do not have a verified local copy", len(e.Failures))
}

// Failure describes one blob that prevents cloud deletion.
type Failure struct {
	BlobName string `json:"blob"`
	Reason   string `json:"reason"`
}

// Result summarizes a successful verified deletion.
type Result struct {
	Container     string `json:"container"`
	VerifiedFiles int    `json:"verifiedFiles"`
	VerifiedBytes int64  `json:"verifiedBytes"`
}

// Service owns the destructive cleanup workflow.
type Service struct {
	store azblob.Storage
	fs    *localfs.FS
	jobs  *job.Manager
}

func New(store azblob.Storage, fs *localfs.FS, jobs *job.Manager) *Service {
	return &Service{store: store, fs: fs, jobs: jobs}
}

// DeleteContainer performs fresh local verification, detects remote changes,
// and only then deletes the Azure container.
func (s *Service) DeleteContainer(ctx context.Context, containerName string) (Result, error) {
	release, err := s.jobs.ReserveDeletion(containerName)
	if err != nil {
		return Result{}, err
	}
	defer release()

	first, err := s.store.ListBlobs(ctx, containerName)
	if err != nil {
		return Result{}, fmt.Errorf("list container before cleanup: %w", err)
	}

	result := Result{Container: containerName, VerifiedFiles: len(first)}
	failures := make([]Failure, 0)
	for _, blob := range first {
		result.VerifiedBytes += blob.Size
		localPath, err := s.fs.LocalPath(containerName, blob.Name)
		if err != nil {
			failures = append(failures, Failure{
				BlobName: blob.Name,
				Reason:   err.Error(),
			})
			continue
		}
		decision := s.fs.VerifyForDeletion(
			localPath,
			localfs.RemoteIdentity{
				Size:       blob.Size,
				MtimeMeta:  blob.Metadata["mtime"],
				ETag:       blob.ETag,
				ContentMD5: blob.ContentMD5,
			},
		)
		if !decision.Skip {
			failures = append(failures, Failure{
				BlobName: blob.Name,
				Reason:   decision.Reason,
			})
		}
	}
	if len(failures) > 0 {
		return Result{}, &VerificationError{Failures: failures}
	}

	second, err := s.store.ListBlobs(ctx, containerName)
	if err != nil {
		return Result{}, fmt.Errorf("re-list container before cleanup: %w", err)
	}
	if !sameSnapshot(first, second) {
		return Result{}, ErrRemoteChanged
	}

	destructiveCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Minute)
	defer cancel()
	for _, blob := range first {
		if blob.ETag == "" {
			return Result{}, fmt.Errorf("blob %s has no ETag; cleanup cannot delete it safely", blob.Name)
		}
		if err := s.store.DeleteBlobIfMatch(
			destructiveCtx,
			containerName,
			blob.Name,
			blob.ETag,
		); err != nil {
			return Result{}, err
		}
	}
	remaining, err := s.store.ListBlobs(destructiveCtx, containerName)
	if err != nil {
		return Result{}, fmt.Errorf("confirm empty container before cleanup: %w", err)
	}
	if len(remaining) != 0 {
		return Result{}, ErrContainerNotEmpty
	}

	if err := s.store.DeleteContainer(destructiveCtx, containerName); err != nil {
		return Result{}, err
	}
	return result, nil
}

type snapshotItem struct {
	name  string
	size  int64
	mtime string
	md5   []byte
	etag  string
}

func sameSnapshot(a, b []azblob.BlobInfo) bool {
	if len(a) != len(b) {
		return false
	}
	left := snapshot(a)
	right := snapshot(b)
	for i := range left {
		if left[i].name != right[i].name ||
			left[i].size != right[i].size ||
			left[i].mtime != right[i].mtime ||
			left[i].etag != right[i].etag ||
			!bytes.Equal(left[i].md5, right[i].md5) {
			return false
		}
	}
	return true
}

func snapshot(blobs []azblob.BlobInfo) []snapshotItem {
	out := make([]snapshotItem, 0, len(blobs))
	for _, blob := range blobs {
		out = append(out, snapshotItem{
			name:  blob.Name,
			size:  blob.Size,
			mtime: blob.Metadata["mtime"],
			md5:   append([]byte(nil), blob.ContentMD5...),
			etag:  blob.ETag,
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].name < out[j].name })
	return out
}
