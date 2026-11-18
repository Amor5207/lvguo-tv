// Package ui 把 PC 页面打进二进制。
package ui

import (
	"embed"
	"io/fs"
)

//go:embed pc shared
var files embed.FS

// FS 的根下有 pc/ shared/。
func FS() fs.FS { return files }
