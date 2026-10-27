package com.hgtv.app.media;

import android.media.*;
import android.os.Build;
import com.hgtv.app.App;
import com.hgtv.app.model.Gear;
import org.json.*;

/** Local-only bounded diagnostic snapshot. No media URLs, keys or device IDs. */
public final class PlaybackDiagnostics {
    private PlaybackDiagnostics(){}
    public static synchronized void event(String stage,String message){
        try{
            JSONArray events=new JSONArray(App.prefs().getString("playback_diagnostics","[]"));
            events.put(new JSONObject().put("time",System.currentTimeMillis()).put("stage",stage).put("detail",message==null?"":message.replaceAll("https?://[^\\s]+","[media-url]")));
            JSONArray bounded=new JSONArray();for(int i=Math.max(0,events.length()-24);i<events.length();i++)bounded.put(events.get(i));
            App.prefs().edit().putString("playback_diagnostics",bounded.toString()).apply();
        }catch(Exception ignored){}
    }
    public static void source(Gear gear,String engine){if(gear!=null)event("source",engine+" "+gear.definition+" "+gear.width+"x"+gear.height+" codec="+gear.codec+" fps="+gear.fps);}
    public static JSONObject snapshot()throws JSONException{
        int appVersionCode=0;
        try{
            android.content.pm.PackageInfo packageInfo=App.ctx().getPackageManager().getPackageInfo(App.ctx().getPackageName(),0);
            appVersionCode=Build.VERSION.SDK_INT>=28?(int)packageInfo.getLongVersionCode():packageInfo.versionCode;
        }catch(Exception ignored){}
        JSONObject result=new JSONObject().put("appVersionCode",appVersionCode).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT);
        JSONArray codecs=new JSONArray();
        try{for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(info.isEncoder())continue;for(String type:info.getSupportedTypes())if("video/hevc".equalsIgnoreCase(type)){
                JSONObject codec=new JSONObject().put("name",info.getName());try{
                    MediaCodecInfo.CodecCapabilities cap=info.getCapabilitiesForType(type);MediaCodecInfo.VideoCapabilities video=cap.getVideoCapabilities();
                    if(video!=null)codec.put("landscape1080",video.isSizeSupported(1920,1080)).put("portrait1080",video.isSizeSupported(1080,1920));
                    JSONArray profiles=new JSONArray();for(MediaCodecInfo.CodecProfileLevel profile:cap.profileLevels)profiles.put(new JSONObject().put("profile",profile.profile).put("level",profile.level));codec.put("profiles",profiles);
                }catch(Exception error){codec.put("capabilityError",error.getClass().getSimpleName());}codecs.put(codec);
            }
        }}catch(Exception error){result.put("codecRegistryError",error.getClass().getSimpleName());}
        result.put("hevcDecoders",codecs).put("confirmed1080",App.prefs().getInt("decode_1080",0)).put("maximumQuality",PlaybackCompatibility.maxShortSide()).put("events",new JSONArray(App.prefs().getString("playback_diagnostics","[]")));return result;
    }
}
