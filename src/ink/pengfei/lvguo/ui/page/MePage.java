package ink.pengfei.lvguo.ui.page;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.ui.RootActivity;
import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.net.Account;
import ink.pengfei.lvguo.net.Session;
import ink.pengfei.lvguo.store.Store;
import ink.pengfei.lvguo.ui.IconView;
import ink.pengfei.lvguo.ui.Page;
import ink.pengfei.lvguo.ui.Ui;
import ink.pengfei.lvguo.ui.widget.HScrollRow;
import ink.pengfei.lvguo.ui.widget.FocusScrollView;
import ink.pengfei.lvguo.ui.widget.PosterCard;
import ink.pengfei.lvguo.ui.widget.RoundFrame;
import ink.pengfei.lvguo.ui.widget.TvDialog;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * 我的：账号资料（真实头像 / 昵称 / 签名）+ 观看历史 + 我的收藏 + 我的预约（已上线 / 待上线 标签切换）。
 * 数据都来自登录账号，与手机红果 App 同步；删除、清空会写回云端。
 *
 * 遥控器：上下在分区之间移动，左右在一行卡片里移动；确定=打开剧集；
 * 长按确定（或菜单键）=对这张卡片的操作（删除这条历史 / 取消收藏）。
 * 「清空」按钮放在分区标题的右侧，和卡片行分开，避免误触。
 */
public class MePage extends Page {
    private ScrollView scroll;
    private ImageView avatar;
    private TextView letter, name, signature, meta;
    private LinearLayout stats;
    private View phoneButton;
    private LinearLayout actionTiles;

    private int reservationCount = -1;
    private HScrollRow historyRow, favoriteRow, reserveRow;
    private TextView historyCount, favoriteCount;
    private TextView historyEmpty, favoriteEmpty, reserveEmpty;
    private View historyClear, favoriteClear;
    private LinearLayout reserveSkeleton;
    private final TextView[] reserveTabs = new TextView[2];
    private int reserveTab = -1;                       // 0 已上线 / 1 待上线；-1 = 尚未决定
    private boolean reservationsReady, reservationsFailed;
    private List<Account.Entry> onlineItems = new ArrayList<>(), offlineItems = new ArrayList<>();
    private int onlineTotal, offlineTotal;
    private int navId;
    private long reservationsLoadedAt;
    private final Store.Listener libraryListener = () -> { if (root != null && root.isShown()) renderLibrary(); };

    @Override protected View build() {
        Store.listen(libraryListener);
        scroll = new FocusScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout col = Ui.column(ctx);
        Ui.padding(col, 48, 40, 48, 48);
        col.setClipChildren(false);
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));

        // ---------- 资料头：头像 + 昵称 + 签名 + 统计（不再用背景图）
        LinearLayout head = Ui.row(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Ui.padding(head, 0, 8, 0, 8);   // 资料区不套卡片底板，直接落在页面上
        RoundFrame frame = new RoundFrame(ctx, 36);
        letter = Ui.text(ctx, "", 30, 0xFFFFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        letter.setBackgroundColor(Ui.PRIMARY_DIM);
        frame.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        avatar = new ImageView(ctx);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
        head.addView(frame, Ui.lp(Ui.dp(ctx, 72), Ui.dp(ctx, 72)));
        LinearLayout info = Ui.column(ctx);
        name = Ui.text(ctx, "", 22, Ui.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name, Ui.lp(-2, -2));
        signature = Ui.text(ctx, "", 14, Ui.TEXT_3, false);
        signature.setSingleLine(true);
        signature.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = Ui.lp(-1, -2); sl.topMargin = Ui.dp(ctx, 6);
        info.addView(signature, sl);
        meta = Ui.text(ctx, "", 12, Ui.ACCENT_TEXT, true);
        meta.setVisibility(View.GONE);
        LinearLayout.LayoutParams ml = Ui.lp(-2, -2); ml.topMargin = Ui.dp(ctx, 8);
        info.addView(meta, ml);
        LinearLayout.LayoutParams il = Ui.lp(0, -2, 1); il.leftMargin = Ui.dp(ctx, 18);
        head.addView(info, il);
        // 数据收进名字下方一行；右侧是三个常用入口：手机遥控 / 设置 / 退出登录
        stats = Ui.row(ctx);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        // 数字放在资料卡右侧（大号数字 + 小字说明），和左边的头像昵称左右平衡
        LinearLayout.LayoutParams stl = Ui.lp(-2, -2); stl.rightMargin = Ui.dp(ctx, 16);
        head.addView(stats, stl);
        // 设置、手机遥控各有自己的入口（左侧导航 / 首页右上角），这里只留快捷退出，放在资料卡最右边
        actionTiles = Ui.row(ctx);
        View logout = exitButton(v -> {
            scrollPageToTop();
            if (!Session.loggedIn()) {   // 匿名：这个按钮是「登录」入口
                host.startActivity(new android.content.Intent(host, ink.pengfei.lvguo.ui.LoginActivity.class));
                return;
            }
            TvDialog.confirm(host, "退出登录？", "退出后需重新登录。", "退出登录", () -> {
                scrollPageToTop();
                String cookie = Session.cookieHeader();
                App.io().execute(() -> ink.pengfei.lvguo.net.Account.logoutRemote(cookie));
                Session.logout();
                clearReservations();
                renderProfile();
                renderReservationArea();
            });
        }, this::scrollPageToTop);
        actionTiles.addView(logout, Ui.lp(Ui.dp(ctx, 88), Ui.dp(ctx, 76)));
        head.addView(actionTiles, Ui.lp(-2, -2));
        phoneButton = logout;
        logout.setId(View.generateViewId());
        logout.setNextFocusRightId(logout.getId()); logout.setNextFocusUpId(logout.getId());
        logout.setOnKeyListener((v, code, e) -> {   // 左键回导航，不斜着跳到海报
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { host.focusNav(RootActivity.TAB_ME); return true; }
            return false;
        });
        col.addView(head, Ui.lp(-1, -2));

        // ---------- 观看历史
        historyRow = new HScrollRow(ctx, 14);
        historyCount = sectionHead(col, "观看历史");
        historyClear = clearButton((LinearLayout) historyCount.getParent(), "清空历史", () ->
                TvDialog.confirm(host, "清空观看历史？", "将同步清空红果 App 的观看历史。", "清空", () -> { Store.clearHistory(); renderLibrary(); }));
        historyEmpty = empty(col, "暂无观看记录");
        col.addView(historyRow, rowLp());

        // ---------- 我的收藏
        favoriteRow = new HScrollRow(ctx, 14);
        favoriteCount = sectionHead(col, "我的收藏");
        favoriteClear = clearButton((LinearLayout) favoriteCount.getParent(), "清空收藏", () ->
                TvDialog.confirm(host, "清空全部收藏？", "将同步取消红果 App 的收藏。", "清空", () -> {
                    List<String> ids = new ArrayList<>();
                    for (Store.Item it : Store.favorites()) ids.add(it.seriesId);
                    Store.removeFavorites(ids);
                    renderLibrary();
                }));
        favoriteEmpty = empty(col, "暂无收藏");
        col.addView(favoriteRow, rowLp());

        // ---------- 我的预约：「已上线 / 待上线」用标签切换，同一行卡片里显示；数据没回来前先放占位
        LinearLayout reserveHead = Ui.row(ctx);
        reserveHead.setGravity(Gravity.CENTER_VERTICAL);
        reserveHead.addView(Ui.text(ctx, "我的预约", 20, Ui.TEXT, true));
        LinearLayout tabs = Ui.row(ctx);
        reserveTabs[0] = reserveTab("已上线", 0);
        reserveTabs[1] = reserveTab("待上线", 1);
        tabs.addView(reserveTabs[0], Ui.lp(-2, Ui.dp(ctx, 36)));
        LinearLayout.LayoutParams t1 = Ui.lp(-2, Ui.dp(ctx, 36)); t1.leftMargin = Ui.dp(ctx, 8);
        tabs.addView(reserveTabs[1], t1);
        LinearLayout.LayoutParams tabsLp = Ui.lp(-2, -2); tabsLp.leftMargin = Ui.dp(ctx, 20);
        reserveHead.addView(tabs, tabsLp);
        LinearLayout.LayoutParams rhl = Ui.lp(-1, Ui.dp(ctx, 44)); rhl.topMargin = Ui.dp(ctx, 30);
        col.addView(reserveHead, rhl);
        reserveRow = new HScrollRow(ctx, 14);
        col.addView(reserveRow, rowLp());
        reserveEmpty = empty(col, "");
        reserveSkeleton = Ui.row(ctx);
        for (int i = 0; i < 7; i++) {
            View ph = new View(ctx);
            ph.setBackground(Ui.round(ctx, Ui.SKELETON, 14));
            LinearLayout.LayoutParams pl = Ui.lp(Ui.dp(ctx, Ui.CARD_W), Ui.dp(ctx, Ui.CARD_H));
            if (i > 0) pl.leftMargin = Ui.dp(ctx, 14);
            reserveSkeleton.addView(ph, pl);
        }
        col.addView(reserveSkeleton, rowLp());
        reserveRow.setVisibility(View.GONE);
        reserveSkeleton.setVisibility(View.GONE);
        updateReserveTabs();

        return scroll;
    }

    /** 预约标签：聚焦即切换内容；选中=主色字，聚焦=高亮底。 */
    private TextView reserveTab(String label, int index) {
        TextView t = Ui.text(ctx, label, 16, Ui.TEXT_2, true);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) t.setDefaultFocusHighlightEnabled(false);
        Ui.padding(t, 18, 0, 18, 0);
        t.setOnFocusChangeListener((v, f) -> { if (f && reservationsReady) selectReserveTab(index); updateReserveTabs(); });
        t.setOnClickListener(v -> { if (reservationsReady) selectReserveTab(index); });
        t.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && index == 0) { host.focusNav(RootActivity.TAB_ME); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && index == 1) return true;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && reserveRow.getVisibility() == View.VISIBLE && reserveRow.size() > 0) { reserveRow.focusFirst(); return true; }
            return false;
        });
        return t;
    }

    private void updateReserveTabs() {
        for (int i = 0; i < 2; i++) {
            TextView t = reserveTabs[i];
            if (t == null) continue;
            boolean f = t.hasFocus(), on = i == reserveTab;
            t.setBackground(f ? Ui.round(ctx, Ui.FOCUS_FILL, 18) : on ? Ui.round(ctx, Ui.FILL, 18) : null);
            t.setTextColor(f ? Ui.ON_FOCUS : on ? Ui.ACCENT_TEXT : Ui.TEXT_2);
        }
    }

    private LinearLayout.LayoutParams rowLp() { LinearLayout.LayoutParams lp = Ui.lp(-1, -2); lp.topMargin = Ui.dp(ctx, 12); return lp; }

    /** Reset the page's vertical scroll only; keep each horizontal library row's position intact. */
    private void scrollPageToTop() {
        if (scroll == null) return;
        scroll.scrollTo(0, 0);
        // FocusScrollView schedules its focused-child visibility check. Run a
        // second reset afterward so the profile card remains fully visible.
        scroll.post(() -> { if (scroll != null) scroll.scrollTo(0, 0); });
    }

    private TextView sectionHead(LinearLayout parent, String title) {
        LinearLayout line = Ui.row(ctx);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(Ui.text(ctx, title, 20, Ui.TEXT, true));
        TextView count = Ui.text(ctx, "", 14, Ui.TEXT_3, false);
        LinearLayout.LayoutParams cl = Ui.lp(0, -2, 1); cl.leftMargin = Ui.dp(ctx, 10);
        line.addView(count, cl);
        LinearLayout.LayoutParams lp = Ui.lp(-1, Ui.dp(ctx, 44)); lp.topMargin = Ui.dp(ctx, 30);
        parent.addView(line, lp);
        return count;
    }

    private View clearButton(LinearLayout line, String label, Runnable action) {
        TextView b = Ui.action(ctx, label, IconView.TRASH, false);
        Ui.padding(b, 14, 0, 16, 0);
        b.setTextSize(13);
        b.setMinHeight(Ui.dp(ctx, 36));
        b.setOnClickListener(v -> action.run());
        line.addView(b, Ui.lp(-2, Ui.dp(ctx, 36)));
        return b;
    }

    private TextView empty(LinearLayout parent, String text) {
        TextView t = Ui.text(ctx, text, 15, Ui.TEXT_3, false);
        LinearLayout.LayoutParams lp = Ui.lp(-1, -2); lp.topMargin = Ui.dp(ctx, 10);
        parent.addView(t, lp);
        t.setVisibility(View.GONE);
        return t;
    }

    // ------------------------------------------------------------ 数据

    @Override public void onShow() {
        root.setBackgroundColor(Ui.BG);   // 不透出首页的大封面背景
        if (!Session.loggedIn()) {
            clearReservations();
        }
        renderProfile();
        renderLibrary();
        if (Session.loggedIn()) {
            App.io().execute(() -> {
                try { Account.profile(); host.runOnUiThread(this::renderProfile); } catch (Exception ignored) { }
            });
            ink.pengfei.lvguo.net.LibrarySync.pull();
            if (System.currentTimeMillis() - reservationsLoadedAt > 60_000) loadReservations();
        } else {
            renderReservationArea();
        }
    }

    private void renderProfile() {
        boolean in = Session.loggedIn();
        // 右上角那枚按钮：已登录=退出账号，匿名=登录
        relabelAccountTile(in ? "退出账号" : "登录", in ? IconView.LOGOUT : IconView.USER);
        if (!in) {
            name.setText("未登录");
            letter.setText("游");
            signature.setText("登录后同步手机红果的进度与收藏");
            meta.setVisibility(View.GONE);
            avatar.setImageDrawable(null);
            renderStats();
            return;
        }
        Session.Profile p = Session.profile();
        String nick = p.name == null || p.name.isEmpty() ? "红果用户" : p.name;
        name.setText(nick);
        letter.setText(nick.substring(0, nick.offsetByCodePoints(0, 1)));
        signature.setText(p.signature == null || p.signature.isEmpty() ? "" : p.signature);
        meta.setText("VIP 会员");
        meta.setVisibility(p.vip ? View.VISIBLE : View.GONE);
        renderStats();
        if (p.avatar != null && p.avatar.startsWith("http")) ImageLoader.load(p.avatar, avatar, Ui.dp(ctx, 160), Ui.dp(ctx, 160));
    }

    /** 更新右上角账号按钮的图标与文字（已登录=退出账号 / 匿名=登录）。 */
    private void relabelAccountTile(String label, int icon) {
        if (!(phoneButton instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) phoneButton;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof IconView) ((IconView) c).setType(icon);
            else if (c instanceof TextView) ((TextView) c).setText(label);
        }
        phoneButton.setContentDescription(label);
    }

    /** 头部右侧的数字：观看 / 收藏 / 预约 / 累计时长（或活跃天数）。 */
    private void renderStats() {
        if (stats == null) return;
        Session.Profile p = Session.profile();
        stats.removeAllViews();
        List<String[]> cells = new ArrayList<>();
        cells.add(new String[]{String.valueOf(Store.history().size()), "看过"});
        cells.add(new String[]{String.valueOf(Store.favorites().size()), "收藏"});
        cells.add(new String[]{reservationCount >= 0 ? String.valueOf(reservationCount) : "–", "预约"});   // 没加载完先放占位，不突然冒出来
        for (int i = 0; i < cells.size(); i++) {
            LinearLayout cell = Ui.column(ctx);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView num = Ui.text(ctx, cells.get(i)[0], 30, Ui.TEXT, true);
            num.setGravity(Gravity.CENTER);
            cell.addView(num, Ui.lp(-2, -2));
            TextView l = Ui.text(ctx, cells.get(i)[1], 14, Ui.TEXT_3, false);
            l.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ll = Ui.lp(-2, -2); ll.topMargin = Ui.dp(ctx, 4);
            cell.addView(l, ll);
            LinearLayout.LayoutParams cl = Ui.lp(-2, -2); if (i > 0) cl.leftMargin = Ui.dp(ctx, 44);
            stats.addView(cell, cl);
        }
    }

    private void renderLibrary() {
        List<Store.Item> history = Store.history();
        boolean restoreHistoryFocus = historyRow.hasFocusInside();
        historyRow.clear();
        for (int i = 0; i < Math.min(ROW_MAX, history.size()); i++) historyRow.add(historyCard(history.get(i)));
        if (history.size() > ROW_MAX) historyRow.add(moreTile("观看历史", history, true));
        historyCount.setText(history.isEmpty() ? "" : history.size() + " 部");
        historyEmpty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
        historyRow.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        historyClear.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        if (restoreHistoryFocus && historyRow.size() > 0 && !historyRow.focusRemembered()) historyRow.focusFirst();

        List<Store.Item> favs = Store.favorites();
        boolean restoreFavoriteFocus = favoriteRow.hasFocusInside();
        favoriteRow.clear();
        for (int i = 0; i < Math.min(ROW_MAX, favs.size()); i++) favoriteRow.add(favoriteCard(favs.get(i)));
        if (favs.size() > ROW_MAX) favoriteRow.add(moreTile("我的收藏", favs, false));
        favoriteCount.setText(favs.isEmpty() ? "" : favs.size() + " 部");
        favoriteEmpty.setVisibility(favs.isEmpty() ? View.VISIBLE : View.GONE);
        favoriteRow.setVisibility(favs.isEmpty() ? View.GONE : View.VISIBLE);
        favoriteClear.setVisibility(favs.isEmpty() ? View.GONE : View.VISIBLE);
        if (restoreFavoriteFocus && favoriteRow.size() > 0 && !favoriteRow.focusRemembered()) favoriteRow.focusFirst();
        renderStats();
        bindNav(navId);
    }

    /** 每行最多放 10 部，多出来的进「查看全部」页（网格，可长按删除）。 */
    private static final int ROW_MAX = 10;

    private View moreTile(String title, List<Store.Item> items, boolean history) {
        final List<Series> all = new ArrayList<>();
        final java.util.Map<String, Store.Item> byId = new java.util.HashMap<>();
        for (Store.Item it : items) { all.add(series(it.seriesId, it.title, it.cover, it.vid)); byId.put(it.seriesId, it); }
        return new ink.pengfei.lvguo.ui.widget.MoreTile(ctx, Ui.CARD_W, Ui.CARD_H, HomePage.coversAfter(all, ROW_MAX), items.size() - ROW_MAX, () -> {
            final ListPage[] page = new ListPage[1];
            page[0] = ListPage.of(title, all, (card, s) -> {
                Store.Item it = byId.get(s.seriesId);
                if (it == null) return;
                if (history) card.setBadge(progress(it));
                else if (it.progressTime > 0 && it.lastEpisode > 0) card.setBadge(progress(it));
                else if (it.episodeCount > 0) card.setBadge("共 " + it.episodeCount + " 集");
                card.enableRemove("移除", () -> {
                    Store.remove(history ? "history" : "fav", it.seriesId);
                    host.toast(history ? "已移除" : "已取消收藏，手机端同步");
                    page[0].remove(it.seriesId);
                });
            });
            host.openList(page[0]);
        });
    }

    /** 进度角标（压在封面上）：已看/全部，纯数字，如 56/619；总集数未知时用缓存的详情补，再没有就只写已看集数。 */
    static String progress(Store.Item it) {
        if (it.lastEpisode <= 0) return null;
        int total = it.episodeCount;
        if (total <= 0) { ink.pengfei.lvguo.net.Repo.Detail d = ink.pengfei.lvguo.net.Repo.cachedDetail(it.seriesId); if (d != null) total = d.episodes.size(); }
        return total > 0 ? it.lastEpisode + "/" + total : "第" + it.lastEpisode + "集";
    }

    private Series series(String id, String title, String cover, String vid) {
        Series s = new Series(); s.seriesId = id; s.title = title; s.cover = Store.bestCover(id, cover); s.firstVid = vid; return s;
    }

    private PosterCard historyCard(Store.Item it) {
        PosterCard card = new PosterCard(ctx, series(it.seriesId, it.title, it.cover, it.vid), Ui.CARD_W, Ui.CARD_H);
        card.setBadge(progress(it));
        card.setOnClickListener(v -> host.openDetail(series(it.seriesId, it.title, it.cover, it.vid)));
        card.enableRemove("移除", () -> removeAndRefocus("history", it.seriesId, historyRow, card, "已移除"));
        return card;
    }

    private PosterCard favoriteCard(Store.Item it) {
        PosterCard card = new PosterCard(ctx, series(it.seriesId, it.title, it.cover, it.vid), Ui.CARD_W, Ui.CARD_H);
        if (it.progressTime > 0 && it.lastEpisode > 0) card.setBadge(progress(it));
        else if (it.episodeCount > 0) card.setBadge("共 " + it.episodeCount + " 集");
        card.setOnClickListener(v -> host.openDetail(series(it.seriesId, it.title, it.cover, it.vid)));
        card.enableRemove("移除", () -> removeAndRefocus("fav", it.seriesId, favoriteRow, card, "已取消收藏，手机端同步"));
        return card;
    }

    private void loadReservations() {
        App.io().execute(() -> {
            try {
                Account.Reservations on = Account.reservations(true);
                Account.Reservations off = Account.reservations(false);
                reservationsLoadedAt = System.currentTimeMillis();
                host.runOnUiThread(() -> renderReservations(on, off));
            } catch (Exception e) {
                android.util.Log.w("MePage", "reservations failed: " + e.getMessage());
                if (!reservationsReady) host.runOnUiThread(() -> { reservationsFailed = true; renderReservationArea(); });
            }
        });
    }

    private void renderReservations(Account.Reservations on, Account.Reservations off) {
        onlineItems = on.items; offlineItems = off.items;
        onlineTotal = Math.max(on.onlineTotal, on.items.size());
        offlineTotal = Math.max(off.offlineTotal, off.items.size());
        reservationCount = onlineTotal + offlineTotal;
        reservationsReady = true; reservationsFailed = false;
        renderStats();
        if (reserveTab < 0) reserveTab = onlineItems.isEmpty() && !offlineItems.isEmpty() ? 1 : 0;   // 默认看有内容的那一边
        renderReservationArea();
    }

    private void selectReserveTab(int index) {
        if (reserveTab == index) return;
        reserveTab = index;
        renderReservationArea();
    }

    private void clearReservations() {
        onlineItems = Collections.emptyList();
        offlineItems = Collections.emptyList();
        onlineTotal = 0;
        offlineTotal = 0;
        reservationCount = -1;
        reservationsReady = false;
        reservationsFailed = false;
        reservationsLoadedAt = 0;
    }

    /** 按当前标签重画预约区：未登录 / 为空=一行说明，加载中=占位，失败=一行说明，否则一行卡片。 */
    private void renderReservationArea() {
        if (!Session.loggedIn()) {
            reserveTabs[0].setText("已上线");
            reserveTabs[1].setText("待上线");
            updateReserveTabs();
            reserveSkeleton.setVisibility(View.GONE);
            reserveRow.clear();
            reserveRow.setVisibility(View.GONE);
            reserveEmpty.setText("暂无预约");
            reserveEmpty.setVisibility(View.VISIBLE);
            return;
        }
        reserveTabs[0].setText(reservationsReady ? "已上线 " + onlineTotal : "已上线");
        reserveTabs[1].setText(reservationsReady ? "待上线 " + offlineTotal : "待上线");
        updateReserveTabs();
        reserveSkeleton.setVisibility(!reservationsReady && !reservationsFailed ? View.VISIBLE : View.GONE);
        if (!reservationsReady) {
            reserveRow.setVisibility(View.GONE);
            reserveEmpty.setText("预约加载失败");
            reserveEmpty.setVisibility(reservationsFailed ? View.VISIBLE : View.GONE);
            return;
        }
        boolean online = reserveTab == 0;
        List<Account.Entry> items = online ? onlineItems : offlineItems;
        boolean restoreFocus = reserveRow.hasFocusInside();
        reserveRow.clear();
        int n = 0;
        for (Account.Entry e : items) {
            if (n++ >= ROW_MAX) { reserveRow.add(reservationMore(online ? "我的预约 · 已上线" : "我的预约 · 待上线", items)); break; }
            PosterCard card = new PosterCard(ctx, series(e.seriesId, e.title, e.cover, e.vid), Ui.CARD_W, Ui.CARD_H);
            if (online) {
                card.setBadge(e.time > 0 ? date(e.time) + " 上线" : "已上线");
                card.setOnClickListener(v -> host.openDetail(series(e.seriesId, e.title, e.cover, e.vid)));
            } else {
                // 待上线：有官方文案用文案，有时间写时间，都没有就只写「待上线」；点卡进详情（那里可查看 / 取消预约，取消需确认）
                // 有预计上线时间就写时间；没有时间时官方文案带具体日期才用；「敬请期待」这类空话不显示
                String pub = e.publishText == null ? "" : e.publishText.trim();
                if (!pub.matches(".*\\d.*")) pub = "";
                if (pub.length() > 9 && pub.endsWith("上线")) pub = pub.substring(0, pub.length() - 2).trim();   // 角标放不下时省掉「上线」
                String when = e.time > 0 ? date(e.time) + " 上线" : !pub.isEmpty() ? pub : "待上线";
                card.setBadge(when);   // 上线时间放在封面角标，不单起一行
                card.setOnClickListener(v -> host.openDetail(series(e.seriesId, e.title, e.cover, e.vid)));
            }
            reserveRow.add(card);
        }
        reserveRow.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        reserveEmpty.setText("暂无预约");
        reserveEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (restoreFocus && reserveRow.size() > 0 && !reserveRow.focusRemembered()) reserveRow.focusFirst();
        bindNav(navId);
    }

    private View reservationMore(String title, List<Account.Entry> items) {
        List<Series> all = new ArrayList<>();
        for (Account.Entry e : items) all.add(series(e.seriesId, e.title, e.cover, e.vid));
        return new ink.pengfei.lvguo.ui.widget.MoreTile(ctx, Ui.CARD_W, Ui.CARD_H, HomePage.coversAfter(all, ROW_MAX), items.size() - ROW_MAX,
                () -> host.openList(ListPage.of(title, all, null)));
    }

    private static String date(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t > 10_000_000_000L ? t : t * 1000L);
        Calendar now = Calendar.getInstance();
        String md = (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
        return c.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? md : c.get(Calendar.YEAR) + "年" + md;
    }

    /** 移除一张卡片后重排这一行，焦点落在原位置（没有就前一张），不要跳回行首。 */
    private void removeAndRefocus(String key, String seriesId, HScrollRow row, View card, String toast) {
        int at = row.inner().indexOfChild(card);
        Store.remove(key, seriesId);
        host.toast(toast);
        renderLibrary();
        row.post(() -> {
            int n = row.size();
            if (n == 0) { focusFirst(); return; }
            View v = row.item(Math.max(0, Math.min(at, n - 1)));
            if (v != null) v.requestFocus();
        });
    }

    private void focusRow(HScrollRow row) { row.post(() -> { if (row.size() > 0) row.focusFirst(); }); }

    /** 从导航右键进来：先落在第一行海报（不先落在「退出登录」上，避免误按），往上才是退出登录。 */
    @Override public void focusFirst() {
        for (HScrollRow r : new HScrollRow[]{historyRow, favoriteRow, reserveRow})
            if (r.getVisibility() == View.VISIBLE && r.size() > 0 && ((View) r.getParent()).getVisibility() == View.VISIBLE) { scroll.smoothScrollTo(0, 0); r.focusFirst(); return; }
        if (phoneButton != null) phoneButton.requestFocus();
    }

    @Override public void bindNav(int id) {
        navId = id;
        for (HScrollRow r : new HScrollRow[]{historyRow, favoriteRow, reserveRow})
            if (r != null && r.size() > 0) r.item(0).setNextFocusLeftId(id);
    }

    /** 退出：只放在资料卡最右边，图标在上、「退出账号」在下；聚焦时红底白字。 */
    private View exitButton(View.OnClickListener click, Runnable onFocused) {
        LinearLayout t = Ui.column(ctx);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) t.setDefaultFocusHighlightEnabled(false);
        t.setContentDescription("退出账号");
        IconView ic = new IconView(ctx, IconView.LOGOUT, 24);
        t.addView(ic, Ui.lp(Ui.dp(ctx, 24), Ui.dp(ctx, 24)));
        TextView tt = Ui.text(ctx, "退出账号", 13, Ui.TEXT_2, false);
        LinearLayout.LayoutParams tl = Ui.lp(-2, -2); tl.topMargin = Ui.dp(ctx, 6);
        t.addView(tt, tl);
        Runnable paint = () -> {
            boolean f = t.isFocused();
            t.setBackground(f ? Ui.round(ctx, 0xFFFF453A, 16) : null);
            int fg = f ? 0xFFFFFFFF : Ui.TEXT_2;
            tt.setTextColor(fg); ic.setColor(fg);
        };
        paint.run();
        t.setOnFocusChangeListener((v, f) -> {
            paint.run();
            if (f && onFocused != null) onFocused.run();
        });
        t.setOnClickListener(click);
        return t;
    }
}
