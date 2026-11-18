package ink.pengfei.lvguo.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.ui.IconView;
import ink.pengfei.lvguo.ui.Ui;

/**
 * 时间段选择：开始 / 结束两组「时:分」同屏调整。左右键在四栏间切换，上下键加减，
 * 数字键直接输入（输满自动跳到下一栏）。样式与 {@link TimeDialog} 一致。
 */
public final class TimeRangeDialog {
    private TimeRangeDialog() {}

    public interface Pick { void pick(int startMinute, int endMinute); }

    public static Dialog show(Activity host, String title, String hint, int start, int end, View returnFocus, Pick pick) {
        Dialog d = new Dialog(host);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = Ui.column(host);
        Ui.padding(panel, 28, 24, 28, 22);
        panel.setBackground(Ui.roundStroke(host, Ui.SURFACE, 18, Ui.SEPARATOR, 1));
        panel.addView(Ui.text(host, title, 21, Ui.TEXT, true), Ui.lp(-1, -2));
        TextView hintView = Ui.text(host, hint, 13, Ui.TEXT_3, false);
        LinearLayout.LayoutParams hl = Ui.lp(-1, -2); hl.topMargin = Ui.dp(host, 6);
        panel.addView(hintView, hl);

        start = ((start % 1440) + 1440) % 1440; end = ((end % 1440) + 1440) % 1440;
        final int[] v = {start / 60, start % 60, end / 60, end % 60};
        final int[] typed = {-1, -1, -1, -1};
        final TextView[] boxes = new TextView[4];
        final IconView[][] arrows = new IconView[4][2];

        LinearLayout row = Ui.row(host);
        row.setGravity(Gravity.CENTER);
        for (int g = 0; g < 2; g++) {
            LinearLayout group = Ui.column(host);
            group.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView cap = Ui.text(host, g == 0 ? "开始" : "结束", 14, Ui.TEXT_2, true);
            cap.setGravity(Gravity.CENTER);
            group.addView(cap, Ui.lp(-1, -2));
            LinearLayout pair = Ui.row(host);
            pair.setGravity(Gravity.CENTER);
            for (int k = 0; k < 2; k++) {
                int c = g * 2 + k;
                if (k == 1) {
                    TextView colon = Ui.text(host, ":", 30, Ui.TEXT_2, true);
                    colon.setGravity(Gravity.CENTER);
                    pair.addView(colon, Ui.lp(Ui.dp(host, 14), -2));
                }
                LinearLayout col = Ui.column(host);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                IconView up = new IconView(host, IconView.CHEVRON_RIGHT, 18);
                up.setRotation(-90);
                col.addView(up, Ui.lp(Ui.dp(host, 18), Ui.dp(host, 18)));
                TextView box = Ui.text(host, "", 32, Ui.TEXT, true);
                box.setGravity(Gravity.CENTER);
                box.setFocusable(true);
                box.setFontFeatureSettings("tnum");
                LinearLayout.LayoutParams bl = Ui.lp(Ui.dp(host, 64), Ui.dp(host, 64));
                bl.topMargin = Ui.dp(host, 4); bl.bottomMargin = Ui.dp(host, 4);
                col.addView(box, bl);
                IconView down = new IconView(host, IconView.CHEVRON_RIGHT, 18);
                down.setRotation(90);
                col.addView(down, Ui.lp(Ui.dp(host, 18), Ui.dp(host, 18)));
                pair.addView(col, Ui.lp(-2, -2));
                boxes[c] = box; arrows[c][0] = up; arrows[c][1] = down;
            }
            group.addView(pair, Ui.lp(-2, -2));
            row.addView(group, Ui.lp(-2, -2));
            if (g == 0) {
                TextView dash = Ui.text(host, "~", 30, Ui.TEXT_2, true);
                dash.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams dl = Ui.lp(Ui.dp(host, 36), -2); dl.topMargin = Ui.dp(host, 4);
                row.addView(dash, dl);
            }
        }
        LinearLayout.LayoutParams rl = Ui.lp(-1, -2); rl.topMargin = Ui.dp(host, 18);
        panel.addView(row, rl);

        final TextView summary = Ui.text(host, "", 14, Ui.TEXT_2, false);
        summary.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = Ui.lp(-1, -2); sl.topMargin = Ui.dp(host, 10);
        panel.addView(summary, sl);

        final Runnable paint = () -> {
            for (int c = 0; c < 4; c++) {
                boolean f = boxes[c].hasFocus();
                boxes[c].setText(typed[c] >= 0 ? String.valueOf(typed[c]) : String.format(java.util.Locale.US, "%02d", v[c]));
                boxes[c].setBackground(Ui.round(host, f ? Ui.FOCUS_FILL : Ui.FILL, 14));
                boxes[c].setTextColor(f ? Ui.ON_FOCUS : Ui.TEXT);
                int arrow = f ? Ui.TEXT_2 : 0x00000000;
                arrows[c][0].setColor(arrow); arrows[c][1].setColor(arrow);
            }
            int s = v[0] * 60 + v[1], e = v[2] * 60 + v[3];
            if (s == e) summary.setText("开始与结束时间不能相同");
            else {
                int len = ((e - s) % 1440 + 1440) % 1440;
                summary.setText((e < s ? "次日结束 · " : "当日结束 · ") + "共 " + (len / 60 > 0 ? len / 60 + " 小时" : "")
                        + (len % 60 > 0 ? (len / 60 > 0 ? " " : "") + len % 60 + " 分钟" : ""));
            }
        };

        LinearLayout actions = Ui.row(host);
        actions.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams ap = Ui.lp(-1, -2); ap.topMargin = Ui.dp(host, 20);
        panel.addView(actions, ap);
        TextView cancel = Ui.action(host, "取消", -1, false);
        cancel.setOnClickListener(x -> d.dismiss());
        actions.addView(cancel, Ui.lp(-2, Ui.dp(host, 46)));
        final TextView ok = Ui.action(host, "确定", -1, false);
        ok.setOnClickListener(x -> {
            int s = v[0] * 60 + v[1], e = v[2] * 60 + v[3];
            if (s == e) { paint.run(); return; }
            d.dismiss(); pick.pick(s, e);
        });
        LinearLayout.LayoutParams op = Ui.lp(-2, Ui.dp(host, 46)); op.leftMargin = Ui.dp(host, 18);
        actions.addView(ok, op);

        for (int col = 0; col < 4; col++) {
            final int c = col, max = col % 2 == 0 ? 24 : 60;
            boxes[c].setOnFocusChangeListener((x, f) -> { typed[c] = -1; paint.run(); });
            boxes[c].setOnKeyListener((x, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int dir = code == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1;
                    typed[c] = -1;
                    if (c % 2 == 1 && e.getRepeatCount() >= 3) {
                        int next = dir > 0 ? (v[c] / 5 + 1) * 5 : ((v[c] + 4) / 5 - 1) * 5;
                        v[c] = ((next % 60) + 60) % 60;
                    } else v[c] = ((v[c] + dir) % max + max) % max;
                    paint.run();
                    return true;
                }
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    int digit = code - KeyEvent.KEYCODE_0, firstMax = c % 2 == 0 ? 2 : 5;
                    if (typed[c] < 0) {
                        if (digit > firstMax) { v[c] = digit; typed[c] = -1; next(c, boxes, ok); }
                        else typed[c] = digit;
                    } else {
                        v[c] = Math.min(max - 1, typed[c] * 10 + digit); typed[c] = -1; next(c, boxes, ok);
                    }
                    paint.run();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (typed[c] >= 0) { v[c] = typed[c]; typed[c] = -1; paint.run(); }
                    next(c, boxes, ok);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && c < 3) { boxes[c + 1].requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && c > 0) { boxes[c - 1].requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && c == 0) return true;
                return false;
            });
        }
        paint.run();

        d.setContentView(panel);
        Ui.popIn(panel);
        d.setCanceledOnTouchOutside(false);
        Ui.closeOnBack(d);
        d.setOnDismissListener(x -> { if (returnFocus != null) returnFocus.post(returnFocus::requestFocus); });
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(Ui.dp(host, 520), host.getResources().getDisplayMetrics().widthPixels - Ui.dp(host, 40)), -2);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.55f);
        }
        boxes[0].requestFocus();
        return d;
    }

    private static void next(int c, TextView[] boxes, View ok) { if (c < 3) boxes[c + 1].requestFocus(); else ok.requestFocus(); }
}
