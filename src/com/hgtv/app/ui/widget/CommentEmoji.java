package com.hgtv.app.ui.widget;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.widget.TextView;
import com.hgtv.app.R;
import java.util.*;
import java.util.regex.*;
public final class CommentEmoji {
    private static final Map<String,Integer> IDS=new HashMap<>();
    private static final Pattern TOKEN=Pattern.compile("\\[[^\\[\\]\\r\\n]{1,16}\\]");
    static {
        IDS.put("[微笑]",R.drawable.comment_emoji_1);
        IDS.put("[偷笑]",R.drawable.comment_emoji_2);
        IDS.put("[笑]",R.drawable.comment_emoji_3);
        IDS.put("[什么]",R.drawable.comment_emoji_4);
        IDS.put("[害羞]",R.drawable.comment_emoji_5);
        IDS.put("[爱慕]",R.drawable.comment_emoji_6);
        IDS.put("[飞吻]",R.drawable.comment_emoji_7);
        IDS.put("[奸笑]",R.drawable.comment_emoji_8);
        IDS.put("[尬笑]",R.drawable.comment_emoji_9);
        IDS.put("[思考]",R.drawable.comment_emoji_10);
        IDS.put("[撇嘴]",R.drawable.comment_emoji_11);
        IDS.put("[做鬼脸]",R.drawable.comment_emoji_12);
        IDS.put("[酷]",R.drawable.comment_emoji_13);
        IDS.put("[翻白眼]",R.drawable.comment_emoji_14);
        IDS.put("[惊呆]",R.drawable.comment_emoji_15);
        IDS.put("[震惊]",R.drawable.comment_emoji_16);
        IDS.put("[送心]",R.drawable.comment_emoji_17);
        IDS.put("[委屈]",R.drawable.comment_emoji_18);
        IDS.put("[快哭了]",R.drawable.comment_emoji_19);
        IDS.put("[笑哭]",R.drawable.comment_emoji_20);
        IDS.put("[哭]",R.drawable.comment_emoji_21);
        IDS.put("[大笑]",R.drawable.comment_emoji_22);
        IDS.put("[舔屏]",R.drawable.comment_emoji_23);
        IDS.put("[怒]",R.drawable.comment_emoji_24);
        IDS.put("[捂脸]",R.drawable.comment_emoji_25);
        IDS.put("[吐]",R.drawable.comment_emoji_26);
        IDS.put("[恐惧]",R.drawable.comment_emoji_27);
        IDS.put("[抓狂]",R.drawable.comment_emoji_28);
        IDS.put("[敬礼]",R.drawable.comment_emoji_29);
        IDS.put("[石化]",R.drawable.comment_emoji_30);
        IDS.put("[OK]",R.drawable.comment_emoji_31);
        IDS.put("[赞]",R.drawable.comment_emoji_32);
        IDS.put("[爱心]",R.drawable.comment_emoji_33);
        IDS.put("[伤心]",R.drawable.comment_emoji_34);
        IDS.put("[KISS]",R.drawable.comment_emoji_35);
        IDS.put("[懂了]",R.drawable.comment_emoji_36);
        IDS.put("[探究]",R.drawable.comment_emoji_37);
        IDS.put("[重拳出击]",R.drawable.comment_emoji_38);
        IDS.put("[吃瓜]",R.drawable.comment_emoji_39);
        IDS.put("[盯]",R.drawable.comment_emoji_40);
        IDS.put("[你细品]",R.drawable.comment_emoji_41);
        IDS.put("[赶稿中]",R.drawable.comment_emoji_42);
        IDS.put("[注意]",R.drawable.comment_emoji_43);
        IDS.put("[饱了]",R.drawable.comment_emoji_44);
        IDS.put("[码住]",R.drawable.comment_emoji_45);
        IDS.put("[学会了]",R.drawable.comment_emoji_46);
        IDS.put("[顶帖]",R.drawable.comment_emoji_47);
        IDS.put("[求爆更]",R.drawable.comment_emoji_48);
        IDS.put("[求关注]",R.drawable.comment_emoji_49);
        IDS.put("[爽]",R.drawable.comment_emoji_50);
        IDS.put("[鼓掌]",R.drawable.comment_emoji_51);
        IDS.put("[没看够]",R.drawable.comment_emoji_52);
        IDS.put("[送花]",R.drawable.comment_emoji_53);
    }
    public static void bind(TextView view,String text){
        if(text==null)text="";
        SpannableStringBuilder body=new SpannableStringBuilder(text);Matcher matcher=TOKEN.matcher(text);
        int size=Math.round(view.getTextSize()*1.05f);
        while(matcher.find()){Integer id=IDS.get(matcher.group());if(id==null)continue;
            Drawable drawable=view.getResources().getDrawable(id,view.getContext().getTheme());drawable.setBounds(0,0,size,size);
            body.setSpan(new CenteredEmojiSpan(drawable),matcher.start(),matcher.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(body);
    }
    private static final class CenteredEmojiSpan extends ImageSpan {
        CenteredEmojiSpan(Drawable drawable){super(drawable,ImageSpan.ALIGN_BASELINE);}
        @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt fm){
            Drawable d=getDrawable();int height=d.getBounds().height();
            if(fm!=null){Paint.FontMetricsInt base=paint.getFontMetricsInt();int center=(base.ascent+base.descent)/2;
                int top=center-height/2,bottom=top+height;fm.ascent=Math.min(base.ascent,top);fm.descent=Math.max(base.descent,bottom);fm.top=Math.min(base.top,fm.ascent);fm.bottom=Math.max(base.bottom,fm.descent);}
            return d.getBounds().width();
        }
        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int y,int bottom,Paint paint){
            Drawable d=getDrawable();Paint.FontMetricsInt fm=paint.getFontMetricsInt();int height=d.getBounds().height();
            int transY=y+(fm.ascent+fm.descent-height)/2;canvas.save();canvas.translate(x,transY);d.draw(canvas);canvas.restore();
        }
    }
}
