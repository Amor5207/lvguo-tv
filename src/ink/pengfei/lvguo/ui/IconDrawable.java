package ink.pengfei.lvguo.ui;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/**
 * 统一的 24 网格图标（重绘版）。
 *
 * 规则：内容落在 3–21 的安全框内；描边 1.8、圆头圆角；
 * 实心图标（播放/暂停/上下集/星）用 CornerPathEffect 做圆角，避免尖角；
 * 每个图标是一条连续的轮廓或几条完整笔画，不再有断开的拼接段。
 */
public final class IconDrawable extends Drawable {
    private static final float STROKE = 1.8f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final CornerPathEffect soft = new CornerPathEffect(1.6f);
    private final CornerPathEffect softer = new CornerPathEffect(1.1f);
    private int type, color;
    private boolean filled;

    public IconDrawable(int type, int color) { this.type = type; this.color = color; }
    public void setColor(int value) { color = value; invalidateSelf(); }
    public void setType(int value) { type = value; invalidateSelf(); }
    public void setFilled(boolean value) { filled = value; invalidateSelf(); }

    private void stroke() { paint.setStyle(Paint.Style.STROKE); paint.setPathEffect(null); }
    private void fill(CornerPathEffect round) { paint.setStyle(Paint.Style.FILL); paint.setPathEffect(round); }
    private void flush(Canvas c) { c.drawPath(path, paint); path.reset(); }

    /** 快进快退专用：弧线 + 实心三角箭头（尖端沿转动方向，底边骑在弧线上），小尺寸下也清楚。 */
    private void arcArrowAt(Canvas c, float cx, float cy, float r, float start, float sweep, float size) {
        path.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), start, sweep);
        flush(c);
        double end = Math.toRadians(start + sweep);
        float ex = cx + r * (float) Math.cos(end), ey = cy + r * (float) Math.sin(end);
        double fwd = end + (sweep > 0 ? Math.PI / 2 : -Math.PI / 2);   // 转动方向
        float fx = (float) Math.cos(fwd), fy = (float) Math.sin(fwd), nx = -fy, ny = fx;
        path.moveTo(ex + fx * size, ey + fy * size);
        path.lineTo(ex - fx * size * .35f + nx * size, ey - fy * size * .35f + ny * size);
        path.lineTo(ex - fx * size * .35f - nx * size, ey - fy * size * .35f - ny * size);
        path.close();
        fill(new CornerPathEffect(.8f)); c.drawPath(path, paint); path.reset(); stroke();
    }

    /** 圆弧 + 顺着弧走向的箭头（刷新、快进快退共用）。 */
    private void arcArrow(Canvas c, float cx, float cy, float r, float start, float sweep) {
        path.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), start, sweep);
        double end = Math.toRadians(start + sweep);
        float ex = cx + r * (float) Math.cos(end), ey = cy + r * (float) Math.sin(end);
        double dir = end + (sweep > 0 ? Math.PI / 2 : -Math.PI / 2);   // 弧在终点处的切线方向
        float wing = 3.6f;
        for (double off : new double[]{Math.toRadians(150), Math.toRadians(-150)}) {
            path.moveTo(ex, ey);
            path.lineTo(ex + wing * (float) Math.cos(dir + off), ey + wing * (float) Math.sin(dir + off));
        }
        flush(c);
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        // 视觉居中校正：按实测的墨迹包围框/重心偏移逐个修正（单位：24 网格）
        float[] off = optical(type);
        canvas.translate(off[0], off[1]);
        paint.setColor(color);
        paint.setStrokeWidth(STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        stroke();
        path.reset();
        switch (type) {
            case IconView.HOME:   // 一笔闭合的房子：斜屋顶 + 圆角墙脚 + 门洞
                path.moveTo(3.5f, 10.4f); path.lineTo(12, 3.6f); path.lineTo(20.5f, 10.4f);
                path.lineTo(20.5f, 18.6f); path.quadTo(20.5f, 20.5f, 18.6f, 20.5f);
                path.lineTo(14.6f, 20.5f); path.lineTo(14.6f, 15f); path.quadTo(14.6f, 14.2f, 13.8f, 14.2f);
                path.lineTo(10.2f, 14.2f); path.quadTo(9.4f, 14.2f, 9.4f, 15f); path.lineTo(9.4f, 20.5f);
                path.lineTo(5.4f, 20.5f); path.quadTo(3.5f, 20.5f, 3.5f, 18.6f); path.close();
                if (filled) fill(null);
                break;
            case IconView.SEARCH:
                path.addCircle(10.8f, 10.8f, 6.8f, Path.Direction.CW);
                path.moveTo(15.9f, 15.9f); path.lineTo(20.5f, 20.5f); break;
            case IconView.FILTER:   // 分类：2×2 圆角方块
                for (int r = 0; r < 2; r++) for (int col = 0; col < 2; col++)
                    path.addRoundRect(new RectF(3.5f + col * 10, 3.5f + r * 10, 10.5f + col * 10, 10.5f + r * 10), 2.2f, 2.2f, Path.Direction.CW);
                break;
            case IconView.EPISODES:  // 选集：列表
                for (int r = 0; r < 3; r++) { float y = 6.5f + r * 5.5f; path.moveTo(9, y); path.lineTo(20.5f, y); }
                flush(canvas); fill(null);
                for (int r = 0; r < 3; r++) path.addCircle(4.8f, 6.5f + r * 5.5f, 1.3f, Path.Direction.CW);
                break;
            case IconView.HISTORY:   // 历史：表盘 + 指针
                path.addCircle(12, 12, 8.8f, Path.Direction.CW);
                path.moveTo(12, 7.4f); path.lineTo(12, 12); path.lineTo(15.2f, 14); break;
            case IconView.STAR:     // 追剧：圆角五角星
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float r = i % 2 == 0 ? 9.2f : 4.4f;
                    float x = 12 + r * (float) Math.cos(a), y = 12.6f + r * (float) Math.sin(a);
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                if (filled) fill(softer); else paint.setPathEffect(softer);
                break;
            case IconView.SETTING:  // 设置：8 齿圆角齿轮 + 轴孔
                for (int k = 0; k < 8; k++) {
                    double a = Math.toRadians(k * 45);
                    float[][] pts = {{-17, 7.1f}, {-10, 9.6f}, {10, 9.6f}, {17, 7.1f}};
                    for (int j = 0; j < 4; j++) {
                        double t = a + Math.toRadians(pts[j][0]);
                        float x = 12 + pts[j][1] * (float) Math.cos(t), y = 12 + pts[j][1] * (float) Math.sin(t);
                        if (k == 0 && j == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                }
                path.close(); paint.setPathEffect(softer); flush(canvas); stroke();
                path.addCircle(12, 12, 3, Path.Direction.CW); break;
            case IconView.USER:
                path.addCircle(12, 8.2f, 3.9f, Path.Direction.CW);
                path.moveTo(4.6f, 20.4f); path.cubicTo(4.6f, 16.4f, 7.9f, 13.8f, 12, 13.8f); path.cubicTo(16.1f, 13.8f, 19.4f, 16.4f, 19.4f, 20.4f); break;
            case IconView.PHONE:
                path.addRoundRect(new RectF(6.4f, 2.8f, 17.6f, 21.2f), 3, 3, Path.Direction.CW);
                path.moveTo(10.6f, 18.2f); path.lineTo(13.4f, 18.2f); break;
            case IconView.REFRESH:   // 换一批：顺时针箭头圆
                arcArrow(canvas, 12, 12, 8.3f, 18, 300); break;
            case IconView.COMMENT:   // 评论：一笔闭合的圆角气泡
                path.moveTo(7.2f, 4); path.lineTo(16.8f, 4); path.quadTo(20.5f, 4, 20.5f, 7.7f);
                path.lineTo(20.5f, 12.8f); path.quadTo(20.5f, 16.5f, 16.8f, 16.5f);
                path.lineTo(11.2f, 16.5f); path.lineTo(7.2f, 20); path.lineTo(7.2f, 16.5f);
                path.quadTo(3.5f, 16.5f, 3.5f, 12.8f); path.lineTo(3.5f, 7.7f); path.quadTo(3.5f, 4, 7.2f, 4); path.close(); break;
            case IconView.PLAY:
                path.moveTo(7.5f, 4.6f); path.lineTo(19.6f, 12); path.lineTo(7.5f, 19.4f); path.close();
                fill(soft); break;
            case IconView.PAUSE:
                fill(null);
                path.addRoundRect(new RectF(6.4f, 4.8f, 10.1f, 19.2f), 1.6f, 1.6f, Path.Direction.CW);
                path.addRoundRect(new RectF(13.9f, 4.8f, 17.6f, 19.2f), 1.6f, 1.6f, Path.Direction.CW); break;
            case IconView.NEXT:
                path.moveTo(5.2f, 5.6f); path.lineTo(15.2f, 12); path.lineTo(5.2f, 18.4f); path.close();
                fill(soft); flush(canvas); fill(null);
                path.addRoundRect(new RectF(16.4f, 5.4f, 18.8f, 18.6f), 1.2f, 1.2f, Path.Direction.CW); break;
            case IconView.PREVIOUS:
                path.moveTo(18.8f, 5.6f); path.lineTo(8.8f, 12); path.lineTo(18.8f, 18.4f); path.close();
                fill(soft); flush(canvas); fill(null);
                path.addRoundRect(new RectF(5.2f, 5.4f, 7.6f, 18.6f), 1.2f, 1.2f, Path.Direction.CW); break;
            case IconView.REWIND:
            case IconView.FAST_FORWARD: {  // 快退/快进 10 秒：开口圆弧箭头 + 居中数字
                boolean back = type == IconView.REWIND;
                // 圆弧正好停在正上方（-90°），箭头贴着圆走、尖端落在圆上，不再翘出圆外
                arcArrowAt(canvas, 12, 13.2f, 7.4f, back ? -150 : -30, back ? -296 : 296, 2.6f);
                fill(null);
                paint.setTextSize(7.4f);
                paint.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("10", 12, 15.85f, paint);
                break;
            }
            case IconView.SPEED:   // 倍速：仪表盘
                path.addArc(new RectF(3.6f, 4.6f, 20.4f, 21.4f), 150, 240);
                path.moveTo(12, 13); path.lineTo(15.6f, 9.4f); flush(canvas); fill(null);
                path.addCircle(12, 13, 1.6f, Path.Direction.CW); break;
            case IconView.QUALITY:  // 清晰度：HD 徽标
                path.addRoundRect(new RectF(2.8f, 5.2f, 21.2f, 18.8f), 3.4f, 3.4f, Path.Direction.CW);
                path.moveTo(7, 9.2f); path.lineTo(7, 14.8f); path.moveTo(10.6f, 9.2f); path.lineTo(10.6f, 14.8f);
                path.moveTo(7, 12); path.lineTo(10.6f, 12);
                path.moveTo(13.6f, 9.2f); path.lineTo(13.6f, 14.8f); path.lineTo(15.2f, 14.8f);
                path.quadTo(17.8f, 14.8f, 17.8f, 12); path.quadTo(17.8f, 9.2f, 15.2f, 9.2f); path.close(); break;
            case IconView.LOCATE:   // 定位：准星
                path.addCircle(12, 12, 6.4f, Path.Direction.CW);
                path.moveTo(12, 2.8f); path.lineTo(12, 5.6f); path.moveTo(12, 18.4f); path.lineTo(12, 21.2f);
                path.moveTo(2.8f, 12); path.lineTo(5.6f, 12); path.moveTo(18.4f, 12); path.lineTo(21.2f, 12);
                flush(canvas); fill(null); path.addCircle(12, 12, 1.9f, Path.Direction.CW); break;
            case IconView.WIFI_OFF: {  // 网络断开：以圆点为圆心的三道弧 + 斜线，斜线两侧挖出间隙（不与弧线糊在一起）
                int layer = canvas.saveLayer(0, 0, 24, 24, null);
                float cy = 18.2f;
                for (float r : new float[]{4.2f, 8.3f, 12.4f})
                    path.addArc(new RectF(12 - r, cy - r, 12 + r, cy + r), 226, 88);
                flush(canvas);
                fill(null); path.addCircle(12, cy, 1.35f, Path.Direction.CW); flush(canvas);
                stroke();
                paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR)); paint.setStrokeWidth(STROKE + 1.9f);
                path.moveTo(3.6f, 3.6f); path.lineTo(20.4f, 20.4f); flush(canvas);
                paint.setXfermode(null); paint.setStrokeWidth(STROKE);
                path.moveTo(3.6f, 3.6f); path.lineTo(20.4f, 20.4f); flush(canvas);
                canvas.restoreToCount(layer);
                break;
            }
            case IconView.MORE:   // 更多：圆内三个点
                path.addCircle(12, 12, 8.6f, Path.Direction.CW); flush(canvas);
                fill(null);
                for (int k = -1; k <= 1; k++) path.addCircle(12 + k * 3.6f, 12, 1.25f, Path.Direction.CW);
                flush(canvas); stroke(); break;
            case IconView.LOGOUT:   // 退出登录：半开的门框 + 向右出去的箭头
                path.moveTo(10.5f, 4f); path.lineTo(6.2f, 4f); path.quadTo(4f, 4f, 4f, 6.2f);
                path.lineTo(4f, 17.8f); path.quadTo(4f, 20f, 6.2f, 20f); path.lineTo(10.5f, 20f);
                path.moveTo(10f, 12f); path.lineTo(20f, 12f);
                path.moveTo(16.2f, 8.2f); path.lineTo(20f, 12f); path.lineTo(16.2f, 15.8f);
                break;
            case IconView.DISCOVER:   // 找剧：圆角竖屏 + 播放三角（刷剧）
                path.addRoundRect(new RectF(5.4f, 2.8f, 18.6f, 21.2f), 3.2f, 3.2f, Path.Direction.CW);
                flush(canvas); fill(soft);
                path.moveTo(10.2f, 8.6f); path.lineTo(15.4f, 12f); path.lineTo(10.2f, 15.4f); path.close(); break;
            case IconView.RANK:       // 榜单：三根高低不同的圆角柱
                path.addRoundRect(new RectF(3.6f, 11.6f, 8.4f, 20.2f), 1.6f, 1.6f, Path.Direction.CW);
                path.addRoundRect(new RectF(9.6f, 4.2f, 14.4f, 20.2f), 1.6f, 1.6f, Path.Direction.CW);
                path.addRoundRect(new RectF(15.6f, 8.2f, 20.4f, 20.2f), 1.6f, 1.6f, Path.Direction.CW); break;
            case IconView.DANMAKU:    // 弹幕：圆角屏幕 + 三行错落的字幕条
                path.addRoundRect(new RectF(2.8f, 4.6f, 21.2f, 19.4f), 3.4f, 3.4f, Path.Direction.CW);
                path.moveTo(6.6f, 9.2f); path.lineTo(13.4f, 9.2f);
                path.moveTo(10.6f, 12.4f); path.lineTo(17.4f, 12.4f);
                path.moveTo(6.6f, 15.6f); path.lineTo(11.4f, 15.6f); break;
            case IconView.SLIDERS:    // 通用：三条横向滑杆，各带一个圆形旋钮
                path.moveTo(4, 7); path.lineTo(20, 7); path.moveTo(4, 12); path.lineTo(20, 12); path.moveTo(4, 17); path.lineTo(20, 17);
                flush(canvas);
                fill(null); path.addCircle(9, 7, 2.3f, Path.Direction.CW); path.addCircle(15.5f, 12, 2.3f, Path.Direction.CW); path.addCircle(7.5f, 17, 2.3f, Path.Direction.CW); break;
            case IconView.APPEARANCE: // 外观：圆形，右半边实心（明暗/主题）
                path.addCircle(12, 12, 8.6f, Path.Direction.CW);
                flush(canvas);
                fill(null); path.moveTo(12, 3.4f); path.arcTo(new android.graphics.RectF(3.4f, 3.4f, 20.6f, 20.6f), -90, 180); path.close(); break;
            case IconView.STORAGE:    // 数据：叠起来的圆柱（存储）
                path.addOval(new android.graphics.RectF(5, 3.8f, 19, 8.2f), Path.Direction.CW);
                path.moveTo(5, 6); path.lineTo(5, 18); path.quadTo(5, 20.2f, 12, 20.2f); path.quadTo(19, 20.2f, 19, 18); path.lineTo(19, 6);
                path.moveTo(5, 12); path.quadTo(5, 14.2f, 12, 14.2f); path.quadTo(19, 14.2f, 19, 12); break;
            case IconView.PLAY_CIRCLE: // 播放设置：圆 + 小三角（和其它线性图标统一）
                path.addCircle(12, 12, 9, Path.Direction.CW);
                flush(canvas);
                fill(new CornerPathEffect(1.2f)); path.moveTo(10.2f, 8.4f); path.lineTo(15.8f, 12); path.lineTo(10.2f, 15.6f); path.close(); break;
            case IconView.INFO:       // 关于：圆 + 小写 i
                path.addCircle(12, 12, 9, Path.Direction.CW);
                path.moveTo(12, 11); path.lineTo(12, 16.8f);
                flush(canvas); fill(null); path.addCircle(12, 7.6f, 1.2f, Path.Direction.CW); break;
            case IconView.ALERT:      // 提示：圆 + 感叹号
                path.addCircle(12, 12, 9, Path.Direction.CW);
                path.moveTo(12, 7.2f); path.lineTo(12, 13.2f);
                flush(canvas); fill(null); path.addCircle(12, 16.6f, 1.2f, Path.Direction.CW); break;
            case IconView.CHEVRON_RIGHT:
                path.moveTo(9.4f, 5.6f); path.lineTo(15.8f, 12); path.lineTo(9.4f, 18.4f); break;
            case IconView.BACK:
                path.moveTo(14.6f, 5.6f); path.lineTo(8.2f, 12); path.lineTo(14.6f, 18.4f); break;
            case IconView.CLOSE:
                path.moveTo(6.2f, 6.2f); path.lineTo(17.8f, 17.8f); path.moveTo(17.8f, 6.2f); path.lineTo(6.2f, 17.8f); break;
            case IconView.CHECK:
                path.moveTo(5, 12.6f); path.lineTo(9.6f, 17.2f); path.lineTo(19, 7.4f); break;
            case IconView.PLUS:
                path.moveTo(12, 5); path.lineTo(12, 19); path.moveTo(5, 12); path.lineTo(19, 12); break;
            case IconView.HEART:
                path.moveTo(12, 20.2f);
                path.cubicTo(12, 20.2f, 3.6f, 15.3f, 3.6f, 9.3f); path.cubicTo(3.6f, 6.6f, 5.6f, 4.6f, 8.1f, 4.6f);
                path.cubicTo(9.8f, 4.6f, 11.2f, 5.5f, 12, 6.9f); path.cubicTo(12.8f, 5.5f, 14.2f, 4.6f, 15.9f, 4.6f);
                path.cubicTo(18.4f, 4.6f, 20.4f, 6.6f, 20.4f, 9.3f); path.cubicTo(20.4f, 15.3f, 12, 20.2f, 12, 20.2f); path.close();
                if (filled) fill(null);
                break;
            case IconView.TRASH:
                path.moveTo(4, 6.8f); path.lineTo(20, 6.8f);
                path.moveTo(9.4f, 6.8f); path.lineTo(9.4f, 4.9f); path.quadTo(9.4f, 4, 10.3f, 4); path.lineTo(13.7f, 4); path.quadTo(14.6f, 4, 14.6f, 4.9f); path.lineTo(14.6f, 6.8f);
                path.moveTo(6.1f, 6.8f); path.lineTo(7, 19); path.quadTo(7.1f, 20.4f, 8.5f, 20.4f); path.lineTo(15.5f, 20.4f); path.quadTo(16.9f, 20.4f, 17, 19); path.lineTo(17.9f, 6.8f);
                path.moveTo(10.2f, 10.6f); path.lineTo(10.2f, 16.4f); path.moveTo(13.8f, 10.6f); path.lineTo(13.8f, 16.4f); break;
            case IconView.DOWNLOAD:
                path.moveTo(12, 3.8f); path.lineTo(12, 14.6f); path.moveTo(7.6f, 10.4f); path.lineTo(12, 14.8f); path.lineTo(16.4f, 10.4f);
                path.moveTo(4.4f, 15.8f); path.lineTo(4.4f, 18.4f); path.quadTo(4.4f, 20.2f, 6.2f, 20.2f); path.lineTo(17.8f, 20.2f); path.quadTo(19.6f, 20.2f, 19.6f, 18.4f); path.lineTo(19.6f, 15.8f); break;
            case IconView.LOCK:       // 遥控安全：挂锁（锁梁 + 圆角锁体 + 锁孔）
                path.moveTo(8, 10.6f); path.lineTo(8, 7.8f); path.cubicTo(8, 5.4f, 9.8f, 3.8f, 12, 3.8f); path.cubicTo(14.2f, 3.8f, 16, 5.4f, 16, 7.8f); path.lineTo(16, 10.6f);
                path.addRoundRect(new RectF(5, 10.6f, 19, 20.4f), 2.4f, 2.4f, Path.Direction.CW);
                path.moveTo(12, 14.4f); path.lineTo(12, 16.6f); break;
            case IconView.MOON: {     // 强制休息：弯月（大圆减去右上偏移的圆）
                Path cut = new Path();
                cut.addCircle(16.2f, 7.6f, 7.4f, Path.Direction.CW);
                path.addCircle(11.6f, 12.4f, 8.6f, Path.Direction.CW);
                path.op(cut, Path.Op.DIFFERENCE);
                if (filled) fill(null);
                break;
            }
            case IconView.SUN: {      // 休息页（白天）：圆 + 八道光线
                path.addCircle(12, 12, 4.4f, Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    path.moveTo(12 + (float) Math.cos(a) * 7.6f, 12 + (float) Math.sin(a) * 7.6f);
                    path.lineTo(12 + (float) Math.cos(a) * 10f, 12 + (float) Math.sin(a) * 10f);
                }
                break;
            }
            case IconView.EXPAND:
                path.moveTo(4,9);path.lineTo(4,4);path.lineTo(9,4);
                path.moveTo(15,4);path.lineTo(20,4);path.lineTo(20,9);
                path.moveTo(20,15);path.lineTo(20,20);path.lineTo(15,20);
                path.moveTo(9,20);path.lineTo(4,20);path.lineTo(4,15);break;
            case IconView.HIDE_CONTROLS:
                path.addRoundRect(new RectF(3.5f, 3.5f, 20.5f, 20.5f), 3.5f, 3.5f, Path.Direction.CW);
                path.moveTo(8.4f, 10); path.lineTo(12, 13.6f); path.lineTo(15.6f, 10); break;
        }
        canvas.drawPath(path, paint);
        paint.setPathEffect(null);
        canvas.restoreToCount(save);
    }

    private static float[] optical(int type) {
        switch (type) {
            case IconView.PLAY: return new float[]{-0.5f, 0};          // 三角形保留少量右偏，视觉才居中
            case IconView.BACK: return new float[]{0.6f, 0};
            case IconView.CHEVRON_RIGHT: return new float[]{-0.6f, 0};
            case IconView.EPISODES: return new float[]{-0.6f, 0};
            case IconView.REFRESH: return new float[]{0.35f, 0.4f};
            case IconView.SPEED: return new float[]{0, 1.1f};
            case IconView.CHECK: return new float[]{0, -0.4f};
            case IconView.USER: return new float[]{0, -0.3f};
            default: return new float[]{0, 0};
        }
    }

    @Override public void setAlpha(int alpha) { color = (color & 0x00ffffff) | (alpha << 24); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
