package ink.pengfei.lvguo.net.lvguosign;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号类写接口的完整签名请求（Gorgon / Helios / Medusa，与登录、取流同一套）。
 * 旧的手写签名或不带签名的写请求，服务端同样回 code 0，但会被静默丢弃：
 * 观看历史因此一直写不进去（2026-11-11 用真实账号逐项对照确认）。
 */
public final class LvguoCloud {
    private LvguoCloud() {}
    private static final String VERSION = "7.3.5.32";

    /** POST JSON，返回响应文本。deviceId / installId 为该登录会话使用的已注册设备。 */
    public static String postJson(String host, String path, String deviceId, String installId, String cookie, byte[] body) throws Exception {
        return postJson(host, path, deviceId, installId, null, null, cookie, body);
    }

    /** cdid / openudid 为设备注册时生成的那一组；缺少时服务端同样回成功但不落库。 */
    public static String postJson(String host, String path, String deviceId, String installId, String cdid, String openudid, String cookie, byte[] body) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        String[][] kv = {{"ac", "wifi"}, {"aid", "8662"}, {"app_name", "novelread"}, {"channel", "update_64"},
            {"device_brand", "Redmi"}, {"device_id", deviceId}, {"device_platform", "android"}, {"device_type", "25053RT47C"},
            {"dpi", "520"}, {"iid", installId}, {"language", "zh"}, {"manifest_version_code", "73532"}, {"os", "android"},
            {"os_api", "36"}, {"os_version", "16"}, {"resolution", "1280*2772"}, {"ssmix", "a"}, {"update_version_code", "73532"},
            {"version_code", "73532"}, {"version_name", VERSION}};
        for (String[] p : kv) q.put(p[0], p[1] == null ? "" : p[1]);
        if (cdid != null && !cdid.isEmpty()) q.put("cdid", cdid);
        if (openudid != null && !openudid.isEmpty()) q.put("openudid", openudid);
        long stamp = System.currentTimeMillis();
        LvguoPlayback.Signed s = LvguoPlayback.signRequest(q, body, stamp, deviceId, VERSION);
        HttpURLConnection c = (HttpURLConnection) new URL(host + path + "?" + s.rawQuery).openConnection();
        try {
            c.setRequestMethod("POST"); c.setConnectTimeout(12000); c.setReadTimeout(15000); c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> e : s.headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            c.setRequestProperty("User-Agent", LvguoPlayback.DEFAULT_UA);
            c.setRequestProperty("Accept", "application/json; charset=utf-8");
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("Passport-Sdk-Version", "50561");
            c.setRequestProperty("X-SS-DP", "8662");
            c.setRequestProperty("X-Reading-Request", stamp + "-" + (LvguoPlayback.RND.nextInt() & 0x7fffffff));
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            OutputStream os = c.getOutputStream(); os.write(body); os.close();
            int code = c.getResponseCode();
            byte[] resp = LvguoPlayback.readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) throw new java.io.IOException("账号服务 HTTP " + code);
            return new String(resp, StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }
}
