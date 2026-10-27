package com.hgtv.app.ui.page;
import android.view.*;
import android.widget.*;
import com.hgtv.app.App;
import com.hgtv.app.model.Series;
import com.hgtv.app.net.*;
import com.hgtv.app.ui.*;
import com.hgtv.app.ui.widget.CardGrid;
import org.json.*;
import java.util.*;

/** Actual server selectors and cursors; changing a condition starts a new result generation. */
public final class FilterPage extends Page {
    private ScrollView scroll;private LinearLayout groups;private CardGrid grid;
    private TextView status,more,filterSummary;private View first;
    private String board=Repo.BOARD_RECOMMEND,filter="",session=UUID.randomUUID().toString();
    private int cursor,generation,nav;private boolean loading,started,hasMore=true,selectors;
    private final Set<String> seen=new HashSet<>();
    private final Map<TextView,String[]> chips=new LinkedHashMap<>();
    private JSONObject categoryOptions;private String contentType="all";private final JSONObject selected=new JSONObject();
    private String selectorShape="";private JSONArray selectorRows=new JSONArray();private int activeGroup;private LinearLayout groupTabs,optionHost;
    @Override public boolean onBack(){if(scroll!=null&&scroll.getScrollY()>0){if(first!=null)first.requestFocus();scroll.scrollTo(0,0);scroll.post(()->scroll.scrollTo(0,0));return true;}return false;}
    @Override protected View build(){
        scroll=new ScrollView(ctx);scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);scroll.setFocusable(false);
        LinearLayout col=Ui.column(ctx);Ui.padding(col,32,28,32,32);scroll.addView(col);
        col.addView(Ui.text(ctx,"分类选剧",26,Ui.TEXT,true));
        TextView sub=Ui.text(ctx,"按榜单与题材，找到想看的故事",13,Ui.TEXT_3,false);Ui.padding(sub,0,0,0,18);sub.setText("");col.addView(sub);
        groups=Ui.column(ctx);col.addView(groups,Ui.lp(-1,-2));
        addGroup("榜单",new String[]{"推荐","热播","新剧"},new String[]{Repo.BOARD_RECOMMEND,Repo.BOARD_HOT,Repo.BOARD_NEW},true);
        groups.addView(new com.hgtv.app.ui.widget.SkeletonView(ctx,com.hgtv.app.ui.widget.SkeletonView.FILTERS,4,0,4),Ui.lp(-1,-2));
        status=Ui.text(ctx,"",13,Ui.TEXT_3,false);Ui.padding(status,0,16,0,16);col.addView(status);
        grid=new CardGrid(ctx,7,112,160,12);grid.setLeftAligned(true);grid.setOnPick(host::openDetail);grid.setOnBindCard((card,s)->card.setOnHighlighted(()->com.hgtv.app.media.PlaybackWarmup.schedule(s.seriesId)));LinearLayout.LayoutParams gridLp=Ui.lp(-1,-2);gridLp.topMargin=Ui.dp(ctx,18);col.addView(grid,gridLp);
        more=Ui.action(ctx,"继续加载",-1,false);more.setOnClickListener(v->{if(categoryOptions==null)loadCategoryOptions();else load();});col.addView(more,Ui.lp(-1,Ui.dp(ctx,46)));
        scroll.getViewTreeObserver().addOnScrollChangedListener(this::requestMoreIfNear);
        return scroll;
    }
    private void addGroup(String label,String[] names,String[] ids,boolean boards){
        if("画风".equals(label)||"剧集画风".equals(label))return;
        if(!boards&&!java.util.Arrays.asList(ids).contains("")){String[] allNames=new String[names.length+1],allIds=new String[ids.length+1];allNames[0]="全部";allIds[0]="";System.arraycopy(names,0,allNames,1,names.length);System.arraycopy(ids,0,allIds,1,ids.length);names=allNames;ids=allIds;}
        LinearLayout line=Ui.row(ctx);String heading="榜单".equals(label)?"推荐榜单":"综合".equals(label)?"适合受众":label;TextView title=Ui.text(ctx,heading,14,Ui.TEXT_3,false);title.setSingleLine(true);title.setGravity(Gravity.CENTER_VERTICAL);line.addView(title,Ui.lp(Ui.dp(ctx,80),Ui.dp(ctx,30)));
        HorizontalScrollView strip=new HorizontalScrollView(ctx);strip.setHorizontalScrollBarEnabled(false);strip.setHorizontalFadingEdgeEnabled(false);strip.setOverScrollMode(View.OVER_SCROLL_NEVER);strip.setFocusable(false);
        LinearLayout row=Ui.row(ctx);Ui.padding(row,8,0,8,0);strip.addView(row);line.addView(strip,Ui.lp(0,-2,1));
        LinearLayout.LayoutParams lineLp=Ui.lp(-1,-2);lineLp.bottomMargin=Ui.dp(ctx,3);groups.addView(line,lineLp);
        for(int n=0;n<names.length;n++){
            final String id=ids[n];TextView chip=Ui.text(ctx,!boards&&id.isEmpty()?"全部":names[n],14,Ui.TEXT_2,false);chip.setSingleLine(true);chip.setFocusable(true);chip.setId(View.generateViewId());Ui.padding(chip,10,5,10,5);chip.setIncludeFontPadding(false);chip.setMinHeight(0);chip.setGravity(Gravity.CENTER);chip.setLineSpacing(0,1f);
            chips.put(chip,new String[]{boards?"board":"filter",id,label});
            chip.setOnFocusChangeListener((v,has)->{paint();if(has)strip.post(()->strip.smoothScrollTo(Math.max(0,chip.getLeft()-(strip.getWidth()-chip.getWidth())/2),0));});
            chip.setOnClickListener(v->{
                if(id.equals("体裁".equals(label)?contentType:boards?board:selected.optString(label)))return;
                if("体裁".equals(label)){contentType=id;board="";selectedReset();loadCategoryOptions();return;}
                if(boards){groups.setFocusableInTouchMode(true);groups.requestFocus();board=id;configure(null);
                    groups.post(()->{for(Map.Entry<TextView,String[]> entry:chips.entrySet()){String[] tag=entry.getValue();if("board".equals(tag[0])&&id.equals(tag[1])){entry.getKey().requestFocus();groups.setFocusableInTouchMode(false);groups.setFocusable(false);break;}}});
                }
                else{try{if(id.isEmpty())selected.remove(label);else selected.put(label,id);}catch(Exception ignored){}}
                paint();reset();
                row.post(()->{View next=row.getChildAt(row.indexOfChild(chip)+1);int right=next==null?chip.getRight():next.getRight();int left=strip.getScrollX();int margin=Ui.dp(ctx,8);if(chip.getLeft()<left+margin)strip.smoothScrollTo(Math.max(0,chip.getLeft()-margin),0);else if(right>left+strip.getWidth()-margin)strip.smoothScrollTo(Math.max(0,right-strip.getWidth()+margin),0);});
            });
            LinearLayout.LayoutParams lp=Ui.lp(-2,Ui.dp(ctx,32));lp.rightMargin=Ui.dp(ctx,8);row.addView(chip,lp);if(first==null)first=chip;if(id.equals(boards?board:selected.optString(label)))strip.post(()->strip.scrollTo(Math.max(0,chip.getLeft()-(strip.getWidth()-chip.getWidth())/2),0));
        }paint();
    }
    private void paint(){
        if(filterSummary!=null){String label="";for(int n=0;n<selectorRows.length();n++){JSONObject group=selectorRows.optJSONObject(n);JSONArray items=group==null?null:group.optJSONArray("items");for(int k=0;items!=null&&k<items.length();k++){JSONObject item=items.optJSONObject(k);if(item!=null&&!filter.isEmpty()&&filter.equals(item.optString("id")))label=item.optString("label");}}
            filterSummary.setVisibility(label.isEmpty()?View.GONE:View.VISIBLE);filterSummary.setText("当前题材："+label);}
        for(Map.Entry<TextView,String[]> e:chips.entrySet()){
        TextView v=e.getKey();String[] tag=e.getValue();boolean selected=tag[1].equals("体裁".equals(tag[2])?contentType:"board".equals(tag[0])?board:this.selected.optString(tag[2])),focus=v.hasFocus();
        if("filter".equals(tag[0])&&tag[1].isEmpty())selected=this.selected.optString(tag[2]).isEmpty();
        v.setBackground(Ui.roundStroke(ctx,focus?Ui.TINT_PRIMARY:selected?0x20c6ed92:0x00000000,8,focus?Ui.PRIMARY:0x00000000,focus?1.5f:1));
        v.setTextColor(focus||selected?Ui.PRIMARY:Ui.TEXT_2);
        v.setTypeface(null,focus||selected?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);
        v.setPaintFlags(v.getPaintFlags()&~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
    }}
    private void selectedReset(){java.util.List<String> keys=new java.util.ArrayList<>();java.util.Iterator<String> it=selected.keys();while(it.hasNext())keys.add(it.next());for(String key:keys)selected.remove(key);}
    private void loadCategoryOptions(){final String wanted=contentType;final int token=++generation;loading=false;App.io().execute(()->{try{JSONObject data=Browse.categoryOptions(wanted);host.runOnUiThread(()->{if(token!=generation||!wanted.equals(contentType))return;categoryOptions=data;configure(null);reset();});}catch(Exception e){host.runOnUiThread(()->{if(token!=generation)return;status.setText("分类加载失败");status.setVisibility(View.VISIBLE);more.setText("重试");});}});}
    private void configure(JSONObject selector){
        if(categoryOptions==null)return;try{groups.removeAllViews();chips.clear();first=null;
            JSONArray types=categoryOptions.getJSONArray("types"),boards=categoryOptions.getJSONArray("boards");
            JSONObject current=null;
            for(int n=0;n<boards.length();n++)if(board.equals(boards.getJSONObject(n).optString("id")))current=boards.getJSONObject(n);
            if(current==null&&boards.length()>0){current=boards.getJSONObject(0);board=current.getString("id");}
            addJsonGroup("榜单",boards,true);JSONArray rows=current==null?new JSONArray():current.getJSONArray("groups");selectorRows=rows;
            java.util.Set<String> valid=new java.util.HashSet<>();for(int r=0;r<rows.length();r++){JSONObject row=rows.getJSONObject(r);JSONArray opts=row.getJSONArray("items");String name=row.getString("name");for(int k=0;k<opts.length();k++)if(selected.optString(name).equals(opts.getJSONObject(k).getString("id")))valid.add(name);}
            java.util.List<String> stale=new java.util.ArrayList<>();java.util.Iterator<String> keys=selected.keys();while(keys.hasNext()){String name=keys.next();if(!valid.contains(name))stale.add(name);}for(String name:stale)selected.remove(name);
            for(int n=0;n<rows.length();n++){JSONObject group=rows.getJSONObject(n);addJsonGroup(group.getString("name"),group.getJSONArray("items"),false);}selectors=true;paint();
        }catch(Exception ignored){}
    }
    private void addJsonGroup(String name,JSONArray items,boolean boards)throws JSONException{String[] names=new String[items.length()],ids=new String[items.length()];for(int n=0;n<items.length();n++){JSONObject item=items.getJSONObject(n);names[n]=item.getString("label");ids[n]=item.getString("id");}addGroup(name,names,ids,boards);}
    private void reset(){emptyPages=0;generation++;cursor=0;session=UUID.randomUUID().toString();loading=false;hasMore=true;seen.clear();grid.setData(null);grid.showLoading();load();}
    private int emptyPages;
    private void requestMoreIfNear(){if(scroll.isShown() && started && !loading && hasMore && emptyPages<3 && ((View)scroll.getChildAt(0)).getHeight()-scroll.getScrollY()-scroll.getHeight()<Math.max(scroll.getHeight(),Ui.dp(ctx,650)))load();}
    private void load(){if(loading||!hasMore)return;loading=true;final int token=generation,offset=cursor;final String b=board,f=filter,s=session,t=contentType,selection=selected.toString();status.setText("");status.setVisibility(View.GONE);more.setText("•••");grid.showTailLoading();
        App.io().execute(()->{try{Browse.Result result=Browse.categoryPage(t,b,new JSONObject(selection),offset,s);host.runOnUiThread(()->{
            if(token!=generation)return;loading=false;cursor=result.next;session=result.session;hasMore=result.more;
            List<Series> add=new ArrayList<>();for(Series item:result.items)if(seen.add(item.seriesId))add.add(item);grid.append(add);grid.setLeftNavId(nav);
            emptyPages=add.isEmpty()?emptyPages+1:0;
            status.setText(seen.isEmpty()?"暂无相关短剧":"");status.setVisibility(seen.isEmpty()?View.VISIBLE:View.GONE);more.setText(hasMore?"加载更多":"没有更多了");
            scroll.post(this::requestMoreIfNear);
        });}catch(Exception error){host.runOnUiThread(()->{if(token!=generation)return;loading=false;grid.finishLoading();status.setVisibility(View.VISIBLE);status.setText("加载失败");more.setText("重试");});}});
    }
    @Override public void onShow(){if(!started){started=true;grid.showLoading();loadCategoryOptions();}}
    @Override public void focusFirst(){if(first!=null)first.requestFocus();}
    @Override public void bindNav(int id){nav=id;first.setNextFocusLeftId(id);grid.setLeftNavId(id);if(groupTabs!=null&&groupTabs.getChildCount()>0)groupTabs.getChildAt(0).setNextFocusLeftId(id);if(optionHost!=null)for(int n=0;n<optionHost.getChildCount();n++){LinearLayout row=(LinearLayout)optionHost.getChildAt(n);if(row.getChildCount()>0)row.getChildAt(0).setNextFocusLeftId(id);}}
}
