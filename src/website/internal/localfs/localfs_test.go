package localfs

import (
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
	got := f.LocalPath("videos", "device-x/Camera01/clip.mp4")
	want := filepath.Join("/data", "videos", "device-x/Camera01/clip.mp4")
	if got != want {
		t.Fatalf("LocalPath: got %q want %q", got, want)
	}
}

func TestShouldSkip_NoLocal(t *testing.T) {
	d := t.TempDir()
	f := New(d)
	dec := f.ShouldSkip(filepath.Join(d, "missing.mp4"), 100, "1700000000000")
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
	dec := f.ShouldSkip(p, 5, strconv.FormatInt(mt.UnixMilli(), 10))
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
	dec := f.ShouldSkip(p, 999, "0")
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
	dec := f.ShouldSkip(p, 2, "")
	if dec.Skip {
		t.Fatalf("expected no-skip when metadata missing")
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
	dec := f.ShouldSkip(p, 5, strconv.FormatInt(wantMs, 10))
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
	dec := f.ShouldSkip(p, 5, "222")
	if dec.Skip {
		t.Fatalf("sidecar with different value must not cause skip")
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
