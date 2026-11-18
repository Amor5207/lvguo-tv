// Package cenc 提供红果视频的内容密钥还原（spade_a）与 CENC(cenc-aes-ctr) MP4 解密。
// 移植自 Android 端 crypto/Spade.java 与 crypto/Mp4Decryptor.java。
package cenc

import (
	"encoding/base64"
	"encoding/hex"
	"math/bits"
	"strings"
)

var (
	appV2 = []byte("app_v2")
	webV2 = []byte("web_v2")
)

func strncmp0(a, b []byte, n int) bool {
	for k := 0; k < n; k++ {
		var ca, cb byte
		if k < len(a) {
			ca = a[k]
		}
		if k < len(b) {
			cb = b[k]
		}
		if ca != cb {
			return false
		}
		if ca == 0 {
			return true
		}
	}
	return true
}

func s8(v int) int {
	v &= 0xFF
	if v >= 128 {
		return v - 256
	}
	return v
}

// unwrapV1 把 37 字节 spade 还原成 32 字符 hex 的 key 文本；失败返回 ok=false。
func unwrapV1(spade []byte, flag int) (string, bool) {
	L := len(spade)
	if L < 3 {
		return "", false
	}
	bVar5 := int(spade[0]) ^ int(spade[1]) ^ int(spade[2])
	iVar9 := bVar5 - 0x30
	if iVar9 < 1 {
		return "", false
	}
	uVar1 := (L - bVar5) + 0x2F
	if uVar1 < 1 || 1+uVar1 > L {
		return "", false
	}
	dest := make([]byte, uVar1)
	copy(dest, spade[1:1+uVar1])
	if L-iVar9-2 < 0 {
		return "", false
	}
	b16, b14 := spade[L-iVar9-2], spade[L-iVar9-1]
	s1 := make([]byte, iVar9)
	for i := 0; i < iVar9; i++ {
		s1[i] = b14 ^ b16 ^ spade[i+(L-iVar9)]
	}
	if strncmp0(s1, appV2, iVar9) || strncmp0(s1, webV2, iVar9) {
		return "", false
	}
	bb14, bb16 := 0x55, 0xFA
	for i := 0; i < uVar1; i++ {
		b6 := int(dest[i])
		u18 := bits.OnesCount(uint(i))
		b3, b7 := b6, bb14
		if i&1 != 0 {
			b3 = bb16
			b7 = b6
			bb16 = bb14
		}
		var cVar4 int
		if flag != 0 {
			cVar4 = u18 + 0x15
		} else {
			cVar4 = s8(-0x15 - u18)
		}
		dest[i] = byte((cVar4 + (bb16 ^ b6)) & 0xFF)
		bb14 = b7
		bb16 = b3
	}
	b0 := int(dest[0])
	var u11 int
	switch {
	case b0 >= 0x30 && b0 <= 0x39:
		u11 = b0 - 0x30
	case b0 >= 0x61 && b0 <= 0x7A:
		u11 = b0 - 0x57
	default:
		return "", false
	}
	iv9 := uVar1 - (u11 & 0xFF)
	if iv9 < 2 {
		return "", false
	}
	return string(dest[1:iv9]), true
}

// SpadeToKey 把 spade_a(base64) 还原成 16 字节 AES key。
func SpadeToKey(spadeB64 string) []byte {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(spadeB64))
	if err != nil {
		raw, err = base64.RawStdEncoding.DecodeString(strings.TrimRight(spadeB64, "="))
		if err != nil {
			return nil
		}
	}
	h, ok := unwrapV1(raw, 0)
	if !ok || len(h) != 32 {
		return nil
	}
	key, err := hex.DecodeString(h)
	if err != nil || len(key) != 16 {
		return nil
	}
	return key
}
