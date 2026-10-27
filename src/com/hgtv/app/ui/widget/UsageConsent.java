package com.hgtv.app.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import com.hgtv.app.App;
import com.hgtv.app.ui.Ui;

/** Explicit first-use consent, before browsing or starting the companion service. */
public final class UsageConsent {
    private UsageConsent(){}
    public static void show(Activity activity,Runnable accepted){
        Dialog dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content=Ui.column(activity);Ui.padding(content,28,24,28,24);
        content.setBackground(Ui.roundStroke(activity,0xFF1B2420,18,0x20FFFFFF,1));
        content.addView(Ui.text(activity,"使用须知",24,Ui.TEXT,true),Ui.lp(-1,-2));
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(false);
        TextView body=Ui.text(activity,"本软件仅供个人学习、技术研究与交流使用。请遵守适用的法律法规，尊重著作权及其他合法权益；未经授权，不得复制、传播或以其他方式使用受保护的内容，不得利用本软件从事任何违法、侵权活动。\n\n继续使用前，请阅读并同意上述说明。",16,Ui.TEXT_2,false);
        body.setLineSpacing(Ui.dp(activity,6),1);scroll.addView(body);
        LinearLayout.LayoutParams textLp=Ui.lp(-1,-2);textLp.topMargin=Ui.dp(activity,18);
        textLp.height=Math.min(Ui.dp(activity,180),Math.max(Ui.dp(activity,96),activity.getResources().getDisplayMetrics().heightPixels-Ui.dp(activity,220)));
        content.addView(scroll,textLp);
        LinearLayout actions=Ui.row(activity);actions.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams actionLp=Ui.lp(-1,-2);actionLp.topMargin=Ui.dp(activity,24);content.addView(actions,actionLp);
        TextView decline=Ui.action(activity,"不同意并退出",-1,false);decline.setOnClickListener(v->{dialog.dismiss();activity.finish();});actions.addView(decline,Ui.lp(-2,Ui.dp(activity,46)));
        TextView agree=Ui.action(activity,"同意并继续",-1,true);agree.setFocusableInTouchMode(true);
        agree.setOnClickListener(v->{App.prefs().edit().putBoolean("usage_consent",true).apply();dialog.dismiss();accepted.run();});
        LinearLayout.LayoutParams agreeLp=Ui.lp(-2,Ui.dp(activity,46));agreeLp.leftMargin=Ui.dp(activity,16);actions.addView(agree,agreeLp);
        dialog.setContentView(content);dialog.setCancelable(false);dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((d,key,event)->{if(key!=KeyEvent.KEYCODE_BACK)return false;if(event.getAction()==KeyEvent.ACTION_UP){dialog.dismiss();activity.finish();}return true;});
        dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setLayout(Math.min(Ui.dp(activity,560),activity.getResources().getDisplayMetrics().widthPixels-Ui.dp(activity,40)),-2);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.8f);}agree.requestFocus();
    }
}
