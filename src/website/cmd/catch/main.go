// Action Camera Catch — a web service that mirrors Action Camera Drain in
// reverse: pulls media from Azure Blob Storage onto a NAS-mounted volume.
package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/dinowang/action-camera-drain/src/website/internal/azblob"
	"github.com/dinowang/action-camera-drain/src/website/internal/config"
	"github.com/dinowang/action-camera-drain/src/website/internal/httpd"
	"github.com/dinowang/action-camera-drain/src/website/internal/job"
	"github.com/dinowang/action-camera-drain/src/website/internal/localfs"
	"github.com/dinowang/action-camera-drain/src/website/internal/web"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	log.Printf("starting %s", cfg.Redacted())

	store, err := azblob.New(cfg)
	if err != nil {
		log.Fatalf("azblob: %v", err)
	}

	lfs := localfs.New(cfg.DownloadRoot)
	if err := os.MkdirAll(lfs.Root, 0o755); err != nil {
		log.Fatalf("mkdir download root: %v", err)
	}

	mgr := job.NewManager(store, lfs, cfg.MinConcurrency, cfg.MaxConcurrency)

	srv := httpd.New(store, lfs, mgr, web.FS())
	httpSrv := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.HTTPPort),
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 15 * time.Second,
	}

	go func() {
		log.Printf("HTTP listening on %s", httpSrv.Addr)
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("http: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	log.Println("shutting down...")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = httpSrv.Shutdown(ctx)
}
