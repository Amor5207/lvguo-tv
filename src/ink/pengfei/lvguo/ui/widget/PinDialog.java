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
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.ui.Ui;

/**
 * 数字密码输入框（遥控器友好）：方向键在屏幕小键盘上选择，也可以直接按遥控器数字键。
 * 样式与 {@link TvDialog} 一致。输入满 maxLen 位或按「确定」时回调。
 */
public final class PinDialog {
    private PinDialog() {}

    public interface Done { void done(String pin); }

    public static Dialog show(Activity host, String title, String hint, int minLen, int maxLen, View returnFocus, Done done) {
        Dialog d = new Dialog(host);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = Ui.column(host);
        Ui.padding(panel, 28, 24, 28, 22);
        panel.setBackground(Ui.roundStroke(host, Ui.SURFACE, 18, Ui.SEPARATOR, 1));
        panel.addView(Ui.text(host, title, 21, Ui.TEXT, true), Ui.lp(-1, -2));
        TextView hintView = Ui.text(host, hint == null ? "" : hint, 13, Ui.TEXT_3, false);
        LinearLayout.LayoutParams hl = Ui.lp(-1, -2); hl.topMargin = Ui.dp(host, 6);
        panel.addView(hintView, hl);

        // 输入位：已输入显示圆点
        final StringBuilder value = new StringBuilder();
        LinearLayout dots = Ui.row(host);
        dots.setGravity(Gravity.CENTER);
        final TextView[] slots = new TextView[maxLen];
        for (int i = 0; i < maxLen; i++) {
            TextView s = Ui.text(host, "", 22, Ui.TEXT, true);
            s.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sl = Ui.lp(Ui.dp(host, 40), Ui.dp(host, 48));
            if (i > 0) sl.leftMargin = Ui.dp(host, 10);
            dots.addView(s, sl);
            slots[i] = s;
        }
        LinearLayout.LayoutParams dl = Ui.lp(-1, -2); dl.topMargin = Ui.dp(host, 18);
        panel.addView(dots, dl);

        final Runnable paintSlots = () -> {
            for (int i = 0; i < slots.length; i++) {
                boolean filled = i < value.length();
                boolean optional = i >= minLen;
                slots[i].setText(filled ? "●" : "");
                slots[i].setBackground(Ui.roundStroke(host, Ui.FILL, 10, filled ? Ui.PRIMARY : optional ? 0x00000000 : Ui.SEPARATOR, 1));
            }
        };
        paintSlots.run();

        final Runnable submit = () -> {
            if (value.length() < minLen) { hintView.setText("至少输入 " + minLen + " 位数字"); return; }
            d.dismiss();
            done.done(value.toString());
        };

        // 小键盘：1-9、删除、0、确定
        GridLayout pad = new GridLayout(host);
        pad.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "删除", "0", "确定"};
        View first = null;
        for (String k : keys) {
            final String key = k;
            TextView b = Ui.text(host, k, k.length() > 1 ? 16 : 22, Ui.TEXT, k.length() == 1);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(true);
            Runnable paint = () -> {
                boolean f = b.hasFocus();
                b.setBackground(Ui.round(host, f ? Ui.FOCUS_FILL : Ui.FILL, 12));
                b.setTextColor(f ? Ui.ON_FOCUS : Ui.TEXT);
            };
            paint.run();
            b.setOnFocusChangeListener((v, f) -> paint.run());
            b.setOnClickListener(v -> {
                if ("删除".equals(key)) { if (value.length() > 0) value.setLength(value.length() - 1); paintSlots.run(); }
                else if ("确定".equals(key)) submit.run();
                else if (value.length() < maxLen) {
                    value.append(key); paintSlots.run();
                    if (value.length() == maxLen) submit.run();
                }
            });
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
            gl.width = Ui.dp(host, 96); gl.height = Ui.dp(host, 52);
            int m = Ui.dp(host, 5);
            gl.setMargins(m, m, m, m);
            pad.addView(b, gl);
            if (first == null) first = b;
        }
        LinearLayout.LayoutParams pl = Ui.lp(-2, -2); pl.topMargin = Ui.dp(host, 18); pl.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(pad, pl);

        // 遥控器数字键 / 删除键直接输入；部分遥控器的返回键发的是 ESC / 手柄 B，一并当返回处理
        d.setOnKeyListener((dialog, code, e) -> {
            if (code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_B) {
                if (e.getAction() == KeyEvent.ACTION_UP) d.dismiss();
                return true;
            }
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                if (value.length() < maxLen) {
                    value.append((char) ('0' + code - KeyEvent.KEYCODE_0)); paintSlots.run();
                    if (value.length() == maxLen) submit.run();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DEL) { if (value.length() > 0) value.setLength(value.length() - 1); paintSlots.run(); return true; }
            return false;
        });

        d.setContentView(panel);
        Ui.popIn(panel);
        d.setCanceledOnTouchOutside(false);   // 返回键走 Dialog 默认的取消
        d.setOnDismissListener(x -> { if (returnFocus != null) returnFocus.post(returnFocus::requestFocus); });
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(Ui.dp(host, 440), host.getResources().getDisplayMetrics().widthPixels - Ui.dp(host, 40)), -2);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.55f);
        }
        if (first != null) first.requestFocus();
        return d;
    }
}
