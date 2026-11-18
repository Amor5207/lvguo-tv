package ink.pengfei.lvguo.net.hgsign;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 设备注册 + video_model 取流（纯 Java，签名用 HgSign）。移植自 aston314/hongguo-tv。 */
public final class HgPlayback {
    private HgPlayback() {}
    static final SecureRandom RND = new SecureRandom();

    // ---- Go url.Values.Encode 等价实现（键排序 + QueryEscape）----
    static String goEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte bb : s.getBytes(StandardCharsets.UTF_8)) {
            int c = bb & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') sb.append((char) c);
            else if (c == ' ') sb.append('+');
            else sb.append('%').append(String.format("%02X", c));
        }
        return sb.toString();
    }
    static String encode(Map<String, String> q) {
        List<String> keys = new ArrayList<>(q.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append('&');
            sb.append(goEscape(k)).append('=').append(goEscape(q.get(k)));
        }
        return sb.toString();
    }

    static byte[] md5(byte[] d) { try { return MessageDigest.getInstance("MD5").digest(d); } catch (Exception e) { throw new RuntimeException(e); } }
    static byte[] sha512(byte[] d) { try { return MessageDigest.getInstance("SHA-512").digest(d); } catch (Exception e) { throw new RuntimeException(e); } }
    static int randU32() { return RND.nextInt(); }

    // ---- 分支选择 + 签名头 ----
    static final class Signed { String rawQuery; Map<String, String> headers = new LinkedHashMap<>(); }

    static Signed signRequest(Map<String, String> q, byte[] body, long stamp, String deviceID, String version) {
        String raw = null; HgSign.Branch br = null;
        for (int i = 0; i < 256; i++) {
            q.put("_rticket", Long.toString(stamp + i));
            raw = encode(q);
            br = HgSign.branch(raw, body, (int) (stamp / 1000));
            if (((br.iv & 15) % 3 + 3) % 3 == 2) break; // (iv&15)%3==2
            br = null;
        }
        if (br == null) throw new RuntimeException("无法选择签名分支");
        int ts = (int) (stamp / 1000);
        int n0 = randU32(), n1 = randU32(), n2 = randU32(), n3 = randU32();
        String medusa = HgSign.medusa(raw, ts, n0, n1, n2, deviceID, br.iv, br.q, br.m, version);
        Signed s = new Signed();
        s.rawQuery = raw;
        s.headers.put("X-Khronos", Long.toString(ts & 0xFFFFFFFFL));
        s.headers.put("X-SS-Req-Ticket", q.get("_rticket"));
        s.headers.put("X-Helios", HgSign.helios(ts, n3));
        s.headers.put("X-Medusa", medusa);
        s.headers.put("X-Gorgon", HgSign.gorgon(raw, body, ts, n0 & 0xFFFF));
        if (body != null && body.length > 0) s.headers.put("X-SS-STUB", HgSign.toHex(md5(body)).toUpperCase(Locale.ROOT));
        return s;
    }

    // 播放请求签名：在 passport 签名基础上加时间戳信封 + store-region 头
    static Signed signPlayback(Map<String, String> q, byte[] body, long stamp, String deviceID) {
        q.put("ts", Long.toString(stamp / 1000));
        Signed s = signRequest(q, body, stamp, deviceID, q.get("version_name"));
        int ts = (int) (stamp / 1000);
        // 取流用真实的 Argus / Ladon；不带账号档的 Medusa（带上会返回空响应）。同上游 aston314/hongguo-tv。
        String aid = q.get("aid") == null || q.get("aid").isEmpty() ? "1967" : q.get("aid");
        try {
            s.headers.put("X-Argus", HgPlaySign.argus(s.rawQuery, q, body, ts, randU32()));
            s.headers.put("X-Ladon", HgPlaySign.ladon(ts, aid, randU32()));
        } catch (Exception e) { throw new RuntimeException("取流签名失败", e); }
        s.headers.remove("X-Medusa");
        s.headers.put("Sdk-Version", "2");
        s.headers.put("X-Xs-From-Web", "0");
        s.headers.put("X-Tt-Dt", "");
        s.headers.put("X-Tt-Store-Region", "cn");
        s.headers.put("X-Tt-Store-Region-Src", "did");
        return s;
    }

    // ---- 设备 ----
    static final class Device {
        String cdid, clientudid, openudid; long created;
        String deviceID, installID; Map<String, String> cookies = new LinkedHashMap<>();
    }
    static String uuid() { return UUID.randomUUID().toString(); }
    static Device newDevice() {
        Device d = new Device();
        d.cdid = uuid(); d.clientudid = uuid();
        byte[] o = new byte[8]; RND.nextBytes(o); d.openudid = HgSign.toHex(o);
        d.created = System.currentTimeMillis();
        return d;
    }
    static byte[] deviceKey(byte[] seed) {
        byte[] h = sha512(seed);
        byte[] salt = HgSign.hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");
        byte[] cat = new byte[h.length + salt.length];
        System.arraycopy(h, 0, cat, 0, h.length); System.arraycopy(salt, 0, cat, h.length, salt.length);
        return sha512(cat);
    }
    static byte[] ttEncrypt(byte[] plain, byte[] seed) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bo);
        gz.write(plain); gz.close();
        byte[] z = bo.toByteArray();
        byte[] hdr = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 0};
        System.arraycopy(hdr, 0, z, 0, 10);
        byte[] hash = sha512(z);
        byte[] p = new byte[hash.length + z.length];
        System.arraycopy(hash, 0, p, 0, hash.length); System.arraycopy(z, 0, p, hash.length, z.length);
        int pad = 16 - p.length % 16;
        byte[] pp = new byte[p.length + pad];
        System.arraycopy(p, 0, pp, 0, p.length);
        for (int i = p.length; i < pp.length; i++) pp[i] = (byte) pad;
        byte[] key = deviceKey(seed);
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, 0, 16, "AES"), new IvParameterSpec(key, 16, 16));
        byte[] enc = c.doFinal(pp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x74, 0x63, 5, 0x10, 0, 0});
        out.write(seed); out.write(enc);
        return out.toByteArray();
    }
    static byte[] ttDecrypt(byte[] b) throws Exception {
        if (b.length < 38 + 80 || (b.length - 38) % 16 != 0) throw new RuntimeException("注册响应无效");
        byte[] seed = Arrays.copyOfRange(b, 6, 38);
        byte[] key = deviceKey(seed);
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, 0, 16, "AES"), new IvParameterSpec(key, 16, 16));
        byte[] p = c.doFinal(Arrays.copyOfRange(b, 38, b.length));
        int pad = p[p.length - 1] & 0xFF;
        p = Arrays.copyOfRange(p, 0, p.length - pad);
        byte[] gz = Arrays.copyOfRange(p, 64, p.length);
        GZIPInputStream gi = new GZIPInputStream(new java.io.ByteArrayInputStream(gz));
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n = gi.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // 设备注册 & 请求参数（单集播放档）
    static final String SINGLE_UA = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)";
    static final String DEFAULT_UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    static final String DEVICE_BASE = "https://log5-applog-lq.fqnovel.com";
    static final String VIDEO_ENDPOINT = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/";

    static Map<String, String> singleQuery(Device d) {
        Map<String, String> q = new LinkedHashMap<>();
        String[][] kv = {{"iid", d.installID}, {"device_id", d.deviceID}, {"ac", "wifi"}, {"channel", "xiaomi_8662_64"},
            {"aid", "8662"}, {"app_name", "novelread"}, {"cdid", d.cdid}, {"version_code", "73932"}, {"version_name", "7.3.9.32"},
            {"device_platform", "android"}, {"os", "android"}, {"ssmix", "a"}, {"device_type", "23127PN0CC"}, {"device_brand", "Xiaomi"},
            {"language", "zh"}, {"os_api", "34"}, {"os_version", "14"}, {"manifest_version_code", "73932"},
            {"resolution", "1200*2670"}, {"dpi", "460"}, {"update_version_code", "73932"}, {"host_abi", "arm64-v8a"},
            {"dragon_device_type", "phone"}, {"pv_player", "73932"}, {"compliance_status", "0"}, {"need_personal_recommend", "1"},
            {"player_so_load", "1"}, {"is_android_pad_screen", "0"}, {"openudid", d.openudid}};
        for (String[] p : kv) q.put(p[0], p[1] == null ? "" : p[1]);
        return q;
    }

    @SuppressWarnings("unchecked")
    static byte[] singleDevicePayload(Device d, long stamp) {
        // 基础 header（见 passport_device.payload）+ 单集播放覆盖
        Map<String, Object> h = new LinkedHashMap<>();
        Object[][] base = {{"aid", 8662}, {"access", "wifi"}, {"app_version", "7.3.5.32"}, {"app_version_minor", ""}, {"channel", "update_64"},
            {"device_brand", "Redmi"}, {"device_manufacturer", "Redmi"}, {"device_model", "25053RT47C"}, {"device_category", "phone"}, {"device_platform", "android"},
            {"os", "Android"}, {"os_api", 36}, {"os_version", "16"}, {"density_dpi", 520}, {"display_density", "xxxhdpi"}, {"resolution", "2772x1280"},
            {"language", "zh"}, {"timezone", 8}, {"tz_name", "Asia/Shanghai"}, {"tz_offset", 28800}, {"region", "CN"}, {"package", "com.phoenix.read"},
            {"version_code", 73532}, {"manifest_version_code", 73532}, {"update_version_code", 73532}, {"apk_first_install_time", d.created},
            {"cdid", d.cdid}, {"clientudid", d.clientudid}, {"openudid", d.openudid}, {"cpu_abi", "arm64-v8a"}, {"display_name", "红果短剧"},
            {"sdk_version", "3.7.2"}, {"sdk_target_version", 29}, {"sdk_flavor", "china"}, {"guest_mode", 0}, {"is_system_app", 0}, {"not_request_sender", 0}, {"oaid_may_support", true}};
        for (Object[] p : base) h.put((String) p[0], p[1]);
        h.put("ipv6_list", new ArrayList<>()); h.put("sim_serial_number", new ArrayList<>());
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("is_android_fold", 0); custom.put("filter_warn", 0); custom.put("web_ua", ""); custom.put("is_android_pad", 0); custom.put("client_ipv4", "");
        h.put("custom", custom);
        // 单集覆盖
        Object[][] over = {{"display_name", "红果免费短剧"}, {"app_version", "7.3.9.32"}, {"app_version_minor", "7.3.9.32"}, {"version_code", 73932},
            {"manifest_version_code", 73932}, {"update_version_code", 73932}, {"channel", "xiaomi_8662_64"}, {"device_brand", "Xiaomi"},
            {"device_manufacturer", "Xiaomi"}, {"device_model", "23127PN0CC"}, {"os_api", 34}, {"os_version", "14"}, {"density_dpi", 460},
            {"resolution", "2670x1200"}, {"device_id", "0"}, {"install_id", "0"}, {"req_id", d.clientudid}};
        for (Object[] p : over) h.put((String) p[0], p[1]);
        Map<String, Object> oaid = new LinkedHashMap<>();
        oaid.put("req_id", d.cdid); oaid.put("hw_id_version_code", "0"); oaid.put("take_ms", "6"); oaid.put("is_track_limited", "false");
        oaid.put("query_times", "1"); oaid.put("id", d.cdid); oaid.put("time", Long.toString(stamp - 200));
        h.put("oaid", oaid);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("magic_tag", "ss_app_log"); root.put("header", h); root.put("_gen_time", stamp);
        return jsonStringify(root).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static String jsonStringify(Object o) {
        StringBuilder sb = new StringBuilder();
        if (o == null) sb.append("null");
        else if (o instanceof String) { sb.append('"'); for (char ch : ((String) o).toCharArray()) {
                switch (ch) { case '"': sb.append("\\\""); break; case '\\': sb.append("\\\\"); break; case '\n': sb.append("\\n"); break; case '\r': sb.append("\\r"); break; case '\t': sb.append("\\t"); break; default: if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); } } sb.append('"'); }
        else if (o instanceof Boolean || o instanceof Number) sb.append(o.toString());
        else if (o instanceof Map) { sb.append('{'); boolean first = true; for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) { if (!first) sb.append(','); first = false; sb.append(jsonStringify(e.getKey())).append(':').append(jsonStringify(e.getValue())); } sb.append('}'); }
        else if (o instanceof List) { sb.append('['); boolean first = true; for (Object x : (List<Object>) o) { if (!first) sb.append(','); first = false; sb.append(jsonStringify(x)); } sb.append(']'); }
        else sb.append(jsonStringify(o.toString()));
        return sb.toString();
    }
    static String extract(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        if (m.find()) return m.group(1);
        return "";
    }

    static Device registerDevice() throws Exception {
        Device d = newDevice();
        long now = System.currentTimeMillis();
        long stamp = now;
        byte[] seed = new byte[32]; RND.nextBytes(seed);
        byte[] payload = singleDevicePayload(d, stamp);
        byte[] body = ttEncrypt(payload, seed);
        Map<String, String> q = singleQuery(d);
        q.remove("device_id"); q.remove("iid");
        q.put("tt_data", "a"); q.put("ts", Long.toString(now / 1000)); q.put("_rticket", Long.toString(stamp));
        q.put("package", "com.phoenix.read");
        String query = encode(q);
        HttpURLConnection c = (HttpURLConnection) new URL(DEVICE_BASE + "/service/2/device_register/?" + query).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(12000); c.setReadTimeout(12000); c.setDoOutput(true);
        c.setRequestProperty("User-Agent", SINGLE_UA);
        c.setRequestProperty("Content-Type", "application/octet-stream;tt-data=a");
        c.setRequestProperty("Accept", "application/json; charset=utf-8,application/x-protobuf");
        c.setRequestProperty("Content-Encoding", "gzip");
        c.setRequestProperty("X-Tt-Request-Tag", "t=0;n=1"); c.setRequestProperty("Log-Encode-Type", "gzip");
        c.setRequestProperty("Lc", "102"); c.setRequestProperty("Sdk-Version", "2");
        c.setRequestProperty("Passport-Sdk-Version", "60755");
        c.setRequestProperty("Passport-Sdk-Settings", "device_transfer_s_0,device_transfer_ab_1");
        c.setRequestProperty("X-SS-DP", "8662"); c.setRequestProperty("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn");
        c.setRequestProperty("X-Tt-Store-Region", "cn"); c.setRequestProperty("X-Tt-Store-Region-Src", "did");
        c.setRequestProperty("X-Khronos", Long.toString(now / 1000));
        c.setRequestProperty("X-Argus", HgSign.b64(HgSign.le32((int) (now / 1000))));
        c.setRequestProperty("X-SS-Req-Ticket", q.get("_rticket"));
        c.setRequestProperty("X-SS-STUB", HgSign.toHex(md5(body)));
        int nonce = randU32();
        c.setRequestProperty("X-Gorgon", HgSign.gorgon(query, body, (int) (now / 1000), nonce & 0xFFFF));
        OutputStream os = c.getOutputStream(); os.write(body); os.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] resp = readAll(in); c.disconnect();
        if (code != 200) throw new RuntimeException("设备注册 HTTP " + code);
        if (resp.length >= 6 && resp[0] == 0x74 && resp[1] == 0x63 && resp[2] == 5 && resp[3] == 0x10) resp = ttDecrypt(resp);
        String rs = new String(resp, StandardCharsets.UTF_8);
        String did = extract(rs, "device_id_str"); if (did.isEmpty()) did = extract(rs, "device_id");
        String iid = extract(rs, "install_id_str"); if (iid.isEmpty()) iid = extract(rs, "install_id");
        if (did.isEmpty() || iid.isEmpty() || did.equals("0") || iid.equals("0")) throw new RuntimeException("注册未返回有效身份: " + new String(resp, StandardCharsets.UTF_8).substring(0, Math.min(200, resp.length)));
        d.deviceID = did; d.installID = iid;
        // 从 Set-Cookie 提取 install_id 等（简化：直接放 install_id）
        d.cookies.put("install_id", iid);
        List<String> sc = c.getHeaderFields().get("Set-Cookie");
        if (sc != null) for (String s : sc) {
            String pair = s.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq > 0) { String name = pair.substring(0, eq).trim();
                if (name.equals("store-region") || name.equals("store-region-src") || name.equals("ttreq")) d.cookies.put(name, pair.substring(eq + 1).trim()); }
        }
        return d;
    }

    static String cookieHeader(Device d) {
        List<String> allowed = Arrays.asList("install_id", "ttreq", "store-region", "store-region-src");
        List<String> keys = new ArrayList<>();
        for (String k : d.cookies.keySet()) if (allowed.contains(k) && !d.cookies.get(k).isEmpty()) keys.add(k);
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) { if (sb.length() > 0) sb.append("; "); sb.append(k).append('=').append(d.cookies.get(k)); }
        return sb.toString();
    }

    // Narrow bridge shared with authenticated playback modules.
    private static Device moduleDevice;
    public static synchronized String moduleIdentity() throws Exception {
        if (moduleDevice == null) moduleDevice = registerDevice();
        org.json.JSONObject out = new org.json.JSONObject();
        out.put("device_id", moduleDevice.deviceID); out.put("iid", moduleDevice.installID); out.put("cdid", moduleDevice.cdid);
        return out.toString();
    }
    public static byte[] moduleSm3(byte[] data) { return HgSign.sm3(data); }

    /** 取 vid 的 video_model 原始 JSON。 */
    public static String play(String vid) throws Exception {
        Device d = registerDevice();
        byte[] body = ("{\"video_id\":\"" + vid + "\",\"biz_param\":{\"video_id_type\":0,\"need_all_video_definition\":true,\"video_platform\":3}}").getBytes(StandardCharsets.UTF_8);
        long stamp = System.currentTimeMillis();
        Map<String, String> q = singleQuery(d);
        Signed s = signPlayback(q, body, stamp, d.deviceID);
        HttpURLConnection c = (HttpURLConnection) new URL(VIDEO_ENDPOINT + "?" + s.rawQuery).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(12000); c.setReadTimeout(12000); c.setDoOutput(true);
        c.setInstanceFollowRedirects(false);
        for (Map.Entry<String, String> e : s.headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setRequestProperty("User-Agent", SINGLE_UA);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json; charset=utf-8,application/x-protobuf");
        c.setRequestProperty("Passport-Sdk-Version", "50561");
        c.setRequestProperty("X-Vc-Bdturing-Sdk-Version", "3.7.2.cn");
        c.setRequestProperty("X-Tt-Request-Tag", "t=0;n=0");
        c.setRequestProperty("X-SS-DP", "8662"); c.setRequestProperty("Lc", "101");
        c.setRequestProperty("Cookie", cookieHeader(d));
        c.setRequestProperty("X-Reading-Request", stamp + "-" + (randU32() & 0x7fffffff));
        OutputStream os = c.getOutputStream(); os.write(body); os.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] resp = readAll(in); c.disconnect();
        if (code < 200 || code >= 300) throw new RuntimeException("video_model HTTP " + code);
        if (resp.length == 0) throw new RuntimeException("video_model 空响应");
        return new String(resp, StandardCharsets.UTF_8);
    }

    static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } finally { try { in.close(); } catch (Exception ignored) {} }
    }
    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

}
