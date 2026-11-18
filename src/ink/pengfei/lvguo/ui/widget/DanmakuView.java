package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.ui.Ui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 弹幕层：多轨道从右向左滚动，只占画面上半部分，不挡字幕和控制条。
 * 文字带深色描边，亮画面上也清晰。暂停时停住，继续播放后接着走。
 */
public class DanmakuView extends View {
    public static final float[] SPEEDS = {0.7f, 1f, 1.4f};
    public static final String[] SPEED_LABELS = {"慢", "标准", "快"};
    public static final int[] SIZES = {18, 22, 28};
    public static final String[] SIZE_LABELS = {"小", "中", "大"};
    public static final int[] ALPHAS = {25, 50, 75, 100};

    private static final class Item { String text; float x, width; int lane; boolean mine; }
    private final List<Item> items = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastFrame;
    private boolean paused;

    public DanmakuView(Context c) {
        super(c);
        setWillNotDraw(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xCC000000);
        fill.setColor(0xFFFFFFFF);
        applySettings();
    }

    // ------------------------------------------------------------ 设置（持久化）

    public static boolean enabled() { return App.prefs().getBoolean("dm_on", true); }
    public static int speedIndex() { return clamp(App.prefs().getInt("dm_speed", 1), SPEEDS.length); }
    public static int sizeIndex() { return clamp(App.prefs().getInt("dm_size", 1), SIZES.length); }
    public static int alphaIndex() { return clamp(App.prefs().getInt("dm_alpha", 2), ALPHAS.length); }
    private static int clamp(int v, int n) { return Math.max(0, Math.min(n - 1, v)); }

    public void applySettings() {
        float px = Ui.dp(getContext(), SIZES[sizeIndex()]);
        fill.setTextSize(px); stroke.setTextSize(px);
        stroke.setStrokeWidth(Math.max(2f, px / 9f));
        fill.setFakeBoldText(true);
        setAlpha(ALPHAS[alphaIndex()] / 100f);
        setVisibility(enabled() ? VISIBLE : GONE);
        if (!enabled()) clear();
        invalidate();
    }

    // ------------------------------------------------------------ 控制

    public void clear() { items.clear(); invalidate(); }
    public void setPaused(boolean value) { paused = value; lastFrame = 0; if (!paused) postInvalidateOnAnimation(); }
    public int active() { return items.size(); }

    private float lineHeight() { return fill.getTextSize() * 1.55f; }
    private int lanes() { return Math.max(1, (int) (getHeight() * 0.42f / lineHeight())); }

    /** 发一条弹幕。轨道都被占满时丢弃（宁少勿挤）。 */
    public boolean add(String text, boolean mine) {
        if (!enabled() || getWidth() <= 0 || text == null) return false;
        String t = text.replaceAll("\\[[^\\]]{1,6}\\]", "").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return false;
        if (t.length() > 36) t = t.substring(0, 35) + "…";
        float w = fill.measureText(t);
        int lanes = lanes(), w0 = getWidth();
        float[] tail = new float[lanes];
        // 调整字号/高度后轨道数会变少，旧弹幕可能还在更高的轨道上
        for (Item it : items) if (it.lane < lanes) tail[it.lane] = Math.max(tail[it.lane], it.x + it.width);
        float gap = Ui.dp(getContext(), 48);
        int chosen = -1;
        for (int lane = 0; lane < lanes; lane++) if (tail[lane] + gap < w0) { chosen = lane; break; }
        if (chosen < 0) return false;
        Item it = new Item();
        it.text = t; it.width = w; it.x = w0; it.lane = chosen; it.mine = mine;
        items.add(it);
        lastFrame = 0;
        postInvalidateOnAnimation();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (items.isEmpty()) { lastFrame = 0; return; }
        long now = android.os.SystemClock.uptimeMillis();
        float dt = lastFrame == 0 || paused ? 0 : Math.min(64, now - lastFrame);
        lastFrame = now;
        // 一条弹幕约 9 秒横穿屏幕（标准速度）
        float pxPerMs = (getWidth() + Ui.dp(getContext(), 200)) / 9000f * SPEEDS[speedIndex()];
        float line = lineHeight(), top = Ui.dp(getContext(), 28);
        Paint.FontMetrics fm = fill.getFontMetrics();
        for (Iterator<Item> i = items.iterator(); i.hasNext(); ) {
            Item it = i.next();
            it.x -= dt * pxPerMs;
            if (it.x + it.width < 0) { i.remove(); continue; }
            float y = top + it.lane * line + (line - (fm.descent - fm.ascent)) / 2 - fm.ascent;
            canvas.drawText(it.text, it.x, y, stroke);
            fill.setColor(it.mine ? 0xFF7CF29C : 0xFFFFFFFF);
            canvas.drawText(it.text, it.x, y, fill);
        }
        if (!items.isEmpty() && !paused) postInvalidateOnAnimation();
    }
}
