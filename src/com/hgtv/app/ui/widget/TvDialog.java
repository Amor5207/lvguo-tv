package com.hgtv.app.ui.widget;
import android.app.*;import android.graphics.Color;import android.graphics.drawable.ColorDrawable;import android.view.*;import android.widget.*;import com.hgtv.app.ui.Ui;
/** Shared application-styled confirmations and item actions. */
public final class TvDialog {
 private TvDialog(){}
 public static Dialog confirm(Activity host,String title,String message,String action,Runnable accept){
  Dialog d=new Dialog(host);d.requestWindowFeature(Window.FEATURE_NO_TITLE);
  LinearLayout panel=Ui.column(host);Ui.padding(panel,28,24,28,24);panel.setBackground(Ui.roundStroke(host,0xFF1B2220,16,0x24FFFFFF,1));
  panel.addView(Ui.text(host,title,22,Ui.TEXT,true),Ui.lp(-1,-2));
  if(message!=null&&!message.isEmpty()){TextView body=Ui.text(host,message,15,Ui.TEXT_2,false);LinearLayout.LayoutParams lp=Ui.lp(-1,-2);lp.topMargin=Ui.dp(host,14);panel.addView(body,lp);}
  LinearLayout actions=Ui.row(host);actions.setGravity(Gravity.RIGHT);LinearLayout.LayoutParams ap=Ui.lp(-1,-2);ap.topMargin=Ui.dp(host,24);panel.addView(actions,ap);
  TextView cancel=Ui.action(host,"取消",-1,false);cancel.setOnClickListener(v->d.dismiss());actions.addView(cancel,Ui.lp(-2,Ui.dp(host,46)));
  TextView ok=Ui.action(host,action,-1,false);ok.setOnClickListener(v->{d.dismiss();accept.run();});LinearLayout.LayoutParams op=Ui.lp(-2,Ui.dp(host,46));op.leftMargin=Ui.dp(host,18);actions.addView(ok,op);
  d.setContentView(panel);d.setCanceledOnTouchOutside(false);Ui.closeOnBack(d);d.show();
  Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setLayout(Math.min(Ui.dp(host,520),host.getResources().getDisplayMetrics().widthPixels-Ui.dp(host,40)),-2);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.55f);}cancel.requestFocus();return d;
 }
}
