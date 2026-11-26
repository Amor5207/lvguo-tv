use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD};
use base64::Engine;

const APP_V2: &[u8] = b"app_v2";
const WEB_V2: &[u8] = b"web_v2";

fn strncmp0(a: &[u8], b: &[u8], n: usize) -> bool {
    for k in 0..n {
        let ca = a.get(k).copied().unwrap_or(0);
        let cb = b.get(k).copied().unwrap_or(0);
        if ca != cb {
            return false;
        }
        if ca == 0 {
            return true;
        }
    }
    true
}

fn s8(v: i32) -> i32 {
    let v = v & 0xFF;
    if v >= 128 {
        v - 256
    } else {
        v
    }
}

fn unwrap_v1(spade: &[u8], flag: i32) -> Option<String> {
    let l = spade.len() as i32;
    if l < 3 {
        return None;
    }
    let b_var5 = (spade[0] as i32) ^ (spade[1] as i32) ^ (spade[2] as i32);
    let i_var9 = b_var5 - 0x30;
    if i_var9 < 1 {
        return None;
    }
    let u_var1 = (l - b_var5) + 0x2F;
    if u_var1 < 1 || 1 + u_var1 > l {
        return None;
    }
    let u_var1_u = u_var1 as usize;
    let i_var9_u = i_var9 as usize;
    let mut dest = spade[1..1 + u_var1_u].to_vec();
    if l - i_var9 - 2 < 0 {
        return None;
    }
    let b16 = spade[(l - i_var9 - 2) as usize];
    let b14 = spade[(l - i_var9 - 1) as usize];
    let start = (l - i_var9) as usize;
    let mut s1 = vec![0u8; i_var9_u];
    for i in 0..i_var9_u {
        s1[i] = b14 ^ b16 ^ spade[i + start];
    }
    if strncmp0(&s1, APP_V2, i_var9_u) || strncmp0(&s1, WEB_V2, i_var9_u) {
        return None;
    }
    let mut bb14: i32 = 0x55;
    let mut bb16: i32 = 0xFA;
    for i in 0..u_var1_u {
        let b6 = dest[i] as i32;
        let u18 = (i as u32).count_ones() as i32;
        let mut b3 = b6;
        let mut b7 = bb14;
        if (i & 1) != 0 {
            b3 = bb16;
            b7 = b6;
            bb16 = bb14;
        }
        let c_var4 = if flag != 0 {
            u18 + 0x15
        } else {
            s8(-0x15 - u18)
        };
        dest[i] = ((c_var4 + (bb16 ^ b6)) & 0xFF) as u8;
        bb14 = b7;
        bb16 = b3;
    }
    let b0 = dest[0] as i32;
    let u11 = match b0 {
        0x30..=0x39 => b0 - 0x30,
        0x61..=0x7A => b0 - 0x57,
        _ => return None,
    };
    let iv9 = u_var1 - (u11 & 0xFF);
    if iv9 < 2 {
        return None;
    }
    String::from_utf8(dest[1..(iv9 as usize)].to_vec()).ok()
}

pub fn spade_to_key(spade_b64: &str) -> Option<[u8; 16]> {
    let trimmed = spade_b64.trim();
    let raw = STANDARD
        .decode(trimmed)
        .or_else(|_| STANDARD_NO_PAD.decode(trimmed.trim_end_matches('=')))
        .ok()?;
    let h = unwrap_v1(&raw, 0)?;
    if h.len() != 32 {
        return None;
    }
    let bytes = hex::decode(&h).ok()?;
    if bytes.len() != 16 {
        return None;
    }
    let mut out = [0u8; 16];
    out.copy_from_slice(&bytes);
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wrap_v1_test(hex_key: &str) -> String {
        // u11 = 2 -> iv9 = 1 + 32 = 33, u_var1 = 35; trailing 2 bytes hold padding while key occupies [1..33]
        let mut inner = vec![b'2'];
        inner.extend_from_slice(hex_key.as_bytes());
        inner.extend_from_slice(b"00");
        let u_var1 = inner.len() as i32; // 35

        let mut bb14: i32 = 0x55;
        let mut bb16: i32 = 0xFA;
        let mut dest = vec![0u8; inner.len()];
        for i in 0..inner.len() {
            let u18 = (i as u32).count_ones() as i32;
            let c_var4 = s8(-0x15 - u18);
            let xor_key = if (i & 1) != 0 { bb14 } else { bb16 };
            let b6 = ((((inner[i] as i32) - c_var4) & 0xFF) ^ xor_key) & 0xFF;
            dest[i] = b6 as u8;
            if (i & 1) != 0 {
                bb14 = b6;
            } else {
                bb16 = b6;
            }
        }

        let i_var9 = 4usize;
        let b_var5 = i_var9 as i32 + 0x30;
        let total = (u_var1 + b_var5 - 0x2F) as usize;
        let mut spade = vec![0u8; total];
        spade[1..1 + dest.len()].copy_from_slice(&dest);
        spade[0] = (b_var5 as u8) ^ spade[1] ^ spade[2];
        let b16 = spade[total - i_var9 - 2];
        let b14 = spade[total - i_var9 - 1];
        let tail_tag = b"test";
        for i in 0..i_var9 {
            spade[total - i_var9 + i] = b14 ^ b16 ^ tail_tag[i];
        }
        STANDARD.encode(spade)
    }

    #[test]
    fn test_spade_to_key_roundtrip_and_invalid() {
        let hex_key = "00112233445566778899aabbccddeeff";
        let spade = wrap_v1_test(hex_key);
        let parsed = spade_to_key(&spade).expect("valid spade should decode");
        assert_eq!(hex::encode(parsed), hex_key);
        assert!(spade_to_key("not-valid").is_none());
        assert!(spade_to_key("").is_none());
    }
}
