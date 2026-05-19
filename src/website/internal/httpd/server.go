// Package httpd wires up all HTTP endpoints + serves the embedded SPA.
package httpd

import (
	"context"
	"encoding/json"
	"fmt"
	"io/fs"
	"net/http"
	"strings"

	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/job"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
	"github.com/dinowang/action-camera-drain/src/website/internal/plan"
)

// Server bundles dependencies and exposes Handler() for net/http.
type Server struct {
	store    azblob.Storage
	fs       *localfs.FS
	jobs     *job.Manager
	frontend fs.FS // embedded "web" filesystem
}

func New(store azblob.Storage, lfs *localfs.FS, mgr *job.Manager, frontend fs.FS) *Server {
	return &Server{store: store, fs: lfs, jobs: mgr, frontend: frontend}
}

// Handler returns the root mux.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("/api/containers", s.handleContainers)
	mux.HandleFunc("/api/containers/", s.handleContainerBlobs)
	mux.HandleFunc("/api/jobs", s.handleJobs)
	mux.HandleFunc("/api/jobs/", s.handleJobByID)
	mux.Handle("/", http.FileServer(http.FS(s.frontend)))
	return logMiddleware(mux)
}

func logMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		next.ServeHTTP(w, r)
	})
}

// ----- containers -----------------------------------------------------------

type containerSummary struct {
	Name         string `json:"name"`
	RemoteCount  int    `json:"remoteCount"`
	PendingCount int    `json:"pendingCount"`
	SkippedCount int    `json:"skippedCount"`
	PendingBytes int64  `json:"pendingBytes"`
}

func (s *Server) handleContainers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	names, err := s.store.ListContainers(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	out := make([]containerSummary, 0, len(names))
	for _, c := range names {
		blobs, err := s.store.ListBlobs(r.Context(), c)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
		sum, _ := plan.Build(s.fs, c, blobs)
		out = append(out, containerSummary{
			Name:         sum.Container,
			RemoteCount:  sum.RemoteCount,
			PendingCount: sum.PendingCount,
			SkippedCount: sum.SkippedCount,
			PendingBytes: sum.PendingBytes,
		})
	}
	writeJSON(w, http.StatusOK, out)
}

// ----- container blobs ------------------------------------------------------

type blobView struct {
	Name        string `json:"name"`
	Size        int64  `json:"size"`
	MtimeMillis int64  `json:"mtimeMillis,omitempty"`
	Status      string `json:"status"`
	Reason      string `json:"reason,omitempty"`
}

func (s *Server) handleContainerBlobs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	// Path: /api/containers/{name}/blobs
	path := strings.TrimPrefix(r.URL.Path, "/api/containers/")
	parts := strings.SplitN(path, "/", 2)
	if len(parts) < 2 || parts[1] != "blobs" {
		http.NotFound(w, r)
		return
	}
	containerName := parts[0]
	blobs, err := s.store.ListBlobs(r.Context(), containerName)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	_, items := plan.Build(s.fs, containerName, blobs)
	out := make([]blobView, 0, len(items))
	for _, it := range items {
		out = append(out, blobView{
			Name:        it.BlobName,
			Size:        it.Size,
			MtimeMillis: it.MtimeMillis,
			Status:      string(it.Status),
			Reason:      it.SkipReason,
		})
	}
	writeJSON(w, http.StatusOK, out)
}

// ----- jobs -----------------------------------------------------------------

type startJobReq struct {
	Containers []string `json:"containers"`
}

type startJobResp struct {
	ID string `json:"id"`
}

func (s *Server) handleJobs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req startJobReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil && err.Error() != "EOF" {
		http.Error(w, "bad json: "+err.Error(), http.StatusBadRequest)
		return
	}
	j, err := s.jobs.Start(context.Background(), req.Containers)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusAccepted, startJobResp{ID: j.ID})
}

func (s *Server) handleJobByID(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/jobs/")
	parts := strings.SplitN(path, "/", 2)
	if len(parts) == 0 || parts[0] == "" {
		http.NotFound(w, r)
		return
	}
	id := parts[0]
	switch {
	case len(parts) == 2 && parts[1] == "events" && r.Method == http.MethodGet:
		s.handleJobEvents(w, r, id)
	case len(parts) == 1 && r.Method == http.MethodDelete:
		if s.jobs.Cancel(id) {
			w.WriteHeader(http.StatusNoContent)
		} else {
			http.NotFound(w, r)
		}
	default:
		http.NotFound(w, r)
	}
}

func (s *Server) handleJobEvents(w http.ResponseWriter, r *http.Request, id string) {
	j := s.jobs.Get(id)
	if j == nil {
		http.NotFound(w, r)
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(http.StatusOK)

	ch, history, unsub := j.Subscribe(64)
	defer unsub()

	// Replay history.
	for _, ev := range history {
		writeSSE(w, ev)
	}
	flusher.Flush()

	for {
		select {
		case <-r.Context().Done():
			return
		case ev, ok := <-ch:
			if !ok {
				return
			}
			writeSSE(w, ev)
			flusher.Flush()
		}
	}
}

func writeSSE(w http.ResponseWriter, ev job.Event) {
	payload, err := json.Marshal(ev)
	if err != nil {
		return
	}
	fmt.Fprintf(w, "event: %s\ndata: %s\n\n", ev.Type, payload)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
