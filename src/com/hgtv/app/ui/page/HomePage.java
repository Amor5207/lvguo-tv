package com.hgtv.app.ui.page;

import android.view.*;
import android.widget.*;
import com.hgtv.app.App;
import com.hgtv.app.model.Series;
import com.hgtv.app.net.*;
import com.hgtv.app.ui.*;
import com.hgtv.app.ui.widget.*;
import java.util.*;

/** One continuous recommendation grid; preserve it when returning from details. */
public class HomePage extends Page {
    private ScrollView scroll;
    private LinearLayout column;
    private LinearLayout resumeHost;
    private CardGrid grid;
    private TextView more,status;
    private View first;
    private boolean loading,started,hasMore=true;
    private static final String[] FEEDS={"","cate_36","cate_37","cate_395","cate_739","cate_308","cate_165","cate_760","cate_1024","cate_1040","cate_19","cate_466","cate_43","cate_273","cate_127","cate_390","cate_303","cate_1038","cate_1049"};
    private int cursor,navId,generation,feedIndex,feedsVisited;
    private String session=UUID.randomUUID().toString();
    private final Set<String> seen=new HashSet<>();
    @Override protected View build(){
        scroll=new ScrollView(ctx);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);scroll.setClipToPadding(false);scroll.setFocusable(false);
        column=Ui.column(ctx);column.setPadding(Ui.dp(ctx,32),Ui.dp(ctx,28),Ui.dp(ctx,32),Ui.dp(ctx,36));column.setClipChildren(false);
        scroll.addView(column,new ScrollView.LayoutParams(-1,-2));
        LinearLayout head=Ui.row(ctx);
        head.addView(Ui.text(ctx,"今日推荐",26,Ui.TEXT,true),Ui.lp(0,-2,1));
        TextView search=Ui.action(ctx,"找剧",IconView.SEARCH,false);search.setOnClickListener(v->{host.switchTab(RootActivity.TAB_SEARCH,false);host.focusNav(RootActivity.TAB_SEARCH);});
        head.addView(search,Ui.lp(-2,Ui.dp(ctx,46)));first=search;
        TextView phone=Ui.action(ctx,"手机选剧",IconView.PHONE,false);phone.setOnClickListener(v->com.hgtv.app.remote.Pairing.show(host));
        LinearLayout.LayoutParams pl=Ui.lp(-2,Ui.dp(ctx,46));pl.leftMargin=Ui.dp(ctx,10);head.addView(phone,pl);
        TextView refresh=Ui.action(ctx,"换一批",IconView.REFRESH,false);refresh.setOnClickListener(v->reset());
        LinearLayout.LayoutParams rp=Ui.lp(-2,Ui.dp(ctx,46));rp.leftMargin=Ui.dp(ctx,10);head.addView(refresh,rp);
        LinearLayout.LayoutParams headerLp=Ui.lp(-1,-2);headerLp.bottomMargin=Ui.dp(ctx,18);column.addView(head,headerLp);
        grid=new CardGrid(ctx,7,112,160,12);grid.setLeftAligned(true);grid.setOnPick(host::openDetail);grid.setOnBindCard((card,s)->card.setOnHighlighted(()->com.hgtv.app.media.PlaybackWarmup.schedule(s.seriesId)));
        resumeHost=Ui.column(ctx);column.addView(resumeHost,Ui.lp(-1,-2));
        LinearLayout browse=Ui.row(ctx);
        TextView all=Ui.action(ctx,"发现更多题材",IconView.FILTER,false);all.setOnClickListener(v->{host.switchTab(RootActivity.TAB_FILTER,false);host.focusNav(RootActivity.TAB_FILTER);});
        browse.addView(Ui.text(ctx,"为你精选",16,Ui.TEXT,true),Ui.lp(0,-2,1));browse.addView(all,Ui.lp(-2,Ui.dp(ctx,40)));
        LinearLayout.LayoutParams browseLp=Ui.lp(-1,Ui.dp(ctx,44));browseLp.bottomMargin=Ui.dp(ctx,12);column.addView(browse,browseLp);
        column.addView(grid,Ui.lp(-1,-2));
        status=Ui.text(ctx,"",12,Ui.TEXT_3,false);Ui.padding(status,0,12,0,12);column.addView(status,Ui.lp(-1,-2));
        more=Ui.action(ctx,"继续发现",-1,false);more.setOnClickListener(v->loadMore());column.addView(more,Ui.lp(-1,Ui.dp(ctx,46)));
        scroll.getViewTreeObserver().addOnScrollChangedListener(()->{
            requestMoreIfNear();
        });
        return scroll;
    }
    private void reset(){emptyPages=0;generation++;cursor=0;feedIndex=(feedIndex+1)%FEEDS.length;feedsVisited=0;session=UUID.randomUUID().toString();seen.clear();grid.setData(null);grid.showLoading();loading=false;hasMore=true;loadMore();}
    private int emptyPages;
    private void requestMoreIfNear(){
        if(scroll.isShown() && started && !loading && hasMore && emptyPages<3 && column.getHeight()-scroll.getScrollY()-scroll.getHeight()<Math.max(scroll.getHeight(),Ui.dp(ctx,650)))loadMore();
    }
    @Override public void onShow(){
        renderContinue();
        if(started)return;started=true;
        int visit=App.prefs().getInt("home_feed_visit",0);
        feedIndex=Math.max(0,visit)%FEEDS.length;
        App.prefs().edit().putInt("home_feed_visit",(visit+1)%FEEDS.length).apply();
        grid.showLoading();loadMore();
    }
    private volatile int resumeGeneration;
    @Override public void onHide(){resumeGeneration++;}
    private void renderContinue(){
        final int token=++resumeGeneration;
        final List<com.hgtv.app.store.Store.Item> history=com.hgtv.app.store.Store.history();
        if(history.isEmpty()){resumeHost.removeAllViews();resumeHost.setVisibility(View.GONE);return;}
        App.io().execute(()->{
            history.sort((a,b)->Long.compare(b.time,a.time));
            Map<String,Integer> counts=new HashMap<>();List<Series> catalog=Catalog.cached();
            if(catalog!=null)for(Series item:catalog)if(item.episodeCount>0)counts.put(item.seriesId,item.episodeCount);
            com.hgtv.app.store.Store.Item latest=null;int total=0;
            for(com.hgtv.app.store.Store.Item item:history){
                if(token!=resumeGeneration||Thread.currentThread().isInterrupted())return;
                if(item.seriesId==null||item.seriesId.isEmpty()||item.lastEpisode<=0)continue;
                Repo.Detail detail=Repo.cachedDetail(item.seriesId);
                int count=detail==null?(counts.containsKey(item.seriesId)?counts.get(item.seriesId):0):Math.max(detail.episodeCount,detail.episodes.size());
                if(count<=0)try{detail=Repo.detail(item.seriesId);if(detail!=null)count=Math.max(detail.episodeCount,detail.episodes.size());}catch(Exception ignored){}
                if(count>item.lastEpisode){latest=item;total=count;break;}
            }
            final com.hgtv.app.store.Store.Item item=latest;final int count=total;
            host.runOnUiThread(()->{if(token!=resumeGeneration||host.isFinishing())return;showContinue(item,count);});
        });
    }
    private void showContinue(com.hgtv.app.store.Store.Item item,int total){
        boolean hadFocus=resumeHost.hasFocus();resumeHost.removeAllViews();
        if(item==null){resumeHost.setVisibility(View.GONE);return;}
        resumeHost.setVisibility(View.VISIBLE);Ui.padding(resumeHost,0,0,0,18);
        final String cover=com.hgtv.app.store.Store.bestCover(item.seriesId,item.cover);
        FrameLayout card=new FrameLayout(ctx);card.setFocusable(true);card.setClickable(true);card.setId(View.generateViewId());card.setNextFocusLeftId(navId);
        card.setBackground(Ui.round(ctx,Ui.SURFACE,16));card.setClipToOutline(true);
        ImageView coverBackground=new ImageView(ctx);coverBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);coverBackground.setAlpha(.78f);card.addView(coverBackground,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(ctx);shade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xF4101313,0xF0101313,0xEC101313}));card.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=Ui.row(ctx);content.setGravity(Gravity.CENTER_VERTICAL);Ui.padding(content,20,10,18,10);card.addView(content,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout meta=Ui.column(ctx);meta.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams ml=Ui.lp(0,-2,1);ml.rightMargin=Ui.dp(ctx,28);content.addView(meta,ml);
        TextView eyebrow=Ui.text(ctx,"继续观看",12,Ui.PRIMARY,true);meta.addView(eyebrow);
        TextView title=Ui.text(ctx,item.title,18,Ui.TEXT,true);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams tl=Ui.lp(-1,-2);tl.topMargin=Ui.dp(ctx,4);meta.addView(title,tl);
        if(item.durationMs>0){android.widget.ProgressBar playback=new android.widget.ProgressBar(ctx,null,android.R.attr.progressBarStyleHorizontal);playback.setMax(1000);playback.setProgress((int)Math.min(1000L,(long)item.positionMs*1000L/item.durationMs));if(android.os.Build.VERSION.SDK_INT>=21){playback.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));playback.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x6694A1AD));}LinearLayout.LayoutParams barLp=Ui.lp(Ui.dp(ctx,220),Ui.dp(ctx,3));barLp.topMargin=Ui.dp(ctx,8);meta.addView(playback,barLp);}
        LinearLayout progress=Ui.column(ctx);progress.setGravity(Gravity.CENTER_VERTICAL);TextView episode=Ui.text(ctx,"第 "+item.lastEpisode+" 集",15,Ui.TEXT,true);progress.addView(episode);TextView remaining=Ui.text(ctx,"还剩 "+(total-item.lastEpisode)+" 集",12,Ui.TEXT_2,false);LinearLayout.LayoutParams remainingLp=Ui.lp(-2,-2);remainingLp.topMargin=Ui.dp(ctx,3);progress.addView(remaining,remainingLp);LinearLayout.LayoutParams progressLp=Ui.lp(-2,-2);progressLp.rightMargin=Ui.dp(ctx,16);content.addView(progress,progressLp);
        FrameLayout play=new FrameLayout(ctx);play.setBackground(Ui.round(ctx,Ui.PRIMARY,24));IconView glyph=new IconView(ctx,IconView.PLAY,21);glyph.setColor(Ui.BG);play.addView(glyph,new FrameLayout.LayoutParams(Ui.dp(ctx,22),Ui.dp(ctx,22),Gravity.CENTER));content.addView(play,Ui.lp(Ui.dp(ctx,48),Ui.dp(ctx,48)));
        card.setContentDescription("继续观看，"+item.title+"，第"+item.lastEpisode+"集，还剩"+(total-item.lastEpisode)+"集");
        card.setForeground(Ui.roundStroke(ctx,0,16,0x33FFFFFF,1));
        card.setOnFocusChangeListener((v,focus)->{card.setForeground(Ui.roundStroke(ctx,0,16,focus?Ui.PRIMARY:0x33FFFFFF,focus?3:1));if(focus)host.setCoverBackground(cover);});
        card.setOnClickListener(v->{android.content.Intent intent=new android.content.Intent(host,com.hgtv.app.PlayerActivity.class);intent.putExtra("series_id",item.seriesId);intent.putExtra("title",item.title);intent.putExtra("cover",cover);intent.putExtra("index",item.episodeIndex);intent.putExtra("position_ms",item.positionMs);host.startActivity(intent);});
        resumeHost.addView(card,Ui.lp(-1,Ui.dp(ctx,102)));com.hgtv.app.img.ImageLoader.load(cover,coverBackground,960,240);if(hadFocus)card.requestFocus();
    }
    private void loadMore(){
        if(loading || !hasMore)return;loading=true;final int token=generation,offset=cursor;final String sid=session,filter=FEEDS[feedIndex];
        more.setText("•••");status.setText("");grid.showTailLoading();
        App.io().execute(()->{try{
            Browse.Result result=Browse.page(Repo.BOARD_RECOMMEND,filter,offset,sid);
            host.runOnUiThread(()->{if(token!=generation)return;loading=false;cursor=result.next;session=result.session;hasMore=result.more;
                if(!hasMore && ++feedsVisited<FEEDS.length){feedIndex=(feedIndex+1)%FEEDS.length;cursor=0;session=UUID.randomUUID().toString();hasMore=true;}
                List<Series> add=new ArrayList<>();for(Series s:result.items)if(seen.add(s.seriesId))add.add(s);
                emptyPages=add.isEmpty()?emptyPages+1:0;
                grid.append(add);grid.setLeftNavId(navId);status.setText("");status.setVisibility(View.GONE);more.setText(hasMore?"加载更多":"换一批");
                more.setOnClickListener(v->{if(hasMore)loadMore();else reset();});
                scroll.post(this::requestMoreIfNear);
            });
        }catch(Exception e){host.runOnUiThread(()->{if(token!=generation)return;loading=false;grid.finishLoading();status.setVisibility(View.VISIBLE);status.setText("加载失败");more.setText("重试");});}});
    }
    @Override public void focusFirst(){if(resumeHost.getVisibility()==View.VISIBLE&&resumeHost.getChildCount()>0)resumeHost.getChildAt(0).requestFocus();else if(grid.count()>0)grid.focusFirst();else if(first!=null)first.requestFocus();}
    @Override public void bindNav(int id){navId=id;grid.setLeftNavId(id);first.setNextFocusLeftId(id);}
}
