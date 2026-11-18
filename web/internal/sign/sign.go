// Package sign 实现红果 App 通道的请求签名（Gorgon / Argus / Ladon / Helios / Medusa + SM3）。
//
// 逐行移植自 Android 端 net/Signer.java，并在 sign_test.go 里与 Java 实现的输出逐字节对拍。
// 签名算法任何一处「优化」都会让网关静默拒绝请求，所以保持原样。
package sign

import (
	"bytes"
	"crypto/md5"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"math"
	"math/bits"
	"net/url"
	"strconv"
	"strings"
	"sync"
)

const (
	AppUA   = "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)"
	VideoUA = "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; " +
		"Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
)

// Param 是有序查询参数（签名覆盖参数顺序）。
type Param struct{ K, V string }
type Params []Param

func (p Params) Set(k, v string) Params {
	for i := range p {
		if p[i].K == k {
			p[i].V = v
			return p
		}
	}
	return append(p, Param{k, v})
}

// Signed 是签名结果：完整 URL 与请求头。
type Signed struct {
	URL     string
	Headers [][2]string
}

var (
	sboxOnce sync.Once
	sboxData []byte
)

func sbox() []byte {
	sboxOnce.Do(func() { sboxData = B64Decode(sboxB64) })
	return sboxData
}

// ------------------------------------------------------------ 字节工具

func MD5(d []byte) []byte       { s := md5.Sum(d); return s[:] }
func Hex(b []byte) string       { return hex.EncodeToString(b) }
func Unhex(s string) []byte     { b, _ := hex.DecodeString(s); return b }
func B64Encode(b []byte) string { return base64.StdEncoding.EncodeToString(b) }
func B64Decode(s string) []byte {
	s = strings.NewReplacer("-", "+", "_", "/", "=", "").Replace(s)
	b, _ := base64.RawStdEncoding.DecodeString(s)
	return b
}
func le32(n uint32) []byte        { b := make([]byte, 4); binary.LittleEndian.PutUint32(b, n); return b }
func be32(n uint32) []byte        { b := make([]byte, 4); binary.BigEndian.PutUint32(b, n); return b }
func cat(parts ...[]byte) []byte  { return bytes.Join(parts, nil) }
func head(b []byte, n int) []byte { return b[:n] }

// ------------------------------------------------------------ 编码

// Enc 等价 Python quote_plus(safe="*") 再把 '+'→'%20'、'%7E'→'~'。
func Enc(s string) string {
	var sb strings.Builder
	for _, c := range []byte(s) {
		switch {
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9',
			c == '_', c == '.', c == '-', c == '~', c == '*':
			sb.WriteByte(c)
		case c == ' ':
			sb.WriteString("%20")
		default:
			sb.WriteString("%" + strings.ToUpper(hex.EncodeToString([]byte{c})))
		}
	}
	return sb.String()
}

func EncodeParams(p Params) string {
	parts := make([]string, len(p))
	for i, kv := range p {
		parts[i] = Enc(kv.K) + "=" + Enc(kv.V)
	}
	return strings.Join(parts, "&")
}

// ------------------------------------------------------------ 分支选择 / hashF13

func getIv(initial uint32, data []byte) uint32 {
	v := initial
	for i, x := range data {
		b := uint32(x)
		if i&1 == 0 {
			v = (v >> 4) ^ v ^ (v << 6) ^ b
		} else {
			v = ^((v >> 7) ^ v ^ (b | (v << 12)))
		}
	}
	return v
}

func iv(query, body []byte, ts uint32) uint32 {
	return getIv(getIv(getIv(0x20230928, query), body), le32(ts))
}

func branch(query string, body []byte, ts uint32) uint32 {
	q := SM3([]byte(query))
	b := make([]byte, 16)
	if body != nil {
		b = MD5(body)
	}
	return (iv(q, b, ts) & 15) % 3
}

func sumMd5(data []byte) uint32 {
	check := uint32(0x20220420)
	for i := 0; i < 12; i++ {
		var temp uint32
		if i&1 == 0 {
			temp = (check >> 3) ^ check
			check = uint32(data[i]) ^ (check << 7)
		} else {
			temp = (check >> 5) ^ check
			check = ^(uint32(data[i]) | (check << 11))
		}
		check ^= temp
	}
	return (check | 4) ^ 0x1000000
}

var ivV1 = [6]uint32{0xC4A78580, 0xB3C0FD39, 0xC58C5686, 0xC9AA3BA7, 0xF5A7ADF2, 0x963C2ED1}
var initialSrc = [8]uint32{0x7ABA4FC8, 0x67166507, 0x6403FA00, 0x340F512F, 984304912,
	uint32(0xFFFFFFFF & (1<<32 - 1289919430)), uint32(0xFFFFFFFF & (1<<32 - 1420842003)), uint32(0xFFFFFFFF & (1<<32 - 2142554032))}
var chosenTable = [6][11]int{
	{101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3},
	{96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4},
	{96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5},
	{99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6},
	{96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4},
	{100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5},
}

func rotl(x uint32, k int) uint32 { return bits.RotateLeft32(x, k) }
func rotr(x uint32, k int) uint32 { return bits.RotateLeft32(x, -(k & 31)) }

func hashF13(query, body []byte, ts uint32) ([]byte, error) {
	v := iv(query, body, ts)
	if (v&15)%3 != 0 {
		return nil, errors.New("unsupported hash branch")
	}
	index := int((v & 15) / 3)
	base0 := ivV1[index]
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
	c := chosenTable[index]
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
	return cat(folded, le32(sumMd5(folded))), nil
}

// ------------------------------------------------------------ Gorgon

func bitrev8(x int) int { return int(bits.Reverse8(uint8(x))) }

func gorgon(query string, body []byte, ts uint32, rnd int) string {
	b4 := make([]byte, 4)
	if body != nil {
		b4 = head(MD5(body), 4)
	}
	inp := cat(head(MD5([]byte(query)), 4), b4, make([]byte, 4), le32(67503104), be32(ts))
	key := [8]int{0x4A, 0x40, 0x16, (rnd >> 8) & 255, 0x47, 0x6C, 1, rnd & 255}
	var s [256]int
	for i := range s {
		s[i] = i
	}
	j := 0
	for i := 0; i < 256; i++ {
		j = (j + s[i] + key[i%8]) & 255
		s[i] = s[j]
	}
	j = 0
	out := make([]int, len(inp))
	for n := range inp {
		i := n + 1
		j = (j + s[i]) & 255
		y := s[j&255]
		s[i] = y
		out[n] = int(inp[n]) ^ s[(y+y)&255]
	}
	for i := range out {
		value := ((out[i] >> 4) | (out[i] << 4)) & 255
		nxt := out[0]
		if i+1 < len(out) {
			nxt = out[i+1]
		}
		out[i] = (^(bitrev8(nxt^value) ^ 20)) & 255
	}
	var sb strings.Builder
	sb.WriteString("8404")
	sb.WriteString(Hex([]byte{byte(rnd & 255), byte((rnd >> 8) & 255), 0x40, 1}))
	for _, x := range out {
		sb.WriteString(hex.EncodeToString([]byte{byte(x)}))
	}
	return sb.String()
}

// ------------------------------------------------------------ Helios

func helios(ts uint32, rnd int) string {
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
	text := []byte(strconv.Itoa(int(ts)) + "-1588093228-8662")
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

// ------------------------------------------------------------ Medusa

type proto struct{ buf bytes.Buffer }

func (p *proto) varint(n uint64) {
	for n > 127 {
		p.buf.WriteByte(byte(n&127) | 128)
		n >>= 7
	}
	p.buf.WriteByte(byte(n))
}
func (p *proto) str(tag int, v string) *proto { return p.bytes(tag, []byte(v)) }
func (p *proto) bytes(tag int, raw []byte) *proto {
	if len(raw) == 0 {
		return p
	}
	p.varint(uint64(tag<<3 | 2))
	p.varint(uint64(len(raw)))
	p.buf.Write(raw)
	return p
}
func (p *proto) sint(tag int, n int64) *proto {
	p.varint(uint64(tag << 3))
	if n < 0 {
		p.varint(uint64(-n*2 - 1))
	} else {
		p.varint(uint64(n * 2))
	}
	return p
}
func (p *proto) flt(tag int, f float32) *proto {
	p.varint(uint64(tag<<3 | 5))
	p.buf.Write(le32(math.Float32bits(f)))
	return p
}
func (p *proto) get() []byte { return p.buf.Bytes() }

func deviceProto(deviceID string) []byte {
	return new(proto).sint(1, 1).sint(2, 2).str(3, "8662").str(4, deviceID).
		str(5, "Ai6svO3PyrwDOUSmO6ZcResxu").str(6, "!noperm!").
		sint(7, -888888).sint(8, -888888).sint(9, 3).sint(10, -888888).
		str(11, "!notset!").str(12, "Asia/Shanghai,8").str(13, "zh_CN").sint(14, 4).
		flt(16, 255.24993896484375).flt(17, 35.58599090576172).
		flt(18, 3.467449188232422).flt(19, 3.467449188232422).
		flt(20, 255.1754913330078).flt(21, 42.17544174194336).
		str(22, "16").sint(23, 41).sint(24, 36).
		sint(25, 1728388016635).sint(26, 1728388016635).sint(27, 1728388016635).
		sint(28, 1728388016637).sint(29, -1).
		str(30, "25053RT47C").str(31, "Redmi").str(32, "25053RT47C").str(33, "25053RT47C").
		str(34, "Xiaomi").str(35, "Redmi").str(36, "Redmi").sint(38, 31).get()
}

func xmxor(data, key []byte) []byte {
	n := len(data)
	enc := make([]int, n)
	for i := 0; i < n; i++ {
		at := (i * 4) & 28
		d0, d1 := int(key[at]), int(key[at+1])
		v := int(data[i])
		d2 := uint32(((((v << 4) | (v >> 4)) & 255) + d0))
		d2 = (^d2) ^ uint32(d1)
		e := int(d2 & 255)
		e = ((e << 3) | (e >> 5)) & 255
		e = (e + d1) & 255
		e = (e ^ d0) & 255
		enc[n-i-1] = (^e) & 255
	}
	last := enc[n-1] ^ enc[n-2]
	first := enc[0]
	enc[0] = ((^last) + first) & 255
	enc[1] = ((enc[0] ^ enc[n-1] ^ 254) + enc[1]) & 255
	enc[2] = (enc[2] + ((last - first) ^ (((enc[1] << 3) | (enc[1] >> 5)) & 255) ^ 2)) & 255
	for i := 0; i < n-4; i++ {
		temp := (((enc[i+2] << 3) | (enc[i+2] >> 5)) & 255) ^ enc[i+1] ^ (i + 3)
		enc[i+3] = ((^temp) + enc[i+3]) & 255
	}
	enc[n-1] = enc[n-1] ^ enc[n-2]
	sum := 0
	for i := 1; i < n; i++ {
		sum += enc[i]
	}
	enc[0] = ((enc[0] ^ enc[1]) + sum) & 255
	out := make([]byte, n)
	for i, x := range enc {
		out[i] = byte(x)
	}
	return out
}

// aesV3 是平台自制的分块变换（不是标准 AES），只用于 Medusa 信封。
type aesV3 struct {
	box   [256]int
	con   [4]int
	order [16]int
	keys  [12][4]int
}

func newAesV3(key []byte, ts uint32) *aesV3 {
	a := &aesV3{}
	word := int(ts & 3)
	sb := sbox()
	for i := 0; i < 256; i++ {
		a.box[i] = int(sb[word*256+i])
	}
	a.con = [4][4]int{{1, 0, 2, 3}, {2, 0, 3, 1}, {0, 1, 3, 2}, {1, 0, 2, 3}}[word]
	a.order = [4][16]int{
		{0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3},
		{0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11},
		{0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15},
		{0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3},
	}[word]
	initial := [4]uint32{0xCA025DDC, 0x823DC546, 0xC9420583, 0xC298225F}[word]
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

func xt(x int) int {
	v := x << 1
	if x&128 != 0 {
		v ^= 0x1B
	}
	return v & 255
}

func (a *aesV3) addCon(rows *[4][4]int, offset int) {
	for i := 0; i < 4; i++ {
		for j := 0; j < 4; j++ {
			rows[i][j] ^= a.keys[offset+i][a.con[j]]
		}
	}
}

func (a *aesV3) block(value []byte) []byte {
	var rows [4][4]int
	for i := 0; i < 4; i++ {
		for j := 0; j < 4; j++ {
			rows[i][j] = int(value[i*4+j])
		}
	}
	a.addCon(&rows, 0)
	for rnd := 1; rnd <= 2; rnd++ {
		for i := 0; i < 4; i++ {
			for j := 0; j < 4; j++ {
				rows[i][j] = a.box[rows[i][j]]
			}
		}
		before := rows
		for i := 0; i < 4; i++ {
			rows[i] = before[a.con[i]]
		}
		var flat [16]int
		for i := 0; i < 16; i++ {
			flat[i] = rows[i/4][i%4]
		}
		for i := 0; i < 16; i++ {
			rows[i/4][i%4] = flat[a.order[i]]
		}
		if rnd == 1 {
			for i := 0; i < 4; i++ {
				old := rows[i]
				rows[i] = [4]int{old[a.con[0]], old[a.con[1]], old[a.con[2]], old[a.con[3]]}
			}
			for i := 0; i < 4; i++ {
				t := rows[0][i] ^ rows[1][i] ^ rows[2][i] ^ rows[3][i]
				u := rows[0][i]
				rows[0][i] ^= t ^ xt(rows[0][i]^rows[1][i])
				rows[1][i] ^= t ^ xt(rows[1][i]^rows[2][i])
				rows[2][i] ^= t ^ xt(rows[2][i]^rows[3][i])
				rows[3][i] ^= t ^ xt(rows[3][i]^u)
			}
		}
		a.addCon(&rows, rnd*4)
	}
	for i := 0; i < 4; i++ {
		for j := 0; j < 4; j++ {
			rows[i][j] ^= a.keys[4+i][j]
		}
	}
	out := make([]byte, 16)
	for i := 0; i < 16; i++ {
		out[i] = byte(rows[i/4][i%4])
	}
	return out
}

func xorBytes(a, b []byte) []byte {
	o := make([]byte, len(a))
	for i := range a {
		o[i] = a[i] ^ b[i%len(b)]
	}
	return o
}

func (a *aesV3) encrypt(source, ivb []byte) ([]byte, error) {
	if len(source) < 248 {
		return nil, errors.New("medusa: 输入长度不足 248")
	}
	plaintext := make([]byte, 32)
	for i := 0; i < 31; i++ {
		s := source[i*8 : i*8+8]
		plaintext[i] = byte((int(s[0])>>4)&2 | int(s[1])&64 | (int(s[2])>>2)&1 | (int(s[3])<<3)&128 |
			(int(s[4])>>1)&4 | (int(s[5])<<3)&16 | (int(s[6])<<5)&32 | (int(s[7])>>4)&8)
	}
	plaintext[31] = 1
	first := a.block(xorBytes(plaintext[:16], ivb))
	key := cat(first, a.block(xorBytes(plaintext[16:], first)))
	out := append([]byte{}, source...)
	for i := 0; i < 31; i++ {
		at, k := i*8, int(key[i])
		out[at] = byte(int(out[at])&0xDF | (k<<4)&32)
		out[at+1] = byte(int(out[at+1])&0xBF | k&64)
		out[at+2] = byte(int(out[at+2])&0xFB | (k<<2)&4)
		out[at+3] = byte(int(out[at+3])&0xEF | (k>>3)&16)
		out[at+4] = byte(int(out[at+4])&0xF7 | (k+k)&8)
		out[at+5] = byte(int(out[at+5])&0xFD | (k>>3)&2)
		out[at+6] = byte(int(out[at+6])&0xFE | (k>>5)&1)
		out[at+7] = byte(int(out[at+7])&0x7F | (k<<4)&128)
	}
	return cat([]byte{key[len(key)-1]}, out), nil
}

func u32rand(random func() float64) uint32 {
	return uint32(uint64(random()*4294967296.0) & 0xFFFFFFFF)
}

func medusa(rawURL string, body []byte, ts uint32, deviceID, version string, clockMs int64, random func() float64) (string, error) {
	bodyMd5 := make([]byte, 16)
	if body != nil {
		bodyMd5 = MD5(body)
	}
	var query []byte
	if i := strings.IndexByte(rawURL, '?'); i >= 0 {
		query = []byte(rawURL[i+1:])
	}
	querySm3 := SM3(query)
	queryBodyTs, err := hashF13(querySm3, bodyMd5, ts)
	if err != nil {
		return "", err
	}
	nested := new(proto).sint(1, 111).sint(2, 10).sint(3, 694367).sint(5, 586952199).get()
	messageRand := u32rand(random)
	envLaunch := int64(random()*21) + 100
	envPid := int64(random()*2000) + 10001
	env := new(proto).sint(1, envLaunch).sint(2, 146331399).sint(3, 146331396).
		sint(5, 7).str(6, "v04.06.04.03-bugfix").sint(7, envPid).
		bytes(12, deviceProto(deviceID)).
		bytes(13, new(proto).sint(1, clockMs/1000).sint(2, -2).sint(4, 200).get()).
		str(14, version).get()
	queryHash := SM3(cat(query, bodyMd5, []byte("none")))
	message := new(proto).
		bytes(1, Unhex("f7e85ffad7d7dc3bd62ac87057cf6118")).
		sint(2, 3).sint(3, int64(messageRand)).str(4, "8662").str(5, deviceID).
		str(6, "1588093228").str(7, version).str(8, "v04.06.04-ml-android").
		sint(9, 67503104).bytes(10, Unhex("4001000000000000")).sint(12, int64(ts)).
		bytes(13, queryBodyTs).bytes(14, head(querySm3, 6)).bytes(15, nested).
		str(16, "AXYQOS6n2m60x1fVZHIrH3iol").sint(17, int64(ts)).bytes(19, queryHash).
		str(20, "none").sint(21, 312).bytes(23, env).
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
	check := (uint32(querySm3[0]&63) << 14) | 0x18000001 | (uint32(queryBodyTs[0]&63) << 8)
	packedRand := u32rand(random)
	packed := cat([]byte{0x35}, le32(packedRand), le32(check), transformed, []byte{byte((r >> 16) & 255), byte((r >> 24) & 255)})
	encrypted, err := newAesV3(Unhex("f1593376766ea98d34f31b057a9d5be4"), ts).encrypt(packed, Unhex("1fe109a4125283f418de9e051a969e12"))
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

// ------------------------------------------------------------ SM3

var sm3IV = [8]uint32{0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600, 0xA96F30BC, 0x163138AA, 0xE38DEE4D, 0xB0FB0E4E}

func p0(x uint32) uint32 { return x ^ rotl(x, 9) ^ rotl(x, 17) }
func p1(x uint32) uint32 { return x ^ rotl(x, 15) ^ rotl(x, 23) }

func SM3(data []byte) []byte {
	bitLen := uint64(len(data)) * 8
	padded := ((len(data) + 9 + 63) / 64) * 64
	msg := make([]byte, padded)
	copy(msg, data)
	msg[len(data)] = 0x80
	binary.BigEndian.PutUint64(msg[padded-8:], bitLen)
	v := sm3IV
	var w [68]uint32
	var w1 [64]uint32
	for off := 0; off < padded; off += 64 {
		for i := 0; i < 16; i++ {
			w[i] = binary.BigEndian.Uint32(msg[off+i*4:])
		}
		for j := 16; j < 68; j++ {
			w[j] = p1(w[j-16]^w[j-9]^rotl(w[j-3], 15)) ^ rotl(w[j-13], 7) ^ w[j-6]
		}
		for j := 0; j < 64; j++ {
			w1[j] = w[j] ^ w[j+4]
		}
		a, b, c, d, e, f, g, h := v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]
		for j := 0; j < 64; j++ {
			t := uint32(0x79CC4519)
			if j >= 16 {
				t = 0x7A879D8A
			}
			ss1 := rotl(rotl(a, 12)+e+rotl(t, j%32), 7)
			ss2 := ss1 ^ rotl(a, 12)
			var ff, gg uint32
			if j < 16 {
				ff, gg = a^b^c, e^f^g
			} else {
				ff, gg = (a&b)|(a&c)|(b&c), (e&f)|(^e&g)
			}
			tt1 := ff + d + ss2 + w1[j]
			tt2 := gg + h + ss1 + w[j]
			d, c, b, a = c, rotl(b, 9), a, tt1
			h, g, f, e = g, rotl(f, 19), e, p0(tt2)
		}
		v[0] ^= a
		v[1] ^= b
		v[2] ^= c
		v[3] ^= d
		v[4] ^= e
		v[5] ^= f
		v[6] ^= g
		v[7] ^= h
	}
	out := make([]byte, 32)
	for i := 0; i < 8; i++ {
		binary.BigEndian.PutUint32(out[i*4:], v[i])
	}
	return out
}

// ------------------------------------------------------------ 入口

// Sign 生成签名后的 url + headers。签名覆盖 query 与 body，签完后不能再改参数顺序。
func Sign(base string, values Params, body []byte, deviceID, version string, nowMs int64, random func() float64) (*Signed, error) {
	ts := uint32(nowMs / 1000)
	for offset := int64(0); offset < 128; offset++ {
		ticket := nowMs + offset
		merged := append(Params{}, values...)
		merged = merged.Set("ts", strconv.FormatUint(uint64(ts), 10))
		merged = merged.Set("_rticket", strconv.FormatInt(ticket, 10))
		query := EncodeParams(merged)
		if branch(query, body, ts) != 0 {
			continue
		}
		u := base + "?" + query
		rnd := int(random() * 65536)
		med, err := medusa(u, body, ts, deviceID, version, nowMs, random)
		if err != nil {
			return nil, err
		}
		ua := AppUA
		if body != nil {
			ua = VideoUA
		}
		hd := [][2]string{
			{"User-Agent", ua},
			{"Accept", "application/json; charset=utf-8,application/x-protobuf"},
			{"x-ss-req-ticket", strconv.FormatInt(ticket, 10)},
			{"x-tt-request-tag", "t=0;n=0"},
			{"sdk-version", "2"},
			{"passport-sdk-version", "50561"},
			{"x-xs-from-web", "0"},
			{"x-khronos", strconv.FormatUint(uint64(ts), 10)},
			{"x-ladon", B64Encode(be32(ts))},
			{"x-argus", B64Encode(le32(ts))},
			{"x-gorgon", gorgon(query, body, ts, rnd)},
			{"x-helios", helios(ts, rnd)},
			{"x-medusa", med},
			{"x-tt-dt", ""},
		}
		if body != nil {
			hd = append(hd, [2]string{"Content-Type", "application/json; charset=UTF-8"},
				[2]string{"x-vc-bdturing-sdk-version", "3.7.2.cn"},
				[2]string{"x-ss-stub", strings.ToUpper(Hex(MD5(body)))})
		}
		return &Signed{URL: u, Headers: hd}, nil
	}
	return nil, errors.New("无法生成请求签名")
}

var _ = url.QueryEscape
