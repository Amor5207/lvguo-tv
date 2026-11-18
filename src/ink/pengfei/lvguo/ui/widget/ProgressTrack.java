package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import ink.pengfei.lvguo.ui.Ui;

/**
 * 播放进度条：两端圆角的轨道 / 缓冲 / 已播放，外加一个小圆点。
 * 系统 SeekBar 的 ClipDrawable 会把已播放段的右端裁成直角，这里全部自己画。
 * 拖动（快进快退预览）时进度条加粗、圆点放大，平时保持纤细。
 */
public class ProgressTrack extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), buffer = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int max = 1000, progress, secondary;
    private boolean active,edgeToEdge;

    public ProgressTrack(Context c) {
        super(c);
        track.setColor(0x40FFFFFF);
        buffer.setColor(0x4DFFFFFF);
        fill.setColor(0xFFFFFFFF);
        knob.setColor(0xFFFFFFFF);
        knob.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1), 0x66000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // 阴影需要软件层
    }

    public void setMax(int m) { max = Math.max(1, m); invalidate(); }
    public void setProgress(int p) { progress = Math.max(0, Math.min(max, p)); invalidate(); }
    public void setSecondaryProgress(int p) { secondary = Math.max(0, Math.min(max, p)); invalidate(); }
    public int getProgress() { return progress; }
    /** 正在拖动/预览：加粗并放大圆点。 */
    public void setActive(boolean a) { if (active != a) { active = a; invalidate(); } }

    /** A persistent thin line at the video edge, without inset or a thumb. */
    public void setEdgeToEdge(boolean value){edgeToEdge=value;fill.setColor(value?Ui.PRIMARY:0xFFFFFFFF);invalidate();}

    @Override protected void onDraw(Canvas c) {
        if(edgeToEdge){
            c.drawRect(0,0,getWidth(),getHeight(),track);
            c.drawRect(0,0,getWidth()*secondary/(float)max,getHeight(),buffer);
            c.drawRect(0,0,getWidth()*progress/(float)max,getHeight(),fill);
            return;
        }
        float knobR = Ui.dp(getContext(), active ? 8 : 6);
        float h = Ui.dp(getContext(), active ? 6 : 4);
        float left = knobR, right = getWidth() - knobR, cy = getHeight() / 2f;
        float w = Math.max(0, right - left), rad = h / 2f;
        r.set(left, cy - rad, right, cy + rad);
        c.drawRoundRect(r, rad, rad, track);
        float sx = left + w * secondary / max;
        if (sx > left) { r.set(left, cy - rad, Math.max(sx, left + h), cy + rad); c.drawRoundRect(r, rad, rad, buffer); }
        float px = left + w * progress / max;
        if (px > left) { r.set(left, cy - rad, Math.max(px, left + h), cy + rad); c.drawRoundRect(r, rad, rad, fill); }
        c.drawCircle(px, cy, knobR, knob);
    }
}
