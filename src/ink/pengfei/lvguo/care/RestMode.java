package ink.pengfei.lvguo.care;

import android.app.Activity;
import android.content.Intent;

import ink.pengfei.lvguo.App;

import java.util.Calendar;

/**
 * 强制休息：在用户设定的时间段内整个应用不可用。
 *
 * 只按「当前本地时间是否落在时间段内」判断，不存任何「已跳过」「已延长」之类的标志，
 * 所以关掉应用重开、重启电视都绕不过去；到了结束时间自然解除。
 * 拦截点：应用入口（LoginActivity / RootActivity）、播放器、手机遥控服务，见 {@link #enforce}。
 */
public final class RestMode {
    private RestMode() {}

    static final String K_ON = "rest_on", K_START = "rest_start", K_END = "rest_end", K_WARN = "rest_warn_s";
    /** 默认 23:30 至次日 07:00。 */
    static final int DEFAULT_START = 23 * 60 + 30, DEFAULT_END = 7 * 60, DEFAULT_WARN = 60;

    /** 休息前提示时长（秒）：开始提示一次，随后左上角只显示剩余秒数。 */
    public static final int[] WARN_OPTIONS = {60, 30, 15};

    public static boolean enabled() { return App.prefs().getBoolean(K_ON, false); }
    public static int startMinute() { return App.prefs().getInt(K_START, DEFAULT_START); }
    public static int endMinute() { return App.prefs().getInt(K_END, DEFAULT_END); }
    public static int warnSeconds() { return App.prefs().getInt(K_WARN, DEFAULT_WARN); }
    public static String warnLabel(int seconds) { return seconds % 60 == 0 ? seconds / 60 + " 分钟" : seconds + " 秒"; }
    public static void setWarn(int seconds) { App.prefs().edit().putInt(K_WARN, seconds).commit(); }

    public static void setEnabled(boolean on) { App.prefs().edit().putBoolean(K_ON, on).commit(); }
    public static void setStart(int minute) { App.prefs().edit().putInt(K_START, wrap(minute)).commit(); }
    public static void setEnd(int minute) { App.prefs().edit().putInt(K_END, wrap(minute)).commit(); }
    private static int wrap(int minute) { return ((minute % 1440) + 1440) % 1440; }

    /** 「23:30」这样的显示。 */
    public static String label(int minute) {
        minute = ((minute % 1440) + 1440) % 1440;
        return String.format(java.util.Locale.US, "%02d:%02d", minute / 60, minute % 60);
    }

    /** 时间段说明，例如「23:30 至次日 07:00」。 */
    public static String windowLabel() {
        int s = startMinute(), e = endMinute();
        return label(s) + " ~ " + (e <= s ? "次日 " : "") + label(e);
    }

    /** 设置页一行显示：「23:30 – 次日 07:00 · 共 7 小时 30 分」。 */
    public static String rangeLabel() {
        int s = startMinute(), e = endMinute();
        int len = ((e - s) % 1440 + 1440) % 1440;
        String dur = (len / 60 > 0 ? len / 60 + " 小时" : "") + (len % 60 > 0 ? (len / 60 > 0 ? " " : "") + len % 60 + " 分" : "");
        return windowLabel() + " · 共 " + dur;
    }

    // ------------------------------------------------------------ 纯计算（单元测试覆盖）

    /** 一天中的第几分钟（0..1439）落在 [start, end) 内吗？start > end 表示跨午夜；start == end 视为不生效。 */
    static boolean inWindow(int minute, int start, int end) {
        if (start == end) return false;
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }

    /** 从 now 起，下一次到达「一天中的第 target 分钟」的时间戳（正好就是现在时返回 now 所在这一分钟的起点）。 */
    static long nextAt(long now, int target) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, target / 60);
        c.set(Calendar.MINUTE, target % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now - 60_000L) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    static int minuteOfDay(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    static boolean isResting(long now, boolean on, int start, int end) {
        return on && inWindow(minuteOfDay(now), start, end);
    }

    /**
     * 距离进入休息还有多少毫秒：只在预警窗口内（0 &lt; 剩余 ≤ warn 分钟）返回剩余毫秒，
     * 已经在休息中返回 0，其它情况返回 -1。
     */
    static long msUntilRest(long now, boolean on, int start, int end, int warnSeconds) {
        if (!on || start == end) return -1;
        if (isResting(now, true, start, end)) return 0;
        long at = nextAt(now, start);
        if (at <= now) at += 24L * 3600_000L;
        long left = at - now;
        return left <= warnSeconds * 1000L ? left : -1;
    }

    /** 本次休息结束的时间戳（不在休息中时返回 -1）。 */
    static long restEndsAt(long now, boolean on, int start, int end) {
        if (!isResting(now, on, start, end)) return -1;
        long at = nextAt(now, end);
        if (at <= now) at += 24L * 3600_000L;
        return at;
    }

    // ------------------------------------------------------------ 按当前设置

    /** 现在是否落在给定时间段内（不看开关）；用于「开启前提醒会立即生效」。 */
    public static boolean inWindowNow(int start, int end) { return inWindow(minuteOfDay(System.currentTimeMillis()), start, end); }

    public static boolean isResting() { return isResting(System.currentTimeMillis(), enabled(), startMinute(), endMinute()); }
    public static long msUntilRest() { return msUntilRest(System.currentTimeMillis(), enabled(), startMinute(), endMinute(), warnSeconds()); }
    public static long restEndsAt() { return restEndsAt(System.currentTimeMillis(), enabled(), startMinute(), endMinute()); }

    /**
     * 休息设置是否锁定：休息进行中不能修改或关闭规则，休息结束后才能改。
     * 倒计时阶段不锁——否则刚设好开始时间、离开始不到几分钟时，连结束时间都改不了。
     */
    public static boolean locked() { return msUntilRest() == 0; }

    /**
     * 统一入口：正在休息就把这个界面换成休息页并返回 true（调用方应立即 return）。
     * 应用入口、主界面、播放器在 onCreate / onResume 都调用它。
     */
    public static boolean enforce(Activity a) {
        if (!isResting() || a instanceof RestActivity) return false;
        Intent i = new Intent(a, RestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finish();
        a.overridePendingTransition(0, 0);
        return true;
    }
}
