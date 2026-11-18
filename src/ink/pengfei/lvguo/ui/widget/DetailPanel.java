package ink.pengfei.lvguo.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.ui.IconView;
import ink.pengfei.lvguo.ui.Ui;

/**
 * 设置的二级面板：不是光秃秃的一个按钮，而是「是什么 → 有哪些信息 → 能做什么」。
 * 顶部头像（或图标）+ 标题 + 副标题，中间几行「名称 · 数值」，底部一个整宽操作按钮（可标为危险操作）。
 */
public final class DetailPanel {
    private DetailPanel() {}

    public static final class Spec {
        String avatarUrl, letter, title, subtitle, action;
        int icon = -1;
        boolean danger;
        Runnable onAction;
        final java.util.List<String[]> facts = new java.util.ArrayList<>();
        public Spec avatar(String url, String fallbackLetter) { avatarUrl = url; letter = fallbackLetter; return this; }
        public Spec icon(int iconType) { icon = iconType; return this; }
        public Spec title(String t) { title = t; return this; }
        public Spec subtitle(String s) { subtitle = s; return this; }
        public Spec fact(String label, String value) { facts.add(new String[]{label, value}); return this; }
        public Spec action(String label, boolean isDanger, Runnable run) { action = label; danger = isDanger; onAction = run; return this; }
    }

    public static Dialog show(Activity host, Spec s, View returnFocus) {
        Dialog d = new Dialog(host);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = Ui.column(host);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.padding(panel, 32, 32, 32, 26);
        panel.setBackground(Ui.roundStroke(host, Ui.SURFACE, 22, Ui.SEPARATOR, 1));

        // 头部：头像（真实头像，加载失败显示首字）或图标
        int size = Ui.dp(host, 76);
        RoundFrame badge = new RoundFrame(host, s.avatarUrl != null || s.letter != null ? 38 : 22);
        badge.setBackground(Ui.round(host, s.avatarUrl != null || s.letter != null ? Ui.PRIMARY_DIM : Ui.TINT_PRIMARY, 38));
        if (s.icon >= 0) {
            IconView ic = new IconView(host, s.icon, 34);
            ic.setColor(Ui.ACCENT_TEXT);
            badge.addView(ic, new FrameLayout.LayoutParams(Ui.dp(host, 34), Ui.dp(host, 34), Gravity.CENTER));
        } else {
            TextView letter = Ui.text(host, s.letter == null ? "" : s.letter, 30, 0xFFFFFFFF, true);
            letter.setGravity(Gravity.CENTER);
            badge.addView(letter, new FrameLayout.LayoutParams(-1, -1));
            if (s.avatarUrl != null && s.avatarUrl.startsWith("http")) {
                ImageView img = new ImageView(host);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                badge.addView(img, new FrameLayout.LayoutParams(-1, -1));
                ImageLoader.load(s.avatarUrl, img, size * 2, size * 2);
            }
        }
        panel.addView(badge, Ui.lp(size, size));

        TextView title = Ui.text(host, s.title == null ? "" : s.title, 24, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = Ui.lp(-1, -2); tl.topMargin = Ui.dp(host, 16);
        panel.addView(title, tl);
        if (s.subtitle != null && !s.subtitle.isEmpty()) {
            TextView sub = Ui.text(host, s.subtitle, 14, Ui.TEXT_3, false);
            sub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sl = Ui.lp(-1, -2); sl.topMargin = Ui.dp(host, 6);
            panel.addView(sub, sl);
        }

        // 信息行：浅底圆角块，左名右值，行间极淡分割线
        if (!s.facts.isEmpty()) {
            LinearLayout box = Ui.column(host);
            box.setBackground(Ui.round(host, Ui.FILL_2, 14));
            Ui.padding(box, 18, 4, 18, 4);
            for (int i = 0; i < s.facts.size(); i++) {
                if (i > 0) {
                    View sep = new View(host);
                    sep.setBackgroundColor(Ui.alpha(Ui.TEXT, 0x12));
                    box.addView(sep, Ui.lp(-1, 1));
                }
                LinearLayout row = Ui.row(host);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(Ui.text(host, s.facts.get(i)[0], 15, Ui.TEXT_2, false), Ui.lp(0, -2, 1));
                row.addView(Ui.text(host, s.facts.get(i)[1], 15, Ui.TEXT, true), Ui.lp(-2, -2));
                box.addView(row, Ui.lp(-1, Ui.dp(host, 46)));
            }
            LinearLayout.LayoutParams bl = Ui.lp(-1, -2); bl.topMargin = Ui.dp(host, 22);
            panel.addView(box, bl);
        }

        // 操作：整宽按钮；危险操作聚焦时是红底白字
        TextView act = Ui.text(host, s.action == null ? "关闭" : s.action, 16, s.danger ? 0xFFFF453A : Ui.TEXT, true);
        act.setGravity(Gravity.CENTER);
        act.setFocusable(true);
        Runnable paint = () -> {
            boolean f = act.isFocused();
            int fill = f ? (s.danger ? 0xFFFF453A : Ui.FOCUS_FILL) : Ui.FILL_2;
            act.setBackground(Ui.round(host, fill, 14));
            act.setTextColor(f ? (s.danger ? 0xFFFFFFFF : Ui.ON_FOCUS) : (s.danger ? 0xFFFF453A : Ui.TEXT));
        };
        paint.run();
        act.setOnFocusChangeListener((v, f) -> paint.run());
        act.setOnClickListener(v -> { d.dismiss(); if (s.onAction != null) s.onAction.run(); });
        LinearLayout.LayoutParams al = Ui.lp(-1, Ui.dp(host, 50)); al.topMargin = Ui.dp(host, 22);
        panel.addView(act, al);

        d.setContentView(panel);ink.pengfei.lvguo.ui.Ui.popIn(panel);
        Ui.closeOnBack(d);
        d.setOnDismissListener(x -> { if (returnFocus != null) returnFocus.post(returnFocus::requestFocus); });
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(Ui.dp(host, 440), host.getResources().getDisplayMetrics().widthPixels - Ui.dp(host, 40)), -2);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.55f);
        }
        act.requestFocus();
        return d;
    }
}
