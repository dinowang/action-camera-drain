// Package localfs handles all on-disk operations:
//   - mapping container + blob name to a local path under DownloadRoot
//   - probing whether a local file already matches the remote (size + mtime)
//   - atomic write via *.part sibling files
package localfs

import (
	"bytes"
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"hash"
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

// ReceiptSuffix is appended to the hidden JSON sidecar that proves a
// no-mtime blob matched local content at a specific remote ETag.
const ReceiptSuffix = ".actr-verified.json"

const receiptVersion = 1

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
func (f *FS) LocalPath(containerName, blobName string) (string, error) {
	if containerName == "" || blobName == "" {
		return "", errors.New("container and blob names are required")
	}
	if strings.Contains(blobName, "\\") {
		return "", errors.New("blob name contains an unsafe path separator")
	}
	root := filepath.Clean(f.Root)
	base := filepath.Join(root, containerName)
	containerRelative, err := filepath.Rel(root, base)
	if err != nil {
		return "", fmt.Errorf("resolve container path: %w", err)
	}
	if containerRelative == "." || containerRelative == ".." ||
		strings.Contains(containerRelative, string(filepath.Separator)) {
		return "", errors.New("container name escapes the download root")
	}
	target := filepath.Join(base, filepath.FromSlash(blobName))
	relative, err := filepath.Rel(base, target)
	if err != nil {
		return "", fmt.Errorf("resolve local path: %w", err)
	}
	if relative == "." || relative == ".." ||
		strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", errors.New("blob name escapes its container directory")
	}
	if err := rejectSymlinkComponents(base, relative); err != nil {
		return "", err
	}
	return target, nil
}

func rejectSymlinkComponents(root, relative string) error {
	current := filepath.Clean(root)
	if info, err := os.Lstat(current); err == nil {
		if info.Mode()&os.ModeSymlink != 0 {
			return errors.New("container directory is a symlink")
		}
	} else if !os.IsNotExist(err) {
		return fmt.Errorf("inspect container directory: %w", err)
	}
	for _, component := range strings.Split(relative, string(filepath.Separator)) {
		current = filepath.Join(current, component)
		info, err := os.Lstat(current)
		if os.IsNotExist(err) {
			return nil
		}
		if err != nil {
			return fmt.Errorf("inspect local path: %w", err)
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("local path contains symlink component %q", component)
		}
	}
	return nil
}

// SkipDecision describes whether a blob is already on disk.
type SkipDecision struct {
	Skip               bool
	VerificationNeeded bool
	Reason             string
}

// RemoteIdentity contains the immutable blob properties used by local
// verification. ContentMD5 is optional because Azure blobs need not provide it.
type RemoteIdentity struct {
	Size       int64
	MtimeMeta  string
	ETag       string
	ContentMD5 []byte
}

// VerificationOutcome describes how a no-mtime file was resolved.
type VerificationOutcome string

const (
	OutcomeVerifiedExisting VerificationOutcome = "content verified; kept existing local file"
	OutcomeDownloaded       VerificationOutcome = "downloaded missing local file"
	OutcomeReplaced         VerificationOutcome = "local content differed; replaced from Azure"
)

type verificationReceipt struct {
	Version            int    `json:"version"`
	RemoteETag         string `json:"remoteEtag"`
	RemoteSize         int64  `json:"remoteSize"`
	DigestAlgorithm    string `json:"digestAlgorithm"`
	DigestHex          string `json:"digestHex"`
	LocalSize          int64  `json:"localSize"`
	LocalMtimeUnixNano int64  `json:"localMtimeUnixNano"`
}

// ShouldSkip implements the Drain-mirror rule:
// local file exists with the same size and identical mtime metadata → skip.
//
// When the filesystem mtime does not match but a sidecar dotfile records the
// intended mtime (typical after a chtimes-unsupported run), we still skip —
// the sidecar is our source of truth in that case.
//
// If `mtime` metadata is missing, a receipt may provide a fast skip after a
// previous byte-for-byte verification.
func (f *FS) ShouldSkip(localPath string, remote RemoteIdentity) SkipDecision {
	st, err := regularFileInfo(localPath)
	if err != nil {
		return SkipDecision{Skip: false, Reason: "no local copy"}
	}
	if st.Size() != remote.Size {
		return SkipDecision{Skip: false, Reason: "size differs"}
	}
	if remote.MtimeMeta == "" {
		if receipt, ok := readReceipt(localPath); ok &&
			receiptMatches(receipt, remote, st) {
			return SkipDecision{Skip: true, Reason: "content verified by receipt"}
		}
		return SkipDecision{
			VerificationNeeded: true,
			Reason:             "content verification required",
		}
	}
	wantMs, err := strconv.ParseInt(remote.MtimeMeta, 10, 64)
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

// VerifyForDeletion is deliberately stricter than ShouldSkip: a sidecar can
// preserve sync idempotency, but it cannot prove that an external process did
// not later modify a same-size file.
func (f *FS) VerifyForDeletion(
	localPath string,
	remote RemoteIdentity,
) SkipDecision {
	st, err := regularFileInfo(localPath)
	if err != nil {
		return SkipDecision{Skip: false, Reason: "no local copy"}
	}
	if st.Size() != remote.Size {
		return SkipDecision{Skip: false, Reason: "size differs"}
	}
	if remote.MtimeMeta == "" {
		receipt, ok := readReceipt(localPath)
		if !ok || !receiptMatches(receipt, remote, st) {
			return SkipDecision{Skip: false, Reason: "valid content verification receipt required"}
		}
		digest, err := hashFile(localPath, receipt.DigestAlgorithm)
		if err != nil {
			return SkipDecision{Skip: false, Reason: "hash local file: " + err.Error()}
		}
		if !strings.EqualFold(hex.EncodeToString(digest), receipt.DigestHex) {
			return SkipDecision{Skip: false, Reason: "local content differs from verification receipt"}
		}
		return SkipDecision{Skip: true, Reason: "content receipt and local digest match"}
	}
	wantMs, err := strconv.ParseInt(remote.MtimeMeta, 10, 64)
	if err != nil {
		return SkipDecision{Skip: false, Reason: "mtime metadata not int"}
	}
	if st.ModTime().UnixMilli() != wantMs {
		if side, ok := readSidecar(localPath); ok && side == wantMs {
			return SkipDecision{
				Skip:   false,
				Reason: "filesystem mtime differs; sidecar-only verification cannot authorize cloud deletion",
			}
		}
		return SkipDecision{Skip: false, Reason: "mtime differs"}
	}
	return SkipDecision{Skip: true, Reason: "size and filesystem mtime match"}
}

// VerifyOrReplace proves a no-mtime blob's content. It preserves an identical
// local file, otherwise atomically installs the conditionally downloaded blob.
// The returned byte count is actual network transfer, not logical progress.
func (f *FS) VerifyOrReplace(
	localPath string,
	remote RemoteIdentity,
	writeFn func(io.Writer) error,
) (VerificationOutcome, int64, error) {
	if remote.ETag == "" {
		return "", 0, errors.New("content verification requires remote ETag")
	}
	if err := os.MkdirAll(filepath.Dir(localPath), 0o755); err != nil {
		return "", 0, fmt.Errorf("mkdir: %w", err)
	}

	if len(remote.ContentMD5) > 0 {
		if st, err := regularFileInfo(localPath); err == nil && st.Size() == remote.Size {
			localDigest, err := hashFile(localPath, "md5")
			if err != nil {
				return "", 0, fmt.Errorf("hash local file: %w", err)
			}
			if bytes.Equal(localDigest, remote.ContentMD5) {
				if err := writeReceipt(localPath, remote, "md5", localDigest); err != nil {
					return "", 0, err
				}
				return OutcomeVerifiedExisting, 0, nil
			}
		}
	}

	algorithm := "sha256"
	if len(remote.ContentMD5) > 0 {
		algorithm = "md5"
	}
	tmp := localPath + ".verify.part"
	_ = os.Remove(tmp)
	fp, err := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return "", 0, fmt.Errorf("open verification part: %w", err)
	}
	hasher, err := newHasher(algorithm)
	if err != nil {
		_ = fp.Close()
		_ = os.Remove(tmp)
		return "", 0, err
	}
	counter := &countingWriter{}
	writer := io.MultiWriter(fp, hasher, counter)
	cleanup := func() {
		_ = fp.Close()
		_ = os.Remove(tmp)
	}
	if err := writeFn(writer); err != nil {
		cleanup()
		return "", counter.n, fmt.Errorf("write verification body: %w", err)
	}
	if counter.n != remote.Size {
		cleanup()
		return "", counter.n, fmt.Errorf(
			"downloaded size differs: got %d want %d",
			counter.n,
			remote.Size,
		)
	}
	downloadDigest := hasher.Sum(nil)
	if len(remote.ContentMD5) > 0 && !bytes.Equal(downloadDigest, remote.ContentMD5) {
		cleanup()
		return "", counter.n, errors.New("downloaded content does not match Azure Content-MD5")
	}
	if err := fp.Sync(); err != nil {
		cleanup()
		return "", counter.n, fmt.Errorf("fsync verification part: %w", err)
	}
	if err := fp.Close(); err != nil {
		_ = os.Remove(tmp)
		return "", counter.n, fmt.Errorf("close verification part: %w", err)
	}

	if st, err := regularFileInfo(localPath); err == nil && st.Size() == remote.Size {
		localDigest, err := hashFile(localPath, algorithm)
		if err != nil {
			_ = os.Remove(tmp)
			return "", counter.n, fmt.Errorf("hash local file: %w", err)
		}
		if bytes.Equal(localDigest, downloadDigest) {
			_ = os.Remove(tmp)
			if err := writeReceipt(localPath, remote, algorithm, localDigest); err != nil {
				return "", counter.n, err
			}
			return OutcomeVerifiedExisting, counter.n, nil
		}
	}

	outcome := OutcomeReplaced
	if _, err := os.Lstat(localPath); os.IsNotExist(err) {
		outcome = OutcomeDownloaded
	}
	if err := os.Rename(tmp, localPath); err != nil {
		_ = os.Remove(tmp)
		return "", counter.n, fmt.Errorf("rename verified content: %w", err)
	}
	_ = os.Remove(sidecarPath(localPath))
	if err := writeReceipt(localPath, remote, algorithm, downloadDigest); err != nil {
		return "", counter.n, err
	}
	return outcome, counter.n, nil
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
	_ = os.Remove(receiptPath(localPath))
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

func receiptPath(localPath string) string {
	dir, base := filepath.Split(localPath)
	return filepath.Join(dir, "."+base+ReceiptSuffix)
}

func writeReceipt(
	localPath string,
	remote RemoteIdentity,
	algorithm string,
	digest []byte,
) error {
	st, err := regularFileInfo(localPath)
	if err != nil {
		return fmt.Errorf("stat verified local file: %w", err)
	}
	receipt := verificationReceipt{
		Version:            receiptVersion,
		RemoteETag:         remote.ETag,
		RemoteSize:         remote.Size,
		DigestAlgorithm:    algorithm,
		DigestHex:          hex.EncodeToString(digest),
		LocalSize:          st.Size(),
		LocalMtimeUnixNano: st.ModTime().UnixNano(),
	}
	body, err := json.Marshal(receipt)
	if err != nil {
		return fmt.Errorf("marshal verification receipt: %w", err)
	}
	path := receiptPath(localPath)
	tmp := path + ".part"
	_ = os.Remove(tmp)
	fp, err := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return fmt.Errorf("open verification receipt: %w", err)
	}
	cleanup := func() {
		_ = fp.Close()
		_ = os.Remove(tmp)
	}
	if _, err := fp.Write(body); err != nil {
		cleanup()
		return fmt.Errorf("write verification receipt: %w", err)
	}
	if err := fp.Sync(); err != nil {
		cleanup()
		return fmt.Errorf("fsync verification receipt: %w", err)
	}
	if err := fp.Close(); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("close verification receipt: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("rename verification receipt: %w", err)
	}
	return nil
}

func readReceipt(localPath string) (verificationReceipt, bool) {
	path := receiptPath(localPath)
	info, err := os.Lstat(path)
	if err != nil || info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return verificationReceipt{}, false
	}
	body, err := os.ReadFile(path)
	if err != nil {
		return verificationReceipt{}, false
	}
	var receipt verificationReceipt
	if err := json.Unmarshal(body, &receipt); err != nil {
		return verificationReceipt{}, false
	}
	if receipt.Version != receiptVersion || receipt.RemoteETag == "" ||
		receipt.RemoteSize < 0 || receipt.DigestHex == "" {
		return verificationReceipt{}, false
	}
	if _, err := newHasher(receipt.DigestAlgorithm); err != nil {
		return verificationReceipt{}, false
	}
	if _, err := hex.DecodeString(receipt.DigestHex); err != nil {
		return verificationReceipt{}, false
	}
	return receipt, true
}

func receiptMatches(
	receipt verificationReceipt,
	remote RemoteIdentity,
	st os.FileInfo,
) bool {
	if receipt.RemoteETag != remote.ETag ||
		receipt.RemoteSize != remote.Size ||
		receipt.LocalSize != st.Size() ||
		receipt.LocalMtimeUnixNano != st.ModTime().UnixNano() {
		return false
	}
	if len(remote.ContentMD5) > 0 {
		return receipt.DigestAlgorithm == "md5" &&
			strings.EqualFold(receipt.DigestHex, hex.EncodeToString(remote.ContentMD5))
	}
	return true
}

func regularFileInfo(path string) (os.FileInfo, error) {
	st, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if st.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("local path is a symlink")
	}
	if !st.Mode().IsRegular() {
		return nil, errors.New("local path is not a regular file")
	}
	return st, nil
}

func hashFile(path, algorithm string) ([]byte, error) {
	if _, err := regularFileInfo(path); err != nil {
		return nil, err
	}
	hasher, err := newHasher(algorithm)
	if err != nil {
		return nil, err
	}
	fp, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer fp.Close()
	if _, err := io.Copy(hasher, fp); err != nil {
		return nil, err
	}
	return hasher.Sum(nil), nil
}

func newHasher(algorithm string) (hash.Hash, error) {
	switch algorithm {
	case "md5":
		return md5.New(), nil
	case "sha256":
		return sha256.New(), nil
	default:
		return nil, fmt.Errorf("unsupported digest algorithm %q", algorithm)
	}
}

type countingWriter struct {
	n int64
}

func (w *countingWriter) Write(p []byte) (int, error) {
	w.n += int64(len(p))
	return len(p), nil
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
