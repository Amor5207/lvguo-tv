package ink.pengfei.lvguo.remote;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.store.Store;
import ink.pengfei.lvguo.ui.Ui;
import ink.pengfei.lvguo.ui.widget.DanmakuView;
import ink.pengfei.lvguo.care.RestMode;
import ink.pengfei.lvguo.care.WatchCare;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * 电视端设置的「描述 + 读写」，给手机设置页用：手机按这里返回的分组 / 选项渲染，
 * 和电视「设置」页保持同一份内容；新增电视设置时在这里加一项，手机自动出现。
 */
public final class TvSettings {
    private TvSettings() {}

    static final String[] RESUME = {"询问", "直接继续", "不提示"};
    static final String[] QUALITY_IDS = {"1080p", "720p", "540p", "480p", "360p"};
    static final String[] QUALITY = {"1080P 超清", "720P 高清", "540P 标清", "480P 流畅", "360P 省流"};
    static final String[] THEME = {"跟随系统", "浅色", "深色", "按时间段"};
    static final String[] ON_OFF = {"开启", "关闭"};

    private static JSONObject choice(String key, String title, String[] options, int value, String hint) throws Exception {
        return new JSONObject().put("key", key).put("type", "choice").put("title", title).put("options", new JSONArray(java.util.Arrays.asList(options))).put("value", value).put("hint", hint == null ? "" : hint);
    }
    private static JSONObject toggle(String key, String title, boolean on, String hint) throws Exception {
        return new JSONObject().put("key", key).put("type", "toggle").put("title", title).put("value", on).put("hint", hint == null ? "" : hint);
    }
    /** 时间项：value 为一天中的第几分钟（0~1439），text 为显示文字（如「次日 07:00」）。手机用系统时间选择器修改。 */
    private static JSONObject time(String key, String title, int minute, String text, String hint) throws Exception {
        return new JSONObject().put("key", key).put("type", "time").put("title", title).put("value", minute).put("text", text).put("hint", hint == null ? "" : hint);
    }
    private static JSONObject range(String key, String title, int start, int end, String text) throws Exception {
        return new JSONObject().put("key", key).put("type", "range").put("title", title).put("start", start).put("end", end).put("text", text);
    }
    private static JSONObject info(String key, String title, String text) throws Exception {
        return new JSONObject().put("key", key).put("type", "info").put("title", title).put("text", text);
    }
    private static JSONObject action(String key, String title, String confirm) throws Exception {
        return new JSONObject().put("key", key).put("type", "action").put("title", title).put("confirm", confirm);
    }
    private static JSONObject group(String title, String foot, JSONObject... items) throws Exception {
        JSONArray a = new JSONArray(); for (JSONObject o : items) a.put(o);
        return new JSONObject().put("title", title).put("foot", foot == null ? "" : foot).put("items", a);
    }

    private static int qualityIndex() {
        String q = App.prefs().getString("quality", "720p");
        for (int i = 0; i < QUALITY_IDS.length; i++) if (QUALITY_IDS[i].equals(q)) return i;
        return 1;
    }
    private static String[] alphaLabels() {
        String[] a = new String[DanmakuView.ALPHAS.length];
        for (int i = 0; i < a.length; i++) a[i] = DanmakuView.ALPHAS[i] + "%";
        return a;
    }
    private static String mb(long bytes) {
        double m = bytes / 1048576.0;
        return m < 0.1 ? "0 MB" : String.format(java.util.Locale.US, m < 10 ? "%.1f MB" : "%.0f MB", m);
    }

    /** 二级菜单：一行设置，点开后是若干操作（每个操作可带确认文案）。 */
    private static JSONObject menu(String key, String title, String text, String hint, String[][] actions) throws Exception {
        JSONArray a = new JSONArray();
        for (String[] x : actions) a.put(new JSONObject().put("key", x[0]).put("title", x[1]).put("confirm", x.length > 2 ? x[2] : ""));
        return new JSONObject().put("key", key).put("type", "menu").put("title", title).put("text", text).put("hint", hint == null ? "" : hint).put("actions", a).put("facts", new JSONArray());
    }
    private static JSONObject facts(JSONObject menu, String[][] rows) throws Exception {
        JSONArray f = menu.getJSONArray("facts");
        for (String[] r : rows) f.put(new JSONArray().put(r[0]).put(r[1]));
        return menu;
    }

    public static JSONObject all() throws Exception {
        String version = "";
        try { version = App.ctx().getPackageManager().getPackageInfo(App.ctx().getPackageName(), 0).versionName.split("-")[0]; } catch (Exception ignored) { }
        long video = ink.pengfei.lvguo.media.VideoCache.usedBytes(App.ctx()), image = ink.pengfei.lvguo.img.ImageLoader.diskBytes();
        String[] themes = THEME.clone();
        if (Ui.savedMode() == Ui.MODE_SCHEDULE) themes[3] = "按时间段 · " + Ui.darkWindowLabel();
        JSONObject theme = choice("theme", "界面主题", themes, Ui.savedMode(), null);
        JSONObject rail = toggle("rail_labels", "导航文字", ink.pengfei.lvguo.ui.RootActivity.railLabels(), null);
        // 选了「按时间段」才出现「深色时间段」
        JSONObject look = Ui.savedMode() == Ui.MODE_SCHEDULE
                ? group("外观", null, theme,
                        time("dark_start", "深色开始", Ui.darkStart(), Ui.clock(Ui.darkStart()), null),
                        time("dark_end", "深色结束", Ui.darkEnd(), (Ui.darkEnd() <= Ui.darkStart() ? "次日 " : "") + Ui.clock(Ui.darkEnd()), null), rail)
                : group("外观", null, theme, rail);
        java.util.List<Store.Item> historyItems = Store.history();
        java.util.List<String[]> historyFacts = new java.util.ArrayList<>();
        historyFacts.add(new String[]{"看过的剧", historyItems.size() + " 部"});
        if (!historyItems.isEmpty()) {
            String recentTitle = historyItems.get(0).title;
            historyFacts.add(new String[]{"最近在看", recentTitle == null || recentTitle.isEmpty() ? "—" : recentTitle});
        }
        JSONArray groups = new JSONArray()
                .put(group("播放", null,
                        choice("resume", "启动续播", RESUME, Math.max(0, Math.min(2, App.prefs().getInt("resume_on_launch", 0))), null),
                        choice("quality", "默认画质", QUALITY, qualityIndex(), null),
                        toggle("autoplay", "自动连播", App.prefs().getBoolean("autoplay_next", true), null),
                        toggle("speedlock", "保持倍速", App.prefs().getBoolean("speed_lock", App.SPEED_LOCK_DEFAULT), null)))
                .put(group("弹幕", null,
                        toggle("dm_on", "弹幕显示", DanmakuView.enabled(), null),
                        choice("dm_speed", "弹幕速度", DanmakuView.SPEED_LABELS, DanmakuView.speedIndex(), null),
                        choice("dm_size", "弹幕字号", DanmakuView.SIZE_LABELS, DanmakuView.sizeIndex(), null),
                        choice("dm_alpha", "弹幕透明", alphaLabels(), DanmakuView.alphaIndex(), null)))
                .put(group("提醒", "仅在播放时提示。",
                        toggle("care_remind", "久看提醒", WatchCare.remindOn(), "连续观看每满 1 小时提示一次"),
                        choice("care_clock", "整点报时", clockLabels(), indexOf(WatchCare.CLOCK_OPTIONS, WatchCare.clockStep()), null)))
                .put(group("休息", "休息时段内无法使用应用与手机遥控。",
                        toggle("rest_on", "强制休息", RestMode.enabled(), null),
                        range("rest_range", "休息时段", RestMode.startMinute(), RestMode.endMinute(), RestMode.windowLabel()),
                        choice("rest_warn", "提前预告", warnLabels(), indexOf(RestMode.WARN_OPTIONS, RestMode.warnSeconds()), null)))
                .put(group("遥控", "手机遥控可访问账号资料与观看记录。公共或不可信网络下，请设置遥控密码或关闭手机遥控。",
                        info("remote_on", "手机遥控", RemoteAuth.remoteOn() ? "开启" : "关闭"),
                        info("remote_pwd", "遥控密码", RemoteAuth.hasPassword() ? "已设置（在电视上修改）" : "未设置（在电视上设置）")))
                .put(look)
                .put(group("数据", null,
                        facts(menu("cache", "缓存占用", mb(video + image), "清理不影响收藏和观看记录",
                                new String[][]{{"cache_clear", "清理缓存", ""}}),
                                new String[][]{{"视频片段", mb(video)}, {"封面图片", mb(image)}}),
                        facts(menu("records", "观看记录", historyItems.size() + " 部", "与手机红果 App 同步",
                                new String[][]{{"history_clear", "清空观看记录", "将同步清空红果 App 的观看历史。"}}),
                                historyFacts.toArray(new String[0][]))))
                .put(group("关于", null,
                        menu("about", "关于软件", version, null, new String[][]{{"github", "前往仓库", ""}})));
        return new JSONObject().put("groups", groups);
    }

    private static String[] warnLabels() {
        String[] out = new String[RestMode.WARN_OPTIONS.length];
        for (int i = 0; i < out.length; i++) out[i] = RestMode.warnLabel(RestMode.WARN_OPTIONS[i]);
        return out;
    }
    private static String[] clockLabels() {
        String[] out = new String[WatchCare.CLOCK_OPTIONS.length];
        for (int i = 0; i < out.length; i++) out[i] = WatchCare.clockLabel(WatchCare.CLOCK_OPTIONS[i]);
        return out;
    }
    /** 手机传来的时间（一天中的第几分钟），超出范围视为无效。 */
    private static int minuteOfDay(Object value) throws IOException {
        int m = value instanceof Number ? ((Number) value).intValue() : -1;
        if (!(value instanceof Number)) { try { m = Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException ignored) { } }
        if (m < 0 || m >= 1440) throw new IOException("时间无效");
        return m;
    }
    private static int indexOf(int[] options, int value) {
        for (int i = 0; i < options.length; i++) if (options[i] == value) return i;
        return 0;
    }
    /** 倒计时阶段和休息中不能改休息规则。 */
    private static void requireRestEditable() throws IOException {
        if (RestMode.locked()) throw new IOException("休息中，暂不可修改");
    }

    /** value：choice 传下标，toggle 传 true/false，action 忽略。 */
    public static JSONObject set(String key, Object value) throws Exception {
        android.content.SharedPreferences.Editor e = App.prefs().edit();
        int i = value instanceof Number ? ((Number) value).intValue() : 0;
        boolean on = value instanceof Boolean ? (Boolean) value : "true".equals(String.valueOf(value));
        switch (key) {
            case "care_remind": WatchCare.setRemindOn(on); break;
            case "care_clock": WatchCare.setClock(WatchCare.CLOCK_OPTIONS[clamp(i, WatchCare.CLOCK_OPTIONS.length)]); break;
            case "rest_on": requireRestEditable(); RestMode.setEnabled(on); enforceRestOnTv(); break;
            case "rest_warn": requireRestEditable(); RestMode.setWarn(RestMode.WARN_OPTIONS[clamp(i, RestMode.WARN_OPTIONS.length)]); break;
            case "rest_range": {
                requireRestEditable();
                String[] p = String.valueOf(value).split(",");
                if (p.length != 2) throw new IOException("时间无效");
                int a = minuteOfDay(p[0].trim()), b = minuteOfDay(p[1].trim());
                if (a == b) throw new IOException("开始和结束时间不能相同");
                RestMode.setStart(a); RestMode.setEnd(b); enforceRestOnTv(); break;
            }
            case "rest_start": {
                requireRestEditable(); int m = minuteOfDay(value);
                if (m == RestMode.endMinute()) throw new IOException("开始和结束时间不能相同");
                RestMode.setStart(m); enforceRestOnTv(); break;
            }
            case "rest_end": {
                requireRestEditable(); int m = minuteOfDay(value);
                if (m == RestMode.startMinute()) throw new IOException("开始和结束时间不能相同");
                RestMode.setEnd(m); enforceRestOnTv(); break;
            }
            case "resume": e.putInt("resume_on_launch", clamp(i, 3)).apply(); break;
            case "quality": e.putString("quality", QUALITY_IDS[clamp(i, QUALITY_IDS.length)]).apply(); break;
            case "autoplay": if (!live("autoplay", on)) e.putBoolean("autoplay_next", on).apply(); break;
            case "speedlock": if (!live("speedLock", on)) e.putBoolean("speed_lock", on).apply(); break;
            case "dm_on": e.putBoolean("dm_on", on).commit(); PlaybackControl.danmakuChanged(); break;
            case "dm_speed": e.putInt("dm_speed", clamp(i, DanmakuView.SPEED_LABELS.length)).commit(); PlaybackControl.danmakuChanged(); break;
            case "dm_size": e.putInt("dm_size", clamp(i, DanmakuView.SIZE_LABELS.length)).commit(); PlaybackControl.danmakuChanged(); break;
            case "dm_alpha": e.putInt("dm_alpha", clamp(i, DanmakuView.ALPHAS.length)).commit(); PlaybackControl.danmakuChanged(); break;
            case "theme": Ui.saveMode(clamp(i, THEME.length)); onTv(r -> r.applyThemeInPlace("theme")); break;
            case "dark_start": case "dark_end": {
                int m = minuteOfDay(value);
                int start = "dark_start".equals(key) ? m : Ui.darkStart(), end = "dark_end".equals(key) ? m : Ui.darkEnd();
                if (start == end) throw new IOException("开始和结束时间不能相同");
                Ui.saveDarkWindow(start, end);
                if (Ui.savedMode() == Ui.MODE_SCHEDULE) onTv(r -> r.applyThemeInPlace("theme"));
                break;
            }
            case "rail_labels": e.putBoolean("rail_labels", on).apply(); onTv(ink.pengfei.lvguo.ui.RootActivity::applyRailLabels); break;
            case "history_clear": Store.clearHistory(); break;
            case "cache_clear":
                ink.pengfei.lvguo.img.ImageLoader.clearDisk(); ink.pengfei.lvguo.img.ImageLoader.clearMemory();
                ink.pengfei.lvguo.media.VideoCache.clear(App.ctx()); break;
            default: throw new IOException("未知设置项");
        }
        return all();
    }

    private static int clamp(int i, int n) { return Math.max(0, Math.min(n - 1, i)); }

    /** 正在播放时走播放器的实时命令（播放器自己缓存了这两个状态），否则只改偏好。 */
    private static boolean live(String action, boolean on) {
        try {
            if (!PlaybackControl.status().optBoolean("playing")) return false;
            PlaybackControl.command(action, new JSONObject().put("enabled", on));
            return true;
        } catch (Exception e) { return false; }
    }

    /** 手机上改了休息规则、现在恰好落在休息时段内：电视主界面立即进入休息（播放器自己每秒检查）。 */
    private static void enforceRestOnTv() { onTv(ink.pengfei.lvguo.care.RestMode::enforce); }

    private interface OnRoot { void run(ink.pengfei.lvguo.ui.RootActivity r); }
    /** 电视界面正开着时在主线程原地生效（主题 / 导航文字）。 */
    private static void onTv(OnRoot fn) {
        ink.pengfei.lvguo.ui.RootActivity r = ink.pengfei.lvguo.ui.RootActivity.current();
        if (r != null) r.runOnUiThread(() -> { if (!r.isFinishing()) fn.run(r); });
    }
}
