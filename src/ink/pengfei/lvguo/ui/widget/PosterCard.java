package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.ui.Ui;

/**
 * 竖版短剧封面卡（约 2:3）。聚焦时保持原尺寸，用珊瑚描边和标题提亮提示焦点。
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
    public Runnable getOnFocused() { return mOnFocused; }
    public void setBadge(String badge) {
        if (badge == null || badge.isEmpty()) {
            mBadge.setVisibility(GONE);
        } else {
            mBadge.setText(badge);
            mBadge.setVisibility(VISIBLE);
            mBadge.setTextColor(0xFFFFFFFF);
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
    private final int mW, mH;
    private String mUrl;
    private String mSeriesId;
    private boolean mLoaded;

    public String getSeriesId() { return mSeriesId; }

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
        mFrame = new RoundFrame(c, 12);
        mFrame.setBackground(Ui.round(c, Ui.SURFACE, 12));

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
                new int[]{0x99000000, 0x00000000}));
        mFrame.addView(scrim, new FrameLayout.LayoutParams(-1, Ui.dp(c, 48), Gravity.BOTTOM));

        // 右下角：集数 / 榜单名次
        mBadge = Ui.text(c, "", 11, 0xFFFFFFFF, false);
        mBadge.setBackground(Ui.pill(c, 0x8C000000));
        mBadge.setPadding(Ui.dp(c, 7), Ui.dp(c, 3), Ui.dp(c, 7), Ui.dp(c, 3));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.RIGHT);
        bp.setMargins(0, 0, Ui.dp(c, 7), Ui.dp(c, 7));
        mFrame.addView(mBadge, bp);
        mBadge.setVisibility(GONE);


        addView(mFrame, new LayoutParams(mW, mH));

        // ---- 标题
        mTitle = Ui.ellipsis(c, s == null ? "" : s.title, 14f, Ui.TEXT_2, 1);
        LinearLayout.LayoutParams tp = new LayoutParams(mW, Ui.dp(c, 24));
        mTitle.setGravity(Gravity.CENTER_VERTICAL);
        tp.topMargin = Ui.dp(c, 7);
        mTitle.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
        addView(mTitle, tp);

        setOnFocusChangeListener((v, has) -> {
            Ui.focus(mFrame);               // 卡片获得焦点时保持原尺寸
            mTitle.setTextColor(has ? Ui.TEXT : Ui.TEXT_2);
            mTitle.setTypeface(android.graphics.Typeface.defaultFromStyle(has ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            ((RoundFrame) mFrame).setRing(has, Ui.FOCUS_RING, 3f);
            if (has) {
                ensureLoaded();
                if(getContext() instanceof ink.pengfei.lvguo.ui.RootActivity)((ink.pengfei.lvguo.ui.RootActivity)getContext()).setCoverBackground(mUrl);
                if (mOnFocused != null) mOnFocused.run();
                if (mOnHighlighted != null) mOnHighlighted.onHighlighted();
            }
        });

        if (s != null) bind(s);
    }

    public void bind(Series s) {
        mSeriesId = s.seriesId;
        mUrl = s.cover;
        if(getContext() instanceof ink.pengfei.lvguo.ui.RootActivity)((ink.pengfei.lvguo.ui.RootActivity)getContext()).seedCoverBackground(mUrl);
        mMissingCover.setVisibility(mUrl == null ||
                !(mUrl.startsWith("http://") || mUrl.startsWith("https://")) ? VISIBLE : GONE);
        mLoaded = false;
        mCover.setTag(ink.pengfei.lvguo.R.id.img_tag,null);
        mCover.setImageDrawable(null);
        mFrame.setBackground(Ui.round(getContext(), Ui.SURFACE, 12));

        String badge = null;
        if (s.rank > 0 && s.rank <= 99 && s.showRank) {
            badge = "TOP " + s.rank;
        } else if (s.episodeCount > 0) {
            badge = s.episodeCount + "集";
        }
        if (badge != null) {
            mBadge.setText(badge);
            mBadge.setVisibility(VISIBLE);
            mBadge.setTextColor(s.showRank && s.rank <= 3 ? 0xFFFFD27A : 0xFFFFFFFF);
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
        else if(mLoaded){mLoaded=false;mCover.setTag(ink.pengfei.lvguo.R.id.img_tag,null);mCover.setImageDrawable(null);}
    }

    @Override protected void onDetachedFromWindow(){
        onActiveChanged(false);super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------ 封面上的操作蒙层（移除单条历史 / 取消收藏）

    private static PosterCard sMasked;
    private FrameLayout mMask;

    /**
     * 在封面上盖一层蒙层，竖排两个按钮「移除」「返回」，焦点先落在「移除」。
     * 聚焦的按钮是实心（移除=红底白字，返回=白底黑字），没聚焦的只是半透明描边，一眼看出选中的是哪个。
     * 上下键在两个按钮间切换；左右键 / 菜单键 / 返回键 = 取消。
     */
    public void showAction(String label, Runnable action) {
        if (sMasked != null && sMasked != this) sMasked.hideAction(false);
        Context c = getContext();
        if (mMask == null) {
            mMask = new FrameLayout(c);
            mMask.setBackgroundColor(0xD9000000);
            mFrame.addView(mMask, new FrameLayout.LayoutParams(-1, -1));
        }
        mMask.removeAllViews();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER);
        final View remove = maskButton(c, label, ink.pengfei.lvguo.ui.IconView.TRASH, 0xFFFF453A, 0xFFFFFFFF);
        final View back = maskButton(c, "返回", ink.pengfei.lvguo.ui.IconView.BACK, 0xFFFFFFFF, 0xFF111111);
        int bw = Math.min(mW - Ui.dp(c, 20), Ui.dp(c, 96));
        col.addView(remove, new LayoutParams(bw, Ui.dp(c, 38)));
        LayoutParams bl = new LayoutParams(bw, Ui.dp(c, 38)); bl.topMargin = Ui.dp(c, 12);
        col.addView(back, bl);
        mMask.addView(col, new FrameLayout.LayoutParams(-1, -1));
        mMask.setVisibility(VISIBLE);
        mMask.setAlpha(0f); mMask.animate().alpha(1f).setDuration(140).start();
        remove.setOnClickListener(v -> { hideAction(false); requestFocus(); action.run(); });
        back.setOnClickListener(v -> hideAction(true));
        remove.setId(View.generateViewId()); back.setId(View.generateViewId());
        remove.setNextFocusDownId(back.getId()); remove.setNextFocusUpId(remove.getId());
        back.setNextFocusUpId(remove.getId()); back.setNextFocusDownId(back.getId());
        android.view.View.OnKeyListener keys = (v, code, e) -> {
            if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            switch (code) {
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT: case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                case android.view.KeyEvent.KEYCODE_MENU:
                    hideAction(true); return true;
            }
            return false;
        };
        remove.setOnKeyListener(keys); back.setOnKeyListener(keys);
        // 焦点离开两个按钮（比如被别的界面抢走）就收起蒙层
        android.view.View.OnFocusChangeListener lost = (v, has) -> {
            if (has) return;
            post(() -> { if (mMask != null && mMask.getVisibility() == VISIBLE && !remove.hasFocus() && !back.hasFocus()) hideAction(false); });
        };
        chainFocus(remove, lost); chainFocus(back, lost);
        sMasked = this;
        remove.requestFocus();
    }

    private static void chainFocus(View v, android.view.View.OnFocusChangeListener extra) {
        final android.view.View.OnFocusChangeListener own = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener((x, has) -> { if (own != null) own.onFocusChange(x, has); extra.onFocusChange(x, has); });
    }

    /** 蒙层上的按钮（图标 + 文字居中）：聚焦=实心色块 + 粗体；未聚焦=透明底 + 半透明白描边。 */
    private static View maskButton(Context c, String text, int icon, int fill, int ink) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) b.setDefaultFocusHighlightEnabled(false);
        ink.pengfei.lvguo.ui.IconView iv = new ink.pengfei.lvguo.ui.IconView(c, icon, 15);
        b.addView(iv, new LayoutParams(Ui.dp(c, 15), Ui.dp(c, 15)));
        TextView t = Ui.text(c, text, 14, 0xFFFFFFFF, true);
        t.setSingleLine(true);
        LayoutParams tl = new LayoutParams(-2, -2); tl.leftMargin = Ui.dp(c, 5);
        b.addView(t, tl);
        Runnable paint = () -> {
            boolean f = b.isFocused();
            b.setBackground(f ? Ui.round(c, fill, 19) : Ui.roundStroke(c, 0x33FFFFFF, 19, 0x66FFFFFF, 1));
            int color = f ? ink : 0xFFFFFFFF;
            t.setTextColor(color);
            iv.setColor(color);
            t.setTypeface(android.graphics.Typeface.defaultFromStyle(f ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        };
        paint.run();
        b.setOnFocusChangeListener((v, has) -> paint.run());
        return b;
    }

    public void hideAction(boolean refocus) {
        if (sMasked == this) sMasked = null;
        if (mMask == null || mMask.getVisibility() != VISIBLE) return;
        mMask.setVisibility(GONE);
        if (refocus) requestFocus();
    }

    /** 返回键先关掉正在显示的蒙层（由 RootActivity 调用）。 */
    public static boolean dismissActive() {
        if (sMasked == null) return false;
        sMasked.hideAction(true);
        return true;
    }

    /** 历史 / 收藏卡片：长按确定或菜单键弹出「移除」蒙层。 */
    public void enableRemove(String label, Runnable remove) {
        setOnLongClickListener(v -> { showAction(label, remove); return true; });
        setOnKeyListener((v, code, e) -> {
            if (code == android.view.KeyEvent.KEYCODE_MENU && e.getAction() == android.view.KeyEvent.ACTION_DOWN) { showAction(label, remove); return true; }
            return false;
        });
    }

    /** 让外部（如横向行）能精确控制何时解码 */
    public void forceLoad() {
        ensureLoaded();
    }
}
