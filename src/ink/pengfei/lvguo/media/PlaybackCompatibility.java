package ink.pengfei.lvguo.media;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import ink.pengfei.lvguo.App;

/**
 * 每台设备的解码策略：先按能力表筛一遍，再用真实播放结果确认。
 *
 * 片源只有 H.265（服务端不提供 H.264），所以兼容性靠一条逐级降级的「解码阶梯」：
 * <pre>
 *   0 Media3 硬件解码（全清晰度）
 *   1 Media3 硬件解码，≤720P
 * </pre>
 * 仅尝试硬件解码；硬件不支持时降低到 720P 后明确报错，不切软件解码。
 * 只有「解码类」故障才往下走一级；网络故障不降级。成功出画的那一级会被记住，
 * 后面每一集直接从这一级起播，不会每集都先失败一次。
 */
public final class PlaybackCompatibility {
    private PlaybackCompatibility(){}
    public static final int RUNG_MEDIA3=0, RUNG_MEDIA3_720=1, RUNG_LAST=RUNG_MEDIA3_720;

    private static String profile(){return Build.FINGERPRINT+":media3-1.5.1-v22-hw-only-audio-clock";}
    public static void initialize(){
        // 默认清晰度 1080P：片源全档 HEVC，解码阶梯会在真解不动时自动降级。
        // 旧版本曾把所有设备一次性强制成 720P（720p-default-v1，并非用户选择），这里纠正一次。
        if(!"1080p-default-v2".equals(App.prefs().getString("quality_default_profile","")))
            App.prefs().edit().putString("quality","1080p").putString("quality_default_profile","1080p-default-v2").apply();
        String current=profile();
        if(current.equals(App.prefs().getString("decode_profile",""))){
            // 老版本升级上来：只补上解码阶梯的起点，不动用户选过的清晰度。
            if(!App.prefs().contains("decode_rung"))App.prefs().edit().putInt("decode_rung",RUNG_MEDIA3).apply();
            return;
        }
        boolean supported=false,hardware=false;
        try{for(MediaCodecInfo codec:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(codec.isEncoder() || !isHardware(codec))continue;
            for(String type:codec.getSupportedTypes())if("video/hevc".equalsIgnoreCase(type))try{
                hardware=true;
                MediaCodecInfo.VideoCapabilities video=codec.getCapabilitiesForType(type).getVideoCapabilities();
                if(video!=null && video.areSizeAndRateSupported(1080,1920,30))supported=true;
            }catch(Exception ignored){}
        }}catch(Exception ignored){}
        // Use Media3 as the only video backend because the platform MediaPlayer does not
        // expose a public hardware-only decoder selector. Its HEVC selector filters software.
        // 不动 quality：默认画质由上面的 720p-default 迁移负责，这里换解码档案不能覆盖用户选过的清晰度。
        App.prefs().edit().putString("decode_profile",current).putInt("decode_1080",0).putInt("decode_rung",RUNG_MEDIA3).apply();
        PlaybackDiagnostics.event("capability_screen",(hardware?"hardware HEVC advertised":"no hardware HEVC advertised")+"; "+(supported?"1080P candidate":"default 720P")+"; Media3 hardware-only video decode; software decode disabled");
    }

    /** 软解码器的名字特征（API 29 以前没有 isSoftwareOnly）。 */
    public static boolean isSoftware(String name){
        if(name==null)return false;
        String n=name.toLowerCase(java.util.Locale.US);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.") || n.startsWith("c2.ffmpeg.") || n.contains(".sw.") || n.endsWith(".sw");
    }

    /** Uses the platform software flag where available, with codec-name detection on older TVs. */
    public static boolean isSoftware(MediaCodecInfo codec){
        if(codec==null)return true;
        if(Build.VERSION.SDK_INT>=29)try{return codec.isSoftwareOnly();}catch(RuntimeException ignored){}
        return isSoftware(codec.getName());
    }

    /** Reject unknown and software codecs where the platform exposes hardware classification. */
    public static boolean isHardware(MediaCodecInfo codec){
        if(codec==null || isSoftware(codec))return false;
        if(Build.VERSION.SDK_INT>=29)try{return codec.isHardwareAccelerated();}catch(RuntimeException ignored){}
        return true;
    }

    /** 真实出画确认：清掉所有临时降级，立刻回到全清晰度。 */
    public static void confirmed(){
        boolean degraded=App.prefs().getInt("decode_1080",0)!=1 || App.prefs().getInt("decode_rung",RUNG_MEDIA3)!=RUNG_MEDIA3;
        if(degraded){
            App.prefs().edit().putInt("decode_1080",1).putInt("decode_rung",RUNG_MEDIA3).remove("decode_rung_at").remove("decode_1080_at").apply();
            PlaybackDiagnostics.event("1080_confirmed","real rendered playback; degradation cleared");
        }
    }
    /** 1080P 失败只是临时标记（FAIL_TTL 内封顶 720P），过期自动重新尝试；不改用户选的清晰度。 */
    public static int maxShortSide(){
        if(App.prefs().getInt("decode_1080",0)>=0)return 1080;
        if(System.currentTimeMillis()-App.prefs().getLong("decode_1080_at",0)>FAIL_TTL_MS){
            App.prefs().edit().putInt("decode_1080",0).remove("decode_1080_at").apply();
            return 1080;
        }
        return 720;
    }
    public static void failed(){
        App.prefs().edit().putInt("decode_1080",-1).putLong("decode_1080_at",System.currentTimeMillis()).apply();
        PlaybackDiagnostics.event("1080_unavailable","compatibility playback failed; capped to 720P temporarily");
    }
    private static final long FAIL_TTL_MS=30L*60*1000;

    /** 起播用哪一级（上次在这台设备上稳定出画的那一级）。 */
    public static int startRung(){
        int rung=Math.max(0,Math.min(RUNG_LAST,App.prefs().getInt("decode_rung",RUNG_MEDIA3)));
        // 降级只是临时的：某一集偶发解码失败不该把整台设备永久锁在 720P，过期后重新探测
        if(rung>RUNG_MEDIA3 && System.currentTimeMillis()-App.prefs().getLong("decode_rung_at",0)>RUNG_TTL_MS){
            App.prefs().edit().putInt("decode_rung",RUNG_MEDIA3).apply();
            PlaybackDiagnostics.event("decode_rung_expired",label(rung));
            return RUNG_MEDIA3;
        }
        return rung;
    }
    // 偶发解码失败只临时降级 30 分钟（原 3 天会把能解 1080P 的设备长期锁在 720P）
    private static final long RUNG_TTL_MS=30L*60*1000;
    /** 某一级真实出画且稳定播放后记住它；只往「更保守」的方向记，避免来回试错。 */
    public static void rungWorked(int rung){
        if(rung>startRung()){App.prefs().edit().putInt("decode_rung",rung).putLong("decode_rung_at",System.currentTimeMillis()).apply();PlaybackDiagnostics.event("decode_rung_saved",label(rung));}
    }
    /** 设置页「重新检测」：回到第 0 级重新探测。 */
    public static void reset(){
        App.prefs().edit().putInt("decode_rung",RUNG_MEDIA3).putInt("decode_1080",0).apply();
        PlaybackDiagnostics.event("decode_reset",label(startRung()));
    }
    /** 该级的清晰度上限（短边像素）。 */
    public static int ceiling(int rung){return rung>=RUNG_MEDIA3_720?720:1080;}
    /** 给用户看的名字（设置页 / 诊断）。 */
    public static String label(int rung){
        switch(rung){
            case RUNG_MEDIA3_720: return "Media3 硬解 · 最高 720P";
            default: return "Media3 硬解";
        }
    }
}
