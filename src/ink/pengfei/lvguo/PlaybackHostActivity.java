package ink.pengfei.lvguo;

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
import ink.pengfei.lvguo.media.VideoCache;
import ink.pengfei.lvguo.media.VideoDecoderSupport;
import ink.pengfei.lvguo.media.StreamingSource;
import ink.pengfei.lvguo.model.Episode;
import ink.pengfei.lvguo.model.Gear;
import ink.pengfei.lvguo.net.HgApi;
import ink.pengfei.lvguo.net.Repo;
import ink.pengfei.lvguo.store.Store;
import ink.pengfei.lvguo.ui.IconView;
import ink.pengfei.lvguo.ui.Ui;
import android.media.MediaPlayer;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** TV playback with bounded streaming buffers and one episode of opening-data lookahead. */
public class PlaybackHostActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_SERIES="series_id", EXTRA_INDEX="index",
            EXTRA_TITLE="title", EXTRA_COVER="cover", EXTRA_VID="vid", EXTRA_EPISODE_NO="episode_no";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService preload=Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<String, FutureTask<List<Gear>>> models=new ConcurrentHashMap<>();
    private final LinkedHashMap<String,List<Gear>> qualityChoices=new LinkedHashMap<>();
    private final ConcurrentHashMap<String, Clip> clips=new ConcurrentHashMap<>();
    private Future<?> preloadTask,feedPreloadTask;
    private volatile String preloadEpisodeVid,feedPreloadSeriesId,feedPreloadVid;
    private final Object warmLock=new Object();
    private static final int MAX_WARMED_CLIPS=3;
    private static final long WARMED_TTL_MS=4*60_000L;
    private static final class WarmedClip {
        final String vid; final Gear gear; final StreamingSource source; final long readyAt;
        WarmedClip(String vid,Gear gear,StreamingSource source){this.vid=vid;this.gear=gear;this.source=source;this.readyAt=android.os.SystemClock.elapsedRealtime();}
    }
    private final LinkedHashMap<String,WarmedClip> warmedClips=new LinkedHashMap<>(4,.75f,true);
    private final ConcurrentHashMap<String,FutureTask<WarmedClip>> warmingByVid=new ConcurrentHashMap<>();
    private final Set<StreamingSource> openingSources=Collections.newSetFromMap(new ConcurrentHashMap<StreamingSource,Boolean>());
    /** Next episode queued inside the active Media3 player for a gapless same-drama handoff. */
    private StreamingSource queuedStream;
    private File queuedFile;
    private Gear queuedGear;
    private int queuedEpisode=-1;
    private int warmupRetryIndex=-1,warmupRetries;
    private int lastKnownDuration;
    private long playbackStartedAt;
    private boolean firstFrame;
    private boolean startupRewound,startupCorrection;
    private int startupPosition;
    private long lastVideoSyncRecoveryAt,lastMetricsSampleAt,lastMetricsDataAt,lastMetricsLogAt,lastMetricsPosition;
    private int lastMetricsFrames=-1,lastMetricsDropped=-1;
    private Runnable startMonitor;
    private volatile int sequence;
    private FrameLayout root, loading, episodeOverlay;
    private LinearLayout top, deck, episodeSheet;
    private TextView title, subtitle, time, readyHint, loadingText, episodeButton;
    private FrameLayout centerToggle;
    private IconView centerToggleIcon;
    private ink.pengfei.lvguo.ui.widget.ProgressTrack timeline;
    private TextView remain;
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
    private TextView favoriteButton,commentButton,danmakuButton;
    private ink.pengfei.lvguo.ui.widget.DanmakuView danmaku;
    /** 本集弹幕源（本集评论），按集时长均匀铺开。 */
    private final List<String> bullets=new ArrayList<>();
    private int bulletNext,bulletLastPosition;
    private String bulletVid;
    // 找剧模式：一队推荐短剧，上/下切换下一部；某部看进去后可「只看这部」退出找剧。
    private android.widget.ImageView switchCover,outgoingImage;
    private View incomingMask;
    private FrameLayout outgoingFrame;
    private android.graphics.Bitmap outgoingBitmap;
    private android.animation.ValueAnimator switchAnimator;
    private int pendingSlideDir,feedSwitchToken;
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
        if (isEmbeddedPlaybackHost()) return;
        if(ink.pengfei.lvguo.care.RestMode.enforce(this))return;
        initializePlayback(getIntent(),false);
    }
    protected boolean isEmbeddedPlaybackHost(){return false;}
    private android.content.Intent embeddedIntent;
    private boolean embeddedActive;
    public android.content.Intent playbackIntent(){return embeddedIntent!=null?embeddedIntent:getIntent();}
    private void initializePlayback(android.content.Intent intent,boolean embedded){
        if(embedded)embeddedIntent=intent;
        Ui.applyTheme(this);
        ink.pengfei.lvguo.media.PlaybackWarmup.cancel();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        seriesId=playbackIntent().getStringExtra(EXTRA_SERIES);
        seriesTitle=playbackIntent().getStringExtra(EXTRA_TITLE);
        index=playbackIntent().getIntExtra(EXTRA_INDEX,0);
        pendingResumePosition=playbackIntent().getIntExtra("position_ms",0);
        prefer=App.prefs().getString("quality","720p");if(prefer==null)prefer="720p";

        speedLocked=App.prefs().getBoolean("speed_lock",false);
        playbackSpeed=speedLocked?App.prefs().getFloat("play_speed",1f):1f;
        feedMode=playbackIntent().getBooleanExtra(EXTRA_FEED,false) && (seriesId==null || seriesId.isEmpty());
        buildUi();
        ink.pengfei.lvguo.remote.PlaybackControl.attach(this);
        if(feedMode){buildFeedUi();loadFeed();}else loadSeries();
    }

    private GradientDrawable scrim(boolean fromTop) {
        return new GradientDrawable(fromTop ? GradientDrawable.Orientation.TOP_BOTTOM
                : GradientDrawable.Orientation.BOTTOM_TOP,
                fromTop ? new int[]{0xB3000000,0x59000000,0x00000000} : new int[]{0xF2000000,0xC2000000,0x66000000,0x00000000});
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
        surface.setOnClickListener(v->{ if(feedMode){showFeedInfo();return;} if(chromeVisible) hideChrome(); else revealControls(); });
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        danmaku=new ink.pengfei.lvguo.ui.widget.DanmakuView(this);
        root.addView(danmaku,new FrameLayout.LayoutParams(-1,-1));

        top=Ui.column(this);
        top.setPadding(dp(56),dp(34),dp(56),dp(56));
        top.setBackground(scrim(true));
        title=text(seriesTitle,28,0xFFFFFFFF,true);
        title.setShadowLayer(dp(8),0,dp(1),0x80000000);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title,Ui.lp(-1,-2));
        subtitle=text("正在准备播放",15,0xCCFFFFFF,false);
        LinearLayout.LayoutParams sub=Ui.lp(-1,-2); sub.topMargin=dp(8);
        top.addView(subtitle,sub);
        root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

        deck=Ui.column(this);
        // 控制条贴近底部：底部 22dp 安全边距，上方留给渐变压暗
        deck.setPadding(dp(48),dp(64),dp(48),dp(22));
        deck.setBackground(scrim(false));deck.setClipChildren(false);deck.setClipToPadding(false);

        // 进度行：当前时间 ─── 进度条 ─── 剩余时间
        LinearLayout progressRow=Ui.row(this);progressRow.setGravity(Gravity.CENTER_VERTICAL);
        time=text("00:00",13,0xE6FFFFFF,true);time.setFontFeatureSettings("tnum");time.setMinWidth(dp(52));
        if(!feedMode)progressRow.addView(time);
        timeline=new ink.pengfei.lvguo.ui.widget.ProgressTrack(this);timeline.setMax(1000);timeline.setId(View.generateViewId());
        LinearLayout.LayoutParams tl=Ui.lp(0,dp(20),1);tl.leftMargin=dp(10);tl.rightMargin=dp(10);
        if(feedMode)progressRow.addView(new View(this),Ui.lp(0,1,1f));
        else progressRow.addView(timeline,tl);
        remain=text("-00:00",13,0xB3FFFFFF,true);remain.setFontFeatureSettings("tnum");remain.setMinWidth(dp(52));remain.setGravity(Gravity.RIGHT);
        if(!feedMode){progressRow.addView(remain);deck.addView(progressRow,Ui.lp(-1,-2));}
        readyHint=text("",12,0xB3FFFFFF,false);
        LinearLayout.LayoutParams hintLp=Ui.lp(-2,-2);hintLp.topMargin=dp(2);hintLp.leftMargin=dp(62);
        deck.addView(readyHint,hintLp);

        LinearLayout transport=Ui.row(this);transport.setGravity(Gravity.CENTER_VERTICAL);transport.setClipChildren(false);transport.setClipToPadding(false);
        previousButton=addControl(transport,"",IconView.PREVIOUS,false,()->playAt(index-1));
        rewindButton=addControl(transport,"",IconView.REWIND,false,()->seekBy(-10000));
        playButton=addControl(transport,"",IconView.PAUSE,false,this::togglePause);
        forwardButton=addControl(transport,"",IconView.FAST_FORWARD,false,()->seekBy(10000));
        nextButton=addControl(transport,"",IconView.NEXT,false,this::nextEpisode);
        // 图标按钮：44dp 正圆，左右内边距 = (44-20)/2，图标严格居中；播放键与其它键同尺寸
        for(TextView button:new TextView[]{previousButton,rewindButton,playButton,forwardButton,nextButton}){
            LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)button.getLayoutParams();lp.width=dp(44);lp.height=dp(44);lp.leftMargin=0;lp.rightMargin=dp(10);
            button.setMinHeight(dp(44));button.setMinWidth(dp(44));button.setPadding(dp(12),0,dp(12),0);button.setCompoundDrawablePadding(0);
        }
        transport.addView(new View(this),new LinearLayout.LayoutParams(0,1,1f));
        previousButton.setContentDescription("上一集");nextButton.setContentDescription("下一集");rewindButton.setContentDescription("快退10秒");forwardButton.setContentDescription("快进10秒");
        LinearLayout.LayoutParams transportLp=Ui.lp(-1,dp(48));transportLp.topMargin=dp(8);deck.addView(transport,transportLp);
        LinearLayout tools=transport;
        episodeButton=addControl(tools,"选集",IconView.EPISODES,false,this::openEpisodes);
        speedButton=addControl(tools,"倍速 1.0×",IconView.SPEED,false,this::openSpeed);
        qualityButton=addControl(tools,"720P",IconView.QUALITY,false,this::openQuality);
        danmakuButton=addControl(tools,ink.pengfei.lvguo.ui.widget.DanmakuView.enabled()?"弹幕":"弹幕关",IconView.DANMAKU,false,this::openDanmaku);
        // 追剧是常用操作：直接放在工具条上；评论、连播等低频操作收进「更多」
        favoriteButton=addControl(tools,"追剧",IconView.STAR,false,this::toggleFavorite);
        if(feedMode){
            feedFullscreenButton=addControl(tools,"全屏",IconView.EXPAND,false,()->setFeedFullscreen(!feedFullscreen));
            for(TextView button:new TextView[]{episodeButton,speedButton,qualityButton,danmakuButton,favoriteButton,feedFullscreenButton})Ui.padding(button,10,0,12,0);
            deck.setPadding(dp(32),dp(40),dp(32),dp(12));
        }else commentButton=addControl(tools,"更多",IconView.MORE,false,this::openPlaybackSettings);
        updateSettingsLabels();
        root.addView(deck,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));

        centerToggle=new FrameLayout(this);
        centerToggle.setFocusable(true);centerToggle.setFocusableInTouchMode(true);centerToggle.setClickable(true);centerToggle.setContentDescription("播放或暂停");
        centerToggle.setBackground(Ui.roundStroke(this,0x59000000,36,0x40FFFFFF,1));
        centerToggleIcon=new IconView(this,IconView.PAUSE,28);centerToggleIcon.setColor(0xFFFFFFFF);
        centerToggle.addView(centerToggleIcon,new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER));
        centerToggle.setOnClickListener(v->{togglePause();scheduleHide();});
        centerToggle.setOnFocusChangeListener((v,has)->{
            centerToggle.setBackground(has?Ui.pill(this,Ui.FOCUS_FILL):Ui.roundStroke(this,0x59000000,36,0x40FFFFFF,1));
            centerToggleIcon.setColor(has?Ui.ON_FOCUS:0xFFFFFFFF);
            scheduleHide();
        });
        centerToggle.setVisibility(View.GONE);
        root.addView(centerToggle,new FrameLayout.LayoutParams(dp(72),dp(72),Gravity.CENTER));

        loading=new FrameLayout(this);loading.setVisibility(View.GONE);
        loading.setBackgroundColor(0x33000000);
        LinearLayout loadCard=Ui.column(this); loadCard.setGravity(Gravity.CENTER);
        loadingText=text("正在准备好剧…",16,0xFFFFFFFF,true);
        loadingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams loadingLabel=Ui.lp(-2,-2); loadingLabel.topMargin=dp(18);
        loadCard.addView(loadingText,loadingLabel);
        loadingProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);loadingProgress.setMax(100);loadingProgress.setProgressTintList(ColorStateList.valueOf(0xFFFFFFFF));loadingProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));loadingProgress.setIndeterminateTintList(ColorStateList.valueOf(0xFFFFFFFF));
        LinearLayout.LayoutParams meterLp=Ui.lp(dp(220),dp(4));meterLp.topMargin=dp(14);loadCard.addView(loadingProgress,meterLp);
        loadingDetail=text("正在获取播放信息",12,0xB3FFFFFF,false);loadingDetail.setGravity(Gravity.CENTER);LinearLayout.LayoutParams detailLp=Ui.lp(-2,-2);detailLp.topMargin=dp(10);loadCard.addView(loadingDetail,detailLp);
        loading.addView(loadCard,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        loading.setOnClickListener(v->{ if(failed) playAt(index); });
        root.addView(loading,new FrameLayout.LayoutParams(-1,-1));
        centerToggle.bringToFront();

        episodeOverlay=new FrameLayout(this);
        episodeOverlay.setBackgroundColor(0x99000000);
        episodeOverlay.setVisibility(View.GONE);
        episodeOverlay.setOnClickListener(v->closeEpisodes());
        root.addView(episodeOverlay,new FrameLayout.LayoutParams(-1,-1));
        buildOverlays();
        if (!isEmbeddedPlaybackHost()) setContentView(root);
        ink.pengfei.lvguo.net.Session.listen(sessionWatcher);
        // 换剧过场：切剧时的旧画面快照 + 准备期的黑遮罩 + 慢加载时的全屏封面
        incomingMask=new View(this);
        incomingMask.setBackgroundColor(Color.BLACK);
        incomingMask.setVisibility(View.GONE);
        root.addView(incomingMask,1,new FrameLayout.LayoutParams(-1,-1));

        outgoingFrame=new FrameLayout(this);
        outgoingFrame.setBackgroundColor(Color.BLACK);
        outgoingFrame.setVisibility(View.GONE);
        outgoingImage=new android.widget.ImageView(this);
        outgoingImage.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        outgoingFrame.addView(outgoingImage,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        root.addView(outgoingFrame,2,new FrameLayout.LayoutParams(-1,-1));

        switchCover=new android.widget.ImageView(this);
        switchCover.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        switchCover.setBackgroundColor(Color.BLACK);
        switchCover.setVisibility(View.GONE);
        root.addView(switchCover,3,new FrameLayout.LayoutParams(-1,-1));
        if(isEmbeddedPlaybackHost()){
            chromeVisible=false;
            top.setVisibility(View.INVISIBLE);
            deck.setVisibility(View.INVISIBLE);
            deck.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            centerToggle.setVisibility(View.INVISIBLE);
        }else revealControls();
    }
    private TextView addControl(LinearLayout row,String label,int icon,boolean primary,Runnable action) {
        TextView button=Ui.action(this,label,icon,primary);
        button.setId(View.generateViewId());
        playbackButtonState(button,false);
        Ui.padding(button,14,0,16,0);button.setMinHeight(dp(40));button.setTextSize(14);
        button.setOnClickListener(v->{ action.run(); scheduleHide(); });
        LinearLayout.LayoutParams lp=Ui.lp(-2,dp(40)); lp.gravity=Gravity.CENTER_VERTICAL;
        if(label.length()>0 && row.getChildCount()>0 && row.getChildAt(row.getChildCount()-1) instanceof TextView)lp.leftMargin=dp(8);
        row.addView(button,lp); controls.add(button);
        button.setOnFocusChangeListener((v,focus)->{
            if(focus){lastControlId=v.getId();ui.removeCallbacks(hideTask);}
            playbackButtonState(button,focus);Ui.icon(button,button==playButton?((paused||ended)?IconView.PLAY:IconView.PAUSE):icon,button.getCurrentTextColor(),20);if(button==favoriteButton && Store.isFavorite(seriesId) && button.getCompoundDrawables()[0] instanceof ink.pengfei.lvguo.ui.IconDrawable)((ink.pengfei.lvguo.ui.IconDrawable)button.getCompoundDrawables()[0]).setFilled(true);if(button.getText().length()==0)button.setCompoundDrawablePadding(0);
            scheduleHide();
        });
        return button;
    }
    private void playbackButtonState(TextView button,boolean focus){
        button.setTextColor(focus?0xFF111113:0xFFFFFFFF);
        Ui.tintIcons(button,button.getCurrentTextColor());
        button.setBackground(Ui.pill(this,focus?0xFFF5F5F7:0x66000000));
    }
    private void playbackTile(TextView view,boolean focused,boolean selected,int radius){
        int fill=focused?0xFFF5F5F7:selected?Ui.PRIMARY:0x22FFFFFF;
        view.setBackground(Ui.round(this,fill,radius));
        view.setTextColor(focused||selected?0xFF111113:0xFFFFFFFF);
        view.setTypeface(null,focused||selected?Typeface.BOLD:Typeface.NORMAL);
    }
    private void showChrome() {
        if(isEmbeddedPlaybackHost() && !embeddedActive)return;
        boolean opening=!chromeVisible;
        chromeVisible=true;
        // 控制条出现时弹幕退到背景，不和顶部剧名、集数叠在一起
        if(danmaku!=null){danmaku.animate().cancel();danmaku.animate().alpha(.2f).setDuration(180).start();}
        deck.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        top.setVisibility((feedMode||feedReturn)?View.INVISIBLE:View.VISIBLE); if((feedMode||feedReturn) && feedLayer!=null)feedLayer.setVisibility(View.VISIBLE); deck.setVisibility(View.VISIBLE);centerToggle.setVisibility(loadingRequested?View.INVISIBLE:View.VISIBLE);
        top.animate().cancel(); deck.animate().cancel();
        top.animate().alpha(1).setDuration(160).start();
        deck.animate().alpha(1).setDuration(160).start();
        centerToggle.animate().cancel();if(opening){centerToggle.setAlpha(0f);centerToggle.animate().alpha(1f).setDuration(160).start();}else centerToggle.setAlpha(1f);
        scheduleHide();
    }
    private void revealControls(){showChrome();refreshPauseState();if(centerToggle.getVisibility()==View.VISIBLE)centerToggle.requestFocus();else playButton.requestFocus();}
    private void restoreChrome(boolean visible,View focus){
        if(isEmbeddedPlaybackHost() && !embeddedActive)return;
        if(visible){showChrome();if(focus!=null && (controls.contains(focus)||focus==centerToggle))focus.requestFocus();else centerToggle.requestFocus();}
        else hideChrome();
    }
    private void scheduleHide() {
        ui.removeCallbacks(hideTask);
        if(chromeVisible && !overlayOpen()) ui.postDelayed(hideTask,4500);
    }
    private void hideChrome() {
        chromeVisible=false; ui.removeCallbacks(hideTask);
        if(feedLayer!=null && (feedMode||feedReturn))feedLayer.setVisibility(View.INVISIBLE);
        if(danmaku!=null){danmaku.animate().cancel();danmaku.animate().alpha(ink.pengfei.lvguo.ui.widget.DanmakuView.ALPHAS[ink.pengfei.lvguo.ui.widget.DanmakuView.alphaIndex()]/100f).setDuration(220).start();}
        View focused=focusedView();
        if(focused==centerToggle || focused==timeline || controls.contains(focused))root.requestFocus();
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
        final String id=seriesId,vid=playbackIntent().getStringExtra(EXTRA_VID);
        final int selected=index,episodeNo=playbackIntent().getIntExtra(EXTRA_EPISODE_NO,1),request=++sequence;
        Repo.Detail memory=Repo.memoryDetail(id);
        if(memory!=null){applyLoadedSeries(memory,selected,vid,episodeNo);return;}
        setLoading(true,"正在载入剧集…");
        App.io().execute(()->{
            try {
                Repo.Detail detail=Repo.detail(id);
                ui.post(()->{
                    if(destroyed || request!=sequence)return;
                    applyLoadedSeries(detail,selected,vid,episodeNo);
                });
            }catch(Exception error){ui.post(()->{
                if(destroyed || request!=sequence)return;
                if(vid!=null && !vid.isEmpty()){
                    Episode ep=new Episode();ep.vid=vid;ep.index=episodeNo;
                    episodes=new ArrayList<>();episodes.add(ep);degraded=true;playAt(0);
                }else showError(request,ink.pengfei.lvguo.media.PlaybackError.of(ink.pengfei.lvguo.media.PlaybackError.online(this)
                        ?ink.pengfei.lvguo.media.PlaybackError.Kind.SERIES:ink.pengfei.lvguo.media.PlaybackError.Kind.OFFLINE,error));
            });}
        });
    }
    private void applyLoadedSeries(Repo.Detail detail,int selected,String vid,int episodeNo){
        seriesDetail=detail;episodes=detail.episodes;
        if(seriesTitle==null || seriesTitle.isEmpty())seriesTitle=detail.title;
        index=selected;
        if(vid!=null && !vid.isEmpty()){
            boolean found=false;
            for(int n=0;n<episodes.size();n++)if(vid.equals(episodes.get(n).vid)){index=n;found=true;break;}
            // A stale/reordered detail list must never silently substitute another episode.
            if(!found){
                Episode exact=new Episode();exact.vid=vid;exact.index=episodeNo;
                episodes=new ArrayList<>();episodes.add(exact);index=0;degraded=true;
            }
        }
        playAt(Math.max(0,Math.min(index,episodes.size()-1)));
    }
    private List<Gear> gearsFor(Episode episode) throws Exception {
        String vid=episode.vid;
        FutureTask<List<Gear>> created=new FutureTask<>(()->Repo.play(vid,HgApi.DEVICE_LEVEL_H265));
        FutureTask<List<Gear>> existing=models.putIfAbsent(vid,created);
        FutureTask<List<Gear>> task=existing==null?created:existing;
        try {
            task.run();List<Gear> result=task.get();
            synchronized(qualityChoices){qualityChoices.remove(vid);qualityChoices.put(vid,result);while(qualityChoices.size()>6)qualityChoices.remove(qualityChoices.keySet().iterator().next());}
            return result;
        } catch(ExecutionException error) {
            Throwable cause=error.getCause();
            if(cause instanceof Exception) throw (Exception)cause;
            throw new java.io.IOException("加载播放源失败",cause);
        } finally { if(task.isDone()) models.remove(vid,task); }
    }
    private Clip prepareClip(Episode episode,VideoCache.Progress progress) throws Exception {
        Gear gear=pickPlaybackGear(gearsFor(episode));
        Clip hit=clips.get(episode.vid);
        if(hit!=null && hit.exists() && gear!=null && java.util.Objects.equals(hit.gear.definition,gear.definition))return hit;
        if(gear==null) throw new IllegalStateException("没有可用播放源");
        File file=VideoCache.prepare(App.ctx(),episode.vid,gear,progress);
        Clip clip=new Clip(file,gear); clips.put(episode.vid,clip);
        return clip;
    }
    private int compatSequence=-1;
    private boolean reusePlayer,reuseFeedPlayer;   // 本次 playAt 是否复用现有播放器做无缝换集/换剧（见 playAt / beginEpisodeSwitch / attachPending）
    private volatile int playbackCeiling=ink.pengfei.lvguo.media.PlaybackCompatibility.maxShortSide();
    /** 本集选中的档位高于当前清晰度上限（该集只有这一档可选）。 */
    private boolean forcedAboveCeiling;
    /** 当前这一集用的是解码阶梯的第几级（见 PlaybackCompatibility）。 */
    private volatile int rung=ink.pengfei.lvguo.media.PlaybackCompatibility.RUNG_MEDIA3;
    private int networkRetries;
    private int sourceRetries,profileRefreshes;
    private boolean waitingForNetwork,profileRefreshInFlight;
    private int recoveryPosition;
    private String recoveryVid;
    private boolean retainRung,usingMedia3;
    // 「首帧迟迟不出」多半是网络慢（1080p 一集 8MB 左右），不一定是解码不了：这种降档只算数本集，
    // 连续两集都这样才记成「这台设备 1080p 不行」（记 30 分钟）。真正的解码报错仍然立即记。
    private boolean rungFromTimeout;
    private static int timeoutDowngrades;
    private String switchMessage;
    private int effectiveCeiling(){return Math.min(playbackCeiling,ink.pengfei.lvguo.media.PlaybackCompatibility.ceiling(rung));}
    /** 播放器侧旁证：数据流最近读失败过，或准备阶段几乎没收到数据 → 更像网络问题而不是解码问题。 */
    private boolean networkSuspect(){
        StreamingSource source=stream!=null?stream:pendingStream;
        if(source!=null && source.failedRecently(12000))return true;
        long received=source!=null?source.receivedBytes():openingReceivedBytes;
        return !firstFrame && source!=null && received<128*1024;
    }
    private int restartPosition(){
        int position=startupPosition;
        if(prepared && player!=null && firstFrame)try{position=player.getCurrentPosition();}catch(IllegalStateException ignored){}
        return Math.max(0,position);
    }
    /**
     * 统一的故障恢复入口。按故障类型决定策略：
     * 网络与片源错误先在后台刷新模型、轮换匿名播放身份并重连同一集；
     * 解码错误逐步降低清晰度。只有这些恢复手段都耗尽后才展示错误页。
     */
    private void fallback(int seq,Exception cause){
        if(seq!=sequence || destroyed || failed)return;
        ink.pengfei.lvguo.media.PlaybackError error=ink.pengfei.lvguo.media.PlaybackError.classify(this,cause,networkSuspect());
        ink.pengfei.lvguo.media.PlaybackDiagnostics.event("recovery","rung="+rung+" kind="+error.kind+" "+cause);
        android.util.Log.w("TVPlayback","Recovery rung="+rung+" kind="+error.kind,cause);
        final int position=restartPosition();
        final String vid=episodes!=null && index>=0 && index<episodes.size()?episodes.get(index).vid:null;
        switch(error.kind){
            case OFFLINE:
                waitForNetwork(seq,position,vid);return;
            case UNAVAILABLE:
                if(sourceRetries<ink.pengfei.lvguo.media.PlaybackRecoveryPolicy.SOURCE_RETRY_LIMIT){
                    sourceRetries++;
                    refreshPlaySource(vid);
                    restartEpisode(position,"正在缓冲…",ink.pengfei.lvguo.media.PlaybackRecoveryPolicy.retryDelayMs(Math.max(1,sourceRetries)));
                }else if(profileRefreshes<1){
                    profileRefreshes++;
                    restartWithFreshProfile(seq,position,vid,0L);
                }else showError(seq,error);
                return;
            case NETWORK: case LICENSE:
                if(networkRetries<ink.pengfei.lvguo.media.PlaybackRecoveryPolicy.NETWORK_RETRY_LIMIT){
                    networkRetries++;
                    long delay=ink.pengfei.lvguo.media.PlaybackRecoveryPolicy.retryDelayMs(networkRetries);
                    if(sourceRetries<ink.pengfei.lvguo.media.PlaybackRecoveryPolicy.SOURCE_RETRY_LIMIT){
                        sourceRetries++;refreshPlaySource(vid);
                        restartEpisode(position,"正在缓冲…",delay);
                    }else if(profileRefreshes<1){
                        profileRefreshes++;
                        restartWithFreshProfile(seq,position,vid,delay);
                    }else restartEpisode(position,"正在缓冲…",delay);
                }else showError(seq,error);
                return;
            default:
                // 已超出当前清晰度上限的唯一档位仍然解码失败，说明本机确实放不了，直接报错避免无限重试。
                if(forcedAboveCeiling){showError(seq,error);return;}
                int current=activeGear==null?effectiveCeiling():activeGear.shortSide();
                if(rung<ink.pengfei.lvguo.media.PlaybackCompatibility.RUNG_LAST){
                    rung++;
                    ink.pengfei.lvguo.media.PlaybackDiagnostics.event("decode_rung",ink.pengfei.lvguo.media.PlaybackCompatibility.label(rung));
                    restartEpisode(position,"正在缓冲…",0);
                }else{
                    int lower=ink.pengfei.lvguo.media.PlaybackRecoveryPolicy.nextLowerQuality(current);
                    if(lower<=0){showError(seq,error);return;}
                    playbackCeiling=Math.min(playbackCeiling,lower);
                    ink.pengfei.lvguo.media.PlaybackDiagnostics.event("decode_quality",playbackCeiling+"P");
                    restartEpisode(position,"正在缓冲…",0);
                }
        }
    }
    private void waitForNetwork(int seq,int position,String vid){
        if(seq!=sequence || destroyed)return;
        waitingForNetwork=true;recoveryPosition=position;recoveryVid=vid;
        retainRung=true;restorePosition=position;restorePaused=paused;keepRate=true;
        // Invalidate the current attempt so late callbacks cannot surface an error while
        // connectivity is down. Keep the normal loading treatment on screen.
        sequence++;releasePlayer();
        setLoading(true,"正在缓冲…");
        ui.removeCallbacks(waitForNetwork);ui.postDelayed(waitForNetwork,2000);
    }
    private void restartWithFreshProfile(int seq,int position,String vid,long delayMs){
        if(seq!=sequence || destroyed || profileRefreshInFlight)return;
        profileRefreshInFlight=true;waitingForNetwork=false;recoveryPosition=position;recoveryVid=vid;
        retainRung=true;restorePosition=position;restorePaused=paused;keepRate=true;
        sequence++;final int recoverySeq=sequence;
        releasePlayer();if(pendingStream!=null){pendingStream.close();pendingStream=null;}
        setLoading(true,"正在缓冲…");
        try{preload.execute(()->{
            try{Repo.refreshPlaybackProfile(vid);}catch(Exception error){android.util.Log.w("TVPlayback","Playback profile refresh failed; retrying with refreshed source",error);}
            ui.postDelayed(()->{
                if(recoverySeq!=sequence || destroyed)return;
                profileRefreshInFlight=false;sourceRetries=0;refreshPlaySource(vid);playAt(index);
            },Math.max(0,delayMs));
        });}catch(RejectedExecutionException error){
            profileRefreshInFlight=false;refreshPlaySource(vid);playAt(index);
        }
    }
    private boolean isExpiredMediaAddress(Throwable error){
        for(Throwable t=error;t!=null;t=t.getCause()){
            String message=t.getMessage();if(message==null)continue;
            if(message.contains("HTTP 401")||message.contains("HTTP 403")
                    ||message.contains("HTTP 404")||message.contains("HTTP 410"))return true;
        }
        return false;
    }
    private void refreshPlaySource(String vid){
        if(vid==null||vid.isEmpty())return;
        Repo.invalidatePlay(vid);
        FutureTask<WarmedClip> inflight=warmingByVid.remove(vid);
        if(inflight!=null)inflight.cancel(true);
        synchronized(warmLock){
            WarmedClip removed=warmedClips.remove(vid);
            if(removed!=null && removed.source!=null)removed.source.close();
        }
    }
    private void putWarmed(WarmedClip clip){
        if(clip==null)return;
        List<StreamingSource> evicted=new ArrayList<>();
        synchronized(warmLock){
            WarmedClip previous=warmedClips.remove(clip.vid);
            if(previous!=null && previous.source!=null && previous.source!=clip.source)evicted.add(previous.source);
            warmedClips.put(clip.vid,clip);
            while(warmedClips.size()>MAX_WARMED_CLIPS){
                Iterator<Map.Entry<String,WarmedClip>> it=warmedClips.entrySet().iterator();
                if(!it.hasNext())break;
                StreamingSource s=it.next().getValue().source;
                if(s!=null)evicted.add(s);
                it.remove();
            }
        }
        for(StreamingSource source:evicted)source.close();
    }
    private StreamingSource takeWarmed(String vid,String definition){
        if(vid==null)return null;
        WarmedClip clip;
        synchronized(warmLock){clip=warmedClips.remove(vid);}
        if(clip==null)return null;
        boolean expired=android.os.SystemClock.elapsedRealtime()-clip.readyAt>=WARMED_TTL_MS;
        if(!expired && clip.gear!=null && java.util.Objects.equals(definition,clip.gear.definition))return clip.source;
        if(clip.source!=null)clip.source.close();
        return null;
    }
    private boolean hasWarmed(String vid,String definition){
        if(vid==null)return false;
        WarmedClip stale=null;
        synchronized(warmLock){
            WarmedClip clip=warmedClips.get(vid);
            if(clip==null)return false;
            if(android.os.SystemClock.elapsedRealtime()-clip.readyAt>=WARMED_TTL_MS
                    || (definition!=null && (clip.gear==null || !java.util.Objects.equals(definition,clip.gear.definition)))){
                warmedClips.remove(vid);
                stale=clip;
            }else return true;
        }
        if(stale!=null && stale.source!=null)stale.source.close();
        return false;
    }
    private void closeAllWarmed(){
        for(FutureTask<WarmedClip> task:warmingByVid.values())task.cancel(true);
        warmingByVid.clear();
        for(StreamingSource opening:openingSources)if(opening!=null)opening.close();
        openingSources.clear();
        List<StreamingSource> active=new ArrayList<>();
        synchronized(warmLock){
            for(WarmedClip clip:warmedClips.values())if(clip.source!=null)active.add(clip.source);
            warmedClips.clear();
        }
        for(StreamingSource source:active)source.close();
    }
    /** 同一集从 position 重新起播（保留解码级别、倍速、暂停状态）。 */
    private void restartEpisode(int position,String message,long delayMs){
        restorePosition=position;restorePaused=paused;keepRate=true;retainRung=true;switchMessage=message;
        releasePlayer();if(pendingStream!=null){pendingStream.close();pendingStream=null;}
        setLoading(true,message);
        final int seq=sequence;
        if(delayMs<=0){playAt(index);return;}
        ui.postDelayed(()->{if(seq==sequence && !destroyed && !failed)playAt(index);},delayMs);
    }
    private void playAt(int target) {
        // 保活无缝换集/换剧：复用现有 Media3 播放器做 replaceSource，免去释放与重建耗时。
        reusePlayer = reuseFeedPlayer || (target!=index && prepared && firstFrame && !failed && !ended && surfaceReady
                && player instanceof ink.pengfei.lvguo.media.CompatMediaPlayer
                && !((ink.pengfei.lvguo.media.CompatMediaPlayer)player).isReleased());
        reuseFeedPlayer=false;
        waitingForNetwork=false;profileRefreshInFlight=false;ui.removeCallbacks(waitForNetwork);
        savePlaybackPosition();
        if(target!=index){keepRate=false;retainRung=false;switchMessage=null;}
        if(!retainRung){rungFromTimeout=false;rung=ink.pengfei.lvguo.media.PlaybackCompatibility.startRung();networkRetries=0;sourceRetries=0;profileRefreshes=0;playbackCeiling=ink.pengfei.lvguo.media.PlaybackCompatibility.maxShortSide();}
        retainRung=false;
        final String restartMessage=switchMessage;switchMessage=null;
        if(destroyed || isFinishing()) return;
        if(episodes==null || episodes.isEmpty()) { showError(sequence,ink.pengfei.lvguo.media.PlaybackError.of(ink.pengfei.lvguo.media.PlaybackError.Kind.UNAVAILABLE,null)); return; }
        if(target<0 || target>=episodes.size()) {
            toast(target<0?"已经是第一集":"已经是最后一集"); return;
        }
        final boolean visibleBefore=isEpisodeOpen()?episodeChromeBefore:chromeVisible;
        final View focusedBefore=focusedView();
        closeEpisodes();
        if(!keepRate){restorePosition=Math.max(0,pendingResumePosition);pendingResumePosition=0;restorePaused=false;}
        upNextCancelled=false;hideUpNext();
        ended=false;allFinished=false;buffering=false;bufferedPercent=0;openingReceivedBytes=0;measuredBytes=0;measuredAt=android.os.SystemClock.elapsedRealtime();measuredSource=null;seekPreview=-1;ui.removeCallbacks(commitSeek);lastEpisodeTapKey=KeyEvent.KEYCODE_UNKNOWN;lastVideoSyncRecoveryAt=0;lastMetricsSampleAt=0;lastMetricsDataAt=0;lastMetricsLogAt=0;lastMetricsPosition=0;lastMetricsFrames=-1;lastMetricsDropped=-1;
        if(!keepRate && !speedLocked)playbackSpeed=1f;keepRate=false;
        index=target; final int seq=++sequence;
        if(commentsDialog!=null){commentsDialog.dismiss();commentsDialog=null;}
        playbackStartedAt=android.os.SystemClock.elapsedRealtime();firstFrame=false;startupRewound=false;startupCorrection=false;startupPosition=restorePosition;progressSavedAt=0;
        beginEpisodeSwitch(); pendingFile=null; paused=false; failed=false;hideError();updateSettingsLabels();
        if(pendingStream!=null){pendingStream.close();pendingStream=null;}
        Episode episode=episodes.get(index);
        if(preloadTask!=null && !episode.vid.equals(preloadEpisodeVid)){preloadTask.cancel(true);preloadTask=null;preloadEpisodeVid=null;}
        loadingRequested=false;ui.removeCallbacks(delayedLoading);loading.setVisibility(View.GONE);
        ink.pengfei.lvguo.remote.PlaybackControl.starting(seriesId,seriesTitle,episode.index,playbackIntent().getStringExtra(EXTRA_COVER));
        title.setText(seriesTitle==null?"正在播放":seriesTitle);
        subtitle.setText("第 "+episode.index+" 集  /  "+episodes.size()+" 集    ·    "+prefer.toUpperCase(Locale.US));
        episodeButton.setText("选集");
        readyHint.setText(""); timeline.setProgress(0); timeline.setSecondaryProgress(0); setTimes(0,0);
        refreshFeedInfo();
        // The episode number in the title is enough context; keep the picture uninterrupted.
        loadDanmaku(seq,episode);
        setLoading(true,restartMessage!=null?restartMessage:"正在准备播放…");

        restoreChrome(visibleBefore,focusedBefore);
        Store.markHistory(seriesId,seriesTitle,episode.index,index,episode.vid,
                playbackIntent().getStringExtra(EXTRA_COVER));
        if(android.os.Build.VERSION.SDK_INT>28){
            List<Gear> memGears=Repo.memoryPlay(episode.vid,HgApi.DEVICE_LEVEL_H265);
            if(memGears!=null){
                synchronized(qualityChoices){qualityChoices.remove(episode.vid);qualityChoices.put(episode.vid,memGears);while(qualityChoices.size()>6)qualityChoices.remove(qualityChoices.keySet().iterator().next());}
                Gear gear=pickPlaybackGear(memGears);
                StreamingSource warmed=gear==null?null:takeWarmed(episode.vid,gear.definition);
                if(warmed!=null){
                    ink.pengfei.lvguo.media.PlaybackDiagnostics.event("source_selected",gear.definition);
                    android.util.Log.i("TVPreload","Opening episode="+episode.index+" warm=true");
                    activeGear=gear;pendingStream=warmed;pendingSequence=seq;
                    fitVideoSize(gear.width,gear.height);
                    if(surfaceReady)attachPending();else surface.post(this::attachPending);
                    return;
                }
            }
        }
        // 不在打开时上报：首帧出来后 savePlaybackPosition 才记进度，暂停/切集/退出时再上报
        App.pool().execute(()->{
            try {
                Gear gear=pickPlaybackGear(gearsFor(episode));
                ink.pengfei.lvguo.media.PlaybackDiagnostics.event("source_selected",gear==null?"none":gear.definition);
                if(seq!=sequence || destroyed)return;
                if(gear==null)throw new IllegalStateException("没有可用播放源");
                final boolean legacyHigh=android.os.Build.VERSION.SDK_INT<=28 && gear.shortSide()>720;
                File cached=VideoCache.cached(App.ctx(),episode.vid,gear.definition);
                if(cached==null){
                    FutureTask<WarmedClip> inflight=warmingByVid.get(episode.vid);
                    if(inflight!=null){
                        try{inflight.get(4,TimeUnit.SECONDS);}catch(Exception ignored){}
                        if(seq!=sequence || destroyed)return;
                        cached=VideoCache.cached(App.ctx(),episode.vid,gear.definition);
                    }
                }
                if(cached!=null) {
                    Clip clip=new Clip(cached,gear);clips.put(episode.vid,clip);
                    ui.post(()->{if(seq==sequence){if(legacyHigh)compatSequence=seq;queuePlayer(clip,seq);}});
                } else {
                    StreamingSource warmed=takeWarmed(episode.vid,gear.definition);
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
            if(feedMode)warmFeedNext(seq,feedIndex);
        }},300);
    }
    private WarmedClip warmEpisodeStream(final String vid,final Gear gear)throws Exception {
        if(vid==null || vid.isEmpty() || gear==null)return null;
        File cached=VideoCache.cached(App.ctx(),vid,gear.definition);
        if(cached==null && android.os.Build.VERSION.SDK_INT<=28 && gear.shortSide()>720)
            cached=VideoCache.prepare(App.ctx(),vid,gear,null);
        if(cached!=null || hasWarmed(vid,gear.definition))return null;
        FutureTask<WarmedClip> created=new FutureTask<>(()->{
            StreamingSource source=new StreamingSource(gear);
            openingSources.add(source);
            try {
                if(destroyed || Thread.currentThread().isInterrupted()){source.close();return null;}
                source.warmStart();
                if(destroyed || Thread.currentThread().isInterrupted()){source.close();return null;}
                WarmedClip clip=new WarmedClip(vid,gear,source);
                putWarmed(clip);
                return clip;
            }catch(Exception error){
                source.close();
                throw error;
            }finally{
                openingSources.remove(source);
            }
        });
        FutureTask<WarmedClip> existing=warmingByVid.putIfAbsent(vid,created);
        FutureTask<WarmedClip> task=existing==null?created:existing;
        try {
            task.run();
            return task.get();
        }catch(ExecutionException error){
            Throwable cause=error.getCause();
            if(cause instanceof Exception)throw (Exception)cause;
            throw new java.io.IOException("预热失败",cause);
        }finally{
            warmingByVid.remove(vid,task);
        }
    }
    private void warmUpcoming(int seq,int current) {
        if(destroyed || seq!=sequence || degraded || upNextCancelled || !App.prefs().getBoolean("autoplay_next",true)
                || episodes==null || queuedEpisode>=0) return;
        if(warmupRetryIndex!=current){warmupRetryIndex=current;warmupRetries=0;}
        final List<Episode> list=episodes;final Repo.Detail detail=seriesDetail;final String id=seriesId;
        final boolean continueSeasons=App.prefs().getBoolean("autoplay_next",true);
        final boolean allowNextSeason=!feedMode && continueSeasons;
        Episode knownNext=current+1<list.size()?list.get(current+1):null;
        preloadEpisodeVid=knownNext==null?null:knownNext.vid;
        preloadTask=preload.submit(()->{
            try {
                Episode next=knownNext;boolean nextSeason=next==null;
                if(nextSeason){
                    if(!allowNextSeason)return;
                    Repo.Season season=followingSeason(id,detail);if(season==null)return;
                    if(seq!=sequence || destroyed || Thread.currentThread().isInterrupted())return;
                    Repo.Detail following=Repo.detail(season.id);if(following.episodes.isEmpty())return;
                    next=following.episodes.get(0);
                    preloadEpisodeVid=next.vid;
                }
                if(seq!=sequence || destroyed || Thread.currentThread().isInterrupted())return;
                Gear gear=pickPlaybackGear(gearsFor(next));
                if(gear==null || seq!=sequence || destroyed || Thread.currentThread().isInterrupted())return;
                warmEpisodeStream(next.vid,gear);
                if(seq!=sequence || destroyed || Thread.currentThread().isInterrupted())return;
                android.util.Log.i("TVPreload","Ready nextSeason="+nextSeason+" episode="+next.index);
                ui.post(()->{
                    if(seq!=sequence || destroyed)return;
                    warmupRetries=0;
                    if(isEpisodeOpen())refreshEpisodeCells();
                });
            } catch(Exception error) {
                android.util.Log.w("TVPreload","Episode preload failed",error);
                ui.post(()->{
                    if(destroyed || seq!=sequence || index!=current || queuedEpisode>=0
                            || warmupRetryIndex!=current || warmupRetries>=3)return;
                    int attempt=++warmupRetries;
                    ui.postDelayed(()->warmUpcoming(seq,current),
                            ink.pengfei.lvguo.media.PlaybackRecoveryPolicy.retryDelayMs(attempt));
                });
            }
        });
    }
    /** 刷剧模式预载下一部剧「要播放的那一集」（有历史记录则续播对应集，否则第 1 集）。 */
    private void warmFeedNext(final int seq,final int currentFeedIndex){
        if(!feedMode || destroyed || seq!=sequence || currentFeedIndex<0 || currentFeedIndex+1>=feedList.size())return;
        final ink.pengfei.lvguo.model.Series nextDrama=feedList.get(currentFeedIndex+1);
        if(nextDrama==null || nextDrama.seriesId==null || nextDrama.seriesId.isEmpty())return;
        ink.pengfei.lvguo.img.ImageLoader.preload(nextDrama.cover,1080);
        if(currentFeedIndex+2<feedList.size())ink.pengfei.lvguo.img.ImageLoader.preload(feedList.get(currentFeedIndex+2).cover,1080);
        if(feedPreloadTask!=null && !feedPreloadTask.isDone() && nextDrama.seriesId.equals(feedPreloadSeriesId))return;
        if(feedPreloadTask!=null)feedPreloadTask.cancel(true);
        final Store.Item seen=Store.watched(nextDrama.seriesId);
        feedPreloadSeriesId=nextDrama.seriesId;
        feedPreloadVid=seen!=null?seen.vid:nextDrama.firstVid;
        feedPreloadTask=preload.submit(()->{
            try {
                Repo.Detail detail=null;
                try{detail=Repo.detail(nextDrama.seriesId);}catch(Exception ignored){}
                if(seq!=sequence || destroyed || Thread.currentThread().isInterrupted())return;
                Episode target=null;
                if(seen!=null && seen.vid!=null && !seen.vid.isEmpty()){
                    if(detail!=null)for(Episode ep:detail.episodes)if(seen.vid.equals(ep.vid)){target=ep;break;}
                    if(target==null){target=new Episode();target.vid=seen.vid;target.index=Math.max(1,seen.lastEpisode);}
                }else if(detail!=null && !detail.episodes.isEmpty()){
                    int at=seen!=null?Math.max(0,Math.min(seen.episodeIndex,detail.episodes.size()-1)):0;
                    target=detail.episodes.get(at);
                }else if(nextDrama.firstVid!=null && !nextDrama.firstVid.isEmpty()){
                    target=new Episode();target.vid=nextDrama.firstVid;target.index=1;
                }
                if(target==null || target.vid==null || target.vid.isEmpty())return;
                feedPreloadVid=target.vid;
                if(seq!=sequence || destroyed || Thread.currentThread().isInterrupted())return;
                Gear gear=pickPlaybackGear(gearsFor(target));
                if(gear==null || seq!=sequence || destroyed || Thread.currentThread().isInterrupted())return;
                warmEpisodeStream(target.vid,gear);
                android.util.Log.i("TVPreload","Ready nextDrama="+nextDrama.seriesId+" episode="+target.index+" vid="+target.vid);
            }catch(Exception error){
                android.util.Log.w("TVPreload","Feed next drama preload failed",error);
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
        boolean reuse=reusePlayer && player instanceof ink.pengfei.lvguo.media.CompatMediaPlayer
                && !((ink.pengfei.lvguo.media.CompatMediaPlayer)player).isReleased();
        try {
            final ink.pengfei.lvguo.media.CompatMediaPlayer media;
            if(reuse){
                media=(ink.pengfei.lvguo.media.CompatMediaPlayer)player;
                try{media.setDisplay(surface.getHolder());}catch(IllegalStateException ignored){}
            }else{
                releasePlayer();
                media=new ink.pengfei.lvguo.media.CompatMediaPlayer(this);player=media;usingMedia3=true;
                ink.pengfei.lvguo.media.PlaybackDiagnostics.source(activeGear,"Media3 hardware-only video rung="+rung);
                configurePlayer(media);
                media.setDisplay(surface.getHolder());
                media.setScreenOnWhilePlaying(true);
            }
            String mediaId=episodes.get(index).vid;
            StreamingSource previous=stream;
            if(reuse){
                // 人家那套：保活整源替换。旧画面最后一帧留着，直到新一集首帧渲染 → 无缝、不黑、不重放。
                if(incoming!=null)media.replaceSource(incoming,mediaId);
                else media.replaceSource(source.getAbsolutePath(),mediaId);
                stream=incoming;
                if(previous!=null && previous!=incoming)previous.close();
                android.util.Log.i("TVPlayback","Seamless replace -> index="+index+" vid="+mediaId);
            }else{
                stream=incoming;
                if(incoming!=null)media.setDataSource(incoming,mediaId);
                else media.setDataSource(source.getAbsolutePath(),mediaId);
                media.prepareAsync();
            }
            ink.pengfei.lvguo.media.PlaybackDiagnostics.event("av_sync_mode","media3_audio_renderer_clock");
            ui.postDelayed(()->{if(seq==sequence && player==media && !prepared && !destroyed)fallback(seq,new java.io.IOException("加载超时，请检查网络"));},20000);
        } catch(Exception error) {if(incoming!=null){incoming.close();}fallback(seq,error); }
    }

    /** 装一次监听器即可（播放器保活复用）。监听器按当前 sequence / player / mediaId 校验，不捕获旧 seq。 */
    private void configurePlayer(final ink.pengfei.lvguo.media.CompatMediaPlayer media){
        media.setOnVideoSizeChangedListener((mp,w,h)->{ if(player==media && !destroyed) fitVideoSize(w,h); });
        media.setOnCompletionListener(mp->{ if(player==mp && !destroyed) onEpisodeComplete(); });
        media.setOnSeekCompleteListener(mp->{
            if(player!=mp || destroyed || !prepared)return;
            if(startupCorrection){startupCorrection=false;videoReady(sequence,mp,"startup-rewind");}
            if(foreground && surfaceReady && !paused && !ended && !commentsOpen){
                try{mp.start();android.util.Log.i("TVPlayback","Started seq="+sequence+" after seek");monitorStart(sequence,mp);}catch(IllegalStateException error){fallback(sequence,error);}
            }else if(foreground && !surfaceReady && !paused && !ended && !commentsOpen)resumeAfterPause=true;
        });
        media.setOnBufferingUpdateListener((mp,percent)->{if(player==mp && !destroyed){bufferedPercent=Math.max(0,Math.min(100,percent));timeline.setSecondaryProgress(bufferedPercent*10);ink.pengfei.lvguo.remote.PlaybackControl.buffered(bufferedPercent);}});
        media.setOnInfoListener((mp,what,extra)->{
            if(player!=mp || destroyed)return true;
            if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START){recordMediaInfo(sequence,mp,what,extra);buffering=true;if(!ended)setLoading(true,"正在缓冲…");}
            else if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START){
                recordMediaInfo(sequence,mp,what,extra);
                if(!firstFrame){
                    int position=mp.getCurrentPosition();
                    android.util.Log.i("TVPlayback","Decoded first frame seq="+sequence+" position="+position+" target="+startupPosition);
                    videoReady(sequence,mp,"rendered");
                }
            }
            else if(what==MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING
                    || (android.os.Build.VERSION.SDK_INT>=26 && what==MediaPlayer.MEDIA_INFO_VIDEO_NOT_PLAYING)){
                recordMediaInfo(sequence,mp,what,extra);recoverVideoSync(sequence,mp,what);
            }
            else if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END){recordMediaInfo(sequence,mp,what,extra);buffering=false;if(firstFrame)setLoading(false,null);}
            return false;
        });
        media.setOnErrorListener((mp,what,extra)->{
            if(player!=mp || destroyed)return true;
            ink.pengfei.lvguo.media.PlaybackDiagnostics.event("decoder_error",what+"/"+extra);
            boolean codecFailure=what==MediaPlayer.MEDIA_ERROR_UNSUPPORTED
                    || what==MediaPlayer.MEDIA_ERROR_MALFORMED
                    || what==MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK
                    || what==MediaPlayer.MEDIA_ERROR_SERVER_DIED
                    || extra>=4000 && extra<5000;
            Exception failure=codecFailure
                    ?new ink.pengfei.lvguo.media.PlaybackError.DecoderFailure("播放器/解码器错误 "+what+"/"+extra)
                    :new java.io.IOException((what==MediaPlayer.MEDIA_ERROR_IO || extra==MediaPlayer.MEDIA_ERROR_IO?"播放读取失败 ":"播放器错误 ")+what+"/"+extra);
            fallback(sequence,failure);return true;
        });
        media.setOnPreparedListener(mp->{
            if(player!=mp || destroyed) return;
            prepared=true;paused=restorePaused || !foreground || commentsOpen || !surfaceReady;
            startupPosition=Math.max(0,restorePosition);mp.setVolume(0f,0f);
            if(!foreground || !surfaceReady) resumeAfterPause=!restorePaused;
            android.util.Log.i("TVPlayback","Prepared seq="+sequence+" ms="+(android.os.SystemClock.elapsedRealtime()-playbackStartedAt)+" paused="+paused+" foreground="+foreground);
            setLoading(!paused,"正在加载画面…");
            subtitle.setText("第 "+episodes.get(index).index+" 集  /  "+episodes.size()+
                    " 集    ·    "+activeGear.shortSide()+"P");
            applyPlaybackSpeed(false);if(activeGear!=null)qualityButton.setText(activeGear.shortSide()+"P");
            // 新建的播放器从第一个采样起播，零位 seek 没必要；换集/续播统一在这里对齐到 startupPosition（下一集=0）。
            if(startupPosition>0)seekNative(mp,startupPosition);
            if(!paused){mp.start();monitorStart(sequence,mp);}else mp.pause();
            restorePosition=0;restorePaused=false;
            refreshPauseState(); ui.removeCallbacks(tick); ui.post(tick); scheduleHide();
        });
    }
    private void queuePreparedEpisode(int seq,int target,Episode episode,Gear gear,StreamingSource source,File file){
        if(destroyed || seq!=sequence || target!=index+1 || degraded || player==null
                || !App.prefs().getBoolean("autoplay_next",true) || upNextCancelled
                || !(player instanceof ink.pengfei.lvguo.media.CompatMediaPlayer) || !prepared){
            if(source!=null)source.close();
            return;
        }
        ink.pengfei.lvguo.media.CompatMediaPlayer media=(ink.pengfei.lvguo.media.CompatMediaPlayer)player;
        if(queuedEpisode>=0 || media.hasQueuedEpisode()){
            if(source!=null)source.close();
            return;
        }
        boolean added;
        try {
            added=source!=null?media.appendNextDataSource(source,episode.vid)
                    :file!=null && media.appendNextFile(file,episode.vid);
        } catch(RuntimeException error) {
            android.util.Log.w("TVPlayback","Could not queue next episode",error);
            if(source!=null)source.close();
            return;
        }
        if(!added){
            if(source!=null)source.close();
            return;
        }
        queuedEpisode=target;queuedStream=source;queuedFile=file;queuedGear=gear;
        android.util.Log.i("TVPlayback","Queued episode="+episode.index+" without replacing the active player");
    }

    private void advanceQueuedEpisode(ink.pengfei.lvguo.media.CompatMediaPlayer media,String mediaId,
                                     int oldPosition,boolean completed,int seq){
        if(seq!=sequence || player!=media || episodes==null || queuedEpisode!=index+1
                || queuedEpisode<0 || queuedEpisode>=episodes.size()
                || !episodes.get(queuedEpisode).vid.equals(mediaId))return;
        int previousIndex=index;
        Episode next=episodes.get(queuedEpisode);
        StreamingSource previousStream=stream;
        Gear nextGear=queuedGear;
        StreamingSource nextStream=queuedStream;

        // Persist the outgoing episode before history moves to the incoming episode.
        Store.progress(seriesId,previousIndex,Math.max(0,oldPosition),Math.max(lastKnownDuration,oldPosition));
        ink.pengfei.lvguo.net.LibrarySync.flushProgress();
        media.removePlayedEpisodes();
        stream=nextStream;
        queuedStream=null;queuedFile=null;queuedGear=null;queuedEpisode=-1;
        if(previousStream!=null && previousStream!=stream)previousStream.close();

        index=previousIndex+1;activeGear=nextGear;prepared=true;firstFrame=false;
        networkRetries=0;sourceRetries=0;profileRefreshes=0;
        startupPosition=0;startupRewound=true;startupCorrection=false;restorePosition=0;restorePaused=false;
        playbackStartedAt=android.os.SystemClock.elapsedRealtime();lastKnownDuration=0;progressSavedAt=0;
        startupRewound=true;ended=false;allFinished=false;buffering=false;bufferedPercent=0;upNextCancelled=false;hideUpNext();
        loadingRequested=false;ui.removeCallbacks(delayedLoading);loading.setVisibility(View.GONE);
        seekPreview=-1;ui.removeCallbacks(commitSeek);timeline.setProgress(0);timeline.setSecondaryProgress(0);setTimes(0,0);
        subtitle.setText("第 "+next.index+" 集  /  "+episodes.size()+" 集    ·    "+nextGear.shortSide()+"P");
        qualityButton.setText(nextGear.shortSide()+"P");readyHint.setText("");
        ink.pengfei.lvguo.remote.PlaybackControl.starting(seriesId,seriesTitle,next.index,playbackIntent().getStringExtra(EXTRA_COVER));
        ink.pengfei.lvguo.remote.PlaybackControl.settings(playbackSpeed,nextGear.shortSide()+"P");
        Store.markHistory(seriesId,seriesTitle,next.index,index,next.vid,playbackIntent().getStringExtra(EXTRA_COVER));
        loadDanmaku(seq,next);refreshEpisodeCells();
        if(foreground && !paused)media.start();
        monitorStart(seq,media);
        android.util.Log.i("TVPlayback","Seamless episode transition "+previousIndex+" -> "+index+" auto="+completed);
    }

    private void discardQueuedEpisode(){
        if(player instanceof ink.pengfei.lvguo.media.CompatMediaPlayer)
            ((ink.pengfei.lvguo.media.CompatMediaPlayer)player).removeQueuedEpisodes();
        StreamingSource source=queuedStream;queuedStream=null;
        if(source!=null && source!=stream)source.close();
        queuedFile=null;queuedGear=null;queuedEpisode=-1;
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
        firstFrame=true;buffering=false;feedErrors=0;media.setVolume(1f,1f);setLoading(false,null);hideEpisodeCard();paintFeedActions();
        // 取消延时封面（或淡出已展示的封面）；若还在推拉滑入中则淡出黑遮罩直接露出新剧视频
        int slideDir=pendingSlideDir;pendingSlideDir=0;
        if(switchCover!=null && switchCover.getVisibility()==View.VISIBLE){
            hideIncomingMask(true);
            hideSwitchCover(false);
        }else{
            hideSwitchCover(true);
            hideIncomingMask(false);
            if(slideDir!=0 && switchAnimator==null)startSlideTransition(surface,slideDir);
        }
        refreshPauseState();   // 首帧出来后解除弹幕暂停（之前一直停在屏幕外，看起来像「没有弹幕」）
        final int workedRung=rung;
        ui.postDelayed(()->{if(seq==sequence && player==media && firstFrame && !failed){networkRetries=0;sourceRetries=0;profileRefreshes=0;
            if(rungFromTimeout && workedRung>ink.pengfei.lvguo.media.PlaybackCompatibility.startRung() && ++timeoutDowngrades<2)ink.pengfei.lvguo.media.PlaybackDiagnostics.event("decode_rung_once",ink.pengfei.lvguo.media.PlaybackCompatibility.label(workedRung));
            else ink.pengfei.lvguo.media.PlaybackCompatibility.rungWorked(workedRung);}},6000);
        if(activeGear.shortSide()>720)ui.postDelayed(()->{if(seq==sequence && player==media && firstFrame && !failed){timeoutDowngrades=0;ink.pengfei.lvguo.media.PlaybackCompatibility.confirmed();}},3000);
        ink.pengfei.lvguo.media.PlaybackDiagnostics.event("first_frame",activeGear.shortSide()+"P position="+media.getCurrentPosition());
        android.util.Log.i("TVPlayback","Visible first frame seq="+seq+" position="+media.getCurrentPosition()+" target="+startupPosition+" reason="+reason+" ms="+(android.os.SystemClock.elapsedRealtime()-playbackStartedAt));
        scheduleWarmup(seq);
    }
    private void recoverVideoSync(int seq,MediaPlayer media,int info){
        if(seq!=sequence || player!=media || !prepared || !firstFrame || paused || buffering || !surfaceReady || destroyed)return;
        final int position;
        try{position=Math.max(0,media.getCurrentPosition());}catch(IllegalStateException ignored){return;}
        String engine=media instanceof ink.pengfei.lvguo.media.CompatMediaPlayer?"Media3":"system";
        ink.pengfei.lvguo.media.PlaybackDiagnostics.event("video_sync_recovery","engine="+engine+" info="+info+" positionMs="+position+" quality="+(activeGear==null?"unknown":activeGear.shortSide()+"P"));
        android.util.Log.w("TVPlayback","Video decoder lag seq="+seq+" engine="+engine+" info="+info+" position="+position);
        long now=android.os.SystemClock.elapsedRealtime();
        // Re-anchor the current player at its own playback clock. Lag reports alone do not
        // justify switching decoder engines or silently lowering the selected quality.
        // The audio clock may have advanced while video frames were stalled. Pause both tracks
        // and seek to that clock so the decoder cannot resume from a stale video timestamp.
        // 不再「暂停 + seek」重新对齐：MP4 的 seek 会吸附到前一个关键帧，往回跳 1~2 秒重播，
        // 解码吃力时每 2.5 秒触发一次就会出现「画面反复重复、卡顿怪异」。Media3 自己会丢弃迟到帧
        // 维持音画同步，这里只记录诊断，不干预播放进度。
        if(now-lastVideoSyncRecoveryAt<2500)return;
        lastVideoSyncRecoveryAt=now;
    }
    private void recordMediaInfo(int seq,MediaPlayer media,int what,int extra){
        if(seq!=sequence || player!=media)return;
        int position=-1;try{if(prepared)position=media.getCurrentPosition();}catch(IllegalStateException ignored){}
        ink.pengfei.lvguo.media.PlaybackDiagnostics.event("media_info","engine="+(media instanceof ink.pengfei.lvguo.media.CompatMediaPlayer?"Media3":"system")+" what="+what+" extra="+extra+" positionMs="+position+" buffering="+buffering);
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
                        rungFromTimeout=true;fallback(seq,new java.io.IOException("首帧解码停滞，切换兼容内核"));return;
                    }
                    if(checks>=(activeGear!=null && activeGear.shortSide()>720?15:30)){
                        rungFromTimeout=true;fallback(seq,new java.io.IOException("首帧启动超时，自动恢复"));
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
                if(duration>0)lastKnownDuration=duration;
                if(duration>0 && seekPreview<0) timeline.setProgress((int)(position*1000L/duration));
                if(seekPreview<0)setTimes(position,duration);
                refreshFeedInfo();
                // 片尾「即将播放」倒计时卡已按需求去掉，播完直接连播下一集
                if(!paused && firstFrame)feedDanmaku(position,duration);
                ink.pengfei.lvguo.remote.PlaybackControl.update(seriesTitle,episodes.get(index).index,position,duration,paused);
                captureNativeFrameMetrics(sequence,player,position);
                if(android.os.SystemClock.elapsedRealtime()-progressSavedAt>=5000){savePlaybackPosition();}
            } catch(IllegalStateException ignored) { }
            ui.postDelayed(this,500);
        }
    };
    private void captureNativeFrameMetrics(int seq,MediaPlayer media,int position){
        if(android.os.Build.VERSION.SDK_INT<26 || media==null || media instanceof ink.pengfei.lvguo.media.CompatMediaPlayer)return;
        long now=android.os.SystemClock.elapsedRealtime(),previousDataAt=lastMetricsDataAt;
        if(now-lastMetricsSampleAt<1500)return;
        lastMetricsSampleAt=now;
        try{
            android.os.PersistableBundle metrics=media.getMetrics();
            if(metrics==null){if(lastMetricsLogAt==0){lastMetricsLogAt=now;ink.pengfei.lvguo.media.PlaybackDiagnostics.event("native_metrics_unavailable","player returned no metrics");}return;}
            String codec=metrics.getString(MediaPlayer.MetricsConstants.CODEC_VIDEO,"unknown");
            String audioCodec=metrics.getString(MediaPlayer.MetricsConstants.CODEC_AUDIO,"unknown");
            String videoMime=metrics.getString(MediaPlayer.MetricsConstants.MIME_TYPE_VIDEO,"unknown");
            String audioMime=metrics.getString(MediaPlayer.MetricsConstants.MIME_TYPE_AUDIO,"unknown");
            int frames=metrics.getInt(MediaPlayer.MetricsConstants.FRAMES,-1);
            int dropped=metrics.getInt(MediaPlayer.MetricsConstants.FRAMES_DROPPED,-1);
            int droppedDelta=lastMetricsDropped<0||dropped<0?-1:dropped-lastMetricsDropped;
            int frameDelta=lastMetricsFrames<0||frames<0?-1:frames-lastMetricsFrames;
            long sampleDeltaMs=previousDataAt==0?0:now-previousDataAt;
            long positionDelta=previousDataAt==0?0:position-lastMetricsPosition;
            if(droppedDelta>0 || now-lastMetricsLogAt>=10000){
                ink.pengfei.lvguo.media.PlaybackDiagnostics.event("native_av_metrics","videoCodec="+codec+" videoMime="+videoMime+" audioCodec="+audioCodec+" audioMime="+audioMime+" sampleMs="+sampleDeltaMs+" posMs="+position+" posDeltaMs="+positionDelta+" videoFrames="+frames+" videoFrameDelta="+frameDelta+" videoDropped="+dropped+" videoDroppedDelta="+droppedDelta);
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
            if(lastMetricsLogAt==0){lastMetricsLogAt=now;ink.pengfei.lvguo.media.PlaybackDiagnostics.event("native_metrics_error",error.getClass().getSimpleName());}
        }
    }
    private void savePlaybackPosition(){
        if(!prepared || !firstFrame || player==null || episodes==null || index<0 || index>=episodes.size())return;
        try{Store.progress(seriesId,index,player.getCurrentPosition(),player.getDuration());progressSavedAt=android.os.SystemClock.elapsedRealtime();}catch(IllegalStateException ignored){}
    }
    private void setTimes(int position,int duration){
        if(feedMode || feedLayer!=null)time.setText(formatTime(position)+" / "+(duration>0?formatTime(duration):"--:--"));
        else {
            time.setText(formatTime(position));
            remain.setText(duration>0?"-"+formatTime(Math.max(0,duration-position)):"--:--");
        }
        feedProgress(position,duration);
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
        ink.pengfei.lvguo.remote.PlaybackControl.paused(paused);
        if(danmaku!=null)danmaku.setPaused(paused||ended||!firstFrame);
        if(playButton!=null){playButton.setContentDescription(ended?"重播本集":paused?"播放":"暂停");Ui.icon(playButton,(paused||ended)?IconView.PLAY:IconView.PAUSE,playButton.getCurrentTextColor(),20);}
        centerToggle.setContentDescription(ended?"重播本集":paused?"播放":"暂停");
        centerToggleIcon.setType((paused || ended)?IconView.PLAY:IconView.PAUSE);
        centerToggleIcon.setColor(centerToggle.hasFocus()?Ui.ON_FOCUS:0xFFFFFFFF);
        paintFeedActions();
    }
    private void seekBy(int delta){
        if(!prepared || player==null)return;
        try{
            int duration=player.getDuration(),base=seekPreview>=0?seekPreview:player.getCurrentPosition();
            seekPreview=Math.max(0,Math.min(Math.max(0,duration-300),base+delta));
            timeline.setProgress((int)(seekPreview*1000L/Math.max(1,duration)));
            setTimes(seekPreview,duration);timeline.setActive(true);
            readyHint.setText(delta<0?"快退 10 秒":"快进 10 秒");
            ui.removeCallbacks(commitSeek);ui.postDelayed(commitSeek,450);showChrome();
        }catch(IllegalStateException ignored){}
    }
    private void commitPendingSeek(){
        if(seekPreview<0 || !prepared || player==null)return;
        int position=seekPreview;seekPreview=-1;timeline.setActive(false);readyHint.setText("");
        try{seekNative(player,position);ended=false;allFinished=false;ink.pengfei.lvguo.remote.PlaybackControl.ended(false);refreshPauseState();}
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
        ink.pengfei.lvguo.net.LibrarySync.flushProgress();
        if(!feedMode && !degraded && episodes!=null && index+1>=episodes.size()){
            final int request=sequence;
            ink.pengfei.lvguo.ui.widget.FavoriteActions.onCompleted(this,seriesId,episodes.size(),()->{
                if(!destroyed && request==sequence){paintFavorite();continueAfterEpisodeComplete();}
            });return;
        }
        continueAfterEpisodeComplete();
    }
    private void continueAfterEpisodeComplete(){
        if(feedMode){   // 刷剧：没换剧就接着放本剧下一集；整部放完换下一部
            if(episodes!=null && !degraded && index+1<episodes.size()){restorePosition=0;restorePaused=false;playAt(index+1);}
            else feedShow(feedIndex+1,1);
            return;
        }
        android.util.Log.i("TVPlayback","Completed episode="+episodes.get(index).index+" seq="+sequence+" auto="+App.prefs().getBoolean("autoplay_next",true));
        if(episodes!=null && index+1<episodes.size() && App.prefs().getBoolean("autoplay_next",true) && !upNextCancelled){
            restorePosition=0;restorePaused=false;playAt(index+1);return;
        }
        boolean seasonEnd=!degraded && episodes!=null && index+1>=episodes.size();
        if(seasonEnd && App.prefs().getBoolean("autoplay_next",true) && seriesDetail!=null && seriesDetail.multiSeason){
            final int request=sequence;
            final String id=seriesId;final Repo.Detail detail=seriesDetail;
            setLoading(true,"正在加载下一季…");
            App.io().execute(()->{try{
                final Repo.Season next=followingSeason(id,detail);
                // 没有可播的下一季时，看看有没有「已公布但未上线」的下一季，以及是否已经预约
                Repo.Season soon=null;boolean reservedSoon=false;
                if(next==null){
                    soon=upcomingSeason(id,detail);
                    if(soon!=null && ink.pengfei.lvguo.net.Session.loggedIn()){
                        try{for(ink.pengfei.lvguo.net.Account.Entry e:ink.pengfei.lvguo.net.Account.reservations(false).items)if(soon.id.equals(e.seriesId)){reservedSoon=true;break;}}catch(Exception ignored){}
                    }
                }
                final Repo.Season upcoming=soon;final boolean alreadyReserved=reservedSoon;
                ui.post(()->{if(destroyed || request!=sequence)return;
                    if(next==null && upcoming!=null){promptUpcomingSeason(upcoming,alreadyReserved);return;}
                    if(next==null){finishPlayback(true);return;}
                    android.util.Log.i("TVPlayback","Continue season="+next.number);
                    android.content.Intent intent=new android.content.Intent(this,PlayerActivity.class);
                    intent.putExtra(EXTRA_SERIES,next.id).putExtra(EXTRA_TITLE,next.title).putExtra(EXTRA_COVER,next.cover).putExtra(EXTRA_INDEX,0);
                    // Reuse this Activity so lifecycle transitions cannot leave the next season paused.
                    feedSwitching=true;changePlaybackIntent(intent);
                });
            }catch(Exception error){ui.post(()->{if(destroyed || request!=sequence)return;finishPlayback(false);readyHint.setText("本季已播完 · 下一季加载失败");});}});
            return;
        }
        finishPlayback(seasonEnd);
    }
    /** 本季播完、下一季已公布但还没上线：已预约就提示一句；没预约就问要不要预约（可在这里直接预约）。 */
    private void promptUpcomingSeason(final Repo.Season season,boolean reserved){
        finishPlayback(true);
        final String label="第 "+season.number+" 季";
        if(reserved){readyHint.setText("本季已播完 · "+label+"已预约");return;}
        if(!ink.pengfei.lvguo.net.Session.loggedIn()){readyHint.setText("本季已播完 · "+label+"尚未上线");return;}
        readyHint.setText("本季已播完 · "+label+"尚未上线");
        ink.pengfei.lvguo.ui.widget.TvDialog.confirm(this,label+"尚未上线","上线后可在「我的 · 预约」查看。","预约",()->
            App.io().execute(()->{
                String err=null;
                try{ink.pengfei.lvguo.net.Account.setReservation(season.id,true);}catch(Exception e){err=e.getMessage();}
                final String error=err;
                ui.post(()->{if(destroyed)return;
                    if(error!=null){notice("预约失败："+error,4000);return;}
                    readyHint.setText("本季已播完 · "+label+"已预约");notice("已预约"+label,3000);});
            }));
    }
    /** 已公布但未上线（0 集）的下一季：季号比当前大、最近的那一个。 */
    private Repo.Season upcomingSeason(String id,Repo.Detail detail)throws Exception{
        if(detail==null || !detail.multiSeason)return null;
        Repo.Season found=null;
        for(Repo.Season season:Repo.seasons(id,detail.relatedAlbumId))
            if(season.number>detail.seasonIndex && season.episodes<=0 && (found==null || season.number<found.number))found=season;
        return found;
    }
    private void finishPlayback(boolean complete){
        ended=true;allFinished=complete;paused=true;buffering=false;
        ink.pengfei.lvguo.remote.PlaybackControl.ended(true);
        setLoading(false,null);refreshPauseState();readyHint.setText(allFinished?"已全部播放完毕":"本集结束 · 可选择下一集");
        revealControls();ui.removeCallbacks(hideTask);
    }
    private Gear pickPlaybackGear(List<Gear> gears){
        List<Gear> compatible=VideoDecoderSupport.compatibleHevc(gears);
        Gear highestAvailable=Repo.pickGear(compatible,null,Integer.MAX_VALUE);
        if(highestAvailable==null){forcedAboveCeiling=false;return null;}
        Gear chosen=Repo.pickGear(compatible,prefer==null?"720p":prefer,effectiveCeiling());
        // 单集只提供高于当前上限的档位时，宁可先播它（由解码阶梯兜底），也不能报「没有可用播放源」。
        forcedAboveCeiling=chosen==null;
        return chosen!=null?chosen:highestAvailable;
    }
    private boolean overlayOpen(){return isEpisodeOpen() || commentsOpen || (settingsDialog!=null && settingsDialog.isShowing());}

    private void updateSettingsLabels(){
        String quality=prepared && activeGear!=null?activeGear.shortSide()+"P":prefer==null?"720P":prefer.toUpperCase(Locale.US);
        ink.pengfei.lvguo.remote.PlaybackControl.settings(playbackSpeed,quality);
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
        }catch(RuntimeException error){playbackSpeed=1f;if(feedback)toast("设备不支持该倍速，已恢复 1x");}
        updateSettingsLabels();
    }
    private interface ChoiceAction { void accept(int index); }
    private void options(String heading,List<String> labels,int selected,ChoiceAction action){options(heading,labels,selected,selected,action);}
    /** selected：打勾的当前值（-1 表示这是一级菜单，不打勾）；focus：打开时焦点所在项。 */
    private void options(String heading,List<String> labels,int selected,int focus,ChoiceAction action){
        if(settingsDialog!=null)settingsDialog.dismiss();
        final boolean visibleBefore=chromeVisible;final View focusedBefore=focusedView();
        ui.removeCallbacks(hideTask);
        android.app.Dialog dialog=new android.app.Dialog(this);settingsDialog=dialog;
        Ui.closeOnBack(dialog);
        LinearLayout panel=Ui.column(this);Ui.padding(panel,24,20,24,20);panel.setBackground(Ui.round(this,Ui.SURFACE,16));
        panel.addView(text(heading,22,Ui.TEXT,true));
        ScrollView scroll=new ink.pengfei.lvguo.ui.widget.FocusScrollView(this);scroll.setFillViewport(false);LinearLayout list=Ui.column(this);Ui.padding(list,0,16,0,0);scroll.addView(list);panel.addView(scroll,Ui.lp(-1,0,1));
        // 选项行：左边文字，右边只有「当前选中」才有对勾；不再给每一行放含义不明的箭头图标
        List<View> buttons=new ArrayList<>();
        for(int n=0;n<labels.size();n++){
            final int choice=n;final boolean on=n==selected;
            LinearLayout row=Ui.row(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setFocusable(true);Ui.padding(row,18,0,14,0);
            if(android.os.Build.VERSION.SDK_INT>=26)row.setDefaultFocusHighlightEnabled(false);
            // 「名称\t当前值」：名称在左，当前值在右（灰色），和设置页一致
            String[] parts=labels.get(n).split("\t",2);
            TextView label=text(parts[0],17,Ui.TEXT,on);row.addView(label,Ui.lp(0,-2,1));
            final TextView value=parts.length>1?text(parts[1],15,Ui.TEXT_3,false):null;
            if(value!=null){LinearLayout.LayoutParams vl=Ui.lp(-2,-2);vl.rightMargin=dp(8);row.addView(value,vl);}
            IconView check=new IconView(this,IconView.CHECK,18);check.setVisibility(on?View.VISIBLE:value!=null?View.GONE:View.INVISIBLE);row.addView(check,Ui.lp(dp(18),dp(18)));
            Runnable paint=()->{boolean f=row.hasFocus();row.setBackground(f?Ui.round(this,Ui.FOCUS_FILL,12):null);int fg=f?Ui.ON_FOCUS:on?Ui.ACCENT_TEXT:Ui.TEXT;label.setTextColor(fg);check.setColor(fg);if(value!=null)value.setTextColor(f?Ui.ON_FOCUS:Ui.TEXT_3);};
            paint.run();row.setOnFocusChangeListener((v,f)->paint.run());
            row.setOnClickListener(v->{dialog.dismiss();action.accept(choice);});
            LinearLayout.LayoutParams lp=Ui.lp(-1,dp(50));lp.bottomMargin=dp(4);list.addView(row,lp);buttons.add(row);
        }
        dialog.setOnDismissListener(d->{if(settingsDialog==dialog)settingsDialog=null;if(!destroyed)restoreChrome(visibleBefore,focusedBefore);});
        dialog.setContentView(panel);ink.pengfei.lvguo.ui.Ui.popIn(panel);dialog.show();Window w=dialog.getWindow();
        if(w!=null){w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);w.getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(Math.min(dp(440),root.getWidth()-dp(48)),Math.min(root.getHeight()-dp(48),dp(80+labels.size()*56)));}
        if(!buttons.isEmpty())buttons.get(Math.max(0,Math.min(focus,buttons.size()-1))).requestFocus();
    }
    private void openSpeed(){
        final float[] rates={.75f,1f,1.25f,1.5f,1.75f,2f};List<String> labels=new ArrayList<>();int selected=1;
        for(int n=0;n<rates.length;n++){labels.add(rates[n]+"×"+(rates[n]==1?"  正常速度":""));if(rates[n]==playbackSpeed)selected=n;}
        labels.add("换集保持倍速\t"+(speedLocked?"开启":"关闭"));
        options("倍速",labels,selected,n->{
            if(n==rates.length){speedLocked=!speedLocked;App.prefs().edit().putBoolean("speed_lock",speedLocked).putFloat("play_speed",playbackSpeed).apply();updateSettingsLabels();openSpeed();}
            else{playbackSpeed=rates[n];if(speedLocked)App.prefs().edit().putFloat("play_speed",playbackSpeed).apply();applyPlaybackSpeed(true);}
        });
    }
    private void openQuality(){
        if(episodes==null)return;final int request=sequence;Episode episode=episodes.get(index);String vid=episode.vid;
        ui.removeCallbacks(hideTask);toast("正在读取可用清晰度");
        App.io().execute(()->{try{
            List<Gear> all=gearsFor(episode);TreeMap<Integer,Gear> choices=new TreeMap<>(Collections.reverseOrder());
            for(Gear gear:VideoDecoderSupport.compatibleHevc(all))if(gear.shortSide()>0 && gear.shortSide()<=effectiveCeiling() && !choices.containsKey(gear.shortSide()))choices.put(gear.shortSide(),gear);
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
    private void openPlaybackSettings(){openPlaybackSettings(0);}
    /** 「更多」：一级菜单显示当前状态，进入后再选（二级），关闭后焦点回到「更多」。 */
    private void openPlaybackSettings(int focus){
        boolean auto=App.prefs().getBoolean("autoplay_next",true);
        List<String> labels=new ArrayList<>(Arrays.asList(
                "本集评论",
                "整部剧的剧评",
                "自动连播下一集\t"+(auto?"开启":"关闭"),
                "手机遥控\t扫码连接"));
        if(seriesDetail!=null && seriesDetail.multiSeason)labels.add("切换季"+(seriesDetail.seasonIndex>0?"\t当前第 "+seriesDetail.seasonIndex+" 季":""));
        options("更多",labels,-1,focus,n->{
            if(n==0)showComments(true);
            else if(n==1)showComments(false);
            else if(n==2)options("自动连播下一集",Arrays.asList("开启","关闭"),auto?0:1,k->{setAutoNext(k==0);openPlaybackSettings(2);});
            else if(n==3)ink.pengfei.lvguo.remote.Pairing.show(this);
            else openSeasons();
        });
    }
    /** 工具条上的「追剧」：一键加入 / 取消，按钮文字和图标随状态变化。 */
    private void toggleFavorite(){
        String vid=episodes==null||episodes.isEmpty()?null:episodes.get(Math.max(0,Math.min(index,episodes.size()-1))).vid;
        ink.pengfei.lvguo.ui.widget.FavoriteActions.toggle(this,seriesId,seriesTitle,playbackIntent().getStringExtra(EXTRA_COVER),vid,()->{
            boolean on=Store.isFavorite(seriesId);toast(on?"已加入追剧":"已取消追剧");paintFavorite();});
    }
    private void paintFavorite(){
        if(favoriteButton==null)return;boolean on=Store.isFavorite(seriesId);
        favoriteButton.setText(on?"已追剧":"追剧");
        Ui.icon(favoriteButton,IconView.STAR,favoriteButton.getCurrentTextColor(),20);
        if(on && favoriteButton.getCompoundDrawables()[0] instanceof ink.pengfei.lvguo.ui.IconDrawable)((ink.pengfei.lvguo.ui.IconDrawable)favoriteButton.getCompoundDrawables()[0]).setFilled(true);
        paintFeedActions();
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
        final boolean visibleBefore=chromeVisible;final View focusedBefore=focusedView();
        if(resume){
            if(prepared && player!=null)try{if(player.isPlaying())player.pause();}catch(IllegalStateException ignored){}
            paused=true;refreshPauseState();
        }
        commentsOpen=true;ui.removeCallbacks(hideTask);
        Episode current=episodes.get(index);
        commentsDialog=ink.pengfei.lvguo.ui.widget.CommentsDialog.show(this,seriesId,episode?current.vid:null,
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
        episodeSheet=Ui.column(this);episodeSheet.setClickable(true);Ui.padding(episodeSheet,28,26,28,22);
        episodeSheet.setBackground(Ui.roundStroke(this,0xEB1C1C1F,28,0x1FFFFFFF,1));
        LinearLayout header=Ui.row(this);header.addView(text("选集",24,0xFFFFFFFF,true));
        TextView count=text("共 "+episodes.size()+" 集",15,0x99FFFFFF,false);LinearLayout.LayoutParams cl=Ui.lp(0,-2,1);cl.leftMargin=dp(10);header.addView(count,cl);
        // 直接输入集数：遥控器数字键随时可按；没有数字键时用「输入集数」小键盘
        jumpHint=text("按数字键直接跳集",14,0x80FFFFFF,false);jumpHint.setSingleLine(true);
        jumpBuffer="";
        TextView locate=Ui.action(this,"输入集数",-1,false);
        playbackButtonState(locate,false);locate.setOnFocusChangeListener((v,f)->playbackButtonState(locate,f));locate.setOnClickListener(v->showKeypad());header.addView(locate,Ui.lp(-2,dp(42)));
        TextView close=Ui.action(this,"收起",IconView.CLOSE,false);close.setId(View.generateViewId());playbackButtonState(close,false);close.setOnFocusChangeListener((v,f)->playbackButtonState(close,f));close.setOnClickListener(v->closeEpisodes());header.addView(close,Ui.lp(-2,dp(42)));
        ((LinearLayout.LayoutParams)close.getLayoutParams()).leftMargin=dp(10);
        episodeSheet.addView(header,Ui.lp(-1,dp(44)));
        LinearLayout.LayoutParams jl=Ui.lp(-1,-2);jl.topMargin=dp(6);episodeSheet.addView(jumpHint,jl);
        TextView tip=text("每 100 集一组",11,Ui.TEXT_3,false);Ui.padding(tip,0,8,0,12);tip.setVisibility(View.GONE);episodeSheet.addView(tip);
        episodePage=index/100;rangeTabs.clear();
        HorizontalScrollView ranges=new HorizontalScrollView(this);ranges.setHorizontalScrollBarEnabled(false);ranges.setClipToPadding(false);
        LinearLayout tabs=Ui.row(this);ranges.addView(tabs);
        for(int page=0;episodes.size()>100 && page*100<episodes.size();page++){
            final int target=page;TextView tab=Ui.action(this,(page*100+1)+"~"+Math.min(episodes.size(),page*100+100),IconView.EPISODES,false);
            tab.setCompoundDrawables(null,null,null,null);tab.setCompoundDrawablePadding(0);
            LinearLayout.LayoutParams tabLp=Ui.lp(-2,dp(40));tabLp.rightMargin=dp(10);Ui.padding(tab,18,0,18,0);tabs.addView(tab,tabLp);rangeTabs.add(tab);
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
        ranges.setVisibility(episodes.size()>100?View.VISIBLE:View.GONE);episodeRanges=ranges;
        LinearLayout.LayoutParams rangeLayout=Ui.lp(-1,dp(44));rangeLayout.topMargin=dp(12);episodeSheet.addView(ranges,rangeLayout);
        final int columns=5;
        episodeGrid=new GridView(this);episodeGrid.setNumColumns(columns);episodeGrid.setHorizontalSpacing(dp(10));episodeGrid.setVerticalSpacing(dp(10));episodeGrid.setClipToPadding(false);episodeGrid.setPadding(0,dp(4),0,dp(8));
        episodeGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);episodeGrid.setSelector(android.R.color.transparent);episodeGrid.setVerticalScrollBarEnabled(false);
        episodeGrid.setAdapter(new BaseAdapter(){
            public int getCount(){return Math.min(100,episodes.size()-episodePage*100);}public Object getItem(int pos){return episodes.get(episodePage*100+pos);}public long getItemId(int pos){return episodePage*100+pos;}
            public View getView(int pos,View recycled,ViewGroup parent){
                TextView cell=recycled instanceof TextView?(TextView)recycled:text("",15,Ui.TEXT,true);
                cell.setGravity(Gravity.CENTER);cell.setLayoutParams(new AbsListView.LayoutParams(-1,dp(54)));cell.setTag(episodePage*100+pos);
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
        int drawerWidth=Math.min(dp(440),(int)(root.getWidth()*.46f));
        FrameLayout.LayoutParams panel=new FrameLayout.LayoutParams(drawerWidth,-1,Gravity.RIGHT);
        panel.setMargins(0,dp(28),dp(40),dp(28));
        episodeOverlay.addView(episodeSheet,panel);episodeOverlay.setVisibility(View.VISIBLE);
        episodeSheet.setTranslationX(drawerWidth+dp(40));episodeSheet.animate().translationX(0).setDuration(180).start();
        episodeGrid.post(()->{selectEpisodePage(index/100);episodeGrid.requestFocus();episodeGrid.setSelection(index%100);refreshEpisodeCells();});
    }
    // ---------------- 直接输入集数
    private String jumpBuffer="";private TextView jumpHint;private LinearLayout keypad;private View episodeRanges;private TextView keypadDisplay;
    private boolean jumpPending;   // 输入后 1.2 秒内按确定直接播放（Handler.hasCallbacks 是 API 29，Android 9 不能用）
    private final Runnable jumpCommit=()->{jumpPending=false;jumpTo(false);};
    private void typeDigit(int d){
        if(jumpBuffer.length()>=4)jumpBuffer="";
        jumpBuffer=(jumpBuffer+d).replaceFirst("^0+","");
        if(jumpBuffer.isEmpty()){clearJump();return;}
        jumpHint.setText("跳至第 "+jumpBuffer+" 集");jumpHint.setTextColor(Ui.PRIMARY);
        ui.removeCallbacks(jumpCommit);jumpPending=true;ui.postDelayed(jumpCommit,1200);
    }
    private void clearJump(){jumpBuffer="";jumpPending=false;ui.removeCallbacks(jumpCommit);if(jumpHint!=null){jumpHint.setText("按数字键直接跳集");jumpHint.setTextColor(0x80FFFFFF);}}
    /** 找到对应集数并聚焦；play=true 时直接播放。 */
    private void jumpTo(boolean play){
        if(jumpBuffer.isEmpty()||episodes==null)return;
        int n;try{n=Integer.parseInt(jumpBuffer);}catch(NumberFormatException e){clearJump();return;}
        int target=-1;for(int i=0;i<episodes.size();i++)if(episodes.get(i).index==n){target=i;break;}
        if(target<0 && n>=1 && n<=episodes.size())target=n-1;
        if(target<0){jumpHint.setText("没有第 "+n+" 集");jumpHint.setTextColor(0xFFFF8A80);jumpBuffer="";return;}
        selectEpisodePage(target/100);episodeGrid.requestFocus();episodeGrid.setSelection(target%100);refreshEpisodeCells();
        if(play){jumpBuffer="";if(target==index)closeEpisodes();else{restorePosition=0;restorePaused=false;playAt(target);}}
        else{jumpHint.setText("第 "+n+" 集 · 按确定播放");jumpBuffer="";}
    }
    private void showKeypad(){
        if(keypad!=null)return;clearJump();
        keypad=Ui.column(this);keypad.setGravity(Gravity.CENTER_HORIZONTAL);
        keypadDisplay=text("第  —  集",30,0xFFFFFFFF,true);keypadDisplay.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dl=Ui.lp(-1,dp(64));keypad.addView(keypadDisplay,dl);
        String[][] rows={{"1","2","3"},{"4","5","6"},{"7","8","9"},{"删除","0","确定"}};View firstKey=null;
        for(String[] r:rows){LinearLayout row=Ui.row(this);
            for(String k:r){TextView key=text(k,k.length()>1?16:22,0xFFFFFFFF,true);key.setGravity(Gravity.CENTER);key.setFocusable(true);
                playbackTile(key,false,k.equals("确定"),14);
                key.setOnFocusChangeListener((v,f)->playbackTile((TextView)v,f,k.equals("确定"),14));
                key.setOnClickListener(v->{
                    if(k.equals("删除")){if(!jumpBuffer.isEmpty())jumpBuffer=jumpBuffer.substring(0,jumpBuffer.length()-1);}
                    else if(k.equals("确定")){if(!jumpBuffer.isEmpty()){closeKeypad();jumpTo(true);}return;}
                    else if(jumpBuffer.length()<4)jumpBuffer=(jumpBuffer+k).replaceFirst("^0+","");
                    keypadDisplay.setText(jumpBuffer.isEmpty()?"第  —  集":"第 "+jumpBuffer+" 集");
                });
                LinearLayout.LayoutParams kl=Ui.lp(0,dp(56),1);kl.setMargins(dp(5),dp(5),dp(5),dp(5));row.addView(key,kl);if(firstKey==null)firstKey=key;}
            keypad.addView(row,Ui.lp(-1,-2));}
        TextView tip=text("返回键收起键盘",13,0x80FFFFFF,false);LinearLayout.LayoutParams tl=Ui.lp(-2,-2);tl.topMargin=dp(10);keypad.addView(tip,tl);
        episodeGrid.setVisibility(View.GONE);if(episodeRanges!=null)episodeRanges.setVisibility(View.GONE);
        LinearLayout.LayoutParams kp=Ui.lp(-1,-2);kp.topMargin=dp(14);episodeSheet.addView(keypad,kp);
        if(firstKey!=null)firstKey.requestFocus();
    }
    private void closeKeypad(){
        if(keypad==null)return;episodeSheet.removeView(keypad);keypad=null;
        episodeGrid.setVisibility(View.VISIBLE);if(episodeRanges!=null)episodeRanges.setVisibility(episodes.size()>100?View.VISIBLE:View.GONE);
        if(jumpBuffer.isEmpty()){episodeGrid.requestFocus();episodeGrid.setSelection(index%100);}
    }
    private void paintRangeTab(TextView tab,boolean focus,boolean selected){playbackTile(tab,focus,selected,100);}
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
        boolean warmed=hasWarmed(episodes.get(position).vid,null);
        String label=String.valueOf(episodes.get(position).index);
        String state=current?"播放中":"";
        android.text.SpannableString value=new android.text.SpannableString(label+(state.isEmpty()?"":"\n"+state));
        if(!state.isEmpty()) value.setSpan(new android.text.style.RelativeSizeSpan(0.65f),
                label.length()+1,value.length(),0);
        cell.setText(value);
        cell.setContentDescription("第"+episodes.get(position).index+"集"+(state.isEmpty()?"":"，"+state));
        playbackTile(cell,focus,current,14);cell.setTextSize(17);
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
        if(keypad!=null){episodeSheet.removeView(keypad);keypad=null;}clearJump();
        if(episodeSheet!=null)episodeSheet.animate().cancel();
        episodeOverlay.setVisibility(View.GONE);
        restoreChrome(episodeChromeBefore,episodeButton);
    }
    /** 当前焦点：getCurrentFocus() 在部分设备 / 窗口刚切换时会是空，兜底从视图树里找。 */
    private View focusedView(){View f=super.getCurrentFocus();if(f==null&&getWindow()!=null)f=getWindow().getDecorView().findFocus();return f;}
    private boolean controlFocused() {
        View focused=focusedView();
        return focused==centerToggle || focused!=null && controls.contains(focused);
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        if(feedMode && !feedMasked && (!isEmbeddedPlaybackHost() || embeddedActive) && event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)showFeedInfo();
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(isEmbeddedPlaybackHost())return super.dispatchKeyEvent(event);
        return playbackKey(event)||super.dispatchKeyEvent(event);
    }
    protected final boolean playbackKey(KeyEvent event){
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK){
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)handleBack();
            return true;
        }
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
        int key=event.getKeyCode();
        if(errorVisible())return false;
        if(feedMode){Boolean handled=feedKey(event,key);if(handled!=null)return handled;}
        if(upNextVisible() && !chromeVisible && (key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER)){
            if(event.getRepeatCount()==0)nextEpisode();return true;
        }
        if(chromeVisible)scheduleHide();
        if(key!=KeyEvent.KEYCODE_DPAD_UP && key!=KeyEvent.KEYCODE_DPAD_DOWN)lastEpisodeTapKey=KeyEvent.KEYCODE_UNKNOWN;
        if(isEpisodeOpen()){
            if(key==KeyEvent.KEYCODE_BACK || key==KeyEvent.KEYCODE_MENU){closeEpisodes();return true;}
            int digit=key>=KeyEvent.KEYCODE_0&&key<=KeyEvent.KEYCODE_9?key-KeyEvent.KEYCODE_0:key>=KeyEvent.KEYCODE_NUMPAD_0&&key<=KeyEvent.KEYCODE_NUMPAD_9?key-KeyEvent.KEYCODE_NUMPAD_0:-1;
            if(digit>=0 && keypad==null){typeDigit(digit);return true;}
            if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER) && keypad==null && jumpPending){ui.removeCallbacks(jumpCommit);jumpPending=false;jumpTo(true);return true;}
            return false;
        }
        if(key==KeyEvent.KEYCODE_MENU){revealControls();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_NEXT){nextEpisode();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PREVIOUS){playAt(index-1);return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){togglePause();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY){if(paused || ended)togglePause();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PAUSE){if(!paused)togglePause();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key==KeyEvent.KEYCODE_MEDIA_REWIND){seekBy(key==KeyEvent.KEYCODE_MEDIA_REWIND?-10000:10000);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT){
            if(chromeVisible && controlFocused() && focusedView()!=centerToggle && event.getRepeatCount()==0){scheduleHide();return moveControlFocus(key==KeyEvent.KEYCODE_DPAD_LEFT?View.FOCUS_LEFT:View.FOCUS_RIGHT);}
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
            View current=focusedView();
            if(current==centerToggle){if(key==KeyEvent.KEYCODE_DPAD_DOWN)playButton.requestFocus();}
            else if(current==timeline){centerToggle.requestFocus();}
            else if(controls.contains(current)){
                if(feedLayer!=null){
                    View next=FocusFinder.getInstance().findNextFocus(deck,current,key==KeyEvent.KEYCODE_DPAD_UP?View.FOCUS_UP:View.FOCUS_DOWN);
                    if(next!=null && (controls.contains(next)||next==timeline))next.requestFocus();
                    else if(key==KeyEvent.KEYCODE_DPAD_UP)centerToggle.requestFocus();
                }else if(key==KeyEvent.KEYCODE_DPAD_UP)centerToggle.requestFocus();
            }else centerToggle.requestFocus();
            scheduleHide();
            return true;
        }
        if(key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER){
            if(focusedView()==timeline){if(event.getRepeatCount()==0)togglePause();return true;}
            if(!chromeVisible || focusedView()==root){
                if(event.getRepeatCount()==0)revealControls();return true;
            }
        }
        return false;
    }
    private boolean moveControlFocus(int direction){
        View current=focusedView();
        if(current==centerToggle)return true;
        if(isEmbeddedPlaybackHost() && !feedFullscreen && direction==View.FOCUS_LEFT && (current==previousButton || current==episodeButton)){
            exitEmbeddedToNavigation();return true;
        }
        View next=FocusFinder.getInstance().findNextFocus(deck,current,direction);
        if(next!=null && controls.contains(next))next.requestFocus(direction);
        else if(direction==View.FOCUS_LEFT && isEmbeddedPlaybackHost() && !feedFullscreen)exitEmbeddedToNavigation();
        return true;
    }
    private void handleBack(){
        if((feedMode || feedReturn) && !overlayOpen()){
            if(feedFullscreen){setFeedFullscreen(false);return;}
            if(isEmbeddedPlaybackHost()){exitEmbeddedToNavigation();return;}
            if(chromeVisible){hideChrome();return;}
            focusPlaybackNavigation();return;
        }
        if(errorVisible()){exitPlayback();return;}
        if(upNextVisible() && !chromeVisible){upNextCancelled=true;hideUpNext();discardQueuedEpisode();notice("本集播完后不再自动连播");return;}
        if(commentsDialog!=null && commentsDialog.isShowing()){ink.pengfei.lvguo.ui.widget.CommentsDialog.back(commentsDialog);return;}
        if(settingsDialog!=null && settingsDialog.isShowing()){settingsDialog.dismiss();return;}
        if(isEpisodeOpen()){if(keypad!=null){closeKeypad();return;}if(!jumpBuffer.isEmpty()){clearJump();return;}closeEpisodes();return;}
        if(!chromeVisible){revealControls();return;}
        exitPlayback();
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
        ink.pengfei.lvguo.remote.PlaybackControl.loading(show);
        if(message!=null)loadingText.setText(message);
        if(show&&!loadingRequested)loadingStartedAt=android.os.SystemClock.elapsedRealtime();
        loadingRequested=show;ui.removeCallbacks(delayedLoading);ui.removeCallbacks(loadingTick);
        if(!show)loading.setVisibility(View.GONE);
        else if(failed || loading.getVisibility()==View.VISIBLE)delayedLoading.run();
        else if(foreground)ui.postDelayed(delayedLoading,Math.max(0,1000-(android.os.SystemClock.elapsedRealtime()-loadingStartedAt)));
        if(centerToggle!=null){centerToggle.animate().cancel();centerToggle.setVisibility(!show&&chromeVisible?View.VISIBLE:View.INVISIBLE);
            if(!show&&chromeVisible&&!feedMasked){centerToggle.setAlpha(1f);if(!controlFocused())centerToggle.requestFocus();}}
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
    // ------------------------------------------------------------ 叠层：出错页 / 提示条 / 集数卡 / 即将播放

    private FrameLayout errorPanel;
    private IconView errorIcon;
    private TextView errorTitle,errorMessage,errorCode,noticeView;
    private LinearLayout errorActions,episodeCard,upNext;
    private TextView episodeCardNumber,episodeCardSeries,upNextTitle;
    private View upNextFill;
    private ink.pengfei.lvguo.media.PlaybackError shownError;
    private int errorPosition;
    private boolean upNextCancelled;
    private static final int UP_NEXT_MS=8000;

    private void buildOverlays(){
        // 集数卡：切集时立即出现，首帧出来后淡出，替代黑屏等待。
        episodeCard=Ui.column(this);episodeCard.setGravity(Gravity.CENTER);episodeCard.setVisibility(View.GONE);
        episodeCardNumber=text("",44,0xFFFFFFFF,true);episodeCardNumber.setGravity(Gravity.CENTER);episodeCardNumber.setFontFeatureSettings("tnum");
        episodeCardSeries=text("",17,0x99FFFFFF,false);episodeCardSeries.setGravity(Gravity.CENTER);episodeCardSeries.setMaxLines(1);
        episodeCardSeries.setEllipsize(android.text.TextUtils.TruncateAt.END);episodeCardSeries.setMaxWidth(dp(560));
        episodeCard.addView(episodeCardNumber,Ui.lp(-2,-2));
        LinearLayout.LayoutParams seriesLp=Ui.lp(-2,-2);seriesLp.topMargin=dp(10);episodeCard.addView(episodeCardSeries,seriesLp);
        FrameLayout.LayoutParams cardLp=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER);
        root.addView(episodeCard,cardLp);

        // 即将播放：片尾最后几秒出现在右下角，确定=立即播放，返回=这集播完不自动连播。
        upNext=Ui.column(this);upNext.setVisibility(View.GONE);
        upNext.setBackground(Ui.round(this,0xE61C1C1F,20));Ui.padding(upNext,22,18,22,18);
        TextView eyebrow=text("即将播放",13,0x99FFFFFF,false);upNext.addView(eyebrow,Ui.lp(-2,-2));
        upNextTitle=text("",20,0xFFFFFFFF,true);LinearLayout.LayoutParams utl=Ui.lp(-2,-2);utl.topMargin=dp(4);upNext.addView(upNextTitle,utl);
        FrameLayout track=new FrameLayout(this);track.setBackground(Ui.round(this,0x33FFFFFF,2));
        upNextFill=new View(this);upNextFill.setBackground(Ui.round(this,0xFFFFFFFF,2));upNextFill.setPivotX(0);
        track.addView(upNextFill,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams tl=Ui.lp(dp(220),dp(4));tl.topMargin=dp(14);upNext.addView(track,tl);
        TextView hint=text("按确定立即播放 · 返回取消连播",12,0x80FFFFFF,false);LinearLayout.LayoutParams hl=Ui.lp(-2,-2);hl.topMargin=dp(10);upNext.addView(hint,hl);
        FrameLayout.LayoutParams upLp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);upLp.rightMargin=dp(56);upLp.bottomMargin=dp(48);
        root.addView(upNext,upLp);

        // 出错页：图标 + 标题 + 说明 + 可操作按钮 + 错误码。
        errorPanel=new FrameLayout(this);errorPanel.setVisibility(View.GONE);errorPanel.setBackgroundColor(0xE6000000);errorPanel.setClickable(true);
        LinearLayout box=Ui.column(this);box.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout badge=new FrameLayout(this);badge.setBackground(Ui.round(this,0x1FFFFFFF,48));
        errorIcon=new IconView(this,IconView.ALERT,44);errorIcon.setColor(0xFFFFFFFF);
        badge.addView(errorIcon,new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.CENTER));
        box.addView(badge,Ui.lp(dp(96),dp(96)));
        errorTitle=text("",30,0xFFFFFFFF,true);errorTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams etl=Ui.lp(-2,-2);etl.topMargin=dp(26);box.addView(errorTitle,etl);
        errorMessage=text("",17,0xB3FFFFFF,false);errorMessage.setGravity(Gravity.CENTER);errorMessage.setMaxWidth(dp(600));errorMessage.setLineSpacing(dp(4),1f);
        LinearLayout.LayoutParams eml=Ui.lp(-2,-2);eml.topMargin=dp(12);box.addView(errorMessage,eml);
        errorActions=Ui.row(this);errorActions.setGravity(Gravity.CENTER);errorActions.setClipChildren(false);errorActions.setClipToPadding(false);
        LinearLayout.LayoutParams eal=Ui.lp(-2,-2);eal.topMargin=dp(34);box.addView(errorActions,eal);
        errorCode=text("",13,0x66FFFFFF,false);errorCode.setGravity(Gravity.CENTER);errorCode.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams ecl=Ui.lp(-2,-2);ecl.topMargin=dp(26);box.addView(errorCode,ecl);
        errorPanel.addView(box,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        root.addView(errorPanel,new FrameLayout.LayoutParams(-1,-1));

        // 提示条：替代系统 Toast（电视上系统 Toast 位置/样式都不受控）。
        noticeView=text("",16,0xFFFFFFFF,false);noticeView.setGravity(Gravity.CENTER);noticeView.setVisibility(View.GONE);
        noticeView.setBackground(Ui.round(this,0xEB1C1C1F,24));Ui.padding(noticeView,24,13,24,13);noticeView.setMaxWidth(dp(720));
        FrameLayout.LayoutParams nl=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);nl.topMargin=dp(40);
        root.addView(noticeView,nl);
    }

    private final Runnable noticeHide=()->{if(noticeView!=null)noticeView.animate().alpha(0f).translationY(-dp(8)).setDuration(220).withEndAction(()->noticeView.setVisibility(View.GONE)).start();};
    /** 轻提示：顶部居中的圆角条，2.4 秒后淡出。 */
    private void notice(String message){notice(message,2400);}
    private void notice(String message,long showMs){
        if(noticeView==null || destroyed)return;
        ui.removeCallbacks(noticeHide);noticeView.animate().cancel();
        noticeView.setText(message);
        if(noticeView.getVisibility()!=View.VISIBLE){noticeView.setAlpha(0f);noticeView.setTranslationY(-dp(8));noticeView.setVisibility(View.VISIBLE);}
        noticeView.animate().alpha(1f).translationY(0).setDuration(200).start();
        ui.postDelayed(noticeHide,showMs);
    }

    // ------------------------------------------------------------ 定时提醒 / 强制休息
    // 优先级：强制休息 > 整点提示 / 累计提醒。每秒检查一次（不依赖播放状态，加载中、出错时也照样生效）。

    private TextView restSeconds;private int restStage;
    private TextView clockBadge;
    private boolean restEnforced;
    private long clockShownAt;
    private final Runnable clockHide=()->{if(clockBadge!=null)clockBadge.animate().alpha(0f).setDuration(300).withEndAction(()->clockBadge.setVisibility(View.GONE)).start();};

    private final Runnable careTick=new Runnable(){public void run(){
        ui.removeCallbacks(this);
        if(destroyed || isFinishing())return;
        long now=System.currentTimeMillis();
        // 1. 强制休息：预警倒计时（不可跳过、取消或延长），到点停播并进入休息页
        long left=ink.pengfei.lvguo.care.RestMode.msUntilRest();
        if(left==0){enforceRest();return;}
        if(left>0){showRestCountdown(left);ui.postDelayed(this,1000);return;}   // 倒计时期间不叠加其它提醒
        hideRestCountdown();
        if(isPlayingNow()){
            // 2. 整点提示 / 3. 累计观看提醒（只在真正播放时；两者错开，不同时出现）
            String time=ink.pengfei.lvguo.care.WatchCare.clockDue(now);
            if(time!=null)showClock(time);
            int minutes=ink.pengfei.lvguo.care.WatchCare.onWatching(now);
            if(minutes>0){
                final String text="已连续观看 "+ink.pengfei.lvguo.care.WatchCare.durationLabel(minutes)+"，请适当休息";
                long wait=clockShownAt>0?Math.max(0,clockShownAt+5000-android.os.SystemClock.uptimeMillis()):0;
                if(wait>0)ui.postDelayed(()->{if(!destroyed&&!restPanelVisible())notice(text,5000);},wait);else notice(text,5000);
            }
        }
        ui.postDelayed(this,1000);
    }};

    private boolean isPlayingNow(){return prepared && player!=null && firstFrame && !paused && !ended && !failed;}
    private boolean restPanelVisible(){return restSeconds!=null && restSeconds.getVisibility()==View.VISIBLE;}

    /** 暂停播放并保存进度，立即尝试上传云端（网络不通时本地保留，之后由后台上传器补传）。 */
    private void pauseForCare(){
        if(isPlayingNow())togglePause();
        savePlaybackPosition();
        ink.pengfei.lvguo.net.LibrarySync.flushProgress();
        showChromeForCare();
    }
    private void showChromeForCare(){try{showChrome();}catch(RuntimeException ignored){}}

    /** 强制休息执行：停止播放 → 保存本地进度 → 立即尝试上传 → 进入休息页（入口拦截保证回不来）。 */
    private void enforceRest(){
        if(restEnforced)return;restEnforced=true;
        savePlaybackPosition();   // 先在暂停前读位置并落地（暂停/销毁后 getCurrentPosition 可能已失效，之前进度没上传就是漏在这）
        ink.pengfei.lvguo.net.LibrarySync.flushProgress();
        try{if(prepared&&player!=null&&player.isPlaying())player.pause();}catch(IllegalStateException ignored){}
        paused=true;
        ink.pengfei.lvguo.care.RestMode.enforce(this);
    }

    /** 休息前倒计时：进入预警窗口时弹一次提示条（几秒后自动收起），之后左上角只显示剩余秒数。 */
    private void showRestCountdown(long leftMs){
        long s=(leftMs+999)/1000;
        if(restStage==0){
            restStage=1;
            notice(ink.pengfei.lvguo.care.RestMode.warnLabel(ink.pengfei.lvguo.care.RestMode.warnSeconds())+"后进入强制休息",4000);
        }
        if(restSeconds==null){
            ViewGroup parent=(ViewGroup)noticeView.getParent();
            restSeconds=text("",30,0xFFFFFFFF,true);restSeconds.setGravity(Gravity.CENTER);
            restSeconds.setFontFeatureSettings("tnum");
            restSeconds.setShadowLayer(dp(5),0,dp(1),0xCC000000);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);lp.topMargin=dp(36);lp.leftMargin=dp(48);
            parent.addView(restSeconds,lp);
        }
        restSeconds.setText(String.valueOf(s));
        restSeconds.setVisibility(View.VISIBLE);restSeconds.bringToFront();
    }
    private void hideRestSeconds(){if(restSeconds!=null)restSeconds.setVisibility(View.GONE);}
    private void hideRestCountdown(){hideRestSeconds();restStage=0;}

    /** 整点 / 半点提示：右上角显示当前时间 4 秒后淡出，不打断播放。 */
    private void showClock(String time){
        if(clockBadge==null){
            ViewGroup parent=(ViewGroup)noticeView.getParent();
            clockBadge=text("",22,0xFFFFFFFF,true);clockBadge.setGravity(Gravity.CENTER);
            clockBadge.setShadowLayer(dp(5),0,dp(1),0xCC000000);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);lp.topMargin=dp(36);lp.rightMargin=dp(48);
            parent.addView(clockBadge,lp);
        }
        ui.removeCallbacks(clockHide);clockBadge.animate().cancel();
        clockBadge.setText(time);clockBadge.setVisibility(View.VISIBLE);clockBadge.setAlpha(0f);
        clockBadge.animate().alpha(1f).setDuration(240).start();clockBadge.bringToFront();
        clockShownAt=android.os.SystemClock.uptimeMillis();
        ui.postDelayed(clockHide,20000);   // 整点/半点时间角标停留约 20 秒，确保看得到（用户确认 20 秒没问题）
    }

    private void showEpisodeCard(int number){
        if(episodeCard==null)return;
        episodeCardNumber.setText("第 "+number+" 集");
        episodeCardSeries.setText(seriesTitle==null?"":seriesTitle);
        episodeCard.animate().cancel();
        episodeCard.setAlpha(0f);episodeCard.setScaleX(.96f);episodeCard.setScaleY(.96f);episodeCard.setVisibility(View.VISIBLE);
        episodeCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        View meter=loading.getChildAt(0);if(meter!=null)meter.setTranslationY(dp(96));
    }
    private void hideEpisodeCard(){
        if(episodeCard==null || episodeCard.getVisibility()!=View.VISIBLE)return;
        episodeCard.animate().cancel();
        episodeCard.animate().alpha(0f).setDuration(260).withEndAction(()->{episodeCard.setVisibility(View.GONE);View meter=loading.getChildAt(0);if(meter!=null)meter.setTranslationY(0);}).start();
    }

    private boolean upNextVisible(){return upNext!=null && upNext.getVisibility()==View.VISIBLE;}
    /** 由进度循环调用：片尾最后 8 秒显示「即将播放」并走进度。 */
    private void updateUpNext(int position,int duration){
        boolean eligible=App.prefs().getBoolean("autoplay_next",true) && !upNextCancelled && !degraded && episodes!=null
                && index+1<episodes.size() && firstFrame && !failed && !ended && duration>20000 && !isEpisodeOpen();
        int remaining=duration-position;
        if(!eligible || remaining>UP_NEXT_MS || remaining<0){if(upNextVisible())hideUpNext();return;}
        if(!upNextVisible()){
            upNextTitle.setText("第 "+episodes.get(index+1).index+" 集");
            upNext.setAlpha(0f);upNext.setTranslationX(dp(24));upNext.setVisibility(View.VISIBLE);
            upNext.animate().alpha(1f).translationX(0).setDuration(240).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        upNextFill.setScaleX(Math.max(0f,Math.min(1f,1f-remaining/(float)UP_NEXT_MS)));
        float lift=chromeVisible && deck.getHeight()>0?-(deck.getHeight()-dp(30)):0;
        if(upNext.getTranslationY()!=lift)upNext.animate().translationY(lift).setDuration(180).start();
    }
    private void hideUpNext(){
        if(!upNextVisible())return;
        upNext.animate().cancel();
        upNext.animate().alpha(0f).translationX(dp(24)).setDuration(180).withEndAction(()->upNext.setVisibility(View.GONE)).start();
    }

    private boolean errorVisible(){return errorPanel!=null && errorPanel.getVisibility()==View.VISIBLE;}
    private final Runnable waitForNetwork=new Runnable(){public void run(){
        if(destroyed || !waitingForNetwork)return;
        if(ink.pengfei.lvguo.media.PlaybackError.online(PlaybackHostActivity.this)){
            waitingForNetwork=false;refreshPlaySource(recoveryVid);playAt(index);return;
        }
        ui.postDelayed(this,2000);
    }};
    /** 展示出错页。所有不可自动恢复的播放问题最终都走这里。 */
    private void showError(int seq,ink.pengfei.lvguo.media.PlaybackError error){
        if(seq!=sequence || isFinishing() || destroyed)return;
        if(feedMode && feedIndex>=0 && feedIndex+1<feedList.size() && error.kind!=ink.pengfei.lvguo.media.PlaybackError.Kind.OFFLINE && error.kind!=ink.pengfei.lvguo.media.PlaybackError.Kind.NETWORK && feedErrors++<2){
            notice("无法播放，已换下一部");feedShow(feedIndex+1,1);return;
        }
        waitingForNetwork=false;profileRefreshInFlight=false;
        errorPosition=restartPosition();
        failed=true;shownError=error;releasePlayer();
        pendingSlideDir=0;cancelSlideTransition();clearOutgoingFrame();hideIncomingMask(true);
        hideSwitchCover(true);   // 出错就别让过场封面盖住错误页
        hideEpisodeCard();hideUpNext();closeEpisodes();
        chromeVisible=false;ui.removeCallbacks(hideTask);top.setVisibility(View.INVISIBLE);deck.setVisibility(View.INVISIBLE);
        setLoading(false,null);centerToggle.setVisibility(View.INVISIBLE);
        ink.pengfei.lvguo.media.PlaybackDiagnostics.event("error_shown",error.kind+" "+error.detail);
        ink.pengfei.lvguo.remote.PlaybackControl.error(error.title(),error.message());

        boolean offline=error.kind==ink.pengfei.lvguo.media.PlaybackError.Kind.OFFLINE;
        errorIcon.setType(offline||error.kind==ink.pengfei.lvguo.media.PlaybackError.Kind.NETWORK?IconView.WIFI_OFF:IconView.ALERT);
        errorTitle.setText(error.title());
        errorMessage.setText(error.message());
        StringBuilder code=new StringBuilder(offline?"正在等待网络恢复 · ":"").append("错误码 ").append(error.code);
        if(episodes!=null && index<episodes.size())code.append(" · 第 ").append(episodes.get(index).index).append(" 集");
        if(activeGear!=null && error.kind==ink.pengfei.lvguo.media.PlaybackError.Kind.DECODE)code.append(" · ").append(activeGear.shortSide()).append("P");
        errorCode.setText(code);

        errorActions.removeAllViews();
        errorAction("重试",IconView.REFRESH,()->retryFromError(error.retryWithSameDecoder()));
        errorAction("返回",IconView.BACK,this::exitPlayback);

        errorPanel.animate().cancel();errorPanel.setAlpha(0f);errorPanel.setVisibility(View.VISIBLE);errorPanel.bringToFront();noticeView.bringToFront();
        if(feedMasked && feedMask!=null)feedMask.bringToFront();
        errorPanel.animate().alpha(1f).setDuration(220).start();
        final View first=errorActions.getChildAt(0);
        if(first!=null && !feedMasked && (!isEmbeddedPlaybackHost() || embeddedActive)){first.requestFocus();ui.post(()->{if(errorVisible() && !feedMasked && !errorPanel.hasFocus())first.requestFocus();});}
        ui.removeCallbacks(waitForNetwork);
    }
    private void errorAction(String label,int icon,Runnable action){
        TextView button=Ui.action(this,label,icon,errorActions.getChildCount()==0);
        playbackButtonState(button,false);
        Ui.padding(button,22,0,24,0);button.setTextSize(16);
        button.setOnClickListener(v->action.run());
        button.setOnFocusChangeListener((v,focus)->{playbackButtonState(button,focus);Ui.icon(button,icon,button.getCurrentTextColor(),20);});
        Ui.icon(button,icon,button.getCurrentTextColor(),20);
        LinearLayout.LayoutParams lp=Ui.lp(-2,dp(52));if(errorActions.getChildCount()>0)lp.leftMargin=dp(14);
        errorActions.addView(button,lp);
    }
    private void hideError(){
        if(errorPanel==null)return;
        ui.removeCallbacks(waitForNetwork);
        waitingForNetwork=false;
        boolean was=errorVisible();shownError=null;failed=false;
        if(was){errorPanel.animate().cancel();errorPanel.setVisibility(View.GONE);ink.pengfei.lvguo.remote.PlaybackControl.error("","");}
    }
    /** 重试：网络类问题保持当前解码方式；其它问题从这台设备的起始解码级别重新走一遍。 */
    private void retryFromError(boolean sameDecoder){
        int position=errorPosition;hideError();
        if(episodes!=null && index>=0 && index<episodes.size())refreshPlaySource(episodes.get(index).vid);
        restorePosition=position;restorePaused=false;keepRate=true;retainRung=sameDecoder;networkRetries=0;
        switchMessage="正在重新加载…";playAt(index);
    }
    private void lowerQualityFromError(){
        int current=activeGear==null?720:activeGear.shortSide();
        playbackCeiling=ink.pengfei.lvguo.media.PlaybackRecoveryPolicy.nextLowerQuality(current);
        if(playbackCeiling<=0)return;
        notice("已切换到 "+playbackCeiling+"P");
        retryFromError(true);
    }
    private void releasePlayer() {
        if(startMonitor!=null){ui.removeCallbacks(startMonitor);startMonitor=null;}
        discardQueuedEpisode();
        if(stream!=null){stream.close();stream=null;}
        prepared=false;appliedSpeed=1f; ui.removeCallbacks(tick);
        if(player!=null) {
            try { player.reset(); player.release(); } catch(RuntimeException ignored) { }
            player=null;
        }
    }
    /** 换集前的清理：复用播放器时只做轻量复位（保留画面与解码器，旧流等换源后再关）；否则彻底释放重建。 */
    private void beginEpisodeSwitch(){
        if(reusePlayer && player instanceof ink.pengfei.lvguo.media.CompatMediaPlayer
                && !((ink.pengfei.lvguo.media.CompatMediaPlayer)player).isReleased()){
            if(startMonitor!=null){ui.removeCallbacks(startMonitor);startMonitor=null;}
            discardQueuedEpisode();
            ui.removeCallbacks(tick);
            prepared=false;appliedSpeed=1f;
        }else{
            reusePlayer=false;
            releasePlayer();
        }
    }
    private void resumeAfterSurfaceReady(){
        if(!resumeAfterPause || !foreground || !surfaceReady || commentsOpen || ended || failed)return;
        paused=false;restorePaused=false;
        if(prepared && player!=null)try{player.start();monitorStart(sequence,player);}
        catch(IllegalStateException error){ink.pengfei.lvguo.media.PlaybackDiagnostics.event("surface_resume_failed",error.getClass().getSimpleName());}
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
                try{player.pause();ink.pengfei.lvguo.media.PlaybackDiagnostics.event("surface_lost_paused","positionMs="+(prepared?player.getCurrentPosition():-1));}
                catch(RuntimeException error){ink.pengfei.lvguo.media.PlaybackDiagnostics.event("surface_lost_pause_failed",error.getClass().getSimpleName());}
            }
            try { player.setDisplay(null); } catch(IllegalStateException ignored) { }
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if(root!=null && (!isEmbeddedPlaybackHost() || embeddedActive)){
            ink.pengfei.lvguo.remote.PlaybackControl.attach(this);resumePlayback();
        }
    }
    private void resumePlayback(){
        if(ink.pengfei.lvguo.care.RestMode.enforce(this))return;
        foreground=true;
        ui.removeCallbacks(careTick);ui.post(careTick);
        if(loadingRequested){ui.removeCallbacks(delayedLoading);ui.postDelayed(delayedLoading,failed?0:Math.max(0,1000-(android.os.SystemClock.elapsedRealtime()-loadingStartedAt)));}
        if(loading!=null && loading.getVisibility()==View.VISIBLE){ui.removeCallbacks(loadingTick);ui.post(loadingTick);}
        resumeAfterSurfaceReady();
    }
    @Override protected void onPause() {
        if(root!=null)pausePlayback();
        super.onPause();
    }
    private void pausePlayback(){
        if(!foreground)return;
        ui.removeCallbacks(careTick);
        savePlaybackPosition();
        ink.pengfei.lvguo.net.LibrarySync.flushProgress();
        foreground=false;ui.removeCallbacks(delayedLoading);
        ui.removeCallbacks(loadingTick);
        // Native startup can temporarily be stopped while the user's intent is still play.
        resumeAfterPause=!paused && !ended && !failed && !commentsOpen;
        if(prepared && player!=null) {
            try { if(player.isPlaying())player.pause(); paused=true; refreshPauseState(); }
            catch(IllegalStateException ignored) { }
        }
    }
    /** 播放中登录失效：关掉播放器（主界面会转到扫码页并提示「登录已过期」），不留在一个已经不能用的播放页上。 */
    private final Runnable sessionWatcher=()->runOnUiThread(()->{
        if(isFinishing()||ink.pengfei.lvguo.net.Session.loggedIn())return;
        android.widget.Toast.makeText(this,"登录已过期，请重新扫码登录",android.widget.Toast.LENGTH_LONG).show();
        exitPlayback();
    });

    @Override protected void onDestroy() {
        ink.pengfei.lvguo.net.Session.unlisten(sessionWatcher);
        savePlaybackPosition();
        ink.pengfei.lvguo.net.LibrarySync.flushProgress();   // 关闭时再上报一次（onPause 之外的退出路径）
        ink.pengfei.lvguo.remote.PlaybackControl.detach(this);
        destroyed=true; sequence++; pendingFile=null;
        if(preloadTask!=null)preloadTask.cancel(true);
        if(feedPreloadTask!=null)feedPreloadTask.cancel(true);
        closeAllWarmed();
        if(settingsDialog!=null)settingsDialog.dismiss();if(commentsDialog!=null)commentsDialog.dismiss();
        if(pendingStream!=null){pendingStream.close();pendingStream=null;}
        cancelSlideTransition();clearOutgoingFrame();hideIncomingMask(true);
        preload.shutdownNow(); ui.removeCallbacksAndMessages(null); releasePlayer();
        super.onDestroy();
    }
    private void toast(String message) { notice(message); }

    // ------------------------------------------------------------ 找剧

    /** 换剧过场：快加载（400ms 内出首帧）直接把旧画面滑出、新视频滑入，不闪静态封面；慢加载才滑入全屏封面兜底。 */
    private static final long SWITCH_COVER_DELAY_MS=400;
    private String pendingSwitchCover;
    private static final class Api24 {
        interface CopyCallback { void onBitmap(android.graphics.Bitmap bitmap); }
        static void copySurface(SurfaceView view,Handler handler,CopyCallback callback){
            int w=view.getWidth(),h=view.getHeight();
            if(w<=0 || h<=0 || !view.getHolder().getSurface().isValid()){callback.onBitmap(null);return;}
            final android.graphics.Bitmap bmp=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            try {
                android.view.PixelCopy.request(view,bmp,result->{
                    if(result==android.view.PixelCopy.SUCCESS)callback.onBitmap(bmp);
                    else{bmp.recycle();callback.onBitmap(null);}
                },handler);
            }catch(Throwable error){bmp.recycle();callback.onBitmap(null);}
        }
    }
    private void clearOutgoingFrame(){
        if(outgoingFrame!=null){outgoingFrame.setVisibility(View.GONE);outgoingFrame.setTranslationY(0f);}
        if(outgoingImage!=null)outgoingImage.setImageDrawable(null);
        if(outgoingBitmap!=null){if(!outgoingBitmap.isRecycled())outgoingBitmap.recycle();outgoingBitmap=null;}
    }
    private void hideIncomingMask(boolean immediate){
        if(incomingMask==null || incomingMask.getVisibility()!=View.VISIBLE)return;
        incomingMask.animate().cancel();
        if(immediate){incomingMask.setVisibility(View.GONE);incomingMask.setAlpha(1f);incomingMask.setTranslationY(0f);}
        else incomingMask.animate().alpha(0f).setDuration(90).withEndAction(()->{incomingMask.setVisibility(View.GONE);incomingMask.setAlpha(1f);incomingMask.setTranslationY(0f);}).start();
    }
    private void cancelSlideTransition(){
        if(switchAnimator!=null){switchAnimator.cancel();switchAnimator=null;}
        if(surface!=null)surface.setTranslationY(0f);
        if(incomingMask!=null)incomingMask.setTranslationY(0f);
        if(switchCover!=null)switchCover.setTranslationY(0f);
    }
    private void startSlideTransition(final View incoming,final int dir){
        if(dir==0 || root==null || root.getHeight()<=0 || incoming==null){clearOutgoingFrame();return;}
        cancelSlideTransition();
        final int h=root.getHeight();
        final boolean hasOutgoing=outgoingFrame!=null && outgoingFrame.getVisibility()==View.VISIBLE;
        final boolean moveMask=incoming==surface && incomingMask!=null && incomingMask.getVisibility()==View.VISIBLE;
        incoming.setTranslationY(dir>0?h:-h);
        if(moveMask)incomingMask.setTranslationY(dir>0?h:-h);
        android.animation.ValueAnimator anim=android.animation.ValueAnimator.ofFloat(0f,1f);
        anim.setDuration(260);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a->{
            float f=(Float)a.getAnimatedValue();
            float inY=(dir>0?h:-h)*(1f-f);
            incoming.setTranslationY(inY);
            if(moveMask)incomingMask.setTranslationY(inY);
            if(hasOutgoing)outgoingFrame.setTranslationY(inY-(dir>0?h:-h));
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(android.animation.Animator animation){
                if(switchAnimator==animation)switchAnimator=null;
                incoming.setTranslationY(0f);
                if(moveMask)incomingMask.setTranslationY(0f);
                if(hasOutgoing)clearOutgoingFrame();
            }
            @Override public void onAnimationCancel(android.animation.Animator animation){
                if(switchAnimator==animation)switchAnimator=null;
            }
        });
        switchAnimator=anim;
        anim.start();
    }
    private final Runnable delayedSwitchCover=()->{
        if(destroyed || !foreground || firstFrame || failed || switchCover==null)return;
        if(pendingSwitchCover!=null && !pendingSwitchCover.isEmpty())
            ink.pengfei.lvguo.img.ImageLoader.load(pendingSwitchCover,switchCover,1080,1080);
        else switchCover.setImageDrawable(null);
        // 保持它在 root 里的原始层级（index 3：盖住 surface 与快照，但在控制条/加载圈之下），不要 bringToFront
        switchCover.animate().cancel();
        pendingSlideDir=0;
        switchCover.setTranslationY(0f);
        if(switchCover.getVisibility()!=View.VISIBLE){
            switchCover.setAlpha(0f);
            switchCover.setVisibility(View.VISIBLE);
            switchCover.animate().alpha(1f).setDuration(180).start();
        }else switchCover.setAlpha(1f);
    };
    private void showSwitchCover(String cover){
        if(switchCover==null)return;
        ui.removeCallbacks(delayedSwitchCover);
        pendingSwitchCover=cover;
        if(cover!=null && !cover.isEmpty())ink.pengfei.lvguo.img.ImageLoader.preload(cover,1080);
        if(switchCover.getVisibility()==View.VISIBLE){
            switchCover.animate().cancel();
            switchCover.setTranslationY(0f);
            switchCover.setAlpha(1f);
            if(cover!=null && !cover.isEmpty())ink.pengfei.lvguo.img.ImageLoader.load(cover,switchCover,1080,1080);
            else switchCover.setImageDrawable(null);
            return;
        }
        ui.postDelayed(delayedSwitchCover,SWITCH_COVER_DELAY_MS);
    }
    private void hideSwitchCover(boolean immediate){
        ui.removeCallbacks(delayedSwitchCover);
        pendingSwitchCover=null;
        if(switchCover==null || switchCover.getVisibility()!=View.VISIBLE)return;
        switchCover.animate().cancel();
        if(immediate){switchCover.setVisibility(View.GONE);switchCover.setAlpha(1f);}
        else switchCover.animate().alpha(0f).setDuration(220).withEndAction(()->switchCover.setVisibility(View.GONE)).start();
    }


    // ------------------------------------------------------------ 刷剧
    // 推荐流里一部接一部地看：上 / 下键换一部，不换就自动连播本剧下一集；控制区复用普通播放器。
    // 界面与普通播放器分开：刷剧信息位于屏幕底部靠左，画面两侧保持沉浸。

    public static final String EXTRA_FEED="feed";
    public static void startFeed(android.content.Context context){
        if(context instanceof ink.pengfei.lvguo.ui.RootActivity){((ink.pengfei.lvguo.ui.RootActivity)context).switchTab(ink.pengfei.lvguo.ui.RootActivity.TAB_FEED,false);return;}
        context.startActivity(new android.content.Intent(context,ink.pengfei.lvguo.ui.RootActivity.class).putExtra("open_tab",ink.pengfei.lvguo.ui.RootActivity.TAB_FEED).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    private boolean feedMode,feedReturn,feedSwitching,feedLoading,feedMore=true;
    private final List<ink.pengfei.lvguo.model.Series> feedList=new ArrayList<>();
    private final java.util.Set<String> feedSeen=new java.util.HashSet<>();
    private int feedIndex=-1,feedCursor,feedWant=-1,feedWantDir=1,feedErrors;
    private long feedSwitchedAt;
    private FrameLayout feedLayer,feedMask;
    private boolean feedFullscreen,feedMasked;
    private View feedInfo,feedHints;
    private TextView feedTitle,feedMeta,feedTags,feedIntro;
    private TextView feedFullscreenButton;
    private String feedShownKey="";
    private final Runnable hideFeedInfo=()->{if(feedMode && !overlayOpen())hideChrome();};
    private final Runnable feedBlur=()->{if(feedMode && !overlayOpen())hideChrome();};
    private void showFeedInfo(){showChrome();}
    protected void onPlaybackFullscreenChanged(boolean fullscreen){}
    protected void focusPlaybackNavigation(){}
    protected final boolean feedIsFullscreen(){return feedFullscreen;}
    protected final boolean isFeedMasked(){return feedMasked;}
    private void setFeedMask(boolean masked){
        if(!isEmbeddedPlaybackHost() || feedMask==null)return;
        feedMasked=masked;
        if(masked){
            hideChrome();
            root.setFocusable(false);root.setFocusableInTouchMode(false);
            root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            feedMask.animate().cancel();feedMask.setAlpha(1f);
            feedMask.setVisibility(View.VISIBLE);feedMask.bringToFront();
        }else{
            root.setFocusable(true);root.setFocusableInTouchMode(true);
            root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            feedMask.animate().cancel();
            feedMask.setVisibility(View.GONE);
        }
    }
    private void exitEmbeddedToNavigation(){
        setFeedMask(true);
        focusPlaybackNavigation();
    }
    private void setFeedFullscreen(boolean fullscreen){
        if(fullscreen)setFeedMask(false);
        feedFullscreen=fullscreen;
        onPlaybackFullscreenChanged(fullscreen);
        if(feedFullscreenButton!=null)feedFullscreenButton.setText(fullscreen?"退出全屏":"全屏");
        if(!feedMasked){
            root.requestFocus();root.post(()->{if(activeGear!=null)fitVideoSize(activeGear.width,activeGear.height);});
            showFeedInfo();
        }else root.post(()->{if(activeGear!=null)fitVideoSize(activeGear.width,activeGear.height);});
    }
    protected final View embeddedFeedView(){
        if(root==null)initializePlayback(new android.content.Intent().putExtra(EXTRA_FEED,true),true);
        return root;
    }
    protected final void showEmbeddedPlayback(){
        embeddedActive=true;
        ink.pengfei.lvguo.remote.PlaybackControl.attach(this);
        setFeedMask(true);
        resumePlayback();
    }
    protected final void hideEmbeddedPlayback(){
        if(feedFullscreen)setFeedFullscreen(false);
        embeddedActive=false;pausePlayback();
        setFeedMask(true);
        pendingSlideDir=0;cancelSlideTransition();clearOutgoingFrame();hideIncomingMask(true);
        hideSwitchCover(true);
        ink.pengfei.lvguo.remote.PlaybackControl.detach(this);
        ui.removeCallbacks(hideFeedInfo);ui.removeCallbacks(hideTask);
        if(settingsDialog!=null)settingsDialog.dismiss();if(commentsDialog!=null)commentsDialog.dismiss();
        closeEpisodes();
    }
    protected final void focusEmbeddedPlayback(){
        if(root==null)return;
        setFeedMask(false);
        if(errorVisible()){
            View first=errorActions==null?null:errorActions.getChildAt(0);
            if(first!=null){first.requestFocus();return;}
        }
        root.requestFocus();showFeedInfo();
    }
    protected final boolean embeddedPlaybackBack(){
        if(root==null || !embeddedActive)return false;
        handleBack();return true;
    }
    private void exitPlayback(){
        if(isEmbeddedPlaybackHost()){setFeedFullscreen(false);exitEmbeddedToNavigation();}
        else finish();
    }
    private void buildFeedUi(){
        feedLayer=new FrameLayout(this);
        time.setText("00:00 / --:--");
        FrameLayout.LayoutParams timeLp=new FrameLayout.LayoutParams(-2,-2,Gravity.START|Gravity.TOP);
        timeLp.leftMargin=dp(32);timeLp.topMargin=dp(24);feedLayer.addView(time,timeLp);
        TextView keys=text("上下换剧 · 左右快进退 · 确认操作 · 返回菜单",12,0xCCFFFFFF,false);
        keys.setSingleLine(true);feedHints=keys;
        FrameLayout.LayoutParams kl=new FrameLayout.LayoutParams(-2,-2,Gravity.END|Gravity.TOP);
        kl.rightMargin=dp(32);kl.topMargin=dp(24);feedLayer.addView(keys,kl);
        LinearLayout info=Ui.column(this);feedInfo=info;
        feedTitle=text("",22,0xFFFFFFFF,true);feedTitle.setMaxLines(1);feedTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        feedTitle.setIncludeFontPadding(false);
        info.addView(feedTitle,Ui.lp(-1,-2));
        LinearLayout metadata=Ui.row(this);metadata.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metadataLp=Ui.lp(-1,-2);metadataLp.topMargin=dp(6);info.addView(metadata,metadataLp);
        feedMeta=text("",13,0xE6FFFFFF,true);feedMeta.setSingleLine(true);feedMeta.setIncludeFontPadding(false);
        metadata.addView(feedMeta,Ui.lp(-2,-2));
        feedTags=text("",13,0xB3FFFFFF,false);feedTags.setIncludeFontPadding(false);feedTags.setSingleLine(true);feedTags.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tagsLp=Ui.lp(0,-2,1f);tagsLp.leftMargin=dp(10);metadata.addView(feedTags,tagsLp);
        feedIntro=text("",13,0xCCFFFFFF,false);feedIntro.setIncludeFontPadding(false);feedIntro.setMaxLines(2);feedIntro.setEllipsize(android.text.TextUtils.TruncateAt.END);feedIntro.setLineSpacing(dp(3),1f);
        LinearLayout.LayoutParams introLp=Ui.lp(-1,-2);introLp.topMargin=dp(6);info.addView(feedIntro,introLp);
        LinearLayout.LayoutParams detailsLp=Ui.lp(-1,-2);detailsLp.bottomMargin=dp(12);
        deck.addView(info,0,detailsLp);
        root.addView(feedLayer,root.indexOfChild(danmaku)+1,new FrameLayout.LayoutParams(-1,-1));
        timeline.setEdgeToEdge(true);
        root.addView(timeline,root.indexOfChild(deck)+1,new FrameLayout.LayoutParams(-1,dp(3),Gravity.BOTTOM));
        feedCoverShape(true);
        if(isEmbeddedPlaybackHost()){
            feedMask=new FrameLayout(this);
            feedMask.setBackgroundColor(0x99000000);
            feedMask.setClickable(true);
            feedMask.setFocusable(false);
            feedMask.setFocusableInTouchMode(false);
            feedMask.setOnClickListener(v->focusEmbeddedPlayback());
            LinearLayout prompt=Ui.column(this);prompt.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout badge=new FrameLayout(this);
            badge.setBackground(Ui.roundStroke(this,0x2EFFFFFF,18,0x55FFFFFF,1));
            IconView discover=new IconView(this,IconView.DISCOVER,28);discover.setColor(0xFFFFFFFF);
            badge.addView(discover,new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER));
            prompt.addView(badge,Ui.lp(dp(60),dp(60)));
            TextView enter=text("正在播放 · 按确认键进入刷剧",18,0xFFFFFFFF,true);
            enter.setShadowLayer(dp(6),0,dp(1),0x80000000);
            LinearLayout.LayoutParams enterLp=Ui.lp(-2,-2);enterLp.topMargin=dp(16);
            prompt.addView(enter,enterLp);
            TextView sub=text("上下换剧 · 左右快进退 · 返回回到菜单",13,0xCCFFFFFF,false);
            LinearLayout.LayoutParams subLp=Ui.lp(-2,-2);subLp.topMargin=dp(6);
            prompt.addView(sub,subLp);
            feedMask.addView(prompt,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
            feedMask.setVisibility(View.GONE);
            root.addView(feedMask,new FrameLayout.LayoutParams(-1,-1));
            setFeedMask(true);
        }else{root.requestFocus();showFeedInfo();}
    }
    /** 刷剧时过场封面铺满全屏（即使是竖屏封面也放大覆盖全屏，避免旧剧画面露在下方）；普通播放器恢复居中适配。 */
    private void feedCoverShape(boolean feed){
        if(switchCover==null)return;
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)switchCover.getLayoutParams();
        lp.width=-1;lp.height=-1;lp.gravity=Gravity.CENTER;
        switchCover.setLayoutParams(lp);
        switchCover.setScaleType(feed?android.widget.ImageView.ScaleType.CENTER_CROP:android.widget.ImageView.ScaleType.FIT_CENTER);
    }
    private void paintFeedActions(){
        if(feedLayer==null)return;
        if(feedFullscreenButton!=null)feedFullscreenButton.setText(feedFullscreen?"退出全屏":"全屏");
    }

    /** 拉一页推荐；首次拉到后直接开播第一部。 */
    private void loadFeed(){
        if(feedLoading || !feedMore)return;
        feedLoading=true;final int offset=feedCursor;final boolean first=feedList.isEmpty();
        if(first)setLoading(true,"正在准备刷剧…");
        App.io().execute(()->{
            ink.pengfei.lvguo.net.Browse.Result page=null;Exception failure=null;
            try{page=ink.pengfei.lvguo.net.Browse.forYou(ink.pengfei.lvguo.net.Browse.CH_RECOMMEND,offset,first);}catch(Exception error){failure=error;}
            final ink.pengfei.lvguo.net.Browse.Result result=page;final Exception error=failure;
            ui.post(()->{
                if(destroyed)return;
                feedLoading=false;
                if(result==null){
                    if(feedList.isEmpty())showError(sequence,ink.pengfei.lvguo.media.PlaybackError.of(ink.pengfei.lvguo.media.PlaybackError.online(this)
                            ?ink.pengfei.lvguo.media.PlaybackError.Kind.SERIES:ink.pengfei.lvguo.media.PlaybackError.Kind.OFFLINE,error));
                    else if(feedWant>=0){feedWant=-1;notice("推荐加载失败");}
                    return;
                }
                int before=feedList.size();
                List<String> addedIds=new ArrayList<>();
                for(ink.pengfei.lvguo.model.Series item:result.items)if(item.seriesId!=null && feedSeen.add(item.seriesId)){
                    feedList.add(item);
                    if(!first || feedList.size()>1)addedIds.add(item.seriesId);
                }
                feedCursor=result.next;feedMore=result.more && feedList.size()>before;
                if(!addedIds.isEmpty())App.io().execute(()->Repo.prefetch(addedIds));
                if(feedIndex<0 && !feedList.isEmpty())feedShow(0,0);
                else if(feedWant>=0){int want=feedWant,dir=feedWantDir;feedWant=-1;if(want<feedList.size())feedShow(want,dir);else notice("没有更多了");}
                else refreshFeedInfo();
                if(feedIndex>=0){
                    if(feedIndex+1<feedList.size())ink.pengfei.lvguo.img.ImageLoader.preload(feedList.get(feedIndex+1).cover,1080);
                    if(feedIndex+2<feedList.size())ink.pengfei.lvguo.img.ImageLoader.preload(feedList.get(feedIndex+2).cover,1080);
                    if(prepared && !ended && foreground && !paused && !buffering)warmFeedNext(sequence,feedIndex);
                }
            });
        });
    }
    /** 换到推荐流里的第 target 部；dir>0 新封面自下而上滑入，dir<0 自上而下。 */
    private void feedShow(int target,int dir){
        if(!feedMode)return;
        if(dir!=0)hideChrome();
        if(target<0){notice("已经是第一部");return;}
        if(target>=feedList.size()){
            if(!feedMore && !feedLoading){notice("没有更多了");return;}
            feedWant=target;feedWantDir=dir;loadFeed();return;
        }
        feedIndex=target;feedSwitchedAt=android.os.SystemClock.elapsedRealtime();
        if(feedList.size()-target<5)loadFeed();
        final ink.pengfei.lvguo.model.Series next=feedList.get(target);
        if(target+1<feedList.size())ink.pengfei.lvguo.img.ImageLoader.preload(feedList.get(target+1).cover,1080);
        if(target+2<feedList.size())ink.pengfei.lvguo.img.ImageLoader.preload(feedList.get(target+2).cover,1080);
        if(target-1>=0)ink.pengfei.lvguo.img.ImageLoader.preload(feedList.get(target-1).cover,1080);
        final android.content.Intent intent=new android.content.Intent(this,PlayerActivity.class);
        intent.putExtra(EXTRA_SERIES,next.seriesId).putExtra(EXTRA_TITLE,next.title).putExtra(EXTRA_COVER,next.cover).putExtra(EXTRA_INDEX,0);
        // 看过的剧接着上次的那一集
        Store.Item seen=Store.watched(next.seriesId);
        if(seen!=null){
            intent.putExtra(EXTRA_INDEX,seen.episodeIndex).putExtra("position_ms",(int)seen.positionMs);
            if(seen.vid!=null && !seen.vid.isEmpty())intent.putExtra(EXTRA_VID,seen.vid).putExtra(EXTRA_EPISODE_NO,seen.lastEpisode);
        }
        final int token=++feedSwitchToken;
        cancelSlideTransition();
        boolean canCopy=dir!=0 && android.os.Build.VERSION.SDK_INT>=24 && firstFrame && surfaceReady
                && surface!=null && surface.getWidth()>0 && surface.getHeight()>0
                && (switchCover==null || switchCover.getVisibility()!=View.VISIBLE);
        if(canCopy){
            final int sw=surface.getWidth(),sh=surface.getHeight();
            Api24.copySurface(surface,ui,bmp->{
                if(destroyed || token!=feedSwitchToken){if(bmp!=null)bmp.recycle();return;}
                if(bmp!=null && outgoingFrame!=null && outgoingImage!=null){
                    if(outgoingBitmap!=null && !outgoingBitmap.isRecycled())outgoingBitmap.recycle();
                    outgoingBitmap=bmp;
                    outgoingImage.setLayoutParams(new FrameLayout.LayoutParams(sw,sh,Gravity.CENTER));
                    outgoingImage.setImageBitmap(bmp);
                    outgoingFrame.setTranslationY(0f);
                    outgoingFrame.setVisibility(View.VISIBLE);
                }
                beginFeedPlayback(intent,next.cover,dir);
            });
        }else{
            if(dir==0 || switchCover==null || switchCover.getVisibility()!=View.VISIBLE)clearOutgoingFrame();
            beginFeedPlayback(intent,next.cover,dir);
        }
    }
    private void beginFeedPlayback(android.content.Intent intent,String cover,int dir){
        pendingSlideDir=dir;
        showSwitchCover(cover);
        if(dir!=0 && incomingMask!=null && (switchCover==null || switchCover.getVisibility()!=View.VISIBLE)){
            incomingMask.animate().cancel();
            incomingMask.setAlpha(1f);
            incomingMask.setVisibility(View.VISIBLE);
            startSlideTransition(surface,dir);
        }
        feedSwitching=true;changePlaybackIntent(intent);
        feedShownKey="";refreshFeedInfo();
    }
    private void refreshFeedInfo(){
        if(!feedMode || feedLayer==null)return;
        ink.pengfei.lvguo.model.Series item=feedIndex>=0 && feedIndex<feedList.size()?feedList.get(feedIndex):null;
        int count=episodes!=null && !degraded?episodes.size():item!=null?item.episodeCount:0;
        int number=episodes!=null && index>=0 && index<episodes.size()?episodes.get(index).index:0;
        String key=seriesId+"#"+number+"#"+count+"#"+feedIndex+"#"+feedList.size()+"#"+(seriesDetail!=null);
        if(key.equals(feedShownKey))return;
        feedShownKey=key;
        feedTitle.setText(seriesTitle!=null && !seriesTitle.isEmpty()?seriesTitle:item!=null?item.title:"");
        feedMeta.setText(number>0 && count>0?"第 "+number+" 集  /  全 "+count+" 集":count>0?"全 "+count+" 集":"");
        feedMeta.setVisibility(feedMeta.length()>0?View.VISIBLE:View.GONE);
        List<String> parts=new ArrayList<>();
        String heat=item==null?"":item.playText();if(!heat.isEmpty())parts.add(heat);
        String category=item==null||item.category==null?"":ink.pengfei.lvguo.net.Browse.categoryNames(item.category);
        int tags=0;for(String tag:category.split("[,，|/]")){tag=tag.trim();if(!tag.isEmpty() && tags++<3)parts.add(tag);}
        feedTags.setText((feedMeta.length()>0 && !parts.isEmpty()?"·  ":"")+android.text.TextUtils.join("  ·  ",parts));
        feedTags.setVisibility(parts.isEmpty()?View.GONE:View.VISIBLE);
        LinearLayout.LayoutParams tagsLp=(LinearLayout.LayoutParams)feedTags.getLayoutParams();tagsLp.leftMargin=feedMeta.length()>0?dp(10):0;feedTags.setLayoutParams(tagsLp);
        String intro=seriesDetail!=null && seriesDetail.intro!=null && !seriesDetail.intro.trim().isEmpty()?seriesDetail.intro.trim():item!=null && item.intro!=null?item.intro.trim():"";
        feedIntro.setText(intro);feedIntro.setVisibility(intro.isEmpty()?View.GONE:View.VISIBLE);
        paintFeedActions();
    }
    private void feedProgress(int position,int duration){} // The normal player owns the progress track.
    /** 刷剧模式下的按键；返回 null 表示交给系统默认处理。 */
    private Boolean feedKey(KeyEvent event,int key){
        if(overlayOpen())return null;
        if(!chromeVisible || focusedView()==root){
            if(key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN){
                if(event.getRepeatCount()>0 || android.os.SystemClock.elapsedRealtime()-feedSwitchedAt<350)return true;
                feedErrors=0;feedShow(feedIndex+(key==KeyEvent.KEYCODE_DPAD_DOWN?1:-1),key==KeyEvent.KEYCODE_DPAD_DOWN?1:-1);return true;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 弹幕

    private void loadDanmaku(final int seq,final Episode episode){
        if(episode==null || episode.vid==null || episode.vid.equals(bulletVid))return;
        bullets.clear();bulletNext=0;bulletLastPosition=0;bulletVid=episode.vid;
        if(danmaku!=null)danmaku.clear();
        if(!ink.pengfei.lvguo.ui.widget.DanmakuView.enabled())return;
        final String sid=seriesId,vid=episode.vid;
        App.io().execute(()->{
            List<String> found=new ArrayList<>();int offset=0;
            try{for(int page=0;page<3;page++){
                org.json.JSONObject data=ink.pengfei.lvguo.net.Browse.comments(sid,vid,offset);
                org.json.JSONArray rows=data.optJSONArray("comment");
                if(rows!=null)for(int n=0;n<rows.length();n++){org.json.JSONObject row=rows.optJSONObject(n);if(row==null)continue;
                    String text=ink.pengfei.lvguo.net.Browse.commentText(row);if(text!=null && !text.trim().isEmpty())found.add(text.trim());}
                if(!data.optBoolean("has_more"))break;offset=data.optInt("next_offset",offset+20);
            }}catch(Exception error){android.util.Log.w("Danmaku","load failed",error);}
            ui.post(()->{if(destroyed || !vid.equals(bulletVid))return;bullets.clear();bullets.addAll(found);bulletNext=0;});
        });
    }
    /** 按播放位置发弹幕：第 i 条在 2 秒 + i × 间隔 时出现；进度回退时重新铺。 */
    private void feedDanmaku(int position,int duration){
        if(danmaku==null || bullets.isEmpty() || duration<=0 || !ink.pengfei.lvguo.ui.widget.DanmakuView.enabled())return;
        int spacing=Math.max(900,(duration-6000)/Math.max(1,bullets.size()));
        if(position+3000<bulletLastPosition){bulletNext=Math.max(0,Math.min(bullets.size(),(position-2000)/spacing));danmaku.clear();}
        bulletLastPosition=position;
        while(bulletNext<bullets.size() && 2000+bulletNext*spacing<=position){
            boolean recent=position-(2000+bulletNext*spacing)<2500;
            if(recent)danmaku.add(bullets.get(bulletNext),false);
            bulletNext++;
        }
    }
    private void openDanmaku(){openDanmaku(0);}
    /** 弹幕设置：一级列表显示当前值，进入某项后在二级列表里选，选完回到一级并保持焦点。 */
    private void openDanmaku(int focus){
        final android.content.SharedPreferences prefs=App.prefs();
        boolean on=ink.pengfei.lvguo.ui.widget.DanmakuView.enabled();
        List<String> labels=new ArrayList<>(Arrays.asList(
                "弹幕\t"+(on?"开启":"关闭"),
                "滚动速度\t"+ink.pengfei.lvguo.ui.widget.DanmakuView.SPEED_LABELS[ink.pengfei.lvguo.ui.widget.DanmakuView.speedIndex()],
                "文字大小\t"+ink.pengfei.lvguo.ui.widget.DanmakuView.SIZE_LABELS[ink.pengfei.lvguo.ui.widget.DanmakuView.sizeIndex()],
                "不透明度\t"+ink.pengfei.lvguo.ui.widget.DanmakuView.ALPHAS[ink.pengfei.lvguo.ui.widget.DanmakuView.alphaIndex()]+"%"));
        options("弹幕设置",labels,-1,focus,n->{
            if(n==0)options("弹幕开关",Arrays.asList("开启","关闭"),on?0:1,k->{
                prefs.edit().putBoolean("dm_on",k==0).apply();
                if(k==0){bulletVid=null;if(episodes!=null)loadDanmaku(sequence,episodes.get(index));}
                afterDanmakuChange(0);});
            else if(n==1)options("滚动速度",Arrays.asList(ink.pengfei.lvguo.ui.widget.DanmakuView.SPEED_LABELS),ink.pengfei.lvguo.ui.widget.DanmakuView.speedIndex(),k->{prefs.edit().putInt("dm_speed",k).apply();afterDanmakuChange(1);});
            else if(n==2)options("文字大小",Arrays.asList(ink.pengfei.lvguo.ui.widget.DanmakuView.SIZE_LABELS),ink.pengfei.lvguo.ui.widget.DanmakuView.sizeIndex(),k->{prefs.edit().putInt("dm_size",k).apply();afterDanmakuChange(2);});
            else{List<String> a=new ArrayList<>();for(int x:ink.pengfei.lvguo.ui.widget.DanmakuView.ALPHAS)a.add(x+"%");
                options("不透明度",a,ink.pengfei.lvguo.ui.widget.DanmakuView.alphaIndex(),k->{prefs.edit().putInt("dm_alpha",k).apply();afterDanmakuChange(3);});}
        });
    }
    /** 手机上改了弹幕设置：立即作用到正在播放的画面（不重开菜单）。 */
    public void danmakuSettingsChanged(){
        ui.post(()->{if(destroyed||danmaku==null)return;danmaku.applySettings();
            if(ink.pengfei.lvguo.ui.widget.DanmakuView.enabled()){bulletVid=null;if(episodes!=null&&index>=0&&index<episodes.size())loadDanmaku(sequence,episodes.get(index));}
            danmakuButton.setText(ink.pengfei.lvguo.ui.widget.DanmakuView.enabled()?"弹幕":"弹幕关");});
    }
    private void afterDanmakuChange(int item){
        danmaku.applySettings();
        danmakuButton.setText(ink.pengfei.lvguo.ui.widget.DanmakuView.enabled()?"弹幕":"弹幕关");
        openDanmaku(item);
    }
    private int dp(float value) { return Ui.dp(this,value); }

    public boolean remoteSpeedLocked(){return speedLocked;}
    public String remoteQualityKey(){Gear gear=activeGear;return prepared&&gear!=null?gear.definition:prefer;}
    public org.json.JSONArray remoteQualities(){
        org.json.JSONArray result=new org.json.JSONArray();
        List<Episode> current=episodes;int at=index;if(current==null||at<0||at>=current.size())return result;
        List<Gear> choices;synchronized(qualityChoices){choices=qualityChoices.get(current.get(at).vid);}if(choices==null)return result;
        try{TreeMap<Integer,String> sizes=new TreeMap<>();for(Gear gear:VideoDecoderSupport.compatibleHevc(choices))if(gear.definition!=null&&gear.shortSide()>0&&gear.shortSide()<=effectiveCeiling())sizes.put(gear.shortSide(),gear.definition);for(Map.Entry<Integer,String> entry:sizes.entrySet())result.put(new org.json.JSONObject().put("id",entry.getValue()).put("label",entry.getKey()+"P"));}catch(Exception ignored){}
        return result;
    }
    public void remoteSetting(String action,org.json.JSONObject body){
        if(action.equals("autoplay")){setAutoNext(body.optBoolean("enabled"));return;}
        if(action.equals("speedLock")){speedLocked=body.optBoolean("enabled");App.prefs().edit().putBoolean("speed_lock",speedLocked).putFloat("play_speed",playbackSpeed).apply();updateSettingsLabels();return;}
        if(action.equals("speed")){
            float speed=(float)body.optDouble("speed",1);boolean valid=false;for(float rate:new float[]{.75f,1f,1.25f,1.5f,1.75f,2f})if(speed==rate)valid=true;
            if(!valid)throw new IllegalArgumentException("不支持的倍速");playbackSpeed=speed;if(speedLocked)App.prefs().edit().putFloat("play_speed",speed).apply();applyPlaybackSpeed(false);return;
        }
        String quality=body.optString("quality");org.json.JSONArray choices=remoteQualities();boolean valid=false;for(int n=0;n<choices.length();n++)if(quality.equals(choices.optJSONObject(n).optString("id")))valid=true;
        if(!valid)throw new IllegalArgumentException("此清晰度暂不可用");if(Objects.equals(quality,prefer))return;
        restorePosition=0;restorePaused=paused;if(prepared&&player!=null)restorePosition=player.getCurrentPosition();prefer=quality;App.prefs().edit().putString("quality",prefer).apply();keepRate=true;playAt(index);
    }
    private void setAutoNext(boolean enabled){
        App.prefs().edit().putBoolean("autoplay_next",enabled).apply();
        upNextCancelled=!enabled;
        if(!enabled){hideUpNext();discardQueuedEpisode();}
    }
    public void remoteCommand(String action,int position) {
        if(errorVisible() && shownError!=null && (action.equals("retry")||action.equals("play")||action.equals("toggle"))){retryFromError(shownError.retryWithSameDecoder());return;}
        switch(action){
            case "retry":playAt(index);break;
            case "play":if(paused || ended)togglePause();break;
            case "pause":if(!paused)togglePause();break;
            case "seek":
                if(!prepared || player==null)throw new IllegalStateException("画面准备中");
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
        super.onNewIntent(intent);
        if(isEmbeddedPlaybackHost())return;
        changePlaybackIntent(intent);
    }
    private void changePlaybackIntent(android.content.Intent intent) {
        boolean fromFeed=feedSwitching;
        if(feedSwitching)feedSwitching=false;
        else if(feedMode || feedReturn){feedMode=false;feedReturn=false;pendingSlideDir=0;cancelSlideTransition();clearOutgoingFrame();hideIncomingMask(true);feedCoverShape(false);hideSwitchCover(true);if(feedLayer!=null)feedLayer.setVisibility(View.GONE);}
        savePlaybackPosition();
        if(isEmbeddedPlaybackHost())embeddedIntent=intent;else setIntent(intent);
        sequence++;if(pendingStream!=null){pendingStream.close();pendingStream=null;}pendingFile=null;
        if(commentsDialog!=null){commentsDialog.dismiss();commentsDialog=null;}
        String nextSeries=intent.getStringExtra(EXTRA_SERIES);
        if(preloadTask!=null){preloadTask.cancel(true);preloadTask=null;}preloadEpisodeVid=null;
        if(feedPreloadTask!=null && (nextSeries==null || !nextSeries.equals(feedPreloadSeriesId))){
            feedPreloadTask.cancel(true);feedPreloadTask=null;feedPreloadSeriesId=null;feedPreloadVid=null;
        }
        if(settingsDialog!=null)settingsDialog.dismiss();closeEpisodes();restorePosition=0;restorePaused=false;
        boolean keepPlayer=fromFeed && !failed && surfaceReady
                && player instanceof ink.pengfei.lvguo.media.CompatMediaPlayer
                && !((ink.pengfei.lvguo.media.CompatMediaPlayer)player).isReleased();
        reuseFeedPlayer=keepPlayer;
        if(keepPlayer){
            try{if(player.isPlaying())player.pause();player.setVolume(0f,0f);}catch(IllegalStateException ignored){}
            if(startMonitor!=null){ui.removeCallbacks(startMonitor);startMonitor=null;}
            discardQueuedEpisode();ui.removeCallbacks(tick);prepared=false;appliedSpeed=1f;
            if(stream!=null){stream.close();stream=null;}
        }else releasePlayer();
        episodes=null;clips.clear();
        seriesId=nextSeries;seriesTitle=intent.getStringExtra(EXTRA_TITLE);
        paintFavorite();
        index=intent.getIntExtra(EXTRA_INDEX,0);pendingResumePosition=intent.getIntExtra("position_ms",0);seriesDetail=null;degraded=false;loadSeries();
    }
}


