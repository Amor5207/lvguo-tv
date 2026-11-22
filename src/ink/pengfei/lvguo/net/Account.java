package ink.pengfei.lvguo.net;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.store.Store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 红果账号通道（与手机 App 同源的数据）。
 *
 * 两族接口（见 hongguo-demo/HANDOVER.md §5）：
 *  - 普通族：带登录 cookie 直接调（账号资料、观看历史、收藏读取、进度上传）；
 *  - 签名族：必须 Gorgon/Medusa… 全套签名（收藏写入、预约读取/取消）。缺签名网关静默回 0 字节。
 *
 * 账号通道使用一台「服务端注册过」的设备（device_id + install_id 成对），
 * 与内容通道的设备号分开，互不影响现有播放。
 */
public final class Account {
    private Account() {}

    public static final String APP_HOST = "https://api5-normal-sinfonlinea.fqnovel.com";
    public static final String SUBSCRIBE_HOST = "https://reading.snssdk.com";
    static final String VERSION_NAME = "7.1.3.32";
    /** Defaults decoded from the official Hongguo APK; remote SSConfig can override them. */
    static final int APK_DEFAULT_HISTORY_PAGE_SIZE = 500;
    static final boolean APK_DEFAULT_READ_HISTORY_PROGRESS_MERGE = false;
    static final boolean APK_DEFAULT_PROGRESS_AND_HISTORY_SYNC = true;
    static final boolean APK_DEFAULT_CUMULATIVE_DURATION_OPT = false;
    /** APK UploadProgressWriteMode.PROGRESS_AND_READ_HISTORY has @SerializedName("1"). */
    static final int WRITE_MODE_PROGRESS_AND_READ_HISTORY = 1;

    /** 账号接口出错（含会话失效）。 */
    public static final class AccountException extends java.io.IOException {
        public final boolean sessionExpired;
        AccountException(String m, boolean expired) { super(m); sessionExpired = expired; }
    }

    // ------------------------------------------------------------ 设备（账号通道专用）

    private static final Object DEVICE_LOCK = new Object();

    /** 返回 {device_id, install_id}；首次调用时向服务端注册一台设备，失败时退回内容通道设备号。 */
    public static String[] device() {
        synchronized (DEVICE_LOCK) {
            DeviceIdentity.ensure();
            String d = App.prefs().getString("acct_device_id", null), i = App.prefs().getString("acct_install_id", null);
            if (d != null && i != null) return new String[]{d, i};
            if (DeviceIdentity.recentlyFailed()) return new String[]{App.deviceId, App.iid};
            try {
                String[] reg = DeviceRegistry.register();
                App.prefs().edit().putString("acct_device_id", reg[0]).putString("acct_install_id", reg[1]).apply();
                return reg;
            } catch (Exception e) {
                android.util.Log.w("Account", "device register failed, fallback", e);
                return new String[]{App.deviceId, App.iid};
            }
        }
    }

    private static Map<String, String> params() {
        String[] login = loginDevice();
        String[] dev = login != null ? login : device();
        Map<String, String> q = new LinkedHashMap<>();
        q.put("aid", "8662"); q.put("app_name", "novelread"); q.put("device_platform", "android");
        q.put("device_type", "25053RT47C"); q.put("device_brand", "Redmi"); q.put("channel", "update_64");
        q.put("version_code", "71332"); q.put("version_name", VERSION_NAME); q.put("manifest_version_code", "71332");
        q.put("update_version_code", "71332"); q.put("pv_player", "71332"); q.put("os", "android");
        q.put("os_version", "16"); q.put("os_api", "36"); q.put("ac", "wifi"); q.put("language", "zh");
        q.put("resolution", "1280*2772"); q.put("dpi", "520"); q.put("host_abi", "arm64-v8a"); q.put("ssmix", "a");
        q.put("dragon_device_type", "phone"); q.put("compliance_status", "0"); q.put("need_personal_recommend", "1");
        q.put("player_so_load", "1"); q.put("is_android_pad_screen", "0");
        q.put("device_id", dev[0]); q.put("iid", dev[1]);
        if (login != null) { q.put("cdid", login[2]); q.put("openudid", login[3]); }
        return q;
    }

    private static final ThreadLocal<Long> EXPECTED_SESSION = new ThreadLocal<>();
    static void bindSession(long generation) { EXPECTED_SESSION.set(generation); }
    static void unbindSession() { EXPECTED_SESSION.remove(); }
    static boolean boundSessionIsCurrent() { Long expected = EXPECTED_SESSION.get(); return expected == null || expected == Session.generation(); }
    static long requestGeneration() { Long expected = EXPECTED_SESSION.get(); return expected == null ? Session.generation() : expected; }

    // ------------------------------------------------------------ HTTP

    static final class Response {
        int status; byte[] body; List<String> cookies = new ArrayList<>(); String location; String ttToken;
        String text() { try { return new String(body, "UTF-8"); } catch (Exception e) { return ""; } }
    }

    /** Request seam for local protocol tests; production leaves this unset. */
    interface RequestTransport {
        Response execute(String url, Map<String, String> headers, byte[] body, String contentType) throws Exception;
    }
    private static volatile RequestTransport requestTransport;
    static void setRequestTransportForTests(RequestTransport transport) { requestTransport = transport; }

    /** 不自动跟随重定向（登录确认跳转要逐跳校验域名）；所有 Set-Cookie 交给调用方/会话。 */
    static Response request(String url, Map<String, String> headers, byte[] body, String contentType) throws Exception {
        RequestTransport transport = requestTransport;
        if (transport != null) return transport.execute(url, headers, body, contentType);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000); c.setReadTimeout(20000); c.setUseCaches(false);
        c.setRequestMethod(body == null ? "GET" : "POST");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        try {
            if (body != null) {
                c.setDoOutput(true);
                if (contentType != null && (headers == null || !headers.containsKey("Content-Type"))) c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream(); os.write(body); os.close();
            }
            Response r = new Response();
            r.status = c.getResponseCode();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = readAll(in);
            if ("gzip".equalsIgnoreCase(c.getHeaderField("Content-Encoding")) && r.body.length > 0)
                r.body = readAll(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(r.body)));
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet())
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) r.cookies.addAll(e.getValue());
            r.location = c.getHeaderField("Location");
            r.ttToken = c.getHeaderField("X-Tt-Token");
            return r;
        } finally { c.disconnect(); }
    }

    static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) >= 0) out.write(b, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static String url(String host, String path, Map<String, String> q) {
        return host + path + "?" + Signer.encodeParams(q);
    }

    private static Map<String, String> appHeaders(long generation) throws java.io.IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", Signer.APP_UA);
        h.put("Accept", "application/json; charset=utf-8");
        h.put("Cookie", Session.cookieHeaderForGeneration(generation));
        return h;
    }

    private static JSONObject parse(Response r, String path, long generation) throws Exception {
        if (generation != Session.generation()) throw new java.io.IOException("会话已切换");
        for (String c : r.cookies) Session.absorbForGeneration(c, generation);
        String text = r.text().trim();
        if (text.isEmpty()) throw new AccountException("服务暂无响应，请稍后重试", false);
        JSONObject data = new JSONObject(text);
        int code = data.optInt("code", data.optInt("Code", 0));
        String message = data.optString("message", "");
        if (isExpired(code, message, data)) throw new AccountException("登录已失效，请重新扫码登录", true);
        return data;
    }

    private static boolean isExpired(int code, String message, JSONObject data) {
        JSONObject inner = data.optJSONObject("data");
        String name = inner == null ? "" : inner.optString("name", inner.optString("error_name", ""));
        return message.contains("session_expired") || name.contains("session_expired") || code == 100004;
    }

    /** 普通族 GET。 */
    static JSONObject get(String path, Map<String, String> extra) throws Exception {
        long generation = requestGeneration();
        Map<String, String> h = appHeaders(generation);
        Map<String, String> q = params();
        if (extra != null) q.putAll(extra);
        return parse(request(url(APP_HOST, path, q), h, null, null), path, generation);
    }

    /** 普通族 POST。 */
    static JSONObject post(String path, JSONObject body) throws Exception {
        long generation = requestGeneration();
        Map<String, String> h = appHeaders(generation);
        byte[] raw = body.toString().getBytes("UTF-8");
        h.put("x-ss-stub", Signer.hex(Signer.md5(raw)).toUpperCase(java.util.Locale.US));
        return parse(request(url(APP_HOST, path, params()), h, raw, "application/json; charset=UTF-8"), path, generation);
    }

    /** 签名族 GET/POST（body 为 null 即 GET）。 */
    static JSONObject signed(String host, String path, Map<String, String> extra, JSONObject body) throws Exception {
        long generation = requestGeneration();
        String cookie = Session.cookieHeaderForGeneration(generation);
        Map<String, String> q = params();
        if (extra != null) q.putAll(extra);
        byte[] raw = body == null ? null : body.toString().getBytes("UTF-8");
        Signer.Signed s = Signer.sign(host + path, q, raw, q.get("device_id"), q.get("version_name"));
        Map<String, String> h = new LinkedHashMap<>(s.headers);
        h.put("Cookie", cookie);
        h.put("X-Xs-From-Web", "0");
        return parse(request(s.url, h, raw, "application/json; charset=UTF-8"), path, generation);
    }

    private static void ok(JSONObject data, String what) throws AccountException {
        int code = data.optInt("code", data.optInt("Code", 0));
        if (code != 0) throw new AccountException(what + "未完成（" + code + "）" + shortMessage(data), false);
    }
    private static String shortMessage(JSONObject d) {
        String m = d.optString("message", "");
        return m.isEmpty() || m.equalsIgnoreCase("success") ? "" : "：" + (m.length() > 30 ? m.substring(0, 30) : m);
    }

    // ------------------------------------------------------------ 账号资料

    /** 读取并缓存账号资料。返回 null 表示会话未被服务端接受（视为未登录）。 */
    public static Session.Profile profile() throws Exception {
        long generation = requestGeneration();
        JSONObject root = get("/reading/user/info/v1/", null);
        ok(root, "读取账号资料");
        JSONObject d = root.optJSONObject("data");
        if (d == null) throw new AccountException("读取账号资料未完成", false);
        if (d.optString("biz_user_id", "").trim().isEmpty()) throw new AccountException("登录已失效，请重新扫码登录", true);
        Session.Profile p = new Session.Profile();
        p.uid = d.optString("biz_user_id");
        p.name = d.optString("user_name");
        p.avatar = firstNonEmpty(d.optString("expand_avatar_url"), d.optString("avatar_url"));
        p.cover = d.optString("profile_cover_url");
        p.coverColor = d.optString("profile_cover_url_color");
        p.signature = d.optString("description");
        p.gender = d.optInt("gender");
        JSONObject vip = d.optJSONObject("vip_info");
        p.vip = vip != null && "1".equals(vip.optString("is_vip"));
        p.vipExpire = vip == null ? 0 : vip.optLong("expire_time");
        p.watchMs = d.optLong("video_consume_duration");
        p.activeDays = d.optInt("active_day");
        Session.setProfileIfCurrent(p, generation);
        return p;
    }

    private static String firstNonEmpty(String a, String b) { return a != null && !a.isEmpty() ? a : b; }

    /**
     * 会话心跳：服务端在需要时回写新的 sid_guard / sessionid（吸收进会话即完成续期）。
     * 返回 true 表示心跳被接受。
     */
    public static boolean heartbeat() throws Exception {
        long generation = requestGeneration();
        byte[] form = "scene=normal".getBytes("UTF-8");
        Response r;
        if (requestTransport != null) {
            r = request(url(APP_HOST, "/passport/token/beat/v2/", params()), appHeaders(generation), form, "application/x-www-form-urlencoded");
        } else {
            String cookie = Session.cookieHeaderForGeneration(generation);
            Map<String, String> q = params();
            Signer.Signed s = Signer.sign(APP_HOST + "/passport/token/beat/v2/", q, form, q.get("device_id"), q.get("version_name"));
            Map<String, String> h = new LinkedHashMap<>(s.headers);
            h.put("User-Agent", Signer.APP_UA);
            h.put("Accept", "application/json; charset=utf-8");
            h.put("Passport-Sdk-Version", "50561");
            h.put("X-SS-DP", "8662");
            h.put("X-XS-From-Web", "0");
            String tk = App.prefs().getString("acct_tt_token", null);
            if (tk != null && !tk.isEmpty()) h.put("X-Tt-Token", tk);
            h.put("Cookie", cookie);
            r = request(s.url, h, form, "application/x-www-form-urlencoded");
            if (r.ttToken != null && !r.ttToken.isEmpty() && generation == Session.generation()) rememberTtToken(r.ttToken);
        }
        JSONObject d = parse(r, "beat", generation);
        JSONObject inner = d.optJSONObject("data");
        return "success".equals(d.optString("message")) && (inner == null || inner.optInt("error_code", 0) == 0);
    }

    public static void logoutRemote(String cookie) {
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", Signer.APP_UA); h.put("Cookie", cookie);
            byte[] body = "{}".getBytes("UTF-8");
            h.put("x-ss-stub", Signer.hex(Signer.md5(body)).toUpperCase(java.util.Locale.US));
            request(url(APP_HOST, "/passport/user/logout/", params()), h, body, "application/json; charset=UTF-8");
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ 数据模型

    /** 云端条目（历史 / 收藏 / 预约通用）。 */
    public static final class Entry {
        public String seriesId, title, cover, vid, desc, publishText;
        public int episodeIndex, episodeCount, positionMs, durationMs;
        public long time, watchedMs, progressTime;
        public boolean online = true;
    }

    // ------------------------------------------------------------ 观看历史（手机「观看历史」同源）

    /** Parameters serialized by the APK's GetReadHistoryRequest model. */
    static Map<String, String> readHistoryParams(int offset, int limit, boolean firstLoad,
                                                  boolean querySoftDeleted, String bookIds) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("book_type", "2");
        q.put("limit", String.valueOf(limit));
        q.put("last_min_read_timestamp_ms", "0");
        q.put("offset", String.valueOf(offset));
        q.put("full_field", "false");
        q.put("is_first_load", String.valueOf(firstLoad));
        q.put("query_soft_deleted", String.valueOf(querySoftDeleted));
        if (bookIds != null) q.put("book_ids", bookIds);
        return q;
    }

    /**
     * Mirrors VideoRecordImpl.loadMoreRemoteReadHistoryAsync: the App explicitly sends
     * is_first_load=false for every page (including offset zero). Its MineHistoryOptV713
     * remote setting defaults request_page_size to 500; the server may override that value.
     */
    static Map<String, String> historyListParams(int offset, int limit) {
        return readHistoryParams(offset, limit, false, false, null);
    }

    public static List<Entry> history(int limit) throws Exception {
        List<Entry> out = new ArrayList<>();
        int offset = 0;
        while (out.size() < limit) {
            Map<String, String> q = historyListParams(offset, APK_DEFAULT_HISTORY_PAGE_SIZE);
            JSONObject data = get("/reading/bookapi/read_history/list/v1/", q);
            ok(data, "读取观看历史");
            JSONObject block = data.optJSONObject("data");
            if (block == null || !block.has("has_more")) throw new AccountException("观看历史分页状态不完整，请重试", false);
            JSONArray rows = block == null ? null : block.optJSONArray("data_list");
            if (rows == null) {
                if (block != null && block.has("total") && block.optLong("total", -1) == 0 && !block.optBoolean("has_more")) rows = new JSONArray();
                else throw new AccountException("观看历史响应不完整，请重试", false);
            }
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) throw new AccountException("观看历史条目不完整，请重试", false);
                if (row.optBoolean("is_delete")) continue;
                String sid = row.optString("book_id_str", "");
                if (sid.isEmpty() || "null".equals(sid)) sid = row.optString("book_id", "");
                if (!sid.matches("\\d+")) throw new AccountException("观看历史条目缺少剧集编号，请重试", false);
                Entry e = new Entry();
                e.seriesId = sid; e.title = row.optString("book_name"); e.cover = row.optString("thumb_url");
                long vid = row.optLong("vid");
                e.vid = vid > 0 ? String.valueOf(vid) : null;
                e.episodeIndex = Math.max(0, row.optInt("vid_index", 1) - 1);
                e.positionMs = (int) row.optLong("current_play_position");
                e.watchedMs = row.optLong("player_accumulate_total_time");
                e.durationMs = 0;
                e.episodeCount = row.optInt("episode_cnt");
                e.time = row.optLong("read_timestamp_ms");
                out.add(e);
            }
            int next;
            try { next = HistoryPaging.next(block.optBoolean("has_more"), offset, block.optInt("next_offset", offset)); }
            catch (IllegalArgumentException e) { throw new AccountException("观看历史分页不完整，请重试", false); }
            if (next < 0) break;
            offset = next;
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** The app queries explicit soft-deletion state by book id before merging local history. */
    public static Map<String, Long> deletedHistoryTimes(List<String> seriesIds) throws Exception {
        Map<String, Long> deleted = new LinkedHashMap<>();
        // The APK's deleted-history lookup joins at most 30 ids into book_ids.
        for (int from = 0; from < seriesIds.size(); from += 30) {
            List<String> batch = seriesIds.subList(from, Math.min(seriesIds.size(), from + 30));
            int offset = 0;
            while (true) {
                Map<String, String> q = readHistoryParams(offset, 0, false, true,
                        android.text.TextUtils.join(",", batch));
                JSONObject response = get("/reading/bookapi/read_history/list/v1/", q);
                ok(response, "查询历史删除状态");
                JSONObject data = response.optJSONObject("data");
                if (data == null || !data.has("has_more")) throw new AccountException("历史删除状态响应不完整", false);
                JSONArray rows = data.optJSONArray("data_list");
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null || !row.optBoolean("is_delete")) continue;
                    String id = row.optString("book_id_str", "");
                    if (id.isEmpty() || "null".equals(id)) id = row.optString("book_id", "");
                    long time = row.optLong("update_timestamp_ms", 0);
                    if (batch.contains(id) && time > 0) {
                        Long previous = deleted.get(id);
                        if (previous == null || time > previous) deleted.put(id, time);
                    }
                }
                int next;
                try { next = HistoryPaging.next(data.optBoolean("has_more"), offset, data.optInt("next_offset", offset)); }
                catch (IllegalArgumentException e) { throw new AccountException("历史删除状态分页不完整", false); }
                if (next < 0) break;
                offset = next;
            }
        }
        return deleted;
    }

    /**
     * 写接口必须带的 cdid / openudid：缺少时服务端回成功但不落库（实测）。
     * 值本身不要求与设备注册时一致，但要固定——首次生成后保存，之后一直用同一组。
     */
    /** 记下手机号登录所用的设备；扫码登录传 null 清除（扫码会话用账号通道自己的设备）。 */
    public static void rememberLoginDevice(String[] ids) {
        android.content.SharedPreferences.Editor e = App.prefs().edit();
        if (ids == null || ids.length < 4 || ids[2].isEmpty() || ids[3].isEmpty())
            e.remove("login_device_id").remove("login_install_id").remove("login_cdid").remove("login_openudid").remove("acct_tt_token");
        else e.putString("login_device_id", ids[0]).putString("login_install_id", ids[1]).putString("login_cdid", ids[2]).putString("login_openudid", ids[3]);
        e.apply();
    }
    public static void rememberTtToken(String token) {
        android.content.SharedPreferences.Editor e = App.prefs().edit();
        if (token == null || token.isEmpty()) e.remove("acct_tt_token");
        else e.putString("acct_tt_token", token);
        e.apply();
    }
    private static String[] loginDevice() {
        android.content.SharedPreferences p = App.prefs();
        String d = p.getString("login_device_id", null), i = p.getString("login_install_id", null);
        String c = p.getString("login_cdid", null), o = p.getString("login_openudid", null);
        return d == null || i == null || c == null || o == null ? null : new String[]{d, i, c, o};
    }

    private static String[] cloudIds() {
        synchronized (DEVICE_LOCK) {
            android.content.SharedPreferences p = App.prefs();
            String cdid = p.getString("acct_cdid", null), open = p.getString("acct_openudid", null);
            if (cdid == null || cdid.isEmpty() || open == null || open.isEmpty()) {
                cdid = java.util.UUID.randomUUID().toString();
                open = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                p.edit().putString("acct_cdid", cdid).putString("acct_openudid", open).apply();
            }
            return new String[]{cdid, open};
        }
    }

    /**
     * 写 read_history/update。必须用完整签名（LvguoCloud）：旧签名或不带签名的写请求，
     * 服务端同样回 code 0，但会被静默丢弃，记录进不了观看历史。
     */
    private static JSONObject historyUpdate(JSONObject body) throws Exception {
        String path = "/reading/bookapi/read_history/update/v1/";
        long generation = requestGeneration();
        RequestTransport transport = requestTransport;
        if (transport != null) return post(path, body);   // 本地协议测试走可替换的传输层
        // 手机号登录的会话绑定在登录时那台设备上，用别的设备发写请求会被丢弃：优先用登录设备
        String[] login = loginDevice();
        String[] dev = login != null ? login : device();
        String[] ids = login != null ? new String[]{login[2], login[3]} : cloudIds();
        String text = ink.pengfei.lvguo.net.lvguosign.LvguoCloud.postJson(APP_HOST, path, dev[0], dev[1], ids[0], ids[1],
                Session.cookieHeaderForGeneration(generation), body.toString().getBytes("UTF-8"));
        Response r = new Response();
        r.status = 200; r.body = text.getBytes("UTF-8");
        return parse(r, path, generation);
    }

    private static void checkHistoryUpdate(JSONObject response, String action) throws Exception {
        ok(response, action);
        JSONObject data = response.optJSONObject("data");
        JSONArray failed = data == null ? null : data.optJSONArray("update_fail_datas");
        if (failed != null && failed.length() > 0)
            throw new AccountException(action + "未完成，服务端拒绝了部分记录", false);
    }

    /**
     * 向云端「观看历史」上报一条记录（read_history/update，is_delete=false）。
     * 让电视上看的剧也进手机的「观看历史」页（read_history/list 与手机同源）。
     */
    public static void reportHistory(String seriesId, String title, String vid, int episodeIndex,
                                     int positionMs, int durationMs, int episodeCount) throws Exception {
        if (seriesId == null || vid == null) return;
        Store.Item local = Store.watched(seriesId);
        long timestamp = local != null && local.time > 0 ? local.time : System.currentTimeMillis();
        long watchedMs = local == null ? 0 : local.watchedMs;
        JSONObject row = historyUploadRow(seriesId, vid, episodeIndex, positionMs, watchedMs, timestamp);
        JSONObject body = new JSONObject().put("update_datas", new JSONArray().put(row));
        JSONObject res = historyUpdate(body);
        checkHistoryUpdate(res, "上报观看历史");
    }

    /** Mirrors VideoRecordImpl's active ReadHistoryData fields before UpdateReadHistoryRequest. */
    static JSONObject historyUploadRow(String seriesId, String vid, int episodeIndex, int positionMs,
                                        long playerAccumulateMs, long readTimestampMs) throws Exception {
        return new JSONObject().put("book_id", Long.parseLong(seriesId)).put("book_type", 2)
                .put("is_delete", false).put("read_timestamp_ms", readTimestampMs)
                .put("update_timestamp_ms", readTimestampMs).put("recent_reads", 0).put("tone_id", 0)
                .put("current_play_position", Math.max(0, positionMs))
                .put("player_accumulate_total_time", Math.max(0L, playerAccumulateMs))
                .put("vid_index", Math.max(1, episodeIndex + 1)).put("vid", Long.parseLong(vid))
                .put("episode_cnt", 0).put("duration", 0).put("series_play_cnt", 0)
                .put("retain_video_play_time", 0).put("digged_count", 0).put("user_digg", false)
                .put("is_listen_mode", false).put("is_multi_season", 0).put("season_index", 0)
                .put("chapter_index", 0).put("is_interactive_game", false).put("origin_novel_book_id", 0)
                .put("meet_guide_comment_tag", false).put("user_playlet_comment_flag", false)
                .put("use_soft_delete", false);
    }

    /**
     * 删除观看历史（App 内的「删除/清空观看记录」走 read_history/update，ReadHistoryData.is_delete=true）。
     * 先走普通族，若服务端不认再签名重试；最后按原 App 的软删除查询回读确认。
     */
    public static void deleteHistory(List<Store.Item> entries) throws Exception {
        if (entries.isEmpty()) return;
        Map<String, Long> expectedDeletes = new LinkedHashMap<>();
        for (int from = 0; from < entries.size(); from += 50) {
            JSONArray list = new JSONArray();
            long now = System.currentTimeMillis();
            for (Store.Item entry : entries.subList(from, Math.min(entries.size(), from + 50))) {
                if (entry != null && entry.seriesId != null) {
                    list.put(historyDeleteRow(entry, now));
                    expectedDeletes.put(entry.seriesId, now);
                }
            }
            if (list.length() == 0) continue;
            JSONObject body = new JSONObject().put("update_datas", list);
            JSONObject res = historyUpdate(body);
            checkHistoryUpdate(res, "删除观看历史");
        }
        // 普通删除不留删除标记，服务端受理（code 0 且无失败条目）即完成，不再回读软删除状态。
    }

    /** A previous tombstone is insufficient; confirm this write or a newer server-side deletion. */
    static List<String> unverifiedDeleteIds(Map<String, Long> requested, Map<String, Long> observed) {
        List<String> remaining = new ArrayList<>();
        for (Map.Entry<String, Long> entry : requested.entrySet()) {
            Long actual = observed.get(entry.getKey());
            Long requestedAt = entry.getValue();
            if (actual == null || requestedAt == null || actual < requestedAt) remaining.add(entry.getKey());
        }
        return remaining;
    }

    /** Mirrors ReadHistoryData values populated by the official VideoRecordUploadMode.SOFT_DELETE path. */
    static JSONObject historyDeleteRow(Store.Item entry, long updateTimestampMs) throws Exception {
        long vid = 0;
        try { if (entry.vid != null) vid = Long.parseLong(entry.vid); } catch (NumberFormatException ignored) { }
        return new JSONObject().put("book_id", Long.parseLong(entry.seriesId)).put("book_type", 2)
                .put("is_delete", true).put("read_timestamp_ms", 0).put("update_timestamp_ms", updateTimestampMs)
                .put("recent_reads", 0).put("tone_id", 0)
                .put("current_play_position", Math.max(0, entry.positionMs))
                .put("player_accumulate_total_time", Math.max(0L, entry.watchedMs))
                .put("vid_index", Math.max(1, entry.episodeIndex + 1)).put("vid", vid)
                .put("episode_cnt", 0).put("duration", 0).put("series_play_cnt", 0)
                .put("retain_video_play_time", 0).put("digged_count", 0).put("user_digg", false)
                .put("is_listen_mode", false).put("is_multi_season", 0).put("season_index", 0)
                .put("chapter_index", 0).put("is_interactive_game", false).put("origin_novel_book_id", 0)
                .put("meet_guide_comment_tag", false).put("user_playlet_comment_flag", false)
                .put("use_soft_delete", false);   // 普通删除：软删除会留下撤不掉的标记，之后再看也回不到观看历史
    }

    /** 上报播放进度（播放中定时 + 暂停/切集/退出时）。手机历史首条随之更新。 */
    public static void uploadProgress(String seriesId, String vid, int episodeIndex, int positionMs, int durationMs, int episodeCount) throws Exception {
        if (seriesId == null || vid == null) return;
        Store.Item local = Store.watched(seriesId);
        // The APK's pd6.u3.c() uses player_cumulative_total_duration when present,
        // otherwise the current record's player_accumulate_total_time. Keep the
        // server-provided history value; current_play_time is a different field.
        long cumulative = local == null ? 0 : local.watchedMs;
        long readTimestamp = local != null && local.time > 0 ? local.time : System.currentTimeMillis();
        boolean mergeHistory = shouldMergeProgressAndHistory(APK_DEFAULT_READ_HISTORY_PROGRESS_MERGE,
                APK_DEFAULT_PROGRESS_AND_HISTORY_SYNC);
        JSONObject entry = progressRateRow(seriesId, vid, episodeIndex, positionMs, cumulative,
                readTimestamp, mergeHistory, APK_DEFAULT_CUMULATIVE_DURATION_OPT);
        JSONObject res = post("/reading/bookapi/read_progress/upload/v1/",
                progressUploadBody(entry, mergeHistory));
        ok(res, "同步播放进度");
    }

    static JSONObject progressUploadBody(JSONObject entry) throws Exception {
        return progressUploadBody(entry, shouldMergeProgressAndHistory(APK_DEFAULT_READ_HISTORY_PROGRESS_MERGE,
                APK_DEFAULT_PROGRESS_AND_HISTORY_SYNC));
    }

    static JSONObject progressUploadBody(JSONObject entry, boolean mergeHistory) throws Exception {
        JSONObject body = new JSONObject().put("books", new JSONArray().put(entry));
        if (mergeHistory) body.put("write_mode", WRITE_MODE_PROGRESS_AND_READ_HISTORY);
        return body;
    }

    static boolean shouldMergeProgressAndHistory(boolean readHistoryMergeEnabled,
                                                   boolean progressAndHistorySyncEnabled) {
        return readHistoryMergeEnabled && progressAndHistorySyncEnabled;
    }

    /**
     * Builds the APK ProgressRateInfo wire shape. Runtime-gated fields and unavailable
     * phone recommendation context follow the TV strategy documented in RED_FRUIT_API_REVERSE.md.
     * Nullable fields left unset by the APK builder stay omitted by its generated serializer.
     */
    static JSONObject progressRateRow(String seriesId, String vid, int episodeIndex, int positionMs,
                                      long cumulativeMs, long readTimestampMs) throws Exception {
        return progressRateRow(seriesId, vid, episodeIndex, positionMs, cumulativeMs,
                readTimestampMs,
                shouldMergeProgressAndHistory(APK_DEFAULT_READ_HISTORY_PROGRESS_MERGE,
                        APK_DEFAULT_PROGRESS_AND_HISTORY_SYNC),
                APK_DEFAULT_CUMULATIVE_DURATION_OPT);
    }

    static JSONObject progressRateRow(String seriesId, String vid, int episodeIndex, int positionMs,
                                      long cumulativeMs, long readTimestampMs,
                                      boolean mergeHistory, boolean cumulativeDurationOpt) throws Exception {
        return new JSONObject()
                .put("book_id", seriesId).put("book_type", 2).put("item_id", vid)
                .put("read_timestamp_ms", readTimestampMs)
                .put("current_play_time", Math.max(0, positionMs))
                // ProgressRateInfo's generated serializer always emits both primitive fields.
                // The APK builder only assigns these when their respective SSConfig gates pass.
                .put("player_cumulative_total_duration", cumulativeDurationOpt ? Math.max(0, cumulativeMs) : 0)
                .put("vid_index", mergeHistory ? Math.max(1, episodeIndex + 1) : 0)
                .put("channel_id", 0).put("cur_channel_id", 0)
                .put("page_index", 0).put("page_progress_rate", 0.0)
                .put("paragraph_offset", 0).put("progress_type", 0).put("tone_id", 0)
                .put("check_timestamp", false).put("is_listen_mode", false)
                .put("is_local_book", false).put("listen_and_read", false);
    }

    // ------------------------------------------------------------ 收藏（追剧书架）

    public static List<Entry> favorites() throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        // GetBookShelfVideoInfoRequest marks both pagination fields EncodeDefault.ALWAYS.
        q.put("offset", "0"); q.put("count", "0");
        JSONObject data = get("/reading/bookapi/bookshelf/video/info/v1/", q);
        ok(data, "读取收藏");
        JSONObject block = data.optJSONObject("data");
        JSONArray rows = block == null ? null : block.optJSONArray("video_shelf_info");
        List<Entry> out = new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            JSONObject video = row.optJSONObject("video_data");
            if (video == null) video = row;
            String sid = firstNonEmpty(video.optString("series_id"), firstNonEmpty(row.optString("series_id"), row.optString("book_id")));
            if (sid == null || !sid.matches("\\d+")) continue;
            Entry e = new Entry();
            e.seriesId = sid;
            e.title = firstNonEmpty(row.optString("series_title"), video.optString("title"));
            e.cover = firstNonEmpty(row.optString("series_cover_url"), firstNonEmpty(video.optString("cover_url"), firstNonEmpty(video.optString("cover"), video.optString("thumb_url"))));
            e.episodeCount = video.optInt("episode_cnt");
            e.time = row.optLong("collect_time") * (row.optLong("collect_time") < 10_000_000_000L ? 1000 : 1);
            long vid = video.optLong("vid");
            e.vid = vid > 0 ? String.valueOf(vid) : null;
            e.episodeIndex = Math.max(0, video.optInt("vid_index", 1) - 1);
            out.add(e);
        }
        return out;
    }

    /**
     * Mirrors VideoCollDataFactory's missing-progress refill for the video shelf:
     * POST mGetReadProgressByBookId with book_ids keyed by ReadingBookType.Watch (2).
     */
    static JSONObject progressByBookIdsBody(List<String> seriesIds) throws Exception {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (seriesIds != null) for (String id : seriesIds) {
            if (id == null || !id.matches("\\d+")) continue;
            ids.add(Long.parseLong(id));
        }
        JSONArray books = new JSONArray();
        for (Long id : ids) books.put(id);
        return new JSONObject().put("book_ids", new JSONObject().put("2", books));
    }

    public static List<Entry> progressByBookIds(List<String> seriesIds) throws Exception {
        JSONObject body = progressByBookIdsBody(seriesIds);
        JSONArray ids = body.getJSONObject("book_ids").optJSONArray("2");
        if (ids == null || ids.length() == 0) return new ArrayList<>();
        JSONObject response = post("/reading/bookapi/read_progress/get/v1/", body);
        ok(response, "读取收藏播放进度");
        JSONArray rows = response.optJSONArray("video_data");
        List<Entry> out = new ArrayList<>();
        if (rows == null) return out;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String sid = row.optString("series_id", "");
            if (!sid.matches("\\d+")) continue;
            Entry e = new Entry();
            e.seriesId = sid;
            e.vid = firstNonEmpty(row.optString("vid", ""), null);
            e.episodeIndex = Math.max(0, row.optInt("vid_index", 1) - 1);
            e.episodeCount = Math.max(0, row.optInt("episode_count", 0));
            e.positionMs = nonNegativeInt(row.optLong("current_play_time", 0));
            e.durationMs = nonNegativeInt(row.optLong("current_total_time", 0));
            e.watchedMs = Math.max(0L, row.optLong("player_cumulative_total_duration", 0));
            e.progressTime = Math.max(0L, row.optLong("last_watch_time", 0));
            out.add(e);
        }
        return out;
    }

    /** Overlay only the App's by-book progress response; keep shelf collection time/order. */
    static void applyFavoriteProgress(List<Entry> favorites, List<Entry> progress) {
        if (favorites == null || favorites.isEmpty() || progress == null || progress.isEmpty()) return;
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry entry : progress) if (entry != null && entry.seriesId != null) byId.put(entry.seriesId, entry);
        for (Entry favorite : favorites) {
            if (favorite == null) continue;
            Entry latest = byId.get(favorite.seriesId);
            if (latest == null) continue;
            favorite.episodeIndex = latest.episodeIndex;
            favorite.positionMs = latest.positionMs;
            favorite.durationMs = latest.durationMs;
            favorite.watchedMs = latest.watchedMs;
            favorite.progressTime = latest.progressTime;
            if (latest.episodeCount > 0) favorite.episodeCount = latest.episodeCount;
            if (latest.vid != null && !latest.vid.isEmpty()) favorite.vid = latest.vid;
        }
    }

    private static int nonNegativeInt(long value) { return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value)); }

    /** 加入 / 移出收藏（签名族），并回读验证。 */
    public static void setFavorite(String seriesId, boolean add) throws Exception {
        setFavorites(java.util.Collections.singletonList(seriesId), add);
    }

    /** 一次请求批量加入 / 移出收藏，只回读书架一轮（清空收藏时逐条写会各回读一次）。 */
    public static void setFavorites(List<String> seriesIds, boolean add) throws Exception {
        if (seriesIds.isEmpty()) return;
        long ts = System.currentTimeMillis();
        JSONObject body = favoriteUpdateBody(seriesIds, add, ts);
        JSONObject res = signed(APP_HOST, "/reading/bookapi/bookshelf/video/update/v1/", null, body);
        ok(res, add ? "加入收藏" : "取消收藏");
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(1000);
            java.util.Set<String> shelf = new java.util.HashSet<>();
            for (Entry entry : favorites()) shelf.add(entry.seriesId);
            boolean done = true;
            for (String id : seriesIds) if (shelf.contains(id) != add) { done = false; break; }
            if (done) return;
        }
        throw new AccountException("云端收藏状态未确认，请重试", false);
    }

    /** UpdateBookShelfVideoInfoRequest / UpdateBookShelfVideo wire shape from the App APK. */
    static JSONObject favoriteUpdateBody(String seriesId, boolean add, long modifyTimeMs) throws Exception {
        return favoriteUpdateBody(java.util.Collections.singletonList(seriesId), add, modifyTimeMs);
    }

    static JSONObject favoriteUpdateBody(List<String> seriesIds, boolean add, long modifyTimeMs) throws Exception {
        JSONArray ids = new JSONArray(), times = new JSONArray(), updates = new JSONArray();
        for (String id : seriesIds) {
            ids.put(id); times.put(modifyTimeMs);
            updates.put(new JSONObject()
                    .put("book_id", id).put("book_type", 2).put("group_name", "")
                    .put("modify_time", modifyTimeMs)
                    .put("video_shelf_operate_type", add ? 0 : 1));
        }
        return new JSONObject()
                .put("book_id_list", ids)
                .put("is_cancelled", false)
                .put("modify_time", times)
                .put("update_bookshelf_video_list", updates);
    }

    // ------------------------------------------------------------ 预约（追更）

    public static final class Reservations {
        public final List<Entry> items = new ArrayList<>();
        public int onlineTotal, offlineTotal;
    }

    /** 已上线 / 待上线预约列表（签名族，host 为 reading.snssdk.com）。 */
    public static Reservations reservations(boolean online) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("offset", "0"); q.put("limit", "50"); q.put("is_online", online ? "true" : "false");
        q.put("tab_type", "13"); q.put("session_id", ""); q.put("subscribe_order_type", "0");
        q.put("swipe_type", "0"); q.put("need_calendar_schema", "false"); q.put("subscribe_offset", "0");
        JSONObject data = signed(SUBSCRIBE_HOST, "/reading/user/subscribe/list/v1/", q, null);
        ok(data, "读取预约");
        Reservations r = new Reservations();
        JSONObject block = data.optJSONObject("data");
        if (block == null) return r;
        r.onlineTotal = block.optInt("online_total_count");
        r.offlineTotal = block.optInt("offline_total_count");
        JSONArray rows = block.optJSONArray("subscribe_items");
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            JSONObject sd = row.optJSONObject("subscribe_data");
            if (sd == null) sd = new JSONObject();
            Entry e = new Entry();
            e.online = row.optBoolean("is_online", online);
            // 已上线剧集优先取 subscribe_data.series_id（真实可播剧 ID），避免打开已失效的预约占位 item_id。
            e.seriesId = e.online
                    ? firstNonEmpty(sd.optString("series_id"), row.optString("item_id"))
                    : firstNonEmpty(row.optString("item_id"), sd.optString("series_id"));
            e.title = firstNonEmpty(sd.optString("title"), firstNonEmpty(row.optString("name"), row.optString("title")));
            e.cover = firstNonEmpty(sd.optString("cover"), row.optString("cover"));
            e.desc = firstNonEmpty(sd.optString("video_desc"), row.optString("item_desc"));
            e.episodeCount = sd.optInt("episode_cnt");
            e.episodeIndex = Math.max(0, sd.optInt("episode") - 1);
            long vid = sd.optLong("vid");
            e.vid = vid > 0 ? String.valueOf(vid) : null;
            e.publishText = row.optString("expected_rec_text");
            e.time = row.optLong("expected_publish_time", row.optLong("schedule_publish_time"));
            if (e.seriesId != null && !e.seriesId.isEmpty()) r.items.add(e);
        }
        return r;
    }

    /** 预约 / 取消预约（search/uncover_subscribe，op_type 1/2）。 */
    public static void setReservation(String seriesId, boolean add) throws Exception {
        JSONObject body = new JSONObject().put("item_id", seriesId).put("item_type", 1).put("op_type", add ? 1 : 2).put("item_id_str", seriesId);
        ok(signed(SUBSCRIBE_HOST, "/reading/bookapi/search/uncover_subscribe/v1/", null, body), add ? "预约" : "取消预约");
    }

    /** 签名 GET（供首页推荐流等签名族内容接口使用）。 */
    public static JSONObject signedGet(String host, String path, Map<String, String> query) throws Exception {
        return signed(host, path, query, null);
    }

    static String uuid() { return UUID.randomUUID().toString(); }
}
