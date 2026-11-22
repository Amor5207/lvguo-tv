package ink.pengfei.lvguo.ui.page;

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

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.net.Catalog;
import ink.pengfei.lvguo.net.Browse;
import ink.pengfei.lvguo.net.OnlineSearch;
import ink.pengfei.lvguo.net.LvguoApi;
import ink.pengfei.lvguo.net.Repo;
import ink.pengfei.lvguo.ui.IconView;
import ink.pengfei.lvguo.ui.Page;
import ink.pengfei.lvguo.ui.Ui;
import ink.pengfei.lvguo.ui.widget.CardGrid;
import ink.pengfei.lvguo.ui.widget.FocusScrollView;

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
    private CardGrid mHotGrid;
    private LinearLayout mResultBox;

    private String mQuery = "";
    private int searchGeneration;
    private int mNavId;
    private List<Series> mAll;
    private boolean mHotLoaded;
    private LinearLayout mSuggest, mHistoryHead;
    private android.widget.HorizontalScrollView mHistoryRow;
    private int suggestSeq;
    private int imeRequest;
    private final Runnable suggestTask = () -> runSuggest();
    private String pendingSuggest = "";
    private int hotOffset;
    private List<String> hotWords=new ArrayList<>();
    private final java.util.Map<String,Series> hotSeries=new java.util.LinkedHashMap<>();

    @Override
    protected View build() {
        FrameLayout root = new FrameLayout(ctx);
        View pageShade=new View(ctx);
        pageShade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.alpha(Ui.BG,0xF4),Ui.alpha(Ui.BG,0xFA),Ui.BG}));   // 搜索页用干净纯色底，不透出背后的模糊剧照
        root.addView(pageShade,new FrameLayout.LayoutParams(-1,-1));

        mScroll = new FocusScrollView(ctx);
        mScroll.setVerticalScrollBarEnabled(false);
        mScroll.setFillViewport(true);
        mScroll.setClipChildren(false);

        LinearLayout col = Ui.column(ctx);
        // 右侧留给手机扫码卡片
        col.setPadding(Ui.dp(ctx, 48), Ui.dp(ctx, 28), Ui.dp(ctx, 48), Ui.dp(ctx, 40));
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
        header.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(header,Ui.lp(-1,-2));

        // ---- 搜索框
        LinearLayout box = Ui.row(ctx);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.roundStroke(ctx, Ui.SURFACE, 14, Ui.SEPARATOR, 1f));
        box.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 9), Ui.dp(ctx, 10), Ui.dp(ctx, 9));
        IconView mag = new IconView(ctx, IconView.SEARCH, 20);
        mag.setColor(Ui.TEXT_3);
        box.addView(mag);
        mInput = new EditText(ctx);
        mInput.setFocusable(true);
        mInput.setFocusableInTouchMode(true);
        mInput.setShowSoftInputOnFocus(true);
        mInput.setSingleLine(true);
        mInput.setTextSize(17);
        mInput.setTextColor(Ui.TEXT);
        mInput.setHintTextColor(Ui.TEXT_4);
        mInput.setHint("输入剧名…");
        // 普通文本输入，并提示输入法优先用简体中文（拼音）键盘；不能强制，但主流电视输入法都会遵循
        mInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        if (android.os.Build.VERSION.SDK_INT >= 24) mInput.setImeHintLocales(new android.os.LocaleList(java.util.Locale.SIMPLIFIED_CHINESE));
        mInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mInput.setBackgroundColor(Color.TRANSPARENT);
        mInput.setPadding(0,0,0,0);
        mInput.setText(mQuery);
        mInput.setSelectAllOnFocus(false);
        LinearLayout.LayoutParams inLp = Ui.lp(0, -2, 1f);
        inLp.leftMargin = Ui.dp(ctx, 14);
        box.addView(mInput, inLp);
        // 搜索键与输入框融为一体：静止是淡色胶囊，聚焦才实心高亮（不再用整块亮绿）
        TextView go = Ui.text(ctx, "搜索", 15, Ui.TEXT, false);
        go.setGravity(Gravity.CENTER);
        go.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), Ui.dp(ctx, 8));
        go.setFocusable(true);
        Ui.tile(go, false, false, 100);
        go.setOnFocusChangeListener((v,focus)->Ui.tile(go,focus,false,100));
        go.setOnClickListener(v->doSearch(mInput.getText().toString()));
        box.addView(go);
        // 搜索框 + 「用手机输入」并排一行
        LinearLayout searchRow = Ui.row(ctx);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.addView(box, Ui.lp(0, -2, 1f));
        TextView phone = Ui.action(ctx, "用手机输入", IconView.PHONE, false);
        phone.setOnClickListener(v -> ink.pengfei.lvguo.remote.Pairing.showSearch(host));
        LinearLayout.LayoutParams phLp = Ui.lp(-2, Ui.dp(ctx, 50)); phLp.leftMargin = Ui.dp(ctx, 14);
        searchRow.addView(phone, phLp);
        LinearLayout.LayoutParams boxLp = Ui.lp(-1, -2);
        boxLp.topMargin = Ui.dp(ctx, 18);
        col.addView(searchRow, boxLp);


        mInput.setOnFocusChangeListener((v, has) -> {
            Ui.focus(v);
            box.setBackground(Ui.roundStroke(ctx, has ? Ui.SURFACE_2 : Ui.SURFACE, 14,
                    has ? Ui.FOCUS_RING : Ui.SEPARATOR, has ? 2.5f : 1f));
        });
        box.setOnClickListener(v -> focusSearchInput());
        mInput.setOnClickListener(v -> focusSearchInput());
        mInput.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH || event!=null && event.getKeyCode()==KeyEvent.KEYCODE_ENTER){
                doSearch(mInput.getText().toString());return true;
            }
            return false;
        });

        // ---- 输入联想（输入时出现在搜索框下方，盖住历史与热搜）
        mSuggest = Ui.column(ctx);
        mSuggest.setVisibility(View.GONE);
        LinearLayout.LayoutParams sgLp = Ui.lp(-1, -2); sgLp.topMargin = Ui.dp(ctx, 14);
        col.addView(mSuggest, sgLp);
        mInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                // 拼音还在组字（没上屏）时不去联想，等选完汉字再查
                if (android.view.inputmethod.BaseInputConnection.getComposingSpanStart(e) >= 0) return;
                scheduleSuggest(e.toString().trim());
            }
        });

        // ---- 搜索历史
        mHistoryHead = Ui.row(ctx);
        mHistoryHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView hh = Ui.text(ctx, "搜索历史", 18, Ui.TEXT, true);
        LinearLayout.LayoutParams hhl = Ui.lp(-2, -2);
        mHistoryHead.addView(hh, hhl);
        mHistoryHead.addView(new View(ctx), Ui.lp(0, 1, 1));
        TextView clearHist = Ui.action(ctx, "清除", IconView.TRASH, false);
        clearHist.setOnClickListener(v -> { App.prefs().edit().remove("search_history").apply(); renderHistory(); focusSearchInput(); });
        mHistoryHead.addView(clearHist, Ui.lp(-2, Ui.dp(ctx, 40)));
        LinearLayout.LayoutParams hisLp = Ui.lp(-1, -2); hisLp.topMargin = Ui.dp(ctx, 30);
        col.addView(mHistoryHead, hisLp);
        mHistoryRow = new android.widget.HorizontalScrollView(ctx);
        mHistoryRow.setHorizontalScrollBarEnabled(false);
        mHistoryRow.setClipChildren(false); mHistoryRow.setClipToPadding(false);
        LinearLayout.LayoutParams hrLp = Ui.lp(-1, -2); hrLp.topMargin = Ui.dp(ctx, 12);
        col.addView(mHistoryRow, hrLp);
        renderHistory();

        // ---- 热搜
        LinearLayout hotHead = Ui.row(ctx);
        hotHead.setGravity(Gravity.CENTER_VERTICAL);
        mHotHead = hotHead;
        LinearLayout.LayoutParams hhLp = Ui.lp(-1, -2);
        hhLp.topMargin = Ui.dp(ctx, 34);
        View ac = new View(ctx);
        ac.setVisibility(View.GONE);  // 分区标题统一不加色条
        hotHead.addView(ac, Ui.lp(0, 0));
        TextView ht = Ui.text(ctx, "热搜榜", 18, Ui.TEXT, true);
        LinearLayout.LayoutParams htLp = Ui.lp(-2, -2);   // 与下方词条左边对齐
        hotHead.addView(ht, htLp);
        hotHead.addView(new View(ctx),Ui.lp(0,1,1));
        TextView rotate=Ui.action(ctx,"换一批",IconView.REFRESH,false);
        mHotRotate=rotate;rotate.setEnabled(false);
        rotate.setOnClickListener(v->{hotOffset=hotWords.isEmpty()?0:(hotOffset+12)%hotWords.size();renderHot();});
        rotate.setVisibility(View.GONE);   // 改成排名海报后不再需要「换一批」
        hotHead.addView(rotate,Ui.lp(-2,Ui.dp(ctx,40)));
        col.addView(hotHead, hhLp);

        mHotHost = Ui.column(ctx);
        mHotHost.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        Ui.padding(mHotHost,0,0,0,0);
        LinearLayout.LayoutParams hotLp = Ui.lp(-1, -2);
        hotLp.topMargin = Ui.dp(ctx, 14);
        col.addView(mHotHost, hotLp);
        // 热搜榜：排名海报（TOP1-3 金色角标），点卡直接进详情；接口失败时才回退到文字热词
        mHotGrid = new CardGrid(ctx, Ui.GRID_CARD_W, Ui.GRID_CARD_H, 12);
        mHotGrid.setLeftAligned(true);
        mHotGrid.setOnPick(s -> host.openDetail(s));
        LinearLayout.LayoutParams hgLp = Ui.lp(-1, -2); hgLp.topMargin = Ui.dp(ctx, 14);
        col.addView(mHotGrid, hgLp);
        mHotGrid.setVisibility(View.GONE);
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

        mGrid = new CardGrid(ctx, Ui.GRID_CARD_W, Ui.GRID_CARD_H, 12);
        mGrid.setLeftAligned(true);
        mGrid.setOnPick(s -> host.openDetail(s));
        mResultBox.addView(mGrid, Ui.lp(-1, -2));
        searchMore=Ui.action(ctx,"加载更多搜索结果",-1,false);searchMore.setFocusable(false);
        mGrid.setOnNearEnd(this::loadSearchPage);
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
        if(mInput==null)return;
        final int request=++imeRequest;
        mInput.requestFocus();
        mInput.post(()->showSearchImeWhenReady(request,0));
    }

    /** Wait for the cached tab/window to become the active IME target before asking for the keyboard. */
    private void showSearchImeWhenReady(int request,int attempt) {
        if(mInput==null || request!=imeRequest || !mInput.isShown())return;
        if(!mInput.hasFocus())mInput.requestFocus();
        if(!mInput.isAttachedToWindow() || !mInput.hasWindowFocus()) {
            if(attempt<12)mInput.postDelayed(()->showSearchImeWhenReady(request,attempt+1),50);
            return;
        }
        if(android.os.Build.VERSION.SDK_INT>=30) {
            android.view.WindowInsetsController controller=mInput.getWindowInsetsController();
            if(controller!=null) {
                controller.show(android.view.WindowInsets.Type.ime());
                return;
            }
        }
        android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)
                ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(im!=null)im.showSoftInput(mInput,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    // ------------------------------------------------------------ 搜索

    // ------------------------------------------------------------ 搜索历史 / 联想

    private static List<String> history() {
        List<String> out = new ArrayList<>();
        try { org.json.JSONArray a = new org.json.JSONArray(App.prefs().getString("search_history", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i)); } catch (Exception ignored) { }
        return out;
    }
    private static void remember(String q) {
        List<String> h = history(); h.remove(q); h.add(0, q);
        while (h.size() > 12) h.remove(h.size() - 1);
        App.prefs().edit().putString("search_history", new org.json.JSONArray(h).toString()).apply();
    }

    private TextView chip(String text, View.OnClickListener click) {
        TextView c = Ui.text(ctx, text, 15, Ui.TEXT, false);
        c.setGravity(Gravity.CENTER); c.setSingleLine(true);
        c.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), 0);
        c.setFocusable(true);
        Ui.tile(c, false, false, 100);
        c.setOnFocusChangeListener((v, f) -> Ui.tile(c, f, false, 100));
        c.setOnClickListener(click);
        return c;
    }

    private void renderHistory() {
        if (mHistoryRow == null) return;
        List<String> h = history();
        LinearLayout row = Ui.row(ctx);
        row.setClipChildren(false); row.setClipToPadding(false);
        for (final String w : h) {
            TextView c = chip(w, v -> { mInput.setText(w); doSearch(w); });
            c.setId(View.generateViewId());
            if (w.equals(h.get(h.size() - 1))) c.setNextFocusRightId(c.getId());   // 最后一个历史词右键停住
            LinearLayout.LayoutParams lp = Ui.lp(-2, Ui.dp(ctx, 38)); lp.rightMargin = Ui.dp(ctx, 10);
            row.addView(c, lp);
        }
        mHistoryRow.removeAllViews();
        mHistoryRow.addView(row);
        boolean show = !h.isEmpty() && mSuggest.getVisibility() != View.VISIBLE && (mResultBox == null || mResultBox.getVisibility() != View.VISIBLE);
        mHistoryHead.setVisibility(show ? View.VISIBLE : View.GONE);
        mHistoryRow.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 输入停顿 250ms 后取联想：先列匹配的历史，再列官方联想词。 */
    private void scheduleSuggest(String q) {
        pendingSuggest = q;
        mSuggest.removeCallbacks(suggestTask);
        if (q.isEmpty() || q.equals(mQuery)) { showSuggest(null, q); return; }
        mSuggest.postDelayed(suggestTask, 250);
    }
    private void runSuggest() {
        final String q = pendingSuggest; final int seq = ++suggestSeq;
        App.io().execute(() -> {
            List<String> words = new ArrayList<>();
            try { words = ink.pengfei.lvguo.net.OnlineSearch.suggest(q); } catch (Exception ignored) { }
            final List<String> got = words;
            host.runOnUiThread(() -> { if (seq == suggestSeq && q.equals(pendingSuggest)) showSuggest(got, q); });
        });
    }
    private void showSuggest(List<String> words, String q) {
        mSuggest.removeAllViews();
        if (words == null || q.isEmpty()) {
            mSuggest.setVisibility(View.GONE);
            if (mResultBox.getVisibility() != View.VISIBLE) { setHotVisible(true); }
            renderHistory();
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (String h : history()) if (h.contains(q) && !h.equals(q) && rows.size() < 3) rows.add(new String[]{h, "1"});
        for (String w : words) { boolean dup = false; for (String[] r : rows) if (r[0].equals(w)) dup = true; if (!dup && rows.size() < 8) rows.add(new String[]{w, "0"}); }
        if (rows.isEmpty()) { showSuggest(null, ""); return; }
        for (final String[] r : rows) {
            LinearLayout line = Ui.row(ctx);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setFocusable(true);
            Ui.padding(line, 16, 0, 16, 0);
            IconView ic = new IconView(ctx, "1".equals(r[1]) ? IconView.HISTORY : IconView.SEARCH, 18);
            line.addView(ic, Ui.lp(Ui.dp(ctx, 18), Ui.dp(ctx, 18)));
            android.text.SpannableString sp = new android.text.SpannableString(r[0]);
            int at = r[0].indexOf(q);
            if (at >= 0) sp.setSpan(new android.text.style.ForegroundColorSpan(Ui.ACCENT_TEXT), at, at + q.length(), 0);
            TextView t = Ui.text(ctx, "", 16, "1".equals(r[1]) ? Ui.TEXT_2 : Ui.TEXT, false);
            t.setText(sp); t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tl = Ui.lp(0, -2, 1); tl.leftMargin = Ui.dp(ctx, 14);
            line.addView(t, tl);
            line.setOnFocusChangeListener((v, f) -> {
                line.setBackground(f ? Ui.round(ctx, Ui.FOCUS_FILL, 12) : null);
                int c = f ? Ui.ON_FOCUS : "1".equals(r[1]) ? Ui.TEXT_2 : Ui.TEXT;
                t.setTextColor(c); ic.setColor(f ? Ui.ON_FOCUS : Ui.TEXT_3);
                if (f && at >= 0) { android.text.SpannableString plain = new android.text.SpannableString(r[0]); t.setText(plain); }
                else if (!f) t.setText(sp);
            });
            ic.setColor(Ui.TEXT_3);
            line.setOnClickListener(v -> { mInput.setText(r[0]); doSearch(r[0]); });
            LinearLayout.LayoutParams ll = Ui.lp(-1, Ui.dp(ctx, 48)); ll.bottomMargin = Ui.dp(ctx, 2);
            mSuggest.addView(line, ll);
        }
        mSuggest.setVisibility(View.VISIBLE);
        mHistoryHead.setVisibility(View.GONE); mHistoryRow.setVisibility(View.GONE);
        setHotVisible(false);
    }

    private void doSearch(String q) {
        android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)
                ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(im!=null)im.hideSoftInputFromWindow(mInput.getWindowToken(),0);
        mQuery = q == null ? "" : q.trim();
        if (mSuggest != null) { mSuggest.removeCallbacks(suggestTask); suggestSeq++; mSuggest.removeAllViews(); mSuggest.setVisibility(View.GONE); }
        if (!mQuery.isEmpty()) remember(mQuery);
        if (mQuery.isEmpty()) {
            searchGeneration++;searchHasMore=false;searchBusy=false;searchFailed=false;
            searchPage=1;searchTotal=0;searchItems.clear();searchIds.clear();
            mInput.setText("");
            mInput.setHint("输入剧名…");
            mResultBox.setVisibility(View.GONE);
            mGrid.setData(null);
            setHotVisible(true);renderHistory();
            searchMore.setVisibility(View.GONE);mHint.setText("");
            mScroll.smoothScrollTo(0,0);
            return;
        }
        mInput.setText(mQuery);
        mInput.setSelection(mInput.length());
        mInput.setHint("输入剧名…");
        mInput.clearFocus();
        searchGeneration++;searchPage=1;searchTotal=0;searchBusy=false;searchHasMore=true;searchItems.clear();searchIds.clear();
        setHotVisible(false);
        if (mHistoryHead != null) { mHistoryHead.setVisibility(View.GONE); mHistoryRow.setVisibility(View.GONE); }
        mScroll.smoothScrollTo(0,0);
        mResultBox.setVisibility(View.VISIBLE);mResultTitle.setText("搜索结果");mGrid.setData(null);mGrid.showLoading();
        loadSearchPage();
    }
    private void loadSearchPage(){
        if(searchBusy || !searchHasMore || mQuery.isEmpty())return;
        searchBusy=true;searchFailed=false;final int generation=searchGeneration,page=searchPage;final String query=mQuery;
        searchMore.setVisibility(View.GONE);
        if(searchItems.isEmpty())mHint.setText("正在搜索「"+query+"」…");
        App.io().execute(()->{try{
            OnlineSearch.Page found=OnlineSearch.page(query,page);
            host.runOnUiThread(()->{
                if(generation!=searchGeneration)return;
                searchBusy=false;searchPage=found.number+1;searchTotal=found.total;searchHasMore=found.more;
                List<Series> added=new ArrayList<>();for(Series item:found.items)if(searchIds.add(item.seriesId)){searchItems.add(item);added.add(item);}
                if(page==1)mGrid.setData(searchItems);else mGrid.append(added);
                mHint.setText(searchItems.isEmpty()?"没有找到相关短剧":"共 "+Math.max(searchTotal,searchItems.size())+" 部");
                searchMore.setVisibility(View.GONE);
            });
        }catch(Exception error){host.runOnUiThread(()->{
            if(generation!=searchGeneration)return;searchBusy=false;searchFailed=true;mGrid.finishLoading();
            mHint.setText(error.getMessage());searchMore.setVisibility(View.VISIBLE);searchMore.setFocusable(true);searchMore.setEnabled(true);searchMore.setText("重试加载搜索结果");
        });}});
    }

    private List<Series> topN(List<Series> src, int n) {
        if (src == null) return new ArrayList<>();
        return src.size() <= n ? src : new ArrayList<>(src.subList(0, n));
    }

    // ------------------------------------------------------------ 热搜

    private boolean hotShown = true;
    private boolean hotVisible() { return hotShown; }
    /** 热搜区整体显隐：有排名海报就显示海报网格，否则显示文字热词（或加载骨架）。 */
    private void setHotVisible(boolean show) {
        hotShown = show;
        mHotHead.setVisibility(show ? View.VISIBLE : View.GONE);
        boolean grid = mHotGrid != null && mHotGrid.count() > 0;
        if (mHotGrid != null) mHotGrid.setVisibility(show && grid ? View.VISIBLE : View.GONE);
        mHotHost.setVisibility(show && !grid ? View.VISIBLE : View.GONE);
    }

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
                List<Series> ranked=new ArrayList<>();
                for(Series item:result){if(item.title==null||item.title.isEmpty())continue;item.rank=ranked.size()+1;item.showRank=true;ranked.add(item);if(ranked.size()>=12)break;}
                if(!ranked.isEmpty()){
                    hotWords=new ArrayList<>(words);
                    mHotHost.removeAllViews();mHotHost.setVisibility(View.GONE);
                    mHotGrid.setData(ranked);mHotGrid.setVisibility(hotVisible()?View.VISIBLE:View.GONE);
                    if(mNavId!=0)mHotGrid.setLeftNavId(mNavId);
                    return;
                }
                mHotGrid.finishLoading();mHotGrid.setVisibility(View.GONE);mHotHost.setVisibility(hotShown?View.VISIBLE:View.GONE);   // 接口失败：回退文字热词
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
        // 热搜榜是海报网格，加载中也用海报骨架
        mHotHost.removeAllViews();mHotHost.setVisibility(View.GONE);
        mHotGrid.setVisibility(hotShown?View.VISIBLE:View.GONE);mHotGrid.showLoading();
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
            Ui.tile(chip,false,false,14);
            Ui.padding(chip,16,10,16,10);
            chip.setOnFocusChangeListener((v,has)->Ui.tile((TextView)v,has,false,14));
            chip.setOnClickListener(v->{if(pick!=null)host.openDetail(pick);else doSearch(word);});
            LinearLayout.LayoutParams lp=Ui.lp(0,Ui.dp(ctx,44),1);
            chip.setId(View.generateViewId());if(i%columns==columns-1||i==count-1)chip.setNextFocusRightId(chip.getId());   // 行尾右键停住
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
        mScroll.smoothScrollTo(0,0);
        return true;
    }

    @Override
    public void focusFirst() {
        focusSearchInput();
    }

    @Override
    public void bindNav(int navViewId) {
        mNavId = navViewId;
        if (mGrid != null) mGrid.setLeftNavId(navViewId);
        if (mHotGrid != null) mHotGrid.setLeftNavId(navViewId);
        if (mInput != null && mInput.getParent() instanceof View) {
            ((View) mInput.getParent()).setNextFocusLeftId(navViewId);
        }
    }
}

