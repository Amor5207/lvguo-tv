package com.hgtv.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.hgtv.app.media.VideoCache;
import com.hgtv.app.media.VideoDecoderSupport;
import com.hgtv.app.media.StreamingSource;
import com.hgtv.app.model.Episode;
import com.hgtv.app.model.Gear;
import com.hgtv.app.net.HgApi;
import com.hgtv.app.net.Repo;
import com.hgtv.app.store.Store;
import com.hgtv.app.ui.IconView;
import com.hgtv.app.ui.Ui;
import android.media.MediaPlayer;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** TV playback with bounded streaming buffers and one episode of opening-data lookahead. */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_SERIES="series_id", EXTRA_INDEX="index",
            EXTRA_TITLE="title", EXTRA_COVER="cover", EXTRA_VID="vid", EXTRA_EPISODE_NO="episode_no";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService preload=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, FutureTask<List<Gear>>> models=new ConcurrentHashMap<>();
    private final LinkedHashMap<String,List<Gear>> qualityChoices=new LinkedHashMap<>();
    private final ConcurrentHashMap<String, Clip> clips=new ConcurrentHashMap<>();
    private Future<?> preloadTask;
    private final Object warmLock=new Object();
    private StreamingSource warmSource;
    private String warmVid;
    private Gear warmGear;
    private volatile StreamingSource openingSource;
    private long playbackStartedAt;
    private boolean firstFrame;
    private boolean startupRewound,startupCorrection;
    private int startupPosition;
    private long lastVideoSyncRecoveryAt,videoLagWindowStartedAt,lastMetricsSampleAt,lastMetricsDataAt,lastMetricsLogAt,lastMetricsPosition;
    private int videoLagEvents,lastMetricsFrames=-1,lastMetricsDropped=-1;
    private Runnable startMonitor;
    private volatile int sequence;
    private FrameLayout root, loading, episodeOverlay;
    private LinearLayout top, deck, episodeSheet;
    private TextView title, subtitle, time, readyHint, loadingText, episodeButton;
    private FrameLayout centerToggle;
    private IconView centerToggleIcon;
    private SeekBar timeline;
    private TextView speedButton,qualityButton,previousButton,nextButton,playButton,rewindButton,forwardButton,loadingDetail;
    private ProgressBar loadingProgress;
    private int bufferedPercent;
    private boolean episodeChromeBefore;
    private StreamingSource measuredSource;
    private long measuredBytes,measuredAt;
    private volatile long openingReceivedBytes;
    private android.app.Dialog settingsDialog;
    private android.app.Dialog commentsDialog;
    private boolean commentsOpen,ended,allFinished,buffering,speedLocked;
    private float playbackSpeed=1f, appliedSpeed=1f;
    private int restorePosition,seekPreview=-1,seekVersion,pendingResumePosition;
    private long progressSavedAt;
    private boolean restorePaused,keepRate;
    private int lastEpisodeTapKey=KeyEvent.KEYCODE_UNKNOWN;
    private long lastEpisodeTapAt;
    private GridView episodeGrid;
    private int lastControlId,episodePage;
    private TextView favoriteButton,commentButton;
    private final List<TextView> rangeTabs=new ArrayList<>();
    private final Runnable commitSeek=()->commitPendingSeek();
    private SurfaceView surface;
    private MediaPlayer player;
    private StreamingSource stream;
    private StreamingSource pendingStream;
    private boolean surfaceReady, prepared, paused, chromeVisible, failed, foreground, resumeAfterPause;
    private volatile boolean destroyed;
    private String seriesId, seriesTitle, prefer;
    private List<Episode> episodes;
    private Repo.Detail seriesDetail;
    private int index;
    private boolean degraded;
    private File pendingFile;
    private Gear activeGear;
    private int pendingSequence;
    private final List<TextView> episodeCells=new ArrayList<>();
    private final List<View> controls=new ArrayList<>();
    private final Runnable hideTask=()-> { if (!overlayOpen()) hideChrome(); };
    private static final class Clip {
        final File file; final Gear gear;
        Clip(File f,Gear g) { file=f; gear=g; }
        boolean exists() { return file.isFile() && file.length()>1024; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        com.hgtv.app.media.PlaybackWarmup.cancel();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        seriesId=getIntent().getStringExtra(EXTRA_SERIES);
        seriesTitle=getIntent().getStringExtra(EXTRA_TITLE);
        index=getIntent().getIntExtra(EXTRA_INDEX,0);
        pendingResumePosition=getIntent().getIntExtra("position_ms",0);
        prefer=App.prefs().getString("quality","720p");if(prefer==null)prefer="720p";

        speedLocked=App.prefs().getBoolean("speed_lock",false);
        playbackSpeed=speedLocked?App.prefs().getFloat("play_speed",1f):1f;
        buildUi();
        com.hgtv.app.remote.PlaybackControl.attach(this);
        loadSeries();
    }

    private GradientDrawable scrim(boolean fromTop) {
        return new GradientDrawable(fromTop ? GradientDrawable.Orientation.TOP_BOTTOM
                : GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF5000000,0xBB000000,0x00000000});
    }
    private TextView text(String value,int size,int color,boolean bold) {
        return Ui.text(this,value,size,color,bold);
    }
    private void buildUi() {
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusableInTouchMode(true);
        surface=new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.setOnClickListener(v->{ if(chromeVisible) hideChrome(); else revealControls(); });
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1));

        top=Ui.column(this);
        top.setPadding(dp(28),dp(18),dp(28),dp(22));
        top.setBackground(scrim(true));
        title=text(seriesTitle,20,Ui.TEXT,true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title,Ui.lp(-1,-2));
        subtitle=text("正在准备播放",12,Ui.TEXT_2,false);
        LinearLayout.LayoutParams sub=Ui.lp(-1,-2); sub.topMargin=dp(8);
        top.addView(subtitle,sub);
        root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

        deck=Ui.column(this);
        deck.setPadding(dp(28),dp(20),dp(28),dp(16));
        deck.setBackground(scrim(false));deck.setClipChildren(false);deck.setClipToPadding(false);
        LinearLayout info=Ui.row(this);
        time=text("00:00 / 00:00",13,Ui.TEXT,true);
        time.setTypeface(Typeface.MONOSPACE);
        info.addView(time);
        info.addView(new View(this),Ui.lp(0,1,1));
        readyHint=text("",12,Ui.TEXT_2,false);
        info.addView(readyHint);
        deck.addView(info,Ui.lp(-1,-2));

        timeline=new SeekBar(this);
        timeline.setMax(1000);
        timeline.setPadding(0,0,0,0);
        timeline.setFocusable(false);timeline.setFocusableInTouchMode(false);timeline.setId(View.generateViewId());
        timeline.setProgressTintList(ColorStateList.valueOf(Ui.PRIMARY));
        timeline.setProgressBackgroundTintList(ColorStateList.valueOf(0x44FFFFFF));
        timeline.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { ui.removeCallbacks(hideTask); }
            public void onStopTrackingTouch(SeekBar bar) { commitPendingSeek();scheduleHide(); }
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser) {
                if(fromUser && prepared && player!=null) {
                    try{seekPreview=(int)(player.getDuration()*value/1000L);time.setText(formatTime(seekPreview)+" / "+formatTime(player.getDuration()));}
                    catch(IllegalStateException ignored){}
                }
            }
        });
        LinearLayout.LayoutParams progress=Ui.lp(-1,dp(16)); progress.topMargin=dp(4);
        deck.addView(timeline,progress);

        LinearLayout transport=Ui.row(this);transport.setGravity(Gravity.LEFT);transport.setClipChildren(false);transport.setClipToPadding(false);
        playButton=addControl(transport,"",IconView.PAUSE,false,this::togglePause);
        previousButton=addControl(transport,"",IconView.PREVIOUS,false,()->playAt(index-1));
        nextButton=addControl(transport,"",IconView.NEXT,false,this::nextEpisode);
        rewindButton=addControl(transport,"",IconView.REWIND,false,()->seekBy(-10000));
        forwardButton=addControl(transport,"",IconView.FAST_FORWARD,false,()->seekBy(10000));
        for(TextView button:new TextView[]{previousButton,rewindButton,playButton,forwardButton,nextButton}){
            LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)button.getLayoutParams();lp.width=dp(48);lp.height=dp(44);lp.rightMargin=0;button.setMinHeight(dp(44));Ui.padding(button,13,0,13,0);button.setCompoundDrawablePadding(0);
        }
        previousButton.setContentDescription("上一集");nextButton.setContentDescription("下一集");rewindButton.setContentDescription("快退10秒");forwardButton.setContentDescription("快进10秒");
        android.widget.HorizontalScrollView transportScroll=new android.widget.HorizontalScrollView(this);
        transportScroll.setHorizontalScrollBarEnabled(false);transportScroll.setFillViewport(true);transportScroll.setFocusable(false);transportScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        transportScroll.addView(transport,new android.widget.HorizontalScrollView.LayoutParams(-2,-1));
        LinearLayout.LayoutParams transportLp=Ui.lp(-1,dp(44));transportLp.topMargin=dp(8);deck.addView(transportScroll,transportLp);
        LinearLayout tools=transport;
        episodeButton=addControl(tools,"选集",IconView.EPISODES,false,this::openEpisodes);
        speedButton=addControl(tools,"倍速 1.0×",IconView.SPEED,false,this::openSpeed);
        qualityButton=addControl(tools,"720P",IconView.QUALITY,false,this::openQuality);
        commentButton=addControl(tools,"评论",IconView.COMMENT,false,this::openComments);
        favoriteButton=addControl(tools,Store.isFavorite(seriesId)?"已追剧":"追剧",IconView.STAR,false,()->{
            String vid=episodes==null?null:episodes.get(index).vid;
            com.hgtv.app.ui.widget.FavoriteActions.toggle(this,seriesId,seriesTitle,getIntent().getStringExtra(EXTRA_COVER),vid,()->{
                favoriteButton.setText(Store.isFavorite(seriesId)?"已追剧":"追剧");
                toast(Store.isFavorite(seriesId)?"已加入追剧":"已取消追剧");
            });
        });
        updateSettingsLabels();
        root.addView(deck,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));

        centerToggle=new FrameLayout(this);
        centerToggle.setFocusable(true);centerToggle.setFocusableInTouchMode(true);centerToggle.setClickable(true);centerToggle.setContentDescription("播放或暂停");
        centerToggle.setBackground(Ui.roundStroke(this,0x680A100E,10,0x55FFFFFF,1));
        centerToggle.setElevation(dp(4));
        centerToggleIcon=new IconView(this,IconView.PAUSE,30);centerToggleIcon.setColor(Ui.TEXT);
        centerToggle.addView(centerToggleIcon,new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER));
        centerToggle.setOnClickListener(v->{togglePause();scheduleHide();});
        centerToggle.setOnFocusChangeListener((v,has)->{
            centerToggle.setBackground(Ui.roundStroke(this,has?0x8C17261D:0x680A100E,10,has?Ui.PRIMARY:0x55FFFFFF,has?2f:1));
            centerToggleIcon.setColor(has?Ui.PRIMARY:Ui.TEXT);
            centerToggle.animate().scaleX(has?1.04f:1f).scaleY(has?1.04f:1f).setDuration(120).start();
            scheduleHide();
        });
        centerToggle.setVisibility(View.GONE);
        root.addView(centerToggle,new FrameLayout.LayoutParams(dp(72),dp(60),Gravity.CENTER));

        loading=new FrameLayout(this);loading.setVisibility(View.GONE);
        loading.setBackgroundColor(0x33000000);
        LinearLayout loadCard=Ui.column(this); loadCard.setGravity(Gravity.CENTER);
        loadingText=text("正在准备好剧…",16,Ui.TEXT,true);
        loadingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams loadingLabel=Ui.lp(-2,-2); loadingLabel.topMargin=dp(18);
        loadCard.addView(loadingText,loadingLabel);
        loadingProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);loadingProgress.setMax(100);loadingProgress.setProgressTintList(ColorStateList.valueOf(Ui.PRIMARY));loadingProgress.setIndeterminateTintList(ColorStateList.valueOf(Ui.PRIMARY));
        LinearLayout.LayoutParams meterLp=Ui.lp(dp(220),dp(4));meterLp.topMargin=dp(14);loadCard.addView(loadingProgress,meterLp);
        loadingDetail=text("正在获取播放信息",12,Ui.TEXT_2,false);loadingDetail.setGravity(Gravity.CENTER);LinearLayout.LayoutParams detailLp=Ui.lp(-2,-2);detailLp.topMargin=dp(10);loadCard.addView(loadingDetail,detailLp);
        loading.addView(loadCard,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        loading.setOnClickListener(v->{ if(failed) playAt(index); });
        root.addView(loading,new FrameLayout.LayoutParams(-1,-1));
        centerToggle.bringToFront();

        episodeOverlay=new FrameLayout(this);
        episodeOverlay.setBackgroundColor(0x99000000);
        episodeOverlay.setVisibility(View.GONE);
        episodeOverlay.setOnClickListener(v->closeEpisodes());
        root.addView(episodeOverlay,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        revealControls();
    }
    private TextView addControl(LinearLayout row,String label,int icon,boolean primary,Runnable action) {
        TextView button=Ui.action(this,label,icon,primary);
        button.setId(View.generateViewId());
        Ui.padding(button,12,0,12,0);button.setMinHeight(dp(36));button.setTextSize(13);
        button.setOnClickListener(v->{ action.run(); scheduleHide(); });
        LinearLayout.LayoutParams lp=Ui.lp(-2,dp(36)); lp.rightMargin=dp(8);
        if(row.getChildCount()>0){View gap=new View(this);gap.setFocusable(false);row.addView(gap,new LinearLayout.LayoutParams(0,1,1f));}
        lp.rightMargin=0;row.addView(button,lp); controls.add(button);
        button.setOnFocusChangeListener((v,focus)->{
            if(focus){lastControlId=v.getId();ui.removeCallbacks(hideTask);}
            Ui.buttonState(button,focus,false);Ui.icon(button,button==playButton?((paused||ended)?IconView.PLAY:IconView.PAUSE):icon,button.getCurrentTextColor(),20);if(button.getText().length()==0)button.setCompoundDrawablePadding(0);
            scheduleHide();
        });
        return button;
    }
    private void showChrome() {
        boolean opening=!chromeVisible;
        chromeVisible=true;
        deck.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        top.setVisibility(View.VISIBLE); deck.setVisibility(View.VISIBLE);centerToggle.setVisibility(loadingRequested?View.INVISIBLE:View.VISIBLE);
        top.animate().cancel(); deck.animate().cancel();
        top.animate().alpha(1).setDuration(160).start();
        deck.animate().alpha(1).setDuration(160).start();
        centerToggle.animate().cancel();if(opening){centerToggle.setAlpha(0f);centerToggle.animate().alpha(1f).setDuration(160).start();}else centerToggle.setAlpha(1f);
        scheduleHide();
    }
    private void revealControls(){showChrome();refreshPauseState();if(centerToggle.getVisibility()==View.VISIBLE)centerToggle.requestFocus();else playButton.requestFocus();}
    private void restoreChrome(boolean visible,View focus){
        if(visible){showChrome();if(focus!=null && (controls.contains(focus)||focus==centerToggle))focus.requestFocus();else centerToggle.requestFocus();}
        else hideChrome();
    }
    private void scheduleHide() {
        ui.removeCallbacks(hideTask);
        if(chromeVisible && !overlayOpen()) ui.postDelayed(hideTask,4500);
    }
    private void hideChrome() {
        chromeVisible=false; ui.removeCallbacks(hideTask);
        root.requestFocus();
        deck.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        top.animate().alpha(0).setDuration(180).withEndAction(()->{
            if(!chromeVisible) top.setVisibility(View.INVISIBLE);
        }).start();
        deck.animate().alpha(0).setDuration(180).withEndAction(()->{
            if(!chromeVisible) deck.setVisibility(View.INVISIBLE);
        }).start();
        centerToggle.animate().alpha(0).setDuration(180).withEndAction(()->{
            if(!chromeVisible)centerToggle.setVisibility(View.INVISIBLE);
        }).start();
    }

    private void loadSeries() {
        final String id=seriesId,vid=getIntent().getStringExtra(EXTRA_VID);
        final int selected=index,episodeNo=getIntent().getIntExtra(EXTRA_EPISODE_NO,1),request=++sequence;
        setLoading(true,"正在载入剧集…");
        App.io().execute(()->{
            try {
                Repo.Detail detail=Repo.detail(id);
                ui.post(()->{
                    if(destroyed || request!=sequence)return;
                    seriesDetail=detail;episodes=detail.episodes;
                    if(seriesTitle==null || seriesTitle.isEmpty())seriesTitle=detail.title;
                    index=selected;
                    if(vid!=null)for(int n=0;n<episodes.size();n++)if(vid.equals(episodes.get(n).vid)){index=n;break;}
                    playAt(Math.max(0,Math.min(index,episodes.size()-1)));
                });
            }catch(Exception error){ui.post(()->{
                if(destroyed || request!=sequence)return;
                if(vid!=null && !vid.isEmpty()){
                    Episode ep=new Episode();ep.vid=vid;ep.index=episodeNo;
                    episodes=new ArrayList<>();episodes.add(ep);degraded=true;playAt(0);
                }else fail(request,"剧集加载失败："+error.getMessage());
            });}
        });
    }
    private List<Gear> gearsFor(String vid) throws Exception {
        FutureTask<List<Gear>> created=new FutureTask<>(()->Repo.play(vid,HgApi.DEVICE_LEVEL_H265));
        FutureTask<List<Gear>> existing=models.putIfAbsent(vid,created);
        FutureTask<List<Gear>> task=existing==null?created:existing;
        try {
            task.run();List<Gear> result=task.get();
            synchronized(qualityChoices){qualityChoices.remove(vid);qualityChoices.put(vid,result);while(qualityChoices.size()>3)qualityChoices.remove(qualityChoices.keySet().iterator().next());}
            return result;
        } catch(ExecutionException error) {
            Throwable cause=error.getCause();
            if(cause instanceof Exception) throw (Exception)cause;
            throw new java.io.IOException("加载播放源失败",cause);
        } finally { if(task.isDone()) models.remove(vid,task); }
    }
    private Clip prepareClip(Episode episode,VideoCache.Progress progress) throws Exception {
        Gear gear=pickPlaybackGear(gearsFor(episode.vid));
        Clip hit=clips.get(episode.vid);
        if(hit!=null && hit.exists() && gear!=null && java.util.Objects.equals(hit.gear.definition,gear.definition))return hit;
        if(gear==null) throw new IllegalStateException("没有可用播放源");
        File file=VideoCache.prepare(App.ctx(),episode.vid,gear,progress);
        Clip clip=new Clip(file,gear); clips.put(episode.vid,clip);
        return clip;
    }
    private int fallbackSequence=-1,compatSequence=-1;
    private volatile int playbackCeiling=com.hgtv.app.media.PlaybackCompatibility.maxShortSide();
    private void fallback(int seq,Exception cause){
        if(seq!=sequence || destroyed)return;
        com.hgtv.app.media.PlaybackDiagnostics.event("recovery",cause.toString());
        if(fallbackSequence==seq){
            if(activeGear!=null && activeGear.shortSide()>720 && playbackCeiling>720){
                playbackCeiling=720;
                prefer="720p";
                String reason=String.valueOf(cause.getMessage());
                boolean unstableDecode=reason.contains("视频解码不稳定");
                boolean decodeFailure=unstableDecode||reason.contains("解码")||reason.contains("首帧")||reason.contains("decoder failure");
                if(decodeFailure)com.hgtv.app.media.PlaybackCompatibility.failed();
                restorePosition=prepared && player!=null && firstFrame?player.getCurrentPosition():startupPosition;
                restorePaused=paused;keepRate=true;
                android.util.Log.w("TVPlayback","High-quality decoder failed; retrying <=720P",cause);
                toast(unstableDecode?"视频解码不稳定，已切换至 720P":decodeFailure?"当前设备暂时无法解码 1080P，兼容播放也未成功，已切换至 720P":"1080P 播放暂时失败，已尝试切换至 720P");playAt(index);return;
            }
            fail(seq,"播放恢复失败："+cause.getMessage());return;
        }
        fallbackSequence=seq;compatSequence=seq;
        if(prepared && player!=null)try{restorePosition=firstFrame?player.getCurrentPosition():startupPosition;restorePaused=paused;}catch(IllegalStateException ignored){}
        firstFrame=false;startupRewound=false;startupCorrection=false;startupPosition=restorePosition;
        releasePlayer();setLoading(true,"正在切换兼容播放方式…");
        final Episode episode=episodes.get(index);
        App.pool().execute(()->{try{
            Gear gear=pickPlaybackGear(gearsFor(episode.vid));
            if(gear==null)throw new java.io.IOException("没有可用播放源");
            File cached=VideoCache.cached(App.ctx(),episode.vid,gear.definition);
            if(cached!=null){Clip clip=new Clip(cached,gear);ui.post(()->queuePlayer(clip,seq));return;}
            StreamingSource source=new StreamingSource(gear,bytes->{if(seq==sequence)openingReceivedBytes=bytes;});
            ui.post(()->{if(seq!=sequence || destroyed){source.close();return;}activeGear=gear;pendingStream=source;pendingSequence=seq;surface.post(this::attachPending);});
        }
        catch(Exception error){ui.post(()->fail(seq,"播放恢复失败："+error.getMessage()));}});
    }
    private void playAt(int target) {
        savePlaybackPosition();
        if(destroyed || isFinishing()) return;
        if(episodes==null || episodes.isEmpty()) { fail(sequence,"没有可播放的剧集"); return; }
        if(target<0 || target>=episodes.size()) {
            toast(target<0?"已经是第一集":"已经是最后一集"); return;
        }
        final boolean visibleBefore=isEpisodeOpen()?episodeChromeBefore:chromeVisible;
        final View focusedBefore=getCurrentFocus();
        closeEpisodes();
        if(!keepRate){restorePosition=Math.max(0,pendingResumePosition);pendingResumePosition=0;restorePaused=false;}
        ended=false;allFinished=false;buffering=false;bufferedPercent=0;openingReceivedBytes=0;measuredBytes=0;measuredAt=android.os.SystemClock.elapsedRealtime();measuredSource=null;seekPreview=-1;ui.removeCallbacks(commitSeek);lastEpisodeTapKey=KeyEvent.KEYCODE_UNKNOWN;lastVideoSyncRecoveryAt=0;videoLagWindowStartedAt=0;videoLagEvents=0;lastMetricsSampleAt=0;lastMetricsDataAt=0;lastMetricsLogAt=0;lastMetricsPosition=0;lastMetricsFrames=-1;lastMetricsDropped=-1;
        if(!keepRate && !speedLocked)playbackSpeed=1f;keepRate=false;
        index=target; final int seq=++sequence;
        if(commentsDialog!=null){commentsDialog.dismiss();commentsDialog=null;}
        if(preloadTask!=null) preloadTask.cancel(true);
        StreamingSource opening=openingSource;openingSource=null;if(opening!=null)opening.close();
        playbackStartedAt=android.os.SystemClock.elapsedRealtime();firstFrame=false;startupRewound=false;startupCorrection=false;startupPosition=restorePosition;progressSavedAt=0;
        releasePlayer(); pendingFile=null; paused=false; failed=false;updateSettingsLabels();
        if(pendingStream!=null){pendingStream.close();pendingStream=null;}
        Episode episode=episodes.get(index);
        loadingRequested=false;ui.removeCallbacks(delayedLoading);loading.setVisibility(View.GONE);
        com.hgtv.app.remote.PlaybackControl.starting(seriesId,seriesTitle,episode.index,getIntent().getStringExtra(EXTRA_COVER));
        title.setText(seriesTitle==null?"正在播放":seriesTitle);
        subtitle.setText("第 "+episode.index+" 集  /  "+episodes.size()+" 集    ·    "+prefer.toUpperCase(Locale.US));
        episodeButton.setText("选集");
        readyHint.setText(""); timeline.setProgress(0); time.setText("00:00 / 00:00");
        setLoading(true,"第 "+episode.index+" 集 · 准备播放");

        restoreChrome(visibleBefore,focusedBefore);
        Store.markHistory(seriesId,seriesTitle,episode.index,index,episode.vid,
                getIntent().getStringExtra(EXTRA_COVER));
        App.pool().execute(()->{
            try {
                Gear gear=pickPlaybackGear(gearsFor(episode.vid));
                com.hgtv.app.media.PlaybackDiagnostics.event("source_selected",gear==null?"none":gear.definition);
                if(seq!=sequence || destroyed)return;
                if(gear==null)throw new IllegalStateException("没有可用播放源");
                final boolean legacyHigh=android.os.Build.VERSION.SDK_INT<=28 && gear.shortSide()>720;
                File cached=VideoCache.cached(App.ctx(),episode.vid,gear.definition);
                if(cached!=null) {
                    Clip clip=new Clip(cached,gear);clips.put(episode.vid,clip);
                    ui.post(()->{if(seq==sequence){if(legacyHigh)compatSequence=seq;queuePlayer(clip,seq);}});
                } else {
                    StreamingSource warmed=null;
                    synchronized(warmLock){
                        if(episode.vid.equals(warmVid) && warmGear!=null && java.util.Objects.equals(gear.definition,warmGear.definition))warmed=warmSource;
                        else if(warmSource!=null)warmSource.close();
                        warmSource=null;warmVid=null;warmGear=null;
                    }
                    final StreamingSource source=warmed!=null?warmed:new StreamingSource(gear,bytes->{if(seq==sequence)openingReceivedBytes=bytes;});
                    android.util.Log.i("TVPreload","Opening episode="+episode.index+" warm="+(warmed!=null));
                    ui.post(()->{
                        if(seq!=sequence || destroyed){source.close();return;}
                        if(legacyHigh)compatSequence=seq;
                        activeGear=gear;pendingStream=source;pendingSequence=seq;
                        fitVideoSize(gear.width,gear.height);surface.post(this::attachPending);
                    });
                }

            } catch(Exception error) { ui.post(()->fallback(seq,error)); }
        });
    }
    private Repo.Season followingSeason(String id,Repo.Detail detail)throws Exception {
        if(detail==null || !detail.multiSeason)return null;
        Repo.Season found=null;
        for(Repo.Season season:Repo.seasons(id,detail.relatedAlbumId))
            if(season.number>detail.seasonIndex && season.episodes>0 && (found==null || season.number<found.number))found=season;
        return found;
    }
    private void scheduleWarmup(final int seq){
        ui.postDelayed(new Runnable(){public void run(){
            if(destroyed || seq!=sequence || !prepared || ended)return;
            if(!foreground || buffering || paused){ui.postDelayed(this,2000);return;}
            warmUpcoming(seq,index);
        }},5000);
    }
    private void warmUpcoming(int seq,int current) {
        if(destroyed || seq!=sequence || degraded || episodes==null) return;
        final List<Episode> list=episodes;final Repo.Detail detail=seriesDetail;final String id=seriesId;
        final boolean continueSeasons=App.prefs().getBoolean("autoplay_next",true);
        preloadTask=preload.submit(()->{
            try {
                Episode next;boolean nextSeason=current+1>=list.size();
                if(!nextSeason)next=list.get(current+1);
                else{
                    if(!continueSeasons)return;
                    Repo.Season season=followingSeason(id,detail);if(season==null)return;
                    if(seq!=sequence || destroyed || Thread.currentThread().isInterrupted())return;
                    Repo.Detail following=Repo.detail(season.id);if(following.episodes.isEmpty())return;
                    next=following.episodes.get(0);
                }
                if(seq!=sequence || destroyed || Thread.currentThread().isInterrupted())return;
                Gear gear=pickPlaybackGear(gearsFor(next.vid));
                if(gear==null || seq!=sequence || destroyed || Thread.currentThread().isInterrupted())return;
                if(android.os.Build.VERSION.SDK_INT<=28 && gear.shortSide()>720){VideoCache.prepare(App.ctx(),next.vid,gear,null);return;}
                if(VideoCache.cached(App.ctx(),next.vid,gear.definition)==null){
                    StreamingSource source=new StreamingSource(gear);
                    if(seq!=sequence || destroyed || Thread.currentThread().isInterrupted()){source.close();return;}
                    openingSource=source;
                    try{
                        source.warmStart();
                        synchronized(warmLock){
                            if(seq!=sequence || destroyed || Thread.currentThread().isInterrupted()){source.close();return;}
                            if(warmSource!=null)warmSource.close();
                            if(openingSource==source)openingSource=null;
                            warmSource=source;warmVid=next.vid;warmGear=gear;
                        }
                    }catch(Exception error){source.close();throw error;}
                    finally{if(openingSource==source)openingSource=null;}
                }
                android.util.Log.i("TVPreload","Ready nextSeason="+nextSeason+" episode="+next.index);
                ui.post(()->{
                    if(seq!=sequence) return;
                    
                    if(isEpisodeOpen()) refreshEpisodeCells();
                });
            } catch(Exception error) {
                android.util.Log.w("TVPreload","Episode preload failed",error);
            }
        });
    }
    private void queuePlayer(Clip clip,int seq) {
        if(destroyed || seq!=sequence) return;
        activeGear=clip.gear; pendingFile=clip.file; pendingSequence=seq;
        fitVideoSize(activeGear.width,activeGear.height);
        surface.post(this::attachPending);
    }
    private void attachPending() {
        if((pendingFile==null && pendingStream==null) || pendingSequence!=sequence || !surfaceReady
                || !surface.getHolder().getSurface().isValid()) return;
        final int seq=pendingSequence;
        File source=pendingFile; pendingFile=null;
        StreamingSource incoming=pendingStream;pendingStream=null;
        try {
            releasePlayer();
            final boolean compatible=compatSequence==seq;
            final MediaPlayer media=compatible?new com.hgtv.app.media.CompatMediaPlayer(this):new MediaPlayer();player=media;
            if(compatible)fallbackSequence=seq;
            com.hgtv.app.media.PlaybackDiagnostics.source(activeGear,compatible?"Media3 compatibility":"System MediaPlayer");
            media.setOnVideoSizeChangedListener((mp,w,h)->{
                if(seq==sequence) fitVideoSize(w,h);
            });
            media.setOnCompletionListener(mp->{if(seq==sequence && player==mp)onEpisodeComplete();});
            media.setOnSeekCompleteListener(mp->{
                if(seq!=sequence || player!=mp || !prepared)return;
                if(startupCorrection){startupCorrection=false;videoReady(seq,mp,"startup-rewind");}
                if(foreground && surfaceReady && !paused && !ended && !commentsOpen){
                    try{mp.start();android.util.Log.i("TVPlayback","Started seq="+seq+" after seek");monitorStart(seq,mp);}catch(IllegalStateException error){fallback(seq,error);}
                }else if(foreground && !surfaceReady && !paused && !ended && !commentsOpen)resumeAfterPause=true;
            });
            media.setOnBufferingUpdateListener((mp,percent)->{if(seq==sequence && player==mp){bufferedPercent=Math.max(0,Math.min(100,percent));timeline.setSecondaryProgress(bufferedPercent*10);com.hgtv.app.remote.PlaybackControl.buffered(bufferedPercent);}});
            media.setOnInfoListener((mp,what,extra)->{
                if(seq!=sequence || player!=mp)return true;
                if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START){recordMediaInfo(seq,mp,what,extra);buffering=true;if(seq==sequence&&!ended)setLoading(true,"正在缓冲…");}
                else if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START){
                    recordMediaInfo(seq,mp,what,extra);
                    if(!firstFrame){
                        int position=mp.getCurrentPosition();
                        android.util.Log.i("TVPlayback","Decoded first frame seq="+seq+" position="+position+" target="+startupPosition);
                        videoReady(seq,mp,"rendered");
                    }
                }
                else if(what==MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING
                        || (android.os.Build.VERSION.SDK_INT>=26 && what==MediaPlayer.MEDIA_INFO_VIDEO_NOT_PLAYING)){
                    recordMediaInfo(seq,mp,what,extra);recoverVideoSync(seq,mp,what);
                }
                else if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END){recordMediaInfo(seq,mp,what,extra);buffering=false;if(firstFrame)setLoading(false,null);}
                return false;
            });
            media.setOnErrorListener((mp,what,extra)->{
                if(seq!=sequence || player!=mp)return true;
                com.hgtv.app.media.PlaybackDiagnostics.event("decoder_error",what+"/"+extra);
                if(android.os.Build.VERSION.SDK_INT>=26 && !(mp instanceof com.hgtv.app.media.CompatMediaPlayer))try{
                    android.os.PersistableBundle metrics=mp.getMetrics();if(metrics!=null)com.hgtv.app.media.PlaybackDiagnostics.event("system_decoder",metrics.getString("android.media.mediaplayer.video.codec","unknown"));
                }catch(Exception ignored){}
                fallback(seq,new java.io.IOException((what==MediaPlayer.MEDIA_ERROR_IO || extra==MediaPlayer.MEDIA_ERROR_IO?"播放读取失败 ":"解码器 ")+what+"/"+extra));return true;
            });
            media.setOnPreparedListener(mp->{
                if(seq!=sequence || player!=mp) return;
                prepared=true;paused=restorePaused || !foreground || commentsOpen || !surfaceReady;
                startupPosition=Math.max(0,restorePosition);mp.setVolume(0f,0f);
                if(!foreground || !surfaceReady) resumeAfterPause=!restorePaused;
                android.util.Log.i("TVPlayback","Prepared seq="+seq+" ms="+(android.os.SystemClock.elapsedRealtime()-playbackStartedAt)+" paused="+paused+" foreground="+foreground);
                setLoading(!paused,"正在加载画面…");
                subtitle.setText("第 "+episodes.get(index).index+" 集  /  "+episodes.size()+
                        " 集    ·    "+activeGear.shortSide()+"P");
                applyPlaybackSpeed(false);if(activeGear!=null)qualityButton.setText(activeGear.shortSide()+"P");
                // A fresh player starts at its first sample. Do not flush it with a zero seek.
                if(startupPosition>0)seekNative(mp,startupPosition);
                if(!paused){mp.start();monitorStart(seq,mp);}else mp.pause();
                restorePosition=0;restorePaused=false;
                android.util.Log.i("TVPlayback","Opening seq="+seq+" target="+startupPosition+" seek="+(startupPosition>0)+" sdk="+android.os.Build.VERSION.SDK_INT+" device="+android.os.Build.MANUFACTURER+"/"+android.os.Build.MODEL);
                refreshPauseState(); ui.removeCallbacks(tick); ui.post(tick); scheduleHide();
            });
            stream=incoming;
            if(incoming!=null)media.setDataSource(incoming);
            else media.setDataSource(source.getAbsolutePath());
            media.setDisplay(surface.getHolder());
            media.setScreenOnWhilePlaying(true);
            media.prepareAsync();
            ui.postDelayed(()->{if(seq==sequence && player==media && !prepared && !destroyed)fallback(seq,new java.io.IOException("播放准备超时，请检查网络后重试"));},20000);
        } catch(Exception error) {if(incoming!=null){incoming.close();fallback(seq,error);}else fallback(seq,error); }
    }
    private void videoReady(int seq,MediaPlayer media,String reason){
        if(seq!=sequence || player!=media || firstFrame || startupCorrection)return;
        // The decoder can advance its clock while the loading cover still hides its first frame.
        // Once it is warm, rewind once before revealing it so the opening remains watchable.
        int position=media.getCurrentPosition();
        if(!startupRewound && !startupCorrection && position>startupPosition+250){
            startupRewound=true;startupCorrection=true;media.pause();
            android.util.Log.i("TVPlayback","Correcting startup seq="+seq+" position="+position+" target="+startupPosition);
            seekNative(media,startupPosition);
            return;
        }
        firstFrame=true;buffering=false;media.setVolume(1f,1f);setLoading(false,null);
        if(activeGear.shortSide()>720)ui.postDelayed(()->{if(seq==sequence && player==media && firstFrame && !failed)com.hgtv.app.media.PlaybackCompatibility.confirmed();},3000);
        com.hgtv.app.media.PlaybackDiagnostics.event("first_frame",activeGear.shortSide()+"P position="+media.getCurrentPosition());
        android.util.Log.i("TVPlayback","Visible first frame seq="+seq+" position="+media.getCurrentPosition()+" target="+startupPosition+" reason="+reason+" ms="+(android.os.SystemClock.elapsedRealtime()-playbackStartedAt));
        scheduleWarmup(seq);
    }
    private void recoverVideoSync(int seq,MediaPlayer media,int info){
        if(seq!=sequence || player!=media || !prepared || !firstFrame || paused || buffering || !surfaceReady || destroyed)return;
        final int position;
        try{position=Math.max(0,media.getCurrentPosition());}catch(IllegalStateException ignored){return;}
        String engine=media instanceof com.hgtv.app.media.CompatMediaPlayer?"Media3":"system";
        com.hgtv.app.media.PlaybackDiagnostics.event("video_sync_recovery","engine="+engine+" info="+info+" positionMs="+position+" quality="+(activeGear==null?"unknown":activeGear.shortSide()+"P"));
        android.util.Log.w("TVPlayback","Video decoder lag seq="+seq+" engine="+engine+" info="+info+" position="+position);
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-videoLagWindowStartedAt>10000){videoLagWindowStartedAt=now;videoLagEvents=0;}
        videoLagEvents++;
        if(!(media instanceof com.hgtv.app.media.CompatMediaPlayer)
                && videoLagEvents>=2 && fallbackSequence!=seq){
            restorePosition=position;restorePaused=false;
            fallback(seq,new java.io.IOException("连续画面解码落后，正在切换兼容播放并同步音画"));
            return;
        }
        if(media instanceof com.hgtv.app.media.CompatMediaPlayer && videoLagEvents>=2 && activeGear!=null
                && activeGear.shortSide()>720 && playbackCeiling>720){
            restorePosition=position;restorePaused=false;
            fallback(seq,new java.io.IOException("视频解码不稳定，降低清晰度后重新同步音画"));
            return;
        }
        // The audio clock may have advanced while video frames were stalled. Pause both tracks
        // and seek to that clock so the decoder cannot resume from a stale video timestamp.
        if(now-lastVideoSyncRecoveryAt<2500)return;
        lastVideoSyncRecoveryAt=now;
        try{media.pause();seekNative(media,position);}
        catch(RuntimeException error){com.hgtv.app.media.PlaybackDiagnostics.event("video_sync_recovery_failed",error.getClass().getSimpleName());}
    }
    private void recordMediaInfo(int seq,MediaPlayer media,int what,int extra){
        if(seq!=sequence || player!=media)return;
        int position=-1;try{if(prepared)position=media.getCurrentPosition();}catch(IllegalStateException ignored){}
        com.hgtv.app.media.PlaybackDiagnostics.event("media_info","engine="+(media instanceof com.hgtv.app.media.CompatMediaPlayer?"Media3":"system")+" what="+what+" extra="+extra+" positionMs="+position+" buffering="+buffering);
    }
    /** Check the native state after start(): some TV players stop between prepare/seek/start. */
    private void monitorStart(final int seq,final MediaPlayer media){
        if(startMonitor!=null)ui.removeCallbacks(startMonitor);
        if(paused || !foreground || ended)return;
        startMonitor=new Runnable(){
            int checks,restarts,lastPosition=-1,stagnantChecks,correctionChecks;
            public void run(){
                if(seq!=sequence || player!=media || destroyed || !prepared || failed)return;
                if(!foreground || paused || ended || commentsOpen)return;
                if(startupCorrection){
                    if(++correctionChecks>100){fallback(seq,new java.io.IOException("首帧定位超时"));return;}
                    ui.postDelayed(this,100);return;
                }
                try{
                    boolean running=media.isPlaying();
                    if(firstFrame && running)return;
                    int position=media.getCurrentPosition();checks++;
                    android.util.Log.i("TVPlayback","StartCheck seq="+seq+" playing="+running+" position="+position+" check="+checks);
                    if(position>lastPosition)stagnantChecks=0;else stagnantChecks++;
                    lastPosition=position;
                    if(!running && restarts<3){
                        media.start();restarts++;
                        android.util.Log.w("TVPlayback","Restarted native playback seq="+seq+" attempt="+restarts);
                    }
                    // Never seek a live stream back to zero simply because the first frame is late.
                    // Recover only after the decoder has truly stopped advancing.
                    if(!firstFrame && stagnantChecks>=12 && stream!=null){
                        fallback(seq,new java.io.IOException("首帧解码停滞，切换兼容内核"));return;
                    }
                    if(checks>=(activeGear!=null && activeGear.shortSide()>720?10:30)){
                        fallback(seq,new java.io.IOException("首帧启动超时，自动恢复"));
                        return;
                    }
                    ui.postDelayed(this,1000);
                }catch(IllegalStateException error){fallback(seq,error);}
            }
        };
        ui.postDelayed(startMonitor,350);
    }
    private void fitVideoSize(int vw,int vh) {
        if(vw<=0 || vh<=0 || surface==null) return;
        int sw=root.getWidth(),sh=root.getHeight();
        if(sw<=0 || sh<=0) return;
        float scale=(float)sh/vh;
        int width=Math.round(vw*scale),height=Math.round(vh*scale);
        ViewGroup.LayoutParams current=surface.getLayoutParams();
        if(current instanceof FrameLayout.LayoutParams && current.width==width && current.height==height
                && ((FrameLayout.LayoutParams)current).gravity==Gravity.CENTER) return;
        surface.setLayoutParams(new FrameLayout.LayoutParams(width,height,Gravity.CENTER));
    }
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(!prepared || player==null) return;
            try {
                int duration=player.getDuration(),position=firstFrame?player.getCurrentPosition():startupPosition;
                if(duration>0 && seekPreview<0) timeline.setProgress((int)(position*1000L/duration));
                if(seekPreview<0)time.setText(formatTime(position)+" / "+formatTime(duration));
                com.hgtv.app.remote.PlaybackControl.update(seriesTitle,episodes.get(index).index,position,duration,paused);
                captureNativeFrameMetrics(sequence,player,position);
                if(android.os.SystemClock.elapsedRealtime()-progressSavedAt>=5000){savePlaybackPosition();}
            } catch(IllegalStateException ignored) { }
            ui.postDelayed(this,500);
        }
    };
    private void captureNativeFrameMetrics(int seq,MediaPlayer media,int position){
        if(android.os.Build.VERSION.SDK_INT<26 || media==null || media instanceof com.hgtv.app.media.CompatMediaPlayer)return;
        long now=android.os.SystemClock.elapsedRealtime(),previousDataAt=lastMetricsDataAt;
        if(now-lastMetricsSampleAt<1500)return;
        lastMetricsSampleAt=now;
        try{
            android.os.PersistableBundle metrics=media.getMetrics();
            if(metrics==null){if(lastMetricsLogAt==0){lastMetricsLogAt=now;com.hgtv.app.media.PlaybackDiagnostics.event("native_metrics_unavailable","player returned no metrics");}return;}
            String codec=metrics.getString(MediaPlayer.MetricsConstants.CODEC_VIDEO,"unknown");
            int frames=metrics.getInt(MediaPlayer.MetricsConstants.FRAMES,-1);
            int dropped=metrics.getInt(MediaPlayer.MetricsConstants.FRAMES_DROPPED,-1);
            int droppedDelta=lastMetricsDropped<0||dropped<0?-1:dropped-lastMetricsDropped;
            int frameDelta=lastMetricsFrames<0||frames<0?-1:frames-lastMetricsFrames;
            long sampleDeltaMs=previousDataAt==0?0:now-previousDataAt;
            long positionDelta=previousDataAt==0?0:position-lastMetricsPosition;
            if(droppedDelta>0 || now-lastMetricsLogAt>=10000){
                com.hgtv.app.media.PlaybackDiagnostics.event("native_frame_metrics","codec="+codec+" sampleMs="+sampleDeltaMs+" posMs="+position+" posDeltaMs="+positionDelta+" frames="+frames+" frameDelta="+frameDelta+" dropped="+dropped+" droppedDelta="+droppedDelta);
                lastMetricsLogAt=now;
            }
            if(firstFrame && !paused && !buffering && sampleDeltaMs>0 && droppedDelta>=12
                    && positionDelta>=sampleDeltaMs*2/3){
                android.util.Log.w("TVPlayback","Native video dropped frames seq="+seq+" delta="+droppedDelta+" in "+sampleDeltaMs+"ms; resyncing");
                recoverVideoSync(seq,media,MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING);
            }
            if(dropped>=0)lastMetricsDropped=dropped;if(frames>=0)lastMetricsFrames=frames;
            lastMetricsPosition=position;lastMetricsDataAt=now;
        }catch(RuntimeException error){
            if(lastMetricsLogAt==0){lastMetricsLogAt=now;com.hgtv.app.media.PlaybackDiagnostics.event("native_metrics_error",error.getClass().getSimpleName());}
        }
    }
    private void savePlaybackPosition(){
        if(!prepared || !firstFrame || player==null || episodes==null || index<0 || index>=episodes.size())return;
        try{Store.progress(seriesId,index,player.getCurrentPosition(),player.getDuration());progressSavedAt=android.os.SystemClock.elapsedRealtime();}catch(IllegalStateException ignored){}
    }
    private static String formatTime(int millis) {
        int seconds=Math.max(0,millis/1000);
        return String.format(Locale.US,"%02d:%02d",seconds/60,seconds%60);
    }
    private void togglePause() {
        if(failed || ended) {restorePosition=0;restorePaused=false;playAt(index);return;}
        if(!prepared || player==null){paused=!paused;restorePaused=paused;refreshPauseState();return;}
        try { paused=!paused; if(paused){if(player.isPlaying())player.pause();}else player.start(); }
        catch(IllegalStateException ignored) { return; }
        refreshPauseState();
        if(!paused)monitorStart(sequence,player);
    }
    private void refreshPauseState() {
        com.hgtv.app.remote.PlaybackControl.paused(paused);
        if(playButton!=null){playButton.setContentDescription(ended?"重播本集":paused?"播放":"暂停");Ui.icon(playButton,(paused||ended)?IconView.PLAY:IconView.PAUSE,playButton.getCurrentTextColor(),20);}
        centerToggle.setContentDescription(ended?"重播本集":paused?"播放":"暂停");
        centerToggleIcon.setType((paused || ended)?IconView.PLAY:IconView.PAUSE);
        centerToggleIcon.setColor(centerToggle.hasFocus()?Ui.PRIMARY:Ui.TEXT);
    }
    private void seekBy(int delta){
        if(!prepared || player==null)return;
        try{
            int duration=player.getDuration(),base=seekPreview>=0?seekPreview:player.getCurrentPosition();
            seekPreview=Math.max(0,Math.min(Math.max(0,duration-300),base+delta));
            timeline.setProgress((int)(seekPreview*1000L/Math.max(1,duration)));
            time.setText(formatTime(seekPreview)+" / "+formatTime(duration));
            readyHint.setText((delta<0?"快退":"快进")+" · 松开方向键后跳转");
            ui.removeCallbacks(commitSeek);ui.postDelayed(commitSeek,450);showChrome();
        }catch(IllegalStateException ignored){}
    }
    private void commitPendingSeek(){
        if(seekPreview<0 || !prepared || player==null)return;
        int position=seekPreview;seekPreview=-1;
        try{seekNative(player,position);ended=false;allFinished=false;com.hgtv.app.remote.PlaybackControl.ended(false);refreshPauseState();}
        catch(IllegalStateException ignored){}
    }
    private void seekNative(MediaPlayer media,int position){
        if(android.os.Build.VERSION.SDK_INT>=26)media.seekTo((long)Math.max(0,position),position<=0?MediaPlayer.SEEK_PREVIOUS_SYNC:MediaPlayer.SEEK_CLOSEST);
        else media.seekTo(Math.max(0,position));
    }
    private void nextEpisode(){
        if(episodes==null || index+1>=episodes.size()){toast("已经是最后一集");return;}
        restorePosition=0;restorePaused=false;playAt(index+1);
    }
    private void onEpisodeComplete(){
        savePlaybackPosition();
        if(!degraded && episodes!=null && index+1>=episodes.size()){
            final int request=sequence;
            com.hgtv.app.ui.widget.FavoriteActions.onCompleted(this,seriesId,episodes.size(),()->{
                if(!destroyed && request==sequence){if(favoriteButton!=null)favoriteButton.setText(Store.isFavorite(seriesId)?"已追剧":"追剧");continueAfterEpisodeComplete();}
            });return;
        }
        continueAfterEpisodeComplete();
    }
    private void continueAfterEpisodeComplete(){
        android.util.Log.i("TVPlayback","Completed episode="+episodes.get(index).index+" seq="+sequence+" auto="+App.prefs().getBoolean("autoplay_next",true));
        if(episodes!=null && index+1<episodes.size() && App.prefs().getBoolean("autoplay_next",true)){
            restorePosition=0;restorePaused=false;playAt(index+1);return;
        }
        boolean seasonEnd=!degraded && episodes!=null && index+1>=episodes.size();
        if(seasonEnd && App.prefs().getBoolean("autoplay_next",true) && seriesDetail!=null && seriesDetail.multiSeason){
            final int request=sequence;
            final String id=seriesId;final Repo.Detail detail=seriesDetail;
            setLoading(true,"本季已播完，正在准备下一季…");
            App.io().execute(()->{try{
                final Repo.Season next=followingSeason(id,detail);
                ui.post(()->{if(destroyed || request!=sequence)return;
                    if(next==null){finishPlayback(true);return;}
                    android.util.Log.i("TVPlayback","Continue season="+next.number);
                    android.content.Intent intent=new android.content.Intent(this,PlayerActivity.class);
                    intent.putExtra(EXTRA_SERIES,next.id).putExtra(EXTRA_TITLE,next.title).putExtra(EXTRA_COVER,next.cover).putExtra(EXTRA_INDEX,0);
                    // Reuse this Activity so lifecycle transitions cannot leave the next season paused.
                    onNewIntent(intent);
                });
            }catch(Exception error){ui.post(()->{if(destroyed || request!=sequence)return;finishPlayback(false);readyHint.setText("本季已播完 · 后续季加载失败，可在设置中重试切换季");});}});
            return;
        }
        finishPlayback(seasonEnd);
    }
    private void finishPlayback(boolean complete){
        ended=true;allFinished=complete;paused=true;buffering=false;
        com.hgtv.app.remote.PlaybackControl.ended(true);
        setLoading(false,null);refreshPauseState();readyHint.setText(allFinished?"已全部播放完毕":"本集结束 · 可选择下一集");
        revealControls();ui.removeCallbacks(hideTask);
    }
    private Gear pickPlaybackGear(List<Gear> gears){
        return Repo.pickGear(VideoDecoderSupport.compatibleHevc(gears),prefer==null?"720p":prefer,playbackCeiling);
    }
    private boolean overlayOpen(){return isEpisodeOpen() || commentsOpen || (settingsDialog!=null && settingsDialog.isShowing());}

    private void updateSettingsLabels(){
        String quality=prepared && activeGear!=null?activeGear.shortSide()+"P":prefer==null?"720P":prefer.toUpperCase(Locale.US);
        com.hgtv.app.remote.PlaybackControl.settings(playbackSpeed,quality);
        if(speedButton!=null)speedButton.setText(String.format(Locale.US,"%.2f",playbackSpeed).replaceAll("0$","")+"×"+(speedLocked?" · 锁定":""));
        if(qualityButton!=null)qualityButton.setText(quality);
    }
    private void applyPlaybackSpeed(boolean feedback){
        if(!prepared || player==null)return;
        try{
            if(playbackSpeed!=appliedSpeed){
                player.setPlaybackParams(new android.media.PlaybackParams().setSpeed(playbackSpeed).setPitch(1f));
                appliedSpeed=playbackSpeed;
            }
            if((paused || !foreground) && player.isPlaying())player.pause();
            if(feedback)toast("播放速度 "+playbackSpeed+"×");
        }catch(RuntimeException error){playbackSpeed=1f;if(feedback)toast("此设备暂不支持该倍速，已恢复正常速度");}
        updateSettingsLabels();
    }
    private interface ChoiceAction { void accept(int index); }
    private void options(String heading,List<String> labels,int selected,ChoiceAction action){
        if(settingsDialog!=null)settingsDialog.dismiss();
        final boolean visibleBefore=chromeVisible;final View focusedBefore=getCurrentFocus();
        ui.removeCallbacks(hideTask);
        android.app.Dialog dialog=new android.app.Dialog(this);settingsDialog=dialog;
        Ui.closeOnBack(dialog);
        LinearLayout panel=Ui.column(this);Ui.padding(panel,24,20,24,20);panel.setBackground(Ui.round(this,Ui.SURFACE,16));
        panel.addView(text(heading,22,Ui.TEXT,true));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);LinearLayout list=Ui.column(this);Ui.padding(list,0,16,0,0);scroll.addView(list);panel.addView(scroll,Ui.lp(-1,0,1));
        List<TextView> buttons=new ArrayList<>();
        for(int n=0;n<labels.size();n++){
            final int choice=n;TextView button=Ui.action(this,labels.get(n),n==selected?IconView.CHECK:IconView.CHEVRON_RIGHT,false);
            button.setOnClickListener(v->{dialog.dismiss();action.accept(choice);});
            LinearLayout.LayoutParams lp=Ui.lp(-1,dp(48));lp.bottomMargin=dp(8);list.addView(button,lp);buttons.add(button);
        }
        dialog.setOnDismissListener(d->{if(settingsDialog==dialog)settingsDialog=null;if(!destroyed)restoreChrome(visibleBefore,focusedBefore);});
        dialog.setContentView(panel);dialog.show();Window w=dialog.getWindow();
        if(w!=null){w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);w.getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(Math.min(dp(440),root.getWidth()-dp(48)),Math.min(root.getHeight()-dp(48),dp(80+labels.size()*56)));}
        if(!buttons.isEmpty())buttons.get(Math.max(0,Math.min(selected,buttons.size()-1))).requestFocus();
    }
    private void openSpeed(){
        final float[] rates={.75f,1f,1.25f,1.5f,1.75f,2f};List<String> labels=new ArrayList<>();int selected=1;
        for(int n=0;n<rates.length;n++){labels.add(rates[n]+"×"+(rates[n]==1?"  正常速度":""));if(rates[n]==playbackSpeed)selected=n;}
        labels.add(speedLocked?"倍速锁定：已开启 · 点击关闭":"倍速锁定：已关闭 · 点击开启");
        options("倍速",labels,selected,n->{
            if(n==rates.length){speedLocked=!speedLocked;App.prefs().edit().putBoolean("speed_lock",speedLocked).putFloat("play_speed",playbackSpeed).apply();updateSettingsLabels();openSpeed();}
            else{playbackSpeed=rates[n];if(speedLocked)App.prefs().edit().putFloat("play_speed",playbackSpeed).apply();applyPlaybackSpeed(true);}
        });
    }
    private void openQuality(){
        if(episodes==null)return;final int request=sequence;String vid=episodes.get(index).vid;
        ui.removeCallbacks(hideTask);toast("正在读取可用清晰度");
        App.io().execute(()->{try{
            List<Gear> all=gearsFor(vid);TreeMap<Integer,Gear> choices=new TreeMap<>(Collections.reverseOrder());
            for(Gear gear:VideoDecoderSupport.compatibleHevc(all))if(gear.shortSide()>0 && gear.shortSide()<=playbackCeiling && !choices.containsKey(gear.shortSide()))choices.put(gear.shortSide(),gear);
            List<Gear> gears=new ArrayList<>(choices.values());
            ui.post(()->{if(destroyed || request!=sequence)return;
                List<String> labels=new ArrayList<>();int selected=0;
                for(int n=0;n<gears.size();n++){Gear gear=gears.get(n);labels.add(gear.shortSide()+"P");if(Objects.equals(gear.definition,prefer))selected=n;}
                if(gears.isEmpty()){toast("本集清晰度暂未返回，请重试");return;}
                options("清晰度",labels,selected,n->{
                    String next=gears.get(n).definition;if(Objects.equals(next,prefer))return;
                    restorePosition=0;restorePaused=paused;
                    if(prepared && player!=null)try{restorePosition=player.getCurrentPosition();}catch(IllegalStateException ignored){}
                    prefer=next;App.prefs().edit().putString("quality",prefer).apply();keepRate=true;playAt(index);
                });
            });
        }catch(Exception error){ui.post(()->toast("清晰度读取失败，请重试"));}});
    }
    private void openPlaybackSettings(){
        boolean auto=App.prefs().getBoolean("autoplay_next",true);
        List<String> labels=new ArrayList<>(Arrays.asList("自动连播下一集："+(auto?"已开启":"已关闭"),"倍速与倍速锁定","清晰度","选集","整部剧的剧评"));
        if(seriesDetail!=null && seriesDetail.multiSeason)labels.add("切换季 · 当前第 "+seriesDetail.seasonIndex+" 季");
        options("播放设置",labels,0,n->{
            if(n==0){App.prefs().edit().putBoolean("autoplay_next",!auto).apply();openPlaybackSettings();}
            else if(n==1)openSpeed();else if(n==2)openQuality();else if(n==3)openEpisodes();else if(n==4)showComments(false);else openSeasons();
        });
    }
    private void openSeasons(){
        if(seriesDetail==null)return;final int request=sequence;final String id=seriesId,album=seriesDetail.relatedAlbumId;
        toast("正在载入各季…");App.io().execute(()->{try{
            List<Repo.Season> all=Repo.seasons(id,album),available=new ArrayList<>();List<String> labels=new ArrayList<>();int selected=0;
            for(Repo.Season season:all)if(season.episodes>0){if(id.equals(season.id))selected=available.size();available.add(season);labels.add("第 "+season.number+" 季 · "+season.episodes+"集 · "+season.title);}
            final int choice=selected;ui.post(()->{if(destroyed || request!=sequence)return;
                options("切换季",labels,choice,n->{Repo.Season season=available.get(n);if(id.equals(season.id))return;
                    android.content.Intent intent=new android.content.Intent(this,PlayerActivity.class);
                    intent.putExtra(EXTRA_SERIES,season.id).putExtra(EXTRA_TITLE,season.title).putExtra(EXTRA_COVER,season.cover).putExtra(EXTRA_INDEX,0);startActivity(intent);
                });
            });
        }catch(Exception error){ui.post(()->{if(request==sequence && !destroyed)toast("各季加载失败，请重试");});}});
    }
    private void openComments(){showComments(true);}
    private void showComments(boolean episode){
        if(episodes==null || commentsOpen)return;
        final int request=sequence;final boolean resume=!paused && !ended && !failed;
        final boolean visibleBefore=chromeVisible;final View focusedBefore=getCurrentFocus();
        if(resume){
            if(prepared && player!=null)try{if(player.isPlaying())player.pause();}catch(IllegalStateException ignored){}
            paused=true;refreshPauseState();
        }
        commentsOpen=true;ui.removeCallbacks(hideTask);
        Episode current=episodes.get(index);
        commentsDialog=com.hgtv.app.ui.widget.CommentsDialog.show(this,seriesId,episode?current.vid:null,
            episode?"第 "+current.index+" 集评论":"整部剧的剧评",()->{
                commentsOpen=false;commentsDialog=null;
                if(resume && request==sequence && !ended && !failed){
                    restorePaused=false;
                    if(foreground && surfaceReady){
                        paused=false;
                        if(prepared && player!=null)try{player.start();monitorStart(sequence,player);}catch(IllegalStateException ignored){}
                        refreshPauseState();
                    }else resumeAfterPause=true;
                }
                if(request==sequence && !destroyed)restoreChrome(visibleBefore,focusedBefore);
            });
    }

    private boolean isEpisodeOpen() { return episodeOverlay!=null && episodeOverlay.getVisibility()==View.VISIBLE; }
    private void openEpisodes(){
        if(episodes==null || episodes.isEmpty() || isEpisodeOpen())return;
        episodeChromeBefore=chromeVisible;
        hideChrome();episodeOverlay.removeAllViews();episodeCells.clear();
        episodeSheet=Ui.column(this);episodeSheet.setClickable(true);Ui.padding(episodeSheet,20,24,20,18);
        episodeSheet.setBackground(Ui.roundStroke(this,0xFA171D23,16,0x33FFFFFF,1));
        LinearLayout header=Ui.row(this);header.addView(text("选集 · "+episodes.size()+"集",19,Ui.TEXT,true),Ui.lp(0,-2,1));
        TextView locate=Ui.action(this,"定位",IconView.LOCATE,false);
        locate.setOnClickListener(v->{selectEpisodePage(index/100);episodeGrid.setSelection(index%100);episodeGrid.requestFocus();});header.addView(locate,Ui.lp(-2,dp(42)));
        TextView close=Ui.action(this,"收起",IconView.CLOSE,false);close.setId(View.generateViewId());close.setOnClickListener(v->closeEpisodes());header.addView(close,Ui.lp(-2,dp(42)));
        ((LinearLayout.LayoutParams)close.getLayoutParams()).leftMargin=dp(10);
        episodeSheet.addView(header,Ui.lp(-1,dp(44)));
        TextView tip=text("每100集一个范围 · 确定播放 · 返回收起",11,Ui.TEXT_3,false);Ui.padding(tip,0,8,0,12);tip.setVisibility(View.GONE);episodeSheet.addView(tip);
        episodePage=index/100;rangeTabs.clear();
        HorizontalScrollView ranges=new HorizontalScrollView(this);ranges.setHorizontalScrollBarEnabled(false);ranges.setClipToPadding(false);
        LinearLayout tabs=Ui.row(this);ranges.addView(tabs);
        for(int page=0;episodes.size()>100 && page*100<episodes.size();page++){
            final int target=page;TextView tab=Ui.action(this,(page*100+1)+"~"+Math.min(episodes.size(),page*100+100),IconView.EPISODES,false);
            tab.setCompoundDrawables(null,null,null,null);tab.setCompoundDrawablePadding(0);
            LinearLayout.LayoutParams tabLp=Ui.lp(dp(116),dp(36));tabLp.rightMargin=dp(8);tabs.addView(tab,tabLp);rangeTabs.add(tab);
            tab.setOnFocusChangeListener((v,focus)->{
                paintRangeTab(tab,focus,target==episodePage);
                if(focus)ranges.smoothScrollTo(Math.max(0,tab.getLeft()-dp(12)),0);
            });
            tab.setOnClickListener(v->{selectEpisodePage(target);episodeGrid.requestFocus();episodeGrid.setSelection(target==index/100?index%100:0);});
            tab.setOnKeyListener((v,key,event)->{
                if(event.getAction()!=0)return false;
                if(key==KeyEvent.KEYCODE_DPAD_DOWN){selectEpisodePage(target);episodeGrid.requestFocus();episodeGrid.setSelection(target==index/100?index%100:0);return true;}
                if(key==KeyEvent.KEYCODE_DPAD_UP){locate.requestFocus();return true;}
                if(key==KeyEvent.KEYCODE_DPAD_LEFT && target==0 || key==KeyEvent.KEYCODE_DPAD_RIGHT && target==rangeTabs.size()-1)return true;
                return false;
            });
        }
        ranges.setVisibility(episodes.size()>100?View.VISIBLE:View.GONE);
        LinearLayout.LayoutParams rangeLayout=Ui.lp(-1,dp(44));rangeLayout.topMargin=dp(12);episodeSheet.addView(ranges,rangeLayout);
        final int columns=5;
        episodeGrid=new GridView(this);episodeGrid.setNumColumns(columns);episodeGrid.setHorizontalSpacing(dp(8));episodeGrid.setVerticalSpacing(dp(8));
        episodeGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);episodeGrid.setSelector(android.R.color.transparent);episodeGrid.setVerticalScrollBarEnabled(false);
        episodeGrid.setAdapter(new BaseAdapter(){
            public int getCount(){return Math.min(100,episodes.size()-episodePage*100);}public Object getItem(int pos){return episodes.get(episodePage*100+pos);}public long getItemId(int pos){return episodePage*100+pos;}
            public View getView(int pos,View recycled,ViewGroup parent){
                TextView cell=recycled instanceof TextView?(TextView)recycled:text("",15,Ui.TEXT,true);
                cell.setGravity(Gravity.CENTER);cell.setLayoutParams(new AbsListView.LayoutParams(-1,dp(46)));cell.setTag(episodePage*100+pos);
                paintEpisode(cell,episodePage*100+pos,episodeGrid.hasFocus() && episodeGrid.getSelectedItemPosition()==pos);return cell;
            }
        });
        episodeGrid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int pos,long id){refreshEpisodeCells();}
            public void onNothingSelected(AdapterView<?> parent){}
        });
        episodeGrid.setOnFocusChangeListener((v,has)->refreshEpisodeCells());
        episodeGrid.setOnItemClickListener((parent,view,pos,id)->{int absolute=episodePage*100+pos;if(absolute==index)closeEpisodes();else {restorePosition=0;restorePaused=false;playAt(absolute);}});
        episodeGrid.setOnKeyListener((v,key,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;int pos=episodeGrid.getSelectedItemPosition();
            if(key==KeyEvent.KEYCODE_DPAD_LEFT && pos%columns==0)return true;
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT && (pos%columns==columns-1 || pos==episodeGrid.getCount()-1))return true;
            if(key==KeyEvent.KEYCODE_DPAD_UP && pos<columns){if(rangeTabs.isEmpty()){locate.requestFocus();}else rangeTabs.get(episodePage).requestFocus();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_DOWN && pos+columns>=episodeGrid.getCount())return true;
            return false;
        });
        View.OnKeyListener headerKeys=(v,key,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(rangeTabs.isEmpty()){episodeGrid.requestFocus();}else rangeTabs.get(episodePage).requestFocus();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_UP)return true;
            if(key==KeyEvent.KEYCODE_DPAD_LEFT){locate.requestFocus();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT){close.requestFocus();return true;}
            return false;
        };locate.setOnKeyListener(headerKeys);close.setOnKeyListener(headerKeys);
        LinearLayout.LayoutParams gridLayout=Ui.lp(-1,0,1);gridLayout.topMargin=dp(12);episodeSheet.addView(episodeGrid,gridLayout);
        int drawerWidth=Math.min(dp(400),(int)(root.getWidth()*.43f));
        FrameLayout.LayoutParams panel=new FrameLayout.LayoutParams(drawerWidth,-1,Gravity.RIGHT);
        episodeOverlay.addView(episodeSheet,panel);episodeOverlay.setVisibility(View.VISIBLE);
        episodeSheet.setTranslationX(drawerWidth);episodeSheet.animate().translationX(0).setDuration(180).start();
        episodeGrid.post(()->{selectEpisodePage(index/100);episodeGrid.requestFocus();episodeGrid.setSelection(index%100);refreshEpisodeCells();});
    }
    private void paintRangeTab(TextView tab,boolean focus,boolean selected){
        tab.setTextColor(selected?Ui.PRIMARY:focus?Ui.TEXT:Ui.TEXT_2);
        android.graphics.drawable.GradientDrawable fill=new android.graphics.drawable.GradientDrawable();fill.setColor(focus?0x18FFFFFF:0);
        android.graphics.drawable.GradientDrawable line=new android.graphics.drawable.GradientDrawable();line.setColor(selected?Ui.PRIMARY:0);
        android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{fill,line});
        layers.setLayerGravity(1,Gravity.BOTTOM);layers.setLayerHeight(1,dp(2));tab.setBackground(layers);
    }
    private void selectEpisodePage(int page){
        episodePage=page;((BaseAdapter)episodeGrid.getAdapter()).notifyDataSetChanged();
        for(int n=0;n<rangeTabs.size();n++){TextView tab=rangeTabs.get(n);boolean current=n==page;
            paintRangeTab(tab,tab.hasFocus(),current);}
        refreshEpisodeCells();
    }
    private void paintEpisode(TextView cell,int position,boolean focus) {
        boolean current=position==index;
        Clip clip=clips.get(episodes.get(position).vid);
        boolean ready=clip!=null && clip.exists();
        boolean warmed; synchronized(warmLock){warmed=warmSource!=null && episodes.get(position).vid.equals(warmVid);}
        String label=String.format(Locale.US,"%02d",episodes.get(position).index);
        String state=current?"正在播放":"";
        android.text.SpannableString value=new android.text.SpannableString(label+(state.isEmpty()?"":"\n"+state));
        if(!state.isEmpty()) value.setSpan(new android.text.style.RelativeSizeSpan(0.65f),
                label.length()+1,value.length(),0);
        cell.setText(value);
        cell.setContentDescription("第"+episodes.get(position).index+"集"+(state.isEmpty()?"":"，"+state));
        cell.setTextColor(focus?Ui.BG:current?Ui.PRIMARY:Ui.TEXT_2);
        cell.setBackground(Ui.roundStroke(this,focus?Ui.PRIMARY:current?Ui.TINT_PRIMARY:0x12FFFFFF,
                6,focus?Ui.PRIMARY:current?Ui.PRIMARY:0x38FFFFFF,focus?2:1));
    }
    private void refreshEpisodeCells(){
        if(episodeGrid==null || episodes==null)return;
        for(int n=0;n<episodeGrid.getChildCount();n++){
            View child=episodeGrid.getChildAt(n);if(!(child instanceof TextView) || !(child.getTag() instanceof Integer))continue;
            int pos=(Integer)child.getTag();if(pos<episodes.size())paintEpisode((TextView)child,pos,episodeGrid.hasFocus() && pos==episodePage*100+episodeGrid.getSelectedItemPosition());
        }
    }
    private void closeEpisodes() {
        if(!isEpisodeOpen()) return;
        if(episodeSheet!=null)episodeSheet.animate().cancel();
        episodeOverlay.setVisibility(View.GONE);
        restoreChrome(episodeChromeBefore,episodeButton);
    }
    private boolean controlFocused() {
        View focused=getCurrentFocus();
        return focused==centerToggle || focused!=null && controls.contains(focused);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK){
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)handleBack();
            return true;
        }
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return super.dispatchKeyEvent(event);
        int key=event.getKeyCode();
        if(chromeVisible)scheduleHide();
        if(key!=KeyEvent.KEYCODE_DPAD_UP && key!=KeyEvent.KEYCODE_DPAD_DOWN)lastEpisodeTapKey=KeyEvent.KEYCODE_UNKNOWN;
        if(isEpisodeOpen()){
            if(key==KeyEvent.KEYCODE_BACK || key==KeyEvent.KEYCODE_MENU){closeEpisodes();return true;}
            return super.dispatchKeyEvent(event);
        }
        if(key==KeyEvent.KEYCODE_MENU){revealControls();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_NEXT){nextEpisode();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PREVIOUS){playAt(index-1);return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){togglePause();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY){if(paused || ended)togglePause();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PAUSE){if(!paused)togglePause();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key==KeyEvent.KEYCODE_MEDIA_REWIND){seekBy(key==KeyEvent.KEYCODE_MEDIA_REWIND?-10000:10000);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT){
            if(chromeVisible && controlFocused() && getCurrentFocus()!=centerToggle && event.getRepeatCount()==0){scheduleHide();return moveControlFocus(key==KeyEvent.KEYCODE_DPAD_LEFT?View.FOCUS_LEFT:View.FOCUS_RIGHT);}
            int step=event.getRepeatCount()>8?30000:10000;seekBy(key==KeyEvent.KEYCODE_DPAD_LEFT?-step:step);return true;
        }
        if(key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN){
            if(event.getRepeatCount()>0)return true;
            long now=android.os.SystemClock.elapsedRealtime();
            // Only a gesture begun with hidden controls may change episodes.
            if(lastEpisodeTapKey==key && now-lastEpisodeTapAt<=320){
                lastEpisodeTapKey=KeyEvent.KEYCODE_UNKNOWN;
                if(key==KeyEvent.KEYCODE_DPAD_UP)playAt(index-1);else nextEpisode();
                return true;
            }
            lastEpisodeTapKey=KeyEvent.KEYCODE_UNKNOWN;
            if(!chromeVisible){lastEpisodeTapKey=key;lastEpisodeTapAt=now;revealControls();return true;}
            View current=getCurrentFocus();
            if(current==centerToggle){if(key==KeyEvent.KEYCODE_DPAD_DOWN)playButton.requestFocus();}
            else if(current==timeline){centerToggle.requestFocus();}
            else if(controls.contains(current)){
                if(key==KeyEvent.KEYCODE_DPAD_UP)centerToggle.requestFocus();
            }else centerToggle.requestFocus();
            scheduleHide();
            return true;
        }
        if(key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER){
            if(getCurrentFocus()==timeline){if(event.getRepeatCount()==0)togglePause();return true;}
            if(!chromeVisible || getCurrentFocus()==root){
                if(event.getRepeatCount()==0)revealControls();return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private boolean moveControlFocus(int direction){
        View current=getCurrentFocus();
        if(current==centerToggle)return true;
        View next=FocusFinder.getInstance().findNextFocus(deck,current,direction);
        if(next!=null && controls.contains(next))next.requestFocus(direction);return true;
    }
    private void handleBack(){
        if(commentsDialog!=null && commentsDialog.isShowing()){com.hgtv.app.ui.widget.CommentsDialog.back(commentsDialog);return;}
        if(settingsDialog!=null && settingsDialog.isShowing()){settingsDialog.dismiss();return;}
        if(isEpisodeOpen()){closeEpisodes();return;}
        if(!chromeVisible){revealControls();return;}
        finish();
    }
    @Override public void onBackPressed(){handleBack();}
    private boolean loadingRequested;
    private long loadingStartedAt;
    private final Runnable delayedLoading=()->{
        if(!loadingRequested || destroyed || !foreground)return;
        loading.setVisibility(View.VISIBLE);updateLoadingMeter();
        ui.removeCallbacks(this.loadingTick);ui.postDelayed(this.loadingTick,500);
    };
    private void setLoading(boolean show,String message) {
        com.hgtv.app.remote.PlaybackControl.loading(show);
        if(message!=null)loadingText.setText(message);
        if(show&&!loadingRequested)loadingStartedAt=android.os.SystemClock.elapsedRealtime();
        loadingRequested=show;ui.removeCallbacks(delayedLoading);ui.removeCallbacks(loadingTick);
        if(!show)loading.setVisibility(View.GONE);
        else if(failed || loading.getVisibility()==View.VISIBLE)delayedLoading.run();
        else if(foreground)ui.postDelayed(delayedLoading,Math.max(0,1000-(android.os.SystemClock.elapsedRealtime()-loadingStartedAt)));
        if(centerToggle!=null){centerToggle.animate().cancel();centerToggle.setVisibility(!show&&chromeVisible?View.VISIBLE:View.INVISIBLE);
            if(!show&&chromeVisible){centerToggle.setAlpha(1f);centerToggle.requestFocus();}}
    }
    private final Runnable loadingTick=new Runnable(){public void run(){
        if(destroyed || !foreground || loading==null || loading.getVisibility()!=View.VISIBLE)return;
        updateLoadingMeter();ui.postDelayed(this,500);
    }};
    private void updateLoadingMeter(){
        if(loadingDetail==null)return;
        StreamingSource source=stream!=null?stream:pendingStream;
        long now=android.os.SystemClock.elapsedRealtime(),bytes=source==null?openingReceivedBytes:source.receivedBytes();
        if(source!=measuredSource){measuredSource=source;measuredAt=now;measuredBytes=bytes;}
        long elapsed=now-measuredAt,rate=elapsed>0?Math.max(0,bytes-measuredBytes)*1000/elapsed:0;
        loadingProgress.setIndeterminate(bufferedPercent<=0);if(bufferedPercent>0)loadingProgress.setProgress(bufferedPercent);
        String detail=bufferedPercent>0?"缓冲 "+bufferedPercent+"%":"";
        if(source!=null || bytes>0){detail+=(detail.isEmpty()?"":" · ")+"已接收 "+byteSize(bytes)+" · "+byteSize(rate)+"/s";}
        if(detail.isEmpty())detail=failed?"按确定重试":prepared?"正在解码首帧":"正在获取播放信息";
        loadingDetail.setText(detail);
        if(elapsed>=500){measuredAt=now;measuredBytes=bytes;}
    }
    private static String byteSize(long bytes){return bytes>=1024*1024?String.format(Locale.US,"%.1f MB",bytes/1048576f):bytes>=1024?String.format(Locale.US,"%.0f KB",bytes/1024f):bytes+" B";}
    private void fail(int seq,String message) {
        if(seq!=sequence || isFinishing()) return;
        failed=true; releasePlayer();
        setLoading(true,message+"\n按确定重试，按返回退出");
        showChrome(); root.requestFocus();
    }
    private void releasePlayer() {
        if(startMonitor!=null){ui.removeCallbacks(startMonitor);startMonitor=null;}
        if(stream!=null){stream.close();stream=null;}
        prepared=false;appliedSpeed=1f; ui.removeCallbacks(tick);
        if(player!=null) {
            try { player.reset(); player.release(); } catch(RuntimeException ignored) { }
            player=null;
        }
    }
    private void resumeAfterSurfaceReady(){
        if(!resumeAfterPause || !foreground || !surfaceReady || commentsOpen || ended || failed)return;
        paused=false;restorePaused=false;
        if(prepared && player!=null)try{player.start();monitorStart(sequence,player);}
        catch(IllegalStateException error){com.hgtv.app.media.PlaybackDiagnostics.event("surface_resume_failed",error.getClass().getSimpleName());}
        resumeAfterPause=false;refreshPauseState();scheduleHide();
    }
    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady=true;
        if(pendingFile!=null || pendingStream!=null) attachPending();
        else if(player!=null) try { player.setDisplay(holder); } catch(IllegalStateException ignored) { }
        resumeAfterSurfaceReady();
    }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) { attachPending(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady=false;
        if(player!=null) {
            boolean shouldResume=foreground && !paused && !commentsOpen && !ended && !failed;
            if(shouldResume){
                resumeAfterPause=true;
                try{player.pause();com.hgtv.app.media.PlaybackDiagnostics.event("surface_lost_paused","positionMs="+(prepared?player.getCurrentPosition():-1));}
                catch(RuntimeException error){com.hgtv.app.media.PlaybackDiagnostics.event("surface_lost_pause_failed",error.getClass().getSimpleName());}
            }
            try { player.setDisplay(null); } catch(IllegalStateException ignored) { }
        }
    }
    @Override protected void onResume() {
        super.onResume(); foreground=true;
        if(loadingRequested){ui.removeCallbacks(delayedLoading);ui.postDelayed(delayedLoading,failed?0:Math.max(0,1000-(android.os.SystemClock.elapsedRealtime()-loadingStartedAt)));}
        if(loading!=null && loading.getVisibility()==View.VISIBLE){ui.removeCallbacks(loadingTick);ui.post(loadingTick);}
        resumeAfterSurfaceReady();
    }
    @Override protected void onPause() {
        savePlaybackPosition();
        foreground=false;ui.removeCallbacks(delayedLoading);
        ui.removeCallbacks(loadingTick);
        // Native startup can temporarily be stopped while the user's intent is still play.
        resumeAfterPause=!paused && !ended && !failed && !commentsOpen;
        if(prepared && player!=null) {
            try { if(player.isPlaying())player.pause(); paused=true; refreshPauseState(); }
            catch(IllegalStateException ignored) { }
        }
        super.onPause();
    }
    @Override protected void onDestroy() {
        savePlaybackPosition();
        com.hgtv.app.remote.PlaybackControl.detach(this);
        destroyed=true; sequence++; pendingFile=null;
        StreamingSource opening=openingSource;openingSource=null;if(opening!=null)opening.close();
        synchronized(warmLock){if(warmSource!=null)warmSource.close();warmSource=null;warmVid=null;warmGear=null;}
        if(settingsDialog!=null)settingsDialog.dismiss();if(commentsDialog!=null)commentsDialog.dismiss();
        if(pendingStream!=null){pendingStream.close();pendingStream=null;}
        preload.shutdownNow(); ui.removeCallbacksAndMessages(null); releasePlayer();
        super.onDestroy();
    }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show(); }
    private int dp(float value) { return Ui.dp(this,value); }

    public boolean remoteSpeedLocked(){return speedLocked;}
    public String remoteQualityKey(){Gear gear=activeGear;return prepared&&gear!=null?gear.definition:prefer;}
    public org.json.JSONArray remoteQualities(){
        org.json.JSONArray result=new org.json.JSONArray();
        List<Episode> current=episodes;int at=index;if(current==null||at<0||at>=current.size())return result;
        List<Gear> choices;synchronized(qualityChoices){choices=qualityChoices.get(current.get(at).vid);}if(choices==null)return result;
        try{TreeMap<Integer,String> sizes=new TreeMap<>();for(Gear gear:VideoDecoderSupport.compatibleHevc(choices))if(gear.definition!=null&&gear.shortSide()>0&&gear.shortSide()<=playbackCeiling)sizes.put(gear.shortSide(),gear.definition);for(Map.Entry<Integer,String> entry:sizes.entrySet())result.put(new org.json.JSONObject().put("id",entry.getValue()).put("label",entry.getKey()+"P"));}catch(Exception ignored){}
        return result;
    }
    public void remoteSetting(String action,org.json.JSONObject body){
        if(action.equals("autoplay")){App.prefs().edit().putBoolean("autoplay_next",body.optBoolean("enabled")).apply();return;}
        if(action.equals("speedLock")){speedLocked=body.optBoolean("enabled");App.prefs().edit().putBoolean("speed_lock",speedLocked).putFloat("play_speed",playbackSpeed).apply();updateSettingsLabels();return;}
        if(action.equals("speed")){
            float speed=(float)body.optDouble("speed",1);boolean valid=false;for(float rate:new float[]{.75f,1f,1.25f,1.5f,1.75f,2f})if(speed==rate)valid=true;
            if(!valid)throw new IllegalArgumentException("不支持的倍速");playbackSpeed=speed;if(speedLocked)App.prefs().edit().putFloat("play_speed",speed).apply();applyPlaybackSpeed(false);return;
        }
        String quality=body.optString("quality");org.json.JSONArray choices=remoteQualities();boolean valid=false;for(int n=0;n<choices.length();n++)if(quality.equals(choices.optJSONObject(n).optString("id")))valid=true;
        if(!valid)throw new IllegalArgumentException("此清晰度暂不可用");if(Objects.equals(quality,prefer))return;
        restorePosition=0;restorePaused=paused;if(prepared&&player!=null)restorePosition=player.getCurrentPosition();prefer=quality;App.prefs().edit().putString("quality",prefer).apply();keepRate=true;playAt(index);
    }
    public void remoteCommand(String action,int position) {
        switch(action){
            case "play":if(paused || ended)togglePause();break;
            case "pause":if(!paused)togglePause();break;
            case "seek":
                if(!prepared || player==null)throw new IllegalStateException("正在准备画面，请稍后调整进度");
                seekPreview=Math.max(0,Math.min(Math.max(0,player.getDuration()-300),position));
                commitPendingSeek();showChrome();break;
            case "episodes":openEpisodes();break;
            case "comments":openComments();break;
            case "menu":openPlaybackSettings();break;
            case "toggle":togglePause();break;
            case "next":nextEpisode();break;
            case "previous":playAt(index-1);break;
            case "forward":seekBy(10000);break;
            case "rewind":seekBy(-10000);break;
            case "back":handleBack();break;
        }
    }
    @Override protected void onNewIntent(android.content.Intent intent) {
        savePlaybackPosition();
        super.onNewIntent(intent);setIntent(intent);
        sequence++;if(pendingStream!=null){pendingStream.close();pendingStream=null;}pendingFile=null;
        if(commentsDialog!=null){commentsDialog.dismiss();commentsDialog=null;}
        if(preloadTask!=null)preloadTask.cancel(true);if(settingsDialog!=null)settingsDialog.dismiss();closeEpisodes();restorePosition=0;restorePaused=false;releasePlayer();episodes=null;clips.clear();
        seriesId=intent.getStringExtra(EXTRA_SERIES);seriesTitle=intent.getStringExtra(EXTRA_TITLE);
        if(favoriteButton!=null)favoriteButton.setText(Store.isFavorite(seriesId)?"已追剧":"追剧");
        index=intent.getIntExtra(EXTRA_INDEX,0);pendingResumePosition=intent.getIntExtra("position_ms",0);seriesDetail=null;degraded=false;loadSeries();
    }
}


