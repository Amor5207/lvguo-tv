package com.hgtv.app.ui.page;

import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hgtv.app.App;
import com.hgtv.app.model.Series;
import com.hgtv.app.net.Catalog;
import com.hgtv.app.net.Browse;
import com.hgtv.app.net.OnlineSearch;
import com.hgtv.app.net.HgApi;
import com.hgtv.app.net.Repo;
import com.hgtv.app.ui.IconView;
import com.hgtv.app.ui.Page;
import com.hgtv.app.ui.Ui;
import com.hgtv.app.ui.widget.CardGrid;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Native TV search with server pagination and preserved results while returning from details. */
public class SearchPage extends Page {

    private static final String[] FALLBACK_HOT = {
            "总裁", "都市", "甜宠", "逆袭", "战神", "古装", "悬疑", "重生",
            "穿越", "闪婚", "萌宝", "大女主", "职场", "家庭", "喜剧", "年代", "马甲", "神医", "亲情", "青梅竹马", "赘婿", "仙侠", "破镜重圆", "打脸虐渣"};

    private ScrollView mScroll;
    private EditText mInput;
    private TextView mHotRotate;
    private CardGrid mGrid;
    private TextView mResultTitle, mHint, searchMore;
    private int searchPage=1, searchTotal;
    private boolean searchBusy, searchHasMore, searchFailed;
    private final List<Series> searchItems=new ArrayList<>();
    private final java.util.Set<String> searchIds=new java.util.HashSet<>();
    private LinearLayout mHotHost, mHotHead;
    private LinearLayout mResultBox;

    private String mQuery = "";
    private int searchGeneration;
    private int mNavId;
    private List<Series> mAll;
    private boolean mHotLoaded;
    private int hotOffset;
    private List<String> hotWords=new ArrayList<>();
    private final java.util.Map<String,Series> hotSeries=new java.util.LinkedHashMap<>();

    @Override
    protected View build() {
        FrameLayout root = new FrameLayout(ctx);
        View pageShade=new View(ctx);
        pageShade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x20101313,0x40101313,0x70101313}));
        root.addView(pageShade,new FrameLayout.LayoutParams(-1,-1));

        mScroll = new ScrollView(ctx);
        mScroll.setVerticalScrollBarEnabled(false);
        mScroll.setFillViewport(true);
        mScroll.setClipChildren(false);

        LinearLayout col = Ui.column(ctx);
        col.setPadding(Ui.dp(ctx, 32), Ui.dp(ctx, 28), Ui.dp(ctx, 32), Ui.dp(ctx, 40));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        mScroll.addView(col, new FrameLayout.LayoutParams(-1, -2));
        root.addView(mScroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header=Ui.row(ctx);
        LinearLayout titles=Ui.column(ctx);
        titles.addView(Ui.text(ctx,"搜索",26,Ui.TEXT,true),Ui.lp(-1,-2));
        TextView sub=Ui.text(ctx,"",13,Ui.TEXT_4,false);
        LinearLayout.LayoutParams subLp=Ui.lp(-1,-2);subLp.topMargin=Ui.dp(ctx,8);sub.setVisibility(View.GONE);titles.addView(sub,subLp);
        header.addView(titles,Ui.lp(0,-2,1));
        TextView phone=Ui.action(ctx,"手机选剧",IconView.PHONE,false);
        phone.setOnClickListener(v->com.hgtv.app.remote.Pairing.show(host));
        LinearLayout.LayoutParams phoneLp=Ui.lp(-2,Ui.dp(ctx,46));phoneLp.leftMargin=Ui.dp(ctx,18);header.addView(phone,phoneLp);
        col.addView(header,Ui.lp(-1,-2));

        // ---- 搜索框
        LinearLayout box = Ui.row(ctx);
        box.setBackground(Ui.roundStroke(ctx, 0xC31B2020, 14, 0x30FFFFFF, 1.1f));
        box.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 13), Ui.dp(ctx, 22), Ui.dp(ctx, 13));
        IconView mag = new IconView(ctx, IconView.SEARCH, 20);
        mag.setColor(Ui.TEXT_3);
        box.addView(mag);
        mInput = new EditText(ctx);
        mInput.setSingleLine(true);
        mInput.setTextSize(17);
        mInput.setTextColor(Ui.TEXT);
        mInput.setHintTextColor(Ui.TEXT_4);
        mInput.setHint("输入剧名…");
        mInput.setInputType(InputType.TYPE_CLASS_TEXT);
        mInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mInput.setBackgroundColor(Color.TRANSPARENT);
        mInput.setPadding(0,0,0,0);
        mInput.setText(mQuery);
        mInput.setSelectAllOnFocus(false);
        LinearLayout.LayoutParams inLp = Ui.lp(0, -2, 1f);
        inLp.leftMargin = Ui.dp(ctx, 14);
        box.addView(mInput, inLp);
        TextView go = Ui.text(ctx, "搜索", 13, Ui.PRIMARY, true);
        go.setBackground(Ui.pill(ctx, Ui.PRIMARY));
        go.setTextColor(Ui.BG);
        go.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 6), Ui.dp(ctx, 16), Ui.dp(ctx, 6));
        go.setFocusable(true);
        go.setOnFocusChangeListener((v,focus)->Ui.buttonState(go,focus,false));
        go.setOnClickListener(v->doSearch(mInput.getText().toString()));
        box.addView(go);
        LinearLayout.LayoutParams boxLp = Ui.lp(-1, -2);
        boxLp.topMargin = Ui.dp(ctx, 24);
        col.addView(box, boxLp);


        mInput.setOnFocusChangeListener((v, has) -> {
            Ui.focus(v, has, 1f);
            box.setBackground(Ui.roundStroke(ctx, has ? 0xE9252C2A : 0xC31B2020, 14,
                    has ? Ui.PRIMARY : 0x30FFFFFF, has ? 2 : 1.1f));
        });
        box.setOnClickListener(v -> focusSearchInput());
        mInput.setOnClickListener(v -> focusSearchInput());
        mInput.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH || event!=null && event.getKeyCode()==KeyEvent.KEYCODE_ENTER){
                doSearch(mInput.getText().toString());return true;
            }
            return false;
        });

        // ---- 热搜
        LinearLayout hotHead = Ui.row(ctx);
        mHotHead = hotHead;
        LinearLayout.LayoutParams hhLp = Ui.lp(-1, -2);
        hhLp.topMargin = Ui.dp(ctx, 34);
        View ac = new View(ctx);
        ac.setBackground(Ui.round(ctx, Ui.PRIMARY, 2));
        hotHead.addView(ac, Ui.lp(Ui.dp(ctx, 4), Ui.dp(ctx, 16)));
        TextView ht = Ui.text(ctx, "大家在搜", 18, Ui.TEXT, true);
        LinearLayout.LayoutParams htLp = Ui.lp(-2, -2);
        htLp.leftMargin = Ui.dp(ctx, 10);
        hotHead.addView(ht, htLp);
        hotHead.addView(new View(ctx),Ui.lp(0,1,1));
        TextView rotate=Ui.action(ctx,"换一批",IconView.REFRESH,false);
        mHotRotate=rotate;rotate.setEnabled(false);
        rotate.setOnClickListener(v->{hotOffset=hotWords.isEmpty()?0:(hotOffset+12)%hotWords.size();renderHot();});
        hotHead.addView(rotate,Ui.lp(-2,Ui.dp(ctx,40)));
        col.addView(hotHead, hhLp);

        mHotHost = Ui.column(ctx);
        mHotHost.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        Ui.padding(mHotHost,0,0,0,0);
        LinearLayout.LayoutParams hotLp = Ui.lp(-1, -2);
        hotLp.topMargin = Ui.dp(ctx, 14);
        col.addView(mHotHost, hotLp);
        showHotLoading();

        // ---- 结果
        mResultBox = Ui.column(ctx);
        LinearLayout.LayoutParams rbLp = Ui.lp(-1, -2);
        rbLp.topMargin = Ui.dp(ctx, 34);
        col.addView(mResultBox, rbLp);
        mResultBox.setVisibility(View.GONE);

        mResultTitle = Ui.text(ctx, "热门短剧", 18, Ui.TEXT, true);
        mResultBox.addView(mResultTitle, Ui.lp(-1, -2));

        mHint = Ui.text(ctx, "", 13, Ui.TEXT_4, false);
        LinearLayout.LayoutParams hintLp = Ui.lp(-1, -2);
        hintLp.topMargin = Ui.dp(ctx, 8);
        hintLp.bottomMargin = Ui.dp(ctx, 16);
        mResultBox.addView(mHint, hintLp);

        mGrid = new CardGrid(ctx, 7, 112, 160, 12);
        mGrid.setLeftAligned(true);
        mGrid.setOnPick(s -> host.openDetail(s));
        mResultBox.addView(mGrid, Ui.lp(-1, -2));
        searchMore=Ui.action(ctx,"加载更多搜索结果",-1,false);
        searchMore.setOnClickListener(v->loadSearchPage());
        LinearLayout.LayoutParams moreLp=Ui.lp(-1,Ui.dp(ctx,46));moreLp.topMargin=Ui.dp(ctx,18);
        mResultBox.addView(searchMore,moreLp);
        mScroll.getViewTreeObserver().addOnScrollChangedListener(()->{
            if(mScroll.isShown() && searchHasMore && !searchBusy && !searchFailed && ((View)mScroll.getChildAt(0)).getHeight()-mScroll.getScrollY()-mScroll.getHeight()<Ui.dp(ctx,400))loadSearchPage();
        });

        return root;
    }

    // ------------------------------------------------------------ 输入

    private void focusSearchInput() {
        mInput.requestFocus();
        android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)
                ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(im!=null)im.showSoftInput(mInput,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    // ------------------------------------------------------------ 搜索

    private void doSearch(String q) {
        android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)
                ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(im!=null)im.hideSoftInputFromWindow(mInput.getWindowToken(),0);
        mQuery = q == null ? "" : q.trim();
        if (mQuery.isEmpty()) {
            searchGeneration++;searchHasMore=false;searchBusy=false;searchFailed=false;
            searchPage=1;searchTotal=0;searchItems.clear();searchIds.clear();
            mInput.setText("");
            mInput.setHint("输入剧名…");
            mResultBox.setVisibility(View.GONE);
            mGrid.setData(null);
            mHotHead.setVisibility(View.VISIBLE);mHotHost.setVisibility(View.VISIBLE);
            searchMore.setVisibility(View.GONE);mHint.setText("");
            mScroll.scrollTo(0,0);
            return;
        }
        mInput.setText(mQuery);
        mInput.setSelection(mInput.length());
        mInput.setHint("输入剧名…");
        mInput.clearFocus();
        searchGeneration++;searchPage=1;searchTotal=0;searchBusy=false;searchHasMore=true;searchItems.clear();searchIds.clear();
        mHotHead.setVisibility(View.GONE);mHotHost.setVisibility(View.GONE);
        mScroll.scrollTo(0,0);
        mResultBox.setVisibility(View.VISIBLE);mResultTitle.setText("搜索结果");mGrid.setData(null);mGrid.showLoading();
        loadSearchPage();
    }
    private void loadSearchPage(){
        if(searchBusy || !searchHasMore || mQuery.isEmpty())return;
        searchBusy=true;searchFailed=false;final int generation=searchGeneration,page=searchPage;final String query=mQuery;
        searchMore.setVisibility(View.VISIBLE);searchMore.setEnabled(false);searchMore.setText("正在加载搜索结果…");
        if(searchItems.isEmpty())mHint.setText("正在搜索「"+query+"」…");
        App.io().execute(()->{try{
            OnlineSearch.Page found=OnlineSearch.page(query,page);
            host.runOnUiThread(()->{
                if(generation!=searchGeneration)return;
                searchBusy=false;searchPage=found.number+1;searchTotal=found.total;searchHasMore=found.more;
                List<Series> added=new ArrayList<>();for(Series item:found.items)if(searchIds.add(item.seriesId)){searchItems.add(item);added.add(item);}
                if(page==1)mGrid.setData(searchItems);else mGrid.append(added);
                mHint.setText(searchItems.isEmpty()?"没有找到相关短剧，试试完整剧名或关键词":"共 "+searchTotal+" 部 · 已加载 "+searchItems.size()+" 部");
                searchMore.setEnabled(searchHasMore);searchMore.setText(searchHasMore?"加载更多搜索结果":"搜索结果已全部加载");
            });
        }catch(Exception error){host.runOnUiThread(()->{
            if(generation!=searchGeneration)return;searchBusy=false;searchFailed=true;mGrid.finishLoading();
            mHint.setText(error.getMessage());searchMore.setEnabled(true);searchMore.setText("重试加载搜索结果");
        });}});
    }

    private List<Series> topN(List<Series> src, int n) {
        if (src == null) return new ArrayList<>();
        return src.size() <= n ? src : new ArrayList<>(src.subList(0, n));
    }

    // ------------------------------------------------------------ 热搜

    private List<String> defaultHot() {
        List<String> l = new ArrayList<>();
        for (String s : FALLBACK_HOT) l.add(s);
        return l;
    }

    private void loadHot() {
        if(mHotLoaded)return;mHotLoaded=true;
        App.io().execute(()->{
            List<Series> picks=new ArrayList<>();
            try{picks=Repo.rank("ranklist_hot_search_sc",null,24);}catch(Exception ignored){}
            final List<Series> result=picks;
            host.runOnUiThread(()->{
                hotSeries.clear();java.util.LinkedHashSet<String> words=new java.util.LinkedHashSet<>();
                for(Series item:result)if(item.title!=null && !item.title.isEmpty()){
                    hotSeries.put(item.title,item);words.add(item.title);
                }
                hotOffset=0;
                if(words.isEmpty()){
                    mHotHost.removeAllViews();
                    TextView retry=Ui.action(ctx,"热搜暂未加载，点击重试",IconView.REFRESH,false);
                    retry.setOnClickListener(v->{mHotLoaded=false;showHotLoading();loadHot();});
                    mHotHost.addView(retry,Ui.lp(-2,Ui.dp(ctx,44)));
                }else fillHot(new ArrayList<>(words));
            });
        });
    }
    private void fillHot(List<String> words){hotWords=new ArrayList<>(words);mHotRotate.setEnabled(!hotWords.isEmpty());renderHot();}
    private void showHotLoading(){
        mHotHost.removeAllViews();
        mHotHost.addView(new com.hgtv.app.ui.widget.SkeletonView(ctx,com.hgtv.app.ui.widget.SkeletonView.EPISODES,3,44,4),Ui.lp(-1,-2));
    }
    private void renderHot(){
        mHotHost.removeAllViews();
        int columns=ctx.getResources().getConfiguration().screenWidthDp>=1100?4:3;
        int count=Math.min(12,hotWords.size());LinearLayout row=null;
        for(int i=0;i<count;i++){
            if(i%columns==0){row=Ui.row(ctx);LinearLayout.LayoutParams rlp=Ui.lp(-1,-2);rlp.bottomMargin=Ui.dp(ctx,10);mHotHost.addView(row,rlp);}
            final String word=hotWords.get((hotOffset+i)%hotWords.size());
            final Series pick=hotSeries.get(word);
            TextView chip=Ui.text(ctx,word,14,Ui.TEXT_2,false);
            chip.setSingleLine(true);chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setGravity(Gravity.CENTER_VERTICAL);chip.setFocusable(true);chip.setContentDescription(word);
            chip.setBackground(Ui.roundStroke(ctx,0x3D1B2020,10,0x20FFFFFF,1));
            Ui.padding(chip,12,10,12,10);
            chip.setOnFocusChangeListener((v,has)->{
                TextView item=(TextView)v;
                item.setBackground(Ui.roundStroke(ctx,has?0xA5252C2A:0x3D1B2020,10,has?Ui.PRIMARY:0x20FFFFFF,has?2:1));
                item.setTextColor(has?Ui.PRIMARY:Ui.TEXT_2);
            });
            chip.setOnClickListener(v->{if(pick!=null)host.openDetail(pick);else doSearch(word);});
            LinearLayout.LayoutParams lp=Ui.lp(0,Ui.dp(ctx,44),1);
            if(i%columns!=0)lp.leftMargin=Ui.dp(ctx,10);row.addView(chip,lp);
        }
        if(row!=null)for(int i=count%columns;i>0 && i<columns;i++){
            LinearLayout.LayoutParams lp=Ui.lp(0,1,1);lp.leftMargin=Ui.dp(ctx,10);row.addView(new View(ctx),lp);
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onShow() {
        loadHot();
    }

    private void renderList(List<Series> list) {
        mGrid.setData(list);
    }

    @Override
    public boolean onBack() {
        if(mQuery.isEmpty())return false;
        doSearch("");
        focusFirst();
        mScroll.scrollTo(0,0);
        return true;
    }

    @Override
    public void focusFirst() {
        if(mInput!=null)mInput.requestFocus();
    }

    @Override
    public void bindNav(int navViewId) {
        mNavId = navViewId;
        if (mInput != null && mInput.getParent() instanceof View) {
            ((View) mInput.getParent()).setNextFocusLeftId(navViewId);
        }
    }
}

