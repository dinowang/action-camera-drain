package job

import (
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
)

func TestSnapshotTracksProgressAndTerminalState(t *testing.T) {
	now := time.Date(2026, 9, 30, 1, 0, 0, 0, time.UTC)
	j := newJob("job-1", []string{"camera"}, now, 10, func() time.Time {
		now = now.Add(time.Second)
		return now
	})

	j.emit(Event{Type: EventJobStart, State: StateRunning})
	j.setTotals(2, 12)
	j.emit(Event{
		Type:       EventFileDone,
		FilesDone:  1,
		FilesTotal: 2,
		BytesDone:  5,
		BytesTotal: 12,
	})
	j.emit(Event{Type: EventConcurrency, Concurrency: 3, Bps: 1024})
	j.emit(Event{Type: EventJobDone, State: StateDone, Reason: "complete"})

	snapshot := j.Snapshot()
	if snapshot.State != StateDone ||
		snapshot.FilesDone != 1 ||
		snapshot.FilesTotal != 2 ||
		snapshot.BytesDone != 5 ||
		snapshot.BytesTotal != 12 ||
		snapshot.CurrentConcurrency != 3 ||
		snapshot.BytesPerSecond != 0 ||
		snapshot.Message != "complete" {
		t.Fatalf("unexpected snapshot: %+v", snapshot)
	}
}

func TestHistoryIsBounded(t *testing.T) {
	now := time.Now()
	j := newJob("job-1", []string{"camera"}, now, 3, func() time.Time {
		now = now.Add(time.Second)
		return now
	})
	for i := 0; i < 5; i++ {
		j.emit(Event{Type: EventConcurrency, Concurrency: i})
	}

	_, history, unsubscribe := j.Subscribe(1)
	unsubscribe()
	if len(history) != 3 || history[0].Concurrency != 2 || history[2].Concurrency != 4 {
		t.Fatalf("unexpected bounded history: %+v", history)
	}
}

func TestFinishClosesSlowSubscriber(t *testing.T) {
	now := time.Now()
	j := newJob("job-1", []string{"camera"}, now, 10, func() time.Time {
		now = now.Add(time.Second)
		return now
	})
	ch, _, unsubscribe := j.Subscribe(1)
	defer unsubscribe()

	j.emit(Event{Type: EventConcurrency, Concurrency: 1})
	manager := &Manager{}
	manager.finish(j, StateDone, "")

	<-ch
	if _, ok := <-ch; ok {
		t.Fatal("subscriber channel should close even when job-done is dropped")
	}
}

func TestManagerPrunesRecentJobsButKeepsActive(t *testing.T) {
	now := time.Date(2026, 9, 30, 1, 0, 0, 0, time.UTC)
	store := &snapshotStorage{}
	manager := NewManager(store, localfs.New(t.TempDir()), 1, 1)
	manager.now = func() time.Time { return now }
	manager.retention = time.Hour
	manager.maxRecent = 2

	active := newJob("active", []string{"active"}, now.Add(-2*time.Hour), 10, manager.now)
	now = now.Add(-2 * time.Hour)
	old := newJob("old", []string{"old"}, now.Add(-2*time.Hour), 10, manager.now)
	old.emit(Event{Type: EventJobDone, State: StateDone})
	now = now.Add(2 * time.Hour)
	recent1 := newJob("recent-1", []string{"one"}, now, 10, manager.now)
	recent1.emit(Event{Type: EventJobDone, State: StateDone})
	now = now.Add(time.Minute)
	recent2 := newJob("recent-2", []string{"two"}, now, 10, manager.now)
	recent2.emit(Event{Type: EventJobDone, State: StateFailed})
	now = now.Add(time.Minute)
	recent3 := newJob("recent-3", []string{"three"}, now, 10, manager.now)
	recent3.emit(Event{Type: EventJobDone, State: StateCancelled})
	now = now.Add(time.Minute)

	manager.jobs = map[string]*Job{
		active.ID:  active,
		old.ID:     old,
		recent1.ID: recent1,
		recent2.ID: recent2,
		recent3.ID: recent3,
	}

	list := manager.List()
	if len(list) != 3 {
		t.Fatalf("expected active plus two recent jobs, got %+v", list)
	}
	if list[0].ID != "active" || list[1].ID != "recent-3" || list[2].ID != "recent-2" {
		t.Fatalf("unexpected ordering or retention: %+v", list)
	}
}

func TestManagerRejectsOverlappingDownloads(t *testing.T) {
	store := newBlockingStorage()
	manager := NewManager(store, localfs.New(t.TempDir()), 1, 1)
	first, err := manager.Start(context.Background(), []string{"camera"})
	if err != nil {
		t.Fatal(err)
	}
	<-store.started

	if _, err := manager.Start(context.Background(), []string{"camera"}); !errors.Is(err, ErrOperationConflict) {
		t.Fatalf("same container should conflict, got %v", err)
	}
	if _, err := manager.Start(context.Background(), []string{"*"}); !errors.Is(err, ErrOperationConflict) {
		t.Fatalf("wildcard should conflict, got %v", err)
	}
	if _, err := manager.Start(context.Background(), []string{"other"}); err != nil {
		t.Fatalf("independent container should be allowed: %v", err)
	}

	close(store.release)
	waitForJob(t, first)
}

func TestManagerReservesEveryContainerInRequest(t *testing.T) {
	store := newBlockingStorage()
	manager := NewManager(store, localfs.New(t.TempDir()), 1, 1)
	first, err := manager.Start(context.Background(), []string{"camera", "secondary"})
	if err != nil {
		t.Fatal(err)
	}
	<-store.started

	if _, err := manager.Start(context.Background(), []string{"secondary"}); !errors.Is(err, ErrOperationConflict) {
		t.Fatalf("overlapping multi-container request should conflict, got %v", err)
	}

	close(store.release)
	waitForJob(t, first)
}

type snapshotStorage struct{}

func (*snapshotStorage) ListContainers(context.Context) ([]string, error) {
	return nil, nil
}

func (*snapshotStorage) ListBlobs(context.Context, string) ([]azblob.BlobInfo, error) {
	return nil, nil
}

func (*snapshotStorage) Download(context.Context, string, string, io.Writer) error {
	return nil
}

func (*snapshotStorage) DeleteBlobIfMatch(context.Context, string, string, string) error {
	return nil
}

func (*snapshotStorage) DeleteContainer(context.Context, string) error {
	return nil
}

type blockingStorage struct {
	started chan struct{}
	release chan struct{}
}

func newBlockingStorage() *blockingStorage {
	return &blockingStorage{
		started: make(chan struct{}, 2),
		release: make(chan struct{}),
	}
}

func (*blockingStorage) ListContainers(context.Context) ([]string, error) {
	return []string{"camera"}, nil
}

func (s *blockingStorage) ListBlobs(
	ctx context.Context,
	_ string,
) ([]azblob.BlobInfo, error) {
	s.started <- struct{}{}
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-s.release:
		return nil, nil
	}
}

func (*blockingStorage) Download(context.Context, string, string, io.Writer) error {
	return nil
}

func (*blockingStorage) DeleteBlobIfMatch(context.Context, string, string, string) error {
	return nil
}

func (*blockingStorage) DeleteContainer(context.Context, string) error {
	return nil
}
