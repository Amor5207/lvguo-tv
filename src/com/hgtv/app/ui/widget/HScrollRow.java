package com.hgtv.app.ui.widget;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.hgtv.app.ui.Ui;

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
        mRow.removeAllViews();
        mItems.clear();
    }

    public void add(View v) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = mGap;
        v.setLayoutParams(lp);
        mRow.addView(v, lp);
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
            scrollIntoView(v);
            refreshVisible();
            // 预取右侧 3 项，同时保留 PosterCard 自身的焦点描边/缩放监听。
            int idx = mItems.indexOf(v);
            for (int i = idx + 1; i <= idx + 3 && i < mItems.size(); i++) {
                setActive(mItems.get(i), true);
            }
        };
        if (v instanceof com.hgtv.app.ui.widget.PosterCard) {
            ((com.hgtv.app.ui.widget.PosterCard) v).setOnFocused(onFocused);
        } else {
            v.setOnFocusChangeListener((view, has) -> { if (has) onFocused.run(); });
        }
    }

    /** 让目标卡片出现在视窗 1/5 处；靠近末尾时自动收敛，避免右侧留白过大 */
    private void scrollIntoView(View v) {
        int vx = v.getLeft();
        int vw = v.getWidth();
        if (vw <= 0) {
            post(() -> scrollIntoView(v));
            return;
        }
        int target = vx - getWidth() / 5;
        int max = Math.max(0, mRow.getWidth() - getWidth());
        if (target < 0) target = 0;
        if (target > max) target = max;
        smoothScrollTo(target, 0);
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
