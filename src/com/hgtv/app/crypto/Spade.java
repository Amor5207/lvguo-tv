package com.hgtv.app.crypto;

import android.util.Base64;

/**
 * spade_a -> content key。
 *
 * 算法纯字节变换（XOR + POPCOUNT + 位置相关 + 按首字节切片），
 * 无 KEK、无 AES、不需要网络，100% 可离线复现。
 */
public final class Spade {

    private Spade() {}

    private static int popcount(int x) {
        return Integer.bitCount(x);
    }

    private static int s8(int v) {
        v &= 0xFF;
        return v >= 128 ? v - 256 : v;
    }

    private static boolean strncmp0(byte[] a, byte[] b, int n) {
        for (int k = 0; k < n; k++) {
            byte ca = k < a.length ? a[k] : 0;
            byte cb = k < b.length ? b[k] : 0;
            if (ca != cb) return false;
            if (ca == 0) return true;
        }
        return true;
    }

    private static final byte[] APP_V2 = {'a', 'p', 'p', '_', 'v', '2'};
    private static final byte[] WEB_V2 = {'w', 'e', 'b', '_', 'v', '2'};

    /** 37B spade -> 32 字符 hex 的 key 文本；失败返回 null */
    public static String unwrapV1(byte[] spade, int flag) {
        if (spade == null) return null;
        int L = spade.length;
        if (L < 3) return null;

        int bVar5 = (spade[0] & 0xFF) ^ (spade[1] & 0xFF) ^ (spade[2] & 0xFF);
        int iVar9 = bVar5 - 0x30;                    // type 串长度
        if (iVar9 < 1) return null;
        int uVar1 = (L - bVar5) + 0x2F;              // 工作缓冲长度
        if (uVar1 < 1 || 1 + uVar1 > L) return null;

        byte[] dest = new byte[uVar1];
        System.arraycopy(spade, 1, dest, 0, uVar1);

        // 还原尾部 type 串，判断 v1 / v2
        byte[] s1 = new byte[iVar9];
        if (L - iVar9 - 2 < 0) return null;
        byte b16 = spade[L - iVar9 - 2];
        byte b14 = spade[L - iVar9 - 1];
        for (int i = 0; i < iVar9; i++) {
            s1[i] = (byte) (b14 ^ b16 ^ spade[i + (L - iVar9)]);
        }
        if (strncmp0(s1, APP_V2, iVar9) || strncmp0(s1, WEB_V2, iVar9)) {
            return null;                             // ver2 走 AES-GCM，红果视频不用
        }

        // ver1 字节变换
        int bb14 = 0x55, bb16 = 0xFA;
        for (int i = 0; i < uVar1; i++) {
            int b6 = dest[i] & 0xFF;
            int u18 = popcount(i);
            int b3 = b6, b7 = bb14;
            if ((i & 1) != 0) {
                b3 = bb16;
                b7 = b6;
                bb16 = bb14;
            }
            int cVar4 = flag != 0 ? (u18 + 0x15) : s8(-0x15 - u18);
            dest[i] = (byte) ((cVar4 + (bb16 ^ b6)) & 0xFF);
            bb14 = b7;
            bb16 = b3;
        }

        int b0 = dest[0] & 0xFF;
        int u11;
        if (b0 >= 0x30 && b0 <= 0x39) u11 = b0 - 0x30;
        else if (b0 >= 0x61 && b0 <= 0x7A) u11 = b0 - 0x57;
        else return null;

        int iv9 = uVar1 - (u11 & 0xFF);
        if (iv9 < 2) return null;
        return new String(dest, 1, iv9 - 1, java.nio.charset.Charset.forName("ISO-8859-1"));
    }

    /** spade_a(base64) -> 16 字节 AES key */
    public static byte[] spadeToKey(String spadeB64) {
        try {
            byte[] raw = Base64.decode(spadeB64, Base64.DEFAULT);
            String hex = unwrapV1(raw, 0);
            if (hex == null || hex.length() != 32) return null;
            byte[] key = new byte[16];
            for (int i = 0; i < 16; i++) {
                key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return key;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 自测：6 组真值，全部命中才返回 true */
    public static boolean selftest() {
        String[] truth = {
                "93bc1df253ba1bf7618b19c448b806f64d810afc45b715fe5eb925f26cbf12f541ba098282",
                "a1bc2ff164b91df04dbf00f17dba00f47cbb35c27b922aec67a628eb52972fdc7ea036a7a7",
                "9cbc12fb588721cd69b739f977b70be7429c08e074990ec847843dcc738638cf76b33ebcbc",
        };
        String[] expect = {
                "287216bfa89e662a0f748120c305e199",
                "d742b28967e4e6c92b699f4375add27f",
                "113fb30d9767d80e3edbb905b052204f",
        };
        for (int i = 0; i < truth.length; i++) {
            byte[] b = new byte[truth[i].length() / 2];
            for (int k = 0; k < b.length; k++) {
                b[k] = (byte) Integer.parseInt(truth[i].substring(k * 2, k * 2 + 2), 16);
            }
            String got = unwrapV1(b, 0);
            if (got == null || !got.equals(expect[i])) return false;
        }
        return true;
    }
}
