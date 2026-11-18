// SPDX-License-Identifier: GPL-3.0-only
// Playback signing ported from tsukiyo-zero/hongguoTV kotlin-v0.1.26 Signer.kt.
package sign

import (
	cryptorand "crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"math/rand"
	"strconv"
	"strings"
	"time"
)

const (
	PlaybackUA      = "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
	PlaybackVersion = "7.3.9.32"
)

// PlaybackSigner keeps the process nonce and process start time stable for one
// registered playback device, matching the TV app's Signer lifetime.
type PlaybackSigner struct {
	deviceID       string
	version        string
	processNonce   string
	processStarted int64
}

func NewPlaybackSigner(deviceID, version string) (*PlaybackSigner, error) {
	if deviceID == "" {
		return nil, errors.New("播放签名缺少设备 ID")
	}
	if version == "" {
		version = PlaybackVersion
	}
	nonce := make([]byte, 18)
	if _, err := cryptorand.Read(nonce); err != nil {
		return nil, err
	}
	return &PlaybackSigner{
		deviceID: deviceID, version: version,
		processNonce:   base64.RawURLEncoding.EncodeToString(nonce),
		processStarted: time.Now().UnixMilli(),
	}, nil
}

// SignPlayback signs exactly the final playback query and body. Unlike Sign,
// this TV request shape has no ts query parameter and uses a separate Helios
// random value. The signer state remains fixed across calls.
func (s *PlaybackSigner) SignPlayback(base string, values Params, body []byte, nowMs int64, random func() float64) (*Signed, error) {
	if s == nil || s.deviceID == "" {
		return nil, errors.New("播放签名器未初始化")
	}
	if nowMs <= 0 {
		nowMs = time.Now().UnixMilli()
	}
	if random == nil {
		random = rand.Float64
	}
	ts := uint32(nowMs / 1000)
	for offset := int64(0); offset < 128; offset++ {
		ticket := nowMs + offset
		query := append(Params{}, values...).Set("_rticket", strconv.FormatInt(ticket, 10))
		encoded := EncodeParams(query)
		if branch(encoded, body, ts) != 0 {
			continue
		}
		u := base + "?" + encoded
		gorgonRandom := int(random() * 65536)
		heliosRandom := u32rand(random)
		med, err := medusaPlayback(u, body, ts, s.deviceID, s.version,
			s.processNonce, s.processStarted, nowMs, random)
		if err != nil {
			return nil, err
		}
		headers := [][2]string{
			{"User-Agent", PlaybackUA},
			{"Accept", "application/json; charset=utf-8,application/x-protobuf"},
			{"x-ss-req-ticket", strconv.FormatInt(ticket, 10)},
			{"sdk-version", "2"},
			{"x-xs-from-web", "0"},
			{"x-khronos", strconv.FormatUint(uint64(ts), 10)},
			{"x-ladon", B64Encode(be32(ts))},
			{"x-argus", B64Encode(le32(ts))},
			{"x-gorgon", gorgon(encoded, body, ts, gorgonRandom)},
			{"x-helios", helios(ts, int(heliosRandom))},
			{"x-medusa", med},
		}
		if body != nil {
			headers = append(headers,
				[2]string{"Content-Type", "application/json; charset=utf-8"},
				[2]string{"x-ss-stub", strings.ToUpper(Hex(MD5(body)))},
			)
		}
		return &Signed{URL: u, Headers: headers}, nil
	}
	return nil, errors.New("无法生成播放签名")
}

func playbackDeviceProto(deviceID, nonce string, processStarted int64) []byte {
	return new(proto).sint(1, 1).sint(2, 2).str(3, "8662").str(4, deviceID).
		str(5, nonce).str(6, "!noperm!").sint(7, -888888).sint(8, -888888).
		sint(9, 3).sint(10, -888888).str(11, "!notset!").str(12, "Asia/Shanghai,8").
		str(13, "zh_CN").sint(14, 4).flt(16, 0).flt(17, 0).flt(18, 0).flt(19, 0).
		flt(20, 0).flt(21, 0).str(22, "16").sint(23, 41).sint(24, 36).
		sint(25, processStarted).sint(26, processStarted).sint(27, processStarted).
		sint(28, processStarted).sint(29, -1).str(30, "25053RT47C").str(31, "Redmi").
		str(32, "25053RT47C").str(33, "25053RT47C").str(34, "Xiaomi").
		str(35, "Redmi").str(36, "Redmi").sint(38, 31).get()
}

func medusaPlayback(rawURL string, body []byte, ts uint32, deviceID, version, nonce string,
	processStarted, nowMs int64, random func() float64) (string, error) {
	bodyMD5 := make([]byte, 16)
	if body != nil {
		bodyMD5 = MD5(body)
	}
	var query []byte
	if i := strings.IndexByte(rawURL, '?'); i >= 0 {
		query = []byte(rawURL[i+1:])
	}
	querySM3 := SM3(query)
	queryBodyTS, err := hashF13(querySM3, bodyMD5, ts)
	if err != nil {
		return "", err
	}
	nested := new(proto).sint(1, 111).sint(2, 10).sint(3, 694367).sint(5, 586952199).get()
	messageRand := u32rand(random)
	envLaunch := int64(random()*21) + 100
	envPID := int64(random()*2000) + 10001
	env := new(proto).sint(1, envLaunch).sint(2, 146331399).sint(3, 146331396).
		sint(5, 7).str(6, "v04.06.04.03-bugfix").sint(7, envPID).
		bytes(12, playbackDeviceProto(deviceID, nonce, processStarted)).
		bytes(13, new(proto).sint(1, nowMs/1000).sint(2, -2).sint(4, 200).get()).
		str(14, version).get()
	queryHash := SM3(cat(query, bodyMD5, []byte("none")))
	message := new(proto).
		bytes(1, Unhex("f7e85ffad7d7dc3bd62ac87057cf6118")).
		sint(2, 3).sint(3, int64(messageRand)).str(4, "8662").str(5, deviceID).
		str(6, "1588093228").str(7, version).str(8, "v04.06.04-ml-android").
		sint(9, 67503104).bytes(10, Unhex("4001000000000000")).sint(12, int64(ts)).
		bytes(13, queryBodyTS).bytes(14, head(querySM3, 6)).bytes(15, nested).
		str(16, nonce).sint(17, int64(ts)).bytes(19, queryHash).str(20, "none").
		sint(21, 312).bytes(23, env).
		str(24, `{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,`+
			`"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}`).get()
	r := u32rand(random)
	signKey := Unhex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
	h := SM3(cat(signKey, le32(r), signKey))
	d1 := (r >> 16) & 255
	seed := le32(^((((d1 << 11) | (r >> 24)) ^ (d1 >> 5)) ^ d1))
	transformed := cat(Unhex("4001000000000000"), xmxor(message, h))
	for i, j := 0, len(transformed)-1; i < j; i, j = i+1, j-1 {
		transformed[i], transformed[j] = transformed[j], transformed[i]
	}
	for i := range transformed {
		transformed[i] ^= seed[(^i)&3]
	}
	check := (uint32(querySM3[0]&63) << 14) | 0x18000001 | (uint32(queryBodyTS[0]&63) << 8)
	packedRand := u32rand(random)
	packed := cat([]byte{0x35}, le32(packedRand), le32(check), transformed,
		[]byte{byte((r >> 16) & 255), byte((r >> 24) & 255)})
	encrypted, err := newAesV3(Unhex("f1593376766ea98d34f31b057a9d5be4"), ts).
		encrypt(packed, Unhex("1fe109a4125283f418de9e051a969e12"))
	if err != nil {
		return "", err
	}
	vb := Unhex("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
	prefix := make([]byte, 20)
	for i := 0; i < 20; i += 4 {
		binary.LittleEndian.PutUint32(prefix[i:], binary.LittleEndian.Uint32(vb[i:])^ts)
	}
	return B64Encode(cat(prefix, []byte{byte(r & 255), byte((r >> 8) & 255), 0, 1}, encrypted)), nil
}
