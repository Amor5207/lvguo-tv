package ink.pengfei.lvguo.update;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** 向更新服务查询最新版本。任何异常都吞掉并返回「查不到」，绝不向外抛。 */
final class UpdateChecker {
    private UpdateChecker() {}

    /** 当前安装包的版本号。 */
    static int versionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable e) { return 0; }
    }

    static String versionName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable e) { return ""; }
    }

    private static String ua = "App";
    static String userAgent() { return ua; }

    /** 结果：info 为 null 表示没有新版本或查询失败；ok 区分「已是最新」与「没查成」。 */
    static final class Result {
        final boolean ok; final UpdateInfo info;
        Result(boolean ok, UpdateInfo info) { this.ok = ok; this.info = info; }
    }

    static Result check(Context c) {
        int cur = versionCode(c);
        return checkPackage(c, c.getPackageName(), cur, versionName(c));
    }

    private static Result checkPackage(Context c, String packageName, int cur, String version) {
        try {
            if (cur < 0) return new Result(false, null);
            ua = packageName + "/" + version + " (" + cur + ")";
            String url = UpdateConfig.BASE + "/api/v1/check?app=" + URLEncoder.encode(packageName, "UTF-8")
                    + "&versionCode=" + cur + "&channel=" + UpdateConfig.CHANNEL;
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            try {
                h.setConnectTimeout(UpdateConfig.CONNECT_TIMEOUT_MS);
                h.setReadTimeout(UpdateConfig.READ_TIMEOUT_MS);
                h.setRequestProperty("User-Agent", ua);
                h.setRequestProperty("Accept", "application/json");
                if (h.getResponseCode() != 200) return new Result(false, null);
                try (InputStream in = h.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0 && (total += n) < 65536) bo.write(buf, 0, n);
                    JSONObject j = new JSONObject(bo.toString("UTF-8"));
                    if (!j.optBoolean("update")) return new Result(true, null);
                    UpdateInfo info = new UpdateInfo(j);
                    // A stale registry must never make a newer install "upgrade"
                    // to a numerically older release.
                    if (info.valid() && info.versionCode > cur && olderNumericVersion(info.versionName, version))
                        return new Result(true, null);
                    // 服务端说有更新，但数据不完整 / 版本其实不更高：当作没查成，不打扰用户
                    return info.valid() && info.versionCode > cur ? new Result(true, info) : new Result(false, null);
                }
            } finally { h.disconnect(); }
        } catch (Throwable e) {
            return new Result(false, null);
        }
    }

    private static boolean olderNumericVersion(String candidate, String current) {
        if (candidate == null || current == null
                || !candidate.matches("\\d+(?:\\.\\d+)*")
                || !current.matches("\\d+(?:\\.\\d+)*")) return false;
        String[] next = candidate.split("\\."), installed = current.split("\\.");
        int count = Math.max(next.length, installed.length);
        for (int i = 0; i < count; i++) {
            long n, c;
            try {
                n = i < next.length ? Long.parseLong(next[i]) : 0;
                c = i < installed.length ? Long.parseLong(installed[i]) : 0;
            } catch (NumberFormatException ignored) { return false; }
            if (n != c) return n < c;
        }
        return false;
    }
}
