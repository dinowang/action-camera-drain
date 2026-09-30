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
	"errors"
	"fmt"
	"io"
	"sort"
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

func (s State) terminal() bool {
	return s == StateDone || s == StateCancelled || s == StateFailed
}

// Event types emitted via SSE.
const (
	EventJobStart    = "job-start"
	EventFileStart   = "file-start"
	EventFileSkip    = "file-skip"
	EventFileDone    = "file-done"
	EventFileWarning = "file-warning"
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

// Snapshot is the browser-reconstructable state of one server-side job.
type Snapshot struct {
	ID                 string   `json:"id"`
	Containers         []string `json:"containers"`
	State              State    `json:"state"`
	CreatedAtMillis    int64    `json:"createdAtMillis"`
	StartedAtMillis    int64    `json:"startedAtMillis,omitempty"`
	FinishedAtMillis   int64    `json:"finishedAtMillis,omitempty"`
	FilesDone          int      `json:"filesDone"`
	FilesFailed        int      `json:"filesFailed"`
	FilesTotal         int      `json:"filesTotal"`
	BytesDone          int64    `json:"bytesDone"`
	BytesTotal         int64    `json:"bytesTotal"`
	CurrentConcurrency int      `json:"currentConcurrency"`
	BytesPerSecond     float64  `json:"bytesPerSecond"`
	Message            string   `json:"message,omitempty"`
}

// Job is one sync run.
type Job struct {
	ID string

	mu          sync.RWMutex
	snapshot    Snapshot
	subscribers map[chan Event]struct{}
	history     []Event
	cancel      context.CancelFunc
	maxHistory  int
	now         func() time.Time
}

func newJob(
	id string,
	containers []string,
	createdAt time.Time,
	maxHistory int,
	now func() time.Time,
) *Job {
	return &Job{
		ID: id,
		snapshot: Snapshot{
			ID:              id,
			Containers:      append([]string(nil), containers...),
			State:           StateRunning,
			CreatedAtMillis: createdAt.UnixMilli(),
		},
		subscribers: map[chan Event]struct{}{},
		maxHistory:  maxHistory,
		now:         now,
	}
}

// Snapshot returns an immutable copy suitable for APIs and UI reconstruction.
func (j *Job) Snapshot() Snapshot {
	j.mu.RLock()
	defer j.mu.RUnlock()
	out := j.snapshot
	out.Containers = append([]string(nil), j.snapshot.Containers...)
	return out
}

// Subscribe returns a channel that receives events. Caller should consume
// promptly; slow consumers will drop events (best-effort SSE semantics).
// Pass the channel back to Unsubscribe when done.
func (j *Job) Subscribe(buffer int) (<-chan Event, []Event, func()) {
	ch := make(chan Event, buffer)
	j.mu.Lock()
	hist := append([]Event(nil), j.history...)
	if j.snapshot.State.terminal() {
		close(ch)
		j.mu.Unlock()
		return ch, hist, func() {}
	}
	j.subscribers[ch] = struct{}{}
	j.mu.Unlock()
	return ch, hist, func() {
		j.mu.Lock()
		delete(j.subscribers, ch)
		j.mu.Unlock()
	}
}

func (j *Job) emit(ev Event) {
	ev.Timestamp = j.now().UnixMilli()
	j.mu.Lock()
	j.applyEventLocked(ev)
	j.history = append(j.history, ev)
	if len(j.history) > j.maxHistory {
		j.history = append([]Event(nil), j.history[len(j.history)-j.maxHistory:]...)
	}
	for ch := range j.subscribers {
		select {
		case ch <- ev:
		default:
			// Drop event for slow consumer.
		}
	}
	j.mu.Unlock()
}

func (j *Job) applyEventLocked(ev Event) {
	switch ev.Type {
	case EventJobStart:
		j.snapshot.StartedAtMillis = ev.Timestamp
	case EventFileDone:
		j.snapshot.FilesDone = ev.FilesDone
		j.snapshot.FilesTotal = ev.FilesTotal
		j.snapshot.BytesDone = ev.BytesDone
		j.snapshot.BytesTotal = ev.BytesTotal
	case EventFileFailed:
		j.snapshot.FilesFailed = ev.FilesFailed
		j.snapshot.FilesTotal = ev.FilesTotal
		j.snapshot.BytesTotal = ev.BytesTotal
		j.snapshot.Message = ev.Reason
	case EventConcurrency:
		j.snapshot.CurrentConcurrency = ev.Concurrency
		j.snapshot.BytesPerSecond = ev.Bps
	case EventJobDone:
		j.snapshot.State = ev.State
		j.snapshot.FinishedAtMillis = ev.Timestamp
		j.snapshot.BytesPerSecond = 0
		j.snapshot.Message = ev.Reason
	}
}

func (j *Job) setTotals(files int, bytes int64) {
	j.mu.Lock()
	j.snapshot.FilesTotal = files
	j.snapshot.BytesTotal = bytes
	j.mu.Unlock()
}

func (j *Job) setCancel(cancel context.CancelFunc) {
	j.mu.Lock()
	j.cancel = cancel
	j.mu.Unlock()
}

// Manager keeps track of all active and recent jobs.
type Manager struct {
	store  azblob.Storage
	fs     *localfs.FS
	minCon int
	maxCon int

	mu               sync.Mutex
	jobs             map[string]*Job
	seq              uint64
	activeAll        int
	activeContainers map[string]int
	deleting         map[string]bool
	now              func() time.Time
	retention        time.Duration
	maxRecent        int
	maxHistory       int
}

const (
	defaultRetention  = 24 * time.Hour
	defaultMaxRecent  = 50
	defaultMaxHistory = 500
)

func NewManager(store azblob.Storage, fs *localfs.FS, minCon, maxCon int) *Manager {
	return &Manager{
		store:            store,
		fs:               fs,
		minCon:           minCon,
		maxCon:           maxCon,
		jobs:             map[string]*Job{},
		activeContainers: map[string]int{},
		deleting:         map[string]bool{},
		now:              time.Now,
		retention:        defaultRetention,
		maxRecent:        defaultMaxRecent,
		maxHistory:       defaultMaxHistory,
	}
}

// ErrOperationConflict means a download and deletion target overlap.
var ErrOperationConflict = errors.New("container operation conflicts with an active job")
var ErrJobNotFound = errors.New("job not found")
var ErrJobNotRunning = errors.New("job is not running")

// Get returns a job by ID, or nil.
func (m *Manager) Get(id string) *Job {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pruneLocked()
	return m.jobs[id]
}

// GetSnapshot returns one browser-safe job summary.
func (m *Manager) GetSnapshot(id string) (Snapshot, bool) {
	j := m.Get(id)
	if j == nil {
		return Snapshot{}, false
	}
	return j.Snapshot(), true
}

// List returns active jobs first, then recent terminal jobs.
func (m *Manager) List() []Snapshot {
	m.mu.Lock()
	m.pruneLocked()
	jobs := make([]*Job, 0, len(m.jobs))
	for _, j := range m.jobs {
		jobs = append(jobs, j)
	}
	m.mu.Unlock()

	out := make([]Snapshot, 0, len(jobs))
	for _, j := range jobs {
		out = append(out, j.Snapshot())
	}
	sort.Slice(out, func(i, k int) bool {
		if out[i].State.terminal() != out[k].State.terminal() {
			return !out[i].State.terminal()
		}
		return out[i].CreatedAtMillis > out[k].CreatedAtMillis
	})
	return out
}

// Cancel signals a running job to stop.
func (m *Manager) Cancel(id string) error {
	j := m.Get(id)
	if j == nil {
		return ErrJobNotFound
	}
	j.mu.Lock()
	if j.snapshot.State.terminal() {
		j.mu.Unlock()
		return ErrJobNotRunning
	}
	cancel := j.cancel
	j.mu.Unlock()
	if cancel != nil {
		cancel()
		return nil
	}
	return ErrJobNotRunning
}

// Start kicks off a new job covering the specified containers (or "*" = all).
// Returns the job; events stream via Subscribe.
func (m *Manager) Start(ctx context.Context, containers []string) (*Job, error) {
	targets := normalizeTargets(containers)
	runCtx, cancel := context.WithCancel(context.Background())
	m.mu.Lock()
	m.pruneLocked()
	if err := m.reserveJobLocked(targets); err != nil {
		m.mu.Unlock()
		cancel()
		return nil, err
	}
	m.seq++
	now := m.now()
	id := fmt.Sprintf("job-%d-%d", now.Unix(), m.seq)
	j := newJob(id, targets, now, m.maxHistory, m.now)
	j.setCancel(cancel)
	m.jobs[id] = j
	m.mu.Unlock()

	go func() {
		defer m.releaseJob(targets)
		m.run(runCtx, j, targets)
	}()
	return j, nil
}

// ReserveDeletion blocks overlapping Catch downloads and other deletions until
// the returned release function is called.
func (m *Manager) ReserveDeletion(containerName string) (func(), error) {
	if containerName == "" {
		return nil, errors.New("container name is required")
	}
	m.mu.Lock()
	if m.activeAll > 0 || m.activeContainers[containerName] > 0 || m.deleting[containerName] {
		m.mu.Unlock()
		return nil, ErrOperationConflict
	}
	m.deleting[containerName] = true
	m.mu.Unlock()

	var once sync.Once
	return func() {
		once.Do(func() {
			m.mu.Lock()
			delete(m.deleting, containerName)
			m.mu.Unlock()
		})
	}, nil
}

func (m *Manager) reserveJobLocked(targets []string) error {
	if len(targets) == 1 && targets[0] == "*" {
		if len(m.deleting) > 0 || m.activeAll > 0 || len(m.activeContainers) > 0 {
			return ErrOperationConflict
		}
		m.activeAll++
		return nil
	}
	if m.activeAll > 0 {
		return ErrOperationConflict
	}
	for _, target := range targets {
		if m.deleting[target] || m.activeContainers[target] > 0 {
			return ErrOperationConflict
		}
	}
	for _, target := range targets {
		m.activeContainers[target]++
	}
	return nil
}

func (m *Manager) releaseJob(targets []string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(targets) == 1 && targets[0] == "*" {
		m.activeAll--
		return
	}
	for _, target := range targets {
		m.activeContainers[target]--
		if m.activeContainers[target] <= 0 {
			delete(m.activeContainers, target)
		}
	}
}

func (m *Manager) pruneLocked() {
	nowMillis := m.now().UnixMilli()
	cutoffMillis := nowMillis - m.retention.Milliseconds()
	type terminalJob struct {
		id       string
		finished int64
	}
	terminal := make([]terminalJob, 0)
	for id, j := range m.jobs {
		snapshot := j.Snapshot()
		if !snapshot.State.terminal() {
			continue
		}
		if snapshot.FinishedAtMillis > 0 && snapshot.FinishedAtMillis < cutoffMillis {
			delete(m.jobs, id)
			continue
		}
		terminal = append(terminal, terminalJob{id: id, finished: snapshot.FinishedAtMillis})
	}
	if len(terminal) <= m.maxRecent {
		return
	}
	sort.Slice(terminal, func(i, k int) bool {
		return terminal[i].finished > terminal[k].finished
	})
	for _, item := range terminal[m.maxRecent:] {
		delete(m.jobs, item.id)
	}
}

func normalizeTargets(containers []string) []string {
	if len(containers) == 0 {
		return []string{"*"}
	}
	seen := make(map[string]struct{}, len(containers))
	targets := make([]string, 0, len(containers))
	for _, containerName := range containers {
		if containerName == "*" {
			return []string{"*"}
		}
		if containerName == "" {
			continue
		}
		if _, ok := seen[containerName]; ok {
			continue
		}
		seen[containerName] = struct{}{}
		targets = append(targets, containerName)
	}
	if len(targets) == 0 {
		return []string{"*"}
	}
	sort.Strings(targets)
	return targets
}

func (m *Manager) run(ctx context.Context, j *Job, requestedContainers []string) {
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
		_, items, err := plan.Build(m.fs, c, blobs)
		if err != nil {
			m.finish(j, StateFailed, "plan blobs in "+c+": "+err.Error())
			return
		}
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
	j.setTotals(filesTotal, bytesTotal)
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
				if errors.Is(err, localfs.ErrChtimesUnsupported) {
					// File is on disk; mtime persisted to sidecar so future
					// syncs still skip. Surface as a warning, treat as done.
					j.emit(Event{
						Type:      EventFileWarning,
						Container: it.Container,
						BlobName:  it.BlobName,
						Size:      it.Size,
						Reason:    "chtimes unsupported on this filesystem; intended mtime saved to sidecar",
					})
					return it.Size, nil
				}
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
			failed := atomic.AddInt64(&filesFailed, 1)
			j.emit(Event{
				Type:        EventFileFailed,
				Container:   it.Container,
				BlobName:    it.BlobName,
				BytesDone:   atomic.LoadInt64(&bytesDone),
				BytesTotal:  bytesTotal,
				FilesDone:   int(atomic.LoadInt64(&filesDone)),
				FilesTotal:  filesTotal,
				FilesFailed: int(failed),
				Reason:      r.Err.Error(),
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
	j.emit(Event{Type: EventJobDone, State: st, Reason: reason})
	j.mu.Lock()
	j.cancel = nil
	for ch := range j.subscribers {
		close(ch)
		delete(j.subscribers, ch)
	}
	j.mu.Unlock()
}
