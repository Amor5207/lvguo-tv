package ink.pengfei.lvguo.update;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 检查更新的统一入口。设计原则：
 *  - 更新是「锦上添花」：任何一步失败（断网、服务挂了、数据异常、磁盘满）都静默吞掉，
 *    绝不抛异常、不弹错误、不阻塞主界面，不影响其它功能；只有用户手动点「检查更新」才会提示结果。
 *  - 所有网络与下载都在独立的低优先级后台线程里。
 *  - 自动检查有节流，且不会在播放 / 休息等场景打断用户（调用方只在首页恢复时触发）。
 */
public final class UpdateManager {
    private UpdateManager() {}

    private enum State { IDLE, AVAILABLE, DOWNLOADING, READY }

    private static final String P_LAST = "update_last_check", P_SNOOZE = "update_snooze_until", P_SNOOZE_CODE = "update_snooze_code";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static volatile State state = State.IDLE;
    private static volatile UpdateInfo info;
    private static volatile File ready;
    private static volatile long done, total;
    private static volatile boolean checking, readyPrompted;
    private static volatile UpdateDownloader downloader;
    private static volatile UpdateDialog progressDialog;

    private static SharedPreferences prefs() { return ink.pengfei.lvguo.App.prefs(); }

    // ------------------------------------------------------------ 对外

    /** 设置页「检查更新」一行右侧的状态文字。 */
    public static String statusLabel() {
        try {
            switch (state) {
                case AVAILABLE: return "发现 " + info.versionName;
                case DOWNLOADING: return "下载中 " + percent() + "%";
                case READY: return "可安装 " + info.versionName;
                default: return "已是最新";
            }
        } catch (Throwable e) { return "检查更新"; }
    }

    /** 首页恢复时调用：静默检查（有节流）；有后台下好的新版本则提示安装。绝不抛异常。 */
    public static void onHomeResume(Activity host) {
        try {
            resumePendingInstall(host);
            if (state == State.READY) { promptReadyOnce(host); return; }
            if (state == State.DOWNLOADING || checking) return;
            if (state == State.AVAILABLE && info != null && !snoozed(info)) { showFound(host); return; }
            long now = System.currentTimeMillis();
            if (now - prefs().getLong(P_LAST, 0) < UpdateConfig.CHECK_INTERVAL_MS) return;
            runCheck(host, false);
        } catch (Throwable ignored) { }
    }

    /** 点击设置里的「检查更新」。 */
    public static void onItemClick(Activity host) {
        try {
            switch (state) {
                case READY: install(host); return;
                case DOWNLOADING: showProgress(host); return;
                case AVAILABLE: if (info != null) { showFound(host); return; } break;
                default: break;
            }
            if (checking) { toast(host, "正在检查…"); return; }
            toast(host, "正在检查更新…");
            runCheck(host, true);
        } catch (Throwable e) { safeToast(host, "检查失败，请稍后重试"); }
    }

    /** 启动时清理旧安装包（已安装的版本 / 残留的半成品）。 */
    public static void cleanup(Context c) {
        POOL.execute(() -> {
            try {
                int cur = UpdateChecker.versionCode(c);
                File[] fs = UpdateDownloader.dir(c).listFiles();
                if (fs == null) return;
                for (File f : fs) {
                    String n = f.getName();
                    int code = -1;
                    try { code = Integer.parseInt(n.replaceAll("^update-(\\d+)\\.apk(\\.part)?$", "$1")); } catch (Exception ignored) { }
                    if (code <= cur || code < 0) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                }
            } catch (Throwable ignored) { }
        });
    }

    /** 删掉除 keepCode 以外的所有已下载安装包（keepCode = -1 表示全删；正在下载的不动）。 */
    private static void purgeExcept(Context c, int keepCode) {
        try {
            if (state == State.DOWNLOADING) return;
            File[] fs = UpdateDownloader.dir(c).listFiles();
            if (fs == null) return;
            String keep = keepCode > 0 ? "update-" + keepCode + ".apk" : null;
            for (File f : fs) if (f.getName().startsWith("update-") && (keep == null || !f.getName().startsWith(keep))) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ 检查

    private static void runCheck(Activity host, boolean manual) {
        checking = true;
        final Context app = host.getApplicationContext();
        POOL.execute(() -> {
            UpdateChecker.Result r;
            try { r = UpdateChecker.check(app); } catch (Throwable e) { r = new UpdateChecker.Result(false, null); }
            checking = false;
            if (r.ok) {
                prefs().edit().putLong(P_LAST, System.currentTimeMillis()).apply();
                purgeExcept(app, r.info == null ? -1 : r.info.versionCode);   // 服务器已不提供的版本，残留安装包不再保留
            }
            final UpdateChecker.Result res = r;
            MAIN.post(() -> {
                try {
                    if (res.info != null) {
                        info = res.info;
                        File f = new File(UpdateDownloader.dir(app), info.fileName());
                        if (f.isFile() && UpdateDownloader.verify(f, info)) { ready = f; state = State.READY; }
                        else if (state != State.DOWNLOADING) state = State.AVAILABLE;
                        if (host.isFinishing()) return;
                        if (state == State.READY) { if (manual) install(host); else promptReadyOnce(host); }
                        else if (state == State.AVAILABLE && (manual || info.force || !snoozed(info))) showFound(host);
                    } else if (manual) {
                        toast(host, res.ok ? "已是最新版本" : "检查失败，请稍后重试");
                    }
                } catch (Throwable ignored) { }
            });
        });
    }

    private static boolean snoozed(UpdateInfo i) {
        if (i.force) return false;
        SharedPreferences p = prefs();
        return p.getInt(P_SNOOZE_CODE, 0) == i.versionCode && System.currentTimeMillis() < p.getLong(P_SNOOZE, 0);
    }

    // ------------------------------------------------------------ 弹窗

    private static void showFound(Activity host) {
        final UpdateInfo i = info;
        if (i == null || host.isFinishing()) return;
        String msg = String.format(Locale.US, "%s · %.1f MB", i.versionName, i.size / 1048576.0)
                + (i.notes.isEmpty() ? "" : "\n\n" + i.notes);
        String title = (i.force ? "需要更新到 " : "发现新版本 ") + i.versionName;
        // 一开始只有「取消 / 更新」；点了「更新」进入下载，才出现「后台更新」
        UpdateDialog.Btn cancel = new UpdateDialog.Btn("取消", false, () ->
                prefs().edit().putInt(P_SNOOZE_CODE, i.versionCode).putLong(P_SNOOZE, System.currentTimeMillis() + UpdateConfig.SNOOZE_MS).apply());
        UpdateDialog.Btn go = new UpdateDialog.Btn("更新", true, () -> { startDownload(host, i, true); showProgress(host); });
        if (i.force) new UpdateDialog(host, title, msg, false, true, null, go);
        else new UpdateDialog(host, title, msg, false, true, null, cancel, go);
    }

    private static void showProgress(Activity host) {
        final UpdateInfo i = info;
        if (i == null || host.isFinishing()) return;
        if (progressDialog != null && progressDialog.showing()) return;
        progressDialog = new UpdateDialog(host, "正在下载 " + i.versionName, progressText(), true, true, () -> progressDialog = null,
                new UpdateDialog.Btn("取消", false, () -> cancelDownload()),
                new UpdateDialog.Btn("后台更新", true, () -> toast(host, "已转入后台下载")));
        tickProgress();
    }

    private static void tickProgress() {
        UpdateDialog d = progressDialog;
        if (d == null || !d.showing()) return;
        d.setProgress(total > 0 ? (float) done / total : 0f, progressText());
        if (state == State.DOWNLOADING) MAIN.postDelayed(UpdateManager::tickProgress, 300);
    }

    private static String progressText() {
        return String.format(Locale.US, "%d%% · %.1f / %.1f MB", percent(), done / 1048576.0, total / 1048576.0);
    }

    private static int percent() { return total > 0 ? (int) Math.min(100, done * 100 / total) : 0; }

    private static void promptReadyOnce(Activity host) {
        final UpdateInfo i = info;
        if (readyPrompted || i == null || ready == null || host.isFinishing()) return;
        readyPrompted = true;
        new UpdateDialog(host, "新版本已下载", i.versionName + " 已准备好，是否现在安装？", false, true, null,
                new UpdateDialog.Btn("取消", false, null),
                new UpdateDialog.Btn("安装", true, () -> install(host)));
    }

    // ------------------------------------------------------------ 下载 / 安装

    private static void startDownload(Activity host, UpdateInfo i, boolean foreground) {
        if (state == State.DOWNLOADING) return;
        state = State.DOWNLOADING;
        done = 0; total = i.size;
        final Context app = host.getApplicationContext();
        final UpdateDownloader d = new UpdateDownloader();
        downloader = d;
        POOL.execute(() -> {
            File f = null;
            try { f = d.download(app, i, (a, t) -> { done = a; total = t; }); } catch (Throwable ignored) { }
            downloader = null;
            final File out = f;
            MAIN.post(() -> {
                try {
                    UpdateDialog pd = progressDialog;
                    if (out != null) {
                        ready = out; state = State.READY; readyPrompted = true;
                        if (pd != null && pd.showing()) pd.dismiss();
                        if (!host.isFinishing() && isForeground(host)) {
                            // 用户还停在这个界面：直接进入安装；否则只提示
                            if (foreground || (pd != null)) install(host);
                            else new UpdateDialog(host, "新版本已下载", i.versionName + " 已准备好，是否现在安装？", false, true, null,
                                    new UpdateDialog.Btn("取消", false, null), new UpdateDialog.Btn("安装", true, () -> install(host)));
                        }
                    } else {
                        // 取消或失败：回到「有更新」状态；只有用户在盯着进度条时才提示
                        state = State.AVAILABLE;
                        if (pd != null && pd.showing()) {
                            pd.dismiss();
                            if (!d.isCancelled()) safeToast(host, "下载失败，可在「设置 · 关于」重试");
                        }
                    }
                } catch (Throwable ignored) { }
            });
        });
    }

    private static void cancelDownload() {
        UpdateDownloader d = downloader;
        if (d != null) d.cancel();
    }

    private static boolean isForeground(Activity a) { return !a.isFinishing() && !a.isDestroyed(); }

    private static final int INSTALL_COUNTDOWN = 5;

    /** 安装前先倒计时：系统安装自身会结束当前进程，不提示的话看起来像闪退。 */
    private static void install(Activity host) {
        File f = ready;
        if (f == null || !f.isFile()) { state = State.AVAILABLE; return; }
        if (host.isFinishing() || host.isDestroyed()) return;
        final String ver = info != null ? info.versionName : "新版本";
        final boolean[] cancelled = {false};
        final Runnable[] tick = new Runnable[1];
        final UpdateDialog[] dlg = new UpdateDialog[1];
        final Runnable go = () -> {
            if (cancelled[0]) return;
            cancelled[0] = true;
            MAIN.removeCallbacks(tick[0]);
            if (dlg[0] != null) dlg[0].dismiss();
            UpdateRelaunchReceiver.arm(host);   // 安装完成后自动重新打开
            UpdateInstaller.install(host, f);
        };
        dlg[0] = new UpdateDialog(host, "即将安装 " + ver, countdownText(INSTALL_COUNTDOWN), true, true,
                () -> { if (!cancelled[0]) { cancelled[0] = true; MAIN.removeCallbacks(tick[0]); } },
                new UpdateDialog.Btn("取消", false, null),
                new UpdateDialog.Btn("立即安装", true, go));
        final long start = android.os.SystemClock.uptimeMillis(), totalMs = INSTALL_COUNTDOWN * 1000L;
        tick[0] = new Runnable() { public void run() {
            if (cancelled[0]) return;
            long el = android.os.SystemClock.uptimeMillis() - start;
            if (el >= totalMs) { go.run(); return; }
            int left = (int) Math.ceil((totalMs - el) / 1000.0);
            dlg[0].setProgress(el / (float) totalMs, countdownText(left));
            MAIN.postDelayed(this, 100);
        }};
        MAIN.post(tick[0]);
    }

    private static String countdownText(int sec) {
        return sec + " 秒后安装，完成后自动重新打开。";
    }

    /** 用户去系统设置里放行「安装未知应用」后回到应用：继续刚才的安装。 */
    private static void resumePendingInstall(Activity host) {
        File f = UpdateInstaller.pending;
        if (f == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26 && !host.getPackageManager().canRequestPackageInstalls()) return;
        UpdateInstaller.pending = null;
        if (f.isFile()) UpdateInstaller.install(host, f);
    }

    private static void toast(Activity host, String s) { try { Toast.makeText(host, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { } }
    private static void safeToast(Activity host, String s) { toast(host, s); }
}
