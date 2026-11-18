package ink.pengfei.lvguo.update;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 交给系统安装器安装。优先用 PackageInstaller 会话（TV/投影仪更可靠），失败再回退 ACTION_VIEW。 */
final class UpdateInstaller {
    private UpdateInstaller() {}

    /** 已经把用户带去开权限、回来后要继续安装的 apk（旧回退路径用）。 */
    static volatile File pending;

    /** @return 是否已成功把安装交给系统（弹确认界面由系统/接收器处理） */
    static boolean install(Activity a, File apk) {
        if (sessionInstall(a, apk)) return true;
        return legacyInstall(a, apk);
    }

    /** PackageInstaller 会话安装：写入 APK 后 commit，系统通过 UpdateInstallReceiver 弹确认/授权界面。 */
    private static boolean sessionInstall(Activity a, File apk) {
        try {
            if (apk == null || !apk.isFile() || apk.length() <= 0) return false;
            PackageInstaller pi = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            try { params.setAppPackageName(a.getPackageName()); } catch (Throwable ignored) {}
            int sessionId = pi.createSession(params);
            PackageInstaller.Session session = pi.openSession(sessionId);
            try {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent cb = new Intent(a.getApplicationContext(), UpdateInstallReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent intent = PendingIntent.getBroadcast(a.getApplicationContext(), sessionId, cb, flags);
                session.commit(intent.getIntentSender());
                return true;
            } catch (Throwable inner) {
                try { session.abandon(); } catch (Throwable ignored) {}
                throw inner;
            } finally {
                try { session.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            return false;
        }
    }

    /** 老式回退：FileProvider + ACTION_VIEW / ACTION_INSTALL_PACKAGE。 */
    private static boolean legacyInstall(Activity a, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                pending = apk;
                try {
                    Toast.makeText(a, "请允许安装未知应用，返回后自动继续", Toast.LENGTH_LONG).show();
                    a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + a.getPackageName())));
                } catch (Throwable ignored) {}
                return false;
            }
            pending = null;
            Uri uri = UpdateFileProvider.uriFor(a, apk);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                a.startActivity(i);
            } catch (Throwable first) {
                Intent alt = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(alt);
            }
            return true;
        } catch (Throwable e) {
            try { Toast.makeText(a, "无法打开安装程序", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
            return false;
        }
    }
}
