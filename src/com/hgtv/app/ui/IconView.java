package com.hgtv.app.ui;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;

public class IconView extends View {
    public static final int HOME=0, SEARCH=1, FILTER=2, USER=3, PLAY=4, PLUS=5,
            HEART=6, STAR=7, BACK=8, NEXT=9, HISTORY=10, SETTING=11, PAUSE=12,
            EPISODES=13, PREVIOUS=14, CLOSE=15, CHECK=16, DOWNLOAD=17, PHONE=18, REFRESH=19, COMMENT=20, SPEED=21, QUALITY=22, LOCATE=23, CHEVRON_RIGHT=24, REWIND=25, FAST_FORWARD=26, TRASH=27, HIDE_CONTROLS=28;
    private final IconDrawable icon;
    public IconView(Context c,int type,int sizeDp) {
        super(c); icon=new IconDrawable(type,Ui.TEXT_2);
        int size=Ui.dp(c,sizeDp);
        setLayoutParams(new LinearLayout.LayoutParams(size,size));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public void setColor(int color) { icon.setColor(color); invalidate(); }
    public void setType(int type) { icon.setType(type); invalidate(); }
    public void setFilled(boolean filled) { icon.setFilled(filled); invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        icon.setBounds(0,0,getWidth(),getHeight()); icon.draw(canvas);
    }
    public static int withAlpha(int color,int alpha) { return (color&0x00ffffff)|((alpha&255)<<24); }
    public static int blend(int c1,int c2,float t) {
        t=Math.max(0,Math.min(1,t));
        return Color.argb((int)(Color.alpha(c1)+(Color.alpha(c2)-Color.alpha(c1))*t),
                (int)(Color.red(c1)+(Color.red(c2)-Color.red(c1))*t),
                (int)(Color.green(c1)+(Color.green(c2)-Color.green(c1))*t),
                (int)(Color.blue(c1)+(Color.blue(c2)-Color.blue(c1))*t));
    }
}

