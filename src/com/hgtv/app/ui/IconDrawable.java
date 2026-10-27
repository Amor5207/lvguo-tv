package com.hgtv.app.ui;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** One consistent 24-unit icon set, rendered natively at any TV density. */
public final class IconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int type, color;
    private boolean filled;
    public IconDrawable(int type, int color) { this.type = type; this.color = color; }
    public void setColor(int value) { color = value; invalidateSelf(); }
    public void setType(int value) { type = value; invalidateSelf(); }
    public void setFilled(boolean value) { filled = value; invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        Rect bounds = getBounds();
        float size = Math.min(bounds.width(), bounds.height());
        canvas.translate(bounds.left + (bounds.width() - size) / 2f,
                bounds.top + (bounds.height() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        paint.setColor(color); paint.setStrokeWidth(1.8f);
        paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        switch (type) {
            case IconView.HIDE_CONTROLS:
                path.moveTo(4,4);path.lineTo(4,20);path.lineTo(20,20);path.lineTo(20,4);
                path.moveTo(8,9);path.lineTo(12,13);path.lineTo(16,9);
                path.moveTo(8,16);path.lineTo(16,16);break;
            case IconView.LOCATE:
                path.addCircle(12,12,6,Path.Direction.CW);path.addCircle(12,12,2,Path.Direction.CW);
                path.moveTo(12,2);path.lineTo(12,6);path.moveTo(12,18);path.lineTo(12,22);
                path.moveTo(2,12);path.lineTo(6,12);path.moveTo(18,12);path.lineTo(22,12);break;
            case IconView.CHEVRON_RIGHT:
                path.moveTo(9,5);path.lineTo(16,12);path.lineTo(9,19);break;
            case IconView.SPEED:
                path.addArc(new RectF(3,4,21,22),180,180);
                path.moveTo(12,13);path.lineTo(17,7);
                path.moveTo(5,19);path.lineTo(19,19);break;
            case IconView.QUALITY:
                path.addRoundRect(new RectF(2,4,22,20),2,2,Path.Direction.CW);
                path.moveTo(7,9);path.lineTo(7,15);path.moveTo(11,9);path.lineTo(11,15);
                path.moveTo(7,12);path.lineTo(11,12);path.moveTo(15,9);path.lineTo(15,15);
                path.cubicTo(22,15,22,9,15,9);break;
            case IconView.HOME:
                path.moveTo(3,10); path.lineTo(12,3); path.lineTo(21,10);
                path.moveTo(5,9); path.lineTo(5,21); path.lineTo(10,21);
                path.lineTo(10,15); path.lineTo(14,15); path.lineTo(14,21);
                path.lineTo(19,21); path.lineTo(19,9); break;
            case IconView.SEARCH:
                path.addCircle(10.5f,10.5f,6.5f,Path.Direction.CW);
                path.moveTo(16,16); path.lineTo(21,21); break;
            case IconView.FILTER:
                path.moveTo(3,6); path.lineTo(21,6);
                path.moveTo(3,12); path.lineTo(21,12);
                path.moveTo(3,18); path.lineTo(21,18);
                canvas.drawPath(path,paint); path.reset();
                paint.setStyle(Paint.Style.FILL);
                path.addRoundRect(new RectF(7,3.5f,10,8.5f),1,1,Path.Direction.CW);
                path.addRoundRect(new RectF(14,9.5f,17,14.5f),1,1,Path.Direction.CW);
                path.addRoundRect(new RectF(7,15.5f,10,20.5f),1,1,Path.Direction.CW); break;
            case IconView.USER:
                path.addCircle(12,7,4,Path.Direction.CW);
                path.moveTo(4,21); path.cubicTo(4,11,20,11,20,21); break;
            case IconView.PHONE:
                path.addRoundRect(new RectF(6,2,18,22),2.3f,2.3f,Path.Direction.CW);
                path.moveTo(10,5);path.lineTo(14,5);
                path.moveTo(11,19);path.lineTo(13,19);break;
            case IconView.REFRESH:
                path.moveTo(3,10);path.cubicTo(4,3,14,1,19,7);path.lineTo(21,9);
                path.moveTo(21,3);path.lineTo(21,9);path.lineTo(15,9);
                path.moveTo(21,14);path.cubicTo(20,21,10,23,5,17);path.lineTo(3,15);
                path.moveTo(3,21);path.lineTo(3,15);path.lineTo(9,15);break;
            case IconView.COMMENT:
                path.moveTo(5,3);path.lineTo(19,3);path.quadTo(21,3,21,5);
                path.lineTo(21,15);path.quadTo(21,17,19,17);path.lineTo(10,17);
                path.lineTo(4,21);path.lineTo(4,17);path.quadTo(3,17,3,15);
                path.lineTo(3,5);path.quadTo(3,3,5,3);
                path.moveTo(7,8);path.lineTo(17,8);path.moveTo(7,12);path.lineTo(14,12);break;
            case IconView.PLAY:
                path.moveTo(8,4); path.lineTo(21,12); path.lineTo(8,20); path.close();
                paint.setStyle(Paint.Style.FILL); break;
            case IconView.PAUSE:
                paint.setStyle(Paint.Style.FILL);
                path.addRoundRect(new RectF(6,4,10,20),1,1,Path.Direction.CW);
                path.addRoundRect(new RectF(14,4,18,20),1,1,Path.Direction.CW); break;
            case IconView.PLUS:
                path.moveTo(12,4); path.lineTo(12,20);
                path.moveTo(4,12); path.lineTo(20,12); break;
            case IconView.HEART:
                path.moveTo(12,21); path.cubicTo(8,17,2,13,3,7);
                path.cubicTo(4,2,10,2,12,7); path.cubicTo(14,2,20,2,21,7);
                path.cubicTo(22,13,16,17,12,21); break;
            case IconView.STAR:
                for(int i=0;i<10;i++) {
                    double a=-Math.PI/2+i*Math.PI/5;
                    float r=i%2==0?9:4.2f, x=12+r*(float)Math.cos(a), y=12+r*(float)Math.sin(a);
                    if(i==0) path.moveTo(x,y); else path.lineTo(x,y);
                } path.close(); break;
            case IconView.BACK:
                path.moveTo(12,5);path.lineTo(5,12);path.lineTo(12,19);
                path.moveTo(5,12);path.lineTo(20,12);break;
            case IconView.REWIND:
            case IconView.FAST_FORWARD:
                boolean rewind=type==IconView.REWIND;
                int arrowSave=canvas.save();
                if(!rewind)canvas.scale(-1,1,12,12);
                path.addArc(new RectF(3,3,21,21),-145,-300);
                path.moveTo(3.5f,3);path.lineTo(3.5f,8);path.lineTo(8.5f,8);
                canvas.drawPath(path,paint);path.reset();canvas.restoreToCount(arrowSave);
                paint.setStyle(Paint.Style.FILL);paint.setTextSize(8.5f);paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("10",12,15,paint);break;
            case IconView.NEXT:
                path.moveTo(6,5); path.lineTo(16,12); path.lineTo(6,19); path.close();
                path.moveTo(19,5); path.lineTo(19,19); break;
            case IconView.PREVIOUS:
                path.moveTo(18,5); path.lineTo(8,12); path.lineTo(18,19); path.close();
                path.moveTo(5,5); path.lineTo(5,19); break;
            case IconView.HISTORY:
                path.addArc(new RectF(4,4,21,21),-90,300);
                path.moveTo(3,4); path.lineTo(3,9); path.lineTo(8,9);
                path.moveTo(12,7); path.lineTo(12,13); path.lineTo(16,15); break;
            case IconView.SETTING:
                for(int i=0;i<24;i++) {
                    double a=i*Math.PI/12;
                    float r=(i%4==0||i%4==3)?10:8;
                    float x=12+r*(float)Math.cos(a),y=12+r*(float)Math.sin(a);
                    if(i==0)path.moveTo(x,y); else path.lineTo(x,y);
                } path.close(); path.addCircle(12,12,3.3f,Path.Direction.CCW); break;
            case IconView.EPISODES:
                for(int row=0;row<2;row++) for(int col=0;col<2;col++)
                    path.addRoundRect(new RectF(4+col*10,4+row*10,10+col*10,10+row*10),
                            1,1,Path.Direction.CW); break;
            case IconView.CLOSE:
                path.moveTo(5,5); path.lineTo(19,19);
                path.moveTo(19,5); path.lineTo(5,19); break;
            case IconView.TRASH:
                path.moveTo(3,6);path.lineTo(21,6);
                path.moveTo(9,6);path.lineTo(9,3);path.lineTo(15,3);path.lineTo(15,6);
                path.moveTo(5,6);path.lineTo(6,21);path.lineTo(18,21);path.lineTo(19,6);
                path.moveTo(10,10);path.lineTo(10,17);path.moveTo(14,10);path.lineTo(14,17);break;
            case IconView.CHECK:
                path.moveTo(4,12); path.lineTo(9,17); path.lineTo(20,6); break;
            case IconView.DOWNLOAD:
                path.moveTo(12,3); path.lineTo(12,15);
                path.moveTo(7,10); path.lineTo(12,15); path.lineTo(17,10);
                path.moveTo(4,16); path.lineTo(4,21); path.lineTo(20,21); path.lineTo(20,16); break;
        }
        canvas.drawPath(path,paint); canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha) { color=(color&0x00ffffff)|(alpha<<24); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

