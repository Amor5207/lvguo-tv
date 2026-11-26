package ink.pengfei.lvguo.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.net.Session;
import ink.pengfei.lvguo.ui.LoginActivity;
import ink.pengfei.lvguo.ui.Ui;

/** Optional sign-in prompt allowing immediate guest browsing and playback. */
public final class LoginPrompt {
    private static final String PREF_DISMISSED = "login_prompt_dismissed";

    private LoginPrompt() {}

    public static void showIfFirstVisit(Activity host, Runnable onDismiss) {
        if (host == null || host.isFinishing() || host.isDestroyed() || Session.loggedIn()) return;
        if (App.prefs().getBoolean(PREF_DISMISSED, false)) return;
        show(host, "登录红果账号",
                "无需登录即可直接免费看剧；登录后可同步手机红果的收藏、追剧进度与预约提醒。",
                "暂不登录，直接看",
                true,
                onDismiss);
    }

    public static void showForFeature(Activity host, String reason) {
        if (host == null || host.isFinishing() || host.isDestroyed()) return;
        show(host, "登录红果账号", reason, "暂不登录", false, null);
    }

    private static void show(Activity host, String title, String message, String skipLabel, boolean persistDismiss, Runnable onDismiss) {
        Dialog dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = Ui.column(host);
        Ui.padding(panel, 26, 22, 26, 22);
        panel.setBackground(Ui.roundStroke(host, Ui.SURFACE, 18, Ui.SEPARATOR, 1));

        panel.addView(Ui.text(host, title, 21, Ui.TEXT, true), Ui.lp(-1, -2));

        TextView body = Ui.text(host, message, 15, Ui.TEXT_2, false);
        body.setLineSpacing(Ui.dp(host, 5), 1f);
        LinearLayout.LayoutParams blp = Ui.lp(-1, -2);
        blp.topMargin = Ui.dp(host, 12);
        panel.addView(body, blp);

        LinearLayout actions = Ui.row(host);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = Ui.lp(-1, -2);
        alp.topMargin = Ui.dp(host, 22);
        panel.addView(actions, alp);

        TextView skip = Ui.action(host, skipLabel, -1, false);
        skip.setOnClickListener(v -> {
            if (persistDismiss) App.prefs().edit().putBoolean(PREF_DISMISSED, true).apply();
            dialog.dismiss();
            if (onDismiss != null) onDismiss.run();
        });
        actions.addView(skip, Ui.lp(-2, Ui.dp(host, 44)));

        TextView login = Ui.action(host, "立即登录", -1, true);
        login.setFocusableInTouchMode(true);
        login.setOnClickListener(v -> {
            if (persistDismiss) App.prefs().edit().putBoolean(PREF_DISMISSED, true).apply();
            dialog.dismiss();
            host.startActivity(new Intent(host, LoginActivity.class));
        });
        LinearLayout.LayoutParams llp = Ui.lp(-2, Ui.dp(host, 44));
        llp.leftMargin = Ui.dp(host, 14);
        actions.addView(login, llp);

        dialog.setContentView(panel);
        Ui.popIn(panel);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnCancelListener(d -> {
            if (persistDismiss) App.prefs().edit().putBoolean(PREF_DISMISSED, true).apply();
            if (onDismiss != null) onDismiss.run();
        });
        dialog.setOnKeyListener((d, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK && key != KeyEvent.KEYCODE_ESCAPE && key != KeyEvent.KEYCODE_BUTTON_B) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (persistDismiss) App.prefs().edit().putBoolean(PREF_DISMISSED, true).apply();
                dialog.dismiss();
                if (onDismiss != null) onDismiss.run();
            }
            return true;
        });
        dialog.show();

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(Ui.dp(host, 500), host.getResources().getDisplayMetrics().widthPixels - Ui.dp(host, 40));
            w.setLayout(width, -2);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.65f);
        }
        login.requestFocus();
    }
}
