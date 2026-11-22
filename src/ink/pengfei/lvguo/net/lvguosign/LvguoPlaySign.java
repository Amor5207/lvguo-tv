package ink.pengfei.lvguo.net.lvguosign;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 取流请求专用的 X-Argus / X-Ladon（移植自 aston314/hongguo-tv 的 playback_sign.go）。
 * 2026-11 起服务端不再接受「时间戳占位」的这两个头，缺少真实值时 video_model 返回空响应。
 */
final class LvguoPlaySign {
    private LvguoPlaySign() {}
    private static final String LICENSE_ID = "1611921764";

    private static long le64(byte[] d, int at) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (d[at + i] & 0xFFL);
        return v;
    }
    private static void putLe64(byte[] d, int at, long v) {
        for (int i = 0; i < 8; i++) { d[at + i] = (byte) v; v >>>= 8; }
    }
    private static byte[] pad(byte[] data) {
        int n = 16 - data.length % 16;
        byte[] out = new byte[data.length + n];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = data.length; i < out.length; i++) out[i] = (byte) n;
        return out;
    }
    private static void varint(LvguoSign.Proto p, int field, long u32) {
        p.uvarint((long) field << 3);
        p.uvarint(u32 & 0xFFFFFFFFL);
    }
    /** 与 Proto.text 不同：空值也写出（长度 0），和上游逐字节一致。 */
    private static void text(LvguoSign.Proto p, int field, String value) {
        byte[] b = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        p.uvarint((long) (field << 3 | 2));
        p.uvarint(b.length);
        p.b.write(b, 0, b.length);
    }
    private static void rawBlob(LvguoSign.Proto p, int field, byte[] d) {
        p.uvarint((long) (field << 3 | 2));
        p.uvarint(d.length);
        p.b.write(d, 0, d.length);
    }

    private static long[] simonKeys() {
        long[] keys = new long[72];
        byte[] raw = LvguoSign.hex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304");
        for (int i = 0; i < 4; i++) keys[i] = le64(raw, i * 8);
        final long sequence = 0x3dc94c3a046d678bL;
        for (int i = 4; i < keys.length; i++) {
            long x = Long.rotateRight(keys[i - 1], 3) ^ keys[i - 3];
            keys[i] = ~keys[i - 4] ^ x ^ Long.rotateRight(x, 1) ^ ((sequence >>> ((i - 4) % 62)) & 1L) ^ 3L;
        }
        return keys;
    }

    static String argus(String query, Map<String, String> q, byte[] body, int ts, int nonce) throws Exception {
        String aid = q.get("aid") == null || q.get("aid").isEmpty() ? "1967" : q.get("aid");
        LvguoSign.Proto p = new LvguoSign.Proto(), counters = new LvguoSign.Proto(), device = new LvguoSign.Proto();
        varint(p, 1, 1077940818L); varint(p, 2, 2); varint(p, 3, nonce);
        text(p, 4, aid);
        text(p, 5, q.get("device_id"));
        text(p, 6, LICENSE_ID);
        text(p, 7, q.get("version_name"));
        text(p, 8, "v04.04.05-ov-android");
        varint(p, 9, 134744640L);
        rawBlob(p, 10, new byte[8]);
        varint(p, 11, 0);
        varint(p, 12, ((long) ts << 1));
        byte[] m = body != null && body.length > 0 ? LvguoSign.md5(body) : new byte[16];
        byte[] bodyHash = LvguoSign.sm3(m);
        byte[] queryBytes = query.isEmpty() ? new byte[16] : query.getBytes(StandardCharsets.UTF_8);
        byte[] queryHash = LvguoSign.sm3(queryBytes);
        rawBlob(p, 13, java.util.Arrays.copyOf(bodyHash, 6));
        rawBlob(p, 14, java.util.Arrays.copyOf(queryHash, 6));
        varint(counters, 1, 1); varint(counters, 2, 1); varint(counters, 3, 1); varint(counters, 7, 0xc792ecccL);
        rawBlob(p, 15, counters.bytes());
        text(p, 16, q.get("iid"));
        text(p, 20, "none");
        varint(p, 21, 738);
        text(device, 1, "NX551J");
        varint(device, 2, 8196);
        varint(device, 4, 0x80e0d800L);
        rawBlob(p, 23, device.bytes());
        varint(p, 25, 2);

        byte[] plain = pad(p.bytes());
        long[] keys = simonKeys();
        for (int at = 0; at < plain.length; at += 16) {
            long a = le64(plain, at), b = le64(plain, at + 8);
            for (long key : keys) {
                long na = b;
                long nb = a ^ (Long.rotateLeft(b, 1) & Long.rotateLeft(b, 8)) ^ Long.rotateLeft(b, 2) ^ key;
                a = na; b = nb;
            }
            putLe64(plain, at, a); putLe64(plain, at + 8, b);
        }
        byte[] mask = {(byte) 0xf2, (byte) 0xf7, (byte) 0xfc, (byte) 0xff, (byte) 0xf2, (byte) 0xf7, (byte) 0xfc, (byte) 0xff};
        byte[] tail = {(byte) 0xea, (byte) 0xfb, 0x2c, (byte) 0xfe, (byte) 0x85, 0x68, 0x51, (byte) 0x91, 0x54};
        byte[] frame = new byte[mask.length + plain.length + tail.length];
        System.arraycopy(mask, 0, frame, 0, mask.length);
        System.arraycopy(plain, 0, frame, mask.length, plain.length);
        System.arraycopy(tail, 0, frame, mask.length + plain.length, tail.length);
        for (int i = mask.length; i < frame.length; i++) frame[i] ^= mask[i % mask.length];
        for (int i = 0, j = frame.length - 1; i < j; i++, j--) { byte t = frame[i]; frame[i] = frame[j]; frame[j] = t; }
        byte[] ext = java.util.Arrays.copyOf(frame, frame.length + 2);
        ext[frame.length] = 0x61; ext[frame.length + 1] = 0x6f;
        byte[] padded = pad(ext);
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(LvguoSign.hex("d31e3718288a1027baab59f146a09a9c"), "AES"),
                new IvParameterSpec(LvguoSign.hex("ea180a0336ed352fcd24e4d50018ae54")));
        byte[] enc = c.doFinal(padded);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xf2); out.write(0x81); out.write(enc, 0, enc.length);
        return LvguoSign.b64(out.toByteArray());
    }

    static String ladon(int ts, String aid, int nonce) throws Exception {
        byte[] seed = LvguoSign.le32(nonce);
        byte[] aidBytes = aid.getBytes(StandardCharsets.UTF_8);
        byte[] in = new byte[4 + aidBytes.length];
        System.arraycopy(seed, 0, in, 0, 4); System.arraycopy(aidBytes, 0, in, 4, aidBytes.length);
        byte[] hexKey = LvguoSign.toHex(LvguoSign.md5(in)).getBytes(StandardCharsets.US_ASCII);
        long[] keys = new long[34];
        long k = le64(hexKey, 0);
        long p0 = le64(hexKey, 8), p1 = le64(hexKey, 16), p2 = le64(hexKey, 24);
        for (int i = 0; i < keys.length; i++) {
            keys[i] = k;
            long x = (Long.rotateRight(p0, 8) + k) ^ (long) i;
            p0 = p1; p1 = p2; p2 = x;
            k = x ^ Long.rotateLeft(k, 3);
        }
        byte[] plain = pad((Long.toString(ts & 0xFFFFFFFFL) + "-" + LICENSE_ID + "-" + aid).getBytes(StandardCharsets.UTF_8));
        for (int at = 0; at < plain.length; at += 16) {
            long a = le64(plain, at), b = le64(plain, at + 8);
            for (long key : keys) {
                b = key ^ (a + Long.rotateRight(b, 8));
                a = b ^ Long.rotateLeft(a, 3);
            }
            putLe64(plain, at, a); putLe64(plain, at + 8, b);
        }
        byte[] out = new byte[4 + plain.length];
        System.arraycopy(seed, 0, out, 0, 4); System.arraycopy(plain, 0, out, 4, plain.length);
        return LvguoSign.b64(out);
    }
}
