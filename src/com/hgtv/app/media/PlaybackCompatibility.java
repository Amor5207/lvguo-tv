package com.hgtv.app.media;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import com.hgtv.app.App;

/** Capability screening followed by confirmation from real rendered playback. */
public final class PlaybackCompatibility {
    private PlaybackCompatibility(){}
    private static String profile(){return Build.FINGERPRINT+":media3-1.5.1-v20";}
    public static void initialize(){
        String current=profile();
        if(current.equals(App.prefs().getString("decode_profile","")))return;
        boolean supported=false;
        try{for(MediaCodecInfo codec:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(codec.isEncoder())continue;
            for(String type:codec.getSupportedTypes())if("video/hevc".equalsIgnoreCase(type))try{
                MediaCodecInfo.VideoCapabilities video=codec.getCapabilitiesForType(type).getVideoCapabilities();
                if(video!=null && video.areSizeAndRateSupported(1080,1920,30))supported=true;
            }catch(Exception ignored){}
        }}catch(Exception ignored){}
        App.prefs().edit().putString("decode_profile",current).putInt("decode_1080",0)
            .putString("quality",supported?"1080p":"720p").apply();
        PlaybackDiagnostics.event("capability_screen",supported?"1080P candidate; awaiting real playback":"default 720P; no advertised portrait 1080P/30 HEVC decoder");
    }
    public static void confirmed(){
        if(App.prefs().getInt("decode_1080",0)!=1){App.prefs().edit().putInt("decode_1080",1).apply();PlaybackDiagnostics.event("1080_confirmed","real rendered playback");}
    }
    public static int maxShortSide(){return App.prefs().getInt("decode_1080",0)<0?720:1080;}
    public static void failed(){
        App.prefs().edit().putInt("decode_1080",-1).putString("quality","720p").apply();
        PlaybackDiagnostics.event("1080_unavailable","compatibility playback failed; default changed to 720P");
    }
}
