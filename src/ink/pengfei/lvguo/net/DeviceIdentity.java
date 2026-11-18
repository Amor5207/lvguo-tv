package ink.pengfei.lvguo.net;

import android.os.Looper;

import ink.pengfei.lvguo.App;

/**
 * 服务端注册过的设备身份（交接文档 §11 方案 A + B）。
 *
 * 红果的内容/播放接口只认「同一次 device_register 得到的 device_id + install_id 这一对」：
 * 本地自造的 id（尤其 iid = device_id 的老写法）会让 multi_video_model 回 HTTP 200 + 0 字节，
 * 表现就是「拿不到播放地址」。这里负责：
 *  - 首次启动 / 老数据（iid == device_id）→ 后台注册一次并落盘；
 *  - 请求前 ensure()：在后台线程上若还没注册就同步注册；
 *  - 遇到 200 + 空包 → refresh() 重新注册再重试（自愈）。
 * cdid 继续用 device_id，iid 用 install_id，两者不再混用。
 */
public final class DeviceIdentity {
    private DeviceIdentity() {}

    private static final String KEY_DEV = "reg_device_id", KEY_IID = "reg_install_id";
    private static final Object LOCK = new Object();
    private static volatile boolean registered;
    private static long lastRefresh;
    private static long lastFailure;
    private static final long FAILURE_BACKOFF_MS = 30_000;

    /** App.onCreate 调用：载入已注册身份；没有则沿用旧值并在后台注册。 */
    public static void init() {
        String dev = App.prefs().getString(KEY_DEV, null), iid = App.prefs().getString(KEY_IID, null);
        if (dev != null && iid != null && !dev.equals(iid)) {
            apply(dev, iid);
            registered = true;
            return;
        }
        App.io().execute(() -> { try { ensure(); } catch (Exception ignored) { } });
    }

    public static boolean registered() { return registered; }

    /** 刚注册失败过（退避期内）。 */
    static boolean recentlyFailed() { return lastFailure != 0 && System.currentTimeMillis() - lastFailure < FAILURE_BACKOFF_MS; }

    /** 确保已注册（只在后台线程同步注册；主线程上直接返回）。 */
    public static void ensure() {
        if (registered || Looper.myLooper() == Looper.getMainLooper()) return;
        synchronized (LOCK) {
            if (registered) return;
            // 注册失败后 30 秒内不再重试，否则服务端不可达时每个请求都要先等一轮注册超时
            if (lastFailure != 0 && System.currentTimeMillis() - lastFailure < FAILURE_BACKOFF_MS) return;
            register();
        }
    }

    /** 自愈：设备对被服务端判定无效时重新注册。60 秒内最多一次。返回是否拿到了新身份。 */
    public static boolean refresh() {
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - lastRefresh < 60_000) return false;
            lastRefresh = now;
            registered = false;
            return register();
        }
    }

    private static boolean register() {
        try {
            String[] pair = DeviceRegistry.register();
            android.content.SharedPreferences prefs = App.prefs();
            android.content.SharedPreferences.Editor editor = prefs.edit().putString(KEY_DEV, pair[0]).putString(KEY_IID, pair[1]);
            // 账号通道仅在首次未初始化时沿用；取流自愈轮换设备时不能覆盖已登录账号的 device_id。
            if (prefs.getString("acct_device_id", null) == null || prefs.getString("acct_install_id", null) == null) {
                editor.putString("acct_device_id", pair[0]).putString("acct_install_id", pair[1]);
            }
            editor.apply();
            apply(pair[0], pair[1]);
            registered = true; lastFailure = 0;
            android.util.Log.i("DeviceIdentity", "registered device_id=" + pair[0]);
            return true;
        } catch (Exception e) {
            android.util.Log.w("DeviceIdentity", "register failed", e);
            lastFailure = System.currentTimeMillis();
            return false;
        }
    }

    private static void apply(String dev, String iid) {
        App.deviceId = dev;
        App.cdid = dev;
        App.iid = iid;
    }
}
