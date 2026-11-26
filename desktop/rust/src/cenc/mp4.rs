use aes::cipher::{KeyIvInit, StreamCipher};
use aes::Aes128;

type Aes128Ctr = ctr::Ctr128BE<Aes128>;

#[inline]
fn u32_be(d: &[u8], o: usize) -> u32 {
    u32::from_be_bytes([d[o], d[o + 1], d[o + 2], d[o + 3]])
}

#[inline]
fn u64_be(d: &[u8], o: usize) -> u64 {
    u64::from_be_bytes([
        d[o],
        d[o + 1],
        d[o + 2],
        d[o + 3],
        d[o + 4],
        d[o + 5],
        d[o + 6],
        d[o + 7],
    ])
}

#[inline]
const fn tag(s: &[u8; 4]) -> u32 {
    u32::from_be_bytes(*s)
}

const T_MOOV: u32 = tag(b"moov");
const T_TRAK: u32 = tag(b"trak");
const T_MDIA: u32 = tag(b"mdia");
const T_MINF: u32 = tag(b"minf");
const T_STBL: u32 = tag(b"stbl");
const T_STSD: u32 = tag(b"stsd");
const T_STSZ: u32 = tag(b"stsz");
const T_STCO: u32 = tag(b"stco");
const T_CO64: u32 = tag(b"co64");
const T_STSC: u32 = tag(b"stsc");
const T_SENC: u32 = tag(b"senc");
const T_SAIZ: u32 = tag(b"saiz");
const T_SAIO: u32 = tag(b"saio");
const T_SGPD: u32 = tag(b"sgpd");
const T_SBGP: u32 = tag(b"sbgp");
const T_SINF: u32 = tag(b"sinf");
const T_FRMA: u32 = tag(b"frma");
const T_FREE: u32 = tag(b"free");
const T_MP4A: u32 = tag(b"mp4a");

fn is_visual(t: u32) -> bool {
    matches!(
        &t.to_be_bytes(),
        b"encv" | b"avc1" | b"avc3" | b"hvc1" | b"hev1" | b"bvc2" | b"av01" | b"dvh1"
    )
}

fn is_audio(t: u32) -> bool {
    matches!(&t.to_be_bytes(), b"enca" | b"mp4a" | b"ac-3" | b"ec-3")
}

#[derive(Clone, Copy)]
struct BoxRef {
    start: usize,
    pay_start: usize,
    end: usize,
    typ: u32,
}

fn boxes(d: &[u8], start: usize, end: usize) -> Vec<BoxRef> {
    let mut out = Vec::new();
    let mut i = start;
    while i + 8 <= end {
        let mut size = u32_be(d, i) as usize;
        let mut hdr = 8usize;
        if size == 1 {
            if i + 16 > end {
                break;
            }
            let s64 = u64_be(d, i + 8);
            if s64 > (end - i) as u64 {
                break;
            }
            size = s64 as usize;
            hdr = 16;
        } else if size == 0 {
            size = end - i;
        }
        if size < hdr || i + size > end {
            break;
        }
        out.push(BoxRef {
            start: i,
            pay_start: i + hdr,
            end: i + size,
            typ: u32_be(d, i + 4),
        });
        i += size;
    }
    out
}

fn find_box(d: &[u8], start: usize, end: usize, t: u32) -> Option<BoxRef> {
    boxes(d, start, end).into_iter().find(|b| b.typ == t)
}

struct Trak {
    sizes: Vec<usize>,
    chunks: Vec<i64>,
    stsc: Vec<[usize; 3]>,
    ivs: Vec<Vec<u8>>,
    subs: Vec<Vec<usize>>,
    stbl: BoxRef,
}

impl Trak {
    fn sample_offsets(&self) -> Vec<i64> {
        let mut offs = Vec::new();
        let mut si = 0usize;
        for (ci, &chunk_off) in self.chunks.iter().enumerate() {
            let mut spc: isize = -1;
            for e in &self.stsc {
                if ci + 1 >= e[0] {
                    spc = e[1] as isize;
                }
            }
            if spc < 0 {
                break;
            }
            let mut off = chunk_off;
            for _ in 0..(spc as usize) {
                if si >= self.sizes.len() {
                    break;
                }
                offs.push(off);
                off += self.sizes[si] as i64;
                si += 1;
            }
        }
        offs
    }
}

fn parse_trak(d: &[u8], tr: BoxRef) -> Option<Trak> {
    let mdia = find_box(d, tr.pay_start, tr.end, T_MDIA)?;
    let minf = find_box(d, mdia.pay_start, mdia.end, T_MINF)?;
    let stbl = find_box(d, minf.pay_start, minf.end, T_STBL)?;
    let stsz = find_box(d, stbl.pay_start, stbl.end, T_STSZ)?;
    if stsz.pay_start + 12 > stsz.end {
        return None;
    }
    let fixed = u32_be(d, stsz.pay_start + 4) as usize;
    let n = u32_be(d, stsz.pay_start + 8) as usize;
    let mut sizes = Vec::with_capacity(n);
    for i in 0..n {
        if fixed > 0 {
            sizes.push(fixed);
        } else {
            let off = stsz.pay_start + 12 + i * 4;
            if off + 4 > stsz.end {
                return None;
            }
            sizes.push(u32_be(d, off) as usize);
        }
    }

    let (stco, co64) = match find_box(d, stbl.pay_start, stbl.end, T_STCO) {
        Some(b) => (b, false),
        None => (find_box(d, stbl.pay_start, stbl.end, T_CO64)?, true),
    };
    if stco.pay_start + 8 > stco.end {
        return None;
    }
    let nc = u32_be(d, stco.pay_start + 4) as usize;
    let mut chunks = Vec::with_capacity(nc);
    for i in 0..nc {
        if co64 {
            let off = stco.pay_start + 8 + i * 8;
            if off + 8 > stco.end {
                return None;
            }
            chunks.push(u64_be(d, off) as i64);
        } else {
            let off = stco.pay_start + 8 + i * 4;
            if off + 4 > stco.end {
                return None;
            }
            chunks.push(u32_be(d, off) as i64);
        }
    }

    let stsc_box = find_box(d, stbl.pay_start, stbl.end, T_STSC)?;
    if stsc_box.pay_start + 8 > stsc_box.end {
        return None;
    }
    let ne = u32_be(d, stsc_box.pay_start + 4) as usize;
    let mut stsc = Vec::with_capacity(ne);
    for i in 0..ne {
        let o = stsc_box.pay_start + 8 + i * 12;
        if o + 12 > stsc_box.end {
            return None;
        }
        stsc.push([
            u32_be(d, o) as usize,
            u32_be(d, o + 4) as usize,
            u32_be(d, o + 8) as usize,
        ]);
    }

    let mut ivs = Vec::new();
    let mut subs = Vec::new();
    if let Some(senc) = find_box(d, stbl.pay_start, stbl.end, T_SENC) {
        if senc.pay_start + 8 <= senc.end {
            let vf = u32_be(d, senc.pay_start);
            let mut iv_size = (vf & 0xFF) as usize;
            if iv_size == 0 {
                iv_size = 8;
            }
            let cnt = u32_be(d, senc.pay_start + 4) as usize;
            let mut off = senc.pay_start + 8;
            for _ in 0..cnt {
                if off + iv_size > senc.end {
                    return None;
                }
                ivs.push(d[off..off + iv_size].to_vec());
                off += iv_size;
                if (vf & 2) != 0 {
                    if off + 2 > senc.end {
                        return None;
                    }
                    let sn = u16::from_be_bytes([d[off], d[off + 1]]) as usize;
                    off += 2;
                    let mut lst = Vec::with_capacity(sn * 2);
                    for _ in 0..sn {
                        if off + 6 > senc.end {
                            return None;
                        }
                        lst.push(u16::from_be_bytes([d[off], d[off + 1]]) as usize);
                        lst.push(u32_be(d, off + 2) as usize);
                        off += 6;
                    }
                    subs.push(lst);
                } else {
                    subs.push(Vec::new());
                }
            }
        }
    }

    Some(Trak {
        sizes,
        chunks,
        stsc,
        ivs,
        subs,
        stbl,
    })
}

fn set_type(d: &mut [u8], at: usize, t: u32) {
    d[at + 4..at + 8].copy_from_slice(&t.to_be_bytes());
}

fn find_deep_sinf(d: &[u8], e: BoxRef) -> Option<BoxRef> {
    let limit = (e.pay_start + 140).min(e.end.saturating_sub(8));
    for o in e.pay_start..limit {
        let sz = u32_be(d, o) as usize;
        if sz < 8 || sz > e.end - o {
            continue;
        }
        let ok_tag = (4..8).all(|k| d[o + k] >= 32 && d[o + k] < 127);
        if !ok_tag {
            continue;
        }
        for b in boxes(d, o, e.end) {
            if b.typ == T_SINF {
                return Some(b);
            }
        }
        return None;
    }
    None
}

fn patch_stbl(d: &mut [u8], stbl: BoxRef) {
    let children = boxes(d, stbl.pay_start, stbl.end);
    for b in children {
        match b.typ {
            T_SENC | T_SAIZ | T_SAIO | T_SGPD | T_SBGP => set_type(d, b.start, T_FREE),
            T_STSD => {
                let entries = boxes(d, b.pay_start + 8, b.end);
                for e in entries {
                    let mut real = 0u32;
                    if is_visual(e.typ) {
                        if let Some(sinf) = find_deep_sinf(d, e) {
                            if let Some(frma) = find_box(d, sinf.pay_start, sinf.end, T_FRMA) {
                                real = u32_be(d, frma.pay_start);
                            }
                            set_type(d, sinf.start, T_FREE);
                        }
                    } else if is_audio(e.typ) {
                        if let Some(sinf) = find_deep_sinf(d, e) {
                            real = T_MP4A;
                            set_type(d, sinf.start, T_FREE);
                        }
                    }
                    if real != 0 {
                        set_type(d, e.start, real);
                    }
                }
            }
            _ => {}
        }
    }
}

pub fn decrypt_mp4(data: &mut [u8], key: &[u8; 16]) -> Result<(), String> {
    let moov = boxes(data, 0, data.len())
        .into_iter()
        .find(|b| b.typ == T_MOOV)
        .ok_or_else(|| "缺少视频索引".to_string())?;

    let mut traks = Vec::new();
    for b in boxes(data, moov.pay_start, moov.end) {
        if b.typ == T_TRAK {
            if let Some(t) = parse_trak(data, b) {
                traks.push(t);
            }
        }
    }
    if traks.is_empty() {
        return Err("视频索引不完整".to_string());
    }

    for t in &traks {
        if t.ivs.is_empty() {
            continue;
        }
        let offs = t.sample_offsets();
        let n = offs.len().min(t.ivs.len()).min(t.sizes.len());
        for k in 0..n {
            let off = offs[k];
            let size = t.sizes[k];
            if off < 0 || (off as usize) + size > data.len() {
                return Err("视频样本越界".to_string());
            }
            let off = off as usize;
            let mut iv = [0u8; 16];
            let iv_src = &t.ivs[k];
            let copy_len = iv_src.len().min(8);
            iv[..copy_len].copy_from_slice(&iv_src[..copy_len]);

            let sub = t.subs.get(k).filter(|s| !s.is_empty());
            if sub.is_none() {
                let mut cipher = Aes128Ctr::new(key.into(), &iv.into());
                cipher.apply_keystream(&mut data[off..off + size]);
                continue;
            }
            let sub = sub.unwrap();
            let mut ks = vec![0u8; size];
            let mut cipher = Aes128Ctr::new(key.into(), &iv.into());
            cipher.apply_keystream(&mut ks);
            let mut pos = 0usize;
            let mut i = 0usize;
            while i + 1 < sub.len() {
                pos += sub[i];
                let enc_len = sub[i + 1];
                if pos + enc_len > size {
                    return Err("视频子样本越界".to_string());
                }
                for j in 0..enc_len {
                    data[off + pos + j] ^= ks[pos + j];
                }
                pos += enc_len;
                i += 2;
            }
        }
    }

    for t in &traks {
        patch_stbl(data, t.stbl);
    }
    Ok(())
}

#[derive(Clone)]
struct Sample {
    off: i64,
    size: usize,
    iv: Vec<u8>,
    subs: Vec<usize>,
}

pub struct CencIndex {
    moov: Vec<u8>,
    offset: i64,
    key: [u8; 16],
    samples: Vec<Sample>,
}

impl CencIndex {
    pub fn new(moov: &[u8], offset: i64, key: &[u8; 16]) -> Result<Self, String> {
        let mut data = moov.to_vec();
        let top = find_box(&data, 0, data.len(), T_MOOV)
            .ok_or_else(|| "缺少视频索引".to_string())?;
        let mut samples = Vec::new();
        let mut tracks = 0usize;
        let mut stbls = Vec::new();

        for b in boxes(&data, top.pay_start, top.end) {
            if b.typ != T_TRAK {
                continue;
            }
            let Some(t) = parse_trak(&data, b) else {
                continue;
            };
            tracks += 1;
            let offsets = t.sample_offsets();
            if !t.ivs.is_empty() && t.ivs.len() != offsets.len() {
                return Err("视频加密样本索引不完整".to_string());
            }
            for (i, iv) in t.ivs.iter().enumerate() {
                if i >= offsets.len() {
                    return Err("视频样本越界".to_string());
                }
                let subs = t.subs.get(i).cloned().unwrap_or_default();
                samples.push(Sample {
                    off: offsets[i],
                    size: t.sizes[i],
                    iv: iv.clone(),
                    subs,
                });
            }
            stbls.push(t.stbl);
        }

        if tracks == 0 || samples.is_empty() {
            return Err("缺少加密视频样本".to_string());
        }
        for stbl in stbls {
            patch_stbl(&mut data, stbl);
        }
        samples.sort_by_key(|s| s.off);
        Ok(Self {
            moov: data,
            offset,
            key: *key,
            samples,
        })
    }

    pub fn transform(&self, data: &mut [u8], offset: i64) -> Result<(), String> {
        let end = offset + data.len() as i64;
        let mut at = self
            .samples
            .partition_point(|s| s.off + s.size as i64 <= offset);
        while at < self.samples.len() {
            let s = &self.samples[at];
            if s.off >= end {
                break;
            }
            if s.subs.is_empty() {
                self.crypt(data, offset, s, 0, s.size);
            } else {
                let mut pos = 0usize;
                let mut j = 0usize;
                while j + 1 < s.subs.len() {
                    let clear = s.subs[j];
                    let encrypted = s.subs[j + 1];
                    if clear > s.size.saturating_sub(pos)
                        || encrypted > s.size.saturating_sub(pos + clear)
                    {
                        return Err("视频子样本越界".to_string());
                    }
                    pos += clear;
                    self.crypt(data, offset, s, pos, encrypted);
                    pos += encrypted;
                    j += 2;
                }
            }
            at += 1;
        }
        let a = offset.max(self.offset);
        let b = end.min(self.offset + self.moov.len() as i64);
        if a < b {
            let dst_s = (a - offset) as usize;
            let dst_e = (b - offset) as usize;
            let src_s = (a - self.offset) as usize;
            let src_e = (b - self.offset) as usize;
            data[dst_s..dst_e].copy_from_slice(&self.moov[src_s..src_e]);
        }
        Ok(())
    }

    fn crypt(&self, data: &mut [u8], offset: i64, s: &Sample, start: usize, size: usize) {
        let a = offset.max(s.off + start as i64);
        let b = (offset + data.len() as i64).min(s.off + (start + size) as i64);
        if a >= b {
            return;
        }
        let skip = (a - s.off) as u64;
        let mut iv = [0u8; 16];
        let copy_len = s.iv.len().min(8);
        iv[..copy_len].copy_from_slice(&s.iv[..copy_len]);
        let mut carry = skip / 16;
        for i in (0..16).rev() {
            if carry == 0 {
                break;
            }
            let v = (iv[i] as u64) + (carry & 255);
            iv[i] = v as u8;
            carry = (carry >> 8) + (v >> 8);
        }
        let mut cipher = Aes128Ctr::new(&self.key.into(), &iv.into());
        let mut prefix = vec![0u8; (skip % 16) as usize];
        if !prefix.is_empty() {
            cipher.apply_keystream(&mut prefix);
        }
        let s_idx = (a - offset) as usize;
        let e_idx = (b - offset) as usize;
        cipher.apply_keystream(&mut data[s_idx..e_idx]);
    }
}
