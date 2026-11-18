package ink.pengfei.lvguo.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 自更新安装完成（MY_PACKAGE_REPLACED）后自动重新打开应用；只对应用内发起的更新生效。 */
public final class UpdateRelaunchReceiver extends BroadcastReceiver {
    private static final String KEY = "relaunch_after_update";

    static void arm(Context c) {
        try { c.getSharedPreferences("update", 0).edit().putLong(KEY, System.currentTimeMillis()).commit(); } catch (Throwable ignored) { }
    }

    @Override public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        android.content.SharedPreferences p = ctx.getSharedPreferences("update", 0);
        long at = p.getLong(KEY, 0);
        p.edit().remove(KEY).apply();
        if (at <= 0 || System.currentTimeMillis() - at > 30 * 60_000L) return;   // 不是刚才应用内发起的更新
        try {
            Intent launch = new Intent(ctx, ink.pengfei.lvguo.ui.StartupActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ctx.startActivity(launch);
        } catch (Throwable ignored) { }
    }
}
