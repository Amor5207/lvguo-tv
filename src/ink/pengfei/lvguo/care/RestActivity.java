package ink.pengfei.lvguo.care;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.ui.IconView;

import java.util.Random;

/**
 * 强制休息页：背景随当前时段变化（清晨 / 白天 / 黄昏 / 夜晚），不跟随应用主题，
 * 安静、没有任何按钮和内容入口。到了结束时间自动回到首页，不续播。
 * 返回键只是把应用退到后台；再打开应用会被入口拦截重新回到这里。
 */
public final class RestActivity extends Activity {
    public static final String EXTRA_AFTER_REST = "after_rest";
    private static final int TEXT = 0xFFFFFFFF, TEXT_2 = 0xCCFFFFFF, TEXT_3 = 0xB3FFFFFF;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView title, sub, countdown, until, range;
    private IconView icon;
    private View glow, track, fill;
    private Backdrop backdrop;
    private int periodShown = -1;

    /** 四个时段的配色：渐变上 / 下色、强调色。 */
    private static final int[][] PALETTE = {
            {0xFF0B1022, 0xFF05070D, 0xFFF4E6BE},   // 0 夜晚
            {0xFF2A3560, 0xFFC1735F, 0xFFFFE2B8},   // 1 清晨
            {0xFF1D4F8F, 0xFF62A7D8, 0xFFFFF0C4},   // 2 白天
            {0xFF2B2557, 0xFFC95B4B, 0xFFFFD8A6},   // 3 黄昏
    };

    private static final String[] TITLES = {"晚安", "早安", "歇一歇", "傍晚好"};
    private static final String[] SUBS = {"明天再继续吧", "先放松一会儿", "让眼睛放松一下", "先休息一会儿"};

    /** 当前所处时段：5–8 清晨，8–17 白天，17–20 黄昏，其余夜晚。 */
    static int period(long now) {
        int h = RestMode.minuteOfDay(now) / 60;
        return h >= 5 && h < 8 ? 1 : h >= 8 && h < 17 ? 2 : h >= 17 && h < 20 ? 3 : 0;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ink.pengfei.lvguo.ui.Ui.adaptScreen(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF080B14));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        FrameLayout root = new FrameLayout(this);
        backdrop = new Backdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout badge = new FrameLayout(this);
        glow = new View(this);
        badge.addView(glow, new FrameLayout.LayoutParams(dp(170), dp(170), Gravity.CENTER));
        icon = new IconView(this, IconView.SUN, 72);
        badge.addView(icon, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        body.addView(badge, new LinearLayout.LayoutParams(dp(170), dp(170)));

        title = text("", 32, TEXT, true);
        LinearLayout.LayoutParams tl = lp(); tl.topMargin = dp(-8);
        body.addView(title, tl);

        sub = text("", 18, TEXT_2, false);
        LinearLayout.LayoutParams sbl = lp(); sbl.topMargin = dp(10);
        body.addView(sub, sbl);

        countdown = text("", 68, TEXT, true);
        countdown.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams cl = lp(); cl.topMargin = dp(24);
        body.addView(countdown, cl);

        FrameLayout bar = new FrameLayout(this);
        track = new View(this); track.setBackground(pill(0x33FFFFFF));
        bar.addView(track, new FrameLayout.LayoutParams(-1, -1));
        fill = new View(this);
        bar.addView(fill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(360), dp(5)); bl.topMargin = dp(22);
        body.addView(bar, bl);

        until = text("", 20, TEXT_2, false);
        LinearLayout.LayoutParams ul = lp(); ul.topMargin = dp(22);
        body.addView(until, ul);

        range = text("", 15, TEXT_3, false);
        LinearLayout.LayoutParams rl = lp(); rl.topMargin = dp(10);
        body.addView(range, rl);

        root.addView(body, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
    }

    private static GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(1000f);
        return g;
    }

    @Override protected void onResume() {
        super.onResume();
        ink.pengfei.lvguo.ui.Ui.enforceLandscape(this);
        ui.removeCallbacks(check);
        ui.post(check);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(check);
        super.onPause();
    }

    private void applyPeriod(int p) {
        periodShown = p;
        int[] c = PALETTE[p];
        backdrop.set(c[0], c[1], p == 0);
        icon.setType(p == 0 ? IconView.MOON : IconView.SUN);
        icon.setFilled(p == 0);
        icon.setColor(c[2]);
        countdown.setTextColor(c[2]);
        title.setText(TITLES[p]);
        sub.setText(SUBS[p]);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(85));
        g.setColors(new int[]{(c[2] & 0x00FFFFFF) | 0x40000000, c[2] & 0x00FFFFFF});
        glow.setBackground(g);
        fill.setBackground(pill(c[2]));
    }

    /** 每秒刷新：休息结束就回首页（不续播），否则更新倒计时、进度与时段配色。 */
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            long now = System.currentTimeMillis();
            if (!RestMode.isResting()) { leave(); return; }
            int p = period(now);
            if (p != periodShown) applyPeriod(p);
            range.setText("休息时段 " + RestMode.windowLabel());
            long end = RestMode.restEndsAt();
            if (end > 0) {
                long left = Math.max(0, end - now), s = (left + 999) / 1000;
                countdown.setText(String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60));
                java.util.Calendar e = java.util.Calendar.getInstance(), n = java.util.Calendar.getInstance();
                e.setTimeInMillis(end); n.setTimeInMillis(now);
                boolean nextDay = e.get(java.util.Calendar.DAY_OF_YEAR) != n.get(java.util.Calendar.DAY_OF_YEAR);
                until.setText((nextDay ? "次日 " : "") + RestMode.label(RestMode.minuteOfDay(end)) + " 恢复观看");
                int a = RestMode.startMinute(), b = RestMode.endMinute();
                long total = (((b - a) % 1440 + 1440) % 1440) * 60_000L;
                float done = total <= 0 ? 0f : Math.max(0f, Math.min(1f, 1f - (float) left / total));
                FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) fill.getLayoutParams();
                int w = Math.round(dp(360) * done);
                if (fp.width != w) { fp.width = w; fill.setLayoutParams(fp); }
            }
            ui.postDelayed(this, 1000L);
        }
    };

    private void leave() {
        Intent home = new Intent(this, ink.pengfei.lvguo.ui.RootActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_AFTER_REST, true);
        startActivity(home);
        finish();
        overridePendingTransition(0, 0);
    }

    /** 方向键 / 确定键等都不做任何事；返回键把应用退到后台（再打开仍会回到这里）。 */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { moveTaskToBack(true); return true; }
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_POWER
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)
            return super.onKeyDown(keyCode, event);
        return true;
    }
    @Override public void onBackPressed() { moveTaskToBack(true); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD));
        return t;
    }
    private LinearLayout.LayoutParams lp() { return new LinearLayout.LayoutParams(-2, -2); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** 背景：时段渐变；夜晚额外铺稀疏星点（固定随机种子，不闪）。 */
    private static final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int top = 0xFF0B1022, bottom = 0xFF05070D;
        private boolean stars = true;
        Backdrop(Activity a) { super(a); }
        void set(int top, int bottom, boolean stars) { this.top = top; this.bottom = bottom; this.stars = stars; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            paint.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            if (!stars) return;
            Random r = new Random(20261103L);
            float d = getResources().getDisplayMetrics().density;
            for (int i = 0; i < 70; i++) {
                float x = r.nextFloat() * w, y = r.nextFloat() * h * 0.85f, rad = (0.6f + r.nextFloat() * 1.1f) * d;
                paint.setColor(Color.argb(40 + r.nextInt(90), 230, 235, 255));
                c.drawCircle(x, y, rad, paint);
            }
        }
    }
}
