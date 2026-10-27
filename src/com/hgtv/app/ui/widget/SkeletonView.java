package com.hgtv.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import com.hgtv.app.ui.Ui;

/** Static placeholders: one canvas, no timers, layers or per-card view trees. */
public final class SkeletonView extends View {
    public static final int GRID=0, INFO=1, EPISODES=2, FILTERS=3, SEASONS=4;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mode,columns,posterHeight,rows;
    private int cardWidth,cardGap;
    private boolean leftAligned;
    public SkeletonView(Context c,int mode,int columns,int posterHeight,int rows){
        super(c);this.mode=mode;this.columns=Math.max(1,columns);
        this.posterHeight=posterHeight;this.rows=rows;
        setFocusable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    /** Share the real grid geometry so loading never changes the column slots. */
    public SkeletonView gridGeometry(int widthDp,int gapPx,boolean left){
        leftAligned=left;
        cardWidth=Ui.dp(getContext(),widthDp);cardGap=gapPx;return this;
    }
    @Override protected void onMeasure(int w,int h){
        int height=mode==FILTERS?rows*36:mode==INFO?100:mode==EPISODES?rows*64+2:mode==SEASONS?46:rows*(posterHeight+48);
        setMeasuredDimension(MeasureSpec.getSize(w),mode==GRID && cardWidth>0?
                rows*(Ui.dp(getContext(),posterHeight+35)+cardGap):resolveSize(Ui.dp(getContext(),height),h));
    }
    private void block(Canvas c,float x,float y,float w,float h,int color){
        paint.setColor(color);float r=Ui.dp(getContext(),6);
        c.drawRoundRect(x,y,x+w,y+h,r,r,paint);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float d=getResources().getDisplayMetrics().density;
        float width=getWidth(),gap=16*d;
        if(mode==FILTERS){for(int row=0;row<rows;row++){float y=(row*36+13)*d;block(c,0,y,54*d,8*d,Ui.SURFACE_2);for(int col=0;col<4;col++)block(c,(80+col*95)*d,y,62*d,9*d,Ui.SURFACE_2);}return;}
        if(mode==SEASONS){float seasonGap=10*d;int count=Math.max(1,(int)Math.ceil(width/(100*d)));float cell=(width-seasonGap*(count-1))/count;for(int col=0;col<count;col++)block(c,col*(cell+seasonGap),6*d,cell,34*d,0xff252929);return;}
        if(mode==INFO){
            block(c,0,12*d,width*.55f,12*d,0xff252929);
            block(c,0,44*d,width*.94f,10*d,0xff252929);
            block(c,0,66*d,width*.78f,10*d,0xff252929);return;
        }
        if(cardWidth>0)gap=cardGap;
        float cell=cardWidth>0?cardWidth:(width-gap*(columns-1))/columns;
        float inset=cardWidth>0?(leftAligned?0:Math.max(0,(width-columns*(cell+4*d)-(columns-1)*gap)/2))+2*d:0;
        for(int row=0;row<rows;row++)for(int col=0;col<columns;col++){
            float x=inset+col*(cell+gap+(cardWidth>0?4*d:0));
            float y=cardWidth>0?row*((posterHeight+35)*d+gap)+2*d:row*(posterHeight+48)*d;
            if(mode==EPISODES){block(c,x,row*64*d,cell,52*d,0xff252929);}
            else{
                block(c,x,y,cell,posterHeight*d,0xff21292f);
                block(c,x,y+(posterHeight+10)*d,cell*.82f,11*d,0xff293137);
                block(c,x,y+(posterHeight+28)*d,cell*.48f,7*d,0xff21292f);
            }
        }
    }
}
