package com.hgtv.app.img;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.hgtv.app.App;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 海报加载器：内存 LRU → 磁盘 LRU → 网络。
 *
 * 设计取舍：
 *  - 不用第三方图片库：包体、启动开销、线程数都不可控；海报场景只需"降采样 + 缓存"。
 *  - RGB_565：海报无透明通道，直接省一半内存（170×230 一张约 78KB）。
 *  - 磁盘缓存是必须的：TV 上反复进出详情页，没有磁盘缓存每次都要重下，
 *    首页滚动会明显发涩。
 *  - 同一 URL 并发请求合并：首页同一部剧常出现在多个横排。
 */
public final class ImageLoader {

    private static final int DISK_MAX = 220 * 1024 * 1024;   // 220MB
    private static final int TIMEOUT = 12000;
    private static final java.util.concurrent.ExecutorService PREVIEWS=java.util.concurrent.Executors.newFixedThreadPool(4,r->{Thread thread=new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"poster-preview");return thread;});
    private static final java.util.concurrent.ExecutorService IMAGES=java.util.concurrent.Executors.newFixedThreadPool(2,r->{Thread thread=new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"poster-loader");return thread;});

    private static final LruCache<String, Bitmap> MEM;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 同一 URL 的并发请求合并：等待队列里的 ImageView 会在解码完成后统一回填 */
    private static final ConcurrentHashMap<String, java.util.List<java.lang.ref.WeakReference<ImageView>>> WAITERS =
            new ConcurrentHashMap<>();

    static {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        MEM = new LruCache<String, Bitmap>(Math.max(4 * 1024, Math.min(16 * 1024, maxKb / 10))) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private ImageLoader() {}

    /** 同一张图会被不同控件以不同尺寸请求（卡片 176 / Hero 背景 180 / 海报 200），分桶避免互相顶掉 */
    private static String key(String url, int reqW) {
        return reqW <= 0 ? url : url + "@" + (reqW / 64);
    }

    public static synchronized void load(final String input, final ImageView view, final int reqW, final int reqH) {
        final String url=ImageUrls.compatible(input);
        if (view == null) return;
        Object previous=view.getTag(com.hgtv.app.R.id.img_tag);
        if(url==null || !url.equals(previous))view.setTag(com.hgtv.app.R.id.img_quality_stage,0);
        view.setTag(com.hgtv.app.R.id.img_tag, url);
        if (url == null || url.isEmpty()) { view.setImageDrawable(null); return; }
        final String k = key(url, reqW);
        Bitmap hit = MEM.get(k);
        if (hit != null) {
            view.setTag(com.hgtv.app.R.id.img_quality_stage,2);
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);

        java.util.List<java.lang.ref.WeakReference<ImageView>> waiters = WAITERS.get(k);
        if (waiters != null) {
            synchronized (waiters) {
                waiters.add(new java.lang.ref.WeakReference<>(view));
            }
            return;
        }
        final java.util.List<java.lang.ref.WeakReference<ImageView>> list = new java.util.ArrayList<>();
        list.add(new java.lang.ref.WeakReference<>(view));
        WAITERS.put(k, list);

        final File f=diskFile(url);
        PREVIEWS.execute(()->{
            Bitmap preview=null;
            try{
                if(f.exists()&&f.length()>0)preview=decode(f,48);
                else{
                    String low=ImageUrls.preview(url);
                    if(low!=null){File lowFile=diskFile(low);preview=lowFile.exists()?decode(lowFile,48):download(low,48);}
                }
            }catch(Throwable ignored){}
            if(preview!=null)publish(list,url,preview,1);
        });
        IMAGES.execute(() -> {
            boolean wanted=false;
            synchronized(list){for(java.lang.ref.WeakReference<ImageView> ref:list){ImageView target=ref.get();if(target!=null && url.equals(target.getTag(com.hgtv.app.R.id.img_tag)) && target.isShown()){wanted=true;break;}}}
            if(!wanted){WAITERS.remove(k,list);return;}
            Bitmap bmp = null;
            try {
                if (f.exists() && f.length() > 0) bmp = decode(f, reqW);
                if (bmp == null) {
                    bmp = download(url, reqW);
                    // download() 保存原始图片，避免缩略图破坏后续大尺寸封面的清晰度。
                }
            } catch (Throwable ignored) {
            }
            java.util.List<java.lang.ref.WeakReference<ImageView>> ws;
            synchronized(ImageLoader.class){ws=WAITERS.remove(k);}
            if (bmp == null) return;
            MEM.put(k, bmp);
            if(ws!=null)publish(ws,url,bmp,2);
        });
    }

    private static void publish(java.util.List<java.lang.ref.WeakReference<ImageView>> views,String url,Bitmap bitmap,int stage){
        MAIN.post(()->{synchronized(views){for(java.lang.ref.WeakReference<ImageView> ref:views){ImageView target=ref.get();if(target==null||!target.isShown()||!url.equals(target.getTag(com.hgtv.app.R.id.img_tag)))continue;Object current=target.getTag(com.hgtv.app.R.id.img_quality_stage);int shown=current instanceof Integer?(Integer)current:0;if(stage>shown){target.setImageBitmap(bitmap);target.setTag(com.hgtv.app.R.id.img_quality_stage,stage);}}}});
    }

    private static Bitmap decode(File f, int reqW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        if (reqW > 0 && o.outWidth > 0) {
            while (o.outWidth / (sample * 2) >= reqW) sample *= 2;
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.RGB_565;
        o2.inDither = false;
        Bitmap hit = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        if (hit != null) return hit;
        // 历史缓存里的 HEIC / HEIF：部分设备 BitmapFactory 不支持，继续用 ImageDecoder 尝试。
        if (android.os.Build.VERSION.SDK_INT >= 28 && f.length() < 10 * 1024 * 1024) {
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] data = new byte[16384];
                int n;
                while ((n = in.read(data)) > 0) out.write(data, 0, n);
                in.close();
                return decodeBytes(out.toByteArray(), reqW);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Bitmap download(String url, int reqW) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestProperty("User-Agent", App.UA);
            InputStream in = c.getInputStream();

            // 一次读进内存再解码：海报通常 40–120KB，分两次请求反而更慢
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) { if(bos.size()+n>4*1024*1024)throw new java.io.IOException("Image too large");bos.write(buf, 0, n); }
            in.close();
            byte[] data = bos.toByteArray();

            Bitmap bmp = decodeBytes(data, reqW);
            if (bmp != null) saveDiskBytes(url, data);
            if (bmp == null) {
                android.util.Log.w("HGIMG", "decode fail len=" + data.length
                        + " magic=" + hex(data, 16));
            }
            return bmp;
        } catch (Throwable t) {
            android.util.Log.w("HGIMG", "net fail " + t.getClass().getSimpleName()
                    + " " + t.getMessage() + " url=" + url);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String hex(byte[] d, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, d.length); i++) {
            sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16))
              .append(Character.forDigit(d[i] & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 封面是 HEIC（服务端只给这一个格式，改后缀签名会失效）。
     * 先走 BitmapFactory；失败再用 ImageDecoder —— 后者在部分机型上
     * 走的是更完整的 HEIF 解码通道，两者互补。
     */
    private static Bitmap decodeBytes(byte[] data, int reqW) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            if (reqW > 0 && o.outWidth > 0) {
                while (o.outWidth / (sample * 2) >= reqW) sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            o2.inDither = false;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            if (b != null) return b;
        } catch (Throwable ignored) {
        }

        if (android.os.Build.VERSION.SDK_INT >= 28) return Api28.decode(data,reqW);

        return null;
    }

    /** Keep new platform types out of the class verified on Android 6/7 TVs. */
    private static final class Api28 {
        static Bitmap decode(byte[] data,int reqW){
            try {
                android.graphics.ImageDecoder.Source src =
                        android.graphics.ImageDecoder.createSource(
                                java.nio.ByteBuffer.wrap(data));
                return android.graphics.ImageDecoder.decodeBitmap(src,
                        (decoder, info, source) -> {
                            int w = info.getSize().getWidth();
                            int h = info.getSize().getHeight();
                            if (reqW > 0 && w > reqW * 2) {
                                float s = (float) reqW / w;
                                decoder.setTargetSize(reqW, Math.max(1, (int) (h * s)));
                            }
                            decoder.setAllocator(
                                    android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                            decoder.setMemorySizePolicy(
                                    android.graphics.ImageDecoder.MEMORY_POLICY_LOW_RAM);
                        });
            } catch (Throwable t) {
                android.util.Log.w("HGIMG", "ImageDecoder fail " + t.getClass().getSimpleName()
                        + " " + t.getMessage());
            }
            return null;
        }
    }

    // ------------------------------------------------------------ 磁盘

    private static File dir() {
        File d = new File(App.ctx().getCacheDir(), "img");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String md5(String s) {
        try {
            byte[] r = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : r) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static File diskFile(String url) {
        String k = md5(url.replace("http://", "").replace("https://", ""));
        return new File(dir(), k);
    }

    /** 图片缓存保存服务端原图，不把卡片尺寸的降采样图片写回。 */
    private static void saveDiskBytes(String url, byte[] data) {
        File f = diskFile(url);
        File tmp = new File(f.getAbsolutePath() + ".t");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
        } catch (Throwable ignored) { tmp.delete(); return; }
        if (!tmp.renameTo(f)) tmp.delete();
        trimAsync();
    }

    private static volatile long sLastTrim;

    private static void trimAsync() {
        long now = System.currentTimeMillis();
        if (now - sLastTrim < 30000) return;
        sLastTrim = now;
        App.io().execute(() -> {
            File[] fs = dir().listFiles();
            if (fs == null || fs.length == 0) return;
            long total = 0;
            for (File f : fs) total += f.length();
            if (total <= DISK_MAX) return;
            Arrays.sort(fs, (a,b)->Long.compare(a.lastModified(),b.lastModified()));
            for (File f : fs) {
                if (total <= DISK_MAX) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        });
    }

    public static void trimMemory(int level){
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level>=android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)MEM.evictAll();
        else if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)MEM.trimToSize(MEM.maxSize()/2);
    }

    public static void clearMemory() {
        MEM.evictAll();
    }

    public static void clearDisk() {
        File[] fs = dir().listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    public static String stat() {
        File[] fs = dir().listFiles();
        long total = 0;
        if (fs != null) for (File f : fs) total += f.length();
        return "图片缓存 " + (total / 1024 / 1024) + " MB · 内存图片 " + MEM.snapshot().size() + " 张（" + (MEM.size() / 1024) + " MB）";
    }
}
