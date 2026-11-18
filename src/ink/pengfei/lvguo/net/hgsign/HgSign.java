package ink.pengfei.lvguo.net.hgsign;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 红果播放签名（纯 Java 移植自 aston314/hongguo-tv 的 Go 实现，对拍测试向量验证）。 */
public final class HgSign {
    private HgSign() {}

    // ---------- 基础工具 ----------
    static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }
    static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    static String b64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (; i + 3 <= d.length; i += 3) {
            int n = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF);
            sb.append(B64[(n >>> 18) & 63]).append(B64[(n >>> 12) & 63]).append(B64[(n >>> 6) & 63]).append(B64[n & 63]);
        }
        int rem = d.length - i;
        if (rem == 1) { int n = (d[i] & 0xFF) << 16; sb.append(B64[(n >>> 18) & 63]).append(B64[(n >>> 12) & 63]).append("=="); }
        else if (rem == 2) { int n = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8); sb.append(B64[(n >>> 18) & 63]).append(B64[(n >>> 12) & 63]).append(B64[(n >>> 6) & 63]).append("="); }
        return sb.toString();
    }
    static byte[] le32(int n) { return new byte[]{(byte) n, (byte) (n >>> 8), (byte) (n >>> 16), (byte) (n >>> 24)}; }
    static int leU32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }
    static long leU64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (long) (b[off + i] & 0xFF) << (8 * i);
        return v;
    }
    static void putLE32(byte[] b, int off, int n) { b[off] = (byte) n; b[off + 1] = (byte) (n >>> 8); b[off + 2] = (byte) (n >>> 16); b[off + 3] = (byte) (n >>> 24); }
    static void putLE64(byte[] b, int off, long n) { for (int i = 0; i < 8; i++) b[off + i] = (byte) (n >>> (8 * i)); }
    static int rotl8(int x, int k) { x &= 0xFF; k &= 7; return ((x << k) | (x >>> (8 - k))) & 0xFF; }
    static int reverse8(int x) { x &= 0xFF; int r = 0; for (int i = 0; i < 8; i++) { r = (r << 1) | (x & 1); x >>= 1; } return r & 0xFF; }

    // ---------- SM3 ----------
    static byte[] sm3(byte[] data) {
        long bitLen = (long) data.length * 8;
        int padLen = data.length + 1;
        while (padLen % 64 != 56) padLen++;
        byte[] b = new byte[padLen + 8];
        System.arraycopy(data, 0, b, 0, data.length);
        b[data.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) b[padLen + i] = (byte) (bitLen >>> (8 * (7 - i)));
        int[] v = {0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600, 0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e};
        int[] w = new int[68];
        for (int blk = 0; blk < b.length; blk += 64) {
            for (int i = 0; i < 16; i++)
                w[i] = (b[blk + i * 4] & 0xFF) << 24 | (b[blk + i * 4 + 1] & 0xFF) << 16 | (b[blk + i * 4 + 2] & 0xFF) << 8 | (b[blk + i * 4 + 3] & 0xFF);
            for (int i = 16; i < 68; i++)
                w[i] = p1(w[i - 16] ^ w[i - 9] ^ Integer.rotateLeft(w[i - 3], 15)) ^ Integer.rotateLeft(w[i - 13], 7) ^ w[i - 6];
            int a = v[0], c = v[1], d = v[2], e = v[3], f = v[4], g = v[5], h = v[6], k = v[7];
            for (int j = 0; j < 64; j++) {
                int t = 0x79cc4519, ff = a ^ c ^ d, gg = f ^ g ^ h;
                if (j >= 16) { t = 0x7a879d8a; ff = (a & c) | (a & d) | (c & d); gg = (f & g) | (~f & h); }
                int s1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + f + Integer.rotateLeft(t, j), 7);
                int s2 = s1 ^ Integer.rotateLeft(a, 12);
                int u1 = ff + e + s2 + (w[j] ^ w[j + 4]);
                int u2 = gg + k + s1 + w[j];
                e = d; d = Integer.rotateLeft(c, 9); c = a; a = u1;
                k = h; h = Integer.rotateLeft(g, 19); g = f; f = p0(u2);
            }
            int[] x = {a, c, d, e, f, g, h, k};
            for (int i = 0; i < 8; i++) v[i] ^= x[i];
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) { out[i * 4] = (byte) (v[i] >>> 24); out[i * 4 + 1] = (byte) (v[i] >>> 16); out[i * 4 + 2] = (byte) (v[i] >>> 8); out[i * 4 + 3] = (byte) v[i]; }
        return out;
    }
    static int p0(int x) { return x ^ Integer.rotateLeft(x, 9) ^ Integer.rotateLeft(x, 17); }
    static int p1(int x) { return x ^ Integer.rotateLeft(x, 15) ^ Integer.rotateLeft(x, 23); }

    static byte[] md5(byte[] data) {
        try { return MessageDigest.getInstance("MD5").digest(data); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------- IV / branch ----------
    static int ivMix(byte[] data, int iv) {
        for (int i = 0; i < data.length; i++) {
            int x = data[i] & 0xFF;
            if ((i & 1) == 0) iv = (iv >>> 4) ^ iv ^ (iv << 6) ^ x;
            else iv = ~((iv >>> 7) ^ iv ^ (x | (iv << 12)));
        }
        return iv;
    }
    static final class Branch { int iv; byte[] q; byte[] m; }
    static Branch branch(String query, byte[] body, int ts) {
        Branch r = new Branch();
        r.q = sm3(query.getBytes(StandardCharsets.UTF_8));
        r.m = (body != null && body.length > 0) ? md5(body) : new byte[16];
        r.iv = ivMix(le32(ts), ivMix(r.m, ivMix(r.q, 0x20230928)));
        return r;
    }

    // ---------- F13（MD5 变体）----------
    static final int[] MD5_CONST = {
        0xa7aefe20, 0x7149f1d6, 0x47e4ca07, 0xe9b58f67, 0x93b924de, 0xc614d0f5, 0x38afe0ef, 0xb2bbad73,
        0xe24444c3, 0x9d3aec9b, 0xdf7b37e4, 0xd8b16d40, 0xf8ac31b8, 0x76b9a90b, 0x31d833ee, 0x953fce64,
        0x353595a4, 0x4609c13b, 0x36925008, 0x8c6d0925, 0x5df5c177, 0x1cfbf52b, 0x8a4fa7f0, 0x114ca35e,
        0x8193f984, 0x7a7a8733, 0x316ab4d5, 0x3c20cfc9, 0xa6d84453, 0x3a18500c, 0x798ec47a, 0x97a76b28,
        0x66c4ff96, 0x51716443, 0x0dd2fc3b, 0xb5696da7, 0xbbeb3ac5, 0x5c53d204, 0xd32608ce, 0x7279b9ec,
        0xf4188ecf, 0xf7d793db, 0x332cc491, 0xab76ae15, 0x9bebe727, 0x18a01384, 0x5be9f8a7, 0x5f90a754,
        0x39b663c0, 0x36673c83, 0x7c92f514, 0x9d7d94d7, 0xe2e8d9aa, 0x5f7e9ea9, 0x7abd4551, 0x569e05da,
        0x40a25632, 0x3df5a9a5, 0xbab37d80, 0x454286dc, 0x3f5d4e78, 0x03d7b75d, 0xb1fe4af7, 0xa5ab26a3};
    static final byte[] ORDERS = hex("0f0704000908030a060b050d0e010c020f05080c0009020103070e060b0a0d04060500070c000a04080f010b0d09020e060b02050403080101070a000d0c090e0d070e0f0b0208030c0509010004060a0d0902060f0b0a040807000c0503010e0c090f07060f030e020d0405010b0a000c050a090e0802040407030f01060b000b0f0408020a0700090d06010e03050c0b060a0508020c03070f0e090d0001040906080f050800040a0b030d01020c0e090d0c0604070a03030f00080105020e01000d0f090a0b0e0402080703060c0501080a0c0f0905060b000304020e070d04080f000c0f0e0d0a01060207090503040205080d0b0a0606000e0f070c09030a08040f000b01060d0c0709030e05020a070b050f00020e0108030d0c0609040d070f08050f06040b0a0e0c090002030d0c020704010b0e0e08060f09050003");

    static byte[] f13(int iv, int ts, byte[] q, byte[] m) {
        int group = ((iv & 15) - 2) / 3;
        int[] seeds = {0x8980f29b, 0xeb549c7f, 0xb08726db, 0xd40cb5e6, 0xe8f559e4};
        int count = seeds[group] + ts;
        int shift = (count + 5) & 7;
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        msg.write(q, 0, 32); msg.write(m, 0, 16); byte[] le = le32(ts); msg.write(le, 0, 4);
        for (byte x : hex("8496779dd4150bf8")) {
            int v = ((x & 0xFF) * 257) >>> shift;
            msg.write(v & 0xFF);
        }
        msg.write(0xa0); msg.write(1); msg.write(0); msg.write(0);
        byte[] mb = msg.toByteArray();
        int[] start = {0x79e0f2fb, 0xc8b52570, 0xebc2f8cd, 0x7c104d93};
        int[] end = {0x19be4866, 0xe85986b4, 0xe19b326e, 0x71d1d7d4};
        for (int i = 0; i < 4; i++) {
            start[i] = Integer.rotateLeft(start[i], -(count & 31));
            end[i] = Integer.rotateLeft(end[i], -((count + 6) & 31));
        }
        int a = start[0], b = start[1], c = start[2], d = start[3];
        int[][] shifts = {{7, 12, 17, 22}, {5, 9, 14, 20}, {4, 11, 16, 23}, {6, 10, 15, 21}};
        for (int i = 0; i < 64; i++) {
            int fv;
            switch (i / 16) {
                case 0: fv = (b & c) | (~b & d); break;
                case 1: fv = (b & d) | (c & ~d); break;
                case 2: fv = b ^ c ^ d; break;
                default: fv = c ^ (b | ~d); break;
            }
            int word = leU32(mb, (ORDERS[group * 64 + i] & 0xFF) * 4);
            int x = b + Integer.rotateLeft(a + fv + word + Integer.rotateLeft(MD5_CONST[i], -((count + 1) & 31)), shifts[i / 16][i % 4]);
            a = d; d = c; c = b; b = x;
        }
        int[] fin = {a, b, c, d};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) { byte[] p = le32((start[i] + fin[i]) ^ end[i]); out.write(p, 0, 4); }
        byte[] ob = out.toByteArray();
        int check = 0x20220420;
        for (int i = 0; i < 12; i++) {
            int x = ob[i] & 0xFF;
            if ((i & 1) == 0) check = ((check >>> 3) ^ check) ^ x ^ (check << 7);
            else check = ((check >>> 5) ^ check) ^ ~(x | (check << 11));
        }
        byte[] res = new byte[20];
        System.arraycopy(ob, 0, res, 0, 16);
        System.arraycopy(le32((check | 4) ^ 0x1000000), 0, res, 16, 4);
        return res;
    }

    // ---------- Helios ----------
    static String helios(int ts, int nonce) {
        byte[] seed = new byte[4 + 4];
        System.arraycopy(le32(nonce), 0, seed, 0, 4);
        System.arraycopy("8662".getBytes(StandardCharsets.UTF_8), 0, seed, 4, 4);
        byte[] key = md5(seed);
        byte[] raw = toHex(key).getBytes(StandardCharsets.UTF_8); // 32 bytes hex string
        long k = leU64(raw, 0);
        long[] pending = {leU64(raw, 8), leU64(raw, 16), leU64(raw, 24)};
        long[] rounds = new long[34];
        rounds[0] = k;
        long[] pend = {pending[0], pending[1], pending[2]};
        for (int i = 0; i < 33; i++) {
            long x = (Long.rotateLeft(pend[0], -8) + k) ^ (long) i;
            pend[0] = pend[1]; pend[1] = pend[2]; pend[2] = x;
            k = x ^ Long.rotateLeft(k, 3);
            rounds[i + 1] = k;
        }
        byte[] plain = (ts + "-1588093228-8662").getBytes(StandardCharsets.UTF_8);
        int pad = 16 - plain.length % 16;
        byte[] p = new byte[plain.length + pad];
        System.arraycopy(plain, 0, p, 0, plain.length);
        for (int i = plain.length; i < p.length; i++) p[i] = (byte) pad;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(le32(nonce), 0, 4);
        for (int off = 0; off < p.length; off += 16) {
            long a = leU64(p, off), b = leU64(p, off + 8);
            for (long r : rounds) {
                b = r ^ (a + Long.rotateLeft(b, -8));
                a = b ^ Long.rotateLeft(a, 3);
            }
            byte[] ab = new byte[16]; putLE64(ab, 0, a); putLE64(ab, 8, b); out.write(ab, 0, 16);
        }
        return b64(out.toByteArray());
    }

    // ---------- Gorgon ----------
    static String gorgon(String query, byte[] body, int ts, int nonce) {
        byte[] q = md5(query.getBytes(StandardCharsets.UTF_8));
        byte[] m = (body != null && body.length > 0) ? md5(body) : new byte[16];
        byte[] data = new byte[20];
        System.arraycopy(q, 0, data, 0, 4);
        System.arraycopy(m, 0, data, 4, 4);
        putLE32(data, 12, 67503104);
        data[16] = (byte) (ts >>> 24); data[17] = (byte) (ts >>> 16); data[18] = (byte) (ts >>> 8); data[19] = (byte) ts;
        int[] key = {0x4a, 0x40, 0x16, (nonce >>> 8) & 0xFF, 0x47, 0x6c, 1, nonce & 0xFF};
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) { j = (j + s[i] + key[i % 8]) & 255; s[i] = s[j]; } // Go 版是单向赋值，非标准 RC4 交换
        j = 0;
        for (int i = 0; i < 20; i++) {
            j += s[(i + 1) & 255];
            int y = s[j & 255];
            s[i + 1] = y;
            data[i] = (byte) ((data[i] & 0xFF) ^ s[(y * 2) & 255]);
        }
        for (int i = 0; i < 20; i++) {
            int di = rotl8(data[i] & 0xFF, 4);
            // Go: next = data[i+1]（i<19，仍是未被本轮覆写的值）或 data[0]（i==19，已是最终值）
            int next = (i < 19) ? (data[i + 1] & 0xFF) : (data[0] & 0xFF);
            data[i] = (byte) (~(reverse8(di ^ next) ^ 20));
        }
        byte[] out = new byte[6 + 20];
        out[0] = (byte) 0x84; out[1] = 4; out[2] = (byte) nonce; out[3] = (byte) (nonce >>> 8); out[4] = 0x40; out[5] = 1;
        System.arraycopy(data, 0, out, 6, 20);
        return toHex(out);
    }

    // ---------- protobuf 构造 ----------
    static final class Proto {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        void uvarint(long x) {
            while ((x & ~0x7FL) != 0) { b.write((int) ((x & 0x7F) | 0x80)); x >>>= 7; }
            b.write((int) (x & 0x7F));
        }
        void sint(int field, long n) {
            uvarint((long) (field << 3));
            uvarint((n << 1) ^ (n >> 63));
        }
        void blob(int field, byte[] d) {
            if (d == null || d.length == 0) return;
            uvarint((long) (field << 3 | 2));
            uvarint(d.length);
            b.write(d, 0, d.length);
        }
        void text(int field, String s) { blob(field, s.getBytes(StandardCharsets.UTF_8)); }
        void floatf(int field, float f) {
            uvarint((long) (field << 3 | 5));
            int bits = Float.floatToIntBits(f);
            byte[] le = le32(bits); b.write(le, 0, 4);
        }
        byte[] bytes() { return b.toByteArray(); }
    }

    static long u32(int v) { return v & 0xFFFFFFFFL; }

    static byte[] protoVersion(String query, int ts, int nonce, String deviceID, byte[] q, byte[] m, byte[] f13, String version) {
        Proto device = new Proto(), env = new Proto(), report = new Proto(), count = new Proto(), p = new Proto();
        device.sint(1, 1); device.sint(2, 2);
        device.text(3, "8662"); device.text(4, deviceID);
        device.text(5, "Ai6svO3PyrwDOUSmO6ZcResxu"); device.text(6, "!noperm!");
        device.sint(7, -888888); device.sint(8, -888888);
        device.sint(9, 3); device.sint(10, -888888);
        device.text(11, "!notset!"); device.text(12, "Asia/Shanghai,8"); device.text(13, "zh_CN");
        device.sint(14, 4);
        float[] fs = {255.24993896484375f, 35.58599090576172f, 3.467449188232422f, 3.467449188232422f, 255.1754913330078f, 42.17544174194336f};
        for (int i = 0; i < fs.length; i++) device.floatf(i + 16, fs[i]);
        device.text(22, "16"); device.sint(23, 41); device.sint(24, 36);
        for (int i = 25; i <= 28; i++) { long n = 1728388016635L; if (i == 28) n += 2; device.sint(i, n); }
        device.sint(29, -1);
        String[] ss = {"25053RT47C", "Redmi", "25053RT47C", "25053RT47C", "Redmi", "Redmi", "Redmi"};
        for (int i = 0; i < ss.length; i++) device.text(i + 30, ss[i]);
        device.sint(38, 31);
        report.sint(1, u32(ts)); report.sint(2, -2); report.sint(4, 200);
        env.sint(1, 110); env.sint(2, 146331399); env.sint(3, 146331396); env.sint(5, 7);
        env.text(6, "v04.06.04.03-bugfix"); env.sint(7, 11001);
        env.blob(12, device.bytes()); env.blob(13, report.bytes()); env.text(14, version);
        count.sint(1, 111); count.sint(2, 10); count.sint(3, 694367); count.sint(5, 586952199);
        p.blob(1, hex("f7e85ffad7d7dc3bd62ac87057cf6118"));
        p.sint(2, 3); p.sint(3, u32(nonce));
        p.text(4, "8662"); p.text(5, deviceID); p.text(6, "1588093228");
        p.text(7, version); p.text(8, "v04.06.04-ml-android");
        p.sint(9, 67503104); p.blob(10, hex("4001000000000000")); p.sint(12, u32(ts));
        p.blob(13, f13);
        byte[] q6 = new byte[6]; System.arraycopy(q, 0, q6, 0, 6); p.blob(14, q6);
        p.blob(15, count.bytes());
        p.text(16, "AXYQOS6n2m60x1fVZHIrH3iol"); p.sint(17, u32(ts));
        ByteArrayOutputStream hinp = new ByteArrayOutputStream();
        hinp.write(query.getBytes(StandardCharsets.UTF_8), 0, query.getBytes(StandardCharsets.UTF_8).length);
        hinp.write(m, 0, 16); byte[] none = "none".getBytes(StandardCharsets.UTF_8); hinp.write(none, 0, none.length);
        byte[] hash = sm3(hinp.toByteArray());
        p.blob(19, hash);
        p.text(20, "none"); p.sint(21, 312); p.blob(23, env.bytes());
        p.text(24, "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0,\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}");
        return p.bytes();
    }

    // ---------- XMXOR ----------
    static byte[] xmxor(byte[] data, byte[] key) {
        int n = data.length;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int k = (i * 4) & 28;
            int a = key[k] & 0xFF, b = key[k + 1] & 0xFF;
            int v = (~(rotl8(data[i] & 0xFF, 4) + a)) ^ b;
            v = rotl8(v & 0xFF, 3) + b;
            out[n - 1 - i] = (~(v ^ a)) & 0xFF;
        }
        int flag = out[n - 1] ^ out[n - 2];
        int old = out[0];
        out[0] = (out[0] + (~flag)) & 0xFF;
        out[1] = (out[1] + (out[0] ^ out[n - 1] ^ 254)) & 0xFF;
        out[2] = (out[2] + (((flag - old) & 0xFF) ^ rotl8(out[1], 3) ^ 2)) & 0xFF;
        for (int i = 0; i < n - 4; i++) {
            out[i + 3] = (out[i + 3] + (~(rotl8(out[i + 2], 3) ^ out[i + 1] ^ (i + 3)))) & 0xFF;
        }
        out[n - 1] ^= out[n - 2];
        int sum = 0;
        for (int i = 1; i < n; i++) sum = (sum + out[i]) & 0xFF;
        out[0] = ((out[0] ^ out[1]) + sum) & 0xFF;
        byte[] res = new byte[n];
        for (int i = 0; i < n; i++) res[i] = (byte) out[i];
        return res;
    }

    // ---------- AES-V3 ----------
    static final byte[] SBOX = hex("fa7d086b9c59b34b045f39d0384a91990067a6209ff54d827326eedf18668333800319fbd9feaeaaa9b052c60bf379254e78b436ac5d1a279e88dbbd3c63ec4915c1301fdcb856d46ccdca0943c835a3ef1ef496d2fc0e727b9484d1ea455a62023fd31281342bdd7ee628f2a54613013b21f66137292a0ded8cafbf9d5cbb24760f75e45389e1988db19a65704f544c58ab6e6f8b23c407110cbacfa0a48ed8053d14b2da74c3d7e7bed67fde48163e8590a155b7774222c986502e17f964312c9bf16d1c4468e3e9a89397cb3257ebe5716aadc0ccc7c5fd601da22d47a7e251695e7ace0a41b6958ff7b987e03a06108ab5f85bd5f0bc92ff7c2fc2e81b40ec1bdabdba98910cb22b83413467fb0ad876b546055961237590872ae350154cacb179ebaee595470468f0863d518b0fca8ee4b94ef21282bc0ed5f7ef2825cf5b5de96a5502e133be93e7f5ad9d3e3924a8e2fa1757d07a0d0830d6b8a38dfd079ac41e6e226497d21db0bf45663f6cdddb2780a711dca6c552f8c0b6c85c0073607ba01913aac935484bd3a4cd9f99f3104440547e29f4061fa2aba12f3cf6af856236217f5edf201ab3b4e6ff72848f6526945a77ea4378c74acc2c146bc6e87453fcd41cce317003188c96383289f13a5fd7f9a969b7633758c23bc371cb9e92018a0b4d889bbb4f6d6fe0fea549de561609ed9cc12dee817dd97cd12e425b4dc1a65dea44fd454e1ba13fd189e17d2faadbabad59cbb1ce9a28c9e0f670394ad7ff30f5ddbc573b118db2ee00b6e61a5a7cf9dec4cd2e80bbb94ca59f8408c66f426cf027e78b3a9c51fb6721754131a7ca20432ab7bfd97af2b5f88c2c23834f8f60a004133714e301c563665c7481df58bd68903dd2b334f419933229d649ae0d4bd8079eac1e2d0b40b872ba761071a8e4561d48fee5c24791da87269d1f886bc098be25099733a385165e7fdc6e54e9f7a9c8e8c377d0822bec02628a920e3eb00f05f3f19678388636183c24cf0ab453cc6165a4c794d5157e6def792235126a8e5206557b46645095e20cedd31703a29b99eb1cfcafd47369fa5ff72c1ebfc8e1f39f76807148aa94ad64fb89c660c332b34dd2e044dd5fa8b1c7682334c96d127fb7eb15bea9d17893a00c92a4d747e38ac270ab26419a79a7d814858fc06f56d08c11b92e3ce29dcf0ede035d463ecd38430f335ad91a656c223bfc30a688ea37a2b48d8e519cd640eef9f884f4ae97e9ca0a456757042f835cd5c5c482b6a391981f4aac96816ecb1b0908af1895497d54edfa16313adab866f5a5f1fe10010674cc63df7c2825f6ceb24f8be5bc8769bb8621070036e70b50599b1ce862581961f2bd275eba1de699423d0d2ab5dc5b29f02d4c537b6a734e3f75ff4ba1351755723920d3b0fdef02ec777ee42bdb90c1059e7ad4526b2413");
    static final int[][] AES_CON = {{1,0,2,3},{2,0,3,1},{0,1,3,2},{1,0,2,3}};
    static final int[][] AES_ORDER = {
        {0,9,14,11,4,13,2,7,8,1,6,15,12,5,10,3},
        {0,9,14,15,4,13,2,7,8,1,6,3,12,5,10,11},
        {0,9,14,7,4,13,2,11,8,1,6,3,12,5,10,15},
        {0,9,14,11,4,13,2,7,8,1,6,15,12,5,10,3}};
    static final int[] AES_INIT = {0xca025ddc, 0x823dc546, 0xc9420583, 0xc298225f};

    static int xt(int x) { x &= 0xFF; return ((x & 128) != 0) ? ((x << 1) ^ 0x1b) & 0xFF : (x << 1) & 0xFF; }

    static byte[] aesV3(byte[] data, int ts) {
        int i = ts & 3;
        int boxOff = i * 256;
        int[] con = AES_CON[i], order = AES_ORDER[i];
        int init = AES_INIT[i];
        byte[] key = hex("f1593376766ea98d34f31b057a9d5be4");
        int[] mk = new int[48];
        for (int j = 0; j < 16; j++) mk[j] = (key[j] & 0xFF) ^ ((init >>> ((j % 4) * 8)) & 0xFF);
        int r = 8;
        for (int j = 4; j < 12; j++) {
            int[] prev = {mk[j * 4 - 4], mk[j * 4 - 3], mk[j * 4 - 2], mk[j * 4 - 1]};
            if (j % 4 == 0) {
                prev = new int[]{((init >>> (r & 24)) & 0xFF) ^ (SBOX[boxOff + prev[1]] & 0xFF), SBOX[boxOff + prev[2]] & 0xFF, SBOX[boxOff + prev[3]] & 0xFF, SBOX[boxOff + prev[0]] & 0xFF};
            }
            r += 2;
            for (int k = 0; k < 4; k++) mk[j * 4 + k] = prev[k] ^ mk[j * 4 + k - 16];
        }
        byte[] plain = new byte[32];
        for (int j = 0; j < 31; j++) {
            int d0 = data[j * 8] & 0xFF, d1 = data[j * 8 + 1] & 0xFF, d2 = data[j * 8 + 2] & 0xFF, d3 = data[j * 8 + 3] & 0xFF,
                d4 = data[j * 8 + 4] & 0xFF, d5 = data[j * 8 + 5] & 0xFF, d6 = data[j * 8 + 6] & 0xFF, d7 = data[j * 8 + 7] & 0xFF;
            plain[j] = (byte) (((d0 >>> 4) & 2) | (d1 & 64) | ((d2 >>> 2) & 1) | ((d3 << 3) & 128) | ((d4 >>> 1) & 4) | ((d5 << 3) & 16) | ((d6 << 5) & 32) | ((d7 >>> 4) & 8));
        }
        plain[31] = 1;
        byte[] previous = hex("1fe109a4125283f418de9e051a969e12");
        byte[] encrypted = new byte[32];
        int encLen = 0;
        for (int j = 0; j < 32; j += 16) {
            for (int k = 0; k < 16; k++) plain[j + k] ^= previous[k];
            byte[] blk = new byte[16]; System.arraycopy(plain, j, blk, 0, 16);
            previous = aesBlock(blk, mk, con, order, boxOff);
            System.arraycopy(previous, 0, encrypted, encLen, 16); encLen += 16;
        }
        byte[] out = new byte[1 + data.length];
        out[0] = encrypted[31];
        System.arraycopy(data, 0, out, 1, data.length);
        for (int j = 0; j < 31; j++) {
            int base = 1 + j * 8;
            int k = encrypted[j] & 0xFF;
            out[base]     = (byte) ((out[base]     & ~32) | ((k << 4) & 32));
            out[base + 1] = (byte) ((out[base + 1] & ~64) | (k & 64));
            out[base + 2] = (byte) ((out[base + 2] & ~4)  | ((k << 2) & 4));
            out[base + 3] = (byte) ((out[base + 3] & ~16) | ((k >>> 3) & 16));
            out[base + 4] = (byte) ((out[base + 4] & ~8)  | ((k << 1) & 8));
            out[base + 5] = (byte) ((out[base + 5] & ~2)  | ((k >>> 3) & 2));
            out[base + 6] = (byte) ((out[base + 6] & ~1)  | ((k >>> 5) & 1));
            out[base + 7] = (byte) ((out[base + 7] & 127) | ((k << 4) & 128));
        }
        return out;
    }

    static byte[] aesBlock(byte[] src, int[] mk, int[] con, int[] order, int boxOff) {
        int[] b = new int[16];
        for (int j = 0; j < 16; j++) b[j] = src[j] & 0xFF;
        // add(0)
        for (int j = 0; j < 16; j++) b[j] ^= mk[0 + (j / 4) * 4 + con[j % 4]] & 0xFF;
        for (int round = 1; round <= 2; round++) {
            int[] prev = b.clone();
            for (int j = 0; j < 16; j++) {
                int index = order[j];
                b[j] = SBOX[boxOff + (prev[con[index / 4] * 4 + index % 4] & 0xFF)] & 0xFF;
            }
            if (round == 1) {
                int[] prev2 = b.clone();
                for (int j = 0; j < 16; j++) b[j] = prev2[(j / 4) * 4 + con[j % 4]];
                for (int col = 0; col < 4; col++) {
                    int a = b[col], c = b[4 + col], d = b[8 + col], e = b[12 + col];
                    int t = a ^ c ^ d ^ e;
                    b[col]      = (b[col]      ^ t ^ xt(a ^ c)) & 0xFF;
                    b[4 + col]  = (b[4 + col]  ^ t ^ xt(c ^ d)) & 0xFF;
                    b[8 + col]  = (b[8 + col]  ^ t ^ xt(d ^ e)) & 0xFF;
                    b[12 + col] = (b[12 + col] ^ t ^ xt(e ^ a)) & 0xFF;
                }
            }
            for (int j = 0; j < 16; j++) b[j] ^= mk[round * 16 + (j / 4) * 4 + con[j % 4]] & 0xFF;
        }
        for (int j = 0; j < 16; j++) b[j] ^= mk[16 + j] & 0xFF;
        byte[] out = new byte[16];
        for (int j = 0; j < 16; j++) out[j] = (byte) b[j];
        return out;
    }

    // ---------- Medusa ----------
    static String medusa(String query, int ts, int nonce, int hashRand, int xmRand, String deviceID, int iv, byte[] q, byte[] m, String version) {
        byte[] f13 = f13(iv, ts, q, m);
        byte[] p = protoVersion(query, ts, nonce, deviceID, q, m, f13, version);
        byte[] signKey = hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5");
        ByteArrayOutputStream kin = new ByteArrayOutputStream();
        kin.write(signKey, 0, signKey.length);
        byte[] le = le32(hashRand); kin.write(le, 0, 4);
        kin.write(signKey, 0, signKey.length);
        byte[] key = sm3(kin.toByteArray());
        int d1 = (hashRand >>> 16) & 255;
        int seedVal = ~((((d1 << 11) | (hashRand >>> 24)) ^ (d1 >>> 5)) ^ d1);
        byte[] seed = le32(seedVal);
        // p = "4001000000000000" + xmxor(p, key)
        byte[] xp = xmxor(p, key);
        byte[] prefix = hex("4001000000000000");
        byte[] p2 = new byte[prefix.length + xp.length];
        System.arraycopy(prefix, 0, p2, 0, prefix.length);
        System.arraycopy(xp, 0, p2, prefix.length, xp.length);
        p = p2;
        // reverse
        for (int a = 0, b = p.length - 1; a < b; a++, b--) { byte t = p[a]; p[a] = p[b]; p[b] = t; }
        for (int i = 0; i < p.length; i++) p[i] ^= seed[(~i) & 3];
        int check = 0x18000001 | ((q[0] & 63) << 14) | ((f13[0] & 63) << 8);
        byte[] lx = le32(xmRand);
        byte[] lc = le32(check);
        byte[] lh = le32(hashRand);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x35);
        body.write(lx, 0, 4);
        body.write(lc, 0, 4);
        body.write(p, 0, p.length);
        body.write(lh[2] & 0xFF); // le32(hashRand)[2:]
        body.write(lh[3] & 0xFF);
        byte[] enc = aesV3(body.toByteArray(), ts);
        byte[] header = hex("03000000f7e85ffad7d7dc3bd62ac87057cf6118");
        for (int j = 0; j < 20; j += 4) {
            int w = leU32(header, j) ^ ts;
            putLE32(header, j, w);
        }
        ByteArrayOutputStream outb = new ByteArrayOutputStream();
        outb.write(header, 0, header.length);
        outb.write(lh[0] & 0xFF); outb.write(lh[1] & 0xFF);
        outb.write(0); outb.write(1);
        outb.write(enc, 0, enc.length);
        return b64(outb.toByteArray());
    }
}
