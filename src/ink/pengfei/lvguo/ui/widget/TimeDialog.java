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
 * 时间选择（任意时分，遥控器友好）：时 / 分两栏，上下键加减（按住时分钟每次跳 5），
 * 左右键切换栏；也可以直接按数字键，依次输入「2 3 3 0」即 23:30。样式与 {@link TvDialog} 一致。
 */
public final class TimeDialog {
    private TimeDialog() {}

    public interface Pick { void pick(int minuteOfDay); }

    public static Dialog show(Activity host, String title, String hint, int minuteOfDay, View returnFocus, Pick pick) {
        Dialog d = new Dialog(host);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = Ui.column(host);
        Ui.padding(panel, 28, 24, 28, 22);
        panel.setBackground(Ui.roundStroke(host, Ui.SURFACE, 18, Ui.SEPARATOR, 1));
        panel.addView(Ui.text(host, title, 21, Ui.TEXT, true), Ui.lp(-1, -2));
        TextView hintView = Ui.text(host, hint == null || hint.isEmpty() ? "上下键调整，左右键切换时 / 分；也可以直接按数字键" : hint, 13, Ui.TEXT_3, false);
        LinearLayout.LayoutParams hl = Ui.lp(-1, -2); hl.topMargin = Ui.dp(host, 6);
        panel.addView(hintView, hl);

        final int[] value = {((minuteOfDay % 1440) + 1440) % 1440 / 60, ((minuteOfDay % 1440) + 1440) % 1440 % 60};
        final int[] typed = {-1, -1};   // 正在用数字键输入的值（-1 = 没在输入）

        LinearLayout row = Ui.row(host);
        row.setGravity(Gravity.CENTER);
        final TextView[] boxes = new TextView[2];
        final IconView[][] arrows = new IconView[2][2];
        for (int col = 0; col < 2; col++) {
            if (col == 1) {
                TextView colon = Ui.text(host, ":", 44, Ui.TEXT_2, true);
                colon.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams cl = Ui.lp(Ui.dp(host, 40), -2);
                row.addView(colon, cl);
            }
            LinearLayout column = Ui.column(host);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            IconView up = new IconView(host, IconView.CHEVRON_RIGHT, 20);
            up.setRotation(-90);
            column.addView(up, Ui.lp(Ui.dp(host, 20), Ui.dp(host, 20)));
            TextView box = Ui.text(host, "", 44, Ui.TEXT, true);
            box.setGravity(Gravity.CENTER);
            box.setFocusable(true);
            box.setFontFeatureSettings("tnum");
            LinearLayout.LayoutParams bl = Ui.lp(Ui.dp(host, 116), Ui.dp(host, 84));
            bl.topMargin = Ui.dp(host, 6); bl.bottomMargin = Ui.dp(host, 6);
            column.addView(box, bl);
            IconView down = new IconView(host, IconView.CHEVRON_RIGHT, 20);
            down.setRotation(90);
            column.addView(down, Ui.lp(Ui.dp(host, 20), Ui.dp(host, 20)));
            row.addView(column, Ui.lp(-2, -2));
            boxes[col] = box;
            arrows[col][0] = up; arrows[col][1] = down;
        }
        LinearLayout.LayoutParams rl = Ui.lp(-1, -2); rl.topMargin = Ui.dp(host, 20);
        panel.addView(row, rl);

        final Runnable paint = () -> {
            for (int c = 0; c < 2; c++) {
                boolean f = boxes[c].hasFocus();
                String text = typed[c] >= 0 ? String.valueOf(typed[c]) : String.format(java.util.Locale.US, "%02d", value[c]);
                boxes[c].setText(text);
                boxes[c].setBackground(Ui.round(host, f ? Ui.FOCUS_FILL : Ui.FILL, 16));
                boxes[c].setTextColor(f ? Ui.ON_FOCUS : Ui.TEXT);
                int arrow = f ? Ui.TEXT_2 : 0x00000000;
                arrows[c][0].setColor(arrow); arrows[c][1].setColor(arrow);
            }
        };

        // 底部按钮
        LinearLayout actions = Ui.row(host);
        actions.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams ap = Ui.lp(-1, -2); ap.topMargin = Ui.dp(host, 24);
        panel.addView(actions, ap);
        TextView cancel = Ui.action(host, "取消", -1, false);
        cancel.setOnClickListener(v -> d.dismiss());
        actions.addView(cancel, Ui.lp(-2, Ui.dp(host, 46)));
        final TextView ok = Ui.action(host, "确定", -1, false);
        ok.setOnClickListener(v -> { d.dismiss(); pick.pick(value[0] * 60 + value[1]); });
        LinearLayout.LayoutParams op = Ui.lp(-2, Ui.dp(host, 46)); op.leftMargin = Ui.dp(host, 18);
        actions.addView(ok, op);

        for (int col = 0; col < 2; col++) {
            final int c = col, max = col == 0 ? 24 : 60;
            boxes[c].setOnFocusChangeListener((v, f) -> { typed[c] = -1; paint.run(); });
            boxes[c].setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int dir = code == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1;
                    typed[c] = -1;
                    if (c == 1 && e.getRepeatCount() >= 3) {   // 按住：分钟按 5 分钟对齐跳
                        int next = dir > 0 ? (value[1] / 5 + 1) * 5 : ((value[1] + 4) / 5 - 1) * 5;
                        value[1] = ((next % 60) + 60) % 60;
                    } else value[c] = ((value[c] + dir) % max + max) % max;
                    paint.run();
                    return true;
                }
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    int digit = code - KeyEvent.KEYCODE_0;
                    int firstMax = c == 0 ? 2 : 5;   // 小时首位最大 2，分钟首位最大 5
                    if (typed[c] < 0) {
                        if (digit > firstMax) { value[c] = digit; typed[c] = -1; advance(c, boxes, ok); }
                        else typed[c] = digit;
                    } else {
                        value[c] = Math.min(max - 1, typed[c] * 10 + digit);
                        typed[c] = -1;
                        advance(c, boxes, ok);
                    }
                    paint.run();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (typed[c] >= 0) { value[c] = typed[c]; typed[c] = -1; paint.run(); }
                    advance(c, boxes, ok);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && c == 0) { boxes[1].requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && c == 1) { boxes[0].requestFocus(); return true; }
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
            w.setLayout(Math.min(Ui.dp(host, 440), host.getResources().getDisplayMetrics().widthPixels - Ui.dp(host, 40)), -2);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.55f);
        }
        boxes[0].requestFocus();
        return d;
    }

    /** 时栏输完跳到分栏，分栏输完落到「确定」。 */
    private static void advance(int col, TextView[] boxes, View ok) {
        if (col == 0) boxes[1].requestFocus(); else ok.requestFocus();
    }
}
