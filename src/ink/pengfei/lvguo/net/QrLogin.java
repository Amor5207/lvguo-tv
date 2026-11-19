package ink.pengfei.lvguo.net;

import org.json.JSONObject;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 红果扫码登录（官网 hongguoduanju.com 的网页通道）。
 * get_qrcode → 手机红果 App 扫码确认 → check_qrconnect 返回确认 → 逐跳跟随跳转拿到 sessionid。
 * 移植自 hongguo-demo/hg/login.py；该会话在 App 通道同样被认可。
 */
public final class QrLogin {
    public enum State { WAITING, SCANNED, CONFIRMED, EXPIRED, CANCELED }

    private static final String BASE = "https://hongguoduanju.com";
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    private final Map<String, String> jar = new LinkedHashMap<>();
    private String token = "";
    public byte[] image;
    public long expiresAt;

    private static Map<String, String> common() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("aid", "513755"); q.put("passport_jssdk_version", "3.4.10"); q.put("passport_jssdk_type", "normal");
        q.put("account_sdk_source", "web"); q.put("is_from_ttaccountsdk", "1"); q.put("language", "zh");
        q.put("next", BASE + "/");
        return q;
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA); h.put("Referer", BASE + "/"); h.put("Origin", BASE);
        h.put("Accept", "application/json, text/javascript");
        if (!jar.isEmpty()) h.put("Cookie", cookie());
        return h;
    }

    private String cookie() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) { if (sb.length() > 0) sb.append("; "); sb.append(e.getKey()).append('=').append(e.getValue()); }
        return sb.toString();
    }

    private void absorb(Account.Response r) {
        for (String c : r.cookies) {
            String first = c.split(";", 2)[0];
            int eq = first.indexOf('=');
            if (eq <= 0) continue;
            String v = first.substring(eq + 1).trim();
            if (!v.isEmpty() && !v.equalsIgnoreCase("deleted")) jar.put(first.substring(0, eq).trim(), v);
        }
    }

    private JSONObject json(String path, Map<String, String> q) throws Exception {
        Account.Response r = Account.request(BASE + path + "?" + Signer.encodeParams(q), headers(), null, null);
        absorb(r);
        if (r.status != 200) throw new java.io.IOException("登录服务暂时不可用（HTTP " + r.status + "）");
        return new JSONObject(r.text());
    }

    /** 取一张新二维码（约 60 秒有效）。 */
    public void start() throws Exception {
        JSONObject data = json("/passport/web/get_qrcode/", common());
        JSONObject inner = data.optJSONObject("data");
        if (!"success".equals(data.optString("message")) || inner == null) throw new java.io.IOException("获取二维码失败，请重试");
        token = inner.optString("token");
        String qr = inner.optString("qrcode");
        if (token.isEmpty() || qr.isEmpty()) throw new java.io.IOException("二维码数据不完整，请重试");
        int comma = qr.indexOf(',');
        image = Signer.b64decode(qr.startsWith("data:") && comma > 0 ? qr.substring(comma + 1) : qr);
        long expire = inner.optLong("expire_time");
        long now = System.currentTimeMillis();
        expiresAt = expire > 1_000_000_000L ? expire * 1000 : now + Math.max(30, expire > 0 ? expire : 120) * 1000L;
        if (expiresAt <= now + 30_000L) expiresAt = now + 120_000L;
    }

    /** 轮询一次。确认后会完成跳转并把 cookie 写入会话。 */
    public State poll() throws Exception {
        Map<String, String> q = common();
        q.put("token", token);
        JSONObject data = json("/passport/web/check_qrconnect/", q);
        if (!"success".equals(data.optString("message"))) throw new java.io.IOException("登录状态查询失败");
        JSONObject inner = data.optJSONObject("data");
        String raw = inner == null ? "" : inner.optString("status").trim().toLowerCase(java.util.Locale.US);
        switch (raw) {
            case "5": case "expired": case "timeout": return State.EXPIRED;
            case "4": case "canceled": case "cancelled": case "cancel": case "refused": case "reject": case "rejected": return State.CANCELED;
            case "3": case "confirmed": case "confirm":
                String redirect = inner.optString("redirect_url");
                if (!redirect.isEmpty()) follow(redirect);
                if (!jar.containsKey("sessionid") && !jar.containsKey("sessionid_ss")) throw new java.io.IOException("登录确认未完成，请重新扫码");
                Account.rememberLoginDevice(null);
                Session.begin(jar);
                return State.CONFIRMED;
            case "2": case "scanning": case "scanned": return State.SCANNED;
            default: return State.WAITING;
        }
    }

    private void follow(String value) throws Exception {
        URI url = URI.create(BASE + "/").resolve(value);
        for (int i = 0; i < 5; i++) {
            String host = url.getHost() == null ? "" : url.getHost();
            if (!"https".equals(url.getScheme()) || !(host.equals("hongguoduanju.com") || host.endsWith(".hongguoduanju.com")))
                throw new java.io.IOException("登录跳转到了不受支持的站点");
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", UA); h.put("Referer", BASE + "/");
            if (!jar.isEmpty()) h.put("Cookie", cookie());
            Account.Response r = Account.request(url.toString(), h, null, null);
            absorb(r);
            if (r.status >= 300 && r.status < 400) {
                if (r.location == null) throw new java.io.IOException("登录跳转缺少地址");
                url = url.resolve(r.location);
                continue;
            }
            if (r.status >= 400) throw new java.io.IOException("登录确认失败（HTTP " + r.status + "）");
            return;
        }
        throw new java.io.IOException("登录跳转次数过多");
    }
}
