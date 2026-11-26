use crate::sign::{b64_encode, get_param, le32, md5_bytes, set_param, sm3, unhex, Params};
use rand::RngCore;
use std::collections::HashMap;

const PASSPORT_SBOX_HEX: &str = "fa7d086b9c59b34b045f39d0384a91990067a6209ff54d827326eedf18668333800319fbd9feaeaaa9b052c60bf379254e78b436ac5d1a279e88dbbd3c63ec4915c1301fdcb856d46ccdca0943c835a3ef1ef496d2fc0e727b9484d1ea455a62023fd31281342bdd7ee628f2a54613013b21f66137292a0ded8cafbf9d5cbb24760f75e45389e1988db19a65704f544c58ab6e6f8b23c407110cbacfa0a48ed8053d14b2da74c3d7e7bed67fde48163e8590a155b7774222c986502e17f964312c9bf16d1c4468e3e9a89397cb3257ebe5716aadc0ccc7c5fd601da22d47a7e251695e7ace0a41b6958ff7b987e03a06108ab5f85bd5f0bc92ff7c2fc2e81b40ec1bdabdba98910cb22b83413467fb0ad876b546055961237590872ae350154cacb179ebaee595470468f0863d518b0fca8ee4b94ef21282bc0ed5f7ef2825cf5b5de96a5502e133be93e7f5ad9d3e3924a8e2fa1757d07a0d0830d6b8a38dfd079ac41e6e226497d21db0bf45663f6cdddb2780a711dca6c552f8c0b6c85c0073607ba01913aac935484bd3a4cd9f99f3104440547e29f4061fa2aba12f3cf6af856236217f5edf201ab3b4e6ff72848f6526945a77ea4378c74acc2c146bc6e87453fcd41cce317003188c96383289f13a5fd7f9a969b7633758c23bc371cb9e92018a0b4d889bbb4f6d6fe0fea549de561609ed9cc12dee817dd97cd12e425b4dc1a65dea44fd454e1ba13fd189e17d2faadbabad59cbb1ce9a28c9e0f670394ad7ff30f5ddbc573b118db2ee00b6e61a5a7cf9dec4cd2e80bbb94ca59f8408c66f426cf027e78b3a9c51fb6721754131a7ca20432ab7bfd97af2b5f88c2c23834f8f60a004133714e301c563665c7481df58bd68903dd2b334f419933229d649ae0d4bd8079eac1e2d0b40b872ba761071a8e4561d48fee5c24791da87269d1f886bc098be25099733a385165e7fdc6e54e9f7a9c8e8c377d0822bec02628a920e3eb00f05f3f19678388636183c24cf0ab453cc6165a4c794d5157e6def792235126a8e5206557b46645095e20cedd31703a29b99eb1cfcafd47369fa5ff72c1ebfc8e1f39f76807148aa94ad64fb89c660c332b34dd2e044dd5fa8b1c7682334c96d127fb7eb15bea9d17893a00c92a4d747e38ac270ab26419a79a7d814858fc06f56d08c11b92e3ce29dcf0ede035d463ecd38430f335ad91a656c223bfc30a688ea37a2b48d8e519cd640eef9f884f4ae97e9ca0a456757042f835cd5c5c482b6a391981f4aac96816ecb1b0908af1895497d54edfa16313adab866f5a5f1fe10010674cc63df7c2825f6ceb24f8be5bc8769bb8621070036e70b50599b1ce862581961f2bd275eba1de699423d0d2ab5dc5b29f02d4c537b6a734e3f75ff4ba1351755723920d3b0fdef02ec777ee42bdb90c1059e7ad4526b2413";
const PASSPORT_ORDERS_HEX: &str = "0f0704000908030a060b050d0e010c020f05080c0009020103070e060b0a0d04060500070c000a04080f010b0d09020e060b02050403080101070a000d0c090e0d070e0f0b0208030c0509010004060a0d0902060f0b0a040807000c0503010e0c090f07060f030e020d0405010b0a000c050a090e0802040407030f01060b000b0f0408020a0700090d06010e03050c0b060a0508020c03070f0e090d0001040906080f050800040a0b030d01020c0e090d0c0604070a03030f00080105020e01000d0f090a0b0e0402080703060c0501080a0c0f0905060b000304020e070d04080f000c0f0e0d0a01060207090503040205080d0b0a0606000e0f070c09030a08040f000b01060d0c0709030e05020a070b050f00020e0108030d0c0609040d070f08050f06040b0a0e0c090002030d0c020704010b0e0e08060f09050003";

const PASSPORT_MD5_CONSTANTS: [u32; 64] = [
    0xa7aefe20, 0x7149f1d6, 0x47e4ca07, 0xe9b58f67, 0x93b924de, 0xc614d0f5, 0x38afe0ef,
    0xb2bbad73, 0xe24444c3, 0x9d3aec9b, 0xdf7b37e4, 0xd8b16d40, 0xf8ac31b8, 0x76b9a90b,
    0x31d833ee, 0x953fce64, 0x353595a4, 0x4609c13b, 0x36925008, 0x8c6d0925, 0x5df5c177,
    0x1cfbf52b, 0x8a4fa7f0, 0x114ca35e, 0x8193f984, 0x7a7a8733, 0x316ab4d5, 0x3c20cfc9,
    0xa6d84453, 0x3a18500c, 0x798ec47a, 0x97a76b28, 0x66c4ff96, 0x51716443, 0x0dd2fc3b,
    0xb5696da7, 0xbbeb3ac5, 0x5c53d204, 0xd32608ce, 0x7279b9ec, 0xf4188ecf, 0xf7d793db,
    0x332cc491, 0xab76ae15, 0x9bebe727, 0x18a01384, 0x5be9f8a7, 0x5f90a754, 0x39b663c0,
    0x36673c83, 0x7c92f514, 0x9d7d94d7, 0xe2e8d9aa, 0x5f7e9ea9, 0x7abd4551, 0x569e05da,
    0x40a25632, 0x3df5a9a5, 0xbab37d80, 0x454286dc, 0x3f5d4e78, 0x03d7b75d, 0xb1fe4af7,
    0xa5ab26a3,
];

pub fn protocol_random() -> u32 {
    let mut b = [0u8; 4];
    rand::thread_rng().fill_bytes(&mut b);
    u32::from_le_bytes(b)
}

fn form_encode_go_style(q: &[(String, String)]) -> String {
    // Matches Go's url.Values.Encode(): sorted by key, space -> '+'
    let mut sorted = q.to_vec();
    sorted.sort_by(|a, b| a.0.cmp(&b.0));
    sorted
        .into_iter()
        .map(|(k, v)| format!("{}={}", url_query_escape(&k), url_query_escape(&v)))
        .collect::<Vec<_>>()
        .join("&")
}

fn url_query_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for &c in s.as_bytes() {
        match c {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(c as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{:02X}", c)),
        }
    }
    out
}

fn passport_iv(data: &[u8], mut iv: u32) -> u32 {
    for (i, &x) in data.iter().enumerate() {
        if (i & 1) == 0 {
            iv = (iv >> 4) ^ iv ^ (iv << 6) ^ (x as u32);
        } else {
            iv = !((iv >> 7) ^ iv ^ ((x as u32) | (iv << 12)));
        }
    }
    iv
}

fn passport_branch(query: &str, body: &[u8], ts: u32) -> (u32, [u8; 32], [u8; 16]) {
    let q = sm3(query.as_bytes());
    let m = if !body.is_empty() {
        md5_bytes(body)
    } else {
        [0u8; 16]
    };
    let iv = passport_iv(&le32(ts), passport_iv(&m, passport_iv(&q, 0x20230928)));
    (iv, q, m)
}

fn passport_tune_query(
    q: &mut Params,
    body: &[u8],
    stamp: i64,
) -> Result<(String, u32, [u8; 32], [u8; 16]), String> {
    let ts = (stamp / 1000) as u32;
    for i in 0..256i64 {
        set_param(q, "_rticket", &(stamp + i).to_string());
        let raw = form_encode_go_style(q);
        let (iv, hash, m) = passport_branch(&raw, body, ts);
        if (iv & 15) % 3 == 2 {
            return Ok((raw, iv, hash, m));
        }
    }
    Err("unable to select signature branch".to_string())
}

fn passport_f13(iv: u32, ts: u32, q: [u8; 32], m: [u8; 16]) -> Vec<u8> {
    let group = (((iv & 15) - 2) / 3) as usize;
    let seeds = [0x8980f29bu32, 0xeb549c7f, 0xb08726db, 0xd40cb5e6, 0xe8f559e4];
    let count = seeds[group].wrapping_add(ts);
    let shift = (count.wrapping_add(5) & 7) as u32;
    let mut msg = Vec::with_capacity(64);
    msg.extend_from_slice(&q);
    msg.extend_from_slice(&m);
    msg.extend_from_slice(&le32(ts));
    for x in unhex("8496779dd4150bf8") {
        msg.push((((x as u16) * 257) >> shift) as u8);
    }
    msg.extend_from_slice(&[0xa0, 1, 0, 0]);

    let mut start = [0x79e0f2fbu32, 0xc8b52570, 0xebc2f8cd, 0x7c104d93];
    let mut end = [0x19be4866u32, 0xe85986b4, 0xe19b326e, 0x71d1d7d4];
    for i in 0..4 {
        start[i] = start[i].rotate_right(count & 31);
        end[i] = end[i].rotate_right(count.wrapping_add(6) & 31);
    }
    let (mut a, mut b, mut c, mut d) = (start[0], start[1], start[2], start[3]);
    let shifts = [
        [7u32, 12, 17, 22],
        [5, 9, 14, 20],
        [4, 11, 16, 23],
        [6, 10, 15, 21],
    ];
    let orders = unhex(PASSPORT_ORDERS_HEX);
    for i in 0..64 {
        let f = match i / 16 {
            0 => (b & c) | ((!b) & d),
            1 => (b & d) | (c & (!d)),
            2 => b ^ c ^ d,
            _ => c ^ (b | (!d)),
        };
        let w_idx = orders[group * 64 + i] as usize;
        let word = u32::from_le_bytes(msg[w_idx * 4..w_idx * 4 + 4].try_into().unwrap());
        let x = b.wrapping_add(
            a.wrapping_add(f)
                .wrapping_add(word)
                .wrapping_add(PASSPORT_MD5_CONSTANTS[i].rotate_right(count.wrapping_add(1) & 31))
                .rotate_left(shifts[i / 16][i % 4]),
        );
        a = d;
        d = c;
        c = b;
        b = x;
    }
    let mut out = Vec::with_capacity(20);
    for (i, &x) in [a, b, c, d].iter().enumerate() {
        out.extend_from_slice(&le32((start[i].wrapping_add(x)) ^ end[i]));
    }
    let mut check = 0x20220420u32;
    for (i, &x) in out[..12].iter().enumerate() {
        if (i & 1) == 0 {
            check = ((check >> 3) ^ check) ^ (x as u32) ^ (check << 7);
        } else {
            check = ((check >> 5) ^ check) ^ !((x as u32) | (check << 11));
        }
    }
    out.extend_from_slice(&le32((check | 4) ^ 0x1000000));
    out
}

pub fn passport_helios(ts: u32, nonce: u32) -> String {
    let mut in_buf = le32(nonce).to_vec();
    in_buf.extend_from_slice(b"8662");
    let raw = hex::encode(md5_bytes(&in_buf)).into_bytes();
    let mut k = u64::from_le_bytes(raw[0..8].try_into().unwrap());
    let mut pending = vec![
        u64::from_le_bytes(raw[8..16].try_into().unwrap()),
        u64::from_le_bytes(raw[16..24].try_into().unwrap()),
        u64::from_le_bytes(raw[24..32].try_into().unwrap()),
    ];
    let mut rounds = vec![k];
    for i in 0..33u64 {
        let x = (pending[0].rotate_right(8).wrapping_add(k)) ^ i;
        pending.remove(0);
        pending.push(x);
        k = x ^ k.rotate_left(3);
        rounds.push(k);
    }
    let mut plain = format!("{ts}-1588093228-8662").into_bytes();
    let pad = 16 - (plain.len() % 16);
    plain.resize(plain.len() + pad, pad as u8);
    let mut out = le32(nonce).to_vec();
    for chunk in plain.chunks_exact(16) {
        let mut a = u64::from_le_bytes(chunk[0..8].try_into().unwrap());
        let mut b = u64::from_le_bytes(chunk[8..16].try_into().unwrap());
        for &r in &rounds {
            b = r ^ a.wrapping_add(b.rotate_right(8));
            a = b ^ a.rotate_left(3);
        }
        out.extend_from_slice(&a.to_le_bytes());
        out.extend_from_slice(&b.to_le_bytes());
    }
    b64_encode(&out)
}

pub fn passport_gorgon(query: &str, body: &[u8], ts: u32, nonce: u16) -> String {
    let q = md5_bytes(query.as_bytes());
    let m = if !body.is_empty() {
        md5_bytes(body)
    } else {
        [0u8; 16]
    };
    let mut data = [0u8; 20];
    data[0..4].copy_from_slice(&q[0..4]);
    data[4..8].copy_from_slice(&m[0..4]);
    data[12..16].copy_from_slice(&67503104u32.to_le_bytes());
    data[16..20].copy_from_slice(&ts.to_be_bytes());
    let key = [
        0x4Au8,
        0x40,
        0x16,
        (nonce >> 8) as u8,
        0x47,
        0x6c,
        1,
        nonce as u8,
    ];
    let mut s = [0u8; 256];
    for i in 0..256 {
        s[i] = i as u8;
    }
    let mut j = 0usize;
    for i in 0..256 {
        j = (j + (s[i] as usize) + (key[i % 8] as usize)) & 255;
        s[i] = s[j];
    }
    j = 0;
    for i in 0..data.len() {
        j = (j + (s[i + 1] as usize)) & 255;
        let y = s[j];
        s[i + 1] = y;
        data[i] ^= s[((y as usize) * 2) & 255];
    }
    let data_snap = data;
    for i in 0..data.len() {
        let rot = data_snap[i].rotate_left(4);
        let next = if i < 19 { data_snap[i + 1] } else { data_snap[0] };
        data[i] = !((rot ^ next).reverse_bits() ^ 20);
    }
    let mut out = vec![0x84, 4, nonce as u8, (nonce >> 8) as u8, 0x40, 1];
    out.extend_from_slice(&data);
    hex::encode(out)
}

struct Proto(Vec<u8>);

impl Proto {
    fn new() -> Self {
        Self(Vec::new())
    }
    fn varint(&mut self, mut v: u64) {
        while v >= 0x80 {
            self.0.push((v as u8) | 0x80);
            v >>= 7;
        }
        self.0.push(v as u8);
    }
    fn sint(&mut self, field: i32, n: i64) {
        self.varint((field as u64) << 3);
        self.varint(((n << 1) ^ (n >> 63)) as u64);
    }
    fn blob(&mut self, field: i32, b: &[u8]) {
        if b.is_empty() {
            return;
        }
        self.varint(((field as u64) << 3) | 2);
        self.varint(b.len() as u64);
        self.0.extend_from_slice(b);
    }
    fn text(&mut self, field: i32, s: &str) {
        self.blob(field, s.as_bytes());
    }
    fn float(&mut self, field: i32, n: f32) {
        self.varint(((field as u64) << 3) | 5);
        self.0.extend_from_slice(&n.to_bits().to_le_bytes());
    }
}

fn passport_proto_version(
    query: &str,
    ts: u32,
    nonce: u32,
    device_id: &str,
    q: [u8; 32],
    m: [u8; 16],
    f13: &[u8],
    version: &str,
) -> Vec<u8> {
    let mut device = Proto::new();
    device.sint(1, 1);
    device.sint(2, 2);
    device.text(3, "8662");
    device.text(4, device_id);
    device.text(5, "Ai6svO3PyrwDOUSmO6ZcResxu");
    device.text(6, "!noperm!");
    device.sint(7, -888888);
    device.sint(8, -888888);
    device.sint(9, 3);
    device.sint(10, -888888);
    device.text(11, "!notset!");
    device.text(12, "Asia/Shanghai,8");
    device.text(13, "zh_CN");
    device.sint(14, 4);
    for (i, x) in [
        255.24993896484375f32,
        35.58599090576172,
        3.467449188232422,
        3.467449188232422,
        255.1754913330078,
        42.17544174194336,
    ]
    .iter()
    .enumerate()
    {
        device.float(i as i32 + 16, *x);
    }
    device.text(22, "16");
    device.sint(23, 41);
    device.sint(24, 36);
    for i in 25..=28 {
        let mut n = 1728388016635i64;
        if i == 28 {
            n += 2;
        }
        device.sint(i, n);
    }
    device.sint(29, -1);
    for (i, x) in [
        "25053RT47C",
        "Redmi",
        "25053RT47C",
        "25053RT47C",
        "Redmi",
        "Redmi",
        "Redmi",
    ]
    .iter()
    .enumerate()
    {
        device.text(i as i32 + 30, x);
    }
    device.sint(38, 31);

    let mut report = Proto::new();
    report.sint(1, ts as i64);
    report.sint(2, -2);
    report.sint(4, 200);

    let mut env = Proto::new();
    env.sint(1, 110);
    env.sint(2, 146331399);
    env.sint(3, 146331396);
    env.sint(5, 7);
    env.text(6, "v04.06.04.03-bugfix");
    env.sint(7, 11001);
    env.blob(12, &device.0);
    env.blob(13, &report.0);
    env.text(14, version);

    let mut count = Proto::new();
    count.sint(1, 111);
    count.sint(2, 10);
    count.sint(3, 694367);
    count.sint(5, 586952199);

    let mut p = Proto::new();
    p.blob(1, &unhex("f7e85ffad7d7dc3bd62ac87057cf6118"));
    p.sint(2, 3);
    p.sint(3, nonce as i64);
    p.text(4, "8662");
    p.text(5, device_id);
    p.text(6, "1588093228");
    p.text(7, version);
    p.text(8, "v04.06.04-ml-android");
    p.sint(9, 67503104);
    p.blob(10, &unhex("4001000000000000"));
    p.sint(12, ts as i64);
    p.blob(13, f13);
    p.blob(14, &q[..6]);
    p.blob(15, &count.0);
    p.text(16, "AXYQOS6n2m60x1fVZHIrH3iol");
    p.sint(17, ts as i64);
    let mut h_in = Vec::with_capacity(query.len() + 20);
    h_in.extend_from_slice(query.as_bytes());
    h_in.extend_from_slice(&m);
    h_in.extend_from_slice(b"none");
    let hash = sm3(&h_in);
    p.blob(19, &hash);
    p.text(20, "none");
    p.sint(21, 312);
    p.blob(23, &env.0);
    p.text(24, r#"{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}"#);
    p.0
}

fn passport_xmxor(data: &[u8], key: &[u8]) -> Vec<u8> {
    let mut out = vec![0u8; data.len()];
    for (i, &x) in data.iter().enumerate() {
        let k = (i * 4) & 28;
        let a = key[k];
        let b = key[k + 1];
        let mut v = (!(x.rotate_left(4).wrapping_add(a))) ^ b;
        v = v.rotate_left(3).wrapping_add(b);
        out[data.len() - 1 - i] = !(v ^ a);
    }
    let len = out.len();
    let flag = out[len - 1] ^ out[len - 2];
    let old = out[0];
    out[0] = out[0].wrapping_add(!flag);
    let add1 = out[0] ^ out[len - 1] ^ 254;
    out[1] = out[1].wrapping_add(add1);
    out[2] = out[2].wrapping_add((flag.wrapping_sub(old)) ^ out[1].rotate_left(3) ^ 2);
    for i in 0..(len - 4) {
        let temp = !(out[i + 2].rotate_left(3) ^ out[i + 1] ^ ((i + 3) as u8));
        out[i + 3] = out[i + 3].wrapping_add(temp);
    }
    out[len - 1] ^= out[len - 2];
    let sum: u8 = out[1..].iter().fold(0u8, |acc, &x| acc.wrapping_add(x));
    out[0] = (out[0] ^ out[1]).wrapping_add(sum);
    out
}

fn passport_aes_v3(data: &[u8], ts: u32) -> Result<Vec<u8>, String> {
    if data.len() < 248 {
        return Err("signature payload is too short".to_string());
    }
    let i = (ts & 3) as usize;
    let sbox = unhex(PASSPORT_SBOX_HEX);
    let r#box = &sbox[i * 256..(i + 1) * 256];
    let con = [[1usize, 0, 2, 3], [2, 0, 3, 1], [0, 1, 3, 2], [1, 0, 2, 3]][i];
    let order = [
        [0usize, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3],
        [0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11],
        [0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15],
        [0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3],
    ][i];
    let init = [0xca025ddcu32, 0x823dc546, 0xc9420583, 0xc298225f][i];
    let key = unhex("f1593376766ea98d34f31b057a9d5be4");
    let mut mk = [0u8; 48];
    for j in 0..16 {
        mk[j] = key[j] ^ ((init >> ((j % 4) * 8)) as u8);
    }
    let mut r = 8u32;
    for j in 4..12 {
        let mut prev = [mk[j * 4 - 4], mk[j * 4 - 3], mk[j * 4 - 2], mk[j * 4 - 1]];
        if j % 4 == 0 {
            prev = [
                ((init >> (r & 24)) as u8) ^ r#box[prev[1] as usize],
                r#box[prev[2] as usize],
                r#box[prev[3] as usize],
                r#box[prev[0] as usize],
            ];
        }
        r += 2;
        for k in 0..4 {
            mk[j * 4 + k] = prev[k] ^ mk[j * 4 + k - 16];
        }
    }

    let block_fn = |src: &[u8]| -> [u8; 16] {
        let mut b = [0u8; 16];
        b.copy_from_slice(src);
        let add = |b: &mut [u8; 16], base: usize| {
            for j in 0..16 {
                b[j] ^= mk[base + (j / 4) * 4 + con[j % 4]];
            }
        };
        add(&mut b, 0);
        for round in 1..=2 {
            let prev = b;
            for j in 0..16 {
                let index = order[j];
                b[j] = r#box[prev[con[index / 4] * 4 + index % 4] as usize];
            }
            if round == 1 {
                let prev = b;
                for j in 0..16 {
                    b[j] = prev[(j / 4) * 4 + con[j % 4]];
                }
                let xt = |x: u8| -> u8 {
                    if (x & 128) != 0 {
                        (x << 1) ^ 0x1b
                    } else {
                        x << 1
                    }
                };
                for col in 0..4 {
                    let (a, c, d, e) = (b[col], b[4 + col], b[8 + col], b[12 + col]);
                    let t = a ^ c ^ d ^ e;
                    b[col] ^= t ^ xt(a ^ c);
                    b[4 + col] ^= t ^ xt(c ^ d);
                    b[8 + col] ^= t ^ xt(d ^ e);
                    b[12 + col] ^= t ^ xt(e ^ a);
                }
            }
            add(&mut b, round * 16);
        }
        for j in 0..16 {
            b[j] ^= mk[16 + j];
        }
        b
    };

    let mut plain = [0u8; 32];
    for j in 0..31 {
        let d = &data[j * 8..j * 8 + 8];
        plain[j] = ((d[0] >> 4) & 2)
            | (d[1] & 64)
            | ((d[2] >> 2) & 1)
            | ((d[3] << 3) & 128)
            | ((d[4] >> 1) & 4)
            | ((d[5] << 3) & 16)
            | ((d[6] << 5) & 32)
            | ((d[7] >> 4) & 8);
    }
    plain[31] = 1;
    let mut previous = [0u8; 16];
    previous.copy_from_slice(&unhex("1fe109a4125283f418de9e051a969e12"));
    let mut encrypted = Vec::with_capacity(32);
    for j in (0..32).step_by(16) {
        for k in 0..16 {
            plain[j + k] ^= previous[k];
        }
        previous = block_fn(&plain[j..j + 16]);
        encrypted.extend_from_slice(&previous);
    }
    let mut out = Vec::with_capacity(1 + data.len());
    out.push(encrypted[31]);
    out.extend_from_slice(data);
    for j in 0..31 {
        let k = encrypted[j];
        let d = &mut out[1 + j * 8..1 + j * 8 + 8];
        d[0] = (d[0] & !32) | ((k << 4) & 32);
        d[1] = (d[1] & !64) | (k & 64);
        d[2] = (d[2] & !4) | ((k << 2) & 4);
        d[3] = (d[3] & !16) | ((k >> 3) & 16);
        d[4] = (d[4] & !8) | ((k << 1) & 8);
        d[5] = (d[5] & !2) | ((k >> 3) & 2);
        d[6] = (d[6] & !1) | ((k >> 5) & 1);
        d[7] = (d[7] & 127) | ((k << 4) & 128);
    }
    Ok(out)
}

#[allow(clippy::too_many_arguments)]
fn passport_medusa_version(
    query: &str,
    ts: u32,
    nonce: u32,
    hash_rand: u32,
    xm_rand: u32,
    device_id: &str,
    iv: u32,
    q: [u8; 32],
    m: [u8; 16],
    version: &str,
) -> Result<String, String> {
    let f13 = passport_f13(iv, ts, q, m);
    let p = passport_proto_version(query, ts, nonce, device_id, q, m, &f13, version);
    let sign_key = unhex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5");
    let mut k_in = Vec::with_capacity(sign_key.len() * 2 + 4);
    k_in.extend_from_slice(&sign_key);
    k_in.extend_from_slice(&le32(hash_rand));
    k_in.extend_from_slice(&sign_key);
    let key = sm3(&k_in);

    let d1 = (hash_rand >> 16) & 255;
    let seed = le32(!((d1 << 11) | (hash_rand >> 24) ^ (d1 >> 5) ^ d1));
    let mut tr = unhex("4001000000000000");
    tr.extend_from_slice(&passport_xmxor(&p, &key));
    tr.reverse();
    for i in 0..tr.len() {
        tr[i] ^= seed[(!i) & 3];
    }
    let check = 0x18000001u32 | (((q[0] & 63) as u32) << 14) | (((f13[0] & 63) as u32) << 8);
    let mut body = Vec::with_capacity(11 + tr.len());
    body.push(0x35);
    body.extend_from_slice(&le32(xm_rand));
    body.extend_from_slice(&le32(check));
    body.extend_from_slice(&tr);
    body.extend_from_slice(&le32(hash_rand)[2..]);
    let enc = passport_aes_v3(&body, ts)?;
    let mut header = unhex("03000000f7e85ffad7d7dc3bd62ac87057cf6118");
    for j in (0..20).step_by(4) {
        let v = u32::from_le_bytes(header[j..j + 4].try_into().unwrap()) ^ ts;
        header[j..j + 4].copy_from_slice(&v.to_le_bytes());
    }
    header.extend_from_slice(&le32(hash_rand)[..2]);
    header.push(0);
    header.push(1);
    header.extend_from_slice(&enc);
    Ok(b64_encode(&header))
}

pub fn sign_passport_request_version(
    q: &mut Params,
    body: &[u8],
    stamp: i64,
    device_id: &str,
    version: &str,
) -> Result<(String, HashMap<String, String>), String> {
    let (raw, iv, hash, m) = passport_tune_query(q, body, stamp)?;
    let n = [
        protocol_random(),
        protocol_random(),
        protocol_random(),
        protocol_random(),
    ];
    let ts = (stamp / 1000) as u32;
    let medusa =
        passport_medusa_version(&raw, ts, n[0], n[1], n[2], device_id, iv, hash, m, version)?;
    let mut h = HashMap::new();
    h.insert("X-Khronos".to_string(), ts.to_string());
    h.insert(
        "X-SS-Req-Ticket".to_string(),
        get_param(q, "_rticket").to_string(),
    );
    h.insert("X-Helios".to_string(), passport_helios(ts, n[3]));
    h.insert("X-Medusa".to_string(), medusa);
    h.insert(
        "X-Gorgon".to_string(),
        passport_gorgon(&raw, body, ts, n[0] as u16),
    );
    if !body.is_empty() {
        h.insert("X-SS-STUB".to_string(), hex::encode_upper(m));
    }
    Ok((raw, h))
}
