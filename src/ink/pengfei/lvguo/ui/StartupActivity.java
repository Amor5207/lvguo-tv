package ink.pengfei.lvguo.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import ink.pengfei.lvguo.App;

/** Short startup transition; playback signing is pure JVM and needs no SDK warm-up. */
public final class StartupActivity extends Activity {
    private SlimLoader loader;

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        if(ink.pengfei.lvguo.care.RestMode.enforce(this))return;
        ink.pengfei.lvguo.net.LibrarySync.preloadHistory();
        Ui.applyTheme(this);

        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Ui.BG);

        // 光晕：以主色为底的大圆形径向渐变，位置略靠上，像图标在发光
        View glow=new View(this);
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0,0});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);g.setGradientRadius(Ui.dp(this,330));
        g.setColors(new int[]{(Ui.PRIMARY&0x00FFFFFF)|0x38000000,Ui.PRIMARY&0x00FFFFFF});
        glow.setBackground(g);
        FrameLayout.LayoutParams gl=new FrameLayout.LayoutParams(Ui.dp(this,660),Ui.dp(this,660),Gravity.CENTER);gl.topMargin=-Ui.dp(this,70);
        root.addView(glow,gl);

        LinearLayout page=Ui.column(this);page.setGravity(Gravity.CENTER_HORIZONTAL);
        ink.pengfei.lvguo.ui.widget.RoundFrame frame=new ink.pengfei.lvguo.ui.widget.RoundFrame(this,28);
        ImageView logo=new ImageView(this);logo.setImageResource(ink.pengfei.lvguo.R.drawable.app_icon);logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(logo,new FrameLayout.LayoutParams(-1,-1));
        page.addView(frame,Ui.lp(Ui.dp(this,112),Ui.dp(this,112)));

        TextView name=Ui.text(this,"绿果TV",34,Ui.TEXT,true);
        LinearLayout.LayoutParams nl=Ui.lp(-2,-2);nl.topMargin=Ui.dp(this,22);page.addView(name,nl);

        loader=new SlimLoader(this);
        LinearLayout.LayoutParams ll=Ui.lp(Ui.dp(this,180),Ui.dp(this,4));ll.topMargin=Ui.dp(this,40);page.addView(loader,ll);

        TextView status=Ui.text(this,"正在进入绿果TV",15,Ui.TEXT_3,false);
        LinearLayout.LayoutParams sl=Ui.lp(-2,-2);sl.topMargin=Ui.dp(this,16);page.addView(status,sl);
        root.addView(page,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));

        String v="";try{v=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){}
        TextView ver=Ui.text(this,"版本 "+v,13,Ui.TEXT_3,false);
        FrameLayout.LayoutParams vl=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);vl.bottomMargin=Ui.dp(this,36);
        root.addView(ver,vl);
        setContentView(root);

        // 入场：图标与名称淡入并轻微放大
        page.setAlpha(0f);page.setScaleX(.94f);page.setScaleY(.94f);
        page.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();

        App.io().execute(()->runOnUiThread(()->{
            if(isFinishing()||isDestroyed())return;
            // 必须登录才能看：未登录一律先进登录页
            startActivity(new Intent(this,ink.pengfei.lvguo.net.Session.loggedIn()?RootActivity.class:LoginActivity.class));finish();
        }));
    }

    @Override protected void onDestroy(){if(loader!=null)loader.stop();super.onDestroy();}

    /** 细线不确定进度：一段圆角亮条在轨道里来回滑动。 */
    static final class SlimLoader extends View{
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator anim=ValueAnimator.ofFloat(0f,1f);
        private float t=.35f;
        SlimLoader(Context c){
            super(c);
            anim.setDuration(1300);anim.setRepeatCount(ValueAnimator.INFINITE);anim.setRepeatMode(ValueAnimator.REVERSE);
            anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            anim.addUpdateListener(a->{t=(float)a.getAnimatedValue();invalidate();});
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();anim.start();}
        @Override protected void onDetachedFromWindow(){anim.cancel();super.onDetachedFromWindow();}
        void stop(){anim.cancel();}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),r=h/2f;
            paint.setColor(Ui.FILL);c.drawRoundRect(0,0,w,h,r,r,paint);
            float seg=w*.38f,x=(w-seg)*t;
            paint.setColor(Ui.PRIMARY);c.drawRoundRect(x,0,x+seg,h,r,r,paint);
        }
    }
}
