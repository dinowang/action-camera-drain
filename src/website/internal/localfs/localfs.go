// Package localfs handles all on-disk operations:
//   - mapping container + blob name to a local path under DownloadRoot
//   - probing whether a local file already matches the remote (size + mtime)
//   - atomic write via *.part sibling files
package localfs

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// SidecarSuffix is appended to the dotfile that records the intended mtime
// when the filesystem refuses chtimes (e.g., Docker Desktop bind mounts,
// SMB/CIFS mounts, NAS volumes with foreign owner uid).
const SidecarSuffix = ".actr-mtime"

// ErrChtimesUnsupported signals that the file landed on disk but the
// filesystem rejected chtimes. The intended mtime is preserved in a sidecar
// dotfile so future syncs can still skip via [FS.ShouldSkip].
var ErrChtimesUnsupported = errors.New("chtimes unsupported on this filesystem")

// FS is the local filesystem abstraction (root + helpers).
type FS struct {
	Root string
}

func New(root string) *FS { return &FS{Root: root} }

// LocalPath maps a container + blob name to an absolute path on disk.
//
// Layout: <root>/<container>/<blob name>
// The blob name may contain '/' which is preserved as subdirectories.
func (f *FS) LocalPath(containerName, blobName string) string {
	return filepath.Join(f.Root, containerName, blobName)
}

// SkipDecision describes whether a blob is already on disk.
type SkipDecision struct {
	Skip   bool
	Reason string
}

// ShouldSkip implements the Drain-mirror rule:
// local file exists with the same size and identical mtime metadata → skip.
//
// When the filesystem mtime does not match but a sidecar dotfile records the
// intended mtime (typical after a chtimes-unsupported run), we still skip —
// the sidecar is our source of truth in that case.
//
// If `mtime` metadata is missing on the blob, we never skip (re-download to be safe).
func (f *FS) ShouldSkip(localPath string, remoteSize int64, mtimeMeta string) SkipDecision {
	st, err := os.Stat(localPath)
	if err != nil {
		return SkipDecision{Skip: false, Reason: "no local copy"}
	}
	if st.IsDir() {
		return SkipDecision{Skip: false, Reason: "local path is a directory"}
	}
	if st.Size() != remoteSize {
		return SkipDecision{Skip: false, Reason: "size differs"}
	}
	if mtimeMeta == "" {
		return SkipDecision{Skip: false, Reason: "no mtime metadata"}
	}
	wantMs, err := strconv.ParseInt(mtimeMeta, 10, 64)
	if err != nil {
		return SkipDecision{Skip: false, Reason: "mtime metadata not int"}
	}
	if st.ModTime().UnixMilli() == wantMs {
		return SkipDecision{Skip: true, Reason: "size and mtime match"}
	}
	if side, ok := readSidecar(localPath); ok && side == wantMs {
		return SkipDecision{Skip: true, Reason: "size matches; mtime via sidecar"}
	}
	return SkipDecision{Skip: false, Reason: "mtime differs"}
}

// WriteAtomic downloads via writeFn into <localPath>.part, fsyncs, renames to
// localPath, then applies mtime if mtimeMillis > 0.
//
// On any download/fsync/rename error the .part file is removed so that the
// next attempt starts fresh (spec: "失敗就重抓，先抹除本地的檔案").
//
// If chtimes fails after a successful rename (foreign-owner filesystems),
// the intended mtime is persisted to a sidecar dotfile and
// [ErrChtimesUnsupported] is returned. Callers should treat that as a
// non-fatal warning: the file is on disk and idempotency is preserved via
// the sidecar.
//
// Parent directories are created on demand.
func (f *FS) WriteAtomic(localPath string, mtimeMillis int64, writeFn func(io.Writer) error) (err error) {
	if err := os.MkdirAll(filepath.Dir(localPath), 0o755); err != nil {
		return fmt.Errorf("mkdir: %w", err)
	}
	tmp := localPath + ".part"
	_ = os.Remove(tmp) // any stale .part is junk

	fp, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return fmt.Errorf("open part: %w", err)
	}
	cleanupOnFail := func() {
		_ = fp.Close()
		_ = os.Remove(tmp)
	}

	if err := writeFn(fp); err != nil {
		cleanupOnFail()
		return fmt.Errorf("write body: %w", err)
	}
	if err := fp.Sync(); err != nil {
		cleanupOnFail()
		return fmt.Errorf("fsync: %w", err)
	}
	if err := fp.Close(); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("close part: %w", err)
	}
	if err := os.Rename(tmp, localPath); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("rename: %w", err)
	}
	if mtimeMillis > 0 {
		t := time.UnixMilli(mtimeMillis)
		if err := os.Chtimes(localPath, t, t); err != nil {
			// Filesystem refuses chtimes — record intent in sidecar so the
			// skip rule keeps working on subsequent syncs.
			_ = writeSidecar(localPath, mtimeMillis)
			return ErrChtimesUnsupported
		}
		// chtimes succeeded; nuke any stale sidecar from previous runs.
		_ = os.Remove(sidecarPath(localPath))
	}
	return nil
}

// ErrAbort is returned by writeFn callers when they want WriteAtomic to clean
// up without wrapping into "write body" — useful for cancellations.
var ErrAbort = errors.New("aborted")

// sidecarPath returns the hidden dotfile next to localPath that records the
// intended mtime when the filesystem rejects chtimes.
func sidecarPath(localPath string) string {
	dir, base := filepath.Split(localPath)
	return filepath.Join(dir, "."+base+SidecarSuffix)
}

func writeSidecar(localPath string, millis int64) error {
	return os.WriteFile(sidecarPath(localPath), []byte(strconv.FormatInt(millis, 10)), 0o644)
}

func readSidecar(localPath string) (int64, bool) {
	b, err := os.ReadFile(sidecarPath(localPath))
	if err != nil {
		return 0, false
	}
	n, err := strconv.ParseInt(strings.TrimSpace(string(b)), 10, 64)
	if err != nil {
		return 0, false
	}
	return n, true
}
