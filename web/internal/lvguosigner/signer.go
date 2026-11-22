// Package lvguosigner signs exact upstream requests with the app's native
// libmetasec runtime. It is used for playback and remote
// settings because those signatures change with the native SDK, not with the
// Go signer snapshot.
package lvguosigner

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

const (
	defaultEndpoint   = "http://127.0.0.1:19830/feature-hash"
	signerDownBackoff = 60 * time.Second
)

type Client struct {
	Endpoint string
	Token    string
	HTTP     *http.Client

	mu        sync.Mutex
	downUntil time.Time
}

var (
	defaultOnce sync.Once
	defaultCli  *Client
)

// Default reads LVGUO_SIGNER_URL / LVGUO_SIGNER_TOKEN. Setting the URL to
// "off" or "none" disables native signing so playback keeps its Go fallback.
func Default() *Client {
	defaultOnce.Do(func() {
		raw := strings.TrimSpace(os.Getenv("LVGUO_SIGNER_URL"))
		if raw == "" {
			raw = defaultEndpoint
		}
		if strings.EqualFold(raw, "off") || strings.EqualFold(raw, "none") || strings.EqualFold(raw, "disabled") {
			defaultCli = &Client{Endpoint: strings.ToLower(raw)}
			return
		}
		if !strings.Contains(raw, "/") {
			raw = "http://" + raw
		}
		if !strings.HasSuffix(raw, "/feature-hash") {
			raw = strings.TrimRight(raw, "/") + "/feature-hash"
		}
		defaultCli = &Client{
			Endpoint: raw,
			Token:    strings.TrimSpace(os.Getenv("LVGUO_SIGNER_TOKEN")),
			HTTP: &http.Client{
				Timeout: 5 * time.Second,
				Transport: &http.Transport{
					DialContext:         (&net.Dialer{Timeout: 1500 * time.Millisecond, KeepAlive: 30 * time.Second}).DialContext,
					MaxIdleConnsPerHost: 4,
					IdleConnTimeout:     60 * time.Second,
				},
			},
		}
	})
	return defaultCli
}

func (c *Client) Enabled() bool {
	if c == nil || c.Endpoint == "" {
		return false
	}
	switch strings.ToLower(c.Endpoint) {
	case "off", "none", "disabled":
		return false
	}
	return true
}

// Sign returns the native signature headers for the final URL and exact body
// bytes (already compressed when the request is gzipped).
func (c *Client) Sign(ctx context.Context, rawURL string, body []byte) (http.Header, error) {
	if !c.Enabled() {
		return nil, errors.New("native signer disabled")
	}
	c.mu.Lock()
	if time.Now().Before(c.downUntil) {
		c.mu.Unlock()
		return nil, errors.New("native signer temporarily unavailable")
	}
	c.mu.Unlock()
	payload, err := json.Marshal(struct {
		URL     string `json:"url"`
		BodyHex string `json:"body_hex"`
	}{URL: rawURL, BodyHex: hex.EncodeToString(body)})
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.Endpoint, bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.Token != "" {
		req.Header.Set("X-Signer-Token", c.Token)
	}
	httpCli := c.HTTP
	if httpCli == nil {
		httpCli = &http.Client{Timeout: 5 * time.Second}
	}
	resp, err := httpCli.Do(req)
	if err != nil {
		c.mu.Lock()
		c.downUntil = time.Now().Add(signerDownBackoff)
		c.mu.Unlock()
		return nil, fmt.Errorf("native signer: %w", err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("native signer: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("native signer HTTP %d", resp.StatusCode)
	}
	var out struct {
		Headers map[string]string `json:"headers"`
		Error   string            `json:"error"`
	}
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, fmt.Errorf("native signer response: %w", err)
	}
	if out.Error != "" {
		return nil, fmt.Errorf("native signer: %s", out.Error)
	}
	h := make(http.Header, len(out.Headers))
	for k, v := range out.Headers {
		if strings.TrimSpace(v) != "" {
			h.Set(k, v)
		}
	}
	if h.Get("X-Gorgon") == "" && h.Get("X-Medusa") == "" && h.Get("X-Argus") == "" {
		return nil, errors.New("native signer returned no signature")
	}
	return h, nil
}
