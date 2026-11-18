package ink.pengfei.lvguo.update;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.ui.Ui;

/** 更新相关弹窗：标题 + 说明 + 可选进度条 + 若干按钮。样式与 TvDialog 一致。 */
final class UpdateDialog {
    static final class Btn {
        final String label; final Runnable run; final boolean primary;
        Btn(String label, boolean primary, Runnable run) { this.label = label; this.primary = primary; this.run = run; }
    }

    final Dialog dialog;
    private TextView body;
    private View fill, rest;

    UpdateDialog(Activity host, String title, String message, boolean progress, boolean cancelable, Runnable onClose, Btn... btns) {
        dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = Ui.column(host);
        Ui.padding(panel, 28, 24, 28, 24);
        panel.setBackground(Ui.roundStroke(host, Ui.SURFACE, 16, Ui.SEPARATOR, 1));
        panel.addView(Ui.text(host, title, 22, Ui.TEXT, true), Ui.lp(-1, -2));

        body = Ui.text(host, message == null ? "" : message, 15, Ui.TEXT_2, false);
        body.setMaxLines(progress ? 2 : 9);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bl = Ui.lp(-1, -2); bl.topMargin = Ui.dp(host, 14);
        panel.addView(body, bl);

        if (progress) {
            // 用权重分配宽度：不依赖测量时机，任何时候刷新都对
            LinearLayout track = new LinearLayout(host);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setBackground(Ui.round(host, Ui.FILL, 4));
            fill = new View(host);
            fill.setBackground(Ui.round(host, Ui.PRIMARY, 4));
            rest = new View(host);
            track.addView(fill, new LinearLayout.LayoutParams(0, -1, 0f));
            track.addView(rest, new LinearLayout.LayoutParams(0, -1, 1f));
            LinearLayout.LayoutParams tl = Ui.lp(-1, Ui.dp(host, 8)); tl.topMargin = Ui.dp(host, 18);
            panel.addView(track, tl);
        }

        LinearLayout actions = Ui.row(host);
        actions.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams ap = Ui.lp(-1, -2); ap.topMargin = Ui.dp(host, 24);
        panel.addView(actions, ap);
        TextView focus = null;
        for (int i = 0; i < btns.length; i++) {
            final Btn b = btns[i];
            TextView t = Ui.action(host, b.label, -1, false);
            t.setMinWidth(Ui.dp(host, 124));   // 和「继续观看」弹窗的按钮一样，不要太窄
            t.setOnClickListener(v -> { dialog.dismiss(); if (b.run != null) b.run.run(); });
            LinearLayout.LayoutParams lp = Ui.lp(-2, Ui.dp(host, 46));
            if (i > 0) lp.leftMargin = Ui.dp(host, 18);
            actions.addView(t, lp);
            if (b.primary || focus == null) focus = t;
        }
        dialog.setContentView(panel);
        Ui.popIn(panel);
        dialog.setCanceledOnTouchOutside(false);
        if (cancelable) Ui.closeOnBack(dialog);
        else dialog.setCancelable(false);
        dialog.setOnDismissListener(d -> { if (onClose != null) onClose.run(); });
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(Ui.dp(host, 520), host.getResources().getDisplayMetrics().widthPixels - Ui.dp(host, 40)), -2);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.55f);
        }
        if (focus != null) focus.requestFocus();
    }

    void setProgress(float frac, String text) {
        if (body != null) body.setText(text);
        if (fill != null && rest != null) {
            float f = Math.max(0f, Math.min(1f, frac));
            ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = f;
            ((LinearLayout.LayoutParams) rest.getLayoutParams()).weight = 1f - f;
            ((View) fill.getParent()).requestLayout();
        }
    }

    boolean showing() { return dialog.isShowing(); }
    void dismiss() { try { dialog.dismiss(); } catch (Throwable ignored) { } }
}
