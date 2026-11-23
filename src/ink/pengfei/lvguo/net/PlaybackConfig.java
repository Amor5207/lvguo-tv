package ink.pengfei.lvguo.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Pattern;

/** Validated playback routing; the APK baseline remains available after data is cleared. */
public final class PlaybackConfig {
    private static final String CONFIG_URL = "https://update.pengfei.ink/api/v1/playback-config";
    private static final String DEFAULT_URL = "https://lvguo.pengfei.ink/api/tv/model";
    private static final String PRIMARY_HOST = "api5-normal-sinfonlinea.fqnovel.com";
    private static final String BACKUP_HOST = "api3-normal-sinfonlinea.fqnovel.com";
    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_]{1,48}");
    private static volatile PlaybackConfig current = defaults();
    public final int revision;
    public final String modelUrl, dataField, modelField, listField, drScene;
    public final java.util.List<String> routes;
    private final java.util.Map<String, ApiEndpoint> apis;

    public static final class ApiEndpoint {
        public final String method, host, backupHost, path, sign;
        public final java.util.Map<String, String> query;
        public final String bodyJson;
        ApiEndpoint(String method, String host, String backupHost, String path, String sign, java.util.Map<String, String> query, String bodyJson) {
            this.method = method; this.host = host; this.backupHost = backupHost; this.path = path; this.sign = sign;
            this.query = query == null ? java.util.Collections.emptyMap() : java.util.Collections.unmodifiableMap(query);
            this.bodyJson = bodyJson;
        }
    }

    private PlaybackConfig(int revision, String modelUrl, String dataField, String modelField, String listField, java.util.List<String> routes, String drScene, java.util.Map<String, ApiEndpoint> apis) {
        this.revision=revision; this.modelUrl=modelUrl; this.dataField=dataField; this.modelField=modelField; this.listField=listField;
        this.routes=java.util.Collections.unmodifiableList(routes); this.drScene=drScene;
        this.apis=java.util.Collections.unmodifiableMap(apis);
    }
    private static java.util.List<String> defaultRoutes() { return java.util.Arrays.asList("dynamic", "videotab", "legacy"); }
    private static java.util.Map<String, ApiEndpoint> defaultApis() {
        java.util.Map<String, ApiEndpoint> m = new java.util.LinkedHashMap<>();
        m.put("detail", new ApiEndpoint("POST", PRIMARY_HOST, BACKUP_HOST, "/novel/player/video_detail/v1/", "v5", null,
                "{\"biz_param\":{\"caller_scene\":\"single_col\",\"detail_page_version\":0,\"from_video_id\":\"\",\"need_all_video_definition\":false,\"need_mp4_align\":false,\"source\":4,\"video_id_type\":1}}"));
        m.put("detail_batch", new ApiEndpoint("POST", PRIMARY_HOST, BACKUP_HOST, "/novel/player/multi_video_detail/player/v1", "v5", null,
                "{\"dr_scene\":\"player\",\"biz_param\":{\"detail_page_version\":0,\"need_all_video_definition\":false,\"need_mp4_align\":false,\"source\":7,\"use_os_player\":false,\"use_server_dns\":false}}"));
        java.util.Map<String, String> rankQ = new java.util.LinkedHashMap<>();
        rankQ.put("tab_type", "26"); rankQ.put("client_req_type", "2"); rankQ.put("client_template", "2"); rankQ.put("screen_width_px", "1350");
        m.put("rank", new ApiEndpoint("GET", PRIMARY_HOST, BACKUP_HOST, "/reading/bookapi/bookmall/cell/change/v1/", "v5", rankQ, null));
        m.put("album_videos", new ApiEndpoint("GET", PRIMARY_HOST, BACKUP_HOST, "/api/novel/origin/album/get_video_list/v1/", "v5", null, null));
        m.put("comments_book", new ApiEndpoint("GET", PRIMARY_HOST, BACKUP_HOST, "/reading/ugc/novel_comment/book/v1/", "v5", null, null));
        m.put("comments_item", new ApiEndpoint("GET", PRIMARY_HOST, BACKUP_HOST, "/reading/ugc/novel_comment/item/v1/", "v5", null, null));
        return m;
    }
    private static boolean validApiHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.isEmpty() || h.indexOf('/') >= 0 || h.indexOf(':') >= 0 || h.indexOf('?') >= 0 || h.indexOf('#') >= 0 || h.indexOf('@') >= 0 || h.indexOf(' ') >= 0) return false;
        return (h.endsWith(".fqnovel.com") && !h.equals(".fqnovel.com")) || (h.endsWith(".snssdk.com") && !h.equals(".snssdk.com"));
    }
    private static boolean validApiPath(String p) {
        return p != null && p.length() >= 2 && p.length() <= 160 && p.charAt(0) == '/' && !p.contains("..") && p.indexOf('?') < 0 && p.indexOf('#') < 0 && p.indexOf(' ') < 0;
    }
    private static ApiEndpoint parseApiEndpoint(JSONObject o) {
        if (o == null) return null;
        String method = o.optString("method", "").trim().toUpperCase(java.util.Locale.ROOT);
        if (!"GET".equals(method) && !"POST".equals(method)) return null;
        String host = o.optString("host", "").trim();
        String backup = o.optString("backup_host", "").trim();
        String path = o.optString("path", "").trim();
        String sign = o.optString("sign", "v5").trim().toLowerCase(java.util.Locale.ROOT);
        if (!validApiHost(host) || (!backup.isEmpty() && !validApiHost(backup)) || !validApiPath(path)) return null;
        if (!"v5".equals(sign) && !"minireel".equals(sign) && !"none".equals(sign)) return null;
        java.util.Map<String, String> q = new java.util.LinkedHashMap<>();
        JSONObject qObj = o.optJSONObject("query");
        if (qObj != null) {
            java.util.Iterator<String> it = qObj.keys();
            while (it.hasNext()) { String k = it.next(); q.put(k, qObj.optString(k, "")); }
        }
        JSONObject bObj = o.optJSONObject("body");
        return new ApiEndpoint(method, host, backup.isEmpty() ? BACKUP_HOST : backup, path, sign, q, bObj == null ? null : bObj.toString());
    }
    private static PlaybackConfig defaults() { return new PlaybackConfig(0, DEFAULT_URL, "data", "video_model", "video_list", defaultRoutes(), "default", defaultApis()); }
    public static PlaybackConfig get() { return current; }
    public ApiEndpoint api(String name) {
        ApiEndpoint hit = apis.get(name);
        if (hit != null) return hit;
        return defaultApis().get(name);
    }
    static PlaybackConfig parse(String raw) throws Exception {
        if (raw == null || raw.length() > 16384) throw new IllegalArgumentException("invalid config size");
        JSONObject root = new JSONObject(raw);
        if (root.optInt("schema", -1)!=1 || root.optInt("revision",-1)<0) throw new IllegalArgumentException("invalid config version");
        JSONObject tv=root.getJSONObject("tv");
        if (!"server_hmac_sha256".equals(tv.getString("signing"))) throw new IllegalArgumentException("unsupported signing");
        URL u=new URL(tv.getString("model_url"));
        if (!"https".equals(u.getProtocol()) || !"lvguo.pengfei.ink".equalsIgnoreCase(u.getHost()) || u.getPort()!=-1 || u.getUserInfo()!=null || u.getQuery()!=null || u.getRef()!=null || !"/api/tv/model".equals(u.getPath())) throw new IllegalArgumentException("invalid model URL");
        String data=tv.getString("data_field"), model=tv.getString("model_field"), list=tv.getString("list_field");
        if (!FIELD.matcher(data).matches() || !FIELD.matcher(model).matches() || !FIELD.matcher(list).matches()) throw new IllegalArgumentException("invalid fields");
        java.util.List<String> parsedRoutes = new java.util.ArrayList<>();
        org.json.JSONArray arr = root.optJSONArray("routes");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String r = arr.optString(i, "").trim();
                if (("dynamic".equals(r) || "videotab".equals(r) || "legacy".equals(r)) && !parsedRoutes.contains(r)) parsedRoutes.add(r);
            }
        }
        if (parsedRoutes.isEmpty()) parsedRoutes.addAll(defaultRoutes());
        JSONObject dyn = root.optJSONObject("dynamic");
        String scene = dyn == null ? "default" : dyn.optString("dr_scene", "default").trim();
        if (scene.isEmpty() || !FIELD.matcher(scene).matches()) scene = "default";
        java.util.Map<String, ApiEndpoint> parsedApis = defaultApis();
        JSONObject apisObj = root.optJSONObject("apis");
        if (apisObj != null) {
            java.util.Iterator<String> it = apisObj.keys();
            while (it.hasNext()) {
                String k = it.next();
                ApiEndpoint ep = parseApiEndpoint(apisObj.optJSONObject(k));
                if (ep != null) parsedApis.put(k, ep);
            }
        }
        return new PlaybackConfig(root.getInt("revision"),u.toString(),data,model,list,parsedRoutes,scene,parsedApis);
    }
    public static void init(Context context) {
        SharedPreferences prefs=context.getSharedPreferences("playback_config", Context.MODE_PRIVATE);
        try { current=parse(prefs.getString("last_good", "")); } catch (Exception ignored) { current=defaults(); }
        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "playback-config"); t.setDaemon(true); return t; });
        scheduler.scheduleWithFixedDelay(() -> refresh(prefs), 0, 5, java.util.concurrent.TimeUnit.MINUTES);
    }
    private static void refresh(SharedPreferences prefs) {
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(CONFIG_URL).openConnection();c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");
            if(c.getResponseCode()!=200) return;
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>16384)throw new IllegalArgumentException("config too large");out.write(b,0,n);}}
            String raw=out.toString("UTF-8");PlaybackConfig next=parse(raw);
            if(next.revision<current.revision)return;
            if(prefs.edit().putString("last_good",raw).commit())current=next;
        } catch(Exception e){Log.i("PlaybackConfig","Using last available playback configuration");}
        finally {if(c!=null)c.disconnect();}
    }
}
