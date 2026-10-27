package com.hgtv.app.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import com.hgtv.app.App;
import com.hgtv.app.store.Store;
import com.hgtv.app.ui.Ui;

/** Shared TV prompts; storage enforces the same capacity for remote clients. */
public final class FavoriteActions {
    private FavoriteActions(){}
    private static void prompt(Activity activity,String title,String message,String yes,String no,Runnable accept,Runnable decline){
        prompt(activity,title,message,yes,no,accept,decline,decline);
    }
    private static void prompt(Activity activity,String title,String message,String yes,String no,Runnable accept,Runnable decline,Runnable dismissed){
        if(activity.isFinishing() || activity.isDestroyed())return;
        Dialog dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content=Ui.column(activity);Ui.padding(content,28,24,28,24);
        content.setBackground(Ui.roundStroke(activity,0xFF1B2220,16,0x24FFFFFF,1));
        content.addView(Ui.text(activity,title,22,Ui.TEXT,true),Ui.lp(-1,-2));
        TextView body=Ui.text(activity,message,15,Ui.TEXT_2,false);body.setLineSpacing(Ui.dp(activity,5),1);LinearLayout.LayoutParams bodyLp=Ui.lp(-1,-2);bodyLp.topMargin=Ui.dp(activity,16);content.addView(body,bodyLp);
        LinearLayout actions=Ui.row(activity);actions.setGravity(Gravity.RIGHT);LinearLayout.LayoutParams actionsLp=Ui.lp(-1,-2);actionsLp.topMargin=Ui.dp(activity,24);content.addView(actions,actionsLp);
        final boolean[] handled={false};
        TextView cancel=Ui.action(activity,no,-1,false);cancel.setOnClickListener(v->{handled[0]=true;dialog.dismiss();if(decline!=null)decline.run();});actions.addView(cancel,Ui.lp(-2,Ui.dp(activity,46)));
        TextView ok=Ui.action(activity,yes,-1,true);ok.setFocusableInTouchMode(true);ok.setOnClickListener(v->{handled[0]=true;dialog.dismiss();accept.run();});LinearLayout.LayoutParams okLp=Ui.lp(-2,Ui.dp(activity,46));okLp.leftMargin=Ui.dp(activity,12);actions.addView(ok,okLp);
        dialog.setContentView(content);dialog.setOnDismissListener(d->{if(!handled[0] && dismissed!=null)dismissed.run();});Ui.closeOnBack(dialog);dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));dialog.getWindow().setLayout(Math.min(Ui.dp(activity,520),activity.getResources().getDisplayMetrics().widthPixels-Ui.dp(activity,40)),-2);dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);dialog.getWindow().setDimAmount(.55f);}ok.requestFocus();
    }
    public static void toggle(Activity activity,String id,String title,String cover,String vid,Runnable changed){
        if(Store.isFavorite(id)){Store.remove("fav",id);changed.run();return;}
        Runnable add=()->{try{Store.setFavorite(id,title,cover,vid,true);changed.run();}catch(Store.FavoriteFullException e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();}};
        if(Store.favorites().size()<Store.FAVORITE_LIMIT){add.run();return;}
        int done=Store.completedFavorites();
        if(done>0)prompt(activity,"追剧空间已满","已保留300部追剧，其中"+done+"部已经看完。清理后即可添加这部剧。","清理并添加","暂不添加",()->{Store.clearCompletedFavorites();add.run();},null);
        else prompt(activity,"追剧空间已满","已保留300部追剧，暂时没有已看完的剧可以清理。请到追剧页取消部分追剧后再添加。","知道了","取消",()->{},null);
    }
    public static void offerCleanup(Activity activity,Runnable changed){
        int count=Store.favorites().size(),done=Store.completedFavorites();
        if(done>=10 || (Store.FAVORITE_LIMIT-count<10 && done>0))prompt(activity,"清理已看完的追剧？","已有"+done+"部追剧看完，剩余空间"+(Store.FAVORITE_LIMIT-count)+"条。清理不会删除观看历史。","一键清理","暂不清理",()->{Store.clearCompletedFavorites();changed.run();},null);
    }
    public static void onCompleted(Activity activity,String id,int total,Runnable next){
        boolean followed=Store.isFavorite(id);Store.markCompleted(id,total);
        if(!followed){next.run();return;}
        Runnable apply=()->{if(Store.autoUnfollow())Store.remove("fav",id);next.run();};
        if(!App.prefs().getBoolean("auto_unfollow_reminded",false)){
            App.prefs().edit().putBoolean("auto_unfollow_reminded",true).apply();
            prompt(activity,"这部追剧已看完", "看完后自动取消追剧"+(Store.autoUnfollow()?"默认开启":"已关闭")+"。可在设置中随时修改，观看历史会保留。", "开启自动取消", "保留追剧",()->{Store.autoUnfollow(true);apply.run();},()->{Store.autoUnfollow(false);apply.run();},apply);
        }else apply.run();
    }
}
