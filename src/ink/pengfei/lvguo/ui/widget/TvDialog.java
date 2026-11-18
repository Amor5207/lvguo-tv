package ink.pengfei.lvguo.ui.widget;
import android.app.*;import android.graphics.Color;import android.graphics.drawable.ColorDrawable;import android.view.*;import android.widget.*;import ink.pengfei.lvguo.ui.Ui;
/** Shared application-styled confirmations and item actions. */
public final class TvDialog {
 private TvDialog(){}
 public static Dialog confirm(Activity host,String title,String message,String action,Runnable accept){
  Dialog d=new Dialog(host);d.requestWindowFeature(Window.FEATURE_NO_TITLE);
  LinearLayout panel=Ui.column(host);Ui.padding(panel,28,24,28,24);panel.setBackground(Ui.roundStroke(host,Ui.SURFACE,16,Ui.SEPARATOR,1));
  panel.addView(Ui.text(host,title,22,Ui.TEXT,true),Ui.lp(-1,-2));
  if(message!=null&&!message.isEmpty()){TextView body=Ui.text(host,message,15,Ui.TEXT_2,false);LinearLayout.LayoutParams lp=Ui.lp(-1,-2);lp.topMargin=Ui.dp(host,14);panel.addView(body,lp);}
  LinearLayout actions=Ui.row(host);actions.setGravity(Gravity.RIGHT);LinearLayout.LayoutParams ap=Ui.lp(-1,-2);ap.topMargin=Ui.dp(host,24);panel.addView(actions,ap);
  TextView cancel=Ui.action(host,"取消",-1,false);cancel.setOnClickListener(v->d.dismiss());actions.addView(cancel,Ui.lp(-2,Ui.dp(host,46)));
  TextView ok=Ui.action(host,action,-1,false);ok.setOnClickListener(v->{d.dismiss();accept.run();});LinearLayout.LayoutParams op=Ui.lp(-2,Ui.dp(host,46));op.leftMargin=Ui.dp(host,18);actions.addView(ok,op);
  d.setContentView(panel);Ui.popIn(panel);d.setCanceledOnTouchOutside(false);Ui.closeOnBack(d);d.show();
  Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setLayout(Math.min(Ui.dp(host,520),host.getResources().getDisplayMetrics().widthPixels-Ui.dp(host,40)),-2);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.55f);}cancel.requestFocus();return d;
 }
 /**
  * 二级选择弹窗：列出所有选项，当前值打勾、焦点落在当前值上；选完回调 pick(index)。
  * 无论选择还是返回关闭，都会把焦点还给 returnFocus（打开它的那一行），不会乱跳。
  */
 public interface Pick { void pick(int index); }
 public static Dialog choose(Activity host,String title,String hint,String[] options,int selected,View returnFocus,Pick pick){
  Dialog d=new Dialog(host);d.requestWindowFeature(Window.FEATURE_NO_TITLE);
  LinearLayout panel=Ui.column(host);Ui.padding(panel,24,22,24,18);panel.setBackground(Ui.roundStroke(host,Ui.SURFACE,18,Ui.SEPARATOR,1));
  panel.addView(Ui.text(host,title,21,Ui.TEXT,true),Ui.lp(-1,-2));
  if(hint!=null&&!hint.isEmpty()){TextView h=Ui.text(host,hint,13,Ui.TEXT_3,false);LinearLayout.LayoutParams hl=Ui.lp(-1,-2);hl.topMargin=Ui.dp(host,6);panel.addView(h,hl);}
  ScrollView scroll=new FocusScrollView(host);scroll.setVerticalScrollBarEnabled(false);LinearLayout list=Ui.column(host);Ui.padding(list,0,14,0,4);scroll.addView(list);
  final boolean[] selectedOption={false};
  View focus=null;
  for(int n=0;n<options.length;n++){
   final int idx=n;final boolean on=n==selected;
   LinearLayout row=Ui.row(host);row.setGravity(Gravity.CENTER_VERTICAL);row.setFocusable(true);Ui.padding(row,18,0,14,0);
   TextView label=Ui.text(host,options[n],17,Ui.TEXT,on);row.addView(label,Ui.lp(0,-2,1));
   ink.pengfei.lvguo.ui.IconView check=new ink.pengfei.lvguo.ui.IconView(host,ink.pengfei.lvguo.ui.IconView.CHECK,18);check.setVisibility(on?View.VISIBLE:View.INVISIBLE);
   row.addView(check,Ui.lp(Ui.dp(host,18),Ui.dp(host,18)));
   Runnable paint=()->{boolean f=row.hasFocus();row.setBackground(f?Ui.round(host,Ui.FOCUS_FILL,12):null);int fg=f?Ui.ON_FOCUS:on?Ui.ACCENT_TEXT:Ui.TEXT;label.setTextColor(fg);check.setColor(fg);};
   paint.run();row.setOnFocusChangeListener((v,f)->paint.run());
   row.setOnClickListener(v->{selectedOption[0]=true;d.dismiss();pick.pick(idx);});
   LinearLayout.LayoutParams lp=Ui.lp(-1,Ui.dp(host,50));lp.bottomMargin=Ui.dp(host,4);list.addView(row,lp);
   if(on||focus==null&&n==0)focus=row;
  }
  int maxH=host.getResources().getDisplayMetrics().heightPixels-Ui.dp(host,160);
  panel.addView(scroll,Ui.lp(-1,Math.min(maxH,Ui.dp(host,14+options.length*54))));
  d.setContentView(panel);Ui.popIn(panel);d.setCanceledOnTouchOutside(true);Ui.closeOnBack(d);
  d.setOnDismissListener(x->{if(!selectedOption[0]&&returnFocus!=null)returnFocus.post(returnFocus::requestFocus);});
  d.show();
  Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setLayout(Math.min(Ui.dp(host,460),host.getResources().getDisplayMetrics().widthPixels-Ui.dp(host,40)),-2);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.55f);}
  if(focus!=null)focus.requestFocus();
  return d;
 }
}
