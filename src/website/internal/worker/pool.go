// Package worker provides an adaptive concurrency pool that consumes jobs
// from a channel and scales its in-flight worker count based on observed
// throughput. The algorithm mirrors the Drain-side AdaptiveConcurrency.
package worker

import (
	"context"
	"sync"
	"sync/atomic"
	"time"
)

// Task is the unit of work; returns nil on success, error otherwise.
// `bytes` is what to add to the throughput tracker on completion.
type Task func(ctx context.Context) (bytes int64, err error)

// Result reports task outcome plus identifying info for the dispatcher.
type Result struct {
	Index int
	Bytes int64
	Err   error
}

// Pool runs tasks with adaptive in-flight count.
type Pool struct {
	Min        int
	Max        int
	Sample     time.Duration // sliding window length
	cancelFn   context.CancelFunc
	currentMu  sync.Mutex
	current    int
	tracker    *tracker
}

// New returns a Pool with the given concurrency bounds.
func New(min, max int) *Pool {
	if min < 1 {
		min = 1
	}
	if max < min {
		max = min
	}
	return &Pool{
		Min:     min,
		Max:     max,
		Sample:  3 * time.Second,
		current: min,
		tracker: newTracker(),
	}
}

// Current returns the current in-flight target.
func (p *Pool) Current() int {
	p.currentMu.Lock()
	defer p.currentMu.Unlock()
	return p.current
}

// ThroughputBps returns the most recent throughput estimate in bytes/sec.
func (p *Pool) ThroughputBps() float64 { return p.tracker.bps() }

// Run drains the tasks channel until it is closed *or* ctx is cancelled.
// Results stream out via the results channel; Run closes results when done.
func (p *Pool) Run(ctx context.Context, tasks <-chan Task, results chan<- Result) {
	defer close(results)
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	p.cancelFn = cancel

	// Slot tokens; capacity grows/shrinks dynamically via adjustLoop.
	tokens := make(chan struct{}, p.Max)
	for i := 0; i < p.current; i++ {
		tokens <- struct{}{}
	}

	var wg sync.WaitGroup
	idx := int64(0)
	// Adjust loop: every Sample, decide whether to add/remove a token.
	adjustDone := make(chan struct{})
	go func() {
		defer close(adjustDone)
		t := time.NewTicker(p.Sample)
		defer t.Stop()
		var lastBps float64
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				bps := p.tracker.bps()
				cur := p.Current()
				switch {
				case bps > lastBps*1.05 && cur < p.Max:
					p.setCurrent(cur + 1)
					tokens <- struct{}{}
				case bps < lastBps*0.85 && cur > p.Min:
					// Drain one token (non-blocking; if none free, skip).
					select {
					case <-tokens:
						p.setCurrent(cur - 1)
					default:
					}
				}
				lastBps = bps
			}
		}
	}()

	for task := range tasks {
		select {
		case <-ctx.Done():
			break
		case <-tokens:
		}
		i := atomic.AddInt64(&idx, 1) - 1
		wg.Add(1)
		go func(i int, t Task) {
			defer wg.Done()
			defer func() {
				// Return the token; if pool is shrinking the adjust loop already
				// removed our slot — best-effort put back without blocking.
				select {
				case tokens <- struct{}{}:
				default:
				}
			}()
			start := time.Now()
			b, err := t(ctx)
			p.tracker.observe(b, time.Since(start))
			results <- Result{Index: int(i), Bytes: b, Err: err}
		}(int(i), task)
	}
	wg.Wait()
	cancel()
	<-adjustDone
}

// Cancel signals workers to exit ASAP.
func (p *Pool) Cancel() {
	if p.cancelFn != nil {
		p.cancelFn()
	}
}

func (p *Pool) setCurrent(n int) {
	p.currentMu.Lock()
	defer p.currentMu.Unlock()
	p.current = n
}

// ---- throughput tracker ------------------------------------------------------

type sample struct {
	t time.Time
	b int64
}

type tracker struct {
	mu      sync.Mutex
	samples []sample
	window  time.Duration
}

func newTracker() *tracker {
	return &tracker{window: 5 * time.Second}
}

func (t *tracker) observe(bytes int64, _ time.Duration) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.samples = append(t.samples, sample{t: time.Now(), b: bytes})
	cut := time.Now().Add(-t.window)
	i := 0
	for i < len(t.samples) && t.samples[i].t.Before(cut) {
		i++
	}
	if i > 0 {
		t.samples = t.samples[i:]
	}
}

func (t *tracker) bps() float64 {
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.samples) == 0 {
		return 0
	}
	var b int64
	for _, s := range t.samples {
		b += s.b
	}
	dur := time.Since(t.samples[0].t).Seconds()
	if dur < 0.5 {
		dur = 0.5
	}
	return float64(b) / dur
}
