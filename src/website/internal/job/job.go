// Package job orchestrates one user-triggered sync run end-to-end:
//
//   - resolve which containers/blobs to sync (via plan.Build)
//   - dispatch downloads through worker.Pool
//   - broadcast progress events to any SSE subscribers
//
// A Job is one-shot: created → running → done|cancelled|failed.
package job

import (
	"context"
	"fmt"
	"io"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
	"github.com/dinowang/action-camera-drain/src/website/internal/plan"
	"github.com/dinowang/action-camera-drain/src/website/internal/worker"
)

// State is the high-level job state.
type State string

const (
	StateRunning   State = "running"
	StateDone      State = "done"
	StateCancelled State = "cancelled"
	StateFailed    State = "failed"
)

// Event types emitted via SSE.
const (
	EventJobStart    = "job-start"
	EventFileStart   = "file-start"
	EventFileSkip    = "file-skip"
	EventFileDone    = "file-done"
	EventFileFailed  = "file-failed"
	EventConcurrency = "concurrency"
	EventJobDone     = "job-done"
)

// Event is a typed payload sent to subscribers.
type Event struct {
	Type        string  `json:"type"`
	Timestamp   int64   `json:"ts"`
	Container   string  `json:"container,omitempty"`
	BlobName    string  `json:"blob,omitempty"`
	Size        int64   `json:"size,omitempty"`
	BytesDone   int64   `json:"bytesDone,omitempty"`
	BytesTotal  int64   `json:"bytesTotal,omitempty"`
	FilesDone   int     `json:"filesDone,omitempty"`
	FilesTotal  int     `json:"filesTotal,omitempty"`
	FilesFailed int     `json:"filesFailed,omitempty"`
	Concurrency int     `json:"concurrency,omitempty"`
	Bps         float64 `json:"bps,omitempty"`
	Reason      string  `json:"reason,omitempty"`
	State       State   `json:"state,omitempty"`
}

// Job is one sync run.
type Job struct {
	ID         string
	State      State
	CreatedAt  time.Time
	StartedAt  time.Time
	FinishedAt time.Time

	mu          sync.RWMutex
	subscribers map[chan Event]struct{}
	history     []Event
	cancel      context.CancelFunc
}

func newJob(id string) *Job {
	return &Job{
		ID:          id,
		State:       StateRunning,
		CreatedAt:   time.Now(),
		subscribers: map[chan Event]struct{}{},
	}
}

// Subscribe returns a channel that receives events. Caller should consume
// promptly; slow consumers will drop events (best-effort SSE semantics).
// Pass the channel back to Unsubscribe when done.
func (j *Job) Subscribe(buffer int) (<-chan Event, []Event, func()) {
	ch := make(chan Event, buffer)
	j.mu.Lock()
	j.subscribers[ch] = struct{}{}
	hist := append([]Event(nil), j.history...)
	j.mu.Unlock()
	return ch, hist, func() {
		j.mu.Lock()
		if _, ok := j.subscribers[ch]; ok {
			delete(j.subscribers, ch)
			close(ch)
		}
		j.mu.Unlock()
	}
}

func (j *Job) emit(ev Event) {
	ev.Timestamp = time.Now().UnixMilli()
	j.mu.Lock()
	j.history = append(j.history, ev)
	subs := make([]chan Event, 0, len(j.subscribers))
	for ch := range j.subscribers {
		subs = append(subs, ch)
	}
	j.mu.Unlock()
	for _, ch := range subs {
		select {
		case ch <- ev:
		default:
			// Drop event for slow consumer.
		}
	}
}

// Manager keeps track of all active and recent jobs.
type Manager struct {
	store  azblob.Storage
	fs     *localfs.FS
	minCon int
	maxCon int

	mu   sync.Mutex
	jobs map[string]*Job
	seq  uint64
}

func NewManager(store azblob.Storage, fs *localfs.FS, minCon, maxCon int) *Manager {
	return &Manager{
		store:  store,
		fs:     fs,
		minCon: minCon,
		maxCon: maxCon,
		jobs:   map[string]*Job{},
	}
}

// Get returns a job by ID, or nil.
func (m *Manager) Get(id string) *Job {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.jobs[id]
}

// Cancel signals the running job to stop.
func (m *Manager) Cancel(id string) bool {
	j := m.Get(id)
	if j == nil {
		return false
	}
	j.mu.Lock()
	cancel := j.cancel
	j.mu.Unlock()
	if cancel != nil {
		cancel()
		return true
	}
	return false
}

// Start kicks off a new job covering the specified containers (or "*" = all).
// Returns the job; events stream via Subscribe.
func (m *Manager) Start(ctx context.Context, containers []string) (*Job, error) {
	m.mu.Lock()
	m.seq++
	id := fmt.Sprintf("job-%d-%d", time.Now().Unix(), m.seq)
	j := newJob(id)
	m.jobs[id] = j
	m.mu.Unlock()

	runCtx, cancel := context.WithCancel(context.Background())
	j.cancel = cancel
	go m.run(runCtx, j, containers)
	return j, nil
}

func (m *Manager) run(ctx context.Context, j *Job, requestedContainers []string) {
	j.StartedAt = time.Now()
	j.emit(Event{Type: EventJobStart, State: StateRunning})

	// Resolve container list.
	containers := requestedContainers
	if len(containers) == 0 || (len(containers) == 1 && containers[0] == "*") {
		all, err := m.store.ListContainers(ctx)
		if err != nil {
			m.finish(j, StateFailed, "list containers: "+err.Error())
			return
		}
		containers = all
	}

	// Build plan across all containers.
	type planned struct {
		item plan.Item
	}
	var pending []planned
	var bytesTotal int64
	for _, c := range containers {
		blobs, err := m.store.ListBlobs(ctx, c)
		if err != nil {
			m.finish(j, StateFailed, "list blobs in "+c+": "+err.Error())
			return
		}
		_, items := plan.Build(m.fs, c, blobs)
		for _, it := range items {
			if it.Status == plan.StatusSkipped {
				j.emit(Event{
					Type:      EventFileSkip,
					Container: it.Container,
					BlobName:  it.BlobName,
					Size:      it.Size,
					Reason:    it.SkipReason,
				})
				continue
			}
			pending = append(pending, planned{item: it})
			bytesTotal += it.Size
		}
	}

	filesTotal := len(pending)
	if filesTotal == 0 {
		m.finish(j, StateDone, "nothing to do")
		return
	}

	pool := worker.New(m.minCon, m.maxCon)
	tasks := make(chan worker.Task)
	results := make(chan worker.Result)

	var bytesDone int64
	var filesDone, filesFailed int64

	go func() {
		defer close(tasks)
		for _, p := range pending {
			it := p.item
			select {
			case <-ctx.Done():
				return
			case tasks <- func(c context.Context) (int64, error) {
				j.emit(Event{
					Type:      EventFileStart,
					Container: it.Container,
					BlobName:  it.BlobName,
					Size:      it.Size,
				})
				err := m.fs.WriteAtomic(it.LocalPath, it.MtimeMillis, func(w io.Writer) error {
					return m.store.Download(c, it.Container, it.BlobName, w)
				})
				if err != nil {
					return 0, err
				}
				return it.Size, nil
			}:
			}
		}
	}()

	go pool.Run(ctx, tasks, results)

	// Concurrency reporter
	tickerStop := make(chan struct{})
	go func() {
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-tickerStop:
				return
			case <-t.C:
				j.emit(Event{
					Type:        EventConcurrency,
					Concurrency: pool.Current(),
					Bps:         pool.ThroughputBps(),
				})
			}
		}
	}()

	for r := range results {
		idx := r.Index
		if idx < 0 || idx >= len(pending) {
			continue
		}
		it := pending[idx].item
		if r.Err != nil {
			atomic.AddInt64(&filesFailed, 1)
			j.emit(Event{
				Type:      EventFileFailed,
				Container: it.Container,
				BlobName:  it.BlobName,
				Reason:    r.Err.Error(),
			})
			continue
		}
		atomic.AddInt64(&filesDone, 1)
		atomic.AddInt64(&bytesDone, r.Bytes)
		j.emit(Event{
			Type:       EventFileDone,
			Container:  it.Container,
			BlobName:   it.BlobName,
			Size:       it.Size,
			BytesDone:  atomic.LoadInt64(&bytesDone),
			BytesTotal: bytesTotal,
			FilesDone:  int(atomic.LoadInt64(&filesDone)),
			FilesTotal: filesTotal,
		})
	}
	close(tickerStop)

	if ctx.Err() != nil {
		m.finish(j, StateCancelled, "cancelled")
		return
	}
	if filesFailed > 0 {
		m.finish(j, StateFailed, fmt.Sprintf("%d file(s) failed", filesFailed))
		return
	}
	m.finish(j, StateDone, "")
}

func (m *Manager) finish(j *Job, st State, reason string) {
	j.mu.Lock()
	j.State = st
	j.FinishedAt = time.Now()
	j.mu.Unlock()
	j.emit(Event{Type: EventJobDone, State: st, Reason: reason})
	// Close all subscribers so SSE handlers exit cleanly.
	j.mu.Lock()
	for ch := range j.subscribers {
		close(ch)
		delete(j.subscribers, ch)
	}
	j.mu.Unlock()
}
