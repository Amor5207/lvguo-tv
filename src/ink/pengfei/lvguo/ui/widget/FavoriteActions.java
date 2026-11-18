package ink.pengfei.lvguo.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.store.Store;
import ink.pengfei.lvguo.ui.Ui;

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
        content.setBackground(Ui.roundStroke(activity,Ui.SURFACE,16,Ui.SEPARATOR,1));
        content.addView(Ui.text(activity,title,22,Ui.TEXT,true),Ui.lp(-1,-2));
        TextView body=Ui.text(activity,message,15,Ui.TEXT_2,false);body.setLineSpacing(Ui.dp(activity,5),1);LinearLayout.LayoutParams bodyLp=Ui.lp(-1,-2);bodyLp.topMargin=Ui.dp(activity,16);content.addView(body,bodyLp);
        LinearLayout actions=Ui.row(activity);actions.setGravity(Gravity.RIGHT);LinearLayout.LayoutParams actionsLp=Ui.lp(-1,-2);actionsLp.topMargin=Ui.dp(activity,24);content.addView(actions,actionsLp);
        final boolean[] handled={false};
        TextView cancel=Ui.action(activity,no,-1,false);cancel.setOnClickListener(v->{handled[0]=true;dialog.dismiss();if(decline!=null)decline.run();});actions.addView(cancel,Ui.lp(-2,Ui.dp(activity,46)));
        TextView ok=Ui.action(activity,yes,-1,true);ok.setFocusableInTouchMode(true);ok.setOnClickListener(v->{handled[0]=true;dialog.dismiss();accept.run();});LinearLayout.LayoutParams okLp=Ui.lp(-2,Ui.dp(activity,46));okLp.leftMargin=Ui.dp(activity,12);actions.addView(ok,okLp);
        dialog.setContentView(content);ink.pengfei.lvguo.ui.Ui.popIn(content);dialog.setOnDismissListener(d->{if(!handled[0] && dismissed!=null)dismissed.run();});Ui.closeOnBack(dialog);dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));dialog.getWindow().setLayout(Math.min(Ui.dp(activity,520),activity.getResources().getDisplayMetrics().widthPixels-Ui.dp(activity,40)),-2);dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);dialog.getWindow().setDimAmount(.55f);}ok.requestFocus();
    }
    public static void toggle(Activity activity,String id,String title,String cover,String vid,Runnable changed){
        if(Store.isFavorite(id)){Store.remove("fav",id);changed.run();return;}
        // 收藏与云端同步，没有条数上限
        Store.setFavorite(id,title,cover,vid,true);changed.run();
    }
    public static void onCompleted(Activity activity,String id,int total,Runnable next){
        boolean followed=Store.isFavorite(id);Store.markCompleted(id,total);
        if(!followed){next.run();return;}
        // 看完不再自动取消追剧（收藏没有上限，也不再弹提示）
        next.run();
    }
}
