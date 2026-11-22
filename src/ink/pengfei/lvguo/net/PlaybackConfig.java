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
    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_]{1,48}");
    private static volatile PlaybackConfig current = defaults();
    public final int revision;
    public final String modelUrl, dataField, modelField, listField, drScene;
    public final java.util.List<String> routes;
    private PlaybackConfig(int revision, String modelUrl, String dataField, String modelField, String listField, java.util.List<String> routes, String drScene) {
        this.revision=revision; this.modelUrl=modelUrl; this.dataField=dataField; this.modelField=modelField; this.listField=listField;
        this.routes=java.util.Collections.unmodifiableList(routes); this.drScene=drScene;
    }
    private static java.util.List<String> defaultRoutes() { return java.util.Arrays.asList("dynamic", "videotab", "legacy"); }
    private static PlaybackConfig defaults() { return new PlaybackConfig(0, DEFAULT_URL, "data", "video_model", "video_list", defaultRoutes(), "default"); }
    public static PlaybackConfig get() { return current; }
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
        return new PlaybackConfig(root.getInt("revision"),u.toString(),data,model,list,parsedRoutes,scene);
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
