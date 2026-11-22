package ink.pengfei.lvguo.net.lvguosign;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 手机号验证码登录（纯 Java，移植自 aston314/hongguo-tv 的 passport 流程）。
 * 复用 LvguoSign（Medusa/Gorgon/Helios）与 LvguoPlayback 的设备注册/签名底层。
 * 仅支持 +86（国内手机号）；成功后返回会话 cookie，交给 Session.begin 建立登录态。
 * 注意：短信下发/验证需真实手机号，服务器侧风控存在，实际可用性以手机实测为准。
 */
public final class LvguoAccount {
    private static final String ACCOUNT_API = "https://api5-normal-lq.fqnovel.com";
    private static final String ACCOUNT_DEVICE_BASE = "https://log.snssdk.com";
    private static final String VERSION = "7.3.5.32";

    private LvguoPlayback.Device device;

    /** 本次登录所用设备：{device_id, install_id, cdid, openudid}；登录后的账号写请求要沿用这一台。 */
    public String[] deviceIds() {
        LvguoPlayback.Device d = device;
        if (d == null || d.deviceID == null || d.installID == null) return null;
        return new String[]{d.deviceID, d.installID, d.cdid == null ? "" : d.cdid, d.openudid == null ? "" : d.openudid};
    }
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String token = "";
    public String ttToken() { return token; }
    private String smsCodeKey = "";

    /** 把用户输入归一化成 +86 开头的 11 位号码（E.164）。 */
    public static String normalizeCN(String raw) {
        if (raw == null) return "";
        StringBuilder d = new StringBuilder();
        for (char c : raw.toCharArray()) if (c >= '0' && c <= '9') d.append(c);
        String s = d.toString();
        if (s.startsWith("86") && s.length() == 13) s = s.substring(2);
        if (s.startsWith("0") && s.length() == 12) s = s.substring(1);
        return "+86" + s; // 期望 s 为 11 位
    }
    public static boolean validCN(String raw) {
        String n = normalizeCN(raw);
        return n.length() == 14 && n.charAt(3) == '1'; // +86 + 1xxxxxxxxxx
    }

    private static String xor5(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", (x ^ 5) & 0xFF));
        return sb.toString();
    }

    // 账号基础设备档（版本 7.3.5.32），与 passport 签名一致。
    private Map<String, String> baseQuery(LvguoPlayback.Device d) {
        Map<String, String> q = new LinkedHashMap<>();
        String[][] kv = {{"ac", "wifi"}, {"aid", "8662"}, {"app_name", "novelread"}, {"channel", "update_64"},
            {"device_brand", "Redmi"}, {"device_id", d.deviceID}, {"device_platform", "android"}, {"device_type", "25053RT47C"},
            {"dpi", "520"}, {"iid", d.installID}, {"language", "zh"}, {"manifest_version_code", "73532"}, {"os", "android"},
            {"os_api", "36"}, {"os_version", "16"}, {"resolution", "1280*2772"}, {"ssmix", "a"}, {"update_version_code", "73532"},
            {"version_code", "73532"}, {"version_name", VERSION}, {"cdid", d.cdid}, {"openudid", d.openudid}};
        for (String[] p : kv) q.put(p[0], p[1] == null ? "" : p[1]);
        return q;
    }

    private byte[] basePayload(LvguoPlayback.Device d, long stamp) {
        Map<String, Object> h = new LinkedHashMap<>();
        Object[][] base = {{"aid", 8662}, {"access", "wifi"}, {"app_version", VERSION}, {"app_version_minor", ""}, {"channel", "update_64"},
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
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("magic_tag", "ss_app_log"); root.put("header", h); root.put("_gen_time", stamp);
        return LvguoPlayback.jsonStringify(root).getBytes(StandardCharsets.UTF_8);
    }

    private final Object deviceLock = new Object();

    public void warmDevice() {
        try { ensureDevice(); } catch (Exception ignored) {}
    }

    private void ensureDevice() throws Exception {
        if (device != null && device.deviceID != null) return;
        synchronized (deviceLock) {
            if (device != null && device.deviceID != null) return;
            LvguoPlayback.Device d = LvguoPlayback.newDevice();
            long now = System.currentTimeMillis();
            byte[] seed = new byte[32]; LvguoPlayback.RND.nextBytes(seed);
            byte[] body = LvguoPlayback.ttEncrypt(basePayload(d, now), seed);
            Map<String, String> q = baseQuery(d);
            q.remove("device_id"); q.remove("iid");
            q.put("tt_data", "a"); q.put("ts", Long.toString(now / 1000)); q.put("_rticket", Long.toString(now));
            q.put("package", "com.phoenix.read");
            String query = LvguoPlayback.encode(q);
            HttpURLConnection c = (HttpURLConnection) new URL(ACCOUNT_DEVICE_BASE + "/service/2/device_register/?" + query).openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(12000); c.setReadTimeout(12000); c.setDoOutput(true);
            c.setRequestProperty("User-Agent", LvguoPlayback.DEFAULT_UA);
            c.setRequestProperty("Content-Type", "application/octet-stream;tt-data=a");
            c.setRequestProperty("Accept", "application/json; charset=utf-8,application/x-protobuf");
            c.setRequestProperty("Content-Encoding", "gzip");
            c.setRequestProperty("X-Tt-Request-Tag", "t=0;n=1");
            c.setRequestProperty("Log-Encode-Type", "gzip"); c.setRequestProperty("Lc", "102"); c.setRequestProperty("Sdk-Version", "2");
            c.setRequestProperty("Passport-Sdk-Version", "60755");
            c.setRequestProperty("Passport-Sdk-Settings", "device_transfer_s_0,device_transfer_ab_1");
            c.setRequestProperty("X-SS-DP", "8662");
            c.setRequestProperty("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn");
            c.setRequestProperty("X-Tt-Store-Region", "cn");
            c.setRequestProperty("X-Tt-Store-Region-Src", "did");
            c.setRequestProperty("X-Khronos", Long.toString(now / 1000));
            c.setRequestProperty("X-Argus", LvguoSign.b64(LvguoSign.le32((int) (now / 1000))));
            c.setRequestProperty("X-SS-Req-Ticket", q.get("_rticket"));
            c.setRequestProperty("X-SS-STUB", LvguoSign.toHex(LvguoPlayback.md5(body)));
            c.setRequestProperty("X-Gorgon", LvguoSign.gorgon(query, body, (int) (now / 1000), LvguoPlayback.randU32() & 0xFFFF));
            OutputStream os = c.getOutputStream(); os.write(body); os.close();
            int code = c.getResponseCode();
            List<String> sc = c.getHeaderFields().get("Set-Cookie");
            byte[] resp = LvguoPlayback.readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) { c.disconnect(); throw new Exception("设备注册 HTTP " + code); }
            if (resp.length >= 6 && resp[0] == 0x74 && resp[1] == 0x63 && resp[2] == 5 && resp[3] == 0x10) resp = LvguoPlayback.ttDecrypt(resp);
            String rs = new String(resp, StandardCharsets.UTF_8);
            String did = LvguoPlayback.extract(rs, "device_id_str"); if (did.isEmpty()) did = LvguoPlayback.extract(rs, "device_id");
            String iid = LvguoPlayback.extract(rs, "install_id_str"); if (iid.isEmpty()) iid = LvguoPlayback.extract(rs, "install_id");
            if (did.isEmpty() || iid.isEmpty() || did.equals("0") || iid.equals("0")) throw new Exception("设备注册未返回有效身份");
            d.deviceID = did; d.installID = iid;
            if (sc != null) for (String line : sc) {
                String pair = line.split(";", 2)[0]; int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq).trim(), val = pair.substring(eq + 1).trim();
                if (("store-region".equals(name) || "store-region-src".equals(name) || "ttreq".equals(name) || "odin_tt".equals(name))
                        && !val.isEmpty() && !val.equalsIgnoreCase("deleted")) cookies.put(name, val);
            }
            cookies.put("install_id", iid);
            device = d;
        }
    }

    // 账号 passport 请求（form-urlencoded，带全套签名 + 会话 cookie）。
    private Map<String, Object> accountPost(String path, Map<String, String> form) throws Exception {
        ensureDevice();
        StringBuilder fb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) { if (fb.length() > 0) fb.append('&'); fb.append(LvguoPlayback.goEscape(e.getKey())).append('=').append(LvguoPlayback.goEscape(e.getValue())); }
        byte[] body = fb.toString().getBytes(StandardCharsets.UTF_8);
        long stamp = System.currentTimeMillis();
        Map<String, String> q = baseQuery(device);
        LvguoPlayback.Signed s = LvguoPlayback.signRequest(q, body, stamp, device.deviceID, VERSION);
        HttpURLConnection c = (HttpURLConnection) new URL(ACCOUNT_API + path + "?" + s.rawQuery).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(12000); c.setReadTimeout(12000); c.setDoOutput(true);
        c.setInstanceFollowRedirects(false);
        for (Map.Entry<String, String> e : s.headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setRequestProperty("User-Agent", LvguoPlayback.DEFAULT_UA);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-XS-From-Web", "0"); c.setRequestProperty("Sdk-Version", "2");
        c.setRequestProperty("Passport-Sdk-Version", "5051452"); c.setRequestProperty("X-SS-DP", "8662");
        c.setRequestProperty("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn"); c.setRequestProperty("Lc", "101");
        c.setRequestProperty("X-Tt-Store-Region", cookies.getOrDefault("store-region", "cn"));
        c.setRequestProperty("X-Tt-Store-Region-Src", "did");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        if (!token.isEmpty()) c.setRequestProperty("X-Tt-Token", token);
        if (!cookies.isEmpty()) c.setRequestProperty("Cookie", cookieHeader());
        OutputStream os = c.getOutputStream(); os.write(body); os.close();
        int code = c.getResponseCode();
        // 收 Set-Cookie
        List<String> sc = c.getHeaderFields().get("Set-Cookie");
        if (sc != null) for (String line : sc) {
            String pair = line.split(";", 2)[0]; int eq = pair.indexOf('=');
            if (eq > 0) {
                String name = pair.substring(0, eq).trim(), val = pair.substring(eq + 1).trim();
                if (val.isEmpty() || val.equalsIgnoreCase("deleted")) cookies.remove(name);
                else cookies.put(name, val);
            }
        }
        String tk = c.getHeaderField("X-Tt-Token"); if (tk != null && !tk.isEmpty()) token = tk;
        byte[] resp = LvguoPlayback.readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code != 200) { c.disconnect(); throw new Exception("账号服务 HTTP " + code); }
        lastRaw = new String(resp, StandardCharsets.UTF_8);
        return parse(lastRaw);
    }
    private String lastRaw = "";

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) { if (sb.length() > 0) sb.append("; "); sb.append(e.getKey()).append('=').append(e.getValue()); }
        return sb.toString();
    }

    /** 发送验证码。message=="success" 视为成功。 */
    public String sendCode(String phone) throws Exception {
        String canonical = normalizeCN(phone);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("mobile", xor5(canonical));
        f.put("account_sdk_source", "app"); f.put("passport_support_flow", "captcha,verify"); f.put("mix_mode", "1");
        f.put("auto_read", "0"); f.put("unbind_exist", "34"); f.put("type", xor5("24"));
        Map<String, Object> r = accountPost("/passport/mobile/send_code/v1/", f);
        String msg = LvguoPlayback.str(r.get("message"));
        if ("success".equals(msg)) return "success";
        Map<String, Object> data = asMap(r.get("data"));
        String desc = data != null ? LvguoPlayback.str(data.get("description")) : "";
        throw new Exception(desc.isEmpty() ? ("发送验证码失败：" + msg) : desc);
    }

    /** 验证码登录。成功返回会话 cookie（含 sessionid），交给 Session.begin。 */
    public Map<String, String> smsLogin(String phone, String code) throws Exception {
        String canonical = normalizeCN(phone);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("mobile", xor5(canonical));
        f.put("account_sdk_source", "app"); f.put("passport_support_flow", "captcha,verify"); f.put("mix_mode", "1");
        f.put("code", xor5(code));
        if (!smsCodeKey.isEmpty()) { f.put("passport_mfa_retry_tag", "1"); f.put("sms_code_key", xor5(smsCodeKey)); }
        Map<String, Object> r = accountPost("/passport/mobile/sms_login/", f);
        String msg = LvguoPlayback.str(r.get("message"));
        if (!"success".equals(msg)) {
            // 红果风控要求本人二次验证（上行短信）：把服务端给的收件号码 / 短信内容交给界面引导用户完成
            Mfa mfa = parseMfa(lastRaw);
            if (mfa != null) { pendingMfa = mfa; pendingPhone = canonical; pendingCode = code; throw new MfaRequired(mfa); }
            Map<String, Object> data = asMap(r.get("data"));
            String desc = data != null ? LvguoPlayback.str(data.get("description")) : "";
            throw new Exception(desc.isEmpty() ? ("登录失败：" + msg) : desc);
        }
        if (!cookies.containsKey("sessionid") && !cookies.containsKey("sessionid_ss"))
            throw new Exception("登录未返回会话，请重试");
        return new LinkedHashMap<>(cookies);
    }

    // ------------------------------------------------------------ 二次验证（上行短信，参照 aston314/hongguo-tv）

    /** 服务端下发的验证挑战：用登录手机向 number 发送 content。 */
    public static final class Mfa {
        public String encryptUid, codeKey, scene, logId, number, content;
    }
    /** 登录需要本人短信验证。 */
    public static final class MfaRequired extends Exception {
        public final Mfa mfa;
        MfaRequired(Mfa m) { super("为了账号安全，需要完成短信验证"); mfa = m; }
    }
    /** 验证还没完成（用户还没发短信或服务端还没收到），继续等待。 */
    public static final class MfaPending extends Exception { MfaPending() { super("正在等待短信验证"); } }

    private Mfa pendingMfa; private String pendingPhone = "", pendingCode = "";
    public Mfa pendingMfa() { return pendingMfa; }
    public void cancelMfa() { pendingMfa = null; pendingPhone = ""; pendingCode = ""; }

    static Mfa parseMfa(String raw) {
        try {
            org.json.JSONObject data = new org.json.JSONObject(raw).optJSONObject("data");
            if (data == null) return null;
            Mfa m = new Mfa();
            m.encryptUid = data.optString("encrypt_uid"); m.codeKey = data.optString("sms_code_key");
            m.scene = data.optString("passport_scene");
            org.json.JSONObject ev = data.optJSONObject("event_params"); m.logId = ev == null ? "" : ev.optString("log_id");
            org.json.JSONArray ways = data.optJSONArray("verify_ways");
            if (ways != null) for (int i = 0; i < ways.length(); i++) {
                org.json.JSONObject w = ways.optJSONObject(i); if (w == null) continue;
                if ("upsms".equals(w.optString("verify_way")) || !w.optString("sms_content").isEmpty()) {
                    m.content = w.optString("sms_content"); m.number = w.optString("channel_mobile"); break;
                }
            }
            if (m.encryptUid.isEmpty() || m.codeKey.isEmpty() || m.number == null || m.number.isEmpty() || m.content == null || m.content.isEmpty()) return null;
            return m;
        } catch (Exception e) { return null; }
    }

    private static boolean isExpiredDesc(String desc) {
        return desc != null && (desc.contains("过期") || desc.contains("失效") || desc.contains("长时间未操作"));
    }

    private static boolean loginOk(org.json.JSONObject root) {
        if (root == null || !"success".equals(root.optString("message"))) return false;
        org.json.JSONObject data = root.optJSONObject("data");
        String err = data != null ? data.optString("error_code") : root.optString("error_code");
        return err.isEmpty() || "0".equals(err);
    }

    /** 用户发完短信后调用：未完成抛 MfaPending；完成则拿票据重新登录，返回会话 cookie。 */
    public Map<String, String> verifyMfa() throws Exception {
        Mfa m = pendingMfa;
        if (m == null) throw new Exception("验证已结束，请重新获取验证码");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("encrypt_uid", m.encryptUid); f.put("verify_ticket", ""); f.put("verify_scene", "sms_login");
        f.put("verify_reason", "ato"); f.put("copywriting_key", "sms_login"); f.put("ies_safety_diversion_tag", "mfa");
        f.put("new_verify_flow", ""); f.put("new_authn_sdk_version", "1.1.31");
        f.put("new_authn_sdk_verify_reason", "ato"); f.put("new_authn_sdk_verify_scene", "sms_login");
        f.put("request_tag_from", "h5"); f.put("new_authn_sdk_log_id", m.logId == null ? "" : m.logId);
        f.put("biz_params", new org.json.JSONObject().put("passport_mfa_retry_tag", "1").put("sms_code_key", m.codeKey).toString());
        accountPost("/passport/upsms/verify/", f);
        org.json.JSONObject root = new org.json.JSONObject(lastRaw);
        org.json.JSONObject data = root.optJSONObject("data");
        String err = data != null ? data.optString("error_code") : root.optString("error_code");
        String desc = data != null ? data.optString("description") : root.optString("description");
        if ("1045".equals(err)) throw new MfaPending();
        if (!loginOk(root)) {
            if (isExpiredDesc(desc)) { cancelMfa(); throw new Exception("红果已结束本次短信验证，请重新获取验证码"); }
            throw new Exception(desc.isEmpty() ? "短信验证未通过" : desc);
        }
        String ticket = data == null ? "" : data.optString("ticket");
        if (ticket.isEmpty() && data != null) ticket = data.optString("verify_ticket");
        if (ticket.isEmpty()) throw new Exception("验证响应缺少登录票据");
        // 带票据重新登录：sms_code_key 必须保留初次挑战的 codeKey，verify_ticket 传刚换取的票据。
        Map<String, String> lf = new LinkedHashMap<>();
        lf.put("mobile", xor5(pendingPhone));
        lf.put("account_sdk_source", "app"); lf.put("passport_support_flow", "captcha,verify"); lf.put("mix_mode", "1");
        lf.put("code", xor5(pendingCode)); lf.put("passport_mfa_retry_tag", "1");
        lf.put("sms_code_key", xor5(m.codeKey)); lf.put("verify_ticket", ticket);
        accountPost("/passport/mobile/sms_login/", lf);
        org.json.JSONObject loginRoot = new org.json.JSONObject(lastRaw);
        if (!loginOk(loginRoot)) {
            org.json.JSONObject d2 = loginRoot.optJSONObject("data");
            String d2s = d2 != null ? d2.optString("description") : loginRoot.optString("description");
            if (isExpiredDesc(d2s)) { cancelMfa(); throw new Exception("红果已结束本次短信验证，请重新获取验证码"); }
            throw new Exception(d2s.isEmpty() ? "验证后登录失败，请重试" : d2s);
        }
        if (!cookies.containsKey("sessionid") && !cookies.containsKey("sessionid_ss")) throw new Exception("登录未返回会话，请重试");
        cancelMfa();
        return new LinkedHashMap<>(cookies);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    private static Map<String, Object> parse(String json) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            m.put("message", root.optString("message"));
            Map<String, Object> data = new LinkedHashMap<>();
            org.json.JSONObject d = root.optJSONObject("data");
            if (d != null) {
                String desc = d.optString("description");
                if (!desc.isEmpty()) data.put("description", desc);
                String key = d.optString("sms_code_key");
                if (!key.isEmpty()) data.put("sms_code_key", key);
            } else {
                String desc = root.optString("description");
                if (!desc.isEmpty()) data.put("description", desc);
            }
            m.put("data", data);
            return m;
        } catch (Exception ignored) {
            m.put("message", LvguoPlayback.extract(json, "message"));
            String desc = LvguoPlayback.extract(json, "description");
            Map<String, Object> data = new LinkedHashMap<>();
            if (!desc.isEmpty()) data.put("description", desc);
            String key = LvguoPlayback.extract(json, "sms_code_key");
            if (!key.isEmpty()) data.put("sms_code_key", key);
            m.put("data", data);
            return m;
        }
    }
}
