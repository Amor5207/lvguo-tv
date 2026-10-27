package com.hgtv.app.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.hgtv.app.img.ImageLoader;

/** Two static cover layers, updated only after focus settles. */
public final class CoverBackdrop extends FrameLayout {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Layer[] layers=new Layer[2];
    private String wanted="",shown="";
    private int generation,front;
    private Runnable pending;
    private final class Layer extends ImageView {
        int requestGeneration;String requestUrl="";
        Layer(Context context){super(context);setScaleType(ScaleType.CENTER_CROP);setAlpha(0f);setFocusable(false);}
        @Override public void setImageBitmap(Bitmap bitmap){
            if(bitmap==null||bitmap.getWidth()<96||requestGeneration!=generation||!requestUrl.equals(wanted))return;
            final int version=requestGeneration;final String url=requestUrl;
            com.hgtv.app.App.io().execute(()->{
                Bitmap blurred=bitmap;
                handler.post(()->{
                    if(version!=generation||requestGeneration!=version||!url.equals(wanted)||!url.equals(requestUrl))return;
                    super.setImageBitmap(blurred);
                    int next=this==layers[0]?0:1;
                    if(next==front&&url.equals(shown))return;
                    for(Layer layer:layers)layer.animate().cancel();
                    layers[1-next].animate().alpha(0f).setDuration(420).start();
                    animate().alpha(.28f).setDuration(420).start();front=next;shown=url;
                });
            });
        }
    }
    public CoverBackdrop(Context context){super(context);setFocusable(false);setClipChildren(true);
        for(int i=0;i<2;i++){layers[i]=new Layer(context);addView(layers[i],new FrameLayout.LayoutParams(-1,-1));}
        View shade=new View(context);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x78101313,0xB0101313,0xF8101313}));addView(shade,new FrameLayout.LayoutParams(-1,-1));
    }
    public void seed(String url){if(wanted.isEmpty())showCover(url,true);}
    public void showCover(String url,boolean immediate){
        if(url==null||url.isEmpty()||url.equals(wanted))return;
        wanted=url;generation++;if(pending!=null)handler.removeCallbacks(pending);
        if(url.equals(shown))return;
        final int version=generation;
        pending=()->{if(version!=generation)return;Layer target=layers[1-front];target.animate().cancel();target.setAlpha(0f);target.requestGeneration=version;target.requestUrl=url;ImageLoader.load(url,target,480,320);};
        handler.postDelayed(pending,immediate?0:350);
    }
    @Override protected void onAttachedToWindow(){
        super.onAttachedToWindow();
        if(!shown.isEmpty()){layers[front].setAlpha(.28f);layers[1-front].setAlpha(0f);}
        // A detached page can cancel its pending cover; retry that cover on return.
        if(!wanted.isEmpty()&&!wanted.equals(shown)){String url=wanted;wanted="";showCover(url,true);}
    }
    @Override protected void onDetachedFromWindow(){generation++;if(pending!=null)handler.removeCallbacks(pending);for(Layer layer:layers)layer.animate().cancel();super.onDetachedFromWindow();}
}
