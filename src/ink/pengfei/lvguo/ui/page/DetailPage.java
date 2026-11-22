package ink.pengfei.lvguo.ui.page;
import android.widget.HorizontalScrollView;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.PlayerActivity;
import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.model.Episode;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.net.Repo;
import ink.pengfei.lvguo.store.Store;
import ink.pengfei.lvguo.ui.IconView;
import ink.pengfei.lvguo.ui.Page;
import ink.pengfei.lvguo.ui.Ui;
import ink.pengfei.lvguo.ui.widget.FocusScrollView;

/**
 * 详情页（全屏覆盖层）：海报 + 简介 + 收藏/喜欢 + 选集网格。
 *
 * 选集是短剧的核心操作，所以直接铺成网格（每行 8 个），
 * 焦点上下移动即可，不需要额外的"换页"动作。
 *
 * 数据来源：album_detail 旁路（2026-10-21 实测免签可用）——
 * 一次请求同时给出介绍/评分/热度/免费标识/演员/多季信息/完整选集。
 * 只有网络失败时才降级：用榜单带出的数据（标题/封面/集数/第 1 集 vid）渲染，
 * 保证"能看"——从第 1 集播放。
 */
public class DetailPage extends Page {

    private static final int COLS = 8;

    private String mSeriesId;
    private String mFallbackTitle;
    private String mFallbackCover;
    private Series mSeries;
    private Repo.Detail mDetail;
    private boolean mLoading;
    private int loadGeneration;
    private ink.pengfei.lvguo.ui.widget.SkeletonView infoSkeleton;
    /** true = 详情接口失败，当前是降级渲染 */
    private boolean mDegraded;

    private ScrollView mScroll;
    private LinearLayout mRangeHost;
    private boolean compactHeader;
    private ImageView mPoster;
    private TextView mTitle, mMeta, mIntro, mActors;
    private TextView mFavBtn, mLikeBtn, mPlayBtn, mCommentBtn, mReserveBtn;
    private boolean mReserved, mReserveBusy;
    private TextView mSeasonLabel;
    private HorizontalScrollView mSeasonScroll;
    private LinearLayout mSeasonTabs;
    private LinearLayout mEpisodeHeader;
    private TextView mHotTag;
    private LinearLayout mEpisodeHost;
    private TextView mEpHint;
    private LinearLayout mActions;
    private View mDegradedCard;
    private String mSeasonRequestAlbum;
    private boolean mSeasonRequestPending, mSeasonRequestResolved;

    private int mContinueIndex = 0;
    private ink.pengfei.lvguo.ui.widget.CoverBackdrop detailBackdrop;
    private int mNavId;
    private final java.util.Map<String,java.util.List<Repo.Season>> knownSeasonLists=new java.util.HashMap<>();

    @Override
    protected View build() {
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Ui.BG);
        detailBackdrop=new ink.pengfei.lvguo.ui.widget.CoverBackdrop(ctx);root.addView(detailBackdrop,new FrameLayout.LayoutParams(-1,-1));

        int heightDp=(int)(ctx.getResources().getDisplayMetrics().heightPixels/ctx.getResources().getDisplayMetrics().density);
        compactHeader=heightDp<480;
        LinearLayout col = Ui.column(ctx);
        col.setClipChildren(true);
        col.setPadding(0, Ui.dp(ctx, compactHeader?16:24), 0, Ui.dp(ctx, 12));
        mScroll=new FocusScrollView(ctx);mScroll.setVerticalScrollBarEnabled(false);mScroll.setFillViewport(true);mScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mScroll.addView(col,new ScrollView.LayoutParams(-1,-2));root.addView(mScroll,new FrameLayout.LayoutParams(-1,-1));

        // ---- 头部
        LinearLayout head = Ui.row(ctx);
        head.setClipChildren(false);head.setClipToPadding(false);
        head.setGravity(Gravity.TOP);
        head.setPadding(Ui.dp(ctx, 48), Ui.dp(ctx, 8), Ui.dp(ctx, 48), 0);

        mPoster = new ImageView(ctx);
        mPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mPoster.setBackground(Ui.round(ctx, Ui.SURFACE, 14));
        mPoster.setClipToOutline(true);
        mPoster.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(ctx, 14));
            }
        });
        int posterHeight=Math.max(80,Math.min(180,(int)(heightDp*.30f)));
        // 用外层容器裁圆角（与海报卡一致），软件渲染的盒子上也能保证圆角。
        FrameLayout posterFrame = new ink.pengfei.lvguo.ui.widget.RoundFrame(ctx, 16);
        posterFrame.setBackground(Ui.round(ctx, Ui.SURFACE, 16));
        posterFrame.addView(mPoster, new FrameLayout.LayoutParams(-1, -1));
        head.addView(posterFrame, Ui.lp(Ui.dp(ctx, Math.round(posterHeight*.727f)), Ui.dp(ctx, posterHeight)));

        LinearLayout info = Ui.column(ctx);
        info.setClipChildren(false);info.setClipToPadding(false);
        info.setPadding(Ui.dp(ctx, 20), 0, 0, 0);

        mHotTag = Ui.text(ctx, "", 11, Ui.PRIMARY, true);
        mHotTag.setBackground(Ui.pill(ctx, Ui.TINT_PRIMARY));
        mHotTag.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 4));
        mHotTag.setVisibility(View.GONE);

        // 「新剧/热播」标签跟在剧名后面，不再单独占一行（否则下方选集被挤出屏幕）
        LinearLayout titleRow = Ui.row(ctx);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        mTitle = Ui.text(ctx, "", compactHeader?20:23, Ui.TEXT, true);
        mTitle.setMaxLines(1);
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(mTitle, Ui.lp(-2, -2, 1));   // 宽度随文字，太长时先压缩剧名
        LinearLayout.LayoutParams hotTagLp = Ui.lp(-2, -2);
        hotTagLp.leftMargin = Ui.dp(ctx, 12);
        titleRow.addView(mHotTag, hotTagLp);
        info.addView(titleRow, Ui.lp(-2, -2));

        infoSkeleton=new ink.pengfei.lvguo.ui.widget.SkeletonView(ctx,1,1,0,1);
        info.addView(infoSkeleton,Ui.lp(-1,Ui.dp(ctx,compactHeader?32:72)));
        mMeta = Ui.text(ctx, "", 13, Ui.TEXT_3, false);
        LinearLayout.LayoutParams metaLp = Ui.lp(-1, -2);
        metaLp.topMargin = Ui.dp(ctx, 6);
        mMeta.setMaxLines(1);mMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(mMeta, metaLp);

        mIntro = Ui.ellipsis(ctx, "", 14, Ui.TEXT_2, 2);
        LinearLayout.LayoutParams introLp = Ui.lp(-1, -2);
        introLp.topMargin = Ui.dp(ctx, compactHeader?6:8);
        info.addView(mIntro, introLp);

        mActors = Ui.text(ctx, "", 13, Ui.TEXT_3, false);
        mActors.setMaxLines(1);
        mActors.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams actorsLp = Ui.lp(-1, -2);
        actorsLp.topMargin = Ui.dp(ctx, 6);
        info.addView(mActors, actorsLp);

        // 操作按钮
        mActions = Ui.row(ctx);
        mActions.setClipChildren(false);mActions.setClipToPadding(false);
        LinearLayout.LayoutParams actLp = Ui.lp(-1, -2);
        actLp.topMargin = Ui.dp(ctx, compactHeader?6:10);

        mPlayBtn = Ui.action(ctx, "开始播放", IconView.PLAY, false);
        mPlayBtn.setTextSize(15);
        mPlayBtn.setGravity(Gravity.CENTER);
        mPlayBtn.setFocusable(true);
        mPlayBtn.setFocusableInTouchMode(true);
        mPlayBtn.setIncludeFontPadding(false);
        mPlayBtn.setTextColor(Ui.TEXT);
        Ui.buttonState(mPlayBtn,false,false);
        mPlayBtn.setOnClickListener(v -> play(mContinueIndex,true));
        mActions.addView(mPlayBtn, Ui.lp(Ui.dp(ctx, 136), Ui.dp(ctx, compactHeader?40:46)));

        // 预约按钮：仅未上线的剧/季出现（替代「开始播放」），主动预约追更入口
        mReserveBtn = Ui.action(ctx, "预约", IconView.STAR, false);
        mReserveBtn.setTextSize(15);
        mReserveBtn.setGravity(Gravity.CENTER);
        mReserveBtn.setFocusable(true);
        mReserveBtn.setFocusableInTouchMode(true);
        mReserveBtn.setIncludeFontPadding(false);
        mReserveBtn.setTextColor(Ui.TEXT);
        Ui.buttonState(mReserveBtn,false,false);
        mReserveBtn.setVisibility(View.GONE);
        mReserveBtn.setOnClickListener(v -> toggleReserve());
        mActions.addView(mReserveBtn, Ui.lp(Ui.dp(ctx, 176), Ui.dp(ctx, compactHeader?40:46)));

        mFavBtn = iconButton("追剧", IconView.STAR);
        mFavBtn.setOnClickListener(v -> {
            if (mSeriesId == null) return;
            ink.pengfei.lvguo.ui.widget.FavoriteActions.toggle(host,mSeriesId,displayTitle(),usableCover(),firstVid(),()->{
                refreshToggles();host.toast(Store.isFavorite(mSeriesId)?"已加入追剧":"已取消追剧");
            });
        });
        LinearLayout.LayoutParams favLp = Ui.lp(-2, Ui.dp(ctx, compactHeader?40:52));
        favLp.leftMargin = Ui.dp(ctx, 14);
        mActions.addView(mFavBtn, favLp);

        TextView comments=iconButton("剧评",IconView.COMMENT);mCommentBtn=comments;
        comments.setOnClickListener(v->{if(mSeriesId!=null)ink.pengfei.lvguo.ui.widget.CommentsDialog.show(host,mSeriesId);});
        LinearLayout.LayoutParams commentLp=Ui.lp(-2,Ui.dp(ctx,compactHeader?40:52));commentLp.leftMargin=Ui.dp(ctx,10);mActions.addView(comments,commentLp);
        info.addView(mActions, actLp);
        head.addView(info, Ui.lp(0, -2, 1f));
        col.addView(head, Ui.lp(-1, -2));

        mSeasonLabel=Ui.text(ctx,"选择季度",12,Ui.TEXT_3,false);Ui.padding(mSeasonLabel,48,18,48,8);mSeasonLabel.setVisibility(View.GONE);col.addView(mSeasonLabel,Ui.lp(-1,-2));
        mSeasonScroll=new HorizontalScrollView(ctx);
        mSeasonScroll.setHorizontalScrollBarEnabled(false);
        mSeasonScroll.setFillViewport(true);
        mSeasonScroll.setVisibility(View.GONE);
        mSeasonTabs=Ui.row(ctx);mSeasonScroll.addView(mSeasonTabs);
        LinearLayout.LayoutParams seasonsLp=Ui.lp(-1,-2);
        seasonsLp.setMargins(Ui.dp(ctx, 48),Ui.dp(ctx,compactHeader?6:10),Ui.dp(ctx, 48),Ui.dp(ctx,compactHeader?8:14));
        col.addView(mSeasonScroll,seasonsLp);

        // ---- 选集
        mEpisodeHeader = Ui.row(ctx);
        mEpisodeHeader.setPadding(Ui.dp(ctx, 48), Ui.dp(ctx, 24), Ui.dp(ctx, 48), 0);
        View ac = new View(ctx);
        ac.setVisibility(View.GONE);  // 分区标题统一不加色条
        mEpisodeHeader.addView(ac, Ui.lp(Ui.dp(ctx, 4), Ui.dp(ctx, 18)));
        TextView epTitle = Ui.text(ctx, "选集", 21, Ui.TEXT, true);
        LinearLayout.LayoutParams etLp = Ui.lp(-2, -2);
        etLp.leftMargin = Ui.dp(ctx, 12);
        mEpisodeHeader.addView(epTitle, etLp);
        mEpHint = Ui.text(ctx, "", 12, Ui.TEXT_3, false);
        LinearLayout.LayoutParams ehLp = Ui.lp(-2, -2);
        ehLp.leftMargin = Ui.dp(ctx, 10);
        mEpisodeHeader.addView(mEpHint, ehLp);
        col.addView(mEpisodeHeader, Ui.lp(-1, -2));mEpisodeHeader.setVisibility(View.GONE);

        mRangeHost=Ui.column(ctx);mRangeHost.setPadding(Ui.dp(ctx, 48),Ui.dp(ctx,compactHeader?8:14),Ui.dp(ctx, 48),Ui.dp(ctx,compactHeader?4:10));
        col.addView(mRangeHost,Ui.lp(-1,-2));
        mEpisodeHost = Ui.column(ctx);
        mEpisodeHost.setPadding(Ui.dp(ctx, 48),Ui.dp(ctx,4),Ui.dp(ctx, 48),Ui.dp(ctx,24));
        col.addView(mEpisodeHost,Ui.lp(-1,-2));

        return root;
    }

    /** 降级提示卡：剧集列表不可用时显示，带重试按钮 */
    private View buildDegradedCard() {
        LinearLayout card = Ui.column(ctx);
        card.setBackground(Ui.roundStroke(ctx, Ui.FILL_2, 16, 0x00000000, 1f));
        card.setPadding(Ui.dp(ctx, 26), Ui.dp(ctx, 22), Ui.dp(ctx, 26), Ui.dp(ctx, 22));

        TextView t1 = Ui.text(ctx, "详情暂时未能加载", 16, Ui.TEXT, true);
        card.addView(t1, Ui.lp(-1, -2));
        TextView t2 = Ui.text(ctx,
                "请检查网络后重试。",
                13, Ui.TEXT_3, false);
        LinearLayout.LayoutParams lp2 = Ui.lp(-1, -2);
        lp2.topMargin = Ui.dp(ctx, 8);
        card.addView(t2, lp2);

        TextView retry = new TextView(ctx);
        retry.setText("重新加载");
        retry.setTextSize(13);
        retry.setGravity(Gravity.CENTER);
        retry.setFocusable(true);
        retry.setIncludeFontPadding(false);
        retry.setTextColor(Ui.TEXT);
        Ui.tile(retry, false, true, 100);
        retry.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 10), Ui.dp(ctx, 22), Ui.dp(ctx, 10));
        retry.setOnFocusChangeListener((v, has) -> {
            Ui.tile(retry, has, true, 100);
        });
        retry.setOnClickListener(v -> {
            mDegraded = false;
            mDetail = null;
            load(true);
        });
        LinearLayout.LayoutParams rlp = Ui.lp(-2, -2);
        rlp.topMargin = Ui.dp(ctx, 16);
        card.addView(retry, rlp);
        return card;
    }

    private TextView iconButton(String label,int icon){
        TextView button=Ui.action(ctx,label,icon,false);
        button.setOnFocusChangeListener((v,has)->paintToggle(v,has));return button;
    }
    /** 已追剧：未聚焦时主题色文字 + 实心星；聚焦时用聚焦配色（不再主题色叠主题色导致看不见），星仍是实心。 */
    private void paintToggle(View view,boolean focused){
        TextView b=(TextView)view;
        boolean on=view==mFavBtn && Store.isFavorite(mSeriesId);
        Ui.buttonState(b,focused,false);
        if(on && !focused)b.setTextColor(Ui.ACCENT_TEXT);
        if(view==mFavBtn){
            Ui.icon(b,IconView.STAR,b.getCurrentTextColor(),18);
            if(b.getCompoundDrawables()[0] instanceof ink.pengfei.lvguo.ui.IconDrawable)((ink.pengfei.lvguo.ui.IconDrawable)b.getCompoundDrawables()[0]).setFilled(on);
        }
    }

    /** 完整数据入口（首页/筛选/搜索/我的 都传 Series 对象，带第 1 集 vid） */
    public void setSeries(Series s) {
        boolean same = s != null && s.seriesId != null && s.seriesId.equals(mSeriesId);
        mSeries = s;
        mSeriesId = s == null ? null : s.seriesId;
        mFallbackTitle = s == null ? null : s.title;
        mFallbackCover = s == null ? null : s.cover;
        resetView();
    }

    /** 兼容旧签名：只有 id/标题/封面时也能开 */
    public void setSeries(String seriesId, String title, String cover) {
        Series s = new Series();
        s.seriesId = seriesId;
        s.title = title;
        s.cover = cover;
        setSeries(s);
    }

    private String usableCover() {
        String c = Store.bestCover(mSeriesId, mFallbackCover);
        if (Store.usableCover(c)) return c;
        if (mDetail != null && !mDetail.episodes.isEmpty()) {
            String episodeCover = mDetail.episodes.get(0).cover;
            if (Store.usableCover(episodeCover)) return episodeCover;
        }
        return null;
    }

    private void resetView() {
        loadGeneration++;
        mDetail = null;
        mDegraded = false;
        mLoading = false;
        mSeasonRequestAlbum = null;
        mSeasonRequestPending = false;
        mSeasonRequestResolved = false;
        mContinueIndex = 0;
        mTitle.setText(mFallbackTitle == null ? "" : mFallbackTitle);
        mPlayBtn.setText("开始播放");
        mIntro.setText("");
        mIntro.setVisibility(View.VISIBLE);
        mActors.setText("");
        mActors.setVisibility(View.GONE);
        mMeta.setText("");
        mHotTag.setVisibility(View.GONE);
        mHotTag.setText("");
        if (mSeasonScroll != null) {
            mSeasonScroll.setVisibility(View.GONE);
            mSeasonTabs.removeAllViews();
            setEpisodeHeaderSpacing(false);
        }
        mEpisodeHost.removeAllViews();
        mRangeHost.removeAllViews();
        if (mDegradedCard != null) {
            mEpisodeHost.removeView(mDegradedCard);
            mDegradedCard = null;
        }
        mEpHint.setText("");
        mPoster.setImageDrawable(null);
        String cover = usableCover();
        if (cover != null) {
            host.setCoverBackground(cover);detailBackdrop.showCover(cover,true);
            ImageLoader.load(cover, mPoster, Ui.dp(ctx, 192), Ui.dp(ctx, 264));
            Store.updateCover(mSeriesId, cover);
        }
        refreshToggles();
    }

    public void onEnter() {
        mScroll.scrollTo(0, 0);
        load(false);
        mPlayBtn.post(() -> {mPlayBtn.requestFocus();mScroll.scrollTo(0,0);});
    }

    public void onLeave() {
        // 不主动清海报：返回首页立刻再进同一部剧时能秒开
    }

    private void load(boolean force) {
        if (mSeriesId == null || mLoading) return;
        if (mDetail != null && !force) {
            render();
            return;
        }
        mLoading = true;
        final int token=++loadGeneration;final String id=mSeriesId;
        infoSkeleton.setVisibility(View.VISIBLE);mMeta.setVisibility(View.GONE);mIntro.setVisibility(View.GONE);mActors.setVisibility(View.GONE);
        showEpisodeSkeleton();
        mEpHint.setText("加载中…");
        App.io().execute(() -> {
            Repo.Detail d = null;
            String err = null;
            try {
                d = force ? Repo.retryDetail(id) : Repo.detail(id);
            } catch (Throwable e) {
                err = e.getMessage();
            }
            final Repo.Detail dd = d;
            final String ee = err;
            host.runOnUiThread(() -> {
                if(token!=loadGeneration)return;
                infoSkeleton.setVisibility(View.GONE);mMeta.setVisibility(View.VISIBLE);
                mLoading = false;
                if (dd != null) {
                    mDetail = dd;
                    mDegraded = false;
                    render();
                } else {
                    // 详情拿不到 → 降级渲染（不挡用户观看）
                    mDegraded = true;
                    renderDegraded(ee);
                }
            });
        });
    }

    private String displayTitle() {
        if (mDetail != null && mDetail.title != null && !mDetail.title.isEmpty()) {
            return mDetail.title;
        }
        return mFallbackTitle;
    }

    /** 当前可用的第 1 集 vid：优先榜单带出，其次收藏/历史里留存的 */
    private String firstVid() {
        if (mSeries != null && mSeries.firstVid != null && !mSeries.firstVid.isEmpty()) {
            return mSeries.firstVid;
        }
        for (Store.Item it : Store.history()) {
            if (mSeriesId != null && mSeriesId.equals(it.seriesId)
                    && it.vid != null && !it.vid.isEmpty()) {
                return it.vid;
            }
        }
        Store.Item favorite = historyItem();
        if (favorite != null && favorite.vid != null && !favorite.vid.isEmpty()) return favorite.vid;
        return null;
    }

    private Store.Item historyItem() {
        Store.Item history = null;
        for (Store.Item it : Store.history()) {
            if (mSeriesId != null && mSeriesId.equals(it.seriesId)) { history = it; break; }
        }
        Store.Item shelfProgress = null;
        for (Store.Item it : Store.favorites()) {
            if (mSeriesId != null && mSeriesId.equals(it.seriesId) && it.progressTime > 0
                    && (shelfProgress == null || it.progressTime > shelfProgress.progressTime)) shelfProgress = it;
        }
        return shelfProgress != null && (history == null || shelfProgress.progressTime > history.time)
                ? shelfProgress : history;
    }

    // ------------------------------------------------------------ 渲染：完整模式

    private void render() {
        if (mDetail == null) return;
        String cover = usableCover();
        if (cover != null) {
            host.setCoverBackground(cover);detailBackdrop.showCover(cover,true);
            ImageLoader.load(cover, mPoster, Ui.dp(ctx, 192), Ui.dp(ctx, 264));
            Store.updateCover(mSeriesId, cover);
        }
        if (mDegradedCard != null) {
            mEpisodeHost.removeView(mDegradedCard);
            mDegradedCard = null;
        }
        mTitle.setText(displayTitle());
        renderIntro();

        // 演员（intro 下方一行，没有则隐藏）
        if (!compactHeader && !mDetail.actors.isEmpty()) {
            StringBuilder ab = new StringBuilder("主演：");
            for (int i = 0; i < mDetail.actors.size(); i++) {
                if (i > 0) ab.append(" / ");
                ab.append(mDetail.actors.get(i));
            }
            mActors.setText(ab);
            mActors.setVisibility(View.VISIBLE);
        } else {
            mActors.setVisibility(View.GONE);
        }

        // 运营标签（爆剧/热播）
        if (!compactHeader && mDetail.hotTag != null && !mDetail.hotTag.isEmpty()) {
            mHotTag.setText(mDetail.hotTag);
            mHotTag.setVisibility(View.VISIBLE);
        } else {
            mHotTag.setVisibility(View.GONE);
        }

        boolean seasonsLoading = loadSeasons();

        StringBuilder meta = new StringBuilder();
        int cnt = mDetail.episodes.size();
        if (!seasonsLoading && cnt > 0) meta.append("全 ").append(cnt).append(" 集");
        if (!seasonsLoading && mDetail.seasonIndex > 0) {
            if (meta.length() > 0) meta.append("   ·   ");
            meta.append("第 ").append(mDetail.seasonIndex).append(" 季");
        } else if (!seasonsLoading && mDetail.multiSeason) {
            if (meta.length() > 0) meta.append("   ·   ");
            meta.append("多季");
        }
        if (mDetail.rating != null && !mDetail.rating.isEmpty()) {
            if (meta.length() > 0) meta.append("   ·   ");
            meta.append(mDetail.rating);
        }
        if (mDetail.heatText != null && !mDetail.heatText.isEmpty()) {
            if (meta.length() > 0) meta.append("   ·   ");
            meta.append("热度 ").append(mDetail.heatText);
        }
        if (mDetail.payType == 0) {
            if (meta.length() > 0) meta.append("   ·   ");
            meta.append("免费");
        } else if (mDetail.payType > 0) {
            if (meta.length() > 0) meta.append("   ·   ");
            meta.append("付费");
        }
        mMeta.setText(meta);

        // 历史页数据有延迟时，收藏页按 App 的 read_progress/get 回读进度作为续播补充。
        Store.Item progress = historyItem();
        if (progress != null) mContinueIndex = Math.max(0, Math.min(cnt - 1, progress.episodeIndex));
        Episode cur = cnt > 0 ? mDetail.episodes.get(Math.min(mContinueIndex, cnt - 1)) : null;
        mPlayBtn.setText(mContinueIndex > 0
                ? "继续播放"
                : "开始播放");

        mEpHint.setText(seasonsLoading ? "" : cnt>0?(mContinueIndex>0?"已看 "+(cur==null?mContinueIndex+1:cur.index)+"/"+cnt+" 集":"共 "+cnt+" 集"):"");
        if (!seasonsLoading) buildEpisodes();
        refreshToggles();
        updateReserveState();
    }

    // ------------------------------------------------------------ 渲染：降级模式

    private void renderIntro() {
        String intro=mDetail==null?null:mDetail.intro;
        boolean available=intro!=null&&!intro.trim().isEmpty();
        mIntro.setText(available?intro.replace("\n", " "):"");
        mIntro.setVisibility(available?View.VISIBLE:View.GONE);
    }

    private void renderDegraded(String err) {
        android.util.Log.w("DetailPage","Detail unavailable: "+mSeriesId+" : "+err);mRangeHost.removeAllViews();mRangeHost.setVisibility(View.GONE);mSeasonTabs.removeAllViews();mSeasonScroll.setVisibility(View.GONE);setEpisodeHeaderSpacing(false);
        mTitle.setText(displayTitle());
        renderIntro();

        StringBuilder meta = new StringBuilder();
        int cnt = mDetail != null ? mDetail.episodes.size() : 0;
        if (cnt <= 0 && mSeries != null) cnt = mSeries.episodeCount;
        if (cnt > 0) meta.append("全 ").append(cnt).append(" 集");
        if (mSeries != null && mSeries.durationSec > 0) {
            int min = Math.round(mSeries.durationSec / 60f);
            if (min > 0) {
                if (meta.length() > 0) meta.append("   ·   ");
                meta.append("约 ").append(min).append(" 分钟");
            }
        }
        meta.append("   ·   短剧");
        mMeta.setText(meta);

        // 播放按钮文案：有历史且带 vid（第 2 集起）→ 继续播放第 N 集；否则从第 1 集
        Store.Item his = historyItem();
        boolean canResume = his != null && his.vid != null && !his.vid.isEmpty()
                && his.lastEpisode > 1;
        mPlayBtn.setText(canResume ? "继续播放" : "开始播放");

        mEpHint.setText("暂不可用");
        mEpisodeHost.removeAllViews();
        mDegradedCard = buildDegradedCard();
        mEpisodeHost.addView(mDegradedCard, Ui.lp(-1, -2));
        refreshToggles();
        updateReserveState();
    }

    /** 未上线的剧/季 → 显示「预约」替代「开始播放」；上线的隐藏预约、显示播放。 */
    private void updateReserveState() {
        boolean online = mDetail != null && !mDetail.episodes.isEmpty();
        if (mReserveBtn == null || mPlayBtn == null) return;
        mPlayBtn.setVisibility(online ? View.VISIBLE : View.GONE);
        mReserveBtn.setVisibility(online ? View.GONE : View.VISIBLE);
        if (!online) {
            applyReserveLabel();
            if (!mReserveBtn.hasFocus() && !mPlayBtn.hasFocus()) mReserveBtn.post(mReserveBtn::requestFocus);
            // 后台回读是否已预约（只有未上线时才查，不拖慢正常详情）
            final String sid = mSeriesId;
            ink.pengfei.lvguo.App.pool().execute(() -> {
                boolean reserved = false;
                try {
                    for (ink.pengfei.lvguo.net.Account.Entry e : ink.pengfei.lvguo.net.Account.reservations(false).items)
                        if (sid != null && sid.equals(e.seriesId)) { reserved = true; break; }
                } catch (Exception ignored) {}
                final boolean r = reserved;
                host.runOnUiThread(() -> { if (sid != null && sid.equals(mSeriesId)) { mReserved = r; applyReserveLabel(); } });
            });
        }
    }
    private void applyReserveLabel() {
        if (mReserveBtn != null) mReserveBtn.setText(mReserved ? "已预约" : "预约");
    }
    private void toggleReserve() {
        if (mSeriesId == null || mReserveBusy) return;
        if (!ink.pengfei.lvguo.net.Session.loggedIn()) { host.toast("登录后可预约上线提醒"); return; }
        if (mReserved) {   // 取消预约必须二次确认，防误触
            ink.pengfei.lvguo.ui.widget.TvDialog.confirm(host, "取消预约？", "《" + displayTitle() + "》上线后将不再提醒。", "取消预约", () -> applyReserve(false));
            return;
        }
        applyReserve(true);
    }
    private void applyReserve(final boolean add) {
        if (mSeriesId == null || mReserveBusy) return;
        mReserveBusy = true;
        final String sid = mSeriesId;
        ink.pengfei.lvguo.App.pool().execute(() -> {
            String err = null;
            try { ink.pengfei.lvguo.net.Account.setReservation(sid, add); } catch (Exception e) { err = e.getMessage(); }
            final String error = err;
            host.runOnUiThread(() -> {
                mReserveBusy = false;
                if (!sid.equals(mSeriesId)) return;
                if (error != null) { host.toast(error); return; }
                mReserved = add; applyReserveLabel();
                host.toast(add ? "已预约" : "已取消预约");
            });
        });
    }

    private void refreshToggles() {
        if (mFavBtn != null) {
            mFavBtn.setText(Store.isFavorite(mSeriesId) ? "已追剧" : "追剧");
            paintToggle(mFavBtn, mFavBtn.hasFocus());
        }
        if (mLikeBtn != null) {
            mLikeBtn.setText(Store.isLiked(mSeriesId) ? "已喜欢" : "喜欢");
            paintToggle(mLikeBtn, mLikeBtn.hasFocus());
        }
    }

    private void buildEpisodes() {
        mEpisodeHost.removeAllViews();
        mRangeHost.removeAllViews();mScroll.scrollTo(0,0);
        mRangeHost.setVisibility(mDetail!=null&&mDetail.episodes.size()>100?View.VISIBLE:View.GONE);
        if (mDetail == null || mDetail.episodes.isEmpty()) return;
        TextView episodeLabel=Ui.text(ctx,"选择剧集",12,Ui.TEXT_3,false);Ui.padding(episodeLabel,0,8,0,8);mEpisodeHost.addView(episodeLabel);
        if(mDetail.episodes.size()>100){TextView rangeLabel=Ui.text(ctx,"集数范围",12,Ui.TEXT_3,false);Ui.padding(rangeLabel,0,0,0,8);mRangeHost.addView(rangeLabel);}

        HorizontalScrollView ranges=new HorizontalScrollView(ctx);ranges.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs=Ui.row(ctx);ranges.addView(tabs);if(mDetail.episodes.size()>100)mRangeHost.addView(ranges,Ui.lp(-1,Ui.dp(ctx,44)));
        LinearLayout target=Ui.column(ctx);mEpisodeHost.addView(target,Ui.lp(-1,-2));
        final java.util.List<TextView> buttons=new java.util.ArrayList<>();final int[] selected={mContinueIndex/100};
        for(int page=0;mDetail.episodes.size()>100 && page*100<mDetail.episodes.size();page++){
            final int range=page;TextView tab=Ui.action(ctx,(page*100+1)+"～"+Math.min(page*100+100,mDetail.episodes.size()),IconView.EPISODES,false);
            tab.setCompoundDrawables(null,null,null,null);tab.setCompoundDrawablePadding(0);paintRangeTab(tab,false,page==selected[0]);
            LinearLayout.LayoutParams lp=Ui.lp(Ui.dp(ctx,104),Ui.dp(ctx,36));lp.rightMargin=Ui.dp(ctx,10);tabs.addView(tab,lp);buttons.add(tab);
            tab.setOnFocusChangeListener((v,focus)->{paintRangeTab(tab,focus,range==selected[0]);});
            tab.setOnClickListener(v->{selected[0]=range;buildEpisodeRange(target,range);mScroll.smoothScrollTo(0,0);for(int n=0;n<buttons.size();n++){TextView t=buttons.get(n);boolean active=n==range;paintRangeTab(t,t.hasFocus(),active);}tab.requestFocus();});
        }
        if(!buttons.isEmpty()){TextView last=buttons.get(buttons.size()-1);last.setId(View.generateViewId());last.setNextFocusRightId(last.getId());   // 最后一个范围页签右键停住
            TextView cur0=buttons.get(selected[0]);if(cur0.getId()==View.NO_ID)cur0.setId(View.generateViewId());
            for(View act:new View[]{mPlayBtn,mFavBtn,mCommentBtn})if(act!=null)act.setNextFocusDownId(cur0.getId());}
        if(!buttons.isEmpty()){TextView current=buttons.get(selected[0]);paintRangeTab(current,current.hasFocus(),true);ranges.post(()->ranges.smoothScrollTo(current.getLeft(),0));}
        buildEpisodeRange(target,selected[0]);
    }
    private void paintRangeTab(TextView tab,boolean focus,boolean selected){
        Ui.tile(tab,focus,selected,100);
    }

    private View episodeEntry;
    private void buildEpisodeRange(LinearLayout target,int page){
        target.removeAllViews();episodeEntry=null;
        // 短剧选集比影视长文案更重要：更大的遥控器可点击区域。
        int btnH = Ui.dp(ctx, 48);
        int gap = Ui.dp(ctx, 10);

        LinearLayout row = null;
        int start=page*100,end=Math.min(start+100,mDetail.episodes.size());
        for (int i = start; i < end; i++) {
            if ((i-start) % COLS == 0) {
                row = Ui.row(ctx);
                LinearLayout.LayoutParams rlp = Ui.lp(-1, -2);
                rlp.bottomMargin = gap;
                target.addView(row, rlp);
            }
            final int idx = i;
            Episode ep = mDetail.episodes.get(i);
            TextView b = new TextView(ctx);
            b.setText(String.valueOf(ep.index > 0 ? ep.index : (i + 1)));
            b.setTextSize(16);
            b.setGravity(Gravity.CENTER);
            final boolean isContinue = (i == mContinueIndex);
            b.setFocusable(true);
            b.setIncludeFontPadding(false);
            b.setFontFeatureSettings("tnum");
            Ui.tile(b, false, isContinue, 12);
            b.setOnFocusChangeListener((v, has) -> {
                Ui.tile((TextView) v, has, isContinue, 12);
            });
            b.setOnClickListener(v -> play(idx));
            b.setId(View.generateViewId());
            if ((i - start) % COLS == COLS - 1 || i == end - 1) b.setNextFocusRightId(b.getId());   // 行尾右键停住
            // 从上方按钮往下：落在「继续看的那一集」（不在本页就落在本页第一集），而不是正下方随便一集
            if (isContinue || (i == start && (mContinueIndex < start || mContinueIndex >= end))) episodeEntry = b;
            LinearLayout.LayoutParams blp = Ui.lp(0, btnH, 1);
            if ((i-start) % COLS != 0) blp.leftMargin = gap;
            row.addView(b, blp);
        }
        if (episodeEntry != null && (mRangeHost == null || mRangeHost.getVisibility() != View.VISIBLE))
            for (View act : new View[]{mPlayBtn, mFavBtn, mCommentBtn}) if (act != null) act.setNextFocusDownId(episodeEntry.getId());
        int remainder = (end-start) % COLS;
        if (row != null && remainder > 0) for (int i = remainder; i < COLS; i++) {
            LinearLayout.LayoutParams blank = Ui.lp(0, 1, 1);
            blank.leftMargin = gap;
            row.addView(new View(ctx), blank);
        }
    }

    private boolean loadSeasons() {
        if(mDetail==null || !mDetail.multiSeason || mDetail.relatedAlbumId==null || mDetail.relatedAlbumId.isEmpty()){
            mSeasonScroll.setVisibility(View.GONE);mSeasonTabs.removeAllViews();setEpisodeHeaderSpacing(false);return false;
        }
        final String id=mSeriesId,album=mDetail.relatedAlbumId;final int generation=loadGeneration;
        java.util.List<Repo.Season> known=knownSeasonLists.get(album);
        if(known!=null){renderSeasonTabs(known);return false;}
        if (album.equals(mSeasonRequestAlbum)) {
            if (mSeasonRequestPending) return true;
            if (mSeasonRequestResolved) {
                mSeasonTabs.removeAllViews();mSeasonScroll.setVisibility(View.GONE);setEpisodeHeaderSpacing(false);
                return false;
            }
        }
        mSeasonRequestAlbum = album;
        mSeasonRequestPending = true;
        mSeasonRequestResolved = false;
        mSeasonScroll.setVisibility(View.VISIBLE);mSeasonTabs.removeAllViews();setEpisodeHeaderSpacing(true);
        mSeasonTabs.addView(new ink.pengfei.lvguo.ui.widget.SkeletonView(ctx,ink.pengfei.lvguo.ui.widget.SkeletonView.SEASONS,1,0,1),Ui.lp(-1,Ui.dp(ctx,46)));
        App.io().execute(()->{
            try{
                java.util.List<Repo.Season> seasons=Repo.seasonsQuick(id,album);
                host.runOnUiThread(()->{
                    if(generation!=loadGeneration || !id.equals(mSeriesId))return;
                    mSeasonRequestPending=false;
                    mSeasonRequestResolved=true;
                    if(seasons!=null&&!seasons.isEmpty())knownSeasonLists.put(album,new java.util.ArrayList<>(seasons));
                    renderSeasonTabs(seasons);
                    // Publish the episode grid only after the season request has settled.
                    render();
                });
            }catch(Exception error){host.runOnUiThread(()->{
                if(generation!=loadGeneration || !id.equals(mSeriesId))return;
                mSeasonRequestPending=false;
                mSeasonRequestResolved=true;
                mSeasonTabs.removeAllViews();mSeasonScroll.setVisibility(View.GONE);setEpisodeHeaderSpacing(false);
                // Season metadata is optional: show available episodes after the failure.
                render();
            });}
        });
        return true;
    }
    private void renderSeasonTabs(java.util.List<Repo.Season> seasons){
        mSeasonTabs.removeAllViews();
        java.util.List<Repo.Season> playable=new java.util.ArrayList<>();if(seasons!=null)for(Repo.Season item:seasons)if(item.episodes>0||(mSeriesId.equals(item.id)&&mDetail!=null&&!mDetail.episodes.isEmpty()))playable.add(item);seasons=playable;
        if(seasons.size()<=1){mSeasonScroll.setVisibility(View.GONE);setEpisodeHeaderSpacing(false);return;}
        mSeasonScroll.setVisibility(View.VISIBLE);setEpisodeHeaderSpacing(true);
        for(Repo.Season season:seasons){
            boolean exact=false;for(Repo.Season row:seasons)if(mSeriesId.equals(row.id))exact=true;
            boolean current=mSeriesId.equals(season.id)||(!exact&&mDetail!=null&&mDetail.seasonIndex==season.number),available=current||season.episodes>0;
            String label="第 "+season.number+" 季";
            TextView tab=Ui.action(ctx,label,0,false);
            tab.setCompoundDrawables(null,null,null,null);tab.setCompoundDrawablePadding(0);
            paintRangeTab(tab,false,current);tab.setContentDescription(label+"，"+season.title);
            tab.setOnFocusChangeListener((v,has)->paintRangeTab(tab,has,current));
            tab.setEnabled(available);tab.setFocusable(available);if(!available)tab.setAlpha(.5f);
            tab.setOnClickListener(v->{if(current)return;Series s=new Series();s.seriesId=season.id;s.title=season.title;s.cover=season.cover;host.openDetail(s);});
            LinearLayout.LayoutParams lp=Ui.lp(-2,Ui.dp(ctx,compactHeader?36:44));lp.rightMargin=Ui.dp(ctx,10);mSeasonTabs.addView(tab,lp);
            if(current)tab.post(()->mSeasonScroll.smoothScrollTo(Math.max(0,tab.getLeft()-Ui.dp(ctx,24)),0));
        }
    }
    private void setEpisodeHeaderSpacing(boolean hasSeasons){
        if(mSeasonLabel!=null)mSeasonLabel.setVisibility(hasSeasons?View.VISIBLE:View.GONE);
        if(mEpisodeHeader==null)return;
        mEpisodeHeader.setPadding(Ui.dp(ctx, 48),Ui.dp(ctx,hasSeasons?8:compactHeader?16:24),Ui.dp(ctx, 48),0);
    }
    private void showEpisodeSkeleton(){
        int heightDp=(int)(ctx.getResources().getDisplayMetrics().heightPixels/ctx.getResources().getDisplayMetrics().density);
        int rows=Math.max(5,(heightDp-160)/60);
        mSeasonTabs.removeAllViews();mSeasonScroll.setVisibility(View.GONE);setEpisodeHeaderSpacing(false);
        mEpisodeHost.removeAllViews();
        mRangeHost.removeAllViews();mRangeHost.setVisibility(View.GONE);
        mEpisodeHost.addView(new ink.pengfei.lvguo.ui.widget.SkeletonView(ctx,ink.pengfei.lvguo.ui.widget.SkeletonView.EPISODES,COLS,44,rows),Ui.lp(-1,-2));
    }

    private void play(int index) {
        play(index,false);
    }
    private void play(int index,boolean resume) {
        if (mSeriesId == null) return;
        Intent i = new Intent(host, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_SERIES, mSeriesId);
        i.putExtra(PlayerActivity.EXTRA_TITLE, displayTitle());
        String cover = usableCover();
        if (cover != null) i.putExtra(PlayerActivity.EXTRA_COVER, cover);
        if (mDetail != null && !mDetail.episodes.isEmpty()) {
            Store.Item saved=resume?historyItem():null;
            if(saved!=null && saved.episodeIndex==index){
                int position=saved.positionMs;
                if(saved.durationMs>0 && position>=saved.durationMs-1000){position=0;if(index+1<mDetail.episodes.size())index++;}
                i.putExtra("position_ms",position);
            }
            i.putExtra(PlayerActivity.EXTRA_INDEX, index);
            if (index >= 0 && index < mDetail.episodes.size()) {
                String vid = mDetail.episodes.get(index).vid;
                if (vid != null && !vid.isEmpty()) {
                    i.putExtra(PlayerActivity.EXTRA_VID, vid);
                    i.putExtra(PlayerActivity.EXTRA_EPISODE_NO, mDetail.episodes.get(index).index);
                }
            }
        } else {
            // 降级模式：只带 vid（第 1 集或历史里记录的那一集）
            Store.Item his = historyItem();
            String vid = firstVid();
            int epNo = 1;
            if (his != null && his.vid != null && !his.vid.isEmpty() && his.lastEpisode > 1) {
                vid = his.vid;
                epNo = his.lastEpisode;
            }
            if (vid == null) {
                host.toast("暂无可播放内容");
                return;
            }
            i.putExtra(PlayerActivity.EXTRA_INDEX, 0);
            i.putExtra(PlayerActivity.EXTRA_VID, vid);
            i.putExtra(PlayerActivity.EXTRA_EPISODE_NO, epNo);
        }
        host.startActivity(i);
    }

    @Override
    public void focusFirst() {
        if (mPlayBtn != null) mPlayBtn.requestFocus();
    }
}
