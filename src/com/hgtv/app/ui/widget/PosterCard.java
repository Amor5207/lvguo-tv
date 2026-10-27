package com.hgtv.app.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hgtv.app.img.ImageLoader;
import com.hgtv.app.model.Series;
import com.hgtv.app.ui.Ui;

/**
 * 竖版短剧封面卡（约 2:3）。聚焦时放大 + 珊瑚高亮描边，标题提亮。
 *
 * 只负责展示与聚焦反馈；封面加载延后到"进入视窗"时才触发（见 onActiveChanged）。
 */
public class PosterCard extends LinearLayout implements HScrollRow.Visible {

    public interface OnHighlighted { void onHighlighted(); }
    private OnHighlighted mOnHighlighted;
    private Runnable mOnFocused;
    private TextView mSubtitle;

    public void setOnHighlighted(OnHighlighted cb) { mOnHighlighted = cb; }
    public void setOnFocused(Runnable callback) { mOnFocused = callback; }
    public void setBadge(String badge) {
        if (badge == null || badge.isEmpty()) {
            mBadge.setVisibility(GONE);
        } else {
            mBadge.setText(badge);
            mBadge.setVisibility(VISIBLE);
            mBadge.setTextColor(Ui.TEXT);
        }
    }
    public void setSubtitle(String subtitle) {
        if (mSubtitle == null) {
            mSubtitle = Ui.ellipsis(getContext(), "", 12f, Ui.TEXT_3, 1);
            LayoutParams lp = new LayoutParams(mW, -2);
            lp.topMargin = Ui.dp(getContext(), 5);
            addView(mSubtitle, lp);
        }
        mSubtitle.setText(subtitle == null ? "" : subtitle);
        mSubtitle.setVisibility(subtitle == null || subtitle.isEmpty() ? GONE : VISIBLE);
    }

    private final ImageView mCover;
    private final TextView mTitle;
    private final TextView mBadge;
    private final TextView mMissingCover;
    private final FrameLayout mFrame;
    private final View mFocusRing;
    private final int mW, mH;
    private String mUrl;
    private boolean mLoaded;

    public PosterCard(Context c, Series s, int wDp, int hDp) {
        super(c);
        mW = Ui.dp(c, wDp);
        mH = Ui.dp(c, hDp);

        setOrientation(VERTICAL);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(Color.TRANSPARENT);
        if(android.os.Build.VERSION.SDK_INT>=26)setDefaultFocusHighlightEnabled(false);
        setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2));

        // ---- 封面容器
        mFrame = new FrameLayout(c);
        mFrame.setBackground(Ui.round(c, Ui.SURFACE, 6));
        mFrame.setClipToOutline(true);
        mFrame.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(getContext(), 6));
            }
        });

        mCover = new ImageView(c);
        mCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mFrame.addView(mCover, new FrameLayout.LayoutParams(-1, -1));
        mMissingCover = Ui.text(c, "暂无封面", 15f, Ui.TEXT_4, false);
        mMissingCover.setGravity(Gravity.CENTER);
        mFrame.addView(mMissingCover, new FrameLayout.LayoutParams(-1, -1));
        mMissingCover.setVisibility(GONE);

        // 底部渐变，保证角标可读
        View scrim = new View(c);
        scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC0B0E11, 0x000B0E11}));
        mFrame.addView(scrim, new FrameLayout.LayoutParams(-1, Ui.dp(c, 64), Gravity.BOTTOM));

        // 右下角：集数 / 榜单名次
        mBadge = Ui.text(c, "", 11, Ui.TEXT, false);
        mBadge.setBackground(Ui.pill(c, 0x99000000));
        mBadge.setPadding(Ui.dp(c, 7), Ui.dp(c, 3), Ui.dp(c, 7), Ui.dp(c, 3));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.RIGHT);
        bp.setMargins(0, 0, Ui.dp(c, 7), Ui.dp(c, 7));
        mFrame.addView(mBadge, bp);
        mBadge.setVisibility(GONE);

        // 描边必须盖在封面图片之上，否则背景描边会被 ImageView 遮住。
        mFocusRing = new View(c);
        mFocusRing.setFocusable(false);
        mFocusRing.setBackground(Ui.roundStroke(c, 0x00000000, 6, Ui.PRIMARY, 2.5f));
        mFocusRing.setVisibility(INVISIBLE);
        mFrame.addView(mFocusRing, new FrameLayout.LayoutParams(-1, -1));

        addView(mFrame, new LayoutParams(mW, mH));

        // ---- 标题
        mTitle = Ui.ellipsis(c, s == null ? "" : s.title, 14f, Ui.TEXT_2, 1);
        LinearLayout.LayoutParams tp = new LayoutParams(mW, Ui.dp(c, 24));
        mTitle.setGravity(Gravity.CENTER_VERTICAL);
        tp.topMargin = Ui.dp(c, 7);
        mTitle.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
        addView(mTitle, tp);

        setOnFocusChangeListener((v, has) -> {
            Ui.focus(v, has, 1.025f);
            mTitle.setTextColor(has ? Ui.TEXT : Ui.TEXT_2);
            mFocusRing.setVisibility(has ? VISIBLE : INVISIBLE);
            mFrame.setBackground(Ui.roundStroke(getContext(),
                    has ? Ui.SURFACE_2 : Ui.SURFACE, 6,
                    has ? Ui.PRIMARY : 0x00000000, 2));
            if (has) {
                ensureLoaded();
                if(getContext() instanceof com.hgtv.app.ui.RootActivity)((com.hgtv.app.ui.RootActivity)getContext()).setCoverBackground(mUrl);
                if (mOnFocused != null) mOnFocused.run();
                if (mOnHighlighted != null) mOnHighlighted.onHighlighted();
            }
        });

        if (s != null) bind(s);
    }

    public void bind(Series s) {
        mUrl = s.cover;
        if(getContext() instanceof com.hgtv.app.ui.RootActivity)((com.hgtv.app.ui.RootActivity)getContext()).seedCoverBackground(mUrl);
        mMissingCover.setVisibility(mUrl == null ||
                !(mUrl.startsWith("http://") || mUrl.startsWith("https://")) ? VISIBLE : GONE);
        mLoaded = false;
        mCover.setTag(com.hgtv.app.R.id.img_tag,null);
        mCover.setImageDrawable(null);
        mFrame.setBackground(Ui.round(getContext(), Ui.SURFACE, 6));

        String badge = null;
        if (s.rank > 0 && s.rank <= 99 && s.showRank) {
            badge = "TOP " + s.rank;
        } else if (s.episodeCount > 0) {
            badge = s.episodeCount + "集";
        }
        if (badge != null) {
            mBadge.setText(badge);
            mBadge.setVisibility(VISIBLE);
            mBadge.setTextColor(s.showRank && s.rank <= 3 ? Ui.GOLD : Ui.TEXT);
        } else {
            mBadge.setVisibility(GONE);
        }
        mTitle.setText(s.title == null ? "" : s.title);
    }

    private void ensureLoaded() {
        if (mLoaded || mUrl == null || mUrl.isEmpty()) return;
        mLoaded = true;
        ImageLoader.load(mUrl, mCover, mW, mH);
    }

    @Override
    public void onActiveChanged(boolean active) {
        if (active) ensureLoaded();
        else if(mLoaded){mLoaded=false;mCover.setTag(com.hgtv.app.R.id.img_tag,null);mCover.setImageDrawable(null);}
    }

    @Override protected void onDetachedFromWindow(){
        onActiveChanged(false);super.onDetachedFromWindow();
    }

    /** 让外部（如横向行）能精确控制何时解码 */
    public void forceLoad() {
        ensureLoaded();
    }
}
