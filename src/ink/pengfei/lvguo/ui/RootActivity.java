package ink.pengfei.lvguo.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import ink.pengfei.lvguo.R;
import ink.pengfei.lvguo.ui.page.DetailPage;
import ink.pengfei.lvguo.ui.page.FilterPage;
import ink.pengfei.lvguo.ui.page.HomePage;
import ink.pengfei.lvguo.ui.page.MePage;
import ink.pengfei.lvguo.ui.page.SettingsPage;
import ink.pengfei.lvguo.ui.page.SearchPage;

import java.util.ArrayList;
import java.util.List;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 唯一的主 Activity。左侧竖排导航 + 右侧页面，详情页以全屏覆盖层的形式压栈。
 *
 * 导航采用「焦点即切换」：焦点落到哪个导航项就切到哪个页面，不需要按确认键。
 * 这是电视上最省操作的做法 —— 从内容区按左回到导航只需一次按键。
 */
public class RootActivity extends ink.pengfei.lvguo.PlaybackHostActivity {

    // 导航：首页 / 榜单 / 分类 / 搜索 / 我的 / 设置（历史与收藏并入「我的」）
    public static final int TAB_HOME = 0;
    public static final int TAB_RANK = 1;
    public static final int TAB_FILTER = 2;
    public static final int TAB_SEARCH = 3;
    public static final int TAB_FAVORITES = 4;
    public static final int TAB_HISTORY = TAB_FAVORITES;
    public static final int TAB_ME = TAB_FAVORITES;
    public static final int TAB_MINE = 5;
    /** 直接打开刷剧播放器的导航动作，不对应 mPages 页面。 */
    public static final int TAB_FEED = 6;

    // 「设置」不在左侧导航里了：从「我的」进入，是一个单独的页面（仍是 TAB_MINE，返回回到「我的」）
    // 左侧导航：首页 / 刷剧 / 榜单 / 分类 / 我的 / 设置；搜索和手机遥控是首页右上角的按钮
    private static final String[] NAV_TITLES = {"首页", "刷剧", "榜单", "分类", "我的", "设置"};
    private static final int[] NAV_TABS = {TAB_HOME, TAB_FEED, TAB_RANK, TAB_FILTER, TAB_ME, TAB_MINE};
    private static final int[] NAV_ICONS = {
            IconView.HOME, IconView.DISCOVER, IconView.RANK, IconView.FILTER, IconView.USER, IconView.SETTING};

    private FrameLayout mRoot;
    private ink.pengfei.lvguo.ui.widget.CoverBackdrop mCoverBackdrop;
    private FrameLayout mContent;
    private FrameLayout mOverlay;
    private LinearLayout mRail;

    private final List<NavItem> mNavItems = new ArrayList<>();
    private final Page[] mPages = new Page[TAB_FEED + 1];
    private final Map<HorizontalScrollView, WeakReference<View>> mHorizontalFocus = new WeakHashMap<>();
    private final Map<Page, WeakReference<View>> mPageFocus = new WeakHashMap<>();
    private int mCur = -1;
    private boolean mResumed;

    private DetailPage mDetail;
    private View mFocusBeforeOverlay;
    private Runnable mRestoreOverlayPosition;
    private android.app.Dialog mExitDialog;
    private boolean startupFrameDrawn;
    private boolean loginPromptChecked;

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        sCurrent = new java.lang.ref.WeakReference<>(this);
        super.onCreate(b);
        if(ink.pengfei.lvguo.care.RestMode.enforce(this)){StartupGate.drawn();return;}   // 强制休息时段内：不进入正常界面
        try{ink.pengfei.lvguo.update.UpdateManager.cleanup(this);}catch(Throwable ignored){}
        Ui.applyTheme(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        applyImmersive();
        ink.pengfei.lvguo.net.Session.listen(sessionWatcher);
        if(ink.pengfei.lvguo.App.prefs().getBoolean("usage_consent",false))enterApplication();
        else ink.pengfei.lvguo.ui.widget.UsageConsent.show(this,this::enterApplication);
    }

    // 会话变化（退出登录 / 续期失败）：原地刷新当前页，不强制踢回登录页
    private final Runnable sessionWatcher=()->runOnUiThread(()->{
        if(isFinishing() || isDestroyed())return;
        if(mCur>=0 && mCur<mPages.length && mPages[mCur]!=null)mPages[mCur].onShow();
        if(!ink.pengfei.lvguo.net.Session.loggedIn() && ink.pengfei.lvguo.net.Session.wasExpired()){
            toast("登录已过期，当前以游客模式继续观看");
        }
    });

    private void enterApplication(){
        if(isFinishing() || isDestroyed() || mRoot!=null)return;
        ink.pengfei.lvguo.net.LibrarySync.preloadHistory();
        buildShell();
        ink.pengfei.lvguo.net.Browse.preloadFilters();
        startService(new Intent(this,ink.pengfei.lvguo.remote.LocalService.class));
        switchTab(TAB_HOME,true);
        focusNav(TAB_HOME);
        // 导航默认收起：启动后把焦点交给内容区（首页数据到达后会再落到第一张卡）。
        mPages[TAB_HOME].focusFirst();
        reportStartupDrawn();
        maybeShowLoginPrompt();
        // 启动续播：上次没看完的剧（弹窗询问 / 直接续播 / 不提示，见设置 → 播放）
        // 强制休息结束回到首页时不续播（需求：休息结束不自动恢复之前的视频）
        if(!getIntent().getBooleanExtra(ink.pengfei.lvguo.care.RestActivity.EXTRA_AFTER_REST,false))
            mRoot.post(()->ink.pengfei.lvguo.ui.widget.ResumePrompt.afterHistoryReady(this,
                    ()->mResumed && mCur==TAB_HOME && !isOverlayOpen() && !ink.pengfei.lvguo.care.RestMode.isResting()));
    }

    private void maybeShowLoginPrompt(){
        if(loginPromptChecked || mRoot==null || isFinishing() || isDestroyed())return;
        if(ink.pengfei.lvguo.net.Session.loggedIn() || ink.pengfei.lvguo.care.RestMode.isResting())return;
        if(!ink.pengfei.lvguo.App.prefs().getBoolean("usage_consent",false))return;
        loginPromptChecked=true;
        mRoot.post(()->{
            if(mResumed && !isFinishing() && !ink.pengfei.lvguo.net.Session.loggedIn() && !ink.pengfei.lvguo.care.RestMode.isResting()){
                ink.pengfei.lvguo.ui.widget.LoginPrompt.showIfFirstVisit(this,()->{
                    if(mCur==TAB_HOME && mPages[TAB_HOME]!=null)mPages[TAB_HOME].focusFirst();
                });
            }
        });
    }

    /** 首页首帧真正上屏时通知启动页；之前已出过画（进程还活着）时 onResume 直接放行。 */
    private void reportStartupDrawn(){
        final View decor=getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){
                android.view.ViewTreeObserver observer=decor.getViewTreeObserver();
                if(observer.isAlive())observer.removeOnPreDrawListener(this);
                startupFrameDrawn=true;
                StartupGate.drawn();
                return true;
            }
        });
    }

    /** 停在首页等界面（没在看剧）时，到了休息开始时间直接进入休息页，不需要倒计时。 */
    private final android.os.Handler mainHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable restWatch=new Runnable(){public void run(){
        mainHandler.removeCallbacks(this);
        if(isFinishing())return;
        if(ink.pengfei.lvguo.care.RestMode.enforce(RootActivity.this))return;
        mainHandler.postDelayed(this,15_000L);
    }};

    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);if(hasFocus)applyImmersive();
    }

    @Override protected void onResume(){
        super.onResume();
        Ui.enforceLandscape(this);
        mResumed=true;
        if(ink.pengfei.lvguo.care.RestMode.enforce(this))return;
        if(startupFrameDrawn)StartupGate.drawn();
        restWatch.run();
        maybeShowLoginPrompt();
        // 播放器会把全局调色板强制成深色；返回后恢复 App 选定的主题，后续新建的视图才一致。
        Ui.applyTheme(this);
        for (NavItem it : mNavItems) paintNav(it, it.index == navIndex(mCur));
        // 在后台期间登录失效了：直接回扫码页；否则距上次续期太久就补一次续期
        if(ink.pengfei.lvguo.net.Session.loggedIn()){
            ink.pengfei.lvguo.net.LibrarySync.keepAliveIfStale();
            ink.pengfei.lvguo.net.LibrarySync.pull();
        }
        // 检查更新：打开首页立即在后台发起检查（不阻塞首帧），有新版本立刻弹窗提示
        try{getWindow().getDecorView().post(()->{
            try{if(!isFinishing()&&!ink.pengfei.lvguo.care.RestMode.isResting())ink.pengfei.lvguo.update.UpdateManager.onHomeResume(this);}catch(Throwable ignored){}
        });}catch(Throwable ignored){}
    }

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        // 浅色主题下，万一系统栏露出来，用深色图标。
        if (!Ui.isDark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    private void buildShell() {
        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(Ui.BG);
        mCoverBackdrop=new ink.pengfei.lvguo.ui.widget.CoverBackdrop(this);mRoot.addView(mCoverBackdrop,new FrameLayout.LayoutParams(-1,-1));

        mContent = new FrameLayout(this);
        mContent.setId(View.generateViewId());
        mContent.setClipChildren(false);

        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contentLp.leftMargin = Ui.dp(this, Ui.RAIL_W);
        mRoot.addView(mContent, contentLp);

        // 导航展开时压在内容上的左侧渐变，保证文字可读；收起时完全透明。
        mRailScrim = new View(this);
        mRailScrim.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.alpha(Ui.BG, 0xFA), Ui.alpha(Ui.BG, 0xE6), Ui.alpha(Ui.BG, 0x80), Ui.alpha(Ui.BG, 0x00)}));
        mRailScrim.setAlpha(0f);
        mRoot.addView(mRailScrim, new FrameLayout.LayoutParams(Ui.dp(this, 620), ViewGroup.LayoutParams.MATCH_PARENT));

        mRail = buildRail();
        mRoot.addView(mRail, new FrameLayout.LayoutParams(Ui.dp(this, Ui.RAIL_W), ViewGroup.LayoutParams.MATCH_PARENT));
        mRoot.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            setRailOpen(isDescendant(mRail, newFocus));
            rememberHorizontalFocus(newFocus);
            rememberPageFocus(newFocus);
        });

        mOverlay = new FrameLayout(this);
        mOverlay.setVisibility(View.GONE);
        mOverlay.setBackgroundColor(Ui.BG);
        mRoot.addView(mOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(mRoot);
    }

    /**
     * 切换主题：不 recreate 整个 Activity（会黑屏闪一下、焦点全丢），
     * 而是淡出 → 用新调色板原地重建界面 → 回到设置页并把焦点放回原来那一行 → 淡入。
     */
    public void applyThemeInPlace(String focusKey){
        if(mRoot==null){recreate();return;}
        final View old=mRoot;
        old.animate().alpha(0f).setDuration(140).withEndAction(()->{
            Ui.applyTheme(this);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
            applyImmersive();
            for(int i=0;i<mPages.length;i++)mPages[i]=null;
            mNavItems.clear();mDetail=null;mCur=-1;
            buildShell();
            mRoot.setAlpha(0f);
            // 换主题 / 画面效果后回到原处：我的 → 设置页，焦点回到刚改的那一项
            switchTab(TAB_MINE,true);
            mSettings=(ink.pengfei.lvguo.ui.page.SettingsPage)mPages[TAB_MINE];
            if(mSettings!=null)mSettings.restoreFocus(focusKey);
            mRoot.animate().alpha(1f).setDuration(200).start();
        }).start();
    }

    // ------------------------------------------------------------ 左侧导航

    private final class NavItem {
        FrameLayout box;
        View bar;
        IconView icon;
        TextView label;
        int index;
    }

    private View mRailScrim;
    private static java.lang.ref.WeakReference<RootActivity> sCurrent = new java.lang.ref.WeakReference<>(null);
    /** 当前打开的主界面（手机改电视设置时用来原地生效），可能为空。 */
    public static RootActivity current() { return sCurrent.get(); }
    private TextView mRailWordmark;
    private View mLogoRow;
    private boolean mRailOpen;
    private android.animation.ValueAnimator mRailAnim;

    /** 收起：只露图标；展开：图标 + 文字，宽度动画到 RAIL_OPEN_W 并压上左侧渐变。 */
    private void setRailOpen(boolean open) {
        // 导航只显示图标，不再展开文字。
        if (true) return;
        if (mRail == null || open == mRailOpen) return;
        mRailOpen = open;
        if (mRailAnim != null) mRailAnim.cancel();
        final ViewGroup.LayoutParams lp = mRail.getLayoutParams();
        mRailAnim = android.animation.ValueAnimator.ofInt(lp.width, Ui.dp(this, open ? Ui.RAIL_OPEN_W : Ui.RAIL_W));
        mRailAnim.setDuration(open ? 220 : 180);
        mRailAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
        mRailAnim.addUpdateListener(a -> { lp.width = (int) a.getAnimatedValue(); mRail.setLayoutParams(lp); });
        mRailAnim.start();
        mRailScrim.animate().cancel();
        mRailScrim.animate().alpha(open ? 1f : 0f).setDuration(open ? 220 : 160).start();
        float labelAlpha = open ? 1f : 0f;
        mRailWordmark.animate().alpha(labelAlpha).setDuration(160).start();
        for (NavItem it : mNavItems) it.label.animate().alpha(labelAlpha).setDuration(open ? 200 : 100).start();
    }

    private LinearLayout buildRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setClipChildren(false);
        rail.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 16));   // 6 项 × 70dp + logo 刚好放进 540dp 高度
        // 导航与内容区分开：导航用卡片色底（浅色=白、深色=比背景亮一级），内容区是页面底色
        rail.setBackgroundColor(Ui.SURFACE);

        // Logo：果子标 + 下方「绿果TV」字标（TV 用主题色），和菜单文字一起显示 / 隐藏
        LinearLayout logoRow = Ui.column(this);
        logoRow.setGravity(Gravity.CENTER_HORIZONTAL);
        android.widget.ImageView mark = new android.widget.ImageView(this);
        mark.setImageResource(ink.pengfei.lvguo.R.drawable.logo_mark);
        mark.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        logoRow.addView(mark, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 44)));
        android.text.SpannableString word = new android.text.SpannableString("绿果TV");
        word.setSpan(new android.text.style.ForegroundColorSpan(Ui.ACCENT_TEXT), 2, 4, 0);
        mRailWordmark = Ui.text(this, "", 13, Ui.TEXT, true);
        mRailWordmark.setText(word);
        mRailWordmark.setSingleLine(true);
        mRailWordmark.setVisibility(railLabels() ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams llp = Ui.lp(-2, -2);
        llp.topMargin = Ui.dp(this, 3);
        logoRow.addView(mRailWordmark, llp);
        LinearLayout.LayoutParams logoLp = Ui.lp(Ui.dp(this, Ui.RAIL_W), -2);
        logoLp.bottomMargin = Ui.dp(this, railLabels() ? 8 : 20);
        mLogoRow = logoRow;
        rail.addView(logoRow, logoLp);

        LinearLayout menuItems = new LinearLayout(this);
        menuItems.setOrientation(LinearLayout.VERTICAL);
        menuItems.setClipChildren(false);
        menuItems.setClipToPadding(false);
        Ui.padding(menuItems, 0, 4, 0, 4);
        // 菜单超出屏幕高度时可以滚动（焦点移到哪里就滚到哪里）
        ScrollView menuScroll = new ink.pengfei.lvguo.ui.widget.FocusScrollView(this);
        menuScroll.setVerticalScrollBarEnabled(false);
        menuScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        menuScroll.setClipChildren(false);
        menuScroll.setFillViewport(true);
        menuScroll.addView(menuItems, new ScrollView.LayoutParams(-1, -2));
        rail.addView(menuScroll, Ui.lp(-1, 0, 1f));

        for (int i = 0; i < NAV_TITLES.length; i++) {
            final int idx = NAV_TABS[i];
            final NavItem it = new NavItem();
            it.index = idx;

            FrameLayout box = new FrameLayout(this);
            box.setFocusable(true);
            box.setFocusableInTouchMode(true);
            box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            box.setId(View.generateViewId());
            box.setContentDescription(NAV_TITLES[i]);
            if (Build.VERSION.SDK_INT >= 26) box.setDefaultFocusHighlightEnabled(false);

            // 当前页指示：左缘的短竖条
            View bar = new View(this);
            // 当前页：图标下方的品牌色小圆点
            bar.setBackground(Ui.pill(this, Ui.PRIMARY));
            bar.setVisibility(View.GONE);
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                    Ui.dp(this, 5), Ui.dp(this, 5), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            barLp.bottomMargin = Ui.dp(this, 6);
            box.addView(bar, barLp);

            IconView icon = new IconView(this, NAV_ICONS[i], railLabels() ? 26 : 28);
            TextView label = navLabel(NAV_TITLES[i]);
            box.addView(navContent(icon, label), new FrameLayout.LayoutParams(-1, -1));

            box.setOnFocusChangeListener((v, has) -> {
                if (has && intentionalRailFocus) switchTab(idx, false);
                paintNav(it, it.index == navIndex(mCur));
            });
            box.setOnTouchListener((v,event)->{
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP){focusNav(idx);v.performClick();}
                return true;
            });
            box.setOnClickListener(v -> {
                for (int guard = 0; guard < 8 && isOverlayOpen(); guard++) closeOverlay();
                switchTab(idx, false);
                if (idx == TAB_FEED) focusFeed();
            });

            // 56dp 圆角方块居中在导航栏里（图标中心距屏幕左缘 54dp，在电视安全区内）
            // 六个入口要在常见电视上一屏放下；导航顺序与焦点移动保持一致。
            menuItems.addView(box, lp2());

            it.box = box;
            it.bar = bar;
            it.icon = icon;
            it.label = label;
            mNavItems.add(it);
        }
        return rail;
    }

    /** 导航文字（图标下方两个字），可在 设置 → 外观 关掉。 */
    public static boolean railLabels() { return ink.pengfei.lvguo.App.prefs().getBoolean("rail_labels", true); }

    private LinearLayout.LayoutParams lp2() {
        boolean labels = railLabels();
        // 六个入口在常见 540dp 高的电视上一屏放下，焦点移动不需要滚动导航。
        int w = labels ? 72 : 60, h = labels ? 64 : 60;
        LinearLayout.LayoutParams lp = Ui.lp(Ui.dp(this, w), Ui.dp(this, h));
        lp.leftMargin = Ui.dp(this, (Ui.RAIL_W - w) / 2);
        lp.bottomMargin = Ui.dp(this, labels ? 6 : 8);
        return lp;
    }

    private TextView navLabel(String text) {
        TextView label = Ui.text(this, text, 12, Ui.TEXT_2, false);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        return label;
    }

    private LinearLayout navContent(IconView icon, TextView label) {
        LinearLayout col = Ui.column(this);
        col.setGravity(Gravity.CENTER);
        col.addView(icon);
        LinearLayout.LayoutParams lbp = Ui.lp(-2, -2); lbp.topMargin = Ui.dp(this, 3);
        col.addView(label, lbp);
        label.setVisibility(railLabels() ? View.VISIBLE : View.GONE);
        return col;
    }

    /** 设置里切换「导航栏文字」后原地生效，不重建界面。 */
    public void applyRailLabels() {
        boolean labels = railLabels();
        List<NavItem> all = new ArrayList<>(mNavItems);
        if (mRailWordmark != null) mRailWordmark.setVisibility(labels ? View.VISIBLE : View.GONE);
        if (mLogoRow != null) { ((LinearLayout.LayoutParams) mLogoRow.getLayoutParams()).bottomMargin = Ui.dp(this, labels ? 8 : 20); mLogoRow.requestLayout(); }
        for (NavItem it : all) {
            it.label.setVisibility(labels ? View.VISIBLE : View.GONE);
            it.icon.getLayoutParams().width = it.icon.getLayoutParams().height = Ui.dp(this, labels ? 26 : 28);
            it.icon.requestLayout();
            it.box.setLayoutParams(lp2());
            paintNav(it, it.index == mCur);
        }
    }


    private void paintNav(NavItem it, boolean active) {
        boolean focused = it.box.hasFocus();
        it.box.setBackground(focused ? Ui.round(this, Ui.FOCUS_FILL, 18) : null);
        Ui.focus(it.box);
        // 有文字时靠文字/图标的主题色表示当前页，不再画底部小圆点（会和文字挤在一起）
        it.bar.setVisibility(active && !focused && !railLabels() ? View.VISIBLE : View.GONE);
        int color = focused ? Ui.ON_FOCUS : active ? Ui.ACCENT_TEXT : Ui.TEXT_3;
        it.icon.setColor(color);
        it.label.setTextColor(color);   // 文字与图标同色：选中/当前页时一起变色
        it.label.setTypeface(android.graphics.Typeface.defaultFromStyle(focused || active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
    }

    // ------------------------------------------------------------ 页面切换

    public void switchTab(int index, boolean force) {
        ink.pengfei.lvguo.media.PlaybackWarmup.cancel();
        if (index < 0 || index >= mPages.length) return;
        if (index == mCur && !force) return;
        boolean leavingFeed=mCur==TAB_FEED;
        if (mCur >= 0 && mPages[mCur] != null) mPages[mCur].onHide();
        mCur = index;
        if (mCoverBackdrop != null) mCoverBackdrop.setHero(index == TAB_HOME);

        // 搜索页从首页右上角进入：导航上点亮「首页」
        for (NavItem it : mNavItems) paintNav(it, it.index == navIndex(index));

        if (mPages[index] == null) {
            mPages[index] = createPage(index);
            mPages[index].attach(this);
            mContent.addView(mPages[index].root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        for (int i = 0; i < mPages.length; i++) {
            if (mPages[i] != null) {
                mPages[i].root.setVisibility(i == index ? View.VISIBLE : View.GONE);
            }
        }
        Page p = mPages[index];
        if (!force && index != TAB_FEED && !leavingFeed) Ui.enter(p.root, 0, 14, 220);   // 切换页面：轻微上浮淡入
        p.onShow();
        NavItem nav = navItem(index);
        if (nav != null) p.bindNav(nav.box.getId());
    }

    private Page createPage(int index) {
        switch (index) {
            case TAB_FEED:
                return new ink.pengfei.lvguo.ui.page.FeedPage();
            case TAB_RANK:
                return new FilterPage(true);
            case TAB_FILTER:
                return new FilterPage(false);
            case TAB_SEARCH:
                return new SearchPage();
            case TAB_ME:
                return new MePage();
            case TAB_MINE:
                return new SettingsPage();
            default:
                return new HomePage();
        }
    }

    private static boolean isDescendant(View parent, View child) {
        if (parent == null || child == null) return false;
        View cur = child;
        while (cur != null) {
            if (cur == parent) return true;
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return false;
    }

    private static HorizontalScrollView horizontalAncestor(View view) {
        View current = view;
        while (current != null) {
            if (current instanceof HorizontalScrollView) return (HorizontalScrollView) current;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return null;
    }

    private void rememberHorizontalFocus(View focused) {
        HorizontalScrollView row = horizontalAncestor(focused);
        if (row != null && focused != row) mHorizontalFocus.put(row, new WeakReference<>(focused));
    }

    private void rememberPageFocus(View focused) {
        if (focused == null || mCur < 0 || mCur >= mPages.length) return;
        Page page = mPages[mCur];
        if (page != null && isDescendant(page.root, focused))
            mPageFocus.put(page, new WeakReference<>(focused));
    }

    /** Return to the current page's last focused control when leaving the rail. */
    private void focusPageRemembered() {
        Page page = mCur >= 0 && mCur < mPages.length ? mPages[mCur] : null;
        if (page == null) return;
        if (mCur == TAB_FEED) { page.focusFirst(); return; }
        WeakReference<View> reference = mPageFocus.get(page);
        View remembered = reference == null ? null : reference.get();
        if (remembered != null && remembered.isAttachedToWindow()
                && isDescendant(page.root, remembered)
                && remembered.getVisibility() == View.VISIBLE && remembered.isFocusable()
                && remembered.requestFocus(View.FOCUS_RIGHT)) return;
        page.focusFirst();
    }

    private View rememberedHorizontalFocus(HorizontalScrollView row) {
        if (row instanceof ink.pengfei.lvguo.ui.widget.HScrollRow) {
            View remembered = ((ink.pengfei.lvguo.ui.widget.HScrollRow) row).rememberedFocus();
            if (remembered != null) return remembered;
        }
        WeakReference<View> reference = mHorizontalFocus.get(row);
        View remembered = reference == null ? null : reference.get();
        return remembered != null && remembered.isAttachedToWindow() && isDescendant(row, remembered)
                && remembered.getVisibility() == View.VISIBLE && remembered.isFocusable() ? remembered : null;
    }

    public Page page(int index) {
        return mPages[index];
    }

    // ------------------------------------------------------------ 详情覆盖层

    // 「查看全部」页：放在覆盖层里；从它打开详情时把它记下来，关闭详情时回到它（焦点回到原卡片）
    private ink.pengfei.lvguo.ui.Page mListPage;
    private View mFocusInList;
    private boolean mDetailOverList;
    /** 覆盖层里的页面栈（设置 → 关于 …），返回逐层回退。 */
    private final List<Object[]> mPageStack = new ArrayList<>();

    /** 在覆盖层里打开一个全屏子页面（查看全部 / 关于…），返回键回到原处。 */
    public void openList(ink.pengfei.lvguo.ui.Page page) {
        if(!isOverlayOpen()){
            mFocusBeforeOverlay = currentFocus();
            if(mPages[mCur]!=null)mRestoreOverlayPosition=mPages[mCur].rememberScroll();
        }
        // 已经开着一个整页（如设置）再打开下一页（如关于）：压栈，返回时回到上一页原来的焦点
        if (isOverlayOpen() && mListPage != null && mListPage.root.getParent() == mOverlay && mListPage != page) {
            mPageStack.add(new Object[]{mListPage, currentFocus()});
        }
        page.attach(this);
        mListPage = page; mDetailOverList = false;
        mOverlay.removeAllViews();
        mOverlay.addView(page.root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mOverlay.setVisibility(View.VISIBLE);
        mOverlay.animate().cancel(); mOverlay.setAlpha(1f);
        Ui.enter(page.root, 40, 0, 240);   // 整页从右侧滑入
        mRail.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        mContent.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        page.onShow();
    }

    public void openDetail(ink.pengfei.lvguo.model.Series s) {
        if(isOverlayOpen() && mListPage!=null && mListPage.root.getParent()==mOverlay){
            mFocusInList = currentFocus(); mDetailOverList = true;
        }
        if(!isOverlayOpen()){
            mFocusBeforeOverlay = currentFocus();
            if(mPages[mCur]!=null)mRestoreOverlayPosition=mPages[mCur].rememberScroll();
        }
        if (mDetail == null) {
            mDetail = new DetailPage();
            mDetail.attach(this);
        }
        mDetail.setSeries(s);
        mOverlay.removeAllViews();
        mOverlay.addView(mDetail.root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mOverlay.setVisibility(View.VISIBLE);
        mOverlay.animate().cancel();
        mOverlay.setAlpha(1f);
        Ui.enter(mDetail.root, 40, 0, 240);   // 详情从右侧滑入
        mRail.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        mContent.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        mDetail.onEnter();
    }

    public void closeOverlay() {
        if (mOverlay.getVisibility() != View.VISIBLE) return;
        // 详情是从「查看全部」页打开的：先回到全部页，焦点放回原来那张卡片
        if (mDetailOverList && mListPage != null) {
            mDetailOverList = false;
            if (mDetail != null) mDetail.onLeave();
            mOverlay.removeAllViews();
            mOverlay.addView(mListPage.root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View f = mFocusInList; mFocusInList = null;
            if (f != null && f.isAttachedToWindow()) { f.requestFocus(); } else mListPage.focusFirst();
            return;
        }
        if (!mPageStack.isEmpty()) {
            Object[] prev = mPageStack.remove(mPageStack.size() - 1);
            mListPage = (Page) prev[0];
            mOverlay.removeAllViews();
            mOverlay.addView(mListPage.root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View f = (View) prev[1];
            if (f != null && f.isAttachedToWindow()) f.requestFocus(); else mListPage.focusFirst();
            return;
        }
        mListPage = null;
        mRail.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        mContent.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // 先把焦点还给原来的控件，再隐藏覆盖层：反过来的话系统会先把焦点塞给第一个可聚焦控件
        // （设置页会因此切到「通用」分类、重建右栏，原来那一行就没了）
        View back = mFocusBeforeOverlay; mFocusBeforeOverlay = null;
        boolean restored = back != null && back.isAttachedToWindow() && back.requestFocus();
        mOverlay.setVisibility(View.GONE);
        mOverlay.removeAllViews();
        if (!restored) {
            Page p = mPages[mCur];
            if (p != null) p.focusFirst();
        }
        if (mDetail != null) mDetail.onLeave();
        if(mRestoreOverlayPosition!=null){Runnable restore=mRestoreOverlayPosition;mRestoreOverlayPosition=null;restore.run();mContent.post(restore);}
        // 恢复滚动位置后，确保焦点所在的控件仍在屏幕内（否则焦点会停在看不见的地方）
        if(restored){final View f=back;mContent.postDelayed(()->{if(f.isAttachedToWindow()&&f.hasFocus())f.requestRectangleOnScreen(new android.graphics.Rect(0,0,f.getWidth(),f.getHeight()),false);},60);}
    }

    /** 当前焦点：getCurrentFocus() 在窗口刚切换时可能为空，兜底用视图树查找。 */
    private View currentFocus() {
        View f = getCurrentFocus();
        if (f == null && getWindow() != null) f = getWindow().getDecorView().findFocus();
        return f;
    }

    /** 从找剧左侧导航跳回来：切到对应页并把焦点放在导航上。 */
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        int tab = intent.getIntExtra("open_tab", -1);
        if (tab >= 0 && mRoot != null) {
            if (isOverlayOpen()) closeOverlay();
            switchTab(tab, false);
            mRoot.post(() -> focusNav(tab));
        }
    }

    public boolean isOverlayOpen() {
        return mOverlay.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------ 键处理

    private long backDownTime=-1;
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(mRoot==null)return super.dispatchKeyEvent(event);
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK){
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)backDownTime=event.getDownTime();
            if(event.getAction()==KeyEvent.ACTION_UP){
                // Some remotes assign a new downTime to KEY_UP; pair by ownership.
                boolean complete=backDownTime>=0 && !event.isCanceled();
                backDownTime=-1;
                if(complete)handleBack();
            }
            return true;
        }
        if (mCur == TAB_FEED && !isFeedMasked() && !isOverlayOpen() && !isDescendant(mRail,currentFocus())) {
            if(playbackKey(event))return true;
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        if (mCur == TAB_FEED && isFeedMasked() && !isOverlayOpen() && !isDescendant(mRail, currentFocus())) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.getRepeatCount() == 0) focusFeed();
                return true;
            }
            focusNav(TAB_FEED);
        }
        int key = event.getKeyCode();
        int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT
                : key == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                : key == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP
                : key == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : 0;
        if (direction == 0) return super.dispatchKeyEvent(event);
        View focused = currentFocus();
        // 先交给当前焦点自己处理（控件的方向键逻辑，如设置分类「右键进第一项、左键回当前分类」），
        // 它不处理时再按几何位置找下一个焦点；之前直接几何查找，控件自己的逻辑全被跳过
        if (focused != null && !isDescendant(mRail, focused) && focused.dispatchKeyEvent(event)) return true;
        if (isOverlayOpen()) {
            View next = focused == null ? null : android.view.FocusFinder.getInstance()
                    .findNextFocus(mOverlay, focused, direction);
            if (next == null || !isDescendant(mOverlay, next)) return true;
            next.requestFocus(direction); return true;
        }
        if (isDescendant(mRail, focused)) {
            if (direction == View.FOCUS_RIGHT) { focusPageRemembered(); return true; }
            if (direction == View.FOCUS_LEFT) return true;
            // 按导航上的实际顺序移动（找剧不是页面，之前按页面序号跳会直接跳过它）
            List<NavItem> order = railOrder();
            int at = 0;
            for (int i = 0; i < order.size(); i++) if (isDescendant(order.get(i).box, focused) || order.get(i).box == focused) at = i;
            int to = Math.max(0, Math.min(order.size() - 1, at + (direction == View.FOCUS_DOWN ? 1 : -1)));
            NavItem target = order.get(to);
            focusNav(target.index);
            return true;
        }
        Page page = mPages[mCur];
        if (page != null && isDescendant(page.root, focused)) {
            // Page widgets often provide an explicit edge target (for example the
            // first card in each grid row points left to the rail). Respect it
            // before geometric search, which can otherwise pick a filter near
            // the top of a scrolled page and move the whole page unexpectedly.
            int explicitId = direction == View.FOCUS_LEFT ? focused.getNextFocusLeftId()
                    : direction == View.FOCUS_RIGHT ? focused.getNextFocusRightId()
                    : direction == View.FOCUS_UP ? focused.getNextFocusUpId()
                    : focused.getNextFocusDownId();
            View explicit = explicitId == View.NO_ID ? null : mRoot.findViewById(explicitId);
            if (explicit != null && explicit.isFocusable() && explicit.getVisibility() == View.VISIBLE
                    && (isDescendant(page.root, explicit) || isDescendant(mRail, explicit))
                    && explicit.requestFocus(direction)) return true;
            View next = android.view.FocusFinder.getInstance().findNextFocus((ViewGroup)page.root, focused, direction);
            if (next != null && isDescendant(page.root, next)) {
                if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
                    HorizontalScrollView currentRow = horizontalAncestor(focused);
                    HorizontalScrollView targetRow = horizontalAncestor(next);
                    if (targetRow != null && targetRow != currentRow) {
                        View remembered = rememberedHorizontalFocus(targetRow);
                        if (remembered != null) next = remembered;
                    }
                }
                next.requestFocus(direction); return true;
            }
            if (direction == View.FOCUS_LEFT) focusNav(mCur);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if(event.getRepeatCount()==0)handleBack();return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed(){if(mRoot==null)finish();else handleBack();}
    /** 打开设置页（从「我的」或遥控器菜单键进入），焦点落在分类上。 */
    /** 设置：和「关于」一样是单独的整页（覆盖层），返回回到打开它的地方（「我的」里的设置入口）。 */
    private ink.pengfei.lvguo.ui.page.SettingsPage mSettings;
    public void openSettings(){
        for(int guard=0;guard<8 && isOverlayOpen();guard++)closeOverlay();   // 关掉所有层再打开设置
        switchTab(TAB_MINE,false);
        mSettings=(ink.pengfei.lvguo.ui.page.SettingsPage)mPages[TAB_MINE];
        final ink.pengfei.lvguo.ui.page.SettingsPage sp=mSettings;
        mRoot.post(sp::focusFirst);
    }

    /** 首页右上角「搜索」：打开搜索页，返回回到首页的搜索按钮。 */
    public void openSearch(){
        for(int guard=0;guard<8 && isOverlayOpen();guard++)closeOverlay();
        switchTab(TAB_SEARCH,false);
        final Page sp=mPages[TAB_SEARCH];
        mRoot.post(sp::focusFirst);
    }

    private void handleBack(){
        if(ink.pengfei.lvguo.ui.widget.PosterCard.dismissActive())return;   // 先关封面上的「移除」蒙层
        if(isOverlayOpen()){closeOverlay();return;}
        if(mCur==TAB_FEED && (!isFeedMasked() || feedIsFullscreen()) && embeddedPlaybackBack())return;
        // 设置页返回「我的」，焦点回到「设置」按钮
        Page page=mPages[mCur];
        if(page!=null && page.onBack())return;
        if(page!=null && page.returnToTop())return;
        if(mCur==TAB_SEARCH){   // 搜索页返回首页，焦点回到右上角的搜索按钮
            switchTab(TAB_HOME,false);
            Page home=mPages[TAB_HOME];
            if(home instanceof ink.pengfei.lvguo.ui.page.HomePage)((ink.pengfei.lvguo.ui.page.HomePage)home).focusSearchButton();
            return;
        }
        if(!isDescendant(mRail,currentFocus())){
            focusNav(mCur);return;
        }
        if(mCur!=TAB_HOME){switchTab(TAB_HOME,false);focusNav(TAB_HOME);return;}
        confirmExit();
    }

    /** 显示「退出绿果TV？」确认框。 */
    public void confirmExit(){
        if(mExitDialog!=null && mExitDialog.isShowing())return;
        mExitDialog=ink.pengfei.lvguo.ui.widget.TvDialog.confirm(this,"退出绿果TV？","","退出",this::finish);
    }

    @Override protected boolean isEmbeddedPlaybackHost(){return true;}
    public View feedView(){return embeddedFeedView();}
    public void showFeed(){showEmbeddedPlayback();}
    public void hideFeed(){if(feedIsFullscreen())onPlaybackFullscreenChanged(false);hideEmbeddedPlayback();}
    public void focusFeed(){focusEmbeddedPlayback();}
    @Override protected void focusPlaybackNavigation(){focusNav(TAB_FEED);}
    @Override protected void onPlaybackFullscreenChanged(boolean fullscreen){
        mRail.setVisibility(fullscreen?View.GONE:View.VISIBLE);
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)mContent.getLayoutParams();
        lp.leftMargin=fullscreen?0:Ui.dp(this,Ui.RAIL_W);mContent.setLayoutParams(lp);
    }

    public void setCoverBackground(String url){if(mCoverBackdrop!=null)mCoverBackdrop.showCover(url,false);}
    public void seedCoverBackground(String url){if(mCoverBackdrop!=null)mCoverBackdrop.seed(url);}

    private boolean intentionalRailFocus;
    /** 导航项的显示顺序：首页、刷剧、榜单、分类、我的、设置（和添加顺序一致）。 */
    private List<NavItem> railOrder() { return new ArrayList<>(mNavItems); }

    /** 页面对应的导航项：搜索页没有自己的导航项，算作「首页」。 */
    private static int navIndex(int tab) { return tab == TAB_SEARCH ? TAB_HOME : tab; }
    private NavItem navItem(int tab) { int t = navIndex(tab); for (NavItem it : mNavItems) if (it.index == t) return it; return null; }

    public void focusNav(int index) {
        NavItem it = navItem(index);
        if (it != null) {
            intentionalRailFocus=true;try{it.box.requestFocus();}finally{intentionalRailFocus=false;}
        }
    }

    public int currentTab() {
        return mCur;
    }

    public void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        mResumed=false;
        mainHandler.removeCallbacks(restWatch);
        super.onPause();
        // 离开界面时释放不必要的位图，回来会从磁盘/内存 LRU 重新取
    }

    @Override
    protected void onDestroy() {
        ink.pengfei.lvguo.net.Session.unlisten(sessionWatcher);
        super.onDestroy();
        if (isFinishing()) {
            ink.pengfei.lvguo.img.ImageLoader.clearMemory();
        }
    }
}
