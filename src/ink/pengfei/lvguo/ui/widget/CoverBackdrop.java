package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.ui.Ui;

/**
 * 页面背后的封面画布：两层交替淡入，焦点停稳后才换图。
 *
 * 封面贴在右上角，向左、向下渐隐进背景色——首页把它当「看板」主视觉（hero 模式，不透明），
 * 其它页面只留一层很淡的氛围色（ambient 模式）。
 */
public final class CoverBackdrop extends FrameLayout {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Layer[] layers=new Layer[2];
    private final FrameLayout art;
    private final View leftFade,bottomFade,topFade;
    private String wanted="",shown="";
    private int generation,front;
    private Runnable pending;
    private float target=.2f;

    private final class Layer extends ImageView {
        int requestGeneration;String requestUrl="";
        Layer(Context context){super(context);setScaleType(ScaleType.CENTER_CROP);setAlpha(0f);setFocusable(false);}
        @Override public void setImageBitmap(Bitmap bitmap){
            if(bitmap==null||bitmap.getWidth()<96||requestGeneration!=generation||!requestUrl.equals(wanted))return;
            super.setImageBitmap(bitmap);
            int next=this==layers[0]?0:1;
            if(next==front&&requestUrl.equals(shown))return;
            for(Layer layer:layers)layer.animate().cancel();
            layers[1-next].animate().alpha(0f).setDuration(420).start();
            animate().alpha(target).setDuration(420).start();front=next;shown=requestUrl;
        }
    }

    public CoverBackdrop(Context context){
        super(context);setFocusable(false);setClipChildren(true);
        art=new FrameLayout(context);
        art.setClipChildren(true);
        for(int i=0;i<2;i++){layers[i]=new Layer(context);art.addView(layers[i],new FrameLayout.LayoutParams(-1,-1));}
        leftFade=new View(context);
        leftFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.alpha(Ui.BG,0xFF),Ui.alpha(Ui.BG,0xE8),Ui.alpha(Ui.BG,0x90),Ui.alpha(Ui.BG,0x40),Ui.alpha(Ui.BG,0x00)}));   // 必须渐到全透明，否则末端是一条直边（看起来像一块半透明白板）
        art.addView(leftFade,new FrameLayout.LayoutParams(0,-1,Gravity.LEFT));
        bottomFade=new View(context);
        bottomFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.alpha(Ui.BG,0x00),Ui.alpha(Ui.BG,0xA8),Ui.alpha(Ui.BG,0xF2),Ui.alpha(Ui.BG,0xFF)}));
        art.addView(bottomFade,new FrameLayout.LayoutParams(-1,0,Gravity.BOTTOM));
        topFade=new View(context);
        topFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.alpha(Ui.BG,0xC0),Ui.alpha(Ui.BG,0x55),Ui.alpha(Ui.BG,0x00)}));
        art.addView(topFade,new FrameLayout.LayoutParams(-1,0,Gravity.TOP));
        addView(art,new FrameLayout.LayoutParams(-1,-1,Gravity.TOP|Gravity.RIGHT));
    }

    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        super.onSizeChanged(w,h,ow,oh);
        final int aw=(int)(w*.70f),ah=(int)(h*.84f);
        post(()->{
            FrameLayout.LayoutParams a=(FrameLayout.LayoutParams)art.getLayoutParams();a.width=aw;a.height=ah;art.setLayoutParams(a);
            FrameLayout.LayoutParams l=(FrameLayout.LayoutParams)leftFade.getLayoutParams();l.width=(int)(aw*.58f);leftFade.setLayoutParams(l);
            FrameLayout.LayoutParams b=(FrameLayout.LayoutParams)bottomFade.getLayoutParams();b.height=(int)(ah*.62f);bottomFade.setLayoutParams(b);
            FrameLayout.LayoutParams t=(FrameLayout.LayoutParams)topFade.getLayoutParams();t.height=(int)(ah*.30f);topFade.setLayoutParams(t);
        });
    }

    /** hero：首页看板主视觉；否则只做淡淡的氛围底。 */
    public void setHero(boolean hero){
        // 非首页不透出大封面，页面更干净
        // 看板文字压在封面上：封面只给 55% 不透明度，让背景色透上来压暗（深色）/ 提亮（浅色），文字才看得清
        float next=hero?.55f:.18f;if(next==target)return;target=next;
        if(!shown.isEmpty()){layers[front].animate().cancel();layers[front].animate().alpha(target).setDuration(260).start();}
    }

    public void seed(String url){if(wanted.isEmpty())showCover(url,true);}
    public void showCover(String url,boolean immediate){
        if(url==null||url.isEmpty()||url.equals(wanted))return;
        wanted=url;generation++;if(pending!=null)handler.removeCallbacks(pending);
        if(url.equals(shown))return;
        final int version=generation;
        pending=()->{if(version!=generation)return;Layer t=layers[1-front];t.animate().cancel();t.setAlpha(0f);t.requestGeneration=version;t.requestUrl=url;ImageLoader.load(url,t,720,960);};
        handler.postDelayed(pending,immediate?0:300);
    }
    @Override protected void onAttachedToWindow(){
        super.onAttachedToWindow();
        if(!shown.isEmpty()){layers[front].setAlpha(target);layers[1-front].setAlpha(0f);}
        // A detached page can cancel its pending cover; retry that cover on return.
        if(!wanted.isEmpty()&&!wanted.equals(shown)){String url=wanted;wanted="";showCover(url,true);}
    }
    @Override protected void onDetachedFromWindow(){generation++;if(pending!=null)handler.removeCallbacks(pending);for(Layer layer:layers)layer.animate().cancel();super.onDetachedFromWindow();}
}
