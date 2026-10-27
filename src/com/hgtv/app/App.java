package com.hgtv.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 全局：设备身份、线程池、偏好设置。TV 端不依赖任何外部服务。 */
public final class App extends Application {

    private static App sInst;
    private static ExecutorService sPool;
    private static ExecutorService sIo;
    private SharedPreferences mPrefs;

    /** 设备身份（内容接口不需要签名，只需稳定的 device_id / iid / cdid） */
    public static String deviceId;
    public static String iid;
    public static String cdid;

    public static final String UA =
            "com.phoenix.read/74132 (Linux; U; Android 13; zh_CN; 22041211AC; "
                    + "Build/TKQ1.220807.001;tt-ok/3.12.13.20)";

    @Override
    public void onCreate() {
        super.onCreate();
        sInst = this;
        sPool = Executors.newFixedThreadPool(3);
        // IO 线程池：图片/接口/缓存读写。TV 盒子 CPU 弱，线程数不宜多，
        // 4 条足够覆盖"首页 3 个请求 + 若干封面"，再多只会互相抢 CPU。
        sIo = Executors.newFixedThreadPool(4);
        mPrefs = getSharedPreferences("hgtv", Context.MODE_PRIVATE);

        // 关键：cdid / device_id / iid 必须是**同一个值**（昨天验证可用的原始写法）。
        // 拆成 UUID + 两个不同随机数会被服务端当成异常客户端。
        deviceId = mPrefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = String.valueOf(1000000000000000L
                    + (long) (Math.random() * 8999999999999999L));
            mPrefs.edit().putString("device_id", deviceId).apply();
        }
        iid = deviceId;
        cdid = deviceId;
        com.hgtv.app.media.PlaybackCompatibility.initialize();
    }

    @Override public void onTrimMemory(int level){super.onTrimMemory(level);com.hgtv.app.img.ImageLoader.trimMemory(level);}
    @Override public void onLowMemory(){super.onLowMemory();com.hgtv.app.img.ImageLoader.clearMemory();}

    public static Context ctx() { return sInst; }

    public static ExecutorService pool() { return sPool; }

    /** IO 密集任务（网络、磁盘）。与 pool 分开，避免下载把接口请求饿死。 */
    public static ExecutorService io() { return sIo; }

    public static SharedPreferences prefs() { return sInst.mPrefs; }
}
