package cenc

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"errors"
)

func u32(d []byte, o int) uint32 { return binary.BigEndian.Uint32(d[o:]) }
func tag(s string) uint32        { return binary.BigEndian.Uint32([]byte(s)) }

var (
	tMoov, tTrak, tMdia, tMinf, tStbl = tag("moov"), tag("trak"), tag("mdia"), tag("minf"), tag("stbl")
	tStsd, tStsz, tStco, tCo64, tStsc = tag("stsd"), tag("stsz"), tag("stco"), tag("co64"), tag("stsc")
	tSenc, tSaiz, tSaio, tSgpd, tSbgp = tag("senc"), tag("saiz"), tag("saio"), tag("sgpd"), tag("sbgp")
	tSinf, tFrma, tFree               = tag("sinf"), tag("frma"), tag("free")
)

var visual = map[uint32]bool{tag("encv"): true, tag("avc1"): true, tag("avc3"): true, tag("hvc1"): true, tag("hev1"): true,
	tag("bvc2"): true, tag("av01"): true, tag("dvh1"): true}
var audio = map[uint32]bool{tag("enca"): true, tag("mp4a"): true, tag("ac-3"): true, tag("ec-3"): true}

type box struct {
	start, payStart, end int
	typ                  uint32
}

func boxes(d []byte, start, end int) []box {
	var out []box
	i := start
	for i+8 <= end {
		size := int(u32(d, i))
		hdr := 8
		if size == 1 {
			if i+16 > end {
				break
			}
			size = int(binary.BigEndian.Uint64(d[i+8:]))
			hdr = 16
		} else if size == 0 {
			size = end - i
		}
		if size < hdr || i+size > end {
			break
		}
		out = append(out, box{i, i + hdr, i + size, u32(d, i+4)})
		i += size
	}
	return out
}

func find(d []byte, start, end int, t uint32) *box {
	for _, b := range boxes(d, start, end) {
		if b.typ == t {
			bb := b
			return &bb
		}
	}
	return nil
}

type trak struct {
	sizes  []int
	chunks []int64
	stsc   [][3]int
	ivs    [][]byte
	subs   [][]int
	stbl   box
	stsd   *box
}

func parseTrak(d []byte, tr box) *trak {
	mdia := find(d, tr.payStart, tr.end, tMdia)
	if mdia == nil {
		return nil
	}
	minf := find(d, mdia.payStart, mdia.end, tMinf)
	if minf == nil {
		return nil
	}
	stbl := find(d, minf.payStart, minf.end, tStbl)
	if stbl == nil {
		return nil
	}
	t := &trak{stbl: *stbl, stsd: find(d, stbl.payStart, stbl.end, tStsd)}
	stsz := find(d, stbl.payStart, stbl.end, tStsz)
	if stsz == nil {
		return nil
	}
	fixed := int(u32(d, stsz.payStart+4))
	n := int(u32(d, stsz.payStart+8))
	t.sizes = make([]int, n)
	for i := range t.sizes {
		if fixed > 0 {
			t.sizes[i] = fixed
		} else {
			t.sizes[i] = int(u32(d, stsz.payStart+12+i*4))
		}
	}
	stco, co64 := find(d, stbl.payStart, stbl.end, tStco), false
	if stco == nil {
		stco, co64 = find(d, stbl.payStart, stbl.end, tCo64), true
	}
	if stco == nil {
		return nil
	}
	nc := int(u32(d, stco.payStart+4))
	t.chunks = make([]int64, nc)
	for i := range t.chunks {
		if co64 {
			t.chunks[i] = int64(binary.BigEndian.Uint64(d[stco.payStart+8+i*8:]))
		} else {
			t.chunks[i] = int64(u32(d, stco.payStart+8+i*4))
		}
	}
	stsc := find(d, stbl.payStart, stbl.end, tStsc)
	if stsc == nil {
		return nil
	}
	ne := int(u32(d, stsc.payStart+4))
	t.stsc = make([][3]int, ne)
	for i := range t.stsc {
		o := stsc.payStart + 8 + i*12
		t.stsc[i] = [3]int{int(u32(d, o)), int(u32(d, o+4)), int(u32(d, o+8))}
	}
	if senc := find(d, stbl.payStart, stbl.end, tSenc); senc != nil {
		vf := u32(d, senc.payStart)
		ivSize := int(vf & 0xFF)
		if ivSize == 0 {
			ivSize = 8
		}
		cnt := int(u32(d, senc.payStart+4))
		t.ivs, t.subs = make([][]byte, cnt), make([][]int, cnt)
		off := senc.payStart + 8
		for i := 0; i < cnt; i++ {
			t.ivs[i] = d[off : off+ivSize]
			off += ivSize
			if vf&2 != 0 {
				sn := int(binary.BigEndian.Uint16(d[off:]))
				off += 2
				lst := make([]int, sn*2)
				for k := 0; k < sn; k++ {
					lst[k*2] = int(binary.BigEndian.Uint16(d[off:]))
					lst[k*2+1] = int(u32(d, off+2))
					off += 6
				}
				t.subs[i] = lst
			}
		}
	}
	return t
}

// sampleOffsets 按 stsc 展开每个样本的偏移。
func (t *trak) sampleOffsets() []int64 {
	var offs []int64
	si := 0
	for ci := range t.chunks {
		spc := -1
		for _, e := range t.stsc {
			if ci+1 >= e[0] {
				spc = e[1]
			}
		}
		if spc < 0 {
			break
		}
		off := t.chunks[ci]
		for k := 0; k < spc && si < len(t.sizes); k++ {
			offs = append(offs, off)
			off += int64(t.sizes[si])
			si++
		}
	}
	return offs
}

func setType(d []byte, at int, t uint32) { binary.BigEndian.PutUint32(d[at+4:], t) }

// Decrypt 原地解密整个 mp4：样本按 senc 的 IV 做 AES-CTR，moov 等长改写（sinf/senc 等换成 free，encv 改回真实编码）。
func Decrypt(data, key []byte) error {
	var moov *box
	for _, b := range boxes(data, 0, len(data)) {
		if b.typ == tMoov {
			bb := b
			moov = &bb
			break
		}
	}
	if moov == nil {
		return errors.New("缺少视频索引")
	}
	blk, err := aes.NewCipher(key)
	if err != nil {
		return err
	}
	var traks []*trak
	for _, b := range boxes(data, moov.payStart, moov.end) {
		if b.typ == tTrak {
			if t := parseTrak(data, b); t != nil {
				traks = append(traks, t)
			}
		}
	}
	if len(traks) == 0 {
		return errors.New("视频索引不完整")
	}
	for _, t := range traks {
		if t.ivs == nil {
			continue
		}
		offs := t.sampleOffsets()
		n := len(offs)
		if len(t.ivs) < n {
			n = len(t.ivs)
		}
		for k := 0; k < n; k++ {
			off, size := int(offs[k]), t.sizes[k]
			if off < 0 || off+size > len(data) {
				return errors.New("视频样本越界")
			}
			iv := make([]byte, 16)
			copy(iv, t.ivs[k][:min(8, len(t.ivs[k]))])
			var sub []int
			if k < len(t.subs) {
				sub = t.subs[k]
			}
			if sub == nil {
				cipher.NewCTR(blk, iv).XORKeyStream(data[off:off+size], data[off:off+size])
				continue
			}
			ks := make([]byte, size)
			cipher.NewCTR(blk, iv).XORKeyStream(ks, ks)
			pos := 0
			for i := 0; i+1 < len(sub); i += 2 {
				pos += sub[i]
				for j := 0; j < sub[i+1]; j++ {
					data[off+pos+j] ^= ks[pos+j]
				}
				pos += sub[i+1]
			}
		}
	}
	for _, t := range traks {
		patchStbl(data, t)
	}
	return nil
}

func patchStbl(d []byte, t *trak) {
	for _, b := range boxes(d, t.stbl.payStart, t.stbl.end) {
		switch b.typ {
		case tSenc, tSaiz, tSaio, tSgpd, tSbgp:
			setType(d, b.start, tFree)
		case tStsd:
			for _, e := range boxes(d, b.payStart+8, b.end) {
				var real uint32
				switch {
				case visual[e.typ]:
					if sinf := findDeep(d, e); sinf != nil {
						if frma := find(d, sinf.payStart, sinf.end, tFrma); frma != nil {
							real = u32(d, frma.payStart)
						}
						setType(d, sinf.start, tFree)
					}
				case audio[e.typ]:
					if sinf := findDeep(d, e); sinf != nil {
						real = tag("mp4a")
						setType(d, sinf.start, tFree)
					}
				}
				if real != 0 {
					setType(d, e.start, real)
				}
			}
		}
	}
}

// findDeep 在样本条目里找 sinf：条目头部长度不固定，需要探测第一个子 box。
func findDeep(d []byte, e box) *box {
	limit := e.payStart + 140
	if limit > e.end-8 {
		limit = e.end - 8
	}
	for o := e.payStart; o < limit; o++ {
		sz := int(u32(d, o))
		if sz < 8 || sz > e.end-o {
			continue
		}
		okTag := true
		for k := 4; k < 8; k++ {
			if c := d[o+k]; c < 32 || c >= 127 {
				okTag = false
				break
			}
		}
		if !okTag {
			continue
		}
		for _, b := range boxes(d, o, e.end) {
			if b.typ == tSinf {
				bb := b
				return &bb
			}
		}
		return nil
	}
	return nil
}
