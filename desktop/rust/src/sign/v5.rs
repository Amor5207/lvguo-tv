use crate::sign::{
    b64_encode, be32, branch, enc_component, encode_params, get_param, gorgon, helios, iv, le32,
    md5_bytes, rand_f64, set_param, sm3, sum_md5, u32rand, unhex, xor_bytes, AesV3,
    ProtoBuf, Signed, HASH_TABLE, INITIAL_SRC,
};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::RngCore;
use std::time::{SystemTime, UNIX_EPOCH};

pub const PLAYBACK_UA: &str = "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)";
pub const PLAYBACK_VERSION: &str = "7.3.9.32";
#[allow(dead_code)]
const PLAYBACK_LICENSE_ID: &str = "1611921764";

const V5_SBOX_HEX: &str = "53290fe9e51f316f90f74a7e034d367784ab49237cd0135cdc632c322fd3be0e2624a417ecd9151a7ff64f60730a87f9445ec6ada2f865d22b696c4188f24c0df37b75510b939f6bc2fe6883e68bc8c722181ba0bdb08c9871b50c9a16b46eea8eed20ba4b8a14e0fcf1468dbbef05a93e5a9d7dcf06898635c1fd99f5de287a80c594daaab19b6279fbd75070cb52eb2555c456e39e7839853874e2f0013ac0b64742d8ca10e4b79c955de8b843ce4ed464a6a72ed508091c92e7b3eedd1e1dd12afac321618fa3d65f9167c982f43d66a53c6a1997585b346dbf9672ff37cdbc073b2dae11453304df00815476b2a13fccdb30b957a8acafe127400248591238922563e36406d37a24100b7902a028044e218453c2a399199c9d13dc40c55ba67e502273850ebdc1a2e2188b8f2d9e3cb909b8c46a2a9f4a6dd93007eafa65b7b3a881aa908cc74f0ad76bbbb58617c37012ca05a469fd44e62eecf1e4272ce860c0ae5eadf99b20d8f0234174006f3f110d7657d01645d4eff8f71471cbfe1cb2f52648727bf3c9de55153e031b4d61757db63dbf7782eeafe9018a8891362fb14759ac32bab03b349aeb5a377c5156be4c8dfbe13a684bda39cf836697c894545fe0f2491acea9fc6252f4ff1e0fe72bd2df6e67a7f635e5bcc6d1a5dd951d89960887a18eb40c785cdb1f80ed42299333ab98cdd67f316cd54346585dccd759fb722789b08807cc0fda293f7e5e1819bb0353517020b31d3d00374952bea2c421e862040a9ec892a05b266b7a0d0cc316f483b81af39715657ffe32ad8de0c13077646035236ee5363e5f900e9a7cf850a81cd9960b6df73317226f99eaa6fff15cec34acb98246639b918ab2851466a7cdfc4ad1b52c2ae95a58d3734e47ddd5b7560193f644ef9c408b7dee39b4d6aabf4de761e3252d4c7ba3843187745d55e6a5db05d42b45caed43c0c5d0b64bfa9d94a4a9d8f2f0e1a108dcfd288fd2bc1fc7bd675412138169cb09249875ba3b68cfb11180afae104fc9789571c6dfce02413886796ae21e3c488cc21b9f423a066c2eabf98e2fdeeb5776e4f5d09a515ba58cbbab3e37b09fd4f6d9244df80512e84cd7956be301cf64f1429d58577cc241f3a2c655144f81aaf95a4e108da894b38970561ae9002619fbbac91fa922ed202bfc62cb08e0de2a7f3631435f8b47c8a3c563b4db4071ce74c46da6fa696a9e282d1e825d663b93bfd56c48dce1884bb10fccea063929f746443fd830a4232e53da21a1f2d1bdfebc3a02c10a1b8ad31d04fd616590d272ac737e98eb33af60528716bee78ec0ee8699031177277d9cff32542c67caf5f44591681335ae0e178f3409a7803db83892dde5b9b7f0c750e24aef077badd678a09b850b84ec1597e4963cb5e675df1c0d6fb618496ecd76c35c798325590c2fb27a5e";

#[derive(Clone)]
pub struct PlaybackSigner {
    pub device_id: String,
    pub version: String,
    pub process_nonce: String,
    pub process_started: i64,
}

impl PlaybackSigner {
    pub fn new(device_id: &str, version: &str) -> Result<Self, String> {
        if device_id.is_empty() {
            return Err("播放签名缺少设备 ID".to_string());
        }
        let ver = if version.is_empty() {
            PLAYBACK_VERSION
        } else {
            version
        };
        let mut nonce = [0u8; 18];
        rand::thread_rng().fill_bytes(&mut nonce);
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as i64;
        Ok(Self {
            device_id: device_id.to_string(),
            version: ver.to_string(),
            process_nonce: URL_SAFE_NO_PAD.encode(nonce),
            process_started: now_ms,
        })
    }

    pub fn sign_mobile_v5(
        &self,
        base: &str,
        values: &[(String, String)],
        body: Option<&[u8]>,
        now_ms: i64,
        random: Option<&mut dyn FnMut() -> f64>,
    ) -> Result<Signed, String> {
        if self.device_id.is_empty() {
            return Err("播放签名器未初始化".to_string());
        }
        let now_ms = if now_ms > 0 {
            now_ms
        } else {
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64
        };
        let mut default_rng = rand_f64;
        let rng: &mut dyn FnMut() -> f64 = random.unwrap_or(&mut default_rng);
        let ts = (now_ms / 1000) as u32;
        let body_md5 = match body {
            Some(b) => md5_bytes(b),
            None => [0u8; 16],
        };
        if get_param(values, "device_id") != self.device_id {
            return Err("播放设备身份不匹配".to_string());
        }
        for k in ["version_name", "device_type", "os_version", "channel"] {
            if get_param(values, k).is_empty() {
                return Err("缺少播放设备参数".to_string());
            }
        }
        for offset in 0..1024i64 {
            let ticket = now_ms + offset;
            let mut query = values.to_vec();
            set_param(&mut query, "_rticket", &ticket.to_string());
            let encoded = encode_params(&query);
            if (iv(&sm3(encoded.as_bytes()), &body_md5, ts) & 15) != 12 {
                continue;
            }
            let u = format!("{base}?{encoded}");
            let gorgon_random = (rng() * 65536.0) as u32;
            let h_rnd = u32rand(rng);
            let h = helios_v5(ts, h_rnd);
            let med = medusa_v5(&u, body, ts, now_ms, self, &query, rng)?;
            let stub = match body {
                Some(b) => hex::encode_upper(md5_bytes(b)),
                None => hex::encode_upper(md5_bytes(b"")),
            };
            let headers = vec![
                ("x-khronos".into(), ts.to_string()),
                ("x-gorgon".into(), gorgon(&encoded, body, ts, gorgon_random)),
                ("x-argus".into(), b64_encode(&le32(ts))),
                ("x-ladon".into(), b64_encode(&be32(ts))),
                ("x-helios".into(), h),
                ("x-medusa".into(), med),
                ("x-xs-from-web".into(), "0".into()),
                ("x-ss-stub".into(), stub),
                ("Content-Type".into(), "application/json; charset=utf-8".into()),
            ];
            return Ok(Signed { url: u, headers });
        }
        Err("无法生成 v5 播放签名".to_string())
    }

    pub fn sign_playback(
        &self,
        base: &str,
        values: &[(String, String)],
        body: Option<&[u8]>,
        now_ms: i64,
        random: Option<&mut dyn FnMut() -> f64>,
    ) -> Result<Signed, String> {
        if self.device_id.is_empty() {
            return Err("播放签名器未初始化".to_string());
        }
        let now_ms = if now_ms > 0 {
            now_ms
        } else {
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64
        };
        let mut default_rng = rand_f64;
        let rng: &mut dyn FnMut() -> f64 = random.unwrap_or(&mut default_rng);
        let ts = (now_ms / 1000) as u32;
        for offset in 0..128i64 {
            let ticket = now_ms + offset;
            let mut query = values.to_vec();
            set_param(&mut query, "_rticket", &ticket.to_string());
            let encoded = encode_params(&query);
            if branch(&encoded, body, ts) != 0 {
                continue;
            }
            let u = format!("{base}?{encoded}");
            let gorgon_random = (rng() * 65536.0) as u32;
            let helios_random = u32rand(rng);
            let med = medusa_playback(
                &u,
                body,
                ts,
                &self.device_id,
                &self.version,
                &self.process_nonce,
                self.process_started,
                now_ms,
                rng,
            )?;
            let mut headers = vec![
                ("User-Agent".into(), PLAYBACK_UA.into()),
                ("Accept".into(), "application/json; charset=utf-8,application/x-protobuf".into()),
                ("x-ss-req-ticket".into(), ticket.to_string()),
                ("sdk-version".into(), "2".into()),
                ("x-xs-from-web".into(), "0".into()),
                ("x-khronos".into(), ts.to_string()),
                ("x-ladon".into(), b64_encode(&be32(ts))),
                ("x-argus".into(), b64_encode(&le32(ts))),
                ("x-gorgon".into(), gorgon(&encoded, body, ts, gorgon_random)),
                ("x-helios".into(), helios(ts, helios_random)),
                ("x-medusa".into(), med),
            ];
            if let Some(b) = body {
                headers.push(("Content-Type".into(), "application/json; charset=utf-8".into()));
                headers.push(("x-ss-stub".into(), hex::encode_upper(md5_bytes(b))));
            }
            return Ok(Signed { url: u, headers });
        }
        Err("无法生成播放签名".to_string())
    }
}

fn hash_f13_v5(query: &[u8], body: &[u8], ts: u32) -> Result<Vec<u8>, String> {
    let v = iv(query, body, ts);
    if (v & 15) != 12 {
        return Err("unsupported hash branch".to_string());
    }
    let base0 = 0x9d4a97c2u32;
    let count = base0.wrapping_add(ts);
    let mut tt = [0u32; 64];
    for i in 0..64 {
        tt[i] = super::rotr(HASH_TABLE[i], (count.wrapping_add(1) & 255) as i32);
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
            .wrapping_add(super::rotl(x, 14) ^ super::rotl(x, 25) ^ (x >> 3))
            .wrapping_add(super::rotl(y, 13) ^ super::rotl(y, 15) ^ (y >> 10));
    }
    let mut initial = [0u32; 8];
    for i in 0..8 {
        initial[i] = super::rotr(INITIAL_SRC[i], (count & 31) as i32);
    }
    let mut d = initial;
    let c = [96usize, 1, 3, 4, 0, 6, 5, 2, 0, 7, 7];
    for i in 0..c[0] {
        let base = base0.wrapping_add(i as u32);
        let n1 = ((d[c[3]] ^ d[c[4]]) & d[c[1]]) ^ d[c[3]];
        let n2 = super::rotl(d[c[1]], 26) ^ super::rotl(d[c[1]], 21) ^ super::rotl(d[c[1]], 7);
        let n4 = di[(base & 127) as usize]
            .wrapping_add(n1)
            .wrapping_add(n2)
            .wrapping_add(tt[(base & 63) as usize])
            .wrapping_add(d[c[5]]);
        let n5 = super::rotl(d[c[2]], 30) ^ super::rotl(d[c[2]], 19) ^ super::rotl(d[c[2]], 10);
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
    out.extend_from_slice(&le32(sum_md5(&folded) | 0x20));
    Ok(out)
}

fn new_aes_v5(key: &[u8], ts: u32) -> AesV3 {
    let word = (ts & 3) as usize;
    let sb = unhex(V5_SBOX_HEX);
    let mut r#box = [0usize; 256];
    for i in 0..256 {
        r#box[i] = sb[word * 256 + i] as usize;
    }
    let con = [[2, 0, 3, 1], [1, 3, 2, 0], [3, 2, 1, 0], [2, 3, 1, 0]][word];
    let order = [
        [0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11],
        [0, 9, 14, 11, 4, 13, 2, 3, 8, 1, 6, 15, 12, 5, 10, 7],
        [0, 9, 14, 3, 4, 13, 2, 15, 8, 1, 6, 11, 12, 5, 10, 7],
        [0, 9, 14, 15, 4, 13, 2, 3, 8, 1, 6, 11, 12, 5, 10, 7],
    ][word];
    let initial = [0xbbb5de72u32, 0xd4638d5b, 0x92a5f73b, 0x89f8a1c9][word];
    AesV3::from_parts(r#box, con, order, initial, key)
}

fn helios_v5(ts: u32, rnd: u32) -> String {
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
    let text = format!("{ts}-646637794-8662").into_bytes();
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

fn encrypt_v5(a: &AesV3, source: &[u8], ivb: &[u8]) -> Result<Vec<u8>, String> {
    if source.len() < 248 {
        return Err("v5 carrier too short".to_string());
    }
    let wire = [6u32, 5, 3, 4, 2, 7, 1, 0];
    let compact = [1u32, 7, 3, 6, 2, 0, 4, 5];
    let mut plain = [0u8; 32];
    for i in 0..31 {
        for j in 0..8 {
            plain[i] |= ((source[i * 8 + j] >> wire[j]) & 1) << compact[j];
        }
    }
    plain[31] = 1;
    let first = a.block(&xor_bytes(&plain[..16], ivb));
    let second = a.block(&xor_bytes(&plain[16..], &first));
    let mut key = [0u8; 32];
    key[..16].copy_from_slice(&first);
    key[16..].copy_from_slice(&second);
    let mut out = Vec::with_capacity(1 + source.len());
    out.push(key[31]);
    out.extend_from_slice(source);
    for i in 0..31 {
        for j in 0..8 {
            let at = 1 + i * 8 + j;
            let mask = 1u8 << wire[j];
            out[at] = (out[at] & !mask) | (((key[i] >> compact[j]) & 1) << wire[j]);
        }
    }
    Ok(out)
}

fn medusa_v5_token(nonce: &str) -> String {
    if nonce.len() == 25 && nonce.starts_with('A') {
        return nonce.to_string();
    }
    if nonce.len() >= 24 {
        return format!("A{}", &nonce[..24]);
    }
    format!("A{}{}", nonce, "A".repeat(24 - nonce.len()))
}

fn medusa_v5(
    raw_url: &str,
    body: Option<&[u8]>,
    ts: u32,
    now_ms: i64,
    s: &PlaybackSigner,
    who: &[(String, String)],
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
    let qh = sm3(query);
    let f13 = hash_f13_v5(&qh, &body_md5, ts)?;
    let flags = unhex("4480000000000000");
    let version = get_param(who, "version_name");
    let did = get_param(who, "device_id");
    let dev_type = get_param(who, "device_type");
    let brand_raw = get_param(who, "device_brand");
    let brand = if brand_raw.is_empty() {
        "HONOR"
    } else {
        brand_raw
    };
    let sec_token = medusa_v5_token(&s.process_nonce);
    let started = if s.process_started > 0 {
        s.process_started
    } else {
        now_ms
    };
    let now_ms = if now_ms > 0 {
        now_ms
    } else {
        (ts as i64) * 1000
    };

    let mut device = ProtoBuf::new();
    device
        .sint(1, 1)
        .sint(2, 2)
        .str(3, "8662")
        .str(4, did)
        .str(5, &sec_token)
        .str(6, "!noperm!")
        .sint(7, -888888)
        .sint(8, -888888)
        .sint(9, 3)
        .sint(10, -888888)
        .str(11, "!notset!")
        .str(12, "Asia/Shanghai,8")
        .str(13, "zh_CN")
        .sint(14, 4)
        .flt(16, 0.0)
        .flt(17, 0.0)
        .flt(18, 0.0)
        .flt(19, 0.0)
        .flt(20, 0.0)
        .flt(21, 0.0)
        .str(22, get_param(who, "os_version"))
        .sint(23, 41)
        .sint(24, 36)
        .sint(25, started)
        .sint(26, started)
        .sint(27, started)
        .sint(28, now_ms)
        .sint(29, -1)
        .str(30, dev_type)
        .str(31, brand)
        .str(32, dev_type)
        .str(33, dev_type)
        .str(34, brand)
        .str(35, brand)
        .str(36, brand)
        .sint(38, 31);
    let device_b = device.into_bytes();

    let env_launch = (random() * 21.0) as i64 + 100;
    let env_pid = (random() * 2000.0) as i64 + 10001;
    let mut env_sub13 = ProtoBuf::new();
    env_sub13.sint(1, ts as i64).sint(2, -2).sint(4, 200);
    let env_sub13_b = env_sub13.into_bytes();

    let mut env = ProtoBuf::new();
    env.sint(1, env_launch)
        .sint(2, 247235599)
        .sint(3, 247235596)
        .sint(5, 7)
        .str(6, "v04.10.03.04-bugfix")
        .sint(7, env_pid)
        .str(8, get_param(who, "channel"))
        .bytes(12, &device_b)
        .bytes(13, &env_sub13_b)
        .str(14, version);
    let env_b = env.into_bytes();

    let mut nested = ProtoBuf::new();
    nested.sint(1, 305).sint(2, 4).sint(3, 694367).sint(5, 1621072674);
    let nested_b = nested.into_bytes();

    let magic = unhex("2d4b4fca49750d433fb5ae2c226dcc56");
    let key = unhex("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d");
    let f24 = r#"{"cmr":16777216,"cmr2":16777216,"un_h":11,"vpn":0,"kd":0,"dyn":"","do":1,"tk":true}"#;
    let mut msg = ProtoBuf::new();
    msg.bytes(1, &magic)
        .sint(2, 5)
        .sint(3, u32rand(random) as i64)
        .str(4, "8662")
        .str(5, did)
        .str(6, "646637794")
        .str(7, version)
        .str(8, "v04.10.03-ml-android")
        .sint(9, 0x040a0300)
        .bytes(10, &flags)
        .sint(12, ts as i64)
        .bytes(13, &f13)
        .bytes(14, &qh[..6])
        .bytes(15, &nested_b)
        .str(16, &sec_token)
        .sint(17, ts as i64)
        .str(20, "none")
        .sint(21, 312)
        .bytes(23, &env_b)
        .str(24, f24);
    let msg_b = msg.into_bytes();

    let nonce = u32rand(random);
    let mut h_in = Vec::with_capacity(key.len() * 2 + 4);
    h_in.extend_from_slice(&key);
    h_in.extend_from_slice(&le32(nonce));
    h_in.extend_from_slice(&key);
    let hash = sm3(&h_in);
    let high = (nonce >> 16) & 255;
    let seed = le32(!((((high << 11) | (nonce >> 24)) ^ (high >> 5)) ^ high));

    let mut transformed = Vec::with_capacity(flags.len() + msg_b.len());
    transformed.extend_from_slice(&flags);
    transformed.extend_from_slice(&super::xmxor(&msg_b, &hash));
    transformed.reverse();
    for i in 0..transformed.len() {
        transformed[i] ^= seed[(!i) & 3];
    }

    let check = 0x18000001u32 | (((qh[0] & 63) as u32) << 14) | (((f13[0] & 63) as u32) << 8);
    let mut packed = Vec::with_capacity(11 + transformed.len());
    packed.push(0xca);
    packed.extend_from_slice(&le32(u32rand(random)));
    packed.extend_from_slice(&le32(check));
    packed.extend_from_slice(&transformed);
    packed.push((nonce >> 16) as u8);
    packed.push((nonce >> 24) as u8);

    let k_md5 = md5_bytes(&key[..16]);
    let iv_md5 = md5_bytes(&key[16..]);
    let encrypted = encrypt_v5(&new_aes_v5(&k_md5, ts), &packed, &iv_md5)?;

    let mut base = Vec::with_capacity(4 + magic.len());
    base.extend_from_slice(&le32(5));
    base.extend_from_slice(&magic);
    let stamp = le32(ts);
    let prefix: Vec<u8> = base
        .iter()
        .enumerate()
        .map(|(i, &b)| b ^ stamp[i & 3])
        .collect();

    let mut final_buf = Vec::with_capacity(prefix.len() + 4 + encrypted.len());
    final_buf.extend_from_slice(&prefix);
    final_buf.push((nonce & 255) as u8);
    final_buf.push(((nonce >> 8) & 255) as u8);
    final_buf.push(0);
    final_buf.push(1);
    final_buf.extend_from_slice(&encrypted);
    Ok(b64_encode(&final_buf))
}

fn playback_device_proto(device_id: &str, nonce: &str, process_started: i64) -> Vec<u8> {
    let mut p = ProtoBuf::new();
    p.sint(1, 1)
        .sint(2, 2)
        .str(3, "8662")
        .str(4, device_id)
        .str(5, nonce)
        .str(6, "!noperm!")
        .sint(7, -888888)
        .sint(8, -888888)
        .sint(9, 3)
        .sint(10, -888888)
        .str(11, "!notset!")
        .str(12, "Asia/Shanghai,8")
        .str(13, "zh_CN")
        .sint(14, 4)
        .flt(16, 0.0)
        .flt(17, 0.0)
        .flt(18, 0.0)
        .flt(19, 0.0)
        .flt(20, 0.0)
        .flt(21, 0.0)
        .str(22, "16")
        .sint(23, 41)
        .sint(24, 36)
        .sint(25, process_started)
        .sint(26, process_started)
        .sint(27, process_started)
        .sint(28, process_started)
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

#[allow(clippy::too_many_arguments)]
fn medusa_playback(
    raw_url: &str,
    body: Option<&[u8]>,
    ts: u32,
    device_id: &str,
    version: &str,
    nonce: &str,
    process_started: i64,
    now_ms: i64,
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
    let query_body_ts = super::hash_f13(&query_sm3, &body_md5, ts)?;
    let mut nested = ProtoBuf::new();
    nested.sint(1, 111).sint(2, 10).sint(3, 694367).sint(5, 586952199);
    let nested_b = nested.into_bytes();

    let message_rand = u32rand(random);
    let env_launch = (random() * 21.0) as i64 + 100;
    let env_pid = (random() * 2000.0) as i64 + 10001;
    let mut sub13 = ProtoBuf::new();
    sub13.sint(1, now_ms / 1000).sint(2, -2).sint(4, 200);
    let sub13_b = sub13.into_bytes();

    let mut env = ProtoBuf::new();
    env.sint(1, env_launch)
        .sint(2, 146331399)
        .sint(3, 146331396)
        .sint(5, 7)
        .str(6, "v04.06.04.03-bugfix")
        .sint(7, env_pid)
        .bytes(12, &playback_device_proto(device_id, nonce, process_started))
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
        .str(16, nonce)
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
    transformed.extend_from_slice(&super::xmxor(&message, &h));
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

#[allow(dead_code)]
fn _keep_enc_component() {
    let _ = enc_component("");
}
