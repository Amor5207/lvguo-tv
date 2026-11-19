// Package nativestream provides bounded, seekable local decryption for native players.
package nativestream

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"lvguo/internal/cenc"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const blockSize int64 = 512 << 10
const maxBlocks = 40
const maxIndex = 16 << 20
const prefetchBlocks = 3

type block struct {
	done chan struct{}
	data []byte
	err  error
	at   time.Time
}
type Source struct {
	ctx    context.Context
	cancel context.CancelFunc
	url    string
	client *http.Client
	Size   int64
	index  *cenc.Index
	mu     sync.Mutex
	blocks map[int64]*block
}

func Open(ctx context.Context, url string, key []byte) (*Source, error) {
	return OpenWithClient(ctx, url, key, &http.Client{Timeout: 20 * time.Second})
}

func OpenWithClient(ctx context.Context, url string, key []byte, client *http.Client) (*Source, error) {
	if !strings.HasPrefix(url, "https://") {
		return nil, errors.New("视频地址必须使用 HTTPS")
	}
	lifetime, cancel := context.WithCancel(ctx)
	s := &Source{ctx: lifetime, cancel: cancel, url: url, client: client, blocks: map[int64]*block{}}
	head, total, err := s.fetch(0, 63)
	if err != nil {
		cancel()
		return nil, err
	}
	s.Size = total
	if total < 8 {
		cancel()
		return nil, errors.New("视频内容为空")
	}
	if len(key) > 0 {
		var offset int64
		found := false
		for n := 0; n < 128 && offset+8 <= total; n++ {
			var h []byte
			if offset+16 <= int64(len(head)) {
				h = head[offset:]
			} else if offset == 0 {
				h = head
			} else {
				h, _, err = s.fetch(offset, min(total-1, offset+15))
				if err != nil {
					break
				}
			}
			if len(h) < 8 {
				err = io.ErrUnexpectedEOF
				break
			}
			size := int64(binary.BigEndian.Uint32(h))
			if size == 1 {
				if len(h) < 16 {
					err = io.ErrUnexpectedEOF
					break
				}
				size = int64(binary.BigEndian.Uint64(h[8:]))
			}
			if size == 0 {
				size = total - offset
			}
			if size < 8 || size > total-offset {
				err = errors.New("视频容器索引越界")
				break
			}
			if string(h[4:8]) == "moov" {
				if size > maxIndex {
					err = errors.New("视频索引过大")
					break
				}
				var data []byte
				data, _, err = s.fetch(offset, offset+size-1)
				if err == nil {
					s.index, err = cenc.NewIndex(data, offset, key)
				}
				found = err == nil
				break
			}
			offset += size
		}
		if !found {
			cancel()
			if err == nil {
				err = errors.New("找不到视频索引")
			}
			return nil, err
		}
	}
	return s, nil
}
func (s *Source) Close() { s.cancel(); s.client.CloseIdleConnections() }
func (s *Source) fetch(start, end int64) ([]byte, int64, error) {
	req, err := http.NewRequestWithContext(s.ctx, "GET", s.url, nil)
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))
	req.Header.Set("User-Agent", "okhttp/4.12.0")
	req.Header.Set("Accept-Encoding", "identity")
	res, err := s.client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusPartialContent {
		return nil, 0, fmt.Errorf("视频源不支持分段读取（HTTP %d）", res.StatusCode)
	}
	cr := res.Header.Get("Content-Range")
	var a, b, total int64
	if _, err = fmt.Sscanf(cr, "bytes %d-%d/%d", &a, &b, &total); err != nil || a != start || b < start || b > end || total <= b {
		return nil, 0, errors.New("视频分段范围不匹配")
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, end-start+2))
	if err == nil && int64(len(data)) != b-a+1 {
		err = io.ErrUnexpectedEOF
	}
	return data, total, err
}
func (s *Source) get(n int64) ([]byte, error) {
	return s.loadBlock(n)
}

func (s *Source) prefetchAhead(n int64) {
	maxBlock := (s.Size - 1) / blockSize
	for i := int64(1); i <= prefetchBlocks && n+i <= maxBlock; i++ {
		target := n + i
		s.mu.Lock()
		_, exists := s.blocks[target]
		s.mu.Unlock()
		if !exists {
			go func(idx int64) { _, _ = s.loadBlock(idx) }(target)
		}
	}
}

func (s *Source) loadBlock(n int64) ([]byte, error) {
	s.mu.Lock()
	if b := s.blocks[n]; b != nil {
		b.at = time.Now()
		s.mu.Unlock()
		select {
		case <-b.done:
			return b.data, b.err
		case <-s.ctx.Done():
			return nil, s.ctx.Err()
		}
	}
	b := &block{done: make(chan struct{}), at: time.Now()}
	s.blocks[n] = b
	s.mu.Unlock()
	start := n * blockSize
	data, total, err := s.fetch(start, min(s.Size-1, start+blockSize-1))
	if err == nil && total != s.Size {
		err = errors.New("视频源在播放中发生变化")
	}
	if err == nil && s.index != nil {
		err = s.index.Transform(data, start)
	}
	s.mu.Lock()
	b.data, b.err = data, err
	close(b.done)
	if err != nil {
		delete(s.blocks, n)
	}
	for len(s.blocks) > maxBlocks {
		var oldest int64 = -1
		var at time.Time
		for k, v := range s.blocks {
			select {
			case <-v.done:
				if k != n && (oldest < 0 || v.at.Before(at)) {
					oldest = k
					at = v.at
				}
			default:
			}
		}
		if oldest < 0 {
			break
		}
		delete(s.blocks, oldest)
	}
	s.mu.Unlock()
	return data, err
}
func (s *Source) ReadAt(p []byte, off int64) (int, error) {
	if off < 0 {
		return 0, errors.New("无效的视频位置")
	}
	if off >= s.Size {
		return 0, io.EOF
	}
	n := 0
	for n < len(p) && off < s.Size {
		data, err := s.get(off / blockSize)
		if err != nil {
			return n, err
		}
		at := off % blockSize
		if at >= int64(len(data)) {
			return n, io.ErrUnexpectedEOF
		}
		count := copy(p[n:], data[at:])
		n += count
		off += int64(count)
	}
	if n < len(p) {
		return n, io.EOF
	}
	return n, nil
}
func (s *Source) Warm() {
	if s.Size <= 0 {
		return
	}
	_, _ = s.loadBlock(0)
	var wg sync.WaitGroup
	maxBlock := (s.Size - 1) / blockSize
	for i := int64(1); i < 3 && i <= maxBlock; i++ {
		wg.Add(1)
		go func(idx int64) {
			defer wg.Done()
			_, _ = s.loadBlock(idx)
		}(i)
	}
	wg.Wait()
}
type streamReader struct{ s *Source }

func (sr streamReader) ReadAt(p []byte, off int64) (int, error) {
	if off >= 0 && off < sr.s.Size {
		sr.s.prefetchAhead(off / blockSize)
	}
	return sr.s.ReadAt(p, off)
}

func (s *Source) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Cache-Control", "private, max-age=600")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("ETag", strconv.Quote(strconv.FormatInt(s.Size, 10)))
	http.ServeContent(w, r, "episode.mp4", time.Time{}, io.NewSectionReader(streamReader{s}, 0, s.Size))
}
