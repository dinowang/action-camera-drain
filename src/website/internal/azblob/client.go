// Package azblob wraps the Azure SDK Storage Blob client behind a small
// interface so the rest of the app (planner, worker pool, HTTP handlers)
// stays decoupled from the SDK and is easy to fake in tests.
package azblob

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strings"

	sdkazblob "github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/container"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/service"

	"github.com/dinowang/action-camera-drain/src/website/internal/config"
)

// BlobInfo is the minimal blob description used downstream.
type BlobInfo struct {
	Name       string
	Size       int64
	ContentMD5 []byte
	// Metadata keys are always lower-cased so callers can do
	// `m["mtime"]` without worrying about how the server returned them.
	Metadata map[string]string
}

// Storage is the contract the rest of the app uses. Implementations:
//   - *Client (real Azure SDK)
//   - test fakes
type Storage interface {
	ListContainers(ctx context.Context) ([]string, error)
	ListBlobs(ctx context.Context, containerName string) ([]BlobInfo, error)
	Download(ctx context.Context, containerName, blobName string, w io.Writer) error
}

// Client is the production implementation backed by the Azure SDK.
type Client struct {
	svc *service.Client
}

// New constructs a Storage from the validated config.
func New(cfg *config.Config) (*Client, error) {
	switch cfg.AzureMode {
	case config.AzureModeConnectionString:
		c, err := sdkazblob.NewClientFromConnectionString(cfg.AzureConnectionString, nil)
		if err != nil {
			return nil, fmt.Errorf("init client from connection string: %w", err)
		}
		return &Client{svc: c.ServiceClient()}, nil
	case config.AzureModeSAS:
		url := fmt.Sprintf("https://%s.blob.core.windows.net/?%s",
			cfg.AzureAccountName, cfg.AzureSASToken)
		svc, err := service.NewClientWithNoCredential(url, nil)
		if err != nil {
			return nil, fmt.Errorf("init client with SAS: %w", err)
		}
		return &Client{svc: svc}, nil
	default:
		return nil, errors.New("unknown Azure auth mode")
	}
}

// ListContainers returns container names visible to the credential.
// Requires List Containers permission on the account-level SAS.
func (c *Client) ListContainers(ctx context.Context) ([]string, error) {
	pager := c.svc.NewListContainersPager(nil)
	var names []string
	for pager.More() {
		page, err := pager.NextPage(ctx)
		if err != nil {
			return nil, fmt.Errorf("list containers: %w", err)
		}
		for _, it := range page.ContainerItems {
			if it != nil && it.Name != nil {
				names = append(names, *it.Name)
			}
		}
	}
	return names, nil
}

// ListBlobs enumerates blobs in a container including metadata.
func (c *Client) ListBlobs(ctx context.Context, containerName string) ([]BlobInfo, error) {
	cc := c.svc.NewContainerClient(containerName)
	pager := cc.NewListBlobsFlatPager(&container.ListBlobsFlatOptions{
		Include: container.ListBlobsInclude{Metadata: true},
	})
	var out []BlobInfo
	for pager.More() {
		page, err := pager.NextPage(ctx)
		if err != nil {
			return nil, fmt.Errorf("list blobs in %s: %w", containerName, err)
		}
		for _, b := range page.Segment.BlobItems {
			if b == nil || b.Name == nil {
				continue
			}
			info := BlobInfo{Name: *b.Name, Metadata: map[string]string{}}
			if b.Properties != nil {
				if b.Properties.ContentLength != nil {
					info.Size = *b.Properties.ContentLength
				}
				if b.Properties.ContentMD5 != nil {
					info.ContentMD5 = append([]byte(nil), b.Properties.ContentMD5...)
				}
			}
			for k, v := range b.Metadata {
				if v == nil {
					continue
				}
				info.Metadata[strings.ToLower(k)] = *v
			}
			out = append(out, info)
		}
	}
	return out, nil
}

// Download streams the blob into w. The caller is responsible for fsync + rename.
func (c *Client) Download(ctx context.Context, containerName, blobName string, w io.Writer) error {
	cc := c.svc.NewContainerClient(containerName)
	bc := cc.NewBlobClient(blobName)
	resp, err := bc.DownloadStream(ctx, nil)
	if err != nil {
		return fmt.Errorf("download %s/%s: %w", containerName, blobName, err)
	}
	body := resp.NewRetryReader(ctx, nil)
	defer body.Close()
	if _, err := io.Copy(w, body); err != nil {
		return fmt.Errorf("copy %s/%s: %w", containerName, blobName, err)
	}
	return nil
}
