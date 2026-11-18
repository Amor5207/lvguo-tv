// SPDX-License-Identifier: GPL-3.0-only
// Android v5 playback signer adapted from N3urda/hongguoTV (18c37c2).
// This is independent of the existing v3 signer and its behavior.
package sign

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"math/bits"
	"math/rand"
	"strconv"
	"strings"
	"time"
)

const v5SboxHex = "53290fe9e51f316f90f74a7e034d367784ab49237cd0135cdc632c322fd3be0e2624a417ecd9151a7ff64f60730a87f9445ec6ada2f865d22b696c4188f24c0df37b75510b939f6bc2fe6883e68bc8c722181ba0bdb08c9871b50c9a16b46eea8eed20ba4b8a14e0fcf1468dbbef05a93e5a9d7dcf06898635c1fd99f5de287a80c594daaab19b6279fbd75070cb52eb2555c456e39e7839853874e2f0013ac0b64742d8ca10e4b79c955de8b843ce4ed464a6a72ed508091c92e7b3eedd1e1dd12afac321618fa3d65f9167c982f43d66a53c6a1997585b346dbf9672ff37cdbc073b2dae11453304df00815476b2a13fccdb30b957a8acafe127400248591238922563e36406d37a24100b7902a028044e218453c2a399199c9d13dc40c55ba67e502273850ebdc1a2e2188b8f2d9e3cb909b8c46a2a9f4a6dd93007eafa65b7b3a881aa908cc74f0ad76bbbb58617c37012ca05a469fd44e62eecf1e4272ce860c0ae5eadf99b20d8f0234174006f3f110d7657d01645d4eff8f71471cbfe1cb2f52648727bf3c9de55153e031b4d61757db63dbf7782eeafe9018a8891362fb14759ac32bab03b349aeb5a377c5156be4c8dfbe13a684bda39cf836697c894545fe0f2491acea9fc6252f4ff1e0fe72bd2df6e67a7f635e5bcc6d1a5dd951d89960887a18eb40c785cdb1f80ed42299333ab98cdd67f316cd54346585dccd759fb722789b08807cc0fda293f7e5e1819bb0353517020b31d3d00374952bea2c421e862040a9ec892a05b266b7a0d0cc316f483b81af39715657ffe32ad8de0c13077646035236ee5363e5f900e9a7cf850a81cd9960b6df73317226f99eaa6fff15cec34acb98246639b918ab2851466a7cdfc4ad1b52c2ae95a58d3734e47ddd5b7560193f644ef9c408b7dee39b4d6aabf4de761e3252d4c7ba3843187745d55e6a5db05d42b45caed43c0c5d0b64bfa9d94a4a9d8f2f0e1a108dcfd288fd2bc1fc7bd675412138169cb09249875ba3b68cfb11180afae104fc9789571c6dfce02413886796ae21e3c488cc21b9f423a066c2eabf98e2fdeeb5776e4f5d09a515ba58cbbab3e37b09fd4f6d9244df80512e84cd7956be301cf64f1429d58577cc241f3a2c655144f81aaf95a4e108da894b38970561ae9002619fbbac91fa922ed202bfc62cb08e0de2a7f3631435f8b47c8a3c563b4db4071ce74c46da6fa696a9e282d1e825d663b93bfd56c48dce1884bb10fccea063929f746443fd830a4232e53da21a1f2d1bdfebc3a02c10a1b8ad31d04fd616590d272ac737e98eb33af60528716bee78ec0ee8699031177277d9cff32542c67caf5f44591681335ae0e178f3409a7803db83892dde5b9b7f0c750e24aef077badd678a09b850b84ec1597e4963cb5e675df1c0d6fb618496ecd76c35c798325590c2fb27a5e"

func hashF13V5(query, body []byte, ts uint32) ([]byte, error) {
	v := iv(query, body, ts)
	if (v & 15) != 12 {
		return nil, errors.New("unsupported hash branch")
	}
	base0 := uint32(0x9d4a97c2)
	count := base0 + ts
	var tt [64]uint32
	for i := 0; i < 64; i++ {
		tt[i] = rotr(hashTable[i], int((count+1)&255))
	}
	shift := uint((count + 2) & 7)
	pad := make([]byte, 4)
	for i, x := range []uint32{0xFA, 0x45, 0x61, 0xD7} {
		pad[i] = byte(((x | (x << 8)) >> shift) & 0xFF)
	}
	data := cat(query, body, le32(ts), pad, Unhex("00000000000001a0"))
	n := len(data) / 4
	di := make([]uint32, n+112)
	for i := 0; i < n; i++ {
		di[i] = binary.BigEndian.Uint32(data[i*4:])
	}
	for i := 0; i < 112; i++ {
		x, y := di[i+1], di[i+14]
		di[n+i] = di[i] + di[i+9] + (rotl(x, 14) ^ rotl(x, 25) ^ (x >> 3)) + (rotl(y, 13) ^ rotl(y, 15) ^ (y >> 10))
	}
	var initial, d [8]uint32
	for i := 0; i < 8; i++ {
		initial[i] = rotr(initialSrc[i], int(count&31))
	}
	d = initial
	c := [11]int{96, 1, 3, 4, 0, 6, 5, 2, 0, 7, 7}
	for i := 0; i < c[0]; i++ {
		base := base0 + uint32(i)
		n1 := ((d[c[3]] ^ d[c[4]]) & d[c[1]]) ^ d[c[3]]
		n2 := rotl(d[c[1]], 26) ^ rotl(d[c[1]], 21) ^ rotl(d[c[1]], 7)
		n4 := di[base&127] + n1 + n2 + tt[base&63] + d[c[5]]
		n5 := rotl(d[c[2]], 30) ^ rotl(d[c[2]], 19) ^ rotl(d[c[2]], 10)
		n6 := (d[c[2]] & d[c[6]]) | ((d[c[2]] | d[c[6]]) & d[c[7]])
		old := d[c[9]]
		last := d[7]
		copy(d[1:], d[0:7])
		d[0] = last
		d[c[10]] = n5 + n6 + n4
		d[c[8]] = old + n4
	}
	ret := make([]byte, 32)
	for i := 0; i < 8; i++ {
		binary.BigEndian.PutUint32(ret[i*4:], d[i]+initial[i])
	}
	folded := make([]byte, 16)
	for i := 0; i < 16; i++ {
		folded[i] = ret[i] ^ ret[16+i]
	}
	return cat(folded, le32(sumMd5(folded)|0x20)), nil
}

func newAesV5(key []byte, ts uint32) *aesV3 {
	a := &aesV3{}
	word := int(ts & 3)
	sb := Unhex(v5SboxHex)
	for i := 0; i < 256; i++ {
		a.box[i] = int(sb[word*256+i])
	}
	a.con = [4][4]int{{2, 0, 3, 1}, {1, 3, 2, 0}, {3, 2, 1, 0}, {2, 3, 1, 0}}[word]
	a.order = [4][16]int{
		{0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11},
		{0, 9, 14, 11, 4, 13, 2, 3, 8, 1, 6, 15, 12, 5, 10, 7},
		{0, 9, 14, 3, 4, 13, 2, 15, 8, 1, 6, 11, 12, 5, 10, 7},
		{0, 9, 14, 15, 4, 13, 2, 3, 8, 1, 6, 11, 12, 5, 10, 7},
	}[word]
	initial := [4]uint32{0xbbb5de72, 0xd4638d5b, 0x92a5f73b, 0x89f8a1c9}[word]
	init4 := le32(initial)
	var ex [48]int
	for i := 0; i < 16; i++ {
		ex[i] = int(init4[i%4]^key[i%len(key)]) & 255
	}
	rounds := 8
	for i := 4; i < 12; i++ {
		at := 4 * (i - 1)
		x, y, z, w := ex[at], ex[at+1], ex[at+2], ex[at+3]
		if i&3 == 0 {
			t := int(((initial >> uint(rounds&24)) ^ uint32(a.box[y])) & 255)
			y = a.box[z]
			z = a.box[w]
			w = a.box[x]
			x = t
		}
		rounds += 2
		ex[at+4] = x ^ ex[at-12]
		ex[at+5] = y ^ ex[at-11]
		ex[at+6] = z ^ ex[at-10]
		ex[at+7] = w ^ ex[at-9]
	}
	for i := 0; i < 12; i++ {
		a.keys[i] = [4]int{ex[i*4], ex[i*4+1], ex[i*4+2], ex[i*4+3]}
	}
	return a
}

func heliosV5(ts uint32, rnd int) string {
	asciiHex := []byte(Hex(MD5(cat(le32(uint32(rnd)), []byte("8662")))))
	words := make([]uint64, 4)
	for i := range words {
		words[i] = binary.LittleEndian.Uint64(asciiHex[i*8:])
	}
	table := []uint64{words[0]}
	b0 := words[0]
	b8 := words[1]
	words = words[2:]
	for i := 0; i < 34; i++ {
		x8 := bits.RotateLeft64(b8, -8) + b0
		x8 ^= uint64(i)
		words = append(words, x8)
		x8 ^= bits.RotateLeft64(b0, -61)
		table = append(table, x8)
		b0 = x8
		b8 = words[0]
		words = words[1:]
	}
	text := []byte(strconv.Itoa(int(ts)) + "-646637794-8662")
	padding := 16 - len(text)%16
	data := append(append([]byte{}, text...), bytes.Repeat([]byte{byte(padding)}, padding)...)
	out := le32(uint32(rnd))
	for at := 0; at < len(data); at += 16 {
		a := binary.LittleEndian.Uint64(data[at:])
		b := binary.LittleEndian.Uint64(data[at+8:])
		for i := 0; i < 34; i++ {
			b = table[i] ^ (a + bits.RotateLeft64(b, -8))
			a = b ^ bits.RotateLeft64(a, -61)
		}
		buf := make([]byte, 16)
		binary.LittleEndian.PutUint64(buf, a)
		binary.LittleEndian.PutUint64(buf[8:], b)
		out = append(out, buf...)
	}
	return B64Encode(out)
}

func encryptV5(a *aesV3, source, ivb []byte) ([]byte, error) {
	if len(source) < 248 {
		return nil, errors.New("v5 carrier too short")
	}
	wire := [8]uint{6, 5, 3, 4, 2, 7, 1, 0}
	compact := [8]uint{1, 7, 3, 6, 2, 0, 4, 5}
	plain := make([]byte, 32)
	for i := 0; i < 31; i++ {
		for j := 0; j < 8; j++ {
			plain[i] |= ((source[i*8+j] >> wire[j]) & 1) << compact[j]
		}
	}
	plain[31] = 1
	first := a.block(xorBytes(plain[:16], ivb))
	key := cat(first, a.block(xorBytes(plain[16:], first)))
	out := append([]byte(nil), source...)
	for i := 0; i < 31; i++ {
		for j := 0; j < 8; j++ {
			at := i*8 + j
			mask := byte(1 << wire[j])
			out[at] = (out[at] &^ mask) | (((key[i] >> compact[j]) & 1) << wire[j])
		}
	}
	return cat([]byte{key[len(key)-1]}, out), nil
}
func medusaV5(rawURL string, body []byte, ts uint32, s *PlaybackSigner, who map[string]string, random func() float64) (string, error) {
	bodyMD5 := make([]byte, 16)
	if body != nil {
		bodyMD5 = MD5(body)
	}
	query := []byte("")
	if i := strings.IndexByte(rawURL, '?'); i >= 0 {
		query = []byte(rawURL[i+1:])
	}
	qh := SM3(query)
	f13, err := hashF13V5(qh, bodyMD5, ts)
	if err != nil {
		return "", err
	}
	flags := make([]byte, 8)
	version := who["version_name"]
	did := who["device_id"]
	device := new(proto).sint(1, 1).sint(2, 2).str(3, "8662").str(4, did).str(5, s.processNonce).str(6, "!noperm!").str(12, "Asia/Shanghai,8").str(13, "zh_CN").str(22, who["os_version"]).sint(25, s.processStarted).str(30, who["device_type"]).str(31, "HONOR").str(32, who["device_type"]).str(34, "HONOR").get()
	env := new(proto).str(6, "v04.10.03.04-bugfix").str(8, who["channel"]).bytes(12, device).str(14, version).get()
	nested := new(proto).sint(1, 1).sint(2, 1).sint(3, 1).sint(5, 1).get()
	magic := Unhex("2d4b4fca49750d433fb5ae2c226dcc56")
	key := Unhex("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d")
	msg := new(proto).bytes(1, magic).sint(2, 5).sint(3, int64(u32rand(random))).str(4, "8662").str(5, did).str(6, "646637794").str(7, version).str(8, "v04.10.03-ml-android").sint(9, 0x040a0300).bytes(10, flags).sint(12, int64(ts)).bytes(13, f13).bytes(14, head(qh, 6)).bytes(15, nested).sint(17, int64(ts)).str(20, "none").sint(21, 377).bytes(23, env).str(24, `{"cmr":16777216,"cmr2":16777216,"un_h":11,"vpn":0,"kd":0,"dyn":"","do":1,"tk":true}`).get()
	nonce := u32rand(random)
	hash := SM3(cat(key, le32(nonce), key))
	high := (nonce >> 16) & 255
	seed := le32(^((((high << 11) | (nonce >> 24)) ^ (high >> 5)) ^ high))
	transformed := cat(flags, xmxor(msg, hash))
	for i, j := 0, len(transformed)-1; i < j; i, j = i+1, j-1 {
		transformed[i], transformed[j] = transformed[j], transformed[i]
	}
	for i := range transformed {
		transformed[i] ^= seed[(^i)&3]
	}
	check := uint32(0x18000001) | (uint32(qh[0]&63) << 14) | (uint32(f13[0]&63) << 8)
	packed := cat([]byte{0xca}, le32(u32rand(random)), le32(check), transformed, []byte{byte(nonce >> 16), byte(nonce >> 24)})
	encrypted, err := encryptV5(newAesV5(MD5(key[:16]), ts), packed, MD5(key[16:]))
	if err != nil {
		return "", err
	}
	base := cat(le32(5), magic)
	prefix := make([]byte, len(base))
	stamp := le32(ts)
	for i := range base {
		prefix[i] = base[i] ^ stamp[i&3]
	}
	return base64.StdEncoding.EncodeToString(cat(prefix, []byte{byte(nonce), byte(nonce >> 8), 0, 1}, encrypted)), nil
}

// SignMobileV5 signs the final ordered query and exact compressed request body.
func (s *PlaybackSigner) SignMobileV5(base string, values Params, body []byte, nowMs int64, random func() float64) (*Signed, error) {
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
	who := map[string]string{}
	for _, p := range values {
		who[p.K] = p.V
	}
	if who["device_id"] != s.deviceID {
		return nil, errors.New("播放设备身份不匹配")
	}
	for _, k := range []string{"version_name", "device_type", "os_version", "channel"} {
		if who[k] == "" {
			return nil, errors.New("缺少播放设备参数")
		}
	}
	for offset := int64(0); offset < 1024; offset++ {
		ticket := nowMs + offset
		query := append(Params{}, values...).Set("_rticket", strconv.FormatInt(ticket, 10))
		encoded := EncodeParams(query)
		if iv(SM3([]byte(encoded)), MD5(body), ts)&15 != 12 {
			continue
		}
		u := base + "?" + encoded
		// Kotlin consumes the Gorgon nonce even though v5 does not transmit it.
		_ = int(random() * 65536)
		h := heliosV5(ts, int(u32rand(random)))
		med, err := medusaV5(u, body, ts, s, who, random)
		if err != nil {
			return nil, err
		}
		return &Signed{URL: u, Headers: [][2]string{{"x-khronos", strconv.FormatUint(uint64(ts), 10)}, {"x-helios", h}, {"x-medusa", med}, {"x-ss-stub", strings.ToUpper(Hex(MD5(body)))}, {"Content-Type", "application/json; charset=utf-8"}}}, nil
	}
	return nil, errors.New("无法生成 v5 播放签名")
}
