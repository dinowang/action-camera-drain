package localfs

import (
	"crypto/md5"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestLocalPath(t *testing.T) {
	f := New("/data")
	got, err := f.LocalPath("videos", "device-x/Camera01/clip.mp4")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join("/data", "videos", "device-x/Camera01/clip.mp4")
	if got != want {
		t.Fatalf("LocalPath: got %q want %q", got, want)
	}
}

func TestLocalPathRejectsTraversal(t *testing.T) {
	f := New("/data")
	if _, err := f.LocalPath("../outside", "clip.mp4"); err == nil {
		t.Fatal("expected container traversal to be rejected")
	}
	if _, err := f.LocalPath("videos", "../other/clip.mp4"); err == nil {
		t.Fatal("expected traversal to be rejected")
	}
	if _, err := f.LocalPath("videos", `..\other\clip.mp4`); err == nil {
		t.Fatal("expected backslash traversal to be rejected")
	}
}

func TestLocalPathRejectsSymlinkComponent(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	containerDir := filepath.Join(root, "videos")
	if err := os.MkdirAll(containerDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(containerDir, "link")); err != nil {
		t.Fatal(err)
	}

	if _, err := New(root).LocalPath("videos", "link/clip.mp4"); err == nil {
		t.Fatal("expected symlink component to be rejected")
	}
}

func TestShouldSkip_NoLocal(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	dec := f.ShouldSkip(filepath.Join(d, "missing.mp4"), RemoteIdentity{
		Size: 100, MtimeMeta: "1700000000000",
	})
	if dec.Skip {
		t.Fatalf("expected no-skip when local missing")
	}
}

func TestShouldSkip_MtimeMatch(t *testing.T) {
	d := t.TempDir()
	p := filepath.Join(d, "match.mp4")
	if err := os.WriteFile(p, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	mt := time.Date(2024, 6, 1, 12, 0, 0, 0, time.UTC)
	if err := os.Chtimes(p, mt, mt); err != nil {
		t.Fatal(err)
	}
	f := New(d)
	dec := f.ShouldSkip(p, RemoteIdentity{
		Size: 5, MtimeMeta: strconv.FormatInt(mt.UnixMilli(), 10),
	})
	if !dec.Skip {
		t.Fatalf("expected skip, got reason=%q", dec.Reason)
	}
}

func TestShouldSkip_SizeMismatch(t *testing.T) {
	d := t.TempDir()
	p := filepath.Join(d, "size.mp4")
	if err := os.WriteFile(p, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	f := New(d)
	dec := f.ShouldSkip(p, RemoteIdentity{Size: 999, MtimeMeta: "0"})
	if dec.Skip {
		t.Fatalf("expected no-skip on size diff")
	}
	if !strings.Contains(dec.Reason, "size") {
		t.Fatalf("expected size reason, got %q", dec.Reason)
	}
}

func TestShouldSkip_MtimeMissing(t *testing.T) {
	d := t.TempDir()
	p := filepath.Join(d, "m.mp4")
	if err := os.WriteFile(p, []byte("hi"), 0o644); err != nil {
		t.Fatal(err)
	}
	f := New(d)
	dec := f.ShouldSkip(p, RemoteIdentity{Size: 2, ETag: "etag-1"})
	if dec.Skip {
		t.Fatalf("expected no-skip when metadata missing")
	}
	if !dec.VerificationNeeded {
		t.Fatal("same-size no-mtime file should require content verification")
	}
}

func TestShouldSkip_SidecarFallback(t *testing.T) {
	d := t.TempDir()
	p := filepath.Join(d, "with-sidecar.mp4")
	if err := os.WriteFile(p, []byte("12345"), 0o644); err != nil {
		t.Fatal(err)
	}
	// fs mtime is "now"; sidecar carries the intended mtime instead.
	wantMs := int64(1700000000000)
	if err := writeSidecar(p, wantMs); err != nil {
		t.Fatal(err)
	}
	f := New(d)
	dec := f.ShouldSkip(p, RemoteIdentity{
		Size: 5, MtimeMeta: strconv.FormatInt(wantMs, 10),
	})
	if !dec.Skip {
		t.Fatalf("expected skip via sidecar, got reason=%q", dec.Reason)
	}
	if !strings.Contains(dec.Reason, "sidecar") {
		t.Fatalf("reason should mention sidecar, got %q", dec.Reason)
	}
}

func TestShouldSkip_SidecarMismatch(t *testing.T) {
	d := t.TempDir()
	p := filepath.Join(d, "bad-sidecar.mp4")
	_ = os.WriteFile(p, []byte("12345"), 0o644)
	_ = writeSidecar(p, 111)
	f := New(d)
	dec := f.ShouldSkip(p, RemoteIdentity{Size: 5, MtimeMeta: "222"})
	if dec.Skip {
		t.Fatalf("sidecar with different value must not cause skip")
	}
}

func TestVerifyForDeletionRejectsSidecarFallback(t *testing.T) {
	d := t.TempDir()
	p := filepath.Join(d, "changed.mp4")
	if err := os.WriteFile(p, []byte("12345"), 0o644); err != nil {
		t.Fatal(err)
	}
	wantMs := int64(1700000000000)
	if err := writeSidecar(p, wantMs); err != nil {
		t.Fatal(err)
	}

	decision := New(d).VerifyForDeletion(p, RemoteIdentity{
		Size: 5, MtimeMeta: strconv.FormatInt(wantMs, 10),
	})

	if decision.Skip {
		t.Fatal("sidecar alone must not authorize destructive cloud cleanup")
	}
}

func TestWriteAtomic_Success(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	target := filepath.Join(d, "sub/clip.mp4")
	mt := int64(1717250000000)
	err := f.WriteAtomic(target, mt, func(w io.Writer) error {
		_, err := w.Write([]byte("payload"))
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	if st.Size() != 7 {
		t.Fatalf("size: got %d want 7", st.Size())
	}
	if st.ModTime().UnixMilli() != mt {
		t.Fatalf("mtime: got %d want %d", st.ModTime().UnixMilli(), mt)
	}
	// .part must be gone
	if _, err := os.Stat(target + ".part"); !os.IsNotExist(err) {
		t.Fatalf("stale .part should not exist")
	}
}

func TestWriteAtomic_FailureCleansUp(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	target := filepath.Join(d, "broken.mp4")
	err := f.WriteAtomic(target, 0, func(w io.Writer) error {
		_, _ = w.Write([]byte("partial"))
		return io.ErrUnexpectedEOF
	})
	if err == nil {
		t.Fatal("expected error")
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatalf("final file must not exist after failure")
	}
	if _, err := os.Stat(target + ".part"); !os.IsNotExist(err) {
		t.Fatalf(".part must be removed after failure")
	}
}

func TestWriteAtomic_NoMtimePreservesNow(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	target := filepath.Join(d, "no-mtime.mp4")
	before := time.Now().Add(-1 * time.Second)
	if err := f.WriteAtomic(target, 0, func(w io.Writer) error {
		_, err := w.Write([]byte("x"))
		return err
	}); err != nil {
		t.Fatal(err)
	}
	st, _ := os.Stat(target)
	if st.ModTime().Before(before) {
		t.Fatalf("file mtime should be approx-now, got %v (before=%v)", st.ModTime(), before)
	}
}

func TestWriteAtomicRemovesStaleVerificationReceipt(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	target := filepath.Join(d, "clip.mp4")
	if err := os.WriteFile(target, []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}
	remote := RemoteIdentity{Size: 3, ETag: "etag-old"}
	if _, _, err := f.VerifyOrReplace(target, remote, func(w io.Writer) error {
		_, err := w.Write([]byte("old"))
		return err
	}); err != nil {
		t.Fatal(err)
	}

	if err := f.WriteAtomic(target, 1700000000000, func(w io.Writer) error {
		_, err := w.Write([]byte("new"))
		return err
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(receiptPath(target)); !os.IsNotExist(err) {
		t.Fatal("mtime-based replacement should remove stale content receipt")
	}
}

func TestVerifyOrReplaceUsesContentMD5WithoutDownloading(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	target := filepath.Join(d, "manual.mp4")
	content := []byte("same-content")
	if err := os.WriteFile(target, content, 0o644); err != nil {
		t.Fatal(err)
	}
	digest := md5.Sum(content)
	remote := RemoteIdentity{
		Size:       int64(len(content)),
		ETag:       "etag-1",
		ContentMD5: digest[:],
	}
	downloadCalled := false

	outcome, transferred, err := f.VerifyOrReplace(
		target,
		remote,
		func(io.Writer) error {
			downloadCalled = true
			return nil
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if outcome != OutcomeVerifiedExisting || transferred != 0 || downloadCalled {
		t.Fatalf(
			"outcome=%q transferred=%d downloadCalled=%v",
			outcome,
			transferred,
			downloadCalled,
		)
	}
	if decision := f.ShouldSkip(target, remote); !decision.Skip {
		t.Fatalf("receipt should enable future skip: %+v", decision)
	}
	if decision := f.VerifyForDeletion(target, remote); !decision.Skip {
		t.Fatalf("receipt plus digest should authorize deletion: %+v", decision)
	}
}

func TestVerifyOrReplaceStreamsSHA256AndKeepsMatchingLocalFile(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	target := filepath.Join(d, "manual.mp4")
	content := []byte("same-content")
	if err := os.WriteFile(target, content, 0o644); err != nil {
		t.Fatal(err)
	}
	before, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	remote := RemoteIdentity{Size: int64(len(content)), ETag: "etag-1"}

	outcome, transferred, err := f.VerifyOrReplace(
		target,
		remote,
		func(w io.Writer) error {
			_, err := w.Write(content)
			return err
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	after, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	if outcome != OutcomeVerifiedExisting || transferred != int64(len(content)) {
		t.Fatalf("outcome=%q transferred=%d", outcome, transferred)
	}
	if !after.ModTime().Equal(before.ModTime()) {
		t.Fatal("matching local file should not be replaced")
	}
	if _, err := os.Stat(target + ".verify.part"); !os.IsNotExist(err) {
		t.Fatal("verification temp file should be removed")
	}
}

func TestVerifyOrReplaceReplacesDifferentContent(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	target := filepath.Join(d, "manual.mp4")
	if err := os.WriteFile(target, []byte("local"), 0o644); err != nil {
		t.Fatal(err)
	}
	remoteContent := []byte("cloud")
	remote := RemoteIdentity{Size: int64(len(remoteContent)), ETag: "etag-2"}

	outcome, transferred, err := f.VerifyOrReplace(
		target,
		remote,
		func(w io.Writer) error {
			_, err := w.Write(remoteContent)
			return err
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if outcome != OutcomeReplaced || transferred != int64(len(remoteContent)) ||
		string(got) != string(remoteContent) {
		t.Fatalf("outcome=%q transferred=%d content=%q", outcome, transferred, got)
	}
	if decision := f.ShouldSkip(target, remote); !decision.Skip {
		t.Fatalf("replaced file should have a valid receipt: %+v", decision)
	}
}

func TestReceiptInvalidatesOnETagChangeAndDeletionRehashes(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	target := filepath.Join(d, "manual.mp4")
	content := []byte("cloud")
	if err := os.WriteFile(target, content, 0o644); err != nil {
		t.Fatal(err)
	}
	remote := RemoteIdentity{Size: int64(len(content)), ETag: "etag-1"}
	if _, _, err := f.VerifyOrReplace(target, remote, func(w io.Writer) error {
		_, err := w.Write(content)
		return err
	}); err != nil {
		t.Fatal(err)
	}

	changedRemote := remote
	changedRemote.ETag = "etag-2"
	if decision := f.ShouldSkip(target, changedRemote); decision.Skip || !decision.VerificationNeeded {
		t.Fatalf("changed ETag must invalidate receipt: %+v", decision)
	}

	st, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(target, []byte("alter"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(target, st.ModTime(), st.ModTime()); err != nil {
		t.Fatal(err)
	}
	if decision := f.ShouldSkip(target, remote); !decision.Skip {
		t.Fatalf("fast path should still trust unchanged stat identity: %+v", decision)
	}
	if decision := f.VerifyForDeletion(target, remote); decision.Skip {
		t.Fatal("deletion must rehash and reject same-size modified content")
	}
}

func TestVerifyOrReplaceFailurePreservesOriginal(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	target := filepath.Join(d, "manual.mp4")
	if err := os.WriteFile(target, []byte("local"), 0o644); err != nil {
		t.Fatal(err)
	}
	remote := RemoteIdentity{Size: 5, ETag: "etag-1"}

	_, transferred, err := f.VerifyOrReplace(
		target,
		remote,
		func(w io.Writer) error {
			_, _ = w.Write([]byte("cl"))
			return errors.New("connection lost")
		},
	)
	if err == nil || transferred != 2 {
		t.Fatalf("err=%v transferred=%d", err, transferred)
	}
	got, readErr := os.ReadFile(target)
	if readErr != nil {
		t.Fatal(readErr)
	}
	if string(got) != "local" {
		t.Fatalf("original content changed to %q", got)
	}
	if _, err := os.Stat(target + ".verify.part"); !os.IsNotExist(err) {
		t.Fatal("failed verification temp file should be removed")
	}
}
