package cenc

// Index decrypts arbitrary byte ranges without downloading a complete episode.
// The sample tables remain at their original file offsets; only encryption box
// types in moov are replaced, exactly as in Decrypt.
import (
	"crypto/aes"
	"crypto/cipher"
	"errors"
	"sort"
)

type sample struct {
	off  int64
	size int
	iv   []byte
	subs []int
}
type Index struct {
	moov    []byte
	offset  int64
	block   cipher.Block
	samples []sample
}

func NewIndex(moov []byte, offset int64, key []byte) (idx *Index, err error) {
	defer func() {
		if recover() != nil {
			idx = nil
			err = errors.New("视频索引损坏")
		}
	}()
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	data := append([]byte(nil), moov...)
	top := find(data, 0, len(data), tMoov)
	if top == nil {
		return nil, errors.New("缺少视频索引")
	}
	idx = &Index{moov: data, offset: offset, block: block}
	tracks := 0
	for _, b := range boxes(data, top.payStart, top.end) {
		if b.typ != tTrak {
			continue
		}
		t := parseTrak(data, b)
		if t == nil {
			continue
		}
		tracks++
		offsets := t.sampleOffsets()
		if len(t.ivs) > 0 && len(t.ivs) != len(offsets) {
			return nil, errors.New("视频加密样本索引不完整")
		}
		for i, iv := range t.ivs {
			if i >= len(offsets) || t.sizes[i] < 0 {
				return nil, errors.New("视频样本越界")
			}
			var subs []int
			if i < len(t.subs) {
				subs = t.subs[i]
			}
			idx.samples = append(idx.samples, sample{offsets[i], t.sizes[i], append([]byte(nil), iv...), subs})
		}
		patchStbl(data, t)
	}
	if tracks == 0 || len(idx.samples) == 0 {
		return nil, errors.New("缺少加密视频样本")
	}
	sort.Slice(idx.samples, func(i, j int) bool { return idx.samples[i].off < idx.samples[j].off })
	return idx, nil
}
func (x *Index) Transform(data []byte, offset int64) error {
	end := offset + int64(len(data))
	// Begin at the first sample that can overlap this range.
	at := sort.Search(len(x.samples), func(i int) bool { return x.samples[i].off+int64(x.samples[i].size) > offset })
	for ; at < len(x.samples); at++ {
		s := x.samples[at]
		if s.off >= end {
			break
		}
		if s.subs == nil {
			x.crypt(data, offset, s, 0, s.size)
			continue
		}
		pos := 0
		for j := 0; j+1 < len(s.subs); j += 2 {
			clear, encrypted := s.subs[j], s.subs[j+1]
			if clear < 0 || encrypted < 0 || clear > s.size-pos || encrypted > s.size-pos-clear {
				return errors.New("视频子样本越界")
			}
			pos += clear
			x.crypt(data, offset, s, pos, encrypted)
			pos += encrypted
		}
	}
	a := max(offset, x.offset)
	b := min(end, x.offset+int64(len(x.moov)))
	if a < b {
		copy(data[a-offset:b-offset], x.moov[a-x.offset:b-x.offset])
	}
	return nil
}
func (x *Index) crypt(data []byte, offset int64, s sample, start, size int) {
	a := max(offset, s.off+int64(start))
	b := min(offset+int64(len(data)), s.off+int64(start+size))
	if a >= b {
		return
	}
	skip := a - s.off
	iv := make([]byte, 16)
	copy(iv, s.iv[:min(8, len(s.iv))])
	carry := uint64(skip / 16)
	for i := 15; i >= 0 && carry > 0; i-- {
		v := uint64(iv[i]) + (carry & 255)
		iv[i] = byte(v)
		carry = (carry >> 8) + (v >> 8)
	}
	stream := cipher.NewCTR(x.block, iv)
	prefix := make([]byte, int(skip%16))
	stream.XORKeyStream(prefix, prefix)
	stream.XORKeyStream(data[a-offset:b-offset], data[a-offset:b-offset])
}
