package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import ink.pengfei.lvguo.ui.Ui;

/**
 * 圆角裁切容器：子视图（海报图、渐变、角标）全部裁成圆角。
 *
 * 硬件加速时用 Outline 裁切（抗锯齿、开销低）；部分电视盒子会走软件渲染，
 * Outline 在那种情况下不生效，于是再用 clipPath 兜底，保证任何设备上海报都是圆角。
 */
public class RoundFrame extends FrameLayout {
    private final float radius;
    private final Path clip = new Path(), ring = new Path();
    private final android.graphics.Paint ringPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private boolean ringOn;
    private float ringWidth;

    /** 聚焦环：与圆角裁切严格同心（外轮廓 − 内轮廓），不会出现四角对不齐。 */
    public void setRing(boolean on, int color, float widthDp) {
        ringOn = on; ringPaint.setColor(color); ringWidth = Ui.dp(getContext(), widthDp); invalidate();
    }

    public RoundFrame(Context c, float radiusDp) {
        super(c);
        radius = Ui.dp(c, radiusDp);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (canvas.isHardwareAccelerated()) { super.dispatchDraw(canvas); drawRing(canvas); return; }
        int save = canvas.save();
        clip.reset();
        clip.addRoundRect(0, 0, getWidth(), getHeight(), radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        drawRing(canvas);
        canvas.restoreToCount(save);
    }

    private void drawRing(Canvas canvas) {
        if (!ringOn || ringWidth <= 0) return;
        float w = getWidth(), h = getHeight(), s = ringWidth;
        ring.reset();
        ring.setFillType(Path.FillType.EVEN_ODD);
        ring.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
        ring.addRoundRect(s, s, w - s, h - s, Math.max(0, radius - s), Math.max(0, radius - s), Path.Direction.CW);
        canvas.drawPath(ring, ringPaint);
    }
}
