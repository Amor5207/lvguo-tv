package com.hgtv.app.ui.widget;
import android.app.*;
import android.view.*;
import android.widget.*;
import com.hgtv.app.App;
import com.hgtv.app.net.Browse;
import com.hgtv.app.ui.*;
import org.json.*;
import java.util.*;

public final class CommentsDialog {
    public static void back(Dialog dialog){
        View close=dialog.getWindow()==null?null:dialog.getWindow().getDecorView().findViewWithTag("comments_close");
        if(close!=null && !close.hasFocus())close.requestFocus();else dialog.dismiss();
    }
    public static void show(Activity activity,String id){show(activity,id,null,"整部剧的剧评",null);}
    public static Dialog show(Activity activity,String id,String episodeId,String title,Runnable dismiss){CommentsDialog d=new CommentsDialog(activity,id);d.episodeId=episodeId;d.heading=title;d.onDismiss=dismiss;d.open();return d.dialog;}
    private String episodeId,heading;private Runnable onDismiss;
    private final Activity activity;private final String id;private Dialog dialog;private LinearLayout comments;
    private TextView status,more;private int offset;private boolean busy,hasMore=true;private final Set<String> seen=new HashSet<>();
    private CommentsDialog(Activity a,String s){activity=a;id=s;}
    private void open(){
        dialog=new Dialog(activity);LinearLayout panel=Ui.column(activity);Ui.padding(panel,24,22,24,18);panel.setBackgroundColor(Ui.SURFACE);
        LinearLayout head=Ui.row(activity);head.addView(Ui.text(activity,heading,21,Ui.TEXT,true),Ui.lp(0,-2,1));
        TextView close=Ui.action(activity,"关闭",IconView.CLOSE,false);close.setTag("comments_close");close.setOnClickListener(v->dialog.dismiss());head.addView(close,Ui.lp(-2,Ui.dp(activity,44)));panel.addView(head);
        status=Ui.text(activity,"正在加载…",12,Ui.TEXT_3,false);Ui.padding(status,0,10,0,12);panel.addView(status);
        ScrollView scroll=new ScrollView(activity);scroll.setVerticalScrollBarEnabled(false);comments=Ui.column(activity);scroll.addView(comments);panel.addView(scroll,Ui.lp(-1,0,1));
        final long openedAt=android.os.SystemClock.uptimeMillis();final boolean[] backPressed={false};
        dialog.setOnKeyListener((d,key,event)->{
            if(key!=KeyEvent.KEYCODE_BACK)return false;
            if(event.getDownTime()<openedAt)return true;
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)backPressed[0]=true;
            if(event.getAction()==KeyEvent.ACTION_UP){
                boolean fresh=backPressed[0]&&!event.isCanceled();backPressed[0]=false;
                if(fresh)back(dialog);
            }
            return true;
        });
        more=Ui.action(activity,"继续加载",-1,false);more.setOnClickListener(v->load());panel.addView(more,Ui.lp(-1,Ui.dp(activity,44)));
        dialog.setOnDismissListener(d->{if(onDismiss!=null)onDismiss.run();});
        dialog.setContentView(panel);dialog.show();Window window=dialog.getWindow();if(window!=null){window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.RIGHT);window.setLayout(Math.min(Ui.dp(activity,560),(int)(activity.getResources().getDisplayMetrics().widthPixels*.54f)),-1);}
        close.requestFocus();load();
    }
    private void load(){if(busy||!hasMore)return;busy=true;more.setText("正在加载…");
        App.io().execute(()->{try{JSONObject data=Browse.comments(id,episodeId,offset);activity.runOnUiThread(()->{
            if(!dialog.isShowing())return;busy=false;offset=data.optInt("next_offset",offset);hasMore=data.optBoolean("has_more");
            JSONArray rows=data.optJSONArray("comment");if(rows!=null)for(int n=0;n<rows.length();n++){
                JSONObject row=rows.optJSONObject(n);if(row==null||!seen.add(row.optString("comment_id")))continue;
                LinearLayout card=Ui.column(activity);Ui.padding(card,10,16,10,16);card.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                card.setFocusable(true);card.setOnFocusChangeListener((v,focus)->v.setBackground(Ui.roundStroke(activity,focus?Ui.TINT_PRIMARY:0,6,focus?Ui.PRIMARY:0,focus?2:0)));
                JSONObject user=row.optJSONObject("user_info");String name=user==null?"观众":user.optString("user_name","观众");String score=row.optString("score","");
                card.addView(Ui.text(activity,name+(score.isEmpty()?"":"   ·   "+score+"分")+"   ·   "+row.optInt("digg_count")+" 赞",13,Ui.PRIMARY,false));
                TextView body=Ui.text(activity,"",16,Ui.TEXT,false);String content=Browse.commentText(row);CommentEmoji.bind(body,content.trim().isEmpty()?(Browse.commentImages(row).length()>0?"[图片评论]":"该评论内容暂不可用"):content);body.setLineSpacing(Ui.dp(activity,4),1);Ui.padding(body,0,8,0,0);card.addView(body);
                LinearLayout.LayoutParams lp=Ui.lp(-1,-2);lp.bottomMargin=Ui.dp(activity,12);comments.addView(card,lp);
                View divider=new View(activity);divider.setBackgroundColor(0x12FFFFFF);comments.addView(divider,Ui.lp(-1,Ui.dp(activity,1)));
            }
            status.setText("共 "+data.optLong("comment_cnt")+" 条评论 · 已加载 "+seen.size()+" 条");
            more.setText(hasMore?"继续加载":"剧评已全部加载");
        });}catch(Exception error){activity.runOnUiThread(()->{if(!dialog.isShowing())return;busy=false;status.setText("评论请求未完成，请重试");more.setText("重试加载");});}});
    }
}
