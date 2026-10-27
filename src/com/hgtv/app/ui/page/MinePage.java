package com.hgtv.app.ui.page;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hgtv.app.App;
import com.hgtv.app.img.ImageLoader;
import com.hgtv.app.media.VideoCache;
import com.hgtv.app.model.Series;
import com.hgtv.app.store.Store;
import com.hgtv.app.ui.IconView;
import com.hgtv.app.ui.Page;
import com.hgtv.app.ui.Ui;
import com.hgtv.app.ui.widget.CardGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的：收藏 / 喜欢 + 设置。观看历史移至独立的 HistoryPage。
 *
 * 全部本地化（书架接口按设备身份挂，不需要登录），所以这个页面是 0 网络、0 等待的。
 */
public class MinePage extends Page {

    private final boolean favoritesOnly;
    public MinePage(boolean favoritesOnly){this.favoritesOnly=favoritesOnly;}

    private static final String[] TABS = {"追剧"};
    private static final String[] QUALITIES = {"1080p", "720p", "540p", "480p", "360p"};
    private static final String[] QUALITY_LABEL = {
            "1080P", "720P", "540P", "480P", "360P"};

    private ScrollView mScroll;
    private CardGrid mGrid;
    private TextView mEmpty;
    private TextView mStat1;
    private SettingRow mQuality, mCacheInfo, autoUnfollow;
    private LinearLayout mTabsHost;
    private final List<TextView> mTabViews = new ArrayList<>();
    private int mTab = 0;
    private int mNavId;

    @Override
    protected View build() {
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Ui.BG);

        mScroll = new ScrollView(ctx);
        mScroll.setVerticalScrollBarEnabled(false);
        mScroll.setFillViewport(true);
        mScroll.setClipChildren(false);

        LinearLayout col = Ui.column(ctx);
        col.setPadding(Ui.dp(ctx, 32), Ui.dp(ctx, 28), Ui.dp(ctx, 32), Ui.dp(ctx, 44));
        col.setClipChildren(false);
        mScroll.addView(col, new FrameLayout.LayoutParams(-1, -2));
        root.addView(mScroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout heading=Ui.row(ctx);heading.addView(Ui.text(ctx,favoritesOnly?"追剧":"设置",26,Ui.TEXT,true),Ui.lp(0,-2,1));
        if(!favoritesOnly){TextView phone=Ui.action(ctx,"手机选剧",IconView.PHONE,false);phone.setOnClickListener(v->com.hgtv.app.remote.Pairing.show(host));heading.addView(phone,Ui.lp(-2,-2));}col.addView(heading,Ui.lp(-1,-2));

        if(favoritesOnly){
        // 统计条
        LinearLayout stats = Ui.row(ctx);
        LinearLayout.LayoutParams statsLp = Ui.lp(-1, -2);
        statsLp.topMargin = Ui.dp(ctx, 20);
        mStat1 = statCard(stats, "追剧", IconView.STAR);
        col.addView(stats, statsLp);

        // Tab
        mTabsHost = Ui.row(ctx);
        LinearLayout.LayoutParams tabLp = Ui.lp(-1, -2);
        tabLp.topMargin = Ui.dp(ctx, 30);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = Ui.text(ctx, TABS[i], 15, Ui.TEXT_2, false);
            t.setGravity(Gravity.CENTER);
            t.setFocusable(true);
            t.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 10), Ui.dp(ctx, 24), Ui.dp(ctx, 10));
            t.setOnClickListener(v -> switchTab(idx));
            t.setOnFocusChangeListener((v, has) -> {
                Ui.focus(v, has, 1.06f);
                paintTab((TextView) v, idx, has);
            });
            LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
            if (i > 0) lp.leftMargin = Ui.dp(ctx, 10);
            mTabsHost.addView(t, lp);
            mTabViews.add(t);
        }
        mTabsHost.setVisibility(View.GONE);col.addView(mTabsHost, tabLp);

        mEmpty = Ui.text(ctx, "", 14, Ui.TEXT_4, false);
        LinearLayout.LayoutParams emptyLp = Ui.lp(-1, -2);
        emptyLp.topMargin = Ui.dp(ctx, 20);
        col.addView(mEmpty, emptyLp);

        mGrid = new CardGrid(ctx, 7, 112, 160, 12);
        mGrid.setLeftAligned(true);
        mGrid.setOnPick(s -> host.openDetail(s));
        mGrid.setOnBindCard((card,s)->card.setOnLongClickListener(v->{com.hgtv.app.ui.widget.TvDialog.confirm(host,s.title,"不再追这部剧？观看历史会保留。","取消追剧",()->{Store.remove("fav",s.seriesId);onShow();focusFirst();});return true;}));
        LinearLayout.LayoutParams gridLp = Ui.lp(-1, -2);
        gridLp.topMargin = Ui.dp(ctx, 18);
        col.addView(mGrid, gridLp);

        }
        if(!favoritesOnly){
        mQuality = settingRow(col, "清晰度", "720P");
        mQuality.setOnClickListener(v -> {
            int cur = 0;
            String q = App.prefs().getString("quality", "720p");
            for (int i = 0; i < QUALITIES.length; i++) {
                if (QUALITIES[i] == null ? q == null : QUALITIES[i].equals(q)) cur = i;
            }
            cur = (cur + 1) % QUALITIES.length;
            if (QUALITIES[cur] == null) App.prefs().edit().remove("quality").apply();
            else App.prefs().edit().putString("quality", QUALITIES[cur]).apply();
            mQuality.setValue(QUALITY_LABEL[cur]);
            host.toast("清晰度：" + QUALITY_LABEL[cur]);
        });

        autoUnfollow=settingRow(col,"看完后自动取消追剧",Store.autoUnfollow()?"开启":"关闭");
        autoUnfollow.setOnClickListener(v->{Store.autoUnfollow(!Store.autoUnfollow());autoUnfollow.setValue(Store.autoUnfollow()?"开启":"关闭");});
        mCacheInfo = settingRow(col, "清理缓存", "正在统计缓存…");
        mCacheInfo.setOnClickListener(v -> {
            App.io().execute(() -> {
                ImageLoader.clearDisk();
                VideoCache.clear(App.ctx());
                final String stat = ImageLoader.stat();
                host.runOnUiThread(() -> {
                    mCacheInfo.setValue("已清理");
                    host.toast("缓存已清理");
                    refreshCacheInfo();
                });
            });
        });

        String version="";try{version=ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),0).versionName;}catch(Exception ignored){}
        TextView about=Ui.text(ctx,"绿果TV · "+version.split("-")[0],13,Ui.TEXT_3,false);Ui.padding(about,18,28,18,0);col.addView(about,Ui.lp(-1,-2));

        }
        return root;
    }

    private TextView statCard(LinearLayout parent, String label, int icon) {
        LinearLayout card = Ui.row(ctx);
        card.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 14), Ui.dp(ctx, 18), Ui.dp(ctx, 14));
        card.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        IconView ic = new IconView(ctx, icon, 15);
        ic.setColor(Ui.PRIMARY_DIM);
        card.addView(ic);
        TextView num = Ui.text(ctx, "0", 19, Ui.TEXT, true);
        LinearLayout.LayoutParams nlp = Ui.lp(-2, -2);
        nlp.leftMargin = Ui.dp(ctx, 12);
        card.addView(num, nlp);
        TextView lb = Ui.text(ctx, label, 12, Ui.TEXT_3, false);
        LinearLayout.LayoutParams llp = Ui.lp(-2, -2);
        llp.leftMargin = Ui.dp(ctx, 6);
        card.addView(lb, llp);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.rightMargin = Ui.dp(ctx, 12);
        parent.addView(card, lp);
        return num;
    }

    private class SettingRow extends LinearLayout {
        final TextView value;
        SettingRow(String title,String initial){super(ctx);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setFocusable(true);setId(View.generateViewId());Ui.padding(this,18,16,18,16);
            addView(Ui.text(ctx,title,16,Ui.TEXT,false),Ui.lp(0,-2,1));value=Ui.text(ctx,initial,14,Ui.TEXT_2,false);value.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);addView(value,Ui.lp(-2,-2));
            setBackground(Ui.roundStroke(ctx,0x0AFFFFFF,8,0x18FFFFFF,1));setOnFocusChangeListener((v,focus)->setBackground(Ui.roundStroke(ctx,focus?Ui.TINT_PRIMARY:0x0AFFFFFF,8,focus?Ui.PRIMARY:0x18FFFFFF,1)));
        }
        void setValue(String text){value.setText(text);}
    }
    private SettingRow settingRow(LinearLayout parent,String title,String value){SettingRow row=new SettingRow(title,value);LinearLayout.LayoutParams lp=Ui.lp(-1,-2);lp.topMargin=Ui.dp(ctx,14);parent.addView(row,lp);return row;}

    private void paintTab(TextView v, int idx, boolean focused) {
        boolean on = mTab == idx;
        Ui.buttonState(v,focused,on);
        v.setTextColor(on ? Ui.PRIMARY : (focused ? Ui.TEXT : Ui.TEXT_2));
    }

    private void switchTab(int idx) {
        mTab = idx;
        for (int i = 0; i < mTabViews.size(); i++) {
            paintTab(mTabViews.get(i), i, mTabViews.get(i).hasFocus());
        }
        render();
    }

    // ------------------------------------------------------------ 数据

    @Override
    public void onShow() {
        if(favoritesOnly){mStat1.setText(Store.favorites().size()+" / "+Store.FAVORITE_LIMIT);render();
            com.hgtv.app.ui.widget.FavoriteActions.offerCleanup(host,()->{mStat1.setText(Store.favorites().size()+" / "+Store.FAVORITE_LIMIT);render();});return;}
        int fav = Store.favorites().size();
        if(mStat1!=null)mStat1.setText(String.valueOf(fav));

        String q = App.prefs().getString("quality", "720p");
        int qi = 0;
        for (int i = 0; i < QUALITIES.length; i++) {
            if (QUALITIES[i] == null ? q == null : QUALITIES[i].equals(q)) qi = i;
        }
        mQuality.setValue(QUALITY_LABEL[qi]);
        autoUnfollow.setValue(Store.autoUnfollow()?"开启":"关闭");
        refreshCacheInfo();

        for (int i = 0; i < mTabViews.size(); i++) paintTab(mTabViews.get(i), i, mTabViews.get(i).hasFocus());
    }

    private void refreshCacheInfo() {
        App.io().execute(() -> {
            long v = VideoCache.usedBytes(App.ctx());
            String img = ImageLoader.stat();
            final String s = String.format(java.util.Locale.US, "视频缓存 %.0f MB · %s",
                    v / 1024.0 / 1024.0, img);
            host.runOnUiThread(() -> mCacheInfo.setValue(s));
        });
    }

    private void render() {
        List<Store.Item> items = mTab == 0 ? Store.favorites() : Store.likes();
        List<Series> list = new ArrayList<>();
        for (Store.Item it : items) {
            Series s = new Series();
            s.seriesId = it.seriesId;
            s.title = it.title;
            s.cover = Store.bestCover(it.seriesId, it.cover);
            list.add(s);
        }
        if (list.isEmpty()) {
            mEmpty.setVisibility(View.VISIBLE);
            mEmpty.setText(mTab == 0 ? "还没有追剧，在详情里点击「追剧」即可加入。"
                    : "还没有喜欢的短剧，看到心动的就点个喜欢吧。");
        } else {
            mEmpty.setVisibility(View.GONE);
        }
        mGrid.setData(list);
        repairCovers(items);
    }

    private final java.util.Set<String> mRepairing = new java.util.HashSet<>();

    private void repairCovers(List<Store.Item> items) {
        int count = 0;
        for (Store.Item item : items) {
            if (Store.usableCover(Store.bestCover(item.seriesId, item.cover))
                    || item.seriesId == null || mRepairing.contains(item.seriesId)) continue;
            if (++count > 6) break;
            final String id = item.seriesId;
            mRepairing.add(id);
            App.io().execute(() -> {
                try {
                    com.hgtv.app.net.Repo.Detail detail = com.hgtv.app.net.Repo.detail(id);
                    for (com.hgtv.app.model.Episode ep : detail.episodes) {
                        if (Store.usableCover(ep.cover)) {
                            Store.updateCover(id, ep.cover);
                            host.runOnUiThread(() -> {
                                if (root != null && root.isShown()) render();
                            });
                            break;
                        }
                    }
                } catch (Throwable ignored) { /* 离线时仍展示空封面，不影响已存数据。 */ }
            });
        }
    }

    @Override
    public void focusFirst() {
        if(mGrid!=null&&mGrid.count()>0)mGrid.focusFirst();else if(mQuality!=null)mQuality.requestFocus();
    }

    @Override
    public void bindNav(int navViewId) {
        mNavId = navViewId;
        if (!mTabViews.isEmpty()) mTabViews.get(0).setNextFocusLeftId(navViewId);
        if (mGrid != null) mGrid.setLeftNavId(navViewId);
        if(mQuality!=null)mQuality.setNextFocusLeftId(navViewId);
    }
}
