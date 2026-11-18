package ink.pengfei.lvguo.care;

import ink.pengfei.lvguo.App;

import java.util.Calendar;

/**
 * 定时提醒的两项设置与计时（强制休息见 {@link RestMode}）：
 * - 累计提醒：开关，默认开启。连续观看每满 1 小时在顶部提示一次（1、2、3 小时……逐次累加），不暂停。
 * - 整点提示：每 30 / 60 分钟在画面上短暂显示当前时间。
 * 计时状态都放在偏好里，换集（同一个播放器）或短暂退出再进来都接着算。
 */
public final class WatchCare {
    private WatchCare() {}

    static final String K_REMIND = "care_remind", K_CLOCK = "care_clock";
    static final String K_WATCH_MS = "care_watch_ms", K_WATCH_TICK = "care_watch_tick", K_WATCH_SHOWN = "care_watch_shown";

    /** 累计观看提醒的固定间隔（分钟）：满 1 小时、2 小时、3 小时……各提醒一次。 */
    public static final int REMIND_STEP = 60;
    /** 整点提示：0 关闭 / 30 每半小时 / 60 每小时。 */
    public static final int[] CLOCK_OPTIONS = {0, 30, 60};

    /** 暂停超过这么久就算「休息过了」，连续观看重新计时。 */
    static final long BREAK_MS = 10 * 60_000L;

    // ------------------------------------------------------------ 防沉迷提醒 / 整点提示

    /** 累计提醒默认开启；旧版存过的 30 / 45 / 90 分钟等间隔统一按 60 分钟处理，0 = 用户关闭。 */
    public static int remindMinutes() { return App.prefs().getInt(K_REMIND, REMIND_STEP) > 0 ? REMIND_STEP : 0; }
    public static boolean remindOn() { return remindMinutes() > 0; }
    public static void setRemindOn(boolean on) { App.prefs().edit().putInt(K_REMIND, on ? REMIND_STEP : 0).apply(); }
    public static int clockStep() { return App.prefs().getInt(K_CLOCK, 30); }
    public static void setClock(int step) { App.prefs().edit().putInt(K_CLOCK, step).apply(); }

    public static String minutesLabel(int minutes) {
        if (minutes <= 0) return "关闭";
        return minutes % 60 == 0 ? (minutes / 60) + " 小时" : minutes + " 分钟";
    }
    public static String clockLabel(int step) { return step <= 0 ? "关闭" : step == 30 ? "每 30 分钟" : "每 60 分钟"; }

    /**
     * 播放器每次心跳调用（只在真正播放时）。返回值 &gt; 0 表示刚好跨过一个提醒点，
     * 值为已连续观看的分钟数；否则返回 0。
     */
    public static synchronized int onWatching(long now) {
        android.content.SharedPreferences p = App.prefs();
        long last = p.getLong(K_WATCH_TICK, 0), acc = p.getLong(K_WATCH_MS, 0);
        int shown = p.getInt(K_WATCH_SHOWN, 0);
        long[] r = advance(now, last, acc, shown, remindMinutes());
        p.edit().putLong(K_WATCH_TICK, now).putLong(K_WATCH_MS, r[0]).putInt(K_WATCH_SHOWN, (int) r[1]).apply();
        return (int) r[2];
    }

    /** 纯计算：返回 {新的累计毫秒, 新的已提醒次数, 本次要提醒的分钟数(0=不提醒)}。 */
    static long[] advance(long now, long lastTick, long accMs, int shown, int intervalMin) {
        long delta = lastTick <= 0 ? 0 : now - lastTick;
        if (delta < 0 || delta > BREAK_MS) { accMs = 0; shown = 0; delta = 0; }   // 停了很久（或改了时钟）：重新计时
        delta = Math.min(delta, 5_000L);   // 心跳间隔再长也只算几秒，避免一次跳过提醒点
        accMs += delta;
        if (intervalMin <= 0) return new long[]{accMs, shown, 0};
        long step = intervalMin * 60_000L;
        int reached = (int) (accMs / step);
        if (reached > shown) return new long[]{accMs, reached, (long) reached * intervalMin};
        return new long[]{accMs, shown, 0};
    }

    /** 「1 小时 30 分钟」这样的连续观看时长。 */
    public static String durationLabel(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + " 分钟";
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分钟";
    }

    /**
     * 整点提示：现在是否处在一个新的提示点（本地时间 HH:00 / HH:30），返回该提示点的
     * 「一天中第几分钟」+ 日期作为唯一键；不是提示点返回 -1。lastKey 是上次已提示的键。
     */
    static long clockSlot(long now, int step, long lastKey) {
        if (step <= 0) return -1;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int minute = c.get(Calendar.MINUTE);
        if (minute % step != 0) return -1;
        // 在提示点所在的整分钟内任意一次心跳都可触发，靠 key 去重保证只显示一次；
        // 不再限制「开头 20 秒」——那段窗口太窄，心跳稍有抖动就整点错过（之前只有累计提醒生效就是这个原因）。
        long key = c.get(Calendar.YEAR) * 100_000L + c.get(Calendar.DAY_OF_YEAR) * 10_000L + c.get(Calendar.HOUR_OF_DAY) * 100L + minute;
        return key == lastKey ? -1 : key;
    }

    private static long lastClockKey;
    /** 播放器心跳调用：该显示整点时间时返回「22:30」，否则返回 null。 */
    public static synchronized String clockDue(long now) {
        long key = clockSlot(now, clockStep(), lastClockKey);
        if (key < 0) return null;
        lastClockKey = key;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        return String.format(java.util.Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }
}
