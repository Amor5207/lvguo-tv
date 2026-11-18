package ink.pengfei.lvguo.ui.page;

import android.content.Intent;
import android.view.*;
import android.widget.*;
import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.net.*;
import ink.pengfei.lvguo.store.Store;
import ink.pengfei.lvguo.ui.*;
import ink.pengfei.lvguo.ui.widget.*;
import java.util.*;

/**
 * 首页：上方是跟随焦点变化的「看板」（大标题 + 集数热度 + 简介，背后是 CoverBackdrop 的主视觉），
 * 下方是「继续观看」横排和「为你推荐」海报墙。看板固定不滚动，只有下方内容区滚动。
 */
public class HomePage extends Page {
    private static final int BILLBOARD_DP = 188;   // 看板与第一行之间留足间距，不挤在一起
    private static final String[] FEEDS={"","cate_36","cate_37","cate_395","cate_739","cate_308","cate_165","cate_760","cate_1024","cate_1040","cate_19","cate_466","cate_43","cate_273","cate_127","cate_390","cate_303","cate_1038","cate_1049"};

    private ScrollView scroll;
    private LinearLayout column, resumeSection;
    private HScrollRow resumeRow;
    private CardGrid grid;
    private TextView more, status;
    private TextView eyebrow, title, meta, intro;
    private LinearLayout info;
    private View first, gridTitle;
    private boolean loading, started, hasMore = true, billboardFromUser, autoFocus = true;
    private int cursor, navId, generation, feedIndex, feedsVisited, emptyPages;
    private String session = UUID.randomUUID().toString();
    private final Set<String> seen = new HashSet<>();
    private String shownId = "";

    @Override protected View build() {
        FrameLayout root = new FrameLayout(ctx);
        // 内容区滚动时必须裁剪在看板下方，否则会画到看板文字上。
        root.setClipChildren(true);

        // ---------- 看板（固定）
        info = Ui.column(ctx);
        info.setPadding(Ui.dp(ctx, 48), Ui.dp(ctx, 28), 0, 0);
        eyebrow = Ui.text(ctx, "", 13, Ui.ACCENT_TEXT, true);
        eyebrow.setLetterSpacing(.08f);
        info.addView(eyebrow, Ui.lp(-2, -2));
        title = Ui.text(ctx, "", 28, Ui.TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1f);
        title.setLetterSpacing(-.01f);
        LinearLayout.LayoutParams tl = Ui.lp(-1, -2); tl.topMargin = Ui.dp(ctx, 10);
        info.addView(title, tl);
        meta = Ui.text(ctx, "", 15, Ui.TEXT_2, false);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams ml = Ui.lp(-1, -2); ml.topMargin = Ui.dp(ctx, 10);
        info.addView(meta, ml);
        intro = Ui.ellipsis(ctx, "", 14, Ui.TEXT_2, 2);
        intro.setLineSpacing(Ui.dp(ctx, 4), 1f);
        LinearLayout.LayoutParams il = Ui.lp(-1, -2); il.topMargin = Ui.dp(ctx, 10);
        info.addView(intro, il);
        FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(Ui.dp(ctx, 560), Ui.dp(ctx, BILLBOARD_DP));   // 文字留在封面渐隐区内，不压到人脸
        root.addView(info, infoLp);

        // 右上角：搜索 / 手机遥控（图标 + 文字，含义一眼能看懂）；看板文字收窄到 560dp，不会被挡住
        LinearLayout tools = Ui.row(ctx);
        searchButton = topButton("搜索短剧", IconView.SEARCH, v -> host.openSearch());
        TextView phone = topButton("手机遥控", IconView.PHONE, v -> ink.pengfei.lvguo.remote.Pairing.show(host));
        tools.addView(searchButton, Ui.lp(-2, -2));
        LinearLayout.LayoutParams pl = Ui.lp(-2, -2); pl.leftMargin = Ui.dp(ctx, 12);
        tools.addView(phone, pl);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.END);
        // 焦点只改变按钮颜色与字重，容器按实际按钮边界定位。
        tlp.topMargin = Ui.dp(ctx, 24); tlp.rightMargin = Ui.dp(ctx, 48);
        root.addView(tools, tlp);
        // 首行海报往上 → 右上角按钮；按钮往下 → 回到海报
        searchButton.setNextFocusRightId(phone.getId()); phone.setNextFocusLeftId(searchButton.getId());
        phone.setNextFocusRightId(phone.getId()); phone.setNextFocusUpId(phone.getId()); searchButton.setNextFocusUpId(searchButton.getId());
        first = null;

        // ---------- 可滚动内容
        // 海报聚焦时由 snapTo() 按行对齐滚动；其它控件仍用系统的"滚到可见"。
        scroll = new FocusScrollView(ctx) {
            @Override protected boolean shouldScrollOnFocus(View focused) {
                return !(focused instanceof PosterCard);
            }
        };
        scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER); scroll.setClipToPadding(true); scroll.setClipChildren(true); scroll.setFocusable(false);
        scroll.setVerticalFadingEdgeEnabled(true); scroll.setFadingEdgeLength(Ui.dp(ctx, 18));
        column = Ui.column(ctx);
        column.setPadding(Ui.dp(ctx, 48), Ui.dp(ctx, 6), Ui.dp(ctx, 48), Ui.dp(ctx, 40));   // 看板简介两行完整显示；第一行分区紧跟其后
        column.setClipChildren(false);
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -1);
        sl.topMargin = Ui.dp(ctx, BILLBOARD_DP);
        root.addView(scroll, sl);

        resumeSection = Ui.column(ctx);
        resumeSection.setClipChildren(false);
        resumeSection.addView(sectionTitle("继续观看", null), Ui.lp(-1, -2));
        resumeRow = new HScrollRow(ctx, 18);
        resumeSection.addView(resumeRow, Ui.lp(-1, -2));
        LinearLayout.LayoutParams rsl = Ui.lp(-1, -2); rsl.bottomMargin = Ui.dp(ctx, 18);
        column.addView(resumeSection, rsl);
        resumeSection.setVisibility(View.GONE);

        // 首页只放：继续观看 / 为你推荐。榜单在「榜单」页、收藏在「我的」页，不在首页重复

        // 「为你推荐」标题后面跟频道（推荐 / 漫剧 / 真人剧，按账号下发；只有一个频道时不显示）
        LinearLayout gt = Ui.row(ctx);
        gt.setGravity(Gravity.CENTER_VERTICAL);
        gt.addView(Ui.text(ctx, "为你推荐", 19, Ui.TEXT, true), Ui.lp(-2, -2));
        channelRow = Ui.row(ctx);
        channelRow.setClipChildren(false);
        LinearLayout.LayoutParams crl = Ui.lp(-2, -2); crl.leftMargin = Ui.dp(ctx, 16);
        gt.addView(channelRow, crl);
        gt.setMinimumHeight(Ui.dp(ctx, 44));
        gt.setClipChildren(false);
        Ui.padding(gt, 0, 0, 0, 6);
        gridTitle = gt;
        column.addView(gridTitle, Ui.lp(-1, -2));

        grid = new CardGrid(ctx, Ui.GRID_CARD_W, Ui.GRID_CARD_H, 18);
        grid.setLeftAligned(true);
        grid.setOnPick(host::openDetail);
        grid.setOnBindCard((card, s) -> {
            card.setOnHighlighted(() -> ink.pengfei.lvguo.media.PlaybackWarmup.schedule(s.seriesId));
            card.setOnFocused(() -> { billboardFromUser = true; showInfo(s, rankLine(s)); snapTo(card); });
        });
        column.addView(grid, Ui.lp(-1, -2));
        status = Ui.text(ctx, "", 13, Ui.TEXT_3, false);
        status.setGravity(Gravity.CENTER);
        Ui.padding(status, 0, 16, 0, 8);
        column.addView(status, Ui.lp(-1, -2));
        more = Ui.action(ctx, "继续发现", -1, false);
        more.setVisibility(View.GONE);   // 自动加载，不需要手动点
        grid.setOnNearEnd(this::loadMore);
        more.setOnClickListener(v -> loadMore());
        LinearLayout.LayoutParams mlp = Ui.lp(-2, Ui.dp(ctx, 44)); mlp.gravity = Gravity.CENTER_HORIZONTAL; mlp.topMargin = Ui.dp(ctx, 8);
        column.addView(more, mlp);
        scroll.getViewTreeObserver().addOnScrollChangedListener(this::requestMoreIfNear);
        return root;
    }

    private LinearLayout channelRow;
    private String channel = Browse.CH_RECOMMEND;
    private final List<TextView> channelChips = new ArrayList<>();
    private boolean channelsLoaded;

    private void loadChannels() {
        if (channelsLoaded || !Session.loggedIn()) return;
        channelsLoaded = true;
        App.io().execute(() -> {
            final List<String[]> list = Browse.feedChannels();
            host.runOnUiThread(() -> { if (root != null && list.size() > 1) buildChannels(list); else if (list.size() <= 1) channelsLoaded = false; });
        });
    }

    private void buildChannels(List<String[]> list) {
        channelRow.removeAllViews(); channelChips.clear();
        for (final String[] ch : list) {
            final TextView chip = Ui.text(ctx, ch[1], 15, Ui.TEXT_2, false);
            chip.setSingleLine(true); chip.setFocusable(true); chip.setId(View.generateViewId());
            chip.setIncludeFontPadding(false); chip.setGravity(Gravity.CENTER);
            Ui.padding(chip, 12, 0, 12, 0);
            chip.setTag(ch[0]);
            if (android.os.Build.VERSION.SDK_INT >= 26) chip.setDefaultFocusHighlightEnabled(false);
            chip.setOnFocusChangeListener((v, has) -> paintChannels());
            chip.setOnClickListener(v -> { if (!ch[0].equals(channel)) { channel = ch[0]; paintChannels(); reset(); } });
            LinearLayout.LayoutParams lp = Ui.lp(-2, Ui.dp(ctx, 32)); lp.rightMargin = Ui.dp(ctx, 4);
            channelRow.addView(chip, lp); channelChips.add(chip);
        }
        if (!channelChips.isEmpty()) {
            channelChips.get(0).setNextFocusLeftId(navId);
            TextView last = channelChips.get(channelChips.size() - 1); last.setNextFocusRightId(last.getId());
        }
        paintChannels();
    }

    /** 与分类页筛选项同一套状态：聚焦实心高亮，选中品牌色淡底，其余只留文字。 */
    private void paintChannels() {
        for (TextView v : channelChips) {
            boolean on = channel.equals(v.getTag()), focus = v.hasFocus();
            if (focus) { v.setBackground(Ui.pill(ctx, Ui.FOCUS_FILL)); v.setTextColor(Ui.ON_FOCUS); }
            else if (on) { v.setBackground(Ui.pill(ctx, Ui.TINT_PRIMARY)); v.setTextColor(Ui.ACCENT_TEXT); }
            else { v.setBackground(null); v.setTextColor(Ui.TEXT_2); }
            v.setTypeface(null, focus || on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private View sectionTitle(String text, View action) {
        LinearLayout row = Ui.row(ctx);
        row.addView(Ui.text(ctx, text, 19, Ui.TEXT, true), Ui.lp(0, -2, 1));
        if (action != null) row.addView(action, Ui.lp(-2, Ui.dp(ctx, 38)));
        // Keep section title rows the same height.
        row.setMinimumHeight(Ui.dp(ctx, 44));
        Ui.padding(row, 0, 0, 0, 6);
        return row;
    }

    /** 让聚焦卡片所在的整行（含分区标题）对齐到内容区顶部，避免出现被切掉一半的行。 */
    private HScrollRow favShelf, hotShelf, newShelf;
    private boolean boardsLoaded;

    /** 一行横向海报（标题 + 卡片行），默认隐藏，有数据再显示。 */
    private HScrollRow shelf(String title) {
        LinearLayout sec = Ui.column(ctx);
        sec.setClipChildren(false);
        sec.addView(sectionTitle(title, null), Ui.lp(-1, -2));
        HScrollRow row = new HScrollRow(ctx, 18);
        sec.addView(row, Ui.lp(-1, -2));
        LinearLayout.LayoutParams lp = Ui.lp(-1, -2); lp.bottomMargin = Ui.dp(ctx, 18);
        column.addView(sec, lp);
        sec.setVisibility(View.GONE);
        return row;
    }

    /** 横向行只放前 SHELF_MAX 部，超出时最后一张是「查看全部」，进入完整列表页。 */
    private static final int SHELF_MAX = 10;

    private void fillShelf(HScrollRow row, List<Series> items, String title, ink.pengfei.lvguo.ui.page.ListPage.Source all, boolean moreKnown) {
        boolean hadFocus = row.hasFocusInside();
        row.clear();
        int n = 0;
        for (final Series s : items) {
            if (n++ >= SHELF_MAX) break;
            PosterCard card = new PosterCard(ctx, s, Ui.CARD_W, Ui.CARD_H);
            card.setOnFocused(() -> { billboardFromUser = true; showInfo(s, rankLine(s)); snapTo(card); });
            card.setOnHighlighted(() -> ink.pengfei.lvguo.media.PlaybackWarmup.schedule(s.seriesId));
            card.setOnClickListener(v -> host.openDetail(s));
            if (n == 1) card.setNextFocusLeftId(navId);
            row.add(card);
        }
        if (row.size() > 0 && (items.size() > SHELF_MAX || moreKnown)) addMoreTile(row, title, all, items, moreKnown ? 0 : items.size() - SHELF_MAX);
        ((View) row.getParent()).setVisibility(row.size() > 0 ? View.VISIBLE : View.GONE);
        if (hadFocus && row.size() > 0 && !row.focusRemembered()) row.focusFirst();
    }

    /** 拼图用「还没露出来」的那几部（第 11 部起）的封面。 */
    static List<String> coversAfter(List<Series> items, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < items.size() && out.size() < 4; i++) out.add(items.get(i).cover);
        for (int i = 0; i < items.size() && out.size() < 4; i++) out.add(items.get(i).cover);
        return out;
    }

    private void addMoreTile(HScrollRow row, String title, ink.pengfei.lvguo.ui.page.ListPage.Source all, List<Series> items, int remaining) {
        ink.pengfei.lvguo.ui.widget.MoreTile tile = new ink.pengfei.lvguo.ui.widget.MoreTile(ctx, Ui.CARD_W, Ui.CARD_H, coversAfter(items, SHELF_MAX), remaining,
                () -> host.openList(new ink.pengfei.lvguo.ui.page.ListPage(title, all, null)));
        tile.setOnFocusChangeListener(chain(tile.getOnFocusChangeListener(), (v, has) -> { if (has) snapTo(v); }));
        row.add(tile);
    }

    private static View.OnFocusChangeListener chain(View.OnFocusChangeListener a, View.OnFocusChangeListener b) {
        return (v, has) -> { if (a != null) a.onFocusChange(v, has); b.onFocusChange(v, has); };
    }

    private static List<Series> copy(List<Series> l) { return new ArrayList<>(l); }

    /** 榜单分页数据源（查看全部页用，滚到底自动续页）。 */
    private static ink.pengfei.lvguo.ui.page.ListPage.Source board(String board) {
        final String[] session = {UUID.randomUUID().toString()};
        return cursor -> {
            Browse.Result r = Browse.page(board, "", cursor, session[0]);
            if (r.session != null && !r.session.isEmpty()) session[0] = r.session;
            return new ink.pengfei.lvguo.ui.page.ListPage.Chunk(r.items, r.next, r.more && !r.items.isEmpty());
        };
    }

    private void renderShelves() {
        if (favShelf == null) return;
        List<Series> favs = new ArrayList<>();
        for (Store.Item it : Store.favorites()) { Series s = new Series(); s.seriesId = it.seriesId; s.title = it.title; s.cover = Store.bestCover(it.seriesId, it.cover); s.firstVid = it.vid; s.episodeCount = it.episodeCount; favs.add(s); }
        final List<Series> favAll = copy(favs);
        fillShelf(favShelf, favs, "我的收藏", c -> new ink.pengfei.lvguo.ui.page.ListPage.Chunk(favAll, favAll.size(), false), false);
    }

    /**
     * 让聚焦的卡片所在的分区（或网格里的那一行）滚到可见区顶部。
     * 之前只认「继续观看」和网格两种卡片，收藏/热播/新剧几行的卡片会算错位置 → 页面不滚动、焦点跑到屏幕外。
     */
    private void snapTo(View card) {
        View section = card;
        while (section.getParent() instanceof View && section.getParent() != column) section = (View) section.getParent();
        if (section.getParent() != column) return;
        int y;
        if (section == grid) {
            View row = card;
            while (row.getParent() instanceof View && row.getParent() != grid) row = (View) row.getParent();
            y = row == grid.getChildAt(0) ? gridTitle.getTop() : grid.getTop() + row.getTop() - Ui.dp(ctx, 14);
        } else y = section.getTop();
        // 最上面一个分区：保持在顶部不动（之前会往上滚 12dp，导致简介和分区标题的间距忽大忽小）
        View firstVisible = null;
        for (int i = 0; i < column.getChildCount() && firstVisible == null; i++) if (column.getChildAt(i).getVisibility() == View.VISIBLE) firstVisible = column.getChildAt(i);
        boolean atTop = section == firstVisible || (section == grid && firstVisible == gridTitle && y == gridTitle.getTop());
        scroll.smoothScrollTo(0, atTop ? 0 : Math.max(0, y - Ui.dp(ctx, 6)));
        // 兜底：动画被打断或布局刚变化时，确认卡片确实在可见区内，不在就直接跳过去
        card.postDelayed(() -> {
            if (!card.hasFocus() || !card.isAttachedToWindow()) return;
            int top = 0;
            for (View v = card; v != null && v != scroll; v = v.getParent() instanceof View ? (View) v.getParent() : null) top += v.getTop();
            int sy = scroll.getScrollY(), h = scroll.getHeight();
            if (top < sy || top + card.getHeight() > sy + h) scroll.smoothScrollTo(0, Math.max(0, top - Ui.dp(ctx, 50)));
        }, 450);
    }

    private static boolean isInside(View parent, View v) {
        for (View cur = v; cur != null; cur = cur.getParent() instanceof View ? (View) cur.getParent() : null) if (cur == parent) return true;
        return false;
    }

    private static String rankLine(Series s) {
        return s.showRank && s.rank > 0 ? "热播榜 TOP " + s.rank : "今日推荐";
    }

    /** 看板换成某部剧的资料；同一部剧不重复刷新，避免焦点来回时闪动。 */
    private void showInfo(Series s, String kicker) {
        if (s == null) return;
        String id = s.seriesId == null ? "" : s.seriesId;
        eyebrow.setText(kicker == null ? "" : kicker);
        if (id.equals(shownId)) return;
        shownId = id;
        title.setText(s.title == null ? "" : s.title);
        List<String> parts = new ArrayList<>();
        if (s.episodeCount > 0) parts.add("全 " + s.episodeCount + " 集");
        String heat = s.playText();
        if (!heat.isEmpty()) parts.add(heat);
        parts.addAll(tags(s.category, 3));
        meta.setText(android.text.TextUtils.join("  ·  ", parts));
        intro.setText(s.intro == null ? "" : s.intro.trim());
        intro.setVisibility(s.intro == null || s.intro.trim().isEmpty() ? View.GONE : View.VISIBLE);
        // 简介最多两行；标题固定一行
        intro.setMaxLines(2);
        title.setMaxLines(1);
        info.setAlpha(.35f);
        info.animate().cancel();
        info.animate().alpha(1f).setDuration(220).start();
        host.setCoverBackground(s.cover);
        // 冷启动时片库还没缓存好，「继续观看」的剧没有简介/集数/题材：单独拉一次详情补上（每部剧只补一次）
        if ((s.intro == null || s.intro.trim().isEmpty() || s.episodeCount <= 0 || s.playCount <= 0) && !id.isEmpty() && detailFilled.add(id)) {
            final String k = kicker;
            App.io().execute(() -> {
                try {
                    Repo.Detail d = Repo.detail(id);
                    if (s.intro == null || s.intro.trim().isEmpty()) s.intro = d.intro;
                    if (s.episodeCount <= 0) s.episodeCount = d.episodes.size();
                    if (s.playCount <= 0) s.playCount = d.playCount;
                    host.runOnUiThread(() -> { if (id.equals(shownId)) { shownId = ""; showInfo(s, k); } });
                    // 题材来自片库缓存：冷启动时片库几秒后才预热好，最多等 10 秒，拿到了再刷新一次
                    for (int i = 0; i < 20 && (s.category == null || s.category.isEmpty()); i++) {
                        List<Series> all = Catalog.cached();
                        if (all != null) for (Series x : all) if (id.equals(x.seriesId) && x.category != null && !x.category.isEmpty()) { s.category = x.category; break; }
                        if (s.category == null || s.category.isEmpty()) Thread.sleep(500);
                    }
                    if (s.category != null && !s.category.isEmpty())
                        host.runOnUiThread(() -> { if (id.equals(shownId)) { shownId = ""; showInfo(s, k); } });
                } catch (Exception ignored) { }
            });
        }
    }
    private final Set<String> detailFilled = new HashSet<>();

    private static List<String> tags(String raw, int max) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        if (!raw.trim().startsWith("[")) {   // 已转成「脑洞,重生」的纯文本
            for (String n : raw.split("[,，|/]")) { n = n.trim(); if (!n.isEmpty() && out.size() < max) out.add(n); }
            return out;
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length() && out.size() < max; i++) {
                org.json.JSONObject t = arr.optJSONObject(i);
                String name = t == null ? "" : t.optString("name");
                if (!name.isEmpty()) out.add(name);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void reset() {
        emptyPages = 0; generation++; cursor = 0; feedIndex = (feedIndex + 1) % FEEDS.length; feedsVisited = 0;
        session = UUID.randomUUID().toString(); seen.clear(); billboardFromUser = false; shownId = "";
        grid.setData(null); grid.showLoading(); loading = false; hasMore = true; loadMore();
        scroll.scrollTo(0, 0);
    }

    private void requestMoreIfNear() {
        if (scroll.isShown() && started && !loading && hasMore && emptyPages < 3
                && column.getHeight() - scroll.getScrollY() - scroll.getHeight() < Math.max(scroll.getHeight(), Ui.dp(ctx, 650))) loadMore();
    }

    private final Store.Listener libraryListener = () -> { if (root != null && root.isShown()) { renderContinue(); renderShelves(); } };
    private boolean listening;

    @Override public void onShow() {
        if (!listening) { listening = true; Store.listen(libraryListener); }
        renderContinue();
        renderShelves();
        if (started) return;
        started = true;
        int visit = App.prefs().getInt("home_feed_visit", 0);
        feedIndex = Math.max(0, visit) % FEEDS.length;
        App.prefs().edit().putInt("home_feed_visit", (visit + 1) % FEEDS.length).apply();
        grid.showLoading(); loadMore(); loadChannels();
    }

    /** 继续观看：本地历史前 12 部，点卡片直接从上次位置续播。 */
    private void renderContinue() {
        List<Store.Item> history = Store.history();
        boolean hadFocus = resumeRow.hasFocusInside();
        resumeRow.clear();
        Map<String, Series> catalog = new HashMap<>();
        List<Series> cached = Catalog.cached();
        if (cached != null) for (Series s : cached) catalog.put(s.seriesId, s);
        int n = 0;
        final List<Series> unfinished = new ArrayList<>();
        final java.util.Set<String> completedIds = Store.completedIds();
        for (final Store.Item item : history) {
            // 已经整部看完的不再出现在「继续观看」（看到最后一集，或标记过已看完）
            if (completedIds.contains(item.seriesId)) continue;
            if (item.episodeCount > 0 && item.lastEpisode >= item.episodeCount && (item.durationMs <= 0 || item.positionMs >= item.durationMs - 15000)) continue;
            if (n++ >= SHELF_MAX) { unfinished.add(seriesOf(item)); continue; }
            unfinished.add(seriesOf(item));
            final Series s = new Series();
            Series known = catalog.get(item.seriesId);
            s.seriesId = item.seriesId; s.title = item.title;
            s.cover = Store.bestCover(item.seriesId, item.cover);
            if (known != null) { s.intro = known.intro; s.episodeCount = known.episodeCount; s.playCount = known.playCount; s.category = known.category; }
            PosterCard card = new PosterCard(ctx, s, Ui.CARD_W, Ui.CARD_H);
            String prog = MePage.progress(item);
            if (prog != null) card.setBadge(prog);   // 进度压在封面右下角，标题下不再放文字
            card.setOnFocused(() -> { billboardFromUser = true; showInfo(s, item.lastEpisode > 0 ? "继续观看 · 第 " + item.lastEpisode + " 集" : "继续观看"); snapTo(card); });
            card.setOnClickListener(v -> {
                Intent intent = new Intent(host, ink.pengfei.lvguo.PlayerActivity.class);
                intent.putExtra("series_id", item.seriesId); intent.putExtra("title", item.title);
                intent.putExtra("cover", s.cover); intent.putExtra("index", item.episodeIndex);
                if (item.vid != null && !item.vid.isEmpty()) {
                    intent.putExtra(ink.pengfei.lvguo.PlayerActivity.EXTRA_VID, item.vid);
                    intent.putExtra(ink.pengfei.lvguo.PlayerActivity.EXTRA_EPISODE_NO, item.lastEpisode);
                }
                intent.putExtra("position_ms", item.positionMs);
                host.startActivity(intent);
            });
            if (n == 1) card.setNextFocusLeftId(navId);
            resumeRow.add(card);
        }
        if (unfinished.size() > SHELF_MAX) addMoreTile(resumeRow, "继续观看", c -> new ink.pengfei.lvguo.ui.page.ListPage.Chunk(unfinished, unfinished.size(), false), unfinished, unfinished.size() - SHELF_MAX);
        resumeSection.setVisibility(resumeRow.size() > 0 ? View.VISIBLE : View.GONE);
        if (hadFocus && resumeRow.size() > 0 && !resumeRow.focusRemembered()) resumeRow.focusFirst();
        if (!billboardFromUser && !history.isEmpty()) {
            Store.Item top = history.get(0);
            Series s = new Series(); s.seriesId = top.seriesId; s.title = top.title; s.cover = Store.bestCover(top.seriesId, top.cover);
            Series known = catalog.get(top.seriesId);
            if (known != null) { s.intro = known.intro; s.episodeCount = known.episodeCount; s.playCount = known.playCount; s.category = known.category; }
            shownId = ""; showInfo(s, top.lastEpisode > 0 ? "继续观看 · 第 " + top.lastEpisode + " 集" : "继续观看");
        }
    }

    private static Series seriesOf(Store.Item it) {
        Series s = new Series(); s.seriesId = it.seriesId; s.title = it.title; s.cover = Store.bestCover(it.seriesId, it.cover); s.firstVid = it.vid; s.episodeCount = it.episodeCount; return s;
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        loading = true;
        final int token = generation, offset = cursor;
        final String sid = session, filter = FEEDS[feedIndex];
        more.setText("•••"); status.setText(""); grid.showTailLoading();
        App.io().execute(() -> {
            try {
                // 登录后用账号的个性化推荐流（与红果手机首页同源）；失败才退回推荐榜
                Browse.Result fetched;
                // 漫剧 / 真人剧频道失败时不拿榜单顶替（内容对不上），直接报错可重试
                final String ch = channel;
                try { fetched = Session.loggedIn() ? Browse.forYou(ch, offset, offset == 0) : Browse.page(Repo.BOARD_RECOMMEND, filter, offset, sid); }
                catch (Exception e) { if (!Browse.CH_RECOMMEND.equals(ch)) throw e; fetched = Browse.page(Repo.BOARD_RECOMMEND, filter, offset, sid); }
                final Browse.Result result = fetched;
                host.runOnUiThread(() -> {
                    if (token != generation) return;
                    loading = false; cursor = result.next; session = result.session; hasMore = result.more;
                    if (!hasMore && ++feedsVisited < FEEDS.length) { feedIndex = (feedIndex + 1) % FEEDS.length; cursor = 0; session = UUID.randomUUID().toString(); hasMore = true; }
                    List<Series> add = new ArrayList<>();
                    for (Series s : result.items) if (seen.add(s.seriesId)) add.add(s);
                    emptyPages = add.isEmpty() ? emptyPages + 1 : 0;
                    grid.append(add); grid.setLeftNavId(navId);
                    if (!billboardFromUser && shownId.isEmpty() && !add.isEmpty()) showInfo(add.get(0), rankLine(add.get(0)));
                    if (autoFocus && !add.isEmpty()) {
                        autoFocus = false;
                        View f = host.getWindow().getDecorView().findFocus();
                        if (host.currentTab() == RootActivity.TAB_HOME && !host.isOverlayOpen() && f == null) focusFirst();
                    }
                    status.setText(""); status.setVisibility(View.GONE);
                    more.setText("加载更多"); more.setVisibility(View.GONE);
                    more.setOnClickListener(v -> loadMore());
                    scroll.post(this::requestMoreIfNear);
                });
            } catch (Exception e) {
                host.runOnUiThread(() -> {
                    if (token != generation) return;
                    loading = false; grid.finishLoading();
                    status.setVisibility(View.VISIBLE); status.setText("推荐加载失败，请检查网络");
                    more.setText("重试");
                });
            }
        });
    }

    /** 从上往下第一个有内容的分区（继续观看 → 收藏 → 热播 → 新剧 → 为你推荐）。 */
    @Override public void focusFirst() {
        for (HScrollRow r : new HScrollRow[]{resumeRow, favShelf, hotShelf, newShelf})
            if (r != null && r.size() > 0 && ((View) r.getParent()).getVisibility() == View.VISIBLE) { r.focusFirst(); scroll.smoothScrollTo(0, 0); return; }
        if (grid.count() > 0) grid.focusFirst();
        else if (first != null) first.requestFocus();
    }

    @Override public void bindNav(int id) {
        navId = id; grid.setLeftNavId(id);
        if (!channelChips.isEmpty()) channelChips.get(0).setNextFocusLeftId(id);
        for (HScrollRow r : new HScrollRow[]{resumeRow, favShelf, hotShelf, newShelf}) if (r != null && r.size() > 0) r.item(0).setNextFocusLeftId(id);
    }

    private TextView searchButton;

    /** 右上角按钮：常驻实底（压在封面大图上也清楚），聚焦时主题色。 */
    private TextView topButton(String text, int icon, View.OnClickListener click) {
        TextView b = Ui.action(ctx, text, icon, false);
        b.setId(View.generateViewId());
        b.setBackground(Ui.pill(ctx, Ui.SURFACE));
        b.setOnFocusChangeListener((v, f) -> { Ui.buttonState(b, f, false); if (!f) b.setBackground(Ui.pill(ctx, Ui.SURFACE)); });
        b.setOnClickListener(click);
        return b;
    }

    /** 从搜索页返回：焦点回到右上角的「搜索」。 */
    public void focusSearchButton() { if (searchButton != null) searchButton.post(searchButton::requestFocus); }
}
