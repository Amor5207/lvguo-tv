package server

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const sessionEncAlg = "aes-256-gcm"

type encryptedSessionEnvelope struct {
	V     int    `json:"v"`
	Alg   string `json:"alg"`
	Nonce string `json:"nonce"`
	CT    string `json:"ct"`
}

// loadSessionKey returns a 32-byte AES-256 key for encrypting sessions.json at rest.
// Priority:
//  1. LVGUO_SESSION_KEY env var (recommended in production systemd service), derived via SHA-256.
//  2. Auto-generated 32-byte random key stored at <dataDir>/.session.key (mode 0600).
func loadSessionKey(dataDir string) ([]byte, error) {
	if env := strings.TrimSpace(os.Getenv("LVGUO_SESSION_KEY")); env != "" {
		sum := sha256.Sum256([]byte("lvguo-session-store-v1:" + env))
		return sum[:], nil
	}
	keyPath := filepath.Join(dataDir, ".session.key")
	if raw, err := os.ReadFile(keyPath); err == nil {
		if decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw))); err == nil && len(decoded) == 32 {
			return decoded, nil
		}
	}
	key := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, key); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	tmp := keyPath + ".tmp"
	if err := os.WriteFile(tmp, []byte(base64.StdEncoding.EncodeToString(key)), 0o600); err != nil {
		return nil, err
	}
	if err := os.Rename(tmp, keyPath); err != nil {
		_ = os.Remove(tmp)
		return nil, err
	}
	return key, nil
}

func encryptSessionStore(key []byte, data map[string]map[string]string) ([]byte, error) {
	plain, err := json.Marshal(data)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	ct := gcm.Seal(nil, nonce, plain, []byte("lvguo-sessions-v1"))
	env := encryptedSessionEnvelope{
		V:     1,
		Alg:   sessionEncAlg,
		Nonce: base64.StdEncoding.EncodeToString(nonce),
		CT:    base64.StdEncoding.EncodeToString(ct),
	}
	return json.Marshal(env)
}

// decryptSessionStore decrypts an AES-256-GCM session store.
// If the file is a legacy plaintext sessions.json, it parses it and returns migrated=true
// so the caller immediately rewrites it in encrypted form.
func decryptSessionStore(key, raw []byte) (data map[string]map[string]string, migrated bool, err error) {
	var env encryptedSessionEnvelope
	if json.Unmarshal(raw, &env) == nil && env.V == 1 && env.Alg == sessionEncAlg && env.Nonce != "" && env.CT != "" {
		nonce, err := base64.StdEncoding.DecodeString(env.Nonce)
		if err != nil {
			return nil, false, err
		}
		ct, err := base64.StdEncoding.DecodeString(env.CT)
		if err != nil {
			return nil, false, err
		}
		block, err := aes.NewCipher(key)
		if err != nil {
			return nil, false, err
		}
		gcm, err := cipher.NewGCM(block)
		if err != nil {
			return nil, false, err
		}
		if len(nonce) != gcm.NonceSize() {
			return nil, false, errors.New("invalid session nonce size")
		}
		plain, err := gcm.Open(nil, nonce, ct, []byte("lvguo-sessions-v1"))
		if err != nil {
			return nil, false, err
		}
		var out map[string]map[string]string
		if err := json.Unmarshal(plain, &out); err != nil {
			return nil, false, err
		}
		return out, false, nil
	}

	// Legacy plaintext fallback for zero-downtime migration.
	var legacy map[string]map[string]string
	if err := json.Unmarshal(raw, &legacy); err != nil {
		return nil, false, err
	}
	return legacy, true, nil
}
