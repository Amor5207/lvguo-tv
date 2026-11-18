package ink.pengfei.lvguo.ui.page;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.ui.Page;
import ink.pengfei.lvguo.ui.Ui;
import ink.pengfei.lvguo.ui.widget.CardGrid;
import ink.pengfei.lvguo.ui.widget.FocusScrollView;
import ink.pengfei.lvguo.ui.widget.PosterCard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「查看全部」页：横向行里只放前 10 部，最后一张「查看全部」进到这里，用网格一次看全。
 * 数据可以是一份现成的列表（观看历史、收藏），也可以是分页接口（榜单、推荐）——滚到底自动加载，不用按「加载更多」。
 */
public class ListPage extends Page {

    /** 分页数据源：返回这一页的剧，以及下一页的游标（没有更多时 more=false）。 */
    public interface Source { Chunk load(int cursor) throws Exception; }
    public static final class Chunk {
        public final List<Series> items; public final int next; public final boolean more;
        public Chunk(List<Series> items, int next, boolean more) { this.items = items; this.next = next; this.more = more; }
    }
    /** 给每张卡片额外绑定行为（比如长按删除），可为空。 */
    public interface Binder { void bind(PosterCard card, Series s); }

    private final String title;
    private final Source source;
    private final Binder binder;
    private CardGrid grid;
    private TextView count, status;
    private ScrollView scroll;
    private final Set<String> seen = new HashSet<>();
    private int cursor, generation;
    private boolean loading, more = true, started;

    public ListPage(String title, Source source, Binder binder) { this.title = title; this.source = source; this.binder = binder; }

    /** 现成列表的便捷写法。 */
    public static ListPage of(String title, List<Series> all, Binder binder) {
        return new ListPage(title, cursor -> new Chunk(new ArrayList<>(all), all.size(), false), binder);
    }

    @Override protected View build() {
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Ui.BG);
        root.setTag("ListPage");
        scroll = new FocusScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout col = Ui.column(ctx);
        Ui.padding(col, 64, 40, 64, 48);
        col.setClipChildren(false);
        LinearLayout head = Ui.row(ctx);
        head.setGravity(Gravity.BOTTOM);
        head.addView(Ui.text(ctx, title, 28, Ui.TEXT, true));
        count = Ui.text(ctx, "", 15, Ui.TEXT_3, false);
        LinearLayout.LayoutParams cl = Ui.lp(-2, -2); cl.leftMargin = Ui.dp(ctx, 14); cl.bottomMargin = Ui.dp(ctx, 4);
        head.addView(count, cl);
        col.addView(head, Ui.lp(-1, -2));
        TextView tip = Ui.text(ctx, "按返回键回到上一页", 13, Ui.TEXT_3, false);
        LinearLayout.LayoutParams tl = Ui.lp(-2, -2); tl.topMargin = Ui.dp(ctx, 6);
        col.addView(tip, tl);
        grid = new CardGrid(ctx, Ui.GRID_CARD_W, Ui.GRID_CARD_H, 18);
        grid.setLeftAligned(true);
        grid.setOnPick(host::openDetail);
        if (binder != null) grid.setOnBindCard(binder::bind);
        grid.setOnNearEnd(this::load);
        LinearLayout.LayoutParams gl = Ui.lp(-1, -2); gl.topMargin = Ui.dp(ctx, 24);
        col.addView(grid, gl);
        status = Ui.text(ctx, "", 14, Ui.TEXT_3, false);
        status.setGravity(Gravity.CENTER);
        Ui.padding(status, 0, 18, 0, 0);
        col.addView(status, Ui.lp(-1, -2));
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            View c = scroll.getChildAt(0);
            if (c != null && c.getHeight() - scroll.getScrollY() - scroll.getHeight() < Ui.dp(ctx, 500)) load();
        });
        return root;
    }

    @Override public void onShow() {
        if (started) return;
        started = true;
        grid.showLoading();
        load();
    }

    private void load() {
        if (loading || !more) return;
        loading = true;
        final int token = generation, at = cursor;
        App.io().execute(() -> {
            try {
                Chunk c = source.load(at);
                host.runOnUiThread(() -> {
                    if (token != generation) return;
                    loading = false; cursor = c.next; more = c.more;
                    List<Series> add = new ArrayList<>();
                    for (Series s : c.items) if (s.seriesId != null && seen.add(s.seriesId)) add.add(s);
                    boolean first = grid.count() == 0;
                    grid.append(add);
                    count.setText(more ? "" : "共 " + seen.size() + " 部");
                    status.setText(seen.isEmpty() ? "这里还没有内容" : more ? "" : "已经到底了");
                    if (first && grid.count() > 0) grid.focusFirst();
                    // 这一页都是重复的、或者还不够铺满三屏行，就继续往下取（不用等用户滚到底）
                    if (more && (add.isEmpty() || seen.size() < 30)) load();
                });
            } catch (Exception e) {
                host.runOnUiThread(() -> { if (token != generation) return; loading = false; grid.finishLoading(); status.setText("加载失败，稍后再试"); });
            }
        });
    }

    /** 删除一部（例如从观看历史/收藏里移除）后就地更新，焦点留在附近。 */
    public void remove(String seriesId) {
        if (grid == null || !seen.remove(seriesId)) return;
        grid.remove(seriesId);
        count.setText(more ? "" : "共 " + seen.size() + " 部");
        if (seen.isEmpty()) { status.setText("这里还没有内容"); host.closeOverlay(); }
    }

    @Override public void focusFirst() { if (grid != null && grid.count() > 0) grid.focusFirst(); }
}
