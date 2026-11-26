pub mod passport;
pub mod v5;

use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD};
use base64::Engine;
use md5::{Digest, Md5};
use rand::Rng;

pub const APP_UA: &str = "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)";
pub const VIDEO_UA: &str = "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";

pub const HASH_TABLE: [u32; 64] = [
    0xEBB64FAF, 0x07AADCC2, 0xCF3187BF, 0xE01138FF, 0x6D0BFCFF, 0x5A30A3BE,
    0xB41AD638, 0x34180EB8, 0xF233EB6F, 0xB1A584CC, 0xCCC30DC7, 0x47D1DB51,
    0xD55653DE, 0x70A84FA1, 0x57473C12, 0xF76F0288, 0x2C077F0A, 0xDA0DCAD0,
    0xFBB86F6C, 0xFDC4CF00, 0x688A020D, 0xE676C6A6, 0x8CD6338B, 0x1A3C8D0E,
    0xCCE8B06B, 0x6AD0ED0B, 0xA0522717, 0xDC71AC83, 0x2285DB71, 0xD5B4DDA6,
    0x736F8650, 0x6560306C, 0x617CE2A6, 0xE423417E, 0x0A40E143, 0x544E4032,
    0x88DFFB2A, 0x716C1AE0, 0x4C467A88, 0x05B23BB3, 0xE1D0B866, 0xBAA3DCB8,
    0xAE3374D3, 0xC3381A50, 0x1702F75B, 0xFE6DA368, 0xF0B4CF48, 0x4E0FFBB8,
    0x72AAD10D, 0x26C53A3D, 0xF2BCE0F6, 0xB4557581, 0x4A257FDD, 0x8C3182A2,
    0xAB0B3B86, 0x3D5DFB14, 0x4F103634, 0xD37B52D7, 0x444EFF16, 0xEB0A33D1,
    0x6CA86F6E, 0x00284BA7, 0x08387CFA, 0x5FB37586,
];

pub const SBOX_B64: &str = "+n0Ia5xZs0sEXznQOEqRmQBnpiCf9U2Ccybu3xhmgzOAAxn72f6uqqmwUsYL83klTni0NqxdGieeiNu9PGPsSRXBMB/cuFbUbM3KCUPINaPvHvSW0vwOcnuUhNHqRVpiAj/TEoE0K91+5ijypUYTATsh9mE3KSoN7Yyvv51cuyR2D3XkU4nhmI2xmmVwT1RMWKtub4sjxAcRDLrPoKSO2AU9FLLadMPX577Wf95IFj6FkKFVt3dCIsmGUC4X+WQxLJvxbRxEaOPpqJOXyzJX6+Vxaq3AzMfF/WAdoi1Hp+JRaV56zgpBtpWP97mH4DoGEIq1+FvV8LyS/3wvwugbQOwb2r26mJEMsiuDQTRn+wrYdrVGBVlhI3WQhyrjUBVMrLF5667llUcEaPCGPVGLD8qO5LlO8hKCvA7V9+8oJc9bXelqVQLhM76T5/WtnT45JKji+hdX0HoNCDDWuKON/QeaxB5uImSX0h2wv0VmP2zd2yeApxHcpsVS+MC2yFwAc2B7oBkTqsk1SEvTpM2fmfMQREBUfin0Bh+iq6EvPPavhWI2IX9e3yAas7Tm/3KEj2UmlFp36kN4x0rMLBRrxuh0U/zUHM4xcAMYjJY4MonxOl/X+alpt2M3WMI7w3HLnpIBigtNiJu7T21v4P6lSd5WFgntnMEt7oF92XzRLkJbTcGmXepE/UVOG6E/0YnhfS+q26utWcuxzpooyeD2cDlK1/8w9d28VzsRjbLuALbmGlp8+d7EzS6Au7lMpZ+ECMZvQmzwJ+eLOpxR+2chdUExp8ogQyq3v9l68rX4jCwjg0+PYKAEEzcU4wHFY2ZcdIHfWL1okD3SszT0GZMyKdZJrg1L2AeerB4tC0C4crp2EHGo5FYdSP7lwkeR2ocmnR+Ia8CYviUJlzOjhRZef9xuVOn3qcjow3fQgivsAmKKkg4+sA8F8/GWeDiGNhg8JM8KtFPMYWWkx5TVFX5t73kiNRJqjlIGVXtGZFCV4gzt0xcDopuZ6xz8r9Rzafpf9ywev8jh8592gHFIqpStZPuJxmDDMrNN0uBE3V+oscdoIzTJbRJ/t+sVvqnReJOgDJKk10fjisJwqyZBmnmn2BSFj8BvVtCMEbkuPOKdzw7eA11GPs04Qw8zWtkaZWwiO/wwpojqN6K0jY5RnNZA7vn4hPSul+nKCkVnVwQvg1zVxcSCtqORmB9KrJaBbssbCQivGJVJfVTt+hYxOtq4ZvWl8f4QAQZ0zGPffCgl9s6yT4vlvIdpu4YhBwA25wtQWZsc6GJYGWHyvSdeuh3mmUI9DSq13Fsp8C1MU3tqc04/df9LoTUXVXI5INOw/e8C7Hd+5CvbkMEFnnrUUmskEw==";

pub type Params = Vec<(String, String)>;

pub fn set_param(p: &mut Params, k: &str, v: &str) {
    for (ek, ev) in p.iter_mut() {
        if ek == k {
            *ev = v.to_string();
            return;
        }
    }
    p.push((k.to_string(), v.to_string()));
}

pub fn get_param<'a>(p: &'a [(String, String)], k: &str) -> &'a str {
    p.iter()
        .find(|(ek, _)| ek == k)
        .map(|(_, v)| v.as_str())
        .unwrap_or("")
}

pub struct Signed {
    pub url: String,
    pub headers: Vec<(String, String)>,
}

pub fn md5_bytes(d: &[u8]) -> [u8; 16] {
    let mut hasher = Md5::new();
    hasher.update(d);
    hasher.finalize().into()
}

pub fn b64_encode(b: &[u8]) -> String {
    STANDARD.encode(b)
}

pub fn b64_decode(s: &str) -> Vec<u8> {
    let clean = s.replace('-', "+").replace('_', "/").replace('=', "");
    STANDARD_NO_PAD.decode(clean).unwrap_or_default()
}

pub fn unhex(s: &str) -> Vec<u8> {
    hex::decode(s).unwrap_or_default()
}

#[inline]
pub fn le32(n: u32) -> [u8; 4] {
    n.to_le_bytes()
}

#[inline]
pub fn be32(n: u32) -> [u8; 4] {
    n.to_be_bytes()
}

pub fn enc_component(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for &c in s.as_bytes() {
        match c {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'_' | b'.' | b'-' | b'~' | b'*' => {
                out.push(c as char)
            }
            b' ' => out.push_str("%20"),
            _ => out.push_str(&format!("%{:02X}", c)),
        }
    }
    out
}

pub fn encode_params(p: &[(String, String)]) -> String {
    p.iter()
        .map(|(k, v)| format!("{}={}", enc_component(k), enc_component(v)))
        .collect::<Vec<_>>()
        .join("&")
}

#[inline]
pub fn get_iv(initial: u32, data: &[u8]) -> u32 {
    let mut v = initial;
    for (i, &x) in data.iter().enumerate() {
        let b = x as u32;
        if (i & 1) == 0 {
            v = (v >> 4) ^ v ^ (v << 6) ^ b;
        } else {
            v = !((v >> 7) ^ v ^ (b | (v << 12)));
        }
    }
    v
}

#[inline]
pub fn iv(query: &[u8], body: &[u8], ts: u32) -> u32 {
    get_iv(get_iv(get_iv(0x20230928, query), body), &le32(ts))
}

pub fn branch(query: &str, body: Option<&[u8]>, ts: u32) -> u32 {
    let q = sm3(query.as_bytes());
    let b = match body {
        Some(raw) => md5_bytes(raw),
        None => [0u8; 16],
    };
    (iv(&q, &b, ts) & 15) % 3
}

pub fn sum_md5(data: &[u8]) -> u32 {
    let mut check = 0x20220420u32;
    for i in 0..12 {
        let temp;
        if (i & 1) == 0 {
            temp = (check >> 3) ^ check;
            check = (data[i] as u32) ^ (check << 7);
        } else {
            temp = (check >> 5) ^ check;
            check = !((data[i] as u32) | (check << 11));
        }
        check ^= temp;
    }
    (check | 4) ^ 0x1000000
}

pub const IV_V1: [u32; 6] = [
    0xC4A78580, 0xB3C0FD39, 0xC58C5686, 0xC9AA3BA7, 0xF5A7ADF2, 0x963C2ED1,
];

pub const INITIAL_SRC: [u32; 8] = [
    0x7ABA4FC8,
    0x67166507,
    0x6403FA00,
    0x340F512F,
    984304912,
    0xB31D643A, // (1<<32 - 1289919430) & 0xFFFFFFFF
    0xAB4FABED, // (1<<32 - 1420842003) & 0xFFFFFFFF
    0x804B3850, // (1<<32 - 2142554032) & 0xFFFFFFFF
];

const CHOSEN_TABLE: [[usize; 11]; 6] = [
    [101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3],
    [96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4],
    [96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5],
    [99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6],
    [96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4],
    [100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5],
];

#[inline]
pub fn rotl(x: u32, k: i32) -> u32 {
    x.rotate_left((k.rem_euclid(32)) as u32)
}

#[inline]
pub fn rotr(x: u32, k: i32) -> u32 {
    x.rotate_right((k.rem_euclid(32)) as u32)
}

pub fn hash_f13(query: &[u8], body: &[u8], ts: u32) -> Result<Vec<u8>, String> {
    let v = iv(query, body, ts);
    if (v & 15) % 3 != 0 {
        return Err("unsupported hash branch".to_string());
    }
    let index = ((v & 15) / 3) as usize;
    let base0 = IV_V1[index];
    let count = base0.wrapping_add(ts);
    let mut tt = [0u32; 64];
    for i in 0..64 {
        tt[i] = rotr(HASH_TABLE[i], (count.wrapping_add(1) & 255) as i32);
    }
    let shift = count.wrapping_add(2) & 7;
    let mut pad = [0u8; 4];
    for (i, &x) in [0xFAu32, 0x45, 0x61, 0xD7].iter().enumerate() {
        pad[i] = (((x | (x << 8)) >> shift) & 0xFF) as u8;
    }
    let mut data = Vec::with_capacity(query.len() + body.len() + 16);
    data.extend_from_slice(query);
    data.extend_from_slice(body);
    data.extend_from_slice(&le32(ts));
    data.extend_from_slice(&pad);
    data.extend_from_slice(&[0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0xA0]);

    let n = data.len() / 4;
    let mut di = vec![0u32; n + 112];
    for i in 0..n {
        di[i] = u32::from_be_bytes([data[i * 4], data[i * 4 + 1], data[i * 4 + 2], data[i * 4 + 3]]);
    }
    for i in 0..112 {
        let x = di[i + 1];
        let y = di[i + 14];
        di[n + i] = di[i]
            .wrapping_add(di[i + 9])
            .wrapping_add(rotl(x, 14) ^ rotl(x, 25) ^ (x >> 3))
            .wrapping_add(rotl(y, 13) ^ rotl(y, 15) ^ (y >> 10));
    }
    let mut initial = [0u32; 8];
    for i in 0..8 {
        initial[i] = rotr(INITIAL_SRC[i], (count & 31) as i32);
    }
    let mut d = initial;
    let c = CHOSEN_TABLE[index];
    for i in 0..c[0] {
        let base = base0.wrapping_add(i as u32);
        let n1 = ((d[c[3]] ^ d[c[4]]) & d[c[1]]) ^ d[c[3]];
        let n2 = rotl(d[c[1]], 26) ^ rotl(d[c[1]], 21) ^ rotl(d[c[1]], 7);
        let n4 = di[(base & 127) as usize]
            .wrapping_add(n1)
            .wrapping_add(n2)
            .wrapping_add(tt[(base & 63) as usize])
            .wrapping_add(d[c[5]]);
        let n5 = rotl(d[c[2]], 30) ^ rotl(d[c[2]], 19) ^ rotl(d[c[2]], 10);
        let n6 = (d[c[2]] & d[c[6]]) | ((d[c[2]] | d[c[6]]) & d[c[7]]);
        let old = d[c[9]];
        let last = d[7];
        d.copy_within(0..7, 1);
        d[0] = last;
        d[c[10]] = n5.wrapping_add(n6).wrapping_add(n4);
        d[c[8]] = old.wrapping_add(n4);
    }
    let mut ret = [0u8; 32];
    for i in 0..8 {
        ret[i * 4..i * 4 + 4].copy_from_slice(&d[i].wrapping_add(initial[i]).to_be_bytes());
    }
    let mut folded = [0u8; 16];
    for i in 0..16 {
        folded[i] = ret[i] ^ ret[16 + i];
    }
    let mut out = Vec::with_capacity(20);
    out.extend_from_slice(&folded);
    out.extend_from_slice(&le32(sum_md5(&folded)));
    Ok(out)
}

pub fn gorgon(query: &str, body: Option<&[u8]>, ts: u32, rnd: u32) -> String {
    let b4 = match body {
        Some(b) => md5_bytes(b)[..4].to_vec(),
        None => vec![0u8; 4],
    };
    let mut inp = Vec::with_capacity(20);
    inp.extend_from_slice(&md5_bytes(query.as_bytes())[..4]);
    inp.extend_from_slice(&b4);
    inp.extend_from_slice(&[0u8; 4]);
    inp.extend_from_slice(&le32(67503104));
    inp.extend_from_slice(&be32(ts));

    let key = [
        0x4Ausize,
        0x40,
        0x16,
        ((rnd >> 8) & 255) as usize,
        0x47,
        0x6C,
        1,
        (rnd & 255) as usize,
    ];
    let mut s = [0usize; 256];
    for i in 0..256 {
        s[i] = i;
    }
    let mut j = 0usize;
    for i in 0..256 {
        j = (j + s[i] + key[i % 8]) & 255;
        s[i] = s[j];
    }
    j = 0;
    let mut out = vec![0usize; inp.len()];
    for (n, &byte) in inp.iter().enumerate() {
        let i = n + 1;
        j = (j + s[i]) & 255;
        let y = s[j & 255];
        s[i] = y;
        out[n] = (byte as usize) ^ s[(y + y) & 255];
    }
    let out_snapshot = out.clone();
    for i in 0..out.len() {
        let value = ((out_snapshot[i] >> 4) | (out_snapshot[i] << 4)) & 255;
        let nxt = if i + 1 < out_snapshot.len() {
            out_snapshot[i + 1]
        } else {
            out_snapshot[0]
        };
        out[i] = (!((((nxt ^ value) as u8).reverse_bits() as usize) ^ 20)) & 255;
    }
    let mut res = String::from("8404");
    res.push_str(&hex::encode([(rnd & 255) as u8, ((rnd >> 8) & 255) as u8, 0x40, 1]));
    for x in out {
        res.push_str(&hex::encode([x as u8]));
    }
    res
}

pub fn helios(ts: u32, rnd: u32) -> String {
    let mut seed_in = Vec::with_capacity(8);
    seed_in.extend_from_slice(&le32(rnd));
    seed_in.extend_from_slice(b"8662");
    let ascii_hex = hex::encode(md5_bytes(&seed_in)).into_bytes();
    let mut words = Vec::with_capacity(40);
    for i in 0..4 {
        words.push(u64::from_le_bytes(ascii_hex[i * 8..i * 8 + 8].try_into().unwrap()));
    }
    let mut table = vec![words[0]];
    let mut b0 = words[0];
    let mut b8 = words[1];
    let mut idx = 2usize;
    for i in 0..34 {
        let mut x8 = b8.rotate_right(8).wrapping_add(b0);
        x8 ^= i as u64;
        words.push(x8);
        x8 ^= b0.rotate_right(61);
        table.push(x8);
        b0 = x8;
        b8 = words[idx];
        idx += 1;
    }
    let text = format!("{ts}-1588093228-8662").into_bytes();
    let padding = 16 - (text.len() % 16);
    let mut data = text;
    data.resize(data.len() + padding, padding as u8);
    let mut out = le32(rnd).to_vec();
    for chunk in data.chunks_exact(16) {
        let mut a = u64::from_le_bytes(chunk[0..8].try_into().unwrap());
        let mut b = u64::from_le_bytes(chunk[8..16].try_into().unwrap());
        for &t in &table[..34] {
            b = t ^ a.wrapping_add(b.rotate_right(8));
            a = b ^ a.rotate_right(61);
        }
        out.extend_from_slice(&a.to_le_bytes());
        out.extend_from_slice(&b.to_le_bytes());
    }
    b64_encode(&out)
}

pub struct ProtoBuf {
    pub buf: Vec<u8>,
}

impl ProtoBuf {
    pub fn new() -> Self {
        Self { buf: Vec::new() }
    }
    pub fn varint(&mut self, mut n: u64) {
        while n > 127 {
            self.buf.push(((n & 127) as u8) | 128);
            n >>= 7;
        }
        self.buf.push(n as u8);
    }
    pub fn str(&mut self, tag: i32, v: &str) -> &mut Self {
        self.bytes(tag, v.as_bytes())
    }
    pub fn bytes(&mut self, tag: i32, raw: &[u8]) -> &mut Self {
        if raw.is_empty() {
            return self;
        }
        self.varint(((tag as u64) << 3) | 2);
        self.varint(raw.len() as u64);
        self.buf.extend_from_slice(raw);
        self
    }
    pub fn sint(&mut self, tag: i32, n: i64) -> &mut Self {
        self.varint((tag as u64) << 3);
        let zz = ((n << 1) ^ (n >> 63)) as u64;
        self.varint(zz);
        self
    }
    pub fn flt(&mut self, tag: i32, f: f32) -> &mut Self {
        self.varint(((tag as u64) << 3) | 5);
        self.buf.extend_from_slice(&f.to_bits().to_le_bytes());
        self
    }
    pub fn into_bytes(self) -> Vec<u8> {
        self.buf
    }
}

pub fn xmxor(data: &[u8], key: &[u8]) -> Vec<u8> {
    let n = data.len();
    let mut enc = vec![0i32; n];
    for i in 0..n {
        let at = (i * 4) & 28;
        let d0 = key[at] as i32;
        let d1 = key[at + 1] as i32;
        let v = data[i] as i32;
        let d2 = (((((v << 4) | (v >> 4)) & 255) + d0) as u32) ^ (!0u32);
        let d2 = (!(d2 ^ !0u32)) ^ (d1 as u32); // wait: let's match Go's (^d2) ^ uint32(d1) cleanly!
        let _ = d2;
        let d2_u = (!(((((v << 4) | (v >> 4)) & 255) + d0) as u32)) ^ (d1 as u32);
        let mut e = (d2_u & 255) as i32;
        e = ((e << 3) | (e >> 5)) & 255;
        e = (e + d1) & 255;
        e = (e ^ d0) & 255;
        enc[n - i - 1] = (!e) & 255;
    }
    let last = enc[n - 1] ^ enc[n - 2];
    let first = enc[0];
    enc[0] = ((!last) + first) & 255;
    enc[1] = ((enc[0] ^ enc[n - 1] ^ 254) + enc[1]) & 255;
    enc[2] = (enc[2] + ((last - first) ^ (((enc[1] << 3) | (enc[1] >> 5)) & 255) ^ 2)) & 255;
    for i in 0..(n - 4) {
        let temp = (((enc[i + 2] << 3) | (enc[i + 2] >> 5)) & 255) ^ enc[i + 1] ^ (i as i32 + 3);
        enc[i + 3] = ((!temp) + enc[i + 3]) & 255;
    }
    enc[n - 1] ^= enc[n - 2];
    let mut sum = 0i32;
    for &x in &enc[1..] {
        sum += x;
    }
    enc[0] = ((enc[0] ^ enc[1]) + sum) & 255;
    enc.into_iter().map(|x| x as u8).collect()
}

pub struct AesV3 {
    pub r#box: [usize; 256],
    pub con: [usize; 4],
    pub order: [usize; 16],
    pub keys: [[usize; 4]; 12],
}

impl AesV3 {
    pub fn new(key: &[u8], ts: u32) -> Self {
        let word = (ts & 3) as usize;
        let sb = b64_decode(SBOX_B64);
        let mut r#box = [0usize; 256];
        for i in 0..256 {
            r#box[i] = sb[word * 256 + i] as usize;
        }
        let con = [[1, 0, 2, 3], [2, 0, 3, 1], [0, 1, 3, 2], [1, 0, 2, 3]][word];
        let order = [
            [0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3],
            [0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11],
            [0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15],
            [0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3],
        ][word];
        let initial = [0xCA025DDCu32, 0x823DC546, 0xC9420583, 0xC298225F][word];
        Self::from_parts(r#box, con, order, initial, key)
    }

    pub fn from_parts(
        r#box: [usize; 256],
        con: [usize; 4],
        order: [usize; 16],
        initial: u32,
        key: &[u8],
    ) -> Self {
        let init4 = le32(initial);
        let mut ex = [0usize; 48];
        for i in 0..16 {
            ex[i] = (init4[i % 4] ^ key[i % key.len()]) as usize & 255;
        }
        let mut rounds = 8u32;
        for i in 4..12 {
            let at = 4 * (i - 1);
            let (mut x, mut y, mut z, mut w) = (ex[at], ex[at + 1], ex[at + 2], ex[at + 3]);
            if (i & 3) == 0 {
                let t = (((initial >> (rounds & 24)) ^ (r#box[y] as u32)) & 255) as usize;
                y = r#box[z];
                z = r#box[w];
                w = r#box[x];
                x = t;
            }
            rounds += 2;
            ex[at + 4] = x ^ ex[at - 12];
            ex[at + 5] = y ^ ex[at - 11];
            ex[at + 6] = z ^ ex[at - 10];
            ex[at + 7] = w ^ ex[at - 9];
        }
        let mut keys = [[0usize; 4]; 12];
        for i in 0..12 {
            keys[i] = [ex[i * 4], ex[i * 4 + 1], ex[i * 4 + 2], ex[i * 4 + 3]];
        }
        Self {
            r#box,
            con,
            order,
            keys,
        }
    }

    #[inline]
    fn xt(x: usize) -> usize {
        let mut v = x << 1;
        if (x & 128) != 0 {
            v ^= 0x1B;
        }
        v & 255
    }

    fn add_con(&self, rows: &mut [[usize; 4]; 4], offset: usize) {
        for i in 0..4 {
            for j in 0..4 {
                rows[i][j] ^= self.keys[offset + i][self.con[j]];
            }
        }
    }

    pub fn block(&self, value: &[u8]) -> [u8; 16] {
        let mut rows = [[0usize; 4]; 4];
        for i in 0..4 {
            for j in 0..4 {
                rows[i][j] = value[i * 4 + j] as usize;
            }
        }
        self.add_con(&mut rows, 0);
        for rnd in 1..=2 {
            for i in 0..4 {
                for j in 0..4 {
                    rows[i][j] = self.r#box[rows[i][j]];
                }
            }
            let before = rows;
            for i in 0..4 {
                rows[i] = before[self.con[i]];
            }
            let mut flat = [0usize; 16];
            for i in 0..16 {
                flat[i] = rows[i / 4][i % 4];
            }
            for i in 0..16 {
                rows[i / 4][i % 4] = flat[self.order[i]];
            }
            if rnd == 1 {
                for i in 0..4 {
                    let old = rows[i];
                    rows[i] = [
                        old[self.con[0]],
                        old[self.con[1]],
                        old[self.con[2]],
                        old[self.con[3]],
                    ];
                }
                for i in 0..4 {
                    let t = rows[0][i] ^ rows[1][i] ^ rows[2][i] ^ rows[3][i];
                    let u = rows[0][i];
                    rows[0][i] ^= t ^ Self::xt(rows[0][i] ^ rows[1][i]);
                    rows[1][i] ^= t ^ Self::xt(rows[1][i] ^ rows[2][i]);
                    rows[2][i] ^= t ^ Self::xt(rows[2][i] ^ rows[3][i]);
                    rows[3][i] ^= t ^ Self::xt(rows[3][i] ^ u);
                }
            }
            self.add_con(&mut rows, rnd * 4);
        }
        for i in 0..4 {
            for j in 0..4 {
                rows[i][j] ^= self.keys[4 + i][j];
            }
        }
        let mut out = [0u8; 16];
        for i in 0..16 {
            out[i] = rows[i / 4][i % 4] as u8;
        }
        out
    }

    pub fn encrypt(&self, source: &[u8], ivb: &[u8]) -> Result<Vec<u8>, String> {
        if source.len() < 248 {
            return Err("medusa: input len < 248".to_string());
        }
        let mut plaintext = [0u8; 32];
        for i in 0..31 {
            let s = &source[i * 8..i * 8 + 8];
            plaintext[i] = (((s[0] as usize >> 4) & 2)
                | (s[1] as usize & 64)
                | ((s[2] as usize >> 2) & 1)
                | ((s[3] as usize) << 3 & 128)
                | ((s[4] as usize >> 1) & 4)
                | ((s[5] as usize) << 3 & 16)
                | ((s[6] as usize) << 5 & 32)
                | ((s[7] as usize >> 4) & 8)) as u8;
        }
        plaintext[31] = 1;
        let first = self.block(&xor_bytes(&plaintext[..16], ivb));
        let second = self.block(&xor_bytes(&plaintext[16..], &first));
        let mut key = [0u8; 32];
        key[..16].copy_from_slice(&first);
        key[16..].copy_from_slice(&second);

        let mut out = Vec::with_capacity(1 + source.len());
        out.push(key[31]);
        out.extend_from_slice(source);
        for i in 0..31 {
            let at = 1 + i * 8;
            let k = key[i] as usize;
            out[at] = (((out[at] as usize) & 0xDF) | ((k << 4) & 32)) as u8;
            out[at + 1] = (((out[at + 1] as usize) & 0xBF) | (k & 64)) as u8;
            out[at + 2] = (((out[at + 2] as usize) & 0xFB) | ((k << 2) & 4)) as u8;
            out[at + 3] = (((out[at + 3] as usize) & 0xEF) | ((k >> 3) & 16)) as u8;
            out[at + 4] = (((out[at + 4] as usize) & 0xF7) | ((k + k) & 8)) as u8;
            out[at + 5] = (((out[at + 5] as usize) & 0xFD) | ((k >> 3) & 2)) as u8;
            out[at + 6] = (((out[at + 6] as usize) & 0xFE) | ((k >> 5) & 1)) as u8;
            out[at + 7] = (((out[at + 7] as usize) & 0x7F) | ((k << 4) & 128)) as u8;
        }
        Ok(out)
    }
}

pub fn xor_bytes(a: &[u8], b: &[u8]) -> Vec<u8> {
    a.iter()
        .enumerate()
        .map(|(i, &x)| x ^ b[i % b.len()])
        .collect()
}

pub const SM3_IV: [u32; 8] = [
    0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600, 0xA96F30BC, 0x163138AA, 0xE38DEE4D, 0xB0FB0E4E,
];

#[inline]
fn p0(x: u32) -> u32 {
    x ^ rotl(x, 9) ^ rotl(x, 17)
}

#[inline]
fn p1(x: u32) -> u32 {
    x ^ rotl(x, 15) ^ rotl(x, 23)
}

pub fn sm3(data: &[u8]) -> [u8; 32] {
    let bit_len = (data.len() as u64) * 8;
    let padded = ((data.len() + 9 + 63) / 64) * 64;
    let mut msg = vec![0u8; padded];
    msg[..data.len()].copy_from_slice(data);
    msg[data.len()] = 0x80;
    msg[padded - 8..].copy_from_slice(&bit_len.to_be_bytes());
    let mut v = SM3_IV;
    let mut w = [0u32; 68];
    let mut w1 = [0u32; 64];
    for off in (0..padded).step_by(64) {
        for i in 0..16 {
            w[i] = u32::from_be_bytes([
                msg[off + i * 4],
                msg[off + i * 4 + 1],
                msg[off + i * 4 + 2],
                msg[off + i * 4 + 3],
            ]);
        }
        for j in 16..68 {
            w[j] = p1(w[j - 16] ^ w[j - 9] ^ rotl(w[j - 3], 15))
                ^ rotl(w[j - 13], 7)
                ^ w[j - 6];
        }
        for j in 0..64 {
            w1[j] = w[j] ^ w[j + 4];
        }
        let (mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h) =
            (v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]);
        for j in 0..64 {
            let t = if j < 16 { 0x79CC4519u32 } else { 0x7A879D8A };
            let ss1 = rotl(rotl(a, 12).wrapping_add(e).wrapping_add(rotl(t, (j % 32) as i32)), 7);
            let ss2 = ss1 ^ rotl(a, 12);
            let (ff, gg) = if j < 16 {
                (a ^ b ^ c, e ^ f ^ g)
            } else {
                ((a & b) | (a & c) | (b & c), (e & f) | ((!e) & g))
            };
            let tt1 = ff.wrapping_add(d).wrapping_add(ss2).wrapping_add(w1[j]);
            let tt2 = gg.wrapping_add(h).wrapping_add(ss1).wrapping_add(w[j]);
            d = c;
            c = rotl(b, 9);
            b = a;
            a = tt1;
            h = g;
            g = rotl(f, 19);
            f = e;
            e = p0(tt2);
        }
        v[0] ^= a;
        v[1] ^= b;
        v[2] ^= c;
        v[3] ^= d;
        v[4] ^= e;
        v[5] ^= f;
        v[6] ^= g;
        v[7] ^= h;
    }
    let mut out = [0u8; 32];
    for i in 0..8 {
        out[i * 4..i * 4 + 4].copy_from_slice(&v[i].to_be_bytes());
    }
    out
}

fn device_proto(device_id: &str) -> Vec<u8> {
    let mut p = ProtoBuf::new();
    p.sint(1, 1)
        .sint(2, 2)
        .str(3, "8662")
        .str(4, device_id)
        .str(5, "Ai6svO3PyrwDOUSmO6ZcResxu")
        .str(6, "!noperm!")
        .sint(7, -888888)
        .sint(8, -888888)
        .sint(9, 3)
        .sint(10, -888888)
        .str(11, "!notset!")
        .str(12, "Asia/Shanghai,8")
        .str(13, "zh_CN")
        .sint(14, 4)
        .flt(16, 255.24993896484375)
        .flt(17, 35.58599090576172)
        .flt(18, 3.467449188232422)
        .flt(19, 3.467449188232422)
        .flt(20, 255.1754913330078)
        .flt(21, 42.17544174194336)
        .str(22, "16")
        .sint(23, 41)
        .sint(24, 36)
        .sint(25, 1728388016635)
        .sint(26, 1728388016635)
        .sint(27, 1728388016635)
        .sint(28, 1728388016637)
        .sint(29, -1)
        .str(30, "25053RT47C")
        .str(31, "Redmi")
        .str(32, "25053RT47C")
        .str(33, "25053RT47C")
        .str(34, "Xiaomi")
        .str(35, "Redmi")
        .str(36, "Redmi")
        .sint(38, 31);
    p.into_bytes()
}

pub fn rand_f64() -> f64 {
    rand::thread_rng().gen::<f64>()
}

#[inline]
pub fn u32rand(random: &mut dyn FnMut() -> f64) -> u32 {
    ((random() * 4294967296.0) as u64 & 0xFFFFFFFF) as u32
}

fn medusa(
    raw_url: &str,
    body: Option<&[u8]>,
    ts: u32,
    device_id: &str,
    version: &str,
    clock_ms: i64,
    random: &mut dyn FnMut() -> f64,
) -> Result<String, String> {
    let body_md5 = match body {
        Some(b) => md5_bytes(b),
        None => [0u8; 16],
    };
    let query = raw_url
        .split_once('?')
        .map(|(_, q)| q.as_bytes())
        .unwrap_or(b"");
    let query_sm3 = sm3(query);
    let query_body_ts = hash_f13(&query_sm3, &body_md5, ts)?;

    let mut nested = ProtoBuf::new();
    nested.sint(1, 111).sint(2, 10).sint(3, 694367).sint(5, 586952199);
    let nested_b = nested.into_bytes();

    let message_rand = u32rand(random);
    let env_launch = (random() * 21.0) as i64 + 100;
    let env_pid = (random() * 2000.0) as i64 + 10001;
    let mut sub13 = ProtoBuf::new();
    sub13.sint(1, clock_ms / 1000).sint(2, -2).sint(4, 200);
    let sub13_b = sub13.into_bytes();

    let mut env = ProtoBuf::new();
    env.sint(1, env_launch)
        .sint(2, 146331399)
        .sint(3, 146331396)
        .sint(5, 7)
        .str(6, "v04.06.04.03-bugfix")
        .sint(7, env_pid)
        .bytes(12, &device_proto(device_id))
        .bytes(13, &sub13_b)
        .str(14, version);
    let env_b = env.into_bytes();

    let mut qh_in = Vec::with_capacity(query.len() + 20);
    qh_in.extend_from_slice(query);
    qh_in.extend_from_slice(&body_md5);
    qh_in.extend_from_slice(b"none");
    let query_hash = sm3(&qh_in);

    let mut msg = ProtoBuf::new();
    msg.bytes(1, &unhex("f7e85ffad7d7dc3bd62ac87057cf6118"))
        .sint(2, 3)
        .sint(3, message_rand as i64)
        .str(4, "8662")
        .str(5, device_id)
        .str(6, "1588093228")
        .str(7, version)
        .str(8, "v04.06.04-ml-android")
        .sint(9, 67503104)
        .bytes(10, &unhex("4001000000000000"))
        .sint(12, ts as i64)
        .bytes(13, &query_body_ts)
        .bytes(14, &query_sm3[..6])
        .bytes(15, &nested_b)
        .str(16, "AXYQOS6n2m60x1fVZHIrH3iol")
        .sint(17, ts as i64)
        .bytes(19, &query_hash)
        .str(20, "none")
        .sint(21, 312)
        .bytes(23, &env_b)
        .str(
            24,
            r#"{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}"#,
        );
    let message = msg.into_bytes();

    let r = u32rand(random);
    let sign_key = unhex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5");
    let mut h_in = Vec::with_capacity(sign_key.len() * 2 + 4);
    h_in.extend_from_slice(&sign_key);
    h_in.extend_from_slice(&le32(r));
    h_in.extend_from_slice(&sign_key);
    let h = sm3(&h_in);
    let d1 = (r >> 16) & 255;
    let seed = le32(!((((d1 << 11) | (r >> 24)) ^ (d1 >> 5)) ^ d1));

    let mut transformed = Vec::with_capacity(8 + message.len());
    transformed.extend_from_slice(&unhex("4001000000000000"));
    transformed.extend_from_slice(&xmxor(&message, &h));
    transformed.reverse();
    for i in 0..transformed.len() {
        transformed[i] ^= seed[(!i) & 3];
    }

    let check = (((query_sm3[0] & 63) as u32) << 14)
        | 0x18000001
        | (((query_body_ts[0] & 63) as u32) << 8);
    let packed_rand = u32rand(random);
    let mut packed = Vec::with_capacity(11 + transformed.len());
    packed.push(0x35);
    packed.extend_from_slice(&le32(packed_rand));
    packed.extend_from_slice(&le32(check));
    packed.extend_from_slice(&transformed);
    packed.push(((r >> 16) & 255) as u8);
    packed.push(((r >> 24) & 255) as u8);

    let encrypted = AesV3::new(&unhex("f1593376766ea98d34f31b057a9d5be4"), ts)
        .encrypt(&packed, &unhex("1fe109a4125283f418de9e051a969e12"))?;

    let vb = unhex("03000000f7e85ffad7d7dc3bd62ac87057cf6118");
    let mut prefix = [0u8; 20];
    for i in (0..20).step_by(4) {
        let v = u32::from_le_bytes(vb[i..i + 4].try_into().unwrap()) ^ ts;
        prefix[i..i + 4].copy_from_slice(&v.to_le_bytes());
    }
    let mut final_buf = Vec::with_capacity(24 + encrypted.len());
    final_buf.extend_from_slice(&prefix);
    final_buf.push((r & 255) as u8);
    final_buf.push(((r >> 8) & 255) as u8);
    final_buf.push(0);
    final_buf.push(1);
    final_buf.extend_from_slice(&encrypted);
    Ok(b64_encode(&final_buf))
}

pub fn sign_v3(
    base: &str,
    values: &[(String, String)],
    body: Option<&[u8]>,
    device_id: &str,
    version: &str,
    now_ms: i64,
    random: &mut dyn FnMut() -> f64,
) -> Result<Signed, String> {
    let ts = (now_ms / 1000) as u32;
    for offset in 0..128i64 {
        let ticket = now_ms + offset;
        let mut merged = values.to_vec();
        set_param(&mut merged, "ts", &ts.to_string());
        set_param(&mut merged, "_rticket", &ticket.to_string());
        let query = encode_params(&merged);
        if branch(&query, body, ts) != 0 {
            continue;
        }
        let u = format!("{base}?{query}");
        let rnd = (random() * 65536.0) as u32;
        let med = medusa(&u, body, ts, device_id, version, now_ms, random)?;
        let ua = if body.is_some() { VIDEO_UA } else { APP_UA };
        let mut hd = vec![
            ("User-Agent".into(), ua.into()),
            ("Accept".into(), "application/json; charset=utf-8,application/x-protobuf".into()),
            ("x-ss-req-ticket".into(), ticket.to_string()),
            ("x-tt-request-tag".into(), "t=0;n=0".into()),
            ("sdk-version".into(), "2".into()),
            ("passport-sdk-version".into(), "50561".into()),
            ("x-xs-from-web".into(), "0".into()),
            ("x-khronos".into(), ts.to_string()),
            ("x-ladon".into(), b64_encode(&be32(ts))),
            ("x-argus".into(), b64_encode(&le32(ts))),
            ("x-gorgon".into(), gorgon(&query, body, ts, rnd)),
            ("x-helios".into(), helios(ts, rnd)),
            ("x-medusa".into(), med),
            ("x-tt-dt".into(), String::new()),
        ];
        if let Some(b) = body {
            hd.push(("Content-Type".into(), "application/json; charset=UTF-8".into()));
            hd.push(("x-vc-bdturing-sdk-version".into(), "3.7.2.cn".into()));
            hd.push(("x-ss-stub".into(), hex::encode_upper(md5_bytes(b))));
        }
        return Ok(Signed { url: u, headers: hd });
    }
    Err("无法生成请求签名".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sign::v5::PlaybackSigner;

    #[test]
    fn test_sm3_and_sign_v3() {
        // SM3("abc") official test vector
        assert_eq!(
            hex::encode(sm3(b"abc")),
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0"
        );

        let params = vec![
            ("device_id".into(), "1234567890".into()),
            ("iid".into(), "9876543210".into()),
        ];
        let mut rng_fn = || 0.5f64;
        let signed = sign_v3(
            "https://api5-normal-sinfonlinea.fqnovel.com/test",
            &params,
            None,
            "1234567890",
            "7.2.2.32",
            1700000000000,
            &mut rng_fn,
        )
        .expect("sign_v3 should succeed");
        assert!(signed.url.contains("ts="));
        assert!(signed.headers.iter().any(|(k, _)| k == "x-gorgon"));
        assert!(signed.headers.iter().any(|(k, _)| k == "x-medusa"));
    }

    #[test]
    fn test_playback_signer_v5() {
        let signer = PlaybackSigner::new("1234567890", "7.3.9.32").expect("signer");
        let vals = vec![
            ("device_id".into(), "1234567890".into()),
            ("version_name".into(), "7.3.9.32".into()),
            ("device_type".into(), "SM-N9860".into()),
            ("os_version".into(), "9".into()),
            ("channel".into(), "xiaomi_8662_64".into()),
        ];
        let sig = signer
            .sign_mobile_v5(
                "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1/",
                &vals,
                Some(b"{}"),
                1700000000000,
                None,
            )
            .expect("v5 sign");
        assert!(!sig.headers.is_empty());
        assert!(sig.headers.iter().any(|(k, _)| k.eq_ignore_ascii_case("x-gorgon")));
    }
}
