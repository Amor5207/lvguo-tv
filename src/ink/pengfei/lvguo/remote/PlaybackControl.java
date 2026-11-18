package ink.pengfei.lvguo.remote;

import ink.pengfei.lvguo.PlaybackHostActivity;
import android.os.*;
import java.lang.ref.WeakReference;
import org.json.*;
import java.util.concurrent.*;

public final class PlaybackControl {
    private static WeakReference<PlaybackHostActivity> activity=new WeakReference<>(null);
    private static String title="未在播放",seriesId="",cover="",quality="720P";
    private static int episode,position,duration,buffered;
    private static boolean paused,loading,ended;
    private static long version,loadingSince;
    private static String errorTitle="",errorMessage="";
    private static float speed=1;
    /** 弹幕设置变了（手机端修改）：通知正在播放的画面立即生效。 */
    public static void danmakuChanged(){PlaybackHostActivity p=activity.get();if(p!=null)p.danmakuSettingsChanged();}
    public static synchronized void attach(PlaybackHostActivity player){activity=new WeakReference<>(player);seriesId=player.playbackIntent().getStringExtra(PlaybackHostActivity.EXTRA_SERIES);title="正在载入剧集";episode=position=duration=buffered=0;paused=ended=false;loading=true;loadingSince=SystemClock.elapsedRealtime();version++;}
    public static synchronized void detach(PlaybackHostActivity player){if(activity.get()==player){activity.clear();loading=false;paused=true;}}
    public static synchronized void starting(String id,String name,int ep,String image){seriesId=id;title=name;cover=image==null?"":image;episode=ep;position=duration=buffered=0;paused=ended=false;loading=true;loadingSince=SystemClock.elapsedRealtime();errorTitle=errorMessage="";version++;}
    public static synchronized void paused(boolean value){paused=value;}
    public static synchronized void loading(boolean value){if(value&&!loading)loadingSince=SystemClock.elapsedRealtime();loading=value;if(!value)loadingSince=0;}
    public static synchronized void ended(boolean value){ended=value;}
    /** 电视端播放出错时同步给手机（空字符串 = 无错误）。 */
    public static synchronized void error(String title,String message){errorTitle=title==null?"":title;errorMessage=message==null?"":message;if(!errorTitle.isEmpty())loading=false;}
    public static synchronized void buffered(int percent){buffered=percent;}
    public static synchronized void settings(float rate,String definition){speed=rate;quality=definition;}
    public static synchronized void update(String name,int ep,int pos,int dur,boolean pause){title=name;episode=ep;position=pos;duration=dur;paused=pause;}
    public static JSONObject command(String action,JSONObject body)throws Exception {
        final long expected=body.optLong("version",-1);
        final int target=body.optInt("position",0);
        FutureTask<JSONObject> task=new FutureTask<>(()->{
            PlaybackHostActivity player;
            synchronized(PlaybackControl.class){
                player=activity.get();
                if(player==null || player.isFinishing())throw new IllegalStateException("电视当前没有正在播放的剧集");
                if(expected>=0 && expected!=version)throw new IllegalStateException("电视已切换剧集，请同步后再操作");
            }
            if(action.equals("speed")||action.equals("quality")||action.equals("speedLock")||action.equals("autoplay"))player.remoteSetting(action,body);
            else player.remoteCommand(action,target);
            return new JSONObject().put("ok",true).put("status",status());
        });
        new Handler(Looper.getMainLooper()).post(task);
        try{return task.get(3,TimeUnit.SECONDS);}
        catch(ExecutionException error){throw new IllegalStateException(error.getCause().getMessage());}
        catch(TimeoutException error){task.cancel(false);throw new IllegalStateException("电视正在处理，请稍后再试");}
    }
    public static synchronized JSONObject status() throws JSONException {
        PlaybackHostActivity player=activity.get();
        return new JSONObject().put("error",errorTitle).put("errorMessage",errorMessage).put("id",seriesId).put("title",title).put("cover",ink.pengfei.lvguo.img.ImageUrls.compatible(cover)).put("episode",episode).put("position",position).put("duration",duration).put("buffered",buffered).put("speed",speed).put("quality",quality).put("qualityKey",player==null?"720p":player.remoteQualityKey()).put("qualities",player==null?new JSONArray():player.remoteQualities()).put("speedLocked",player!=null&&player.remoteSpeedLocked()).put("autoplay",ink.pengfei.lvguo.App.prefs().getBoolean("autoplay_next",true)).put("version",version).put("ended",ended).put("paused",paused).put("loading",loading).put("loadingElapsed",loading?Math.max(0,SystemClock.elapsedRealtime()-loadingSince):0).put("playing",player!=null);
    }
}
