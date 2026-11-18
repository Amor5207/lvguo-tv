package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import ink.pengfei.lvguo.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 横向内容行。
 *
 * TV 端的滚动是"焦点驱动"而不是触摸拖动，所以这里做两件事：
 *  1) 任一子项获得焦点时，把它滚到距左边缘约 1/5 的位置，露出的后续卡片给人"还有内容"的暗示；
 *  2) 视窗化管理：只有当前视窗 ±1 屏范围内的卡片才真正解码封面图，
 *     其余保持占位。这样一行 30 张卡片的内存占用与一屏 7 张几乎一致。
 *
 * 没有引 RecyclerView：TV 上一行最多几十项，View 复用的收益远小于
 * 焦点跳转时 viewType 重建带来的抖动；改用"限制解码范围"来控制内存更直接。
 */
public class HScrollRow extends HorizontalScrollView {

    public interface Visible {
        void onActiveChanged(boolean active);
    }

    private final LinearLayout mRow;
    private final List<View> mItems = new ArrayList<>();
    private final boolean[] mActive = new boolean[]{false};
    private int mGap;
    private int mLastFocusedIndex = -1;
    private int mRestoreIndex = -1;
    private String mLastFocusedSeriesId;
    private String mRestoreSeriesId;

    public HScrollRow(Context c, int gapDp) {
        super(c);
        mGap = Ui.dp(c, gapDp);
        setHorizontalScrollBarEnabled(false);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        setClipChildren(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        // 左右渐隐，暗示内容延伸
        setHorizontalFadingEdgeEnabled(false);

        mRow = new LinearLayout(c);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setClipChildren(false);
        mRow.setClipToPadding(false);
        addView(mRow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // 滚动或焦点变化后重算可见集合
        getViewTreeObserver().addOnScrollChangedListener(this::refreshVisible);
        getViewTreeObserver().addOnGlobalLayoutListener(this::refreshVisible);
    }

    public LinearLayout inner() {
        return mRow;
    }

    public void clear() {
        View focused = findFocus();
        int focusedIndex = indexContaining(focused);
        if (focusedIndex >= 0) rememberItem(focusedIndex);
        if (mLastFocusedIndex >= 0) {
            mRestoreIndex = mLastFocusedIndex;
            mRestoreSeriesId = mLastFocusedSeriesId;
        }
        mRow.removeAllViews();
        mItems.clear();
    }

    public void add(View v) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = mGap;
        v.setLayoutParams(lp);
        mRow.addView(v, lp);
        // 行尾再按右键停在原地，不要跳到下一行去
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        if (!mItems.isEmpty()) { View prev = mItems.get(mItems.size() - 1); if (prev.getNextFocusRightId() == prev.getId()) prev.setNextFocusRightId(View.NO_ID); }
        v.setNextFocusRightId(v.getId());
        mItems.add(v);
        attachFocusScroll(v);
    }

    public int size() {
        return mItems.size();
    }

    public View item(int i) {
        return i >= 0 && i < mItems.size() ? mItems.get(i) : null;
    }

    public void addAll(List<? extends View> list) {
        for (View v : list) add(v);
        refreshVisible();
    }

    // ------------------------------------------------------------ 焦点滚动

    private void attachFocusScroll(final View v) {
        Runnable onFocused = () -> {
            int idx = mItems.indexOf(v);
            if (idx >= 0) rememberItem(idx);
            scrollIntoView(v);
            refreshVisible();
            // 预取右侧 3 项，同时保留 PosterCard 自身的焦点描边/缩放监听。
            for (int i = idx + 1; i <= idx + 3 && i < mItems.size(); i++) {
                setActive(mItems.get(i), true);
            }
        };
        // 叠加而不是替换：页面自己设的回调（首页按行滚动、看板换内容、焦点描边）必须保留，
        // 之前这里直接覆盖，导致首页收藏/热播/新剧行聚焦后页面不滚动、焦点跑到屏幕外。
        if (v instanceof ink.pengfei.lvguo.ui.widget.PosterCard) {
            ink.pengfei.lvguo.ui.widget.PosterCard card = (ink.pengfei.lvguo.ui.widget.PosterCard) v;
            final Runnable own = card.getOnFocused();
            card.setOnFocused(() -> { onFocused.run(); if (own != null) own.run(); });
        } else {
            final OnFocusChangeListener own = v.getOnFocusChangeListener();
            v.setOnFocusChangeListener((view, has) -> { if (own != null) own.onFocusChange(view, has); if (has) onFocused.run(); });
        }
    }

    /** Keep the current horizontal position unless the focused card is outside the viewport. */
    private void scrollIntoView(View v) {
        int vx = v.getLeft();
        int vw = v.getWidth();
        if (vw <= 0) {
            post(() -> scrollIntoView(v));
            return;
        }
        int current = getScrollX();
        int target = current;
        if (vx < current) target = vx;
        else if (vx + vw > current + getWidth()) target = vx + vw - getWidth();
        int max = Math.max(0, mRow.getWidth() - getWidth());
        if (target < 0) target = 0;
        if (target > max) target = max;
        if (target != current) smoothScrollTo(target, 0);
    }

    private void rememberItem(int index) {
        if (index < 0 || index >= mItems.size()) return;
        mLastFocusedIndex = index;
        View item = mItems.get(index);
        mLastFocusedSeriesId = item instanceof PosterCard ? ((PosterCard) item).getSeriesId() : null;
    }

    private int indexContaining(View focused) {
        if (focused == null) return -1;
        for (int i = 0; i < mItems.size(); i++) {
            View item = mItems.get(i);
            View current = focused;
            while (current != null && current != item) {
                if (!(current.getParent() instanceof View)) break;
                current = (View) current.getParent();
            }
            if (current == item) return i;
        }
        return -1;
    }

    private View resolveRememberedFocus() {
        if (mItems.isEmpty()) return null;
        if (mRestoreSeriesId != null) {
            for (View item : mItems) {
                if (item instanceof PosterCard && mRestoreSeriesId.equals(((PosterCard) item).getSeriesId())
                        && item.getVisibility() == VISIBLE && item.isFocusable()) return item;
            }
        }
        int remembered = mRestoreIndex >= 0 ? mRestoreIndex : mLastFocusedIndex;
        if (remembered < 0) return null;
        int at = Math.max(0, Math.min(remembered, mItems.size() - 1));
        for (int d = 0; d < mItems.size(); d++) {
            int right = at + d;
            if (right < mItems.size() && mItems.get(right).getVisibility() == VISIBLE && mItems.get(right).isFocusable()) return mItems.get(right);
            int left = at - d;
            if (left >= 0 && mItems.get(left).getVisibility() == VISIBLE && mItems.get(left).isFocusable()) return mItems.get(left);
        }
        return null;
    }

    /** Last focused child in this horizontal row, if one has been focused before. */
    public View rememberedFocus() {
        if (mItems.isEmpty()) return null;
        mRestoreIndex = mLastFocusedIndex;
        mRestoreSeriesId = mLastFocusedSeriesId;
        return resolveRememberedFocus();
    }

    /** Restore focus after the row's children have been replaced. */
    public boolean focusRemembered() {
        View target = resolveRememberedFocus();
        if (target == null) return false;
        mRestoreIndex = -1;
        mRestoreSeriesId = null;
        target.requestFocus();
        return true;
    }

    public void focusFirst() {
        for (View v : mItems) {
            if (v.getVisibility() == VISIBLE && v.isFocusable()) {
                v.requestFocus();
                return;
            }
        }
    }

    public void focusLast() {
        for (int i = mItems.size() - 1; i >= 0; i--) {
            View v = mItems.get(i);
            if (v.getVisibility() == VISIBLE && v.isFocusable()) {
                v.requestFocus();
                return;
            }
        }
    }

    /** 焦点是否已在该行内 */
    public boolean hasFocusInside() {
        View f = findFocus();
        return f != null && f != this;
    }

    // ------------------------------------------------------------ 视窗化

    private final android.graphics.Rect visibleRect=new android.graphics.Rect();
    private void refreshVisible() {
        boolean shown=isShown() && getGlobalVisibleRect(visibleRect);
        int x0 = getScrollX() - getWidth();
        int x1 = getScrollX() + getWidth() * 2;
        for (View v : mItems) {
            boolean visible = shown && v.getRight() > x0 && v.getLeft() < x1;
            setActive(v, visible);
        }
    }

    private void setActive(View v, boolean active) {
        if (!(v instanceof Visible)) return;
        Boolean cur = (Boolean) v.getTag(TAG_ACTIVE);
        if (cur != null && cur == active) return;
        v.setTag(TAG_ACTIVE, active);
        ((Visible) v).onActiveChanged(active);
    }

    private static final int TAG_ACTIVE = 0x7f0e0001;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // 行内左右到头时，把事件交回给容器（避免焦点卡死在行尾）
        return super.dispatchKeyEvent(e);
    }
}
