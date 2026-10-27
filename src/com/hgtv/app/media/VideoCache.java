package com.hgtv.app.media;

import android.content.Context;
import com.hgtv.app.App;
import com.hgtv.app.crypto.Mp4Decryptor;
import com.hgtv.app.crypto.Spade;
import com.hgtv.app.model.Gear;
import com.hgtv.app.net.Repo;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/** Local decrypted media, shared by playback and background preloading. */
public final class VideoCache {
    public interface Progress { void onProgress(int percent); }
    public static final int MAX_SHORT_SIDE = 1080;
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final ConcurrentHashMap<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final ExecutorService PREFETCH = Executors.newSingleThreadExecutor();
    private VideoCache() {}

    public static File dir(Context c) {
        File d = c.getExternalFilesDir("videos");
        if (d == null) d = new File(c.getCacheDir(), "videos");
        if (!d.exists()) d.mkdirs();
        return d;
    }
    private static String name(String vid, String def) {
        return vid + "_" + (def == null ? "auto" : def) + ".mp4";
    }
    public static File cached(Context c, String vid, String def) {
        File f = new File(dir(c), name(vid, def));
        if(!f.isFile() || f.length()<=1024)return null;
        if(android.os.Build.VERSION.SDK_INT<=28)try{Mp4Timing.normalizeFile(f);}catch(IOException error){return null;}
        return f;
    }
    public static List<Gear> playable(List<Gear> gears) {
        List<Gear> capped = new ArrayList<>();
        if (gears != null) for (Gear g : gears) {
            if (g != null && g.shortSide() > 0 && g.shortSide() <= MAX_SHORT_SIDE) capped.add(g);
        }
        return capped;
    }
    public static Gear pick(List<Gear> gears, String prefer) {
        return Repo.pickGear(playable(gears), prefer==null?"720p":prefer, MAX_SHORT_SIDE);
    }
    public static File prepare(Context c, String vid, List<Gear> gears,
                               String prefer, int maxShortSide, Progress cb) throws Exception {
        int limit = maxShortSide > 0 ? Math.min(maxShortSide, MAX_SHORT_SIDE) : MAX_SHORT_SIDE;
        Gear gear = Repo.pickGear(playable(gears), prefer, limit);
        if (gear == null) throw new IllegalStateException("没有可用播放源");
        return prepare(c, vid, gear, cb);
    }
    public static File prepare(Context c, String vid, Gear gear, Progress cb) throws Exception {
        if (gear == null || gear.shortSide() <= 0 || gear.shortSide() > MAX_SHORT_SIDE)
            throw new IllegalArgumentException("播放清晰度超出支持范围");
        File hit = cached(c, vid, gear.definition);
        if (hit != null) {
            hit.setLastModified(System.currentTimeMillis());
            if (cb != null) cb.onProgress(100);
            return hit;
        }
        String key = new File(dir(c), name(vid, gear.definition)).getAbsolutePath();
        Job created = new Job(c.getApplicationContext(), key, vid, gear);
        Job existing = JOBS.putIfAbsent(key, created);
        Job job = existing == null ? created : existing;
        if (cb != null) job.listeners.add(cb);
        try {
            // Claims a queued preload or waits for the existing download, without duplicating it.
            job.task.run();
            return job.task.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("视频缓存失败", cause);
        } finally {
            if (cb != null) job.listeners.remove(cb);
        }
    }
    private static final class Job implements Callable<File> {
        final Context context;
        final String key, vid;
        final Gear gear;
        final CopyOnWriteArrayList<Progress> listeners = new CopyOnWriteArrayList<>();
        final FutureTask<File> task;
        Job(Context c, String k, String v, Gear g) {
            context = c; key = k; vid = v; gear = g;
            task = new FutureTask<>(this);
        }
        @Override public File call() throws Exception {
            File tmp = new File(key + ".part");
            try {
                File hit = cached(context, vid, gear.definition);
                if (hit != null) return hit;
                try { download(gear.mainUrl,tmp,this::report); }
                catch (IOException error) {
                    if(Thread.currentThread().isInterrupted() || error instanceof InterruptedIOException)throw error;
                    if (gear.backupUrl == null || gear.backupUrl.isEmpty()) throw error;
                    download(gear.backupUrl,tmp,this::report);
                }
                if(tmp.length()<1024)throw new IOException("视频下载不完整");
                if(gear.encrypted){
                    if(gear.spadeA==null)throw new IOException("视频缺少解密密钥");
                    byte[] secret=Spade.spadeToKey(gear.spadeA);
                    if(secret==null)throw new IOException("视频密钥不可用");
                    Mp4Decryptor.decryptFile(tmp,secret);
                }
                if(android.os.Build.VERSION.SDK_INT<=28)Mp4Timing.normalizeFile(tmp);
                File result = new File(key);
                if (!tmp.renameTo(result)) throw new IOException("视频缓存写入失败");
                report(100);
                trim(context);
                return result;
            } finally {
                if (tmp.exists()) tmp.delete();
                JOBS.remove(key, this);
            }
        }
        void report(int percent) {
            for (Progress listener : listeners) {
                try { listener.onProgress(percent); } catch (RuntimeException ignored) { }
            }
        }
    }
    private static void download(String url,File file, Progress cb) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", App.UA);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("视频下载 HTTP " + status);
            int total = connection.getContentLength();
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count, read = 0, last = -1;
                while ((count = in.read(buffer)) != -1) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    out.write(buffer, 0, count); read += count;
                    if (total > 0) {
                        int p = (int) Math.min(99, read * 100L / total);
                        if (p != last) { last = p; cb.onProgress(p); }
                    }
                }
                if(total>0 && read!=total)throw new IOException("视频下载不完整");
            }
        } finally { connection.disconnect(); }
    }
    public static void prefetch(Context c, String vid, List<Gear> gears, String prefer, int maxShortSide) {
        if (vid == null || vid.isEmpty()) return;
        Context app = c.getApplicationContext();
        PREFETCH.execute(() -> {
            try { prepare(app, vid, gears, prefer, maxShortSide, null); }
            catch (Exception ignored) { }
        });
    }
    public static synchronized void trim(Context c) {
        File[] files = dir(c).listFiles((d, n) -> n.endsWith(".mp4"));
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        Arrays.sort(files, (a,b)->Long.compare(a.lastModified(),b.lastModified()));
        for (File file : files) {
            if (total <= MAX_BYTES) break;
            if (JOBS.containsKey(file.getAbsolutePath())) continue;
            long length = file.length();
            if (file.delete()) total -= length;
        }
    }
    public static void clear(Context c) {
        File[] files = dir(c).listFiles();
        if (files != null) for (File file : files) {
            if (!JOBS.containsKey(file.getAbsolutePath()) && !file.getName().endsWith(".part")) file.delete();
        }
    }
    public static long usedBytes(Context c) {
        File[] files = dir(c).listFiles();
        long total = 0;
        if (files != null) for (File file : files) total += file.length();
        return total;
    }
}

