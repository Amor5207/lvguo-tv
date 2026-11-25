package ink.pengfei.lvguo.update;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** 断点续传下载 + SHA-256 校验。线程内同步执行，调用方放到后台线程。 */
final class UpdateDownloader {
    interface Progress { void on(long done, long total); }

    private volatile boolean cancelled;
    void cancel() { cancelled = true; }
    boolean isCancelled() { return cancelled; }

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** 成功返回校验通过的 apk；失败 / 取消返回 null（失败不抛异常）。 */
    File download(Context c, UpdateInfo info, Progress p) {
        File out = new File(dir(c), info.fileName()), part = new File(dir(c), info.fileName() + ".part");
        try {
            if (out.isFile() && verify(out, info)) return out;
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            for (int attempt = 0; attempt < 4 && !cancelled; attempt++) {
                try {
                    fetch(info, part, p);
                    if (cancelled) return null;
                    if (part.length() == info.size && verify(part, info) && part.renameTo(out)) return out;
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();   // 大小或校验不对：丢弃重来
                } catch (Exception e) {
                    sleep(1500L * (attempt + 1));
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void fetch(UpdateInfo info, File part, Progress p) throws Exception {
        long have = part.isFile() ? part.length() : 0;
        if (have == info.size && info.size > 0) return;
        if (have > info.size) { //noinspection ResultOfMethodCallIgnored
            part.delete(); have = 0; }
        HttpURLConnection h = (HttpURLConnection) new URL(info.url).openConnection();
        try {
            h.setConnectTimeout(UpdateConfig.CONNECT_TIMEOUT_MS);
            h.setReadTimeout(UpdateConfig.READ_TIMEOUT_MS);
            h.setRequestProperty("User-Agent", UpdateChecker.userAgent());
            if (have > 0) h.setRequestProperty("Range", "bytes=" + have + "-");
            int code = h.getResponseCode();
            if (code == 416) { //noinspection ResultOfMethodCallIgnored
                part.delete(); throw new java.io.IOException("HTTP 416"); }
            if (code == 200) have = 0;                       // 服务器不支持续传：从头来
            else if (code != 206) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = h.getInputStream(); RandomAccessFile f = new RandomAccessFile(part, "rw")) {
                f.setLength(have);
                f.seek(have);
                byte[] buf = new byte[64 * 1024];
                long done = have, lastTick = 0;
                int n;
                while (!cancelled && (n = in.read(buf)) > 0) {
                    f.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (p != null && now - lastTick > 250) { lastTick = now; p.on(done, info.size); }
                }
                if (p != null) p.on(done, info.size);
            }
        } finally { h.disconnect(); }
    }

    static boolean verify(File f, UpdateInfo info) {
        if (f.length() != info.size) return false;
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(java.util.Locale.US, "%02x", b));
            return sb.toString().equals(info.sha256);
        } catch (Exception e) { return false; }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
}
