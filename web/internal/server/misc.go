package server

import (
	"bytes"
	"io"
	"sync"
	"time"
)

func bytesReader(b []byte) io.ReadSeeker { return bytes.NewReader(b) }

type bucket struct {
	tokens float64
	at     time.Time
}

// limiter 是按键（IP）计的令牌桶：rate 为每秒补充，burst 为桶容量。
type limiter struct {
	mu sync.Mutex
	m  map[string]*bucket
	n  int
}

func newLimiter() *limiter { return &limiter{m: map[string]*bucket{}} }

func (l *limiter) allow(key string, perMin, burst float64) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if l.n++; l.n%2000 == 0 {
		for k, b := range l.m {
			if now.Sub(b.at) > 10*time.Minute {
				delete(l.m, k)
			}
		}
	}
	b := l.m[key]
	if b == nil {
		b = &bucket{tokens: burst, at: now}
		l.m[key] = b
	}
	b.tokens += now.Sub(b.at).Seconds() * perMin / 60
	if b.tokens > burst {
		b.tokens = burst
	}
	b.at = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}
