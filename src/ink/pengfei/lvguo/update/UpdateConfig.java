package ink.pengfei.lvguo.update;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/**
 * 检查更新的唯一配置点。接入其他应用时只改这里（服务地址 / 渠道），
 * 应用 ID 取自运行时包名，版本号取自安装包自身，无需手填。
 */
final class UpdateConfig {
    private UpdateConfig() {}

    /** 更新登记服务（走边缘加速）。 */
    static volatile String BASE = "https://update.pengfei.ink";   // 非 final 仅为测试可替换

    /** 渠道由安装包清单的 lvguo.update.channel 决定（stable / test），默认正式版。 */
    static String channel(Context c) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(c.getPackageName(), PackageManager.GET_META_DATA);
            String v = ai.metaData == null ? null : ai.metaData.getString("lvguo.update.channel");
            if (v != null && v.matches("[a-z][a-z0-9_-]{0,19}")) return v;
        } catch (Throwable ignored) { }
        return "stable";
    }
    /** 自动检查的最短间隔（测试渠道 1 分钟，方便反复验证更新流程）。 */
    static long checkInterval(Context c) {
        return "test".equals(channel(c)) ? 60_000L : CHECK_INTERVAL_MS;
    }
    static final long CHECK_INTERVAL_MS = 6 * 3600_000L;
    static final int CONNECT_TIMEOUT_MS = 6_000, READ_TIMEOUT_MS = 10_000;
}
