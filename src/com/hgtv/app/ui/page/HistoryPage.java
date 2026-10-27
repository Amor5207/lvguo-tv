package com.hgtv.app.ui.page;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hgtv.app.model.Series;
import com.hgtv.app.App;
import com.hgtv.app.net.Repo;
import com.hgtv.app.model.Episode;
import com.hgtv.app.store.Store;
import com.hgtv.app.ui.Page;
import com.hgtv.app.ui.Ui;
import com.hgtv.app.ui.widget.CardGrid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 独立观看历史。只展示最近看过的剧，并清晰标注上次播放到哪一集。 */
public class HistoryPage extends Page {
    private CardGrid grid;
    private TextView countView, empty,clear;
    private final Map<String, Store.Item> records = new HashMap<>();
    private int navId;
    private final java.util.Set<String> repairStarted = new java.util.HashSet<>();

    @Override protected View build() {
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Ui.BG);
        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout col = Ui.column(ctx);
        col.setPadding(Ui.dp(ctx, 32), Ui.dp(ctx, 32), Ui.dp(ctx, 32), Ui.dp(ctx, 44));
        col.setClipChildren(false);

        LinearLayout heading=Ui.row(ctx);heading.addView(Ui.text(ctx,"观看历史",26,Ui.TEXT,true),Ui.lp(0,-2,1));
        clear=Ui.action(ctx,"清空",com.hgtv.app.ui.IconView.TRASH,false);clear.setOnClickListener(v->com.hgtv.app.ui.widget.TvDialog.confirm(host,"清空观看历史？","清空后无法恢复观看记录。","清空",()->{App.io().execute(()->{Store.clearHistory();host.runOnUiThread(()->{onShow();host.focusNav(3);});});}));heading.addView(clear,Ui.lp(-2,-2));col.addView(heading,Ui.lp(-1,-2));
        TextView hint = Ui.text(ctx, "", 14, Ui.TEXT_3, false);
        LinearLayout.LayoutParams hp = Ui.lp(-1, -2);
        hp.topMargin = Ui.dp(ctx, 9);
        hint.setVisibility(View.GONE);col.addView(hint, hp);
        countView = Ui.text(ctx, "", 13, Ui.PRIMARY, true);
        LinearLayout.LayoutParams cp = Ui.lp(-1, -2);
        cp.topMargin = Ui.dp(ctx, 24);
        col.addView(countView, cp);

        empty = Ui.text(ctx, "还没有观看记录。开始播放一部短剧后，就会出现在这里。", 16, Ui.TEXT_3, false);
        LinearLayout.LayoutParams ep = Ui.lp(-1, -2);
        ep.topMargin = Ui.dp(ctx, 42);
        col.addView(empty, ep);

        grid = new CardGrid(ctx, 7, 112, 160, 12);
        grid.setLeftAligned(true);
        grid.setOnPick(s -> host.openDetail(s));
        grid.setOnBindCard((card, s) -> {
            card.setOnLongClickListener(v->{com.hgtv.app.ui.widget.TvDialog.confirm(host,s.title,"从观看历史中删除这部剧？","删除历史",()->{Store.remove("history",s.seriesId);onShow();focusFirst();});return true;});
            Store.Item item = records.get(s.seriesId);
            if (item != null && item.lastEpisode > 0) {
                card.setBadge("第 " + item.lastEpisode + " 集");
            }
        });
        LinearLayout.LayoutParams gp = Ui.lp(-1, -2);
        gp.topMargin = Ui.dp(ctx, 15);
        col.addView(grid, gp);
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    @Override public void onShow() {
        List<Store.Item> items = Store.history();
        clear.setEnabled(!items.isEmpty());
        records.clear();
        List<Series> series = new ArrayList<>();
        for (Store.Item item : items) {
            if (item.seriesId == null || item.seriesId.isEmpty()) continue;
            records.put(item.seriesId, item);
            Series s = new Series();
            s.seriesId = item.seriesId;
            s.title = item.title;
            s.cover = Store.bestCover(item.seriesId, item.cover);
            s.firstVid = item.vid;
            series.add(s);
        }
        countView.setText("最近观看  ·  " + series.size() + " 部短剧");
        empty.setVisibility(series.isEmpty() ? View.VISIBLE : View.GONE);
        grid.setData(series);
        // 兼容旧版本历史数据没有 cover 字段的情况。
        int queued = 0;
        for (Store.Item item : items) {
            if (item.seriesId == null || Store.usableCover(Store.bestCover(item.seriesId, item.cover))
                    || repairStarted.contains(item.seriesId)) continue;
            if (++queued > 6) break;
            final String sid = item.seriesId;
            repairStarted.add(sid);
            App.io().execute(() -> {
                try {
                    Repo.Detail detail = Repo.detail(sid);
                    for (Episode ep : detail.episodes) {
                        if (Store.usableCover(ep.cover)) {
                            Store.updateCover(sid, ep.cover);
                            host.runOnUiThread(() -> { if (root != null && root.isShown()) onShow(); });
                            break;
                        }
                    }
                } catch (Throwable ignored) { }
            });
        }
    }

    @Override public void focusFirst() { if(grid!=null&&grid.count()>0)grid.focusFirst();else if(clear!=null)clear.requestFocus(); }
    @Override public void bindNav(int id) { navId = id; if (grid != null) grid.setLeftNavId(id); }
}

