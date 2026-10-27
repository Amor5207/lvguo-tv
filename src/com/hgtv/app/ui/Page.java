package com.hgtv.app.ui;

import android.content.Context;
import android.view.View;

/**
 * 页面基类。所有页面共享同一个 Activity，切换只是显隐，
 * 目的是：① 返回时列表位置/焦点/已解码封面都还在（TV 上反复重建列表非常卡）；
 * ② 避免 Activity 转场动画和硬件层重建。
 */
public abstract class Page {

    protected RootActivity host;
    protected Context ctx;
    public View root;

    public final void attach(RootActivity h) {
        this.host = h;
        this.ctx = h;
        this.root = build();
        if(!(this instanceof com.hgtv.app.ui.page.DetailPage))this.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    }

    protected abstract View build();

    /** 首次显示 / 每次切回时调用 */
    public void onShow() {}

    public void onHide() {}

    /** 返回键：true 表示已消费 */
    public boolean onBack() {
        return false;
    }

    /** 让页面把焦点放到"第一个该看的东西"上 */
    public void focusFirst() {}

    /** 让最左侧元素按 LEFT 时回到导航栏的当前项 */
    public void bindNav(int navViewId) {}

    public Runnable rememberScroll(){
        java.util.Map<View,int[]> positions=new java.util.IdentityHashMap<>();
        remember(root,positions);
        return ()->{for(java.util.Map.Entry<View,int[]> e:positions.entrySet())e.getKey().scrollTo(e.getValue()[0],e.getValue()[1]);};
    }
    private void remember(View view,java.util.Map<View,int[]> positions){
        if(view instanceof android.widget.ScrollView || view instanceof android.widget.HorizontalScrollView)
            positions.put(view,new int[]{view.getScrollX(),view.getScrollY()});
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int n=0;n<group.getChildCount();n++)remember(group.getChildAt(n),positions);}
    }
    public boolean returnToTop(){
        android.widget.ScrollView scroll=findScrolled(root);
        if(scroll==null)return false;
        focusFirst();scroll.scrollTo(0,0);scroll.post(()->scroll.scrollTo(0,0));return true;
    }
    private android.widget.ScrollView findScrolled(View view){
        if(view instanceof android.widget.ScrollView && view.getScrollY()>0)return (android.widget.ScrollView)view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int n=0;n<group.getChildCount();n++){android.widget.ScrollView found=findScrolled(group.getChildAt(n));if(found!=null)return found;}}
        return null;
    }
}
