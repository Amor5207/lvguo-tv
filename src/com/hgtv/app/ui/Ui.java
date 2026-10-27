package com.hgtv.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设计系统：颜色 / 尺寸 / 形状 / 文字 / 聚焦动效。
 *
 * TV 端采用 dp 布局，卡片随可用宽度调整，保持遥控器焦点清晰可见。
 * 所有颜色集中在这里，避免散落硬编码。
 */
public final class Ui {
    /** Consume the entire BACK gesture inside the top dialog, including key repeats. */
    public static void closeOnBack(android.app.Dialog dialog){
        final boolean[] pressed={false};
        final long openedAt=android.os.SystemClock.uptimeMillis();
        dialog.setOnKeyListener((d,key,event)->{
            if(key!=android.view.KeyEvent.KEYCODE_BACK)return false;
            // An event from the gesture that opened this dialog must not close it.
            if(event.getDownTime()<openedAt)return true;
            if(event.getAction()==android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)pressed[0]=true;
            if(event.getAction()==android.view.KeyEvent.ACTION_UP){
                boolean close=pressed[0] && !event.isCanceled();pressed[0]=false;
                if(close)dialog.dismiss();
            }
            return true;
        });
    }

    // ---- 短剧客厅视觉：炭黑底色 + 浅绿色高亮
    public static final int BG = 0xFF101313;
    public static final int BG_RAIL = 0xFF101313;
    public static final int SURFACE = 0xFF1B2020;
    public static final int SURFACE_2 = 0xFF252C2A;
    public static final int SURFACE_3 = 0xFF333D38;

    // ---- 强调色
    public static final int PRIMARY = 0xFFC6ED92;
    public static final int PRIMARY_DIM = 0xFF9CBF6D;
    public static final int TINT_PRIMARY = 0x24C5ED8A;
    public static final int ACCENT = 0xFFB8D9CE;
    public static final int GOLD = 0xFFE8B65A;         // 仅用于榜单名次

    // ---- 文字
    public static final int TEXT = 0xFFF5F7F8;
    public static final int TEXT_2 = 0xFFBBC5CE;
    public static final int TEXT_3 = 0xFF94A1AD;
    public static final int TEXT_4 = 0xFF83919E;

    // ---- 尺寸
    public static final int RAIL_W = 160;
    public static final int CARD_W = 148;
    public static final int CARD_H = 204;

    private Ui() {}

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int dp(View v, float val) {
        return dp(v.getContext(), val);
    }

    // ------------------------------------------------------------ 形状

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(Context c, int color, float radiusDp,
                                               int strokeColor, float strokeDp) {
        GradientDrawable d = round(c, color, radiusDp);
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    public static GradientDrawable focusRing(Context c, boolean hot) {
        // 深色底 + 高亮描边；不加高光的纯色更耐看
        return roundStroke(c, hot ? SURFACE_2 : 0x00000000, 12,
                hot ? PRIMARY : 0x00000000, 2);
    }

    /** 顶部到底部的黑色渐变遮罩（用于 Hero 文字可读性） */
    public static GradientDrawable scrimBottom(Context c) {
        return new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF20B0E11, 0xCC0B0E11, 0x400B0E11, 0x000B0E11});
    }

    public static GradientDrawable scrimLeft(Context c) {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF20B0E11, 0xE00B0E11, 0x800B0E11, 0x000B0E11});
    }

    public static GradientDrawable pill(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, 100));
        return d;
    }

    // ------------------------------------------------------------ 文字

    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s == null ? "" : s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(android.graphics.Typeface.create("sans-serif",bold?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLineSpacing(dp(c, 5), 1f);
        return t;
    }

    public static TextView ellipsis(Context c, String s, float sizeSp, int color, int maxLines) {
        TextView t = text(c, s, sizeSp, color, false);
        t.setMaxLines(maxLines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static void icon(TextView view, int type, int color, int size) {
        IconDrawable drawable = new IconDrawable(type, color);
        int px = dp(view, size);
        drawable.setBounds(0, 0, px, px);
        view.setCompoundDrawables(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(view, 8));
    }

    /** Keep a button's pictogram and label in the same foreground color in every state. */
    private static final class ActionText extends TextView {
        ActionText(Context context){super(context);}
        @Override public void setTextColor(int color){super.setTextColor(color);tintIcons(this,color);}
        @Override public void setCompoundDrawables(android.graphics.drawable.Drawable left,android.graphics.drawable.Drawable top,android.graphics.drawable.Drawable right,android.graphics.drawable.Drawable bottom){
            super.setCompoundDrawables(left,top,right,bottom);tintIcons(this,getCurrentTextColor());
        }
    }
    public static void tintIcons(TextView view,int color){
        for(android.graphics.drawable.Drawable drawable:view.getCompoundDrawables())if(drawable instanceof IconDrawable)((IconDrawable)drawable).setColor(color);
    }
    public static void buttonState(TextView view,boolean focused,boolean selected){
        view.setBackground(roundStroke(view.getContext(),focused?TINT_PRIMARY:0x00000000,6,
            focused||selected?PRIMARY:0x00000000,focused?2:selected?1:0));
        int foreground=focused||selected?PRIMARY:TEXT_2;
        view.setTextColor(foreground);tintIcons(view,foreground);
    }

    public static TextView action(Context c, String label, int type, boolean primary) {
        TextView view = new ActionText(c);view.setText(label);view.setTextSize(14);view.setIncludeFontPadding(false);
        view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        view.setSingleLine(true);view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setMinHeight(dp(c, 44));
        padding(view, 16, 0, 16, 0);
        if(type>=0)icon(view, type, TEXT, 18);else view.setCompoundDrawables(null,null,null,null);
        buttonState(view,false,false);
        view.setOnFocusChangeListener((v, has) -> {
            buttonState(view,has,false);
        });
        return view;
    }

    // ------------------------------------------------------------ 聚焦动效

    /** 统一的聚焦反馈：轻微放大 + 描边。用 1.06 而不是 1.1，避免相邻卡片互相压边。 */
    public static void focus(View v, boolean has, float scale) {
        v.animate().cancel();
        v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                .setDuration(has ? 150 : 120)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    // ------------------------------------------------------------ 常用容器

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View spacer(Context c) {
        return new View(c);
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    /** 加一层点击/聚焦都无反馈的透明层（用于占位） */
    public static View blank(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
    }

    public static void hide(View v) {
        if (v != null) v.setVisibility(View.GONE);
    }

    public static void show(View v) {
        if (v != null) v.setVisibility(View.VISIBLE);
    }

    public static void padding(View v, float l, float t, float r, float b) {
        v.setPadding(dp(v, l), dp(v, t), dp(v, r), dp(v, b));
    }

    public static void margin(View v, float l, float t, float r, float b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) p;
            m.setMargins(dp(v, l), dp(v, t), dp(v, r), dp(v, b));
            v.setLayoutParams(m);
        }
    }
}
