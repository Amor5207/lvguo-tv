package com.hgtv.app.ui;

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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hgtv.app.R;
import com.hgtv.app.ui.page.DetailPage;
import com.hgtv.app.ui.page.FilterPage;
import com.hgtv.app.ui.page.HomePage;
import com.hgtv.app.ui.page.HistoryPage;
import com.hgtv.app.ui.page.MinePage;
import com.hgtv.app.ui.page.SearchPage;

import java.util.ArrayList;
import java.util.List;

/**
 * 唯一的主 Activity。左侧竖排导航 + 右侧页面，详情页以全屏覆盖层的形式压栈。
 *
 * 导航采用「焦点即切换」：焦点落到哪个导航项就切到哪个页面，不需要按确认键。
 * 这是电视上最省操作的做法 —— 从内容区按左回到导航只需一次按键。
 */
public class RootActivity extends Activity {

    public static final int TAB_HOME = 0;
    public static final int TAB_SEARCH = 1;
    public static final int TAB_FILTER = 2;
    public static final int TAB_HISTORY = 3;
    public static final int TAB_FAVORITES = 4;
    public static final int TAB_MINE = 5;

    private static final String[] NAV_TITLES = {"推荐", "搜索", "分类", "历史", "追剧", "设置"};
    private static final int[] NAV_ICONS = {
            IconView.HOME, IconView.SEARCH, IconView.FILTER, IconView.HISTORY, IconView.STAR, IconView.SETTING};

    private FrameLayout mRoot;
    private com.hgtv.app.ui.widget.CoverBackdrop mCoverBackdrop;
    private FrameLayout mContent;
    private FrameLayout mOverlay;
    private LinearLayout mRail;

    private final List<NavItem> mNavItems = new ArrayList<>();
    private final Page[] mPages = new Page[NAV_TITLES.length];
    private int mCur = -1;

    private DetailPage mDetail;
    private View mFocusBeforeOverlay;
    private Runnable mRestoreOverlayPosition;
    private android.app.Dialog mExitDialog;

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        applyImmersive();
        if(com.hgtv.app.App.prefs().getBoolean("usage_consent",false))enterApplication();
        else com.hgtv.app.ui.widget.UsageConsent.show(this,this::enterApplication);
    }

    private void enterApplication(){
        if(isFinishing() || isDestroyed() || mRoot!=null)return;
        buildShell();
        com.hgtv.app.net.Browse.preloadFilters();
        startService(new Intent(this,com.hgtv.app.remote.LocalService.class));
        switchTab(TAB_HOME,true);
        focusNav(TAB_HOME);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);if(hasFocus)applyImmersive();
    }

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void buildShell() {
        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(Ui.BG);
        mCoverBackdrop=new com.hgtv.app.ui.widget.CoverBackdrop(this);mRoot.addView(mCoverBackdrop,new FrameLayout.LayoutParams(-1,-1));

        mContent = new FrameLayout(this);
        mContent.setId(View.generateViewId());
        mContent.setClipChildren(false);

        mRail = buildRail();
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(
                Ui.dp(this, Ui.RAIL_W), ViewGroup.LayoutParams.MATCH_PARENT);
        mRoot.addView(mRail, railLp);

        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contentLp.leftMargin = Ui.dp(this, Ui.RAIL_W);
        mRoot.addView(mContent, contentLp);

        mOverlay = new FrameLayout(this);
        mOverlay.setVisibility(View.GONE);
        mOverlay.setBackgroundColor(Ui.BG);
        mRoot.addView(mOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(mRoot);
    }

    // ------------------------------------------------------------ 左侧导航

    private final class NavItem {
        FrameLayout box;
        View bar;
        IconView icon;
        TextView label;
        int index;
    }

    private LinearLayout buildRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(0x99101313);
        rail.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 18));

        // Logo
        LinearLayout logoRow = Ui.row(this);
        logoRow.setGravity(Gravity.CENTER);
        logoRow.setPadding(0,0,0,0);
        android.widget.ImageView mark = new android.widget.ImageView(this);
        mark.setImageResource(com.hgtv.app.R.drawable.logo_mark);
        mark.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        mark.setTranslationY(-Ui.dp(this,1));
        logoRow.addView(mark, Ui.lp(Ui.dp(this, 26), Ui.dp(this, 26)));
        TextView logo = Ui.text(this, "绿果TV", 20, Ui.TEXT, true);
        LinearLayout.LayoutParams llp = Ui.lp(-2, -2);
        logo.setIncludeFontPadding(false);
        llp.leftMargin = Ui.dp(this, 8);
        logoRow.addView(logo, llp);
        LinearLayout.LayoutParams logoLp = Ui.lp(-1, Ui.dp(this, 56));
        logoLp.bottomMargin = Ui.dp(this, 24);
        rail.addView(logoRow, logoLp);

        ScrollView menuScroll = new ScrollView(this);
        menuScroll.setFillViewport(true);
        menuScroll.setClipToPadding(false);
        menuScroll.setVerticalScrollBarEnabled(false);
        menuScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        menuScroll.setFocusable(false);
        menuScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout menuItems = new LinearLayout(this);
        menuItems.setOrientation(LinearLayout.VERTICAL);
        menuScroll.addView(menuItems, new ScrollView.LayoutParams(-1, -2));
        rail.addView(menuScroll, Ui.lp(-1, 0, 1f));

        // 导航项
        for (int i = 0; i < NAV_TITLES.length; i++) {
            final int idx = i;
            final NavItem it = new NavItem();
            it.index = i;

            FrameLayout box = new FrameLayout(this);
            box.setFocusable(true);
            box.setFocusableInTouchMode(true);
            box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            box.setId(View.generateViewId());
            box.setBackgroundColor(Color.TRANSPARENT);

            View bar = new View(this);
            bar.setBackground(Ui.pill(this, Ui.PRIMARY));
            bar.setVisibility(View.GONE);
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                    Ui.dp(this, 3), Ui.dp(this, 22), Gravity.LEFT | Gravity.CENTER_VERTICAL);
            barLp.leftMargin = Ui.dp(this, 6);
            box.addView(bar, barLp);

            LinearLayout line = Ui.row(this);
            line.setGravity(Gravity.CENTER);
            line.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            IconView icon = new IconView(this, NAV_ICONS[i], 21);
            line.addView(icon);
            TextView label = Ui.text(this, NAV_TITLES[i], 15, Ui.TEXT_2, false);
            LinearLayout.LayoutParams lbp = Ui.lp(-2, -2);
            lbp.leftMargin = Ui.dp(this, 10);
            line.addView(label, lbp);
            box.addView(line, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            box.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    v.animate().cancel();
                    v.setBackground(Ui.roundStroke(this, Ui.SURFACE_2, 12, 0x30FFFFFF, 1));
                    icon.setColor(Ui.TEXT);
                    label.setTextColor(Ui.TEXT);
                    if(intentionalRailFocus)switchTab(idx, false);paintNav(it,mCur==idx);
                } else {
                    paintNav(it, mCur == idx);
                }
            });
            box.setOnTouchListener((v,event)->{
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP){focusNav(idx);v.performClick();}
                return true;
            });
            box.setOnClickListener(v -> {
                if (mOverlay.getVisibility() == View.VISIBLE) closeOverlay();
                switchTab(idx, false);
            });

            LinearLayout.LayoutParams lp = Ui.lp(-1, Ui.dp(this, 52));
            lp.leftMargin = Ui.dp(this, 9);
            lp.rightMargin = Ui.dp(this, 9);
            lp.bottomMargin = Ui.dp(this, 8);
            menuItems.addView(box, lp);

            it.box = box;
            it.bar = bar;
            it.icon = icon;
            it.label = label;
            mNavItems.add(it);
        }

        return rail;
    }

    private void paintNav(NavItem it, boolean active) {
        boolean focused=it.box.hasFocus();
        if(focused){it.bar.setVisibility(View.VISIBLE);it.box.setBackground(Ui.roundStroke(this,Ui.TINT_PRIMARY,6,Ui.PRIMARY,1.5f));it.icon.setColor(Ui.PRIMARY);it.label.setTextColor(Ui.PRIMARY);return;}
        it.bar.setVisibility(active ? View.VISIBLE : View.GONE);
        it.box.setBackgroundColor(Color.TRANSPARENT);
        it.icon.setColor(active ? Ui.PRIMARY : Ui.TEXT_3);
        it.label.setTextColor(active ? Ui.PRIMARY : Ui.TEXT_2);
    }

    // ------------------------------------------------------------ 页面切换

    public void switchTab(int index, boolean force) {
        com.hgtv.app.media.PlaybackWarmup.cancel();
        if (index < 0 || index >= mPages.length) return;
        if (index == mCur && !force) return;
        if (mCur >= 0 && mPages[mCur] != null) mPages[mCur].onHide();
        mCur = index;

        for (NavItem it : mNavItems) paintNav(it, it.index == index);

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
        p.onShow();
        p.bindNav(mNavItems.get(index).box.getId());
    }

    private Page createPage(int index) {
        switch (index) {
            case TAB_SEARCH:
                return new SearchPage();
            case TAB_FILTER:
                return new FilterPage();
            case TAB_HISTORY:
                return new HistoryPage();
            case TAB_FAVORITES:
                return new MinePage(true);
            case TAB_MINE:
                return new MinePage(false);
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

    public Page page(int index) {
        return mPages[index];
    }

    // ------------------------------------------------------------ 详情覆盖层

    public void openDetail(com.hgtv.app.model.Series s) {
        if(!isOverlayOpen()){
            mFocusBeforeOverlay = getCurrentFocus();
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
        mRail.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        mContent.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        mDetail.onEnter();
    }

    public void closeOverlay() {
        if (mOverlay.getVisibility() != View.VISIBLE) return;
        mRail.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        mContent.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        mOverlay.setVisibility(View.GONE);
        mOverlay.removeAllViews();
        if (mFocusBeforeOverlay != null) {
            mFocusBeforeOverlay.requestFocus();
            mFocusBeforeOverlay = null;
        } else {
            Page p = mPages[mCur];
            if (p != null) p.focusFirst();
        }
        if (mDetail != null) mDetail.onLeave();
        if(mRestoreOverlayPosition!=null){Runnable restore=mRestoreOverlayPosition;mRestoreOverlayPosition=null;restore.run();mContent.post(restore);}
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
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT
                : key == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                : key == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP
                : key == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : 0;
        if (direction == 0) return super.dispatchKeyEvent(event);
        View focused = getCurrentFocus();
        if (isOverlayOpen()) {
            View next = focused == null ? null : android.view.FocusFinder.getInstance()
                    .findNextFocus(mOverlay, focused, direction);
            if (next == null || !isDescendant(mOverlay, next)) return true;
            next.requestFocus(direction); return true;
        }
        if (isDescendant(mRail, focused)) {
            if (direction == View.FOCUS_RIGHT) { mPages[mCur].focusFirst(); return true; }
            if (direction == View.FOCUS_LEFT) return true;
            int next = Math.max(0, Math.min(mNavItems.size()-1,
                    mCur + (direction == View.FOCUS_DOWN ? 1 : -1)));
            focusNav(next); return true;
        }
        Page page = mPages[mCur];
        if (page != null && isDescendant(page.root, focused)) {
            View next = android.view.FocusFinder.getInstance().findNextFocus((ViewGroup)page.root, focused, direction);
            if (next != null && isDescendant(page.root, next)) {
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
            switchTab(TAB_MINE, false);
            focusNav(TAB_MINE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed(){if(mRoot==null)finish();else handleBack();}
    private void handleBack(){
        if(isOverlayOpen()){closeOverlay();return;}
        Page page=mPages[mCur];
        if(page!=null && page.onBack())return;
        if(page!=null && page.returnToTop())return;
        if(!isDescendant(mRail,getCurrentFocus())){
            focusNav(mCur);return;
        }
        if(mCur!=TAB_HOME){switchTab(TAB_HOME,false);focusNav(TAB_HOME);return;}
        if(mExitDialog!=null && mExitDialog.isShowing())return;
        mExitDialog=com.hgtv.app.ui.widget.TvDialog.confirm(this,"退出绿果TV？","","退出",this::finish);
    }

    public void setCoverBackground(String url){if(mCoverBackdrop!=null)mCoverBackdrop.showCover(url,false);}
    public void seedCoverBackground(String url){if(mCoverBackdrop!=null)mCoverBackdrop.seed(url);}

    private boolean intentionalRailFocus;
    public void focusNav(int index) {
        if (index >= 0 && index < mNavItems.size()) {
            intentionalRailFocus=true;try{mNavItems.get(index).box.requestFocus();}finally{intentionalRailFocus=false;}
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
        super.onPause();
        // 离开界面时释放不必要的位图，回来会从磁盘/内存 LRU 重新取
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            com.hgtv.app.img.ImageLoader.clearMemory();
        }
    }
}
