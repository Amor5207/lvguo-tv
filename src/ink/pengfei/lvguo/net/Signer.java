package ink.pengfei.lvguo.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 红果 App 通道请求签名（Gorgon / Argus / Ladon / Helios / Medusa）。
 *
 * 逐行移植自 hongguo-demo/hg/signer.py（其本身移植自 hongguoTV Signer.kt，GPL-3.0）。
 * 「签名族」接口（收藏写入、预约读取、批量剧集资料…）缺签名时网关静默回 0 字节，
 * 写接口回 101144。这类算法任何一处「优化」都会让签名对不上，所以保持原样。
 *
 * 纯 JVM 实现（不依赖 Android API），可以直接和 Python 版用同一组随机数对拍。
 */
public final class Signer {
    private Signer() {}

    /** 随机源可注入：线上用 Math.random，对拍时用固定序列。 */
    public interface Random { double next(); }
    private static final Random DEFAULT = Math::random;

    public static final String APP_UA = "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)";
    public static final String VIDEO_UA = "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; "
            + "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";

    public static final class Signed {
        public final String url; public final Map<String, String> headers;
        Signed(String url, Map<String, String> headers) { this.url = url; this.headers = headers; }
    }

    // ------------------------------------------------------------ 常量

    private static final long[] HASH_TABLE = {
            0xEBB64FAFL, 0x7AADCC2L, 0xCF3187BFL, 0xE01138FFL, 0x6D0BFCFFL, 0x5A30A3BEL, 0xB41AD638L,
            0x34180EB8L, 0xF233EB6FL, 0xB1A584CCL, 0xCCC30DC7L, 0x47D1DB51L, 0xD55653DEL, 0x70A84FA1L,
            0x57473C12L, 0xF76F0288L, 0x2C077F0AL, 0xDA0DCAD0L, 0xFBB86F6CL, 0xFDC4CF00L, 0x688A020DL,
            0xE676C6A6L, 0x8CD6338BL, 0x1A3C8D0EL, 0xCCE8B06BL, 0x6AD0ED0BL, 0xA0522717L, 0xDC71AC83L,
            0x2285DB71L, 0xD5B4DDA6L, 0x736F8650L, 0x6560306CL, 0x617CE2A6L, 0xE423417EL, 0xA40E143L,
            0x544E4032L, 0x88DFFB2AL, 0x716C1AE0L, 0x4C467A88L, 0x5B23BB3L, 0xE1D0B866L, 0xBAA3DCB8L,
            0xAE3374D3L, 0xC3381A50L, 0x1702F75BL, 0xFE6DA368L, 0xF0B4CF48L, 0x4E0FFBB8L, 0x72AAD10DL,
            0x26C53A3DL, 0xF2BCE0F6L, 0xB4557581L, 0x4A257FDDL, 0x8C3182A2L, 0xAB0B3B86L, 0x3D5DFB14L,
            0x4F103634L, 0xD37B52D7L, 0x444EFF16L, 0xEB0A33D1L, 0x6CA86F6EL, 0x284BA7L, 0x8387CFAL,
            0x5FB37586L,
    };

    private static final String SBOX_B64 =
            "+n0Ia5xZs0sEXznQOEqRmQBnpiCf9U2Ccybu3xhmgzOAAxn72f6uqqmwUsYL83klTni0NqxdGieeiNu9PGPsSRXBMB/cuFbUbM3KCUPINaPvHvSW0vwOcnuUhNHqRVpiAj/TEoE0K91+5ijypUYTATsh9mE3KSoN7Yyvv51cuyR2D3XkU4nhmI2xmmVwT1RMWKtub4sjxAcRDLrPoKSO2AU9FLLadMPX577Wf95IFj6FkKFVt3dCIsmGUC4X+WQxLJvxbRxEaOPpqJOXyzJX6+Vxaq3AzMfF/WAdoi1Hp+JRaV56zgpBtpWP97mH4DoGEIq1+FvV8LyS/3wvwugbQOwb2r26mJEMsiuDQTRn+wrYdrVGBVlhI3WQhyrjUBVMrLF5667llUcEaPCGPVGLD8qO5LlO8hKCvA7V9+8oJc9bXelqVQLhM76T5/WtnT45JKji+hdX0HoNCDDWuKON/QeaxB5uImSX0h2wv0VmP2zd2yeApxHcpsVS+MC2yFwAc2B7oBkTqsk1SEvTpM2fmfMQREBUfin0Bh+iq6EvPPavhWI2IX9e3yAas7Tm/3KEj2UmlFp36kN4x0rMLBRrxuh0U/zUHM4xcAMYjJY4MonxOl/X+alpt2M3WMI7w3HLnpIBigtNiJu7T21v4P6lSd5WFgntnMEt7oF92XzRLkJbTcGmXepE/UVOG6E/0YnhfS+q26utWcuxzpooyeD2cDlK1/8w9d28VzsRjbLuALbmGlp8+d7EzS6Au7lMpZ+ECMZvQmzwJ+eLOpxR+2chdUExp8ogQyq3v9l68rX4jCwjg0+PYKAEEzcU4wHFY2ZcdIHfWL1okD3SszT0GZMyKdZJrg1L2AeerB4tC0C4crp2EHGo5FYdSP7lwkeR2ocmnR+Ia8CYviUJlzOjhRZef9xuVOn3qcjow3fQgivsAmKKkg4+sA8F8/GWeDiGNhg8JM8KtFPMYWWkx5TVFX5t73kiNRJqjlIGVXtGZFCV4gzt0xcDopuZ6xz8r9Rzafpf9ywev8jh8592gHFIqpStZPuJxmDDMrNN0uBE3V+oscdoIzTJbRJ/t+sVvqnReJOgDJKk10fjisJwqyZBmnmn2BSFj8BvVtCMEbkuPOKdzw7eA11GPs04Qw8zWtkaZWwiO/wwpojqN6K0jY5RnNZA7vn4hPSul+nKCkVnVwQvg1zVxcSCtqORmB9KrJaBbssbCQivGJVJfVTt+hYxOtq4ZvWl8f4QAQZ0zGPffCgl9s6yT4vlvIdpu4YhBwA25wtQWZsc6GJYGWHyvSdeuh3mmUI9DSq13Fsp8C1MU3tqc04/df9LoTUXVXI5INOw/e8C7Hd+5CvbkMEFnnrUUmskEw==";
    private static byte[] sbox;
    private static synchronized byte[] sbox() { if (sbox == null) sbox = b64decode(SBOX_B64); return sbox; }

    // ------------------------------------------------------------ 入口

    public static Signed sign(String base, Map<String, String> values, byte[] body, String deviceId, String version) {
        return sign(base, values, body, deviceId, version, System.currentTimeMillis(), DEFAULT);
    }

    /** 生成签名后的 url + headers。签名覆盖 query 与 body，调用方签完后不能再改参数顺序。 */
    public static Signed sign(String base, Map<String, String> values, byte[] body, String deviceId, String version,
                              long now, Random random) {
        int ts = (int) (now / 1000);
        for (int offset = 0; offset < 128; offset++) {
            long ticket = now + offset;
            Map<String, String> merged = new LinkedHashMap<>(values);
            merged.put("ts", String.valueOf(ts));
            merged.put("_rticket", String.valueOf(ticket));
            String query = encodeParams(merged);
            if (branch(query, body, ts) != 0) continue;
            String url = base + "?" + query;
            int rnd = (int) (random.next() * 65536);
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", body != null ? VIDEO_UA : APP_UA);
            h.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
            h.put("x-ss-req-ticket", String.valueOf(ticket));
            h.put("x-tt-request-tag", "t=0;n=0");
            h.put("sdk-version", "2");
            h.put("passport-sdk-version", "50561");
            h.put("x-xs-from-web", "0");
            h.put("x-khronos", String.valueOf(ts));
            h.put("x-ladon", b64encode(be32(ts)));
            h.put("x-argus", b64encode(le32(ts)));
            h.put("x-gorgon", gorgon(query, body, ts, rnd));
            h.put("x-helios", helios(ts, rnd));
            h.put("x-medusa", medusa(url, body, ts, deviceId, version, now, random));
            h.put("x-tt-dt", "");
            if (body != null) {
                h.put("Content-Type", "application/json; charset=UTF-8");
                h.put("x-vc-bdturing-sdk-version", "3.7.2.cn");
                h.put("x-ss-stub", hex(md5(body)).toUpperCase(java.util.Locale.US));
            }
            return new Signed(url, h);
        }
        throw new IllegalStateException("无法生成请求签名");
    }

    // ------------------------------------------------------------ 编码

    public static String encodeParams(Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    /** 等价 Python quote_plus(safe="*") 再把 '+'→'%20'、'%7E'→'~'。 */
    static String enc(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int c = x & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '~' || c == '*') sb.append((char) c);
            else if (c == ' ') sb.append("%20");
            else { sb.append('%'); sb.append(Character.toUpperCase(Character.forDigit(c >> 4, 16))); sb.append(Character.toUpperCase(Character.forDigit(c & 15, 16))); }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 分支选择 / hashF13

    private static int getIv(int initial, byte[] data) {
        int value = initial;
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            if ((i & 1) == 0) value = (value >>> 4) ^ value ^ (value << 6) ^ b;
            else value = ~((value >>> 7) ^ value ^ (b | (value << 12)));
        }
        return value;
    }
    private static int iv(byte[] query, byte[] body, int ts) { return getIv(getIv(getIv(0x20230928, query), body), le32(ts)); }

    static int branch(String query, byte[] body, int ts) {
        byte[] q = sm3(query.getBytes(StandardCharsets.UTF_8));
        byte[] b = body != null ? md5(body) : new byte[16];
        return (iv(q, b, ts) & 15) % 3;
    }

    private static int sumMd5(byte[] data) {
        int check = 0x20220420;
        for (int i = 0; i < 12; i++) {
            int temp = (check >>> ((i & 1) == 0 ? 3 : 5)) ^ check;
            if ((i & 1) == 0) check = (data[i] & 0xFF) ^ (check << 7);
            else check = ~((data[i] & 0xFF) | (check << 11));
            check = check ^ temp;
        }
        return (check | 4) ^ 0x1000000;
    }

    static byte[] hashF13(byte[] query, byte[] body, int ts) {
        int v = iv(query, body, ts);
        if ((v & 15) % 3 != 0) throw new IllegalStateException("Unsupported hash branch");
        int index = (v & 15) / 3;
        int ivV1 = (int) new long[]{0xC4A78580L, 0xB3C0FD39L, 0xC58C5686L, 0xC9AA3BA7L, 0xF5A7ADF2L, 0x963C2ED1L}[index];
        int count = ivV1 + ts;
        int[] tt = new int[64];
        for (int i = 0; i < 64; i++) tt[i] = Integer.rotateRight((int) HASH_TABLE[i], (count + 1) & 255);
        int shift = (count + 2) & 7;
        int[] padSrc = {0xFA, 0x45, 0x61, 0xD7};
        byte[] pad = new byte[4];
        for (int i = 0; i < 4; i++) { int x = padSrc[i]; pad[i] = (byte) (((x | (x << 8)) >> shift) & 0xFF); }
        byte[] data = concat(query, body, le32(ts), pad, unhex("00000000000001a0"));
        int[] di = new int[data.length / 4 + 112];
        int n = data.length / 4;
        for (int i = 0; i < n; i++)
            di[i] = ((data[i * 4] & 0xFF) << 24) | ((data[i * 4 + 1] & 0xFF) << 16) | ((data[i * 4 + 2] & 0xFF) << 8) | (data[i * 4 + 3] & 0xFF);
        for (int i = 0; i < 112; i++) {
            int x = di[i + 1], y = di[i + 14];
            di[n + i] = di[i] + di[i + 9]
                    + (Integer.rotateLeft(x, 14) ^ Integer.rotateLeft(x, 25) ^ (x >>> 3))
                    + (Integer.rotateLeft(y, 13) ^ Integer.rotateLeft(y, 15) ^ (y >>> 10));
        }
        int[] initialSrc = {0x7ABA4FC8, 0x67166507, 0x6403FA00, 0x340F512F, 984304912, -1289919430, -1420842003, -2142554032};
        int[] initial = new int[8];
        for (int i = 0; i < 8; i++) initial[i] = Integer.rotateRight(initialSrc[i], count);
        int[] chosen = new int[][]{
                {101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3},
                {96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4},
                {96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5},
                {99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6},
                {96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4},
                {100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5},
        }[index];
        int[] d = initial.clone();
        for (int i = 0; i < chosen[0]; i++) {
            int base = ivV1 + i;
            int n1 = ((d[chosen[3]] ^ d[chosen[4]]) & d[chosen[1]]) ^ d[chosen[3]];
            int n2 = Integer.rotateLeft(d[chosen[1]], 26) ^ Integer.rotateLeft(d[chosen[1]], 21) ^ Integer.rotateLeft(d[chosen[1]], 7);
            int n4 = di[base & 127] + n1 + n2 + tt[base & 63] + d[chosen[5]];
            int n5 = Integer.rotateLeft(d[chosen[2]], 30) ^ Integer.rotateLeft(d[chosen[2]], 19) ^ Integer.rotateLeft(d[chosen[2]], 10);
            int n6 = (d[chosen[2]] & d[chosen[6]]) | ((d[chosen[2]] | d[chosen[6]]) & d[chosen[7]]);
            int old = d[chosen[9]];
            int last = d[7];
            System.arraycopy(d, 0, d, 1, 7);
            d[0] = last;
            d[chosen[10]] = n5 + n6 + n4;
            d[chosen[8]] = old + n4;
        }
        byte[] ret = new byte[32];
        for (int i = 0; i < 8; i++) System.arraycopy(be32(d[i] + initial[i]), 0, ret, i * 4, 4);
        byte[] folded = new byte[16];
        for (int i = 0; i < 16; i++) folded[i] = (byte) (ret[i] ^ ret[16 + i]);
        return concat(folded, le32(sumMd5(folded)));
    }

    // ------------------------------------------------------------ Gorgon

    static String gorgon(String query, byte[] body, int ts, int rnd) {
        byte[] inp = concat(Arrays4.head(md5(query.getBytes(StandardCharsets.UTF_8)), 4),
                body != null ? Arrays4.head(md5(body), 4) : new byte[4], new byte[4], le32(67503104), be32(ts));
        int[] key = {0x4A, 0x40, 0x16, (rnd >> 8) & 0xFF, 0x47, 0x6C, 1, rnd & 0xFF};
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) { j = (j + s[i] + key[i % 8]) & 255; s[i] = s[j]; }
        j = 0;
        int[] out = new int[inp.length];
        for (int n = 0; n < inp.length; n++) {
            int i = n + 1;
            j = (j + s[i]) & 255;
            int y = s[j & 255];
            s[i] = y;
            out[n] = (inp[n] & 0xFF) ^ s[(y + y) & 255];
        }
        for (int i = 0; i < out.length; i++) {
            int value = ((out[i] >> 4) | (out[i] << 4)) & 255;
            int nxt = i + 1 < out.length ? out[i + 1] : out[0];
            out[i] = (~(bitrev8(nxt ^ value) ^ 20)) & 255;
        }
        StringBuilder sb = new StringBuilder("8404");
        sb.append(hex(new byte[]{(byte) (rnd & 0xFF), (byte) ((rnd >> 8) & 0xFF), 0x40, 1}));
        for (int x : out) sb.append(String.format("%02x", x));
        return sb.toString();
    }
    private static int bitrev8(int x) { return Integer.reverse(x & 0xFF) >>> 24; }

    // ------------------------------------------------------------ Helios

    static String helios(int ts, int rnd) {
        byte[] asciiHex = hex(md5(concat(le32(rnd), "8662".getBytes(StandardCharsets.US_ASCII)))).getBytes(StandardCharsets.US_ASCII);
        List<Long> words = new ArrayList<>();
        for (int i = 0; i < 4; i++) words.add(le64(asciiHex, i * 8));
        List<Long> table = new ArrayList<>();
        table.add(words.get(0));
        long b0 = words.remove(0);
        long b8 = words.remove(0);
        for (int i = 0; i < 34; i++) {
            long x8 = Long.rotateRight(b8, 8) + b0;
            x8 ^= i;
            words.add(x8);
            x8 ^= Long.rotateRight(b0, 61);
            table.add(x8);
            b0 = x8;
            b8 = words.remove(0);
        }
        byte[] text = (ts + "-1588093228-8662").getBytes(StandardCharsets.US_ASCII);
        int padding = 16 - text.length % 16;
        byte[] data = new byte[text.length + padding];
        System.arraycopy(text, 0, data, 0, text.length);
        for (int i = text.length; i < data.length; i++) data[i] = (byte) padding;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(le32(rnd), 0, 4);
        for (int at = 0; at < data.length; at += 16) {
            long a = le64(data, at), b = le64(data, at + 8);
            for (int i = 0; i < 34; i++) {
                b = table.get(i) ^ (a + Long.rotateRight(b, 8));
                a = b ^ Long.rotateRight(a, 61);
            }
            byte[] la = le64bytes(a), lb = le64bytes(b);
            output.write(la, 0, 8); output.write(lb, 0, 8);
        }
        return b64encode(output.toByteArray());
    }

    // ------------------------------------------------------------ Medusa

    private static final class Proto {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Proto str(int tag, String v) { return bytes(tag, v.getBytes(StandardCharsets.UTF_8)); }
        Proto bytes(int tag, byte[] raw) {
            if (raw.length == 0) return this;
            varint((tag << 3) | 2); varint(raw.length); out.write(raw, 0, raw.length); return this;
        }
        Proto sint(int tag, long n) { varint(tag << 3); varint(n < 0 ? -n * 2 - 1 : n * 2); return this; }
        Proto flt(int tag, float f) { varint((tag << 3) | 5); int bits = Float.floatToIntBits(f); out.write(le32(bits), 0, 4); return this; }
        void varint(long n) {
            while (Long.compareUnsigned(n, 127) > 0) { out.write((int) ((n & 127) | 128)); n >>>= 7; }
            out.write((int) n);
        }
        byte[] get() { return out.toByteArray(); }
    }

    private static byte[] deviceProto(String deviceId) {
        return new Proto().sint(1, 1).sint(2, 2).str(3, "8662").str(4, deviceId)
                .str(5, "Ai6svO3PyrwDOUSmO6ZcResxu").str(6, "!noperm!")
                .sint(7, -888888).sint(8, -888888).sint(9, 3).sint(10, -888888)
                .str(11, "!notset!").str(12, "Asia/Shanghai,8").str(13, "zh_CN").sint(14, 4)
                .flt(16, 255.24993896484375f).flt(17, 35.58599090576172f)
                .flt(18, 3.467449188232422f).flt(19, 3.467449188232422f)
                .flt(20, 255.1754913330078f).flt(21, 42.17544174194336f)
                .str(22, "16").sint(23, 41).sint(24, 36)
                .sint(25, 1728388016635L).sint(26, 1728388016635L).sint(27, 1728388016635L)
                .sint(28, 1728388016637L).sint(29, -1)
                .str(30, "25053RT47C").str(31, "Redmi").str(32, "25053RT47C").str(33, "25053RT47C")
                .str(34, "Xiaomi").str(35, "Redmi").str(36, "Redmi").sint(38, 31).get();
    }

    private static byte[] xmxor(byte[] data, byte[] key) {
        int len = data.length;
        int[] encoded = new int[len];
        for (int i = 0; i < len; i++) {
            int at = (i * 4) & 28;
            int d0 = key[at] & 0xFF, d1 = key[at + 1] & 0xFF;
            int v = data[i] & 0xFF;
            long d2 = ((((v << 4) | (v >> 4)) & 255) + d0) & 0xFFFFFFFFL;
            d2 = (~d2) ^ d1;
            int e = (int) (d2 & 255);
            e = ((e << 3) | (e >> 5)) & 255;
            e = (e + d1) & 255;
            e = (e ^ d0) & 255;
            encoded[len - i - 1] = (~e) & 255;
        }
        int last = encoded[len - 1] ^ encoded[len - 2];
        int first = encoded[0];
        encoded[0] = ((~last) + first) & 255;
        encoded[1] = ((encoded[0] ^ encoded[len - 1] ^ 254) + encoded[1]) & 255;
        encoded[2] = (encoded[2] + ((last - first) ^ (((encoded[1] << 3) | (encoded[1] >> 5)) & 255) ^ 2)) & 255;
        for (int i = 0; i < len - 4; i++) {
            int temp = (((encoded[i + 2] << 3) | (encoded[i + 2] >> 5)) & 255) ^ encoded[i + 1] ^ (i + 3);
            encoded[i + 3] = ((~temp) + encoded[i + 3]) & 255;
        }
        encoded[len - 1] = encoded[len - 1] ^ encoded[len - 2];
        int sum = 0;
        for (int i = 1; i < len; i++) sum += encoded[i];
        encoded[0] = ((encoded[0] ^ encoded[1]) + sum) & 255;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = (byte) encoded[i];
        return out;
    }

    /** 平台自制的分块变换（不是标准 AES），只用于 Medusa 信封。 */
    private static final class AesV3 {
        final int[] box = new int[256];
        final int[] con, order;
        final int[][] keys = new int[12][];

        AesV3(byte[] key, int ts) {
            int word = ts & 3;
            byte[] sb = sbox();
            for (int i = 0; i < 256; i++) box[i] = sb[word * 256 + i] & 0xFF;
            con = new int[][]{{1, 0, 2, 3}, {2, 0, 3, 1}, {0, 1, 3, 2}, {1, 0, 2, 3}}[word];
            order = new int[][]{
                    {0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3},
                    {0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11},
                    {0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15},
                    {0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3},
            }[word];
            long initial = new long[]{0xCA025DDCL, 0x823DC546L, 0xC9420583L, 0xC298225FL}[word];
            byte[] init4 = le32((int) initial);
            int[] expanded = new int[48];
            for (int i = 0; i < 16; i++) expanded[i] = (init4[i % 4] ^ key[i % key.length]) & 0xFF;
            int rounds = 8;
            for (int i = 4; i < 12; i++) {
                int at = 4 * (i - 1);
                int a = expanded[at], b = expanded[at + 1], c = expanded[at + 2], d = expanded[at + 3];
                if ((i & 3) == 0) {
                    int t = (int) (((initial >> (rounds & 24)) ^ box[b]) & 255);
                    b = box[c]; c = box[d]; d = box[a]; a = t;
                }
                rounds += 2;
                expanded[at + 4] = a ^ expanded[at - 12];
                expanded[at + 5] = b ^ expanded[at - 11];
                expanded[at + 6] = c ^ expanded[at - 10];
                expanded[at + 7] = d ^ expanded[at - 9];
            }
            for (int i = 0; i < 12; i++) keys[i] = new int[]{expanded[i * 4], expanded[i * 4 + 1], expanded[i * 4 + 2], expanded[i * 4 + 3]};
        }

        private static int xt(int x) { return ((x << 1) ^ ((x & 128) != 0 ? 0x1B : 0)) & 255; }

        byte[] block(byte[] value) {
            int[][] rows = new int[4][4];
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] = value[i * 4 + j] & 0xFF;
            addCon(rows, 0);
            for (int rnd = 1; rnd <= 2; rnd++) {
                for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] = box[rows[i][j]];
                int[][] before = new int[4][];
                for (int i = 0; i < 4; i++) before[i] = rows[i].clone();
                for (int i = 0; i < 4; i++) rows[i] = before[con[i]].clone();
                int[] flat = new int[16];
                for (int i = 0; i < 16; i++) flat[i] = rows[i / 4][i % 4];
                for (int i = 0; i < 16; i++) rows[i / 4][i % 4] = flat[order[i]];
                if (rnd == 1) {
                    for (int i = 0; i < 4; i++) {
                        int[] old = rows[i].clone();
                        rows[i] = new int[]{old[con[0]], old[con[1]], old[con[2]], old[con[3]]};
                    }
                    for (int i = 0; i < 4; i++) {
                        int t = rows[0][i] ^ rows[1][i] ^ rows[2][i] ^ rows[3][i];
                        int u = rows[0][i];
                        rows[0][i] ^= t ^ xt(rows[0][i] ^ rows[1][i]);
                        rows[1][i] ^= t ^ xt(rows[1][i] ^ rows[2][i]);
                        rows[2][i] ^= t ^ xt(rows[2][i] ^ rows[3][i]);
                        rows[3][i] ^= t ^ xt(rows[3][i] ^ u);
                    }
                }
                addCon(rows, rnd * 4);
            }
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] ^= keys[4 + i][j];
            byte[] out = new byte[16];
            for (int i = 0; i < 16; i++) out[i] = (byte) rows[i / 4][i % 4];
            return out;
        }
        private void addCon(int[][] rows, int offset) {
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] ^= keys[offset + i][con[j]];
        }

        byte[] encrypt(byte[] source, byte[] iv) {
            if (source.length < 248) throw new IllegalArgumentException("medusa: 输入长度不足 248");
            byte[] plaintext = new byte[32];
            for (int i = 0; i < 31; i++) {
                int at = i * 8;
                int s0 = source[at] & 0xFF, s1 = source[at + 1] & 0xFF, s2 = source[at + 2] & 0xFF, s3 = source[at + 3] & 0xFF;
                int s4 = source[at + 4] & 0xFF, s5 = source[at + 5] & 0xFF, s6 = source[at + 6] & 0xFF, s7 = source[at + 7] & 0xFF;
                plaintext[i] = (byte) ((((s0 >> 4) & 2) | (s1 & 64) | ((s2 >> 2) & 1) | ((s3 << 3) & 128)
                        | ((s4 >> 1) & 4) | ((s5 << 3) & 16) | ((s6 << 5) & 32) | ((s7 >> 4) & 8)) & 0xFF);
            }
            plaintext[31] = 1;
            byte[] first = block(xor(Arrays4.slice(plaintext, 0, 16), iv));
            byte[] key = concat(first, block(xor(Arrays4.slice(plaintext, 16, 32), first)));
            byte[] out = source.clone();
            for (int i = 0; i < 31; i++) {
                int at = i * 8, k = key[i] & 0xFF;
                out[at] = (byte) ((out[at] & 0xDF) | ((k << 4) & 32));
                out[at + 1] = (byte) ((out[at + 1] & 0xBF) | (k & 64));
                out[at + 2] = (byte) ((out[at + 2] & 0xFB) | ((k << 2) & 4));
                out[at + 3] = (byte) ((out[at + 3] & 0xEF) | ((k >> 3) & 16));
                out[at + 4] = (byte) ((out[at + 4] & 0xF7) | ((k + k) & 8));
                out[at + 5] = (byte) ((out[at + 5] & 0xFD) | ((k >> 3) & 2));
                out[at + 6] = (byte) ((out[at + 6] & 0xFE) | ((k >> 5) & 1));
                out[at + 7] = (byte) ((out[at + 7] & 0x7F) | ((k << 4) & 128));
            }
            return concat(new byte[]{key[key.length - 1]}, out);
        }
    }

    static String medusa(String url, byte[] body, int ts, String deviceId, String version, long clockMs, Random random) {
        byte[] bodyMd5 = body != null ? md5(body) : new byte[16];
        int q = url.indexOf('?');
        byte[] query = q >= 0 ? url.substring(q + 1).getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] querySm3 = sm3(query);
        byte[] queryBodyTs = hashF13(querySm3, bodyMd5, ts);
        byte[] nested = new Proto().sint(1, 111).sint(2, 10).sint(3, 694367).sint(5, 586952199).get();
        long messageRand = (long) (random.next() * 4294967296.0) & 0xFFFFFFFFL;
        int envLaunch = (int) (random.next() * 21) + 100;
        int envPid = (int) (random.next() * 2000) + 10001;
        byte[] env = new Proto().sint(1, envLaunch).sint(2, 146331399).sint(3, 146331396)
                .sint(5, 7).str(6, "v04.06.04.03-bugfix").sint(7, envPid)
                .bytes(12, deviceProto(deviceId))
                .bytes(13, new Proto().sint(1, clockMs / 1000).sint(2, -2).sint(4, 200).get())
                .str(14, version).get();
        byte[] queryHash = sm3(concat(query, bodyMd5, "none".getBytes(StandardCharsets.US_ASCII)));
        byte[] message = new Proto()
                .bytes(1, unhex("f7e85ffad7d7dc3bd62ac87057cf6118"))
                .sint(2, 3).sint(3, messageRand).str(4, "8662").str(5, deviceId)
                .str(6, "1588093228").str(7, version).str(8, "v04.06.04-ml-android")
                .sint(9, 67503104).bytes(10, unhex("4001000000000000")).sint(12, ts)
                .bytes(13, queryBodyTs).bytes(14, Arrays4.head(querySm3, 6)).bytes(15, nested)
                .str(16, "AXYQOS6n2m60x1fVZHIrH3iol").sint(17, ts).bytes(19, queryHash)
                .str(20, "none").sint(21, 312).bytes(23, env)
                .str(24, "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0,"
                        + "\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}").get();
        long r = (long) (random.next() * 4294967296.0) & 0xFFFFFFFFL;
        byte[] signKey = unhex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5");
        byte[] h = sm3(concat(signKey, le32((int) r), signKey));
        int d1 = (int) ((r >> 16) & 255);
        byte[] seed = le32(~((((d1 << 11) | (int) (r >>> 24)) ^ (d1 >> 5) ^ d1)));
        byte[] transformed = concat(unhex("4001000000000000"), xmxor(message, h));
        for (int i = 0, j = transformed.length - 1; i < j; i++, j--) { byte t = transformed[i]; transformed[i] = transformed[j]; transformed[j] = t; }
        for (int i = 0; i < transformed.length; i++) transformed[i] ^= seed[(~i) & 3];
        int check = ((querySm3[0] & 63) << 14) | 0x18000001 | ((queryBodyTs[0] & 63) << 8);
        long packedRand = (long) (random.next() * 4294967296.0) & 0xFFFFFFFFL;
        byte[] packed = concat(new byte[]{0x35}, le32((int) packedRand), le32(check), transformed,
                new byte[]{(byte) ((r >> 16) & 255), (byte) ((r >> 24) & 255)});
        byte[] encrypted = new AesV3(unhex("f1593376766ea98d34f31b057a9d5be4"), ts).encrypt(packed, unhex("1fe109a4125283f418de9e051a969e12"));
        byte[] versionBytes = unhex("03000000f7e85ffad7d7dc3bd62ac87057cf6118");
        byte[] prefix = new byte[20];
        for (int i = 0; i < 20; i += 4) {
            int w = (versionBytes[i] & 0xFF) | ((versionBytes[i + 1] & 0xFF) << 8) | ((versionBytes[i + 2] & 0xFF) << 16) | ((versionBytes[i + 3] & 0xFF) << 24);
            System.arraycopy(le32(w ^ ts), 0, prefix, i, 4);
        }
        return b64encode(concat(prefix, new byte[]{(byte) (r & 255), (byte) ((r >> 8) & 255), 0, 1}, encrypted));
    }

    // ------------------------------------------------------------ SM3

    private static final int[] SM3_IV = {0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600, 0xA96F30BC, 0x163138AA, 0xE38DEE4D, 0xB0FB0E4E};

    public static byte[] sm3(byte[] data) {
        long bitLen = (long) data.length * 8;
        int padded = ((data.length + 9 + 63) / 64) * 64;
        byte[] msg = new byte[padded];
        System.arraycopy(data, 0, msg, 0, data.length);
        msg[data.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) msg[padded - 1 - i] = (byte) (bitLen >>> (8 * i));
        int[] v = SM3_IV.clone();
        int[] w = new int[68], w1 = new int[64];
        for (int off = 0; off < padded; off += 64) {
            for (int i = 0; i < 16; i++)
                w[i] = ((msg[off + i * 4] & 0xFF) << 24) | ((msg[off + i * 4 + 1] & 0xFF) << 16) | ((msg[off + i * 4 + 2] & 0xFF) << 8) | (msg[off + i * 4 + 3] & 0xFF);
            for (int j = 16; j < 68; j++)
                w[j] = p1(w[j - 16] ^ w[j - 9] ^ Integer.rotateLeft(w[j - 3], 15)) ^ Integer.rotateLeft(w[j - 13], 7) ^ w[j - 6];
            for (int j = 0; j < 64; j++) w1[j] = w[j] ^ w[j + 4];
            int a = v[0], b = v[1], c = v[2], d = v[3], e = v[4], f = v[5], g = v[6], h = v[7];
            for (int j = 0; j < 64; j++) {
                int t = j < 16 ? 0x79CC4519 : 0x7A879D8A;
                int ss1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + e + Integer.rotateLeft(t, j % 32), 7);
                int ss2 = ss1 ^ Integer.rotateLeft(a, 12);
                int ff = j < 16 ? (a ^ b ^ c) : ((a & b) | (a & c) | (b & c));
                int gg = j < 16 ? (e ^ f ^ g) : ((e & f) | (~e & g));
                int tt1 = ff + d + ss2 + w1[j];
                int tt2 = gg + h + ss1 + w[j];
                d = c; c = Integer.rotateLeft(b, 9); b = a; a = tt1;
                h = g; g = Integer.rotateLeft(f, 19); f = e; e = p0(tt2);
            }
            v[0] ^= a; v[1] ^= b; v[2] ^= c; v[3] ^= d; v[4] ^= e; v[5] ^= f; v[6] ^= g; v[7] ^= h;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) System.arraycopy(be32(v[i]), 0, out, i * 4, 4);
        return out;
    }
    private static int p0(int x) { return x ^ Integer.rotateLeft(x, 9) ^ Integer.rotateLeft(x, 17); }
    private static int p1(int x) { return x ^ Integer.rotateLeft(x, 15) ^ Integer.rotateLeft(x, 23); }

    // ------------------------------------------------------------ 字节工具

    public static byte[] md5(byte[] data) {
        try { return MessageDigest.getInstance("MD5").digest(data); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static byte[] le32(int n) { return new byte[]{(byte) n, (byte) (n >>> 8), (byte) (n >>> 16), (byte) (n >>> 24)}; }
    static byte[] be32(int n) { return new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n}; }
    private static long le64(byte[] b, int at) { long v = 0; for (int i = 7; i >= 0; i--) v = (v << 8) | (b[at + i] & 0xFF); return v; }
    private static byte[] le64bytes(long v) { byte[] b = new byte[8]; for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i)); return b; }
    private static byte[] xor(byte[] a, byte[] b) { byte[] o = new byte[a.length]; for (int i = 0; i < a.length; i++) o[i] = (byte) (a[i] ^ b[i % b.length]); return o; }
    static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n]; int at = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, at, p.length); at += p.length; }
        return out;
    }
    public static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x & 0xFF)); return sb.toString(); }
    static byte[] unhex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16); return b; }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    public static String b64encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF, b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0, b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(B64[b0 >> 2]).append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < data.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < data.length ? B64[b2 & 63] : '=');
        }
        return sb.toString();
    }
    public static byte[] b64decode(String s) {
        int[] map = new int[128]; java.util.Arrays.fill(map, -1);
        for (int i = 0; i < 64; i++) map[B64[i]] = i;
        map['-'] = 62; map['_'] = 63;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c >= 128 || map[c] < 0) continue;
            buf = (buf << 6) | map[c]; bits += 6;
            if (bits >= 8) { bits -= 8; out.write((buf >> bits) & 0xFF); }
        }
        return out.toByteArray();
    }

    private static final class Arrays4 {
        static byte[] head(byte[] b, int n) { return slice(b, 0, n); }
        static byte[] slice(byte[] b, int from, int to) { byte[] o = new byte[to - from]; System.arraycopy(b, from, o, 0, o.length); return o; }
    }
}
