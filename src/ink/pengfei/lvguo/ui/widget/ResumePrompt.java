package ink.pengfei.lvguo.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.store.Store;
import ink.pengfei.lvguo.ui.IconView;
import ink.pengfei.lvguo.ui.Ui;

import java.util.List;
import java.util.Locale;

/**
 * 启动时「继续观看」：上次没看完的剧弹窗询问，按确定续播，返回或「关闭」不播。
 * 设置 → 播放 →「启动续播」可改成「直接继续播放」或「不提示」。
 */
public final class ResumePrompt {
    private ResumePrompt() {}
    private static boolean shownThisLaunch;

    public interface CanShow { boolean allowed(); }
    /** Wait for cloud history to be merged; never automatically resume an unverified old row. */
    public static void afterHistoryReady(Activity host,CanShow canShow){
        if(shownThisLaunch || App.prefs().getInt("resume_on_launch",0)==2)return;
        final long generation=ink.pengfei.lvguo.net.Session.generation();
        ink.pengfei.lvguo.net.LibrarySync.whenHistoryReady(success -> {
            if(!success)return;
            host.getWindow().getDecorView().post(()->{
                if(host.isFinishing() || host.isDestroyed() || generation!=ink.pengfei.lvguo.net.Session.generation() || !canShow.allowed())return;
                maybeShow(host);
            });
        });
    }

    /** 只在每次启动时调用一次。 */
    /** @return 是否弹了窗或直接去续播了（这时不再叠加别的启动提示） */
    public static boolean maybeShow(Activity host) {
        if (shownThisLaunch || !ink.pengfei.lvguo.net.LibrarySync.historyReady()) return false;
        shownThisLaunch = true;
        int mode = App.prefs().getInt("resume_on_launch", 0);
        if (mode == 2) return false;
        List<Store.Item> history = Store.history();
        if (history.isEmpty()) return false;
        Store.Item last = history.get(0);
        if (last.seriesId == null || Store.completed(last.seriesId)) return false;
        if (mode == 1) { play(host, last); return true; }
        show(host, last);
        return true;
    }

    static void play(Activity host, Store.Item it) {
        Intent intent = new Intent(host, ink.pengfei.lvguo.PlayerActivity.class);
        intent.putExtra("series_id", it.seriesId);
        intent.putExtra("title", it.title);
        intent.putExtra("cover", Store.bestCover(it.seriesId, it.cover));
        intent.putExtra("index", it.episodeIndex);
        if (it.vid != null) intent.putExtra(ink.pengfei.lvguo.PlayerActivity.EXTRA_VID, it.vid);
        intent.putExtra(ink.pengfei.lvguo.PlayerActivity.EXTRA_EPISODE_NO, it.lastEpisode);
        intent.putExtra("position_ms", it.positionMs);
        host.startActivity(intent);
    }

    private static void show(Activity host, Store.Item it) {
        Dialog d = new Dialog(host);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = Ui.row(host);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        Ui.padding(panel, 24, 24, 28, 24);
        panel.setBackground(Ui.roundStroke(host, Ui.SURFACE, 20, Ui.SEPARATOR, 1));

        RoundFrame coverFrame = new RoundFrame(host, 12);
        coverFrame.setBackground(Ui.round(host, Ui.SKELETON, 12));   // 占位底色也要圆角，否则四角露出灰色直角
        ImageView cover = new ImageView(host);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverFrame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        panel.addView(coverFrame, Ui.lp(Ui.dp(host, 112), Ui.dp(host, 158)));

        LinearLayout info = Ui.column(host);
        // 顶部一行只有右上角「× 关闭」；它和底部按钮行一样高（46dp），中间的剧集信息就正好和封面垂直居中
        LinearLayout top = Ui.row(host);
        top.setGravity(Gravity.END | Gravity.TOP);
        TextView close = Ui.action(host, "关闭", IconView.CLOSE, false);
        Ui.padding(close, 14, 0, 16, 0);
        close.setMinHeight(Ui.dp(host, 34));
        top.addView(close, Ui.lp(-2, Ui.dp(host, 34)));
        info.addView(top, Ui.lp(-1, Ui.dp(host, 46)));
        LinearLayout details = Ui.column(host);
        TextView title = Ui.text(host, it.title == null ? "" : it.title, 22, Ui.TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(title, Ui.lp(-1, -2));
        String where = "第 " + Math.max(1, it.lastEpisode) + " 集";
        if (it.positionMs > 0) where += " · 看到 " + fmt(it.positionMs);
        TextView meta = Ui.text(host, where, 15, Ui.TEXT_2, false);
        LinearLayout.LayoutParams ml = Ui.lp(-1, -2); ml.topMargin = Ui.dp(host, 8);
        details.addView(meta, ml);
        // 补充信息（专辑旁不留大块空白）：标签一行、主演一行；单行、最大宽度 320dp，放不下用省略号，没有数据就不显示
        TextView tags = Ui.text(host, "", 13, Ui.TEXT_3, false);
        TextView cast = Ui.text(host, "", 13, Ui.TEXT_3, false);
        for (TextView t : new TextView[]{tags, cast}) {
            t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setMaxWidth(Ui.dp(host, 320)); t.setText(" "); t.setVisibility(android.view.View.INVISIBLE);   // 先占位，数据回来时弹窗高度不跳
            LinearLayout.LayoutParams xl = Ui.lp(-2, -2); xl.topMargin = Ui.dp(host, 6);
            details.addView(t, xl);
        }
        final String sid = it.seriesId;
        App.io().execute(() -> {
            try {
                ink.pengfei.lvguo.net.Repo.Detail det = ink.pengfei.lvguo.net.Repo.detail(sid);
                java.util.List<String> parts = new java.util.ArrayList<>();
                if (det.episodes.size() > 0) parts.add("全 " + det.episodes.size() + " 集");
                if (det.hotTag != null && !det.hotTag.isEmpty()) parts.add(det.hotTag);
                String category = "";
                java.util.List<ink.pengfei.lvguo.model.Series> all = ink.pengfei.lvguo.net.Catalog.cached();
                if (all != null) for (ink.pengfei.lvguo.model.Series x : all) if (sid.equals(x.seriesId) && x.category != null) { category = x.category; break; }
                for (String c : ink.pengfei.lvguo.net.Browse.categoryNames(category).split(",")) if (!c.trim().isEmpty() && parts.size() < 4) parts.add(c.trim());
                String tagText = android.text.TextUtils.join("  ·  ", parts);
                java.util.List<String> actors = det.actors.subList(0, Math.min(3, det.actors.size()));
                String castText = actors.isEmpty() ? "" : "主演  " + android.text.TextUtils.join(" / ", actors);
                host.runOnUiThread(() -> {
                    if (!d.isShowing()) return;
                    if (!tagText.isEmpty()) { tags.setText(tagText); tags.setVisibility(android.view.View.VISIBLE); }
                    if (!castText.isEmpty()) { cast.setText(castText); cast.setVisibility(android.view.View.VISIBLE); }
                });
            } catch (Exception ignored) { }   // 拿不到就不显示，不影响弹窗
        });
        info.addView(details, Ui.lp(-1, -2));
        // 底部只有「继续观看」一个按钮，右对齐，默认选中；不倒计时、不自动开播
        LinearLayout actions = Ui.row(host);
        actions.setGravity(Gravity.END | Gravity.BOTTOM);
        TextView go = Ui.action(host, "继续观看", IconView.PLAY, true);
        actions.addView(go, Ui.lp(-2, Ui.dp(host, 46)));
        info.addView(actions, Ui.lp(-1, Ui.dp(host, 46)));
        LinearLayout.LayoutParams il = Ui.lp(0, -2, 1); il.leftMargin = Ui.dp(host, 22);
        panel.addView(info, il);

        go.setOnClickListener(v -> { d.dismiss(); play(host, it); });
        close.setOnClickListener(v -> d.dismiss());
        d.setContentView(panel);ink.pengfei.lvguo.ui.Ui.popIn(panel);
        Ui.closeOnBack(d);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(Ui.dp(host, 600), host.getResources().getDisplayMetrics().widthPixels - Ui.dp(host, 40)), -2);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.6f);
        }
        go.requestFocus();
        ImageLoader.load(Store.bestCover(it.seriesId, it.cover), cover, Ui.dp(host, 224), Ui.dp(host, 316));
    }

    private static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }
}
