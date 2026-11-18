package ink.pengfei.lvguo.ui.widget;
import android.app.*;
import android.view.*;
import android.widget.*;
import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.net.Browse;
import ink.pengfei.lvguo.ui.*;
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
    private static final int[] AVATAR_TINTS={0xFF5E8BFF,0xFF34C759,0xFFFF9F0A,0xFFAF52DE,0xFFFF6482,0xFF30B0C7};
    /** 圆形真实头像；图片到达前显示按名字着色的首字，加载失败也不会空着。 */
    public static View avatar(android.content.Context c,String name,String url,int sizeDp){
        RoundFrame frame=new RoundFrame(c,sizeDp/2f);
        int hash=0;for(int i=0;i<name.length();i++)hash+=name.charAt(i);
        TextView letter=Ui.text(c,name.isEmpty()?"?":name.substring(0,name.offsetByCodePoints(0,1)),sizeDp*0.42f>14?16:13,0xFFFFFFFF,true);
        letter.setGravity(Gravity.CENTER);letter.setBackgroundColor(AVATAR_TINTS[Math.abs(hash)%AVATAR_TINTS.length]);
        frame.addView(letter,new FrameLayout.LayoutParams(-1,-1));
        if(url!=null && url.startsWith("http")){
            ImageView image=new ImageView(c);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
            int px=Ui.dp(c,sizeDp)*2;ink.pengfei.lvguo.img.ImageLoader.load(url,image,px,px);
        }
        return frame;
    }
    private void open(){
        dialog=new Dialog(activity);LinearLayout panel=Ui.column(activity);Ui.padding(panel,24,22,24,18);panel.setBackgroundColor(Ui.SURFACE);
        LinearLayout head=Ui.row(activity);head.addView(Ui.text(activity,heading,21,Ui.TEXT,true),Ui.lp(0,-2,1));
        TextView close=Ui.action(activity,"关闭",IconView.CLOSE,false);close.setTag("comments_close");close.setOnClickListener(v->dialog.dismiss());head.addView(close,Ui.lp(-2,Ui.dp(activity,44)));panel.addView(head);
        status=Ui.text(activity,"正在加载…",12,Ui.TEXT_3,false);Ui.padding(status,0,10,0,12);panel.addView(status);
        ScrollView scroll=new FocusScrollView(activity);scroll.setVerticalScrollBarEnabled(false);comments=Ui.column(activity);scroll.addView(comments);panel.addView(scroll,Ui.lp(-1,0,1));
        final long openedAt=android.os.SystemClock.uptimeMillis();final boolean[] backPressed={false};
        dialog.setOnKeyListener((d,key,event)->{
            if(key!=KeyEvent.KEYCODE_BACK&&key!=KeyEvent.KEYCODE_ESCAPE&&key!=KeyEvent.KEYCODE_BUTTON_B)return false;
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
        dialog.setContentView(panel);ink.pengfei.lvguo.ui.Ui.popIn(panel);dialog.show();Window window=dialog.getWindow();if(window!=null){window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.RIGHT);window.setLayout(Math.min(Ui.dp(activity,560),(int)(activity.getResources().getDisplayMetrics().widthPixels*.54f)),-1);}
        close.requestFocus();load();
    }
    private void load(){if(busy||!hasMore)return;busy=true;more.setText("正在加载…");
        App.io().execute(()->{try{JSONObject data=Browse.comments(id,episodeId,offset);activity.runOnUiThread(()->{
            if(!dialog.isShowing())return;busy=false;offset=data.optInt("next_offset",offset);hasMore=data.optBoolean("has_more");
            JSONArray rows=data.optJSONArray("comment");if(rows!=null)for(int n=0;n<rows.length();n++){
                JSONObject row=rows.optJSONObject(n);if(row==null||!seen.add(row.optString("comment_id")))continue;
                LinearLayout card=Ui.column(activity);Ui.padding(card,10,16,10,16);card.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                card.setFocusable(true);card.setOnFocusChangeListener((v,focus)->v.setBackground(focus?Ui.round(activity,Ui.FILL,16):null));
                JSONObject user=row.optJSONObject("user_info");String name=user==null?"观众":user.optString("user_name","观众");String score=row.optString("score","");
                String avatarUrl=user==null?"":user.optString("user_avatar",user.optString("expand_user_avatar",""));
                LinearLayout head=Ui.row(activity);head.setGravity(Gravity.CENTER_VERTICAL);
                head.addView(avatar(activity,name,avatarUrl,36),Ui.lp(Ui.dp(activity,36),Ui.dp(activity,36)));
                LinearLayout who=Ui.column(activity);
                who.addView(Ui.text(activity,name,14,Ui.TEXT,true));
                who.addView(Ui.text(activity,(score.isEmpty()?"":score+" 分  ·  ")+row.optInt("digg_count")+" 赞",12,Ui.TEXT_3,false));
                LinearLayout.LayoutParams wl=Ui.lp(0,-2,1);wl.leftMargin=Ui.dp(activity,12);head.addView(who,wl);
                card.addView(head);
                TextView body=Ui.text(activity,"",16,Ui.TEXT,false);String content=Browse.commentText(row);CommentEmoji.bind(body,content.trim().isEmpty()?(Browse.commentImages(row).length()>0?"[图片评论]":"该评论内容暂不可用"):content);body.setLineSpacing(Ui.dp(activity,4),1);Ui.padding(body,0,8,0,0);card.addView(body);
                LinearLayout.LayoutParams lp=Ui.lp(-1,-2);lp.bottomMargin=Ui.dp(activity,12);comments.addView(card,lp);
                View divider=new View(activity);divider.setBackgroundColor(Ui.SEPARATOR);comments.addView(divider,Ui.lp(-1,Ui.dp(activity,1)));
            }
            status.setText("共 "+data.optLong("comment_cnt")+" 条评论 · 已加载 "+seen.size()+" 条");
            more.setText(hasMore?"继续加载":"剧评已全部加载");
        });}catch(Exception error){activity.runOnUiThread(()->{if(!dialog.isShowing())return;busy=false;status.setText("评论请求未完成，请重试");more.setText("重试加载");});}});
    }
}
