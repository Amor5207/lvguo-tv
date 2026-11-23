package ink.pengfei.lvguo.ui.page;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.media.VideoCache;
import ink.pengfei.lvguo.net.Session;
import ink.pengfei.lvguo.store.Store;
import ink.pengfei.lvguo.ui.IconView;
import ink.pengfei.lvguo.ui.Page;
import ink.pengfei.lvguo.ui.RootActivity;
import ink.pengfei.lvguo.ui.Ui;
import ink.pengfei.lvguo.ui.widget.DanmakuView;
import ink.pengfei.lvguo.ui.widget.FocusScrollView;
import ink.pengfei.lvguo.ui.widget.TvDialog;
import ink.pengfei.lvguo.ui.widget.PinDialog;
import ink.pengfei.lvguo.ui.widget.TimeDialog;
import ink.pengfei.lvguo.care.RestMode;
import ink.pengfei.lvguo.care.WatchCare;
import ink.pengfei.lvguo.remote.RemoteAuth;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置（tvOS 双栏）：左侧分类，右侧该分类的设置项。
 * - 焦点移到某个分类即切换右侧内容；右键进入设置项，左键回到分类；
 * - 枚举类设置一律弹出二级选择框（不再「按一下变一次」），选完焦点回到原行；
 * - 新增设置只需在 sections() 里加一行，分类会自然变长，不会把页面挤乱。
 */
public class SettingsPage extends Page {

    private interface Value { String get(); }
    private interface Action { void run(View row); }
    private static final class Item {
        final String key, label; final Value value; final Action action; final boolean danger;
        Item(String key, String label, Value value, Action action) { this(key, label, value, action, false); }
        Item(String key, String label, Value value, Action action, boolean danger) { this.key = key; this.label = label; this.value = value; this.action = action; this.danger = danger; }
    }
    private static final class Section {
        final String key, title, note; final int icon; final List<Item> items = new ArrayList<>();
        Section(String key, String title, int icon, String note) { this.key = key; this.title = title; this.icon = icon; this.note = note; }
    }

    private static final String[] QUALITIES = {"1080p", "720p", "540p", "480p", "360p"};
    private static final String[] QUALITY_LABEL = {"1080P 超清", "720P 高清", "540P 标清", "480P 流畅", "360P 省流"};
    private static final String[] THEME_LABEL = {"跟随系统", "浅色", "深色", "按时间段"};
    private static final String[] RESUME_LABEL = {"询问", "直接继续", "不提示"};

    private final List<Section> sections = new ArrayList<>();
    private final List<LinearLayout> categoryViews = new ArrayList<>();
    private LinearLayout rows, backRow;
    private TextView sectionTitle, sectionNote;
    private ScrollView rightScroll;
    private int current = 0, navId;
    private String cacheTotal = "正在统计…", cacheDetail = null;
    private String lastRowKey;
    private boolean rebuildingRows;

    @Override protected View build() {
        buildSections();
        FrameLayout root = new FrameLayout(ctx);
        LinearLayout panes = Ui.row(ctx);
        root.addView(panes, new FrameLayout.LayoutParams(-1, -1));

        // 左栏：分类
        LinearLayout left = Ui.column(ctx);
        Ui.padding(left, 16, 28, 12, 16);
        // 设置是单独的整页：分类栏就是这一页的「导航」，和主界面左侧导航同一种底色
        left.setBackgroundColor(Ui.SURFACE);
        // 顶部「‹ 返回」按钮（常驻浅底，一眼能看出能按），下面是页面标题；遥控器返回键也一样回到「我的」
        backRow = Ui.row(ctx);
        backRow.setGravity(Gravity.CENTER_VERTICAL);
        backRow.setFocusable(true);
        backRow.setId(View.generateViewId());
        if (android.os.Build.VERSION.SDK_INT >= 26) backRow.setDefaultFocusHighlightEnabled(false);
        Ui.padding(backRow, 12, 0, 18, 0);
        final IconView backIcon = new IconView(ctx, IconView.BACK, 18);
        backRow.addView(backIcon, Ui.lp(Ui.dp(ctx, 18), Ui.dp(ctx, 18)));
        final TextView backTitle = Ui.text(ctx, "返回", 16, Ui.TEXT, true);
        LinearLayout.LayoutParams btl = Ui.lp(-2, -2); btl.leftMargin = Ui.dp(ctx, 6);
        backRow.addView(backTitle, btl);
        Runnable paintBack = () -> {
            boolean f = backRow.isFocused();
            backRow.setBackground(Ui.round(ctx, f ? Ui.FOCUS_FILL : Ui.FILL, 22));
            backIcon.setColor(f ? Ui.ON_FOCUS : Ui.TEXT);
            backTitle.setTextColor(f ? Ui.ON_FOCUS : Ui.TEXT);
        };
        paintBack.run();
        backRow.setOnFocusChangeListener((v, f) -> paintBack.run());
        backRow.setOnClickListener(v -> host.focusNav(RootActivity.TAB_MINE));
        // 设置现在是左侧导航里的一个页面：不再需要「返回」按钮，左键直接回到导航
        backRow.setVisibility(View.GONE);
        backRow.setFocusable(false);
        left.addView(backRow, Ui.lp(-2, Ui.dp(ctx, 44)));
        // 页面标题「设置」不再显示：左侧导航已经点亮了「设置」
        for (int i = 0; i < sections.size(); i++) {
            final int idx = i;
            Section sec = sections.get(i);
            LinearLayout cat = Ui.row(ctx);
            cat.setGravity(Gravity.CENTER_VERTICAL);
            cat.setFocusable(true);
            cat.setId(View.generateViewId());
            Ui.padding(cat, 14, 0, 12, 0);
            IconView ic = new IconView(ctx, sec.icon, 20);
            cat.addView(ic, Ui.lp(Ui.dp(ctx, 20), Ui.dp(ctx, 20)));
            TextView label = Ui.text(ctx, sec.title, 17, Ui.TEXT, false);
            LinearLayout.LayoutParams ll = Ui.lp(0, -2, 1); ll.leftMargin = Ui.dp(ctx, 14);
            cat.addView(label, ll);
            cat.setOnFocusChangeListener((v, has) -> { if (has && current != idx && !rebuildingRows) show(idx); paintCategories(); });
            cat.setOnKeyListener((v, code, e) -> {
                if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { focusFirstRow(); return true; }
                if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { host.focusNav(RootActivity.TAB_MINE); return true; }
                return false;
            });
            // 鼠标 / 触摸点击：直接切到这个分类（点击不会产生焦点，不能靠焦点切换）；遥控器确定键：进入右栏
            cat.setOnClickListener(v -> {
                if (current != idx) show(idx);
                paintCategories();
                if (!v.isInTouchMode()) focusFirstRow();
            });
            LinearLayout.LayoutParams cl = Ui.lp(-1, Ui.dp(ctx, 48)); cl.bottomMargin = Ui.dp(ctx, 4);
            left.addView(cat, cl);
            categoryViews.add(cat);
        }
        // 设置是单独的页面：分类栏收窄，不再用竖分割线
        panes.addView(left, Ui.lp(Ui.dp(ctx, 156), -1));

        // 右栏：设置项
        rightScroll = new FocusScrollView(ctx);
        rightScroll.setVerticalScrollBarEnabled(false);
        rightScroll.setClipToPadding(false);
        LinearLayout right = Ui.column(ctx);
        Ui.padding(right, 36, 30, 48, 40);   // 右侧分类标题与左侧「设置」标题同一行
        sectionTitle = Ui.text(ctx, "", 20, Ui.TEXT, true);
        right.addView(sectionTitle, Ui.lp(-1, -2));
        sectionNote = Ui.text(ctx, "", 13, Ui.TEXT_3, false);
        LinearLayout.LayoutParams nl = Ui.lp(-1, -2); nl.topMargin = Ui.dp(ctx, 6);
        right.addView(sectionNote, nl);
        rows = Ui.column(ctx);
        LinearLayout.LayoutParams rl = Ui.lp(-1, -2); rl.topMargin = Ui.dp(ctx, 18);
        right.addView(rows, rl);
        rightScroll.addView(right, new FrameLayout.LayoutParams(-1, -2));
        panes.addView(rightScroll, Ui.lp(0, -1, 1));

        // 焦点围栏：分类列表上下到头不跳走（之前从最后一项往下会跳到右栏、第一项往上跳回导航）
        for (int i = 0; i < categoryViews.size(); i++) {
            LinearLayout c = categoryViews.get(i);
            c.setNextFocusUpId(i == 0 ? c.getId() : categoryViews.get(i - 1).getId());
            c.setNextFocusDownId(i == categoryViews.size() - 1 ? c.getId() : categoryViews.get(i + 1).getId());
        }
        if (!categoryViews.isEmpty()) {
            backRow.setNextFocusDownId(categoryViews.get(0).getId());
            backRow.setNextFocusUpId(backRow.getId());
            backRow.setNextFocusRightId(backRow.getId());
        }
        show(0);
        return root;
    }

    // ------------------------------------------------------------ 内容

    private void buildSections() {
        sections.clear();

        Section play = new Section("play", "播放", IconView.PLAY_CIRCLE, null);
        play.items.add(new Item("resume", "启动续播", () -> RESUME_LABEL[resumeMode()], row ->
                TvDialog.choose(host, "启动续播", null, RESUME_LABEL, resumeMode(), row, i -> {
                    App.prefs().edit().putInt("resume_on_launch", i).apply(); refreshRows(); })));
        play.items.add(new Item("quality", "默认画质", () -> QUALITY_LABEL[qualityIndex()], row ->
                TvDialog.choose(host, "默认画质", null, QUALITY_LABEL, qualityIndex(), row, i -> {
                    App.prefs().edit().putString("quality", QUALITIES[i]).apply(); refreshRows(); })));
        play.items.add(new Item("autoplay", "自动连播", () -> App.prefs().getBoolean("autoplay_next", true) ? "开启" : "关闭", row ->
                choiceOnOff(row, "自动连播", null, App.prefs().getBoolean("autoplay_next", true),
                        on -> App.prefs().edit().putBoolean("autoplay_next", on).apply())));
        play.items.add(new Item("speedlock", "保持倍速", () -> App.prefs().getBoolean("speed_lock", App.SPEED_LOCK_DEFAULT) ? "开启" : "关闭", row ->
                choiceOnOff(row, "保持倍速", "换集后沿用倍速", App.prefs().getBoolean("speed_lock", App.SPEED_LOCK_DEFAULT),
                        on -> App.prefs().edit().putBoolean("speed_lock", on).apply())));
        sections.add(play);

        Section danmaku = new Section("danmaku", "弹幕", IconView.DANMAKU, null);
        danmaku.items.add(new Item("dm_on", "弹幕显示", () -> DanmakuView.enabled() ? "开启" : "关闭", row ->
                choiceOnOff(row, "弹幕显示", null, DanmakuView.enabled(), on -> App.prefs().edit().putBoolean("dm_on", on).apply())));
        danmaku.items.add(new Item("dm_speed", "弹幕速度", () -> DanmakuView.SPEED_LABELS[DanmakuView.speedIndex()], row ->
                TvDialog.choose(host, "弹幕速度", null, DanmakuView.SPEED_LABELS, DanmakuView.speedIndex(), row, i -> { App.prefs().edit().putInt("dm_speed", i).apply(); refreshRows(); })));
        danmaku.items.add(new Item("dm_size", "弹幕字号", () -> DanmakuView.SIZE_LABELS[DanmakuView.sizeIndex()], row ->
                TvDialog.choose(host, "弹幕字号", null, DanmakuView.SIZE_LABELS, DanmakuView.sizeIndex(), row, i -> { App.prefs().edit().putInt("dm_size", i).apply(); refreshRows(); })));
        danmaku.items.add(new Item("dm_alpha", "弹幕透明", () -> DanmakuView.ALPHAS[DanmakuView.alphaIndex()] + "%", row -> {
            String[] a = new String[DanmakuView.ALPHAS.length];
            for (int i = 0; i < a.length; i++) a[i] = DanmakuView.ALPHAS[i] + "%";
            TvDialog.choose(host, "弹幕透明度", null, a, DanmakuView.alphaIndex(), row, i -> { App.prefs().edit().putInt("dm_alpha", i).apply(); refreshRows(); });
        }));
        sections.add(danmaku);

        buildCareSections();

        // 外观：主题选「按时间段」后接着选时间段（二级），不再单独占一行
        Section look = new Section("look", "外观", IconView.APPEARANCE, null);
        look.items.add(new Item("theme", "界面主题", () -> Ui.savedMode() == Ui.MODE_SCHEDULE ? "按时间段 · " + Ui.darkWindowLabel() : THEME_LABEL[Ui.savedMode()], row ->
                TvDialog.choose(host, "界面主题", null, THEME_LABEL, Ui.savedMode(), row, i -> {
                    if (i == Ui.MODE_SCHEDULE) { chooseWindow(row); return; }
                    if (i == Ui.savedMode()) return;
                    Ui.saveMode(i);
                    host.applyThemeInPlace("theme");
                })));
        look.items.add(new Item("rail_labels", "导航文字", () -> ink.pengfei.lvguo.ui.RootActivity.railLabels() ? "显示" : "隐藏", row ->
                TvDialog.choose(host, "导航文字", null, new String[]{"显示", "隐藏"}, ink.pengfei.lvguo.ui.RootActivity.railLabels() ? 0 : 1, row, i -> {
                    App.prefs().edit().putBoolean("rail_labels", i == 0).apply();
                    host.applyRailLabels();
                    refreshRows();
                })));
        sections.add(look);

        // 数据：缓存、观看记录各一行，清理操作都在二级菜单里
        Section storage = new Section("storage", "数据", IconView.STORAGE, null);
        storage.items.add(new Item("cache", "缓存占用", () -> cacheTotal, row -> App.io().execute(() -> {
            long v = VideoCache.usedBytes(App.ctx()), im = ImageLoader.diskBytes();
            host.runOnUiThread(() -> ink.pengfei.lvguo.ui.widget.DetailPanel.show(host, new ink.pengfei.lvguo.ui.widget.DetailPanel.Spec()
                    .icon(IconView.DOWNLOAD).title("缓存 " + mb(v + im)).subtitle("清理不影响收藏和观看记录")
                    .fact("视频片段", mb(v)).fact("封面图片", mb(im)).fact("内存中的图片", ImageLoader.memoryCount() + " 张")
                    .action("清理缓存", false, () -> App.io().execute(() -> {
                        ImageLoader.clearDisk(); ImageLoader.clearMemory(); VideoCache.clear(App.ctx());
                        host.runOnUiThread(() -> { host.toast("缓存已清理"); refreshCache(); });
                    })), row));
        })));
        storage.items.add(new Item("records", "观看记录", () -> Store.history().size() + " 部", row -> {
            List<Store.Item> h = Store.history();
            ink.pengfei.lvguo.ui.widget.DetailPanel.Spec spec = new ink.pengfei.lvguo.ui.widget.DetailPanel.Spec()
                    .icon(IconView.HISTORY).title("观看记录").subtitle("与手机红果 App 同步")
                    .fact("看过的剧", h.size() + " 部");
            if (!h.isEmpty()) spec.fact("最近在看", h.get(0).title == null ? "—" : h.get(0).title);
            ink.pengfei.lvguo.ui.widget.DetailPanel.show(host, spec.action("清空观看记录", true, () ->
                    TvDialog.confirm(host, "清空观看记录？", "将同步清空红果 App 的观看历史。", "清空", () -> { Store.clearHistory(); host.toast("正在同步删除记录"); refreshRows(); })), row);
        }));
        sections.add(storage);

        Section account = new Section("account", "账号", IconView.USER, null);
        account.items.add(new Item("account", "账号信息", () -> { String n = Session.profile().name; return n == null || n.isEmpty() ? "已登录" : n; }, row -> {
            Session.Profile pf = Session.profile();
            String nick = pf.name == null || pf.name.isEmpty() ? "红果用户" : pf.name;
            ink.pengfei.lvguo.ui.widget.DetailPanel.Spec spec = new ink.pengfei.lvguo.ui.widget.DetailPanel.Spec()
                    .avatar(ink.pengfei.lvguo.img.ImageUrls.compatible(pf.avatar), nick.substring(0, nick.offsetByCodePoints(0, 1)))
                    .title(nick).subtitle(pf.signature == null || pf.signature.isEmpty() ? "红果账号 · 收藏与进度实时同步" : pf.signature);
            if (pf.vip) spec.fact("会员", "VIP");
            long hours = pf.watchMs / 3_600_000L;
            if (hours > 0) spec.fact("累计观看", hours + " 小时");
            if (pf.activeDays > 0) spec.fact("活跃", pf.activeDays + " 天");
            spec.fact("看过 / 追剧", Store.history().size() + " / " + Store.favorites().size());
            ink.pengfei.lvguo.ui.widget.DetailPanel.show(host, spec.action("退出登录", true, () ->
                        TvDialog.confirm(host, "退出登录？", "退出后需重新登录。", "退出登录", () -> {
                            String cookie = Session.cookieHeader();
            App.io().execute(() -> ink.pengfei.lvguo.net.Account.logoutRemote(cookie));
                            Session.logout();
                            host.startActivity(new android.content.Intent(host, ink.pengfei.lvguo.ui.LoginActivity.class));
                            host.finish();
                        })), row);
        }));
        // 账号不放在设置里：退出在「我的」页右上角

        Section about = new Section("about", "关于", IconView.INFO, null);
        String v = ""; try { v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        final String version = v.split("-")[0];
        about.items.add(new Item("update", "检查更新", ink.pengfei.lvguo.update.UpdateManager::statusLabel, row -> {
            ink.pengfei.lvguo.update.UpdateManager.onItemClick(host);
            row.postDelayed(this::refreshRows, 1500);
        }));
        about.items.add(new Item("about", "关于软件", () -> version, row -> host.openList(new AboutPage())));
        about.items.add(new Item("module", "播放模块", () -> {
            int r = ink.pengfei.lvguo.net.TVPlaybackModule.revision();
            return r > 0 ? "r" + r : "内置";
        }, null));
        sections.add(about);
    }

    // ------------------------------------------------------------ 观看时间 / 强制休息 / 遥控安全

    private static final String REMOTE_NOTE = "同一网络内的手机可遥控电视并访问账号数据，公共网络下请设置密码。";

    private static int indexOf(int[] options, int value, int fallback) {
        for (int i = 0; i < options.length; i++) if (options[i] == value) return i;
        return fallback;
    }
    /** 倒计时阶段和休息中不能改休息规则（防止用倒计时期间去关掉它）。 */
    private boolean restLocked() {
        if (!RestMode.locked()) return false;
        host.toast("休息中，暂不可修改");
        return true;
    }
    /** 改完休息规则后：如果现在已经落在休息时段内，立即进入休息。 */
    private void afterRestChange() { refreshRows(); RestMode.enforce(host); }

    private void buildCareSections() {
        Section care = new Section("care", "提醒", IconView.HISTORY,
                "仅在播放时提示。");
        care.items.add(new Item("care_remind", "久看提醒", () -> WatchCare.remindOn() ? "开启" : "关闭", row ->
                choiceOnOff(row, "久看提醒", "连续观看每满 1 小时提示一次",
                        WatchCare.remindOn(), on -> WatchCare.setRemindOn(on))));
        care.items.add(new Item("care_clock", "整点报时", () -> WatchCare.clockLabel(WatchCare.clockStep()), row -> {
            String[] labels = new String[WatchCare.CLOCK_OPTIONS.length];
            for (int i = 0; i < labels.length; i++) labels[i] = WatchCare.clockLabel(WatchCare.CLOCK_OPTIONS[i]);
            TvDialog.choose(host, "整点报时", "在画面右上角显示当前时间",
                    labels, indexOf(WatchCare.CLOCK_OPTIONS, WatchCare.clockStep(), 0), row, i -> {
                        WatchCare.setClock(WatchCare.CLOCK_OPTIONS[i]); refreshRows(); });
        }));
        sections.add(care);

        Section rest = new Section("rest", "休息", IconView.MOON,
                "休息时段内无法使用应用与手机遥控。");
        rest.items.add(new Item("rest_on", "强制休息", () -> RestMode.enabled() ? "开启" : "关闭", row -> {
            if (restLocked()) return;
            TvDialog.choose(host, "强制休息", null, new String[]{"开启", "关闭"}, RestMode.enabled() ? 0 : 1, row, i -> {
                boolean on = i == 0;
                if (on && !RestMode.enabled()
                        && RestMode.inWindowNow(RestMode.startMinute(), RestMode.endMinute())) {
                    TvDialog.confirm(host, "现在已在休息时段内", "开启后将立即进入强制休息，直到 " + RestMode.label(RestMode.endMinute()) + " 才能继续使用。", "开启",
                            () -> { RestMode.setEnabled(true); afterRestChange(); });
                    return;
                }
                RestMode.setEnabled(on);
                afterRestChange();
            });
        }));
        rest.items.add(new Item("rest_range", "休息时段", () -> RestMode.rangeLabel(), row -> {
            if (restLocked()) return;
            ink.pengfei.lvguo.ui.widget.TimeRangeDialog.show(host, "休息时段", "结束早于开始则为次日", RestMode.startMinute(), RestMode.endMinute(), row,
                    (start, end) -> { RestMode.setStart(start); RestMode.setEnd(end); afterRestChange(); });
        }));
        rest.items.add(new Item("rest_warn", "提前预告", () -> RestMode.warnLabel(RestMode.warnSeconds()), row -> {
            if (restLocked()) return;
            String[] labels = new String[RestMode.WARN_OPTIONS.length];
            for (int i = 0; i < labels.length; i++) labels[i] = RestMode.warnLabel(RestMode.WARN_OPTIONS[i]);
            TvDialog.choose(host, "提前预告", "休息前提示，随后左上角显示秒数", labels,
                    indexOf(RestMode.WARN_OPTIONS, RestMode.warnSeconds(), -1), row, i -> {
                        RestMode.setWarn(RestMode.WARN_OPTIONS[i]); refreshRows(); });
        }));
        sections.add(rest);

        Section remote = new Section("remote", "遥控", IconView.LOCK, REMOTE_NOTE);
        remote.items.add(new Item("remote_on", "手机遥控", () -> RemoteAuth.remoteOn() ? "开启" : "关闭", row ->
                choiceOnOff(row, "手机遥控", "关闭后手机无法遥控这台电视", RemoteAuth.remoteOn(), on -> {
                    RemoteAuth.setRemoteOn(on);
                    if (!on) ink.pengfei.lvguo.remote.Pairing.dismiss();
                })));
        remote.items.add(new Item("remote_pwd", "遥控密码", () -> RemoteAuth.hasPassword() ? "已设置" : "未设置", row -> {
            if (!RemoteAuth.hasPassword()) { askNewPin(row); return; }
            TvDialog.choose(host, "遥控访问密码", "修改后手机需重新验证", new String[]{"修改密码", "清除密码"}, -1, row, i -> {
                if (i == 0) askNewPin(row);
                else TvDialog.confirm(host, "清除遥控访问密码？", "清除后，同一网络内的手机无需密码即可遥控。", "清除", () -> {
                    RemoteAuth.clearPassword(); host.toast("已清除遥控访问密码"); refreshRows(); });
            });
        }));
        remote.items.add(new Item("phone", "连接手机", () -> "扫码连接", row -> {
            if (!RemoteAuth.remoteOn()) { host.toast("手机遥控已关闭，请先开启"); return; }
            ink.pengfei.lvguo.remote.Pairing.show(host);
        }));
        sections.add(remote);
    }

    /** 设置 / 修改遥控密码：输入两次一致才保存；保存后旧授权全部失效。 */
    private void askNewPin(View row) {
        String hint = RemoteAuth.MIN_LEN + "~" + RemoteAuth.MAX_LEN + " 位数字，手机首次连接时输入";
        PinDialog.show(host, "设置遥控访问密码", hint, RemoteAuth.MIN_LEN, RemoteAuth.MAX_LEN, row, first ->
                PinDialog.show(host, "再输入一次", "确认刚才的密码", RemoteAuth.MIN_LEN, RemoteAuth.MAX_LEN, row, second -> {
                    if (!first.equals(second)) { host.toast("两次输入不一致"); return; }
                    RemoteAuth.setPassword(first);
                    host.toast("密码已设置，手机需重新验证");
                    refreshRows();
                }));
    }

    /** 主题 →「按时间段」：依次设定深色开始、结束时间（任意时分），设好即生效。 */
    private void chooseWindow(View row) {
        TimeDialog.show(host, "深色开始时间", "从这个时间开始使用深色", Ui.darkStart(), row, start ->
                TimeDialog.show(host, "深色结束时间", "早于开始时间即表示到第二天", Ui.darkEnd(), row, end -> {
                    if (start == end) { host.toast("开始和结束时间不能相同"); return; }
                    Ui.saveDarkWindow(start, end);
                    Ui.saveMode(Ui.MODE_SCHEDULE);
                    host.applyThemeInPlace("theme");
                }));
    }

    private interface OnOff { void set(boolean on); }
    private void choiceOnOff(View row, String title, String hint, boolean on, OnOff apply) {
        TvDialog.choose(host, title, hint, new String[]{"开启", "关闭"}, on ? 0 : 1, row, i -> { apply.set(i == 0); refreshRows(); });
    }

    private static int resumeMode() { return Math.max(0, Math.min(2, App.prefs().getInt("resume_on_launch", 0))); }
    private static int qualityIndex() {
        String q = App.prefs().getString("quality", "720p");
        for (int i = 0; i < QUALITIES.length; i++) if (QUALITIES[i].equals(q)) return i;
        return 1;
    }

    // ------------------------------------------------------------ 渲染

    private void show(int idx) {
        current = idx;
        Section sec = sections.get(idx);
        sectionTitle.setText(sec.title);
        sectionNote.setText(sec.note == null ? "" : sec.note);
        sectionNote.setVisibility(sec.note == null ? View.GONE : View.VISIBLE);
        rows.removeAllViews();
        for (Item item : sec.items) rows.addView(row(item), rowLp());
        // 右栏只在可选的行之间上下移动，到头停住；右键不再乱跳
        List<View> f = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) if (rows.getChildAt(i).isFocusable()) f.add(rows.getChildAt(i));
        for (int i = 0; i < f.size(); i++) {
            View r = f.get(i);
            r.setNextFocusUpId((i == 0 ? r : f.get(i - 1)).getId());
            r.setNextFocusDownId((i == f.size() - 1 ? r : f.get(i + 1)).getId());
            r.setNextFocusRightId(r.getId());
        }
        rightScroll.smoothScrollTo(0, 0);
        if ("storage".equals(sec.key)) refreshCache();
        paintCategories();
    }

    private LinearLayout.LayoutParams rowLp() { LinearLayout.LayoutParams lp = Ui.lp(-1, Ui.dp(ctx, 58)); lp.bottomMargin = Ui.dp(ctx, 8); return lp; }

    private View row(Item item) {
        LinearLayout row = Ui.row(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(item.key);
        row.setFocusable(item.action != null);
        row.setId(View.generateViewId());
        Ui.padding(row, 22, 0, 18, 0);
        TextView label = Ui.text(ctx, item.label, 17, item.danger ? 0xFFFF6B60 : Ui.TEXT, false);
        row.addView(label, Ui.lp(0, -2, 1));
        TextView value = Ui.text(ctx, item.value == null ? "" : item.value.get(), 15, Ui.TEXT_2, false);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setMaxWidth(Ui.dp(ctx, 320));
        value.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams vl = Ui.lp(-2, -2); vl.leftMargin = Ui.dp(ctx, 16);
        row.addView(value, vl);
        IconView chev = new IconView(ctx, IconView.CHEVRON_RIGHT, 16);
        LinearLayout.LayoutParams cl = Ui.lp(Ui.dp(ctx, 16), Ui.dp(ctx, 16)); cl.leftMargin = Ui.dp(ctx, 10);
        chev.setVisibility(item.action == null ? View.GONE : View.VISIBLE);
        row.addView(chev, cl);
        Runnable paint = () -> {
            boolean f = row.hasFocus();
            row.setBackground(Ui.round(ctx, f ? Ui.FOCUS_FILL : Ui.FILL_2, 14));
            label.setTextColor(f ? Ui.ON_FOCUS : item.danger ? 0xFFFF6B60 : Ui.TEXT);
            label.setTypeface(null, f ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            value.setTextColor(f ? Ui.ON_FOCUS : Ui.TEXT_2);
            chev.setColor(f ? Ui.ON_FOCUS : Ui.TEXT_3);
        };
        paint.run();
        row.setOnFocusChangeListener((v, has) -> { paint.run(); paintCategories(); });
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { categoryViews.get(current).requestFocus(); return true; }
            return false;
        });
        if (item.action != null) row.setOnClickListener(v -> { lastRowKey = item.key; item.action.run(row); });
        return row;
    }

    /** 当前分类：焦点在分类上=实底高亮；焦点在右侧=淡主题色底，告诉用户「正在看这一类」。 */
    private void paintCategories() {
        for (int i = 0; i < categoryViews.size(); i++) {
            LinearLayout cat = categoryViews.get(i);
            boolean f = cat.hasFocus(), sel = i == current;
            cat.setBackground(f ? Ui.round(ctx, Ui.FOCUS_FILL, 14) : sel ? Ui.round(ctx, Ui.TINT_PRIMARY, 14) : null);
            int fg = f ? Ui.ON_FOCUS : sel ? Ui.ACCENT_TEXT : Ui.TEXT_2;
            ((IconView) cat.getChildAt(0)).setColor(fg);
            TextView t = (TextView) cat.getChildAt(1);
            t.setTextColor(fg);
            t.setTypeface(null, f || sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    /** 选项改完后原地刷新数值，焦点留在原行。 */
    private void refreshRows() {
        String key = lastRowKey;
        int section = sectionFor(key);
        if (section < 0) section = current;
        final int restoreSection = section;
        rebuildingRows = true;
        try {
            show(restoreSection);
            if (key != null) focusRow(key);
        } finally {
            rebuildingRows = false;
        }
        if (key != null) rows.post(() -> {
            if (current != restoreSection) return;
            rebuildingRows = true;
            try { focusRow(key); }
            finally { rebuildingRows = false; }
        });
    }

    private int sectionFor(String key) {
        if (key == null) return -1;
        for (int i = 0; i < sections.size(); i++)
            for (Item item : sections.get(i).items)
                if (key.equals(item.key)) return i;
        return -1;
    }

    private void focusRow(String key) {
        for (int i = 0; i < rows.getChildCount(); i++) {
            View r = rows.getChildAt(i);
            if (key.equals(r.getTag())) { r.requestFocus(); return; }
        }
    }

    private void focusFirstRow() {
        for (int i = 0; i < rows.getChildCount(); i++) if (rows.getChildAt(i).isFocusable()) { rows.getChildAt(i).requestFocus(); return; }
    }

    private void refreshCache() {
        App.io().execute(() -> {
            long v = VideoCache.usedBytes(App.ctx()), im = ImageLoader.diskBytes();
            final String total = mb(v + im), detail = "视频 " + mb(v) + " · 封面 " + mb(im);
            host.runOnUiThread(() -> {
                cacheTotal = total; cacheDetail = detail;
                if (current < sections.size() && "storage".equals(sections.get(current).key))
                    for (int i = 0; i < rows.getChildCount(); i++) { View r = rows.getChildAt(i);
                        if ("cache".equals(r.getTag())) ((TextView) ((LinearLayout) r).getChildAt(1)).setText(total); }
            });
        });
    }

    private static String mb(long bytes) {
        double m = bytes / 1048576.0;
        return m < 0.1 ? "0 MB" : String.format(java.util.Locale.US, m < 10 ? "%.1f MB" : "%.0f MB", m);
    }

    /** 主题原地重建后，回到「外观」分类并把焦点放回对应行。 */
    public void restoreFocus(String key) {
        for (int i = 0; i < sections.size(); i++) for (Item it : sections.get(i).items) if (it.key.equals(key)) {
            final int idx = i;
            root.post(() -> { show(idx); focusRow(key); });
            return;
        }
    }

    @Override public void onShow() { root.setBackgroundColor(Ui.BG); if (rows != null) { show(current); } }
    @Override public void focusFirst() { if (!categoryViews.isEmpty()) categoryViews.get(current).requestFocus(); }
    @Override public void bindNav(int id) { navId = id; for (View c : categoryViews) c.setNextFocusLeftId(id); }
}
