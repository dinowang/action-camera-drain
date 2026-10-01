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

func TestDeletionReservationBlocksOverlappingJobs(t *testing.T) {
	store := &reservationStorage{}
	manager := NewManager(store, localfs.New(t.TempDir()), 1, 1)
	release, err := manager.ReserveDeletion("camera")
	if err != nil {
		t.Fatal(err)
	}
	defer release()

	if _, err := manager.Start(context.Background(), []string{"camera"}); !errors.Is(err, ErrOperationConflict) {
		t.Fatalf("same-container job should conflict, got %v", err)
	}
	if _, err := manager.Start(context.Background(), []string{"*"}); !errors.Is(err, ErrOperationConflict) {
		t.Fatalf("wildcard job should conflict, got %v", err)
	}
	if _, err := manager.Start(context.Background(), []string{"other"}); err != nil {
		t.Fatalf("different container should be allowed: %v", err)
	}
}

func TestWildcardJobBlocksDeletionUntilFinished(t *testing.T) {
	store := &reservationStorage{}
	manager := NewManager(store, localfs.New(t.TempDir()), 1, 1)
	started, err := manager.Start(context.Background(), []string{"*"})
	if err != nil {
		t.Fatal(err)
	}

	if _, err := manager.ReserveDeletion("camera"); !errors.Is(err, ErrOperationConflict) {
		t.Fatalf("deletion should conflict with wildcard job, got %v", err)
	}

	waitForJob(t, started)
	release, err := manager.ReserveDeletion("camera")
	if err != nil {
		t.Fatalf("reservation should be released after finish: %v", err)
	}
	release()
}

func waitForJob(t *testing.T, j *Job) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		state := j.Snapshot().State
		if state.terminal() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("job did not finish")
}

type reservationStorage struct{}

func (*reservationStorage) ListContainers(context.Context) ([]string, error) {
	return []string{"camera"}, nil
}

func (*reservationStorage) ListBlobs(context.Context, string) ([]azblob.BlobInfo, error) {
	return nil, nil
}

func (*reservationStorage) DownloadIfMatch(context.Context, string, string, string, io.Writer) error {
	return nil
}

func (*reservationStorage) DeleteBlobIfMatch(context.Context, string, string, string) error {
	return nil
}

func (*reservationStorage) DeleteContainer(context.Context, string) error {
	return nil
}
