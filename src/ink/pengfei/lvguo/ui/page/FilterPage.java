package ink.pengfei.lvguo.ui.page;
import android.view.*;
import android.widget.*;
import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.net.*;
import ink.pengfei.lvguo.ui.*;
import ink.pengfei.lvguo.ui.widget.CardGrid;
import ink.pengfei.lvguo.ui.widget.FocusScrollView;
import org.json.*;
import java.util.*;

/** Actual server selectors and cursors; changing a condition starts a new result generation. */
public final class FilterPage extends Page {
    /** true=榜单页（只有内容类型 + 榜单页签）；false=分类页（内容类型 + 题材筛选，不出现榜单）。 */
    private final boolean rankMode;
    public FilterPage(boolean rank){rankMode=rank;}
    private ScrollView scroll;private LinearLayout groups;private CardGrid grid;
    private TextView status,more,filterSummary;private View first;
    private String board=Repo.BOARD_RECOMMEND,filter="",session=UUID.randomUUID().toString();
    private int cursor,generation,nav;private boolean loading,started,hasMore=true,selectors;
    private final Set<String> seen=new HashSet<>();
    private final Map<TextView,String[]> chips=new LinkedHashMap<>();
    private JSONObject categoryOptions;private String contentType="all";private final JSONObject selected=new JSONObject();
    private String selectorShape="";private JSONArray selectorRows=new JSONArray();private int activeGroup;private LinearLayout groupTabs,optionHost;
    @Override public boolean onBack(){if(scroll!=null&&scroll.getScrollY()>0){if(first!=null)first.requestFocus();scroll.post(()->scroll.smoothScrollTo(0,0));return true;}return false;}
    @Override protected View build(){
        scroll=new FocusScrollView(ctx);scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);scroll.setFocusable(false);
        LinearLayout col=Ui.column(ctx);Ui.padding(col,48,24,48,32);scroll.addView(col);
        // 标题区压矮：去掉空的副标题占位，内容离顶部更近
        TextView heading=Ui.text(ctx,rankMode?"榜单":"分类",26,Ui.TEXT,true);LinearLayout.LayoutParams hl=Ui.lp(-2,-2);hl.bottomMargin=Ui.dp(ctx,rankMode?6:10);col.addView(heading,hl);
        groups=Ui.column(ctx);groups.setClipChildren(false);groups.setClipToPadding(false);col.setClipChildren(false);col.setClipToPadding(false);col.addView(groups,Ui.lp(-1,-2));
        if(rankMode)addGroup("榜单",new String[]{"推荐","热播","新剧"},new String[]{Repo.BOARD_RECOMMEND,Repo.BOARD_HOT,Repo.BOARD_NEW},true);
        groups.addView(new ink.pengfei.lvguo.ui.widget.SkeletonView(ctx,ink.pengfei.lvguo.ui.widget.SkeletonView.FILTERS,4,0,4),Ui.lp(-1,-2));
        status=Ui.text(ctx,"",13,Ui.TEXT_3,false);Ui.padding(status,0,16,0,16);col.addView(status);
        grid=new CardGrid(ctx,Ui.GRID_CARD_W,Ui.GRID_CARD_H,18);grid.setLeftAligned(true);grid.setOnPick(host::openDetail);grid.setOnBindCard((card,s)->card.setOnHighlighted(()->ink.pengfei.lvguo.media.PlaybackWarmup.schedule(s.seriesId)));LinearLayout.LayoutParams gridLp=Ui.lp(-1,-2);gridLp.topMargin=Ui.dp(ctx,18);col.addView(grid,gridLp);
        more=Ui.action(ctx,"继续加载",-1,false);more.setFocusable(false);more.setVisibility(View.GONE);grid.setOnNearEnd(this::load);more.setOnClickListener(v->{if(categoryOptions==null)loadCategoryOptions();else load();});col.addView(more,Ui.lp(-1,Ui.dp(ctx,46)));
        scroll.getViewTreeObserver().addOnScrollChangedListener(this::requestMoreIfNear);
        return scroll;
    }
    private void addGroup(String label,String[] names,String[] ids,boolean boards){
        if("画风".equals(label)||"剧集画风".equals(label))return;
        if(!boards&&!"体裁".equals(label)&&!java.util.Arrays.asList(ids).contains("")){String[] allNames=new String[names.length+1],allIds=new String[ids.length+1];allNames[0]="全部";allIds[0]="";System.arraycopy(names,0,allNames,1,names.length);System.arraycopy(ids,0,allIds,1,ids.length);names=allNames;ids=allIds;}
        LinearLayout line=Ui.row(ctx);String heading=shortLabel(label);boolean bare=boards||"体裁".equals(label);TextView title=Ui.text(ctx,bare?"":heading,15,Ui.TEXT_3,false);title.setSingleLine(true);title.setGravity(Gravity.CENTER_VERTICAL);line.addView(title,Ui.lp(Ui.dp(ctx,bare?0:52),Ui.dp(ctx,boards?44:36)));
        HorizontalScrollView strip=new HorizontalScrollView(ctx);strip.setHorizontalScrollBarEnabled(false);strip.setHorizontalFadingEdgeEnabled(false);strip.setOverScrollMode(View.OVER_SCROLL_NEVER);strip.setFocusable(false);
        LinearLayout row=Ui.row(ctx);Ui.padding(row,boards?0:4,boards?6:1,8,boards?6:1);row.setClipChildren(false);row.setClipToPadding(false);strip.setClipChildren(false);strip.setClipToPadding(false);strip.addView(row);line.addView(strip,Ui.lp(0,-2,1));
        LinearLayout.LayoutParams lineLp=Ui.lp(-1,-2);lineLp.bottomMargin=Ui.dp(ctx,boards?10:0);
        // 榜单页签：第一项文字与左侧标题对齐（把页签自身的内边距让到页面留白里）
        if(boards)lineLp.leftMargin=-Ui.dp(ctx,14);else if("体裁".equals(label)){lineLp.leftMargin=-Ui.dp(ctx,14);lineLp.bottomMargin=Ui.dp(ctx,4);}
        groups.addView(line,lineLp);
        for(int n=0;n<names.length;n++){
            final String id=ids[n];TextView chip=Ui.text(ctx,!boards&&id.isEmpty()?"全部":names[n],boards?18:15,Ui.TEXT_2,false);chip.setSingleLine(true);chip.setFocusable(true);chip.setId(View.generateViewId());Ui.padding(chip,boards?14:10,0,boards?14:10,0);chip.setIncludeFontPadding(false);chip.setMinHeight(0);chip.setGravity(Gravity.CENTER);chip.setLineSpacing(0,1f);
            chips.put(chip,new String[]{boards?"board":"filter",id,label});
            chip.setOnFocusChangeListener((v,has)->{paint();if(has){strip.post(()->strip.smoothScrollTo(Math.max(0,chip.getLeft()-(strip.getWidth()-chip.getWidth())/2),0));
                // 回到最上面一行筛选时把页面滚回顶部，标题不被遮住
                if(groups.indexOfChild(line)==0)scroll.postDelayed(()->{if(chip.hasFocus())scroll.smoothScrollTo(0,0);},120);}});
            chip.setOnClickListener(v->{
                if(id.equals("体裁".equals(label)?contentType:boards?board:selected.optString(label)))return;
                if("体裁".equals(label)){contentType=id;board="";selectedReset();refocusType=true;paint();loadCategoryOptions();return;}
                if(boards){groups.setFocusableInTouchMode(true);groups.requestFocus();board=id;configure(null);
                    groups.post(()->{for(Map.Entry<TextView,String[]> entry:chips.entrySet()){String[] tag=entry.getValue();if("board".equals(tag[0])&&id.equals(tag[1])){entry.getKey().requestFocus();groups.setFocusableInTouchMode(false);groups.setFocusable(false);break;}}});
                }
                else{try{if(id.isEmpty())selected.remove(label);else selected.put(label,id);}catch(Exception ignored){}}
                paint();reset();
                row.post(()->{View next=row.getChildAt(row.indexOfChild(chip)+1);int right=next==null?chip.getRight():next.getRight();int left=strip.getScrollX();int margin=Ui.dp(ctx,8);if(chip.getLeft()<left+margin)strip.smoothScrollTo(Math.max(0,chip.getLeft()-margin),0);else if(right>left+strip.getWidth()-margin)strip.smoothScrollTo(Math.max(0,right-strip.getWidth()+margin),0);});
            });
            LinearLayout.LayoutParams lp=Ui.lp(-2,Ui.dp(ctx,boards?44:32));lp.rightMargin=Ui.dp(ctx,boards?14:4);row.addView(chip,lp);if(first==null)first=chip;if(id.equals(boards?board:selected.optString(label)))strip.post(()->strip.smoothScrollTo(Math.max(0,chip.getLeft()-(strip.getWidth()-chip.getWidth())/2),0));
        }paint();
    }
    /** 筛选项标题统一两个字（和手机端一致）。 */
    private static String shortLabel(String label){
        if(label==null)return "";
        switch(label){case "综合":return "受众";case "主题情节":case "主题":return "题材";case "角色设定":case "设定":return "人设";case "时代背景":return "背景";case "体裁":return "类型";}
        return label.length()>2?label.substring(label.length()-2):label;
    }
    private void paint(){
        if(filterSummary!=null){String label="";for(int n=0;n<selectorRows.length();n++){JSONObject group=selectorRows.optJSONObject(n);JSONArray items=group==null?null:group.optJSONArray("items");for(int k=0;items!=null&&k<items.length();k++){JSONObject item=items.optJSONObject(k);if(item!=null&&!filter.isEmpty()&&filter.equals(item.optString("id")))label=item.optString("label");}}
            filterSummary.setVisibility(label.isEmpty()?View.GONE:View.VISIBLE);filterSummary.setText("当前题材："+label);}
        for(Map.Entry<TextView,String[]> e:chips.entrySet()){
        TextView v=e.getKey();String[] tag=e.getValue();boolean selected=tag[1].equals("体裁".equals(tag[2])?contentType:"board".equals(tag[0])?board:this.selected.optString(tag[2])),focus=v.hasFocus();
        if("filter".equals(tag[0])&&tag[1].isEmpty())selected=this.selected.optString(tag[2]).isEmpty();
        // 榜单是一级页签：选中=白色粗体+品牌色短下划线；筛选项选中=品牌色淡底胶囊。
        // 未选中只留文字（避免满屏灰块），聚焦一律实心高亮。
        boolean isBoard="board".equals(tag[0]);
        if(focus){v.setBackground(Ui.pill(ctx,Ui.FOCUS_FILL));v.setTextColor(Ui.ON_FOCUS);}
        else if(selected&&isBoard){v.setBackground(underline());v.setTextColor(Ui.TEXT);}
        else if(selected){v.setBackground(Ui.pill(ctx,Ui.TINT_PRIMARY));v.setTextColor(Ui.ACCENT_TEXT);}
        else{v.setBackground(null);v.setTextColor(Ui.TEXT_2);}
        v.setTypeface(null,focus||selected?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);
        v.setPaintFlags(v.getPaintFlags()&~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
    }}
    private android.graphics.drawable.Drawable underline(){
        android.graphics.drawable.GradientDrawable bar=new android.graphics.drawable.GradientDrawable();bar.setColor(Ui.PRIMARY);bar.setCornerRadius(Ui.dp(ctx,2));
        android.graphics.drawable.LayerDrawable d=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{bar});
        d.setLayerGravity(0,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);d.setLayerWidth(0,Ui.dp(ctx,22));d.setLayerHeight(0,Ui.dp(ctx,3));d.setLayerInsetBottom(0,Ui.dp(ctx,2));
        return d;
    }
    private void selectedReset(){java.util.List<String> keys=new java.util.ArrayList<>();java.util.Iterator<String> it=selected.keys();while(it.hasNext())keys.add(it.next());for(String key:keys)selected.remove(key);}
    private void loadCategoryOptions(){final String wanted=contentType;final int token=++generation;loading=false;App.io().execute(()->{try{JSONObject data=Browse.categoryOptions(wanted);host.runOnUiThread(()->{if(token!=generation||!wanted.equals(contentType))return;categoryOptions=data;configure(null);reset();});}catch(Exception e){host.runOnUiThread(()->{if(token!=generation)return;status.setText("分类加载失败");status.setVisibility(View.VISIBLE);more.setText("重试");more.setVisibility(View.VISIBLE);more.setFocusable(true);});}});}
    private void configure(JSONObject selector){
        if(categoryOptions==null)return;try{groups.removeAllViews();chips.clear();first=null;
            JSONArray types=categoryOptions.getJSONArray("types"),boards=categoryOptions.getJSONArray("boards");
            // 榜单页选了类型后：该类型下的「推荐榜」上游不按类型过滤（返回的仍是全站榜），不展示
            if(rankMode&&!"all".equals(contentType)){JSONArray kept=new JSONArray();for(int n=0;n<boards.length();n++){String id=boards.getJSONObject(n).optString("id");if(!id.endsWith("_hot_sc")&&!id.endsWith("_hot_rank"))kept.put(boards.getJSONObject(n));}if(kept.length()>0)boards=kept;}
            JSONObject current=null;
            for(int n=0;n<boards.length();n++)if(board.equals(boards.getJSONObject(n).optString("id")))current=boards.getJSONObject(n);
            if(current==null&&boards.length()>0){current=boards.getJSONObject(0);board=current.getString("id");}
            // 类型（全部 / 漫剧 / 真人剧 / AI剧）只在榜单页，且不带行标题；分类页不放
            if(rankMode&&types.length()>1)addJsonGroup("体裁",types,false);
            // 内容类型（全部/真人剧/漫剧/AI剧）两页都有；榜单页签只在榜单页，题材筛选只在分类页
            // 榜单页只有榜单页签（不再按真人/漫剧/AI 区分）；类型筛选只在分类页
            // 分类页不再放「类型」（全部/真人/漫剧/AI）这一行，和手机端一致
            if(rankMode)addJsonGroup("榜单",boards,true);
            JSONArray rows=current==null||rankMode?new JSONArray():current.getJSONArray("groups");selectorRows=rows;
            java.util.Set<String> valid=new java.util.HashSet<>();for(int r=0;r<rows.length();r++){JSONObject row=rows.getJSONObject(r);JSONArray opts=row.getJSONArray("items");String name=row.getString("name");for(int k=0;k<opts.length();k++)if(selected.optString(name).equals(opts.getJSONObject(k).getString("id")))valid.add(name);}
            java.util.List<String> stale=new java.util.ArrayList<>();java.util.Iterator<String> keys=selected.keys();while(keys.hasNext()){String name=keys.next();if(!valid.contains(name))stale.add(name);}for(String name:stale)selected.remove(name);
            for(int n=0;n<rows.length();n++){JSONObject group=rows.getJSONObject(n);addJsonGroup(group.getString("name"),group.getJSONArray("items"),false);}selectors=true;paint();if(nav!=0)bindNav(nav);
            if(refocusType){refocusType=false;for(Map.Entry<TextView,String[]> entry:chips.entrySet()){String[] tag=entry.getValue();if("体裁".equals(tag[2])&&contentType.equals(tag[1])){final TextView chip=entry.getKey();chip.post(chip::requestFocus);break;}}}
        }catch(Exception ignored){}
    }
    private void addJsonGroup(String name,JSONArray items,boolean boards)throws JSONException{String[] names=new String[items.length()],ids=new String[items.length()];for(int n=0;n<items.length();n++){JSONObject item=items.getJSONObject(n);names[n]=item.getString("label");ids[n]=item.getString("id");}addGroup(name,names,ids,boards);}
    private void reset(){emptyPages=0;generation++;cursor=0;session=UUID.randomUUID().toString();loading=false;hasMore=true;seen.clear();grid.setData(null);grid.showLoading();load();}
    private int emptyPages;private boolean refocusType;
    private void requestMoreIfNear(){if(scroll.isShown() && started && !loading && hasMore && emptyPages<3 && ((View)scroll.getChildAt(0)).getHeight()-scroll.getScrollY()-scroll.getHeight()<Math.max(scroll.getHeight(),Ui.dp(ctx,650)))load();}
    private void load(){if(loading||!hasMore)return;loading=true;final int token=generation,offset=cursor;final String b=board,f=filter,s=session,t=contentType,selection=selected.toString();status.setText("");status.setVisibility(View.GONE);more.setText("•••");grid.showTailLoading();
        App.io().execute(()->{try{Browse.Result result=Browse.categoryPage(t,b,new JSONObject(selection),offset,s);host.runOnUiThread(()->{
            if(token!=generation)return;loading=false;cursor=result.next;session=result.session;hasMore=result.more;
            List<Series> add=new ArrayList<>();for(Series item:result.items)if(seen.add(item.seriesId))add.add(item);grid.append(add);grid.setLeftNavId(nav);
            emptyPages=add.isEmpty()?emptyPages+1:0;
            status.setText(seen.isEmpty()?"暂无相关短剧":"");status.setVisibility(seen.isEmpty()?View.VISIBLE:View.GONE);more.setText(hasMore?"加载更多":"没有更多了");more.setVisibility(View.GONE);
            scroll.post(this::requestMoreIfNear);
        });}catch(Exception error){host.runOnUiThread(()->{if(token!=generation)return;loading=false;grid.finishLoading();status.setVisibility(View.VISIBLE);status.setText("加载失败");more.setText("重试");more.setVisibility(View.VISIBLE);more.setFocusable(true);});}});
    }
    @Override public void onShow(){if(!started){started=true;grid.showLoading();loadCategoryOptions();}}
    @Override public void focusFirst(){if(first!=null)first.requestFocus();}
    @Override public void bindNav(int id){nav=id;if(first!=null)first.setNextFocusLeftId(id);grid.setLeftNavId(id);if(groupTabs!=null&&groupTabs.getChildCount()>0)groupTabs.getChildAt(0).setNextFocusLeftId(id);if(optionHost!=null)for(int n=0;n<optionHost.getChildCount();n++){LinearLayout row=(LinearLayout)optionHost.getChildAt(n);if(row.getChildCount()>0)row.getChildAt(0).setNextFocusLeftId(id);}}
}
