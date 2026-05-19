// Package web exposes the embedded SPA assets.
package web

import (
	"embed"
	"io/fs"
)

//go:embed index.html app.css app.js
var assets embed.FS

// FS returns the embedded filesystem rooted at the package.
func FS() fs.FS { return assets }
