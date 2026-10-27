package com.hgtv.app.ui.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hgtv.app.model.Series;
import com.hgtv.app.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 自适应卡片网格（每行固定列数）。
 *
 * 分批渲染：一次最多铺 30 张，滚到底部给一个"加载更多"。
 * 原因是每张卡片都会解码封面，一次铺 200 张会直接把图片内存拉满 ——
 * 而 TV 上用户实际上一次只会看一屏。
 */
public class CardGrid extends LinearLayout {

    public interface OnPick {
        void onPick(Series s);
    }

    private int mCols;
    private final int mMaxCols, mTargetW, mTargetH;
    private final int mGap;
    private int mCardW;
    private int mCardH;
    private int mColumnGap;
    private int layoutWidth;
    private final List<Series> mData = new ArrayList<>();
    private int mShown;
    private boolean centerIncompleteRows;
    private boolean leftAligned;
    public void setLeftAligned(boolean left){leftAligned=left;requestLayout();}
    /** Local shelves have a complete data set, so short rows can be centered safely. */
    public void setCenterIncompleteRows(boolean center){centerIncompleteRows=center;}
    private final java.util.ArrayDeque<PosterCard> recycled=new java.util.ArrayDeque<>();
    private boolean loadingSkeleton;
    private int mBatch = 30;
    private OnPick mPick;
    public interface OnBindCard { void bind(PosterCard card, Series series); }
    private OnBindCard mBinder;
    private int mLeftNavId;
    public void setLeftNavId(int navId) {
        mLeftNavId = navId;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof LinearLayout && ((LinearLayout) child).getChildCount() > 0) {
                ((LinearLayout) child).getChildAt(0).setNextFocusLeftId(navId);
            }
        }
    }
    public void setOnBindCard(OnBindCard binder) { mBinder = binder; }

    public CardGrid(Context c, int cols, int cardW, int cardH, int gapDp) {
        super(c);
        mCols = cols;
        mMaxCols = cols;
        mTargetW = cardW;
        mTargetH = cardH;
        mCardW = cardW;
        mCardH = cardH;
        mGap = Ui.dp(c, gapDp);
        mColumnGap = mGap;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        getViewTreeObserver().addOnScrollChangedListener(this::refreshVisible);
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int w=MeasureSpec.getSize(widthSpec);
        layoutWidth=w;
        int available = w - getPaddingLeft() - getPaddingRight();
        if (available <= 0){super.onMeasure(widthSpec,heightSpec);return;}
        int columns = Math.max(1, Math.min(mMaxCols,
                (available + mGap) / (Ui.dp(getContext(), mTargetW + 4) + mGap)));
        float density = getResources().getDisplayMetrics().density;
        int widthDp = Math.max(60, Math.min(mTargetW + 24,
                (int) (((available - mGap * (columns - 1)) / columns - Ui.dp(getContext(), 4)) / density)));
        int cardOuter=Ui.dp(getContext(),widthDp+4);
        int columnGap=columns>1?Math.max(Ui.dp(getContext(),8),(available-columns*cardOuter)/(columns-1)):mGap;
        if (columns != mCols || widthDp != mCardW || columnGap != mColumnGap){
            recycled.clear();
            mCols = columns;
            mCardW = widthDp;
            mCardH = Math.round(widthDp * (float) mTargetH / mTargetW);
            mColumnGap = columnGap;
            if(loadingSkeleton)showLoading();else rebuild();
        }else for(int i=0;i<getChildCount();i++)if(getChildAt(i) instanceof LinearLayout)centerRow((LinearLayout)getChildAt(i));
        super.onMeasure(widthSpec,heightSpec);
    }

    public void setOnPick(OnPick p) {
        mPick = p;
    }

    public void setData(List<Series> data) {
        loadingSkeleton=false;
        mData.clear();
        if (data != null) mData.addAll(data);
        mShown = 0;
        rebuild();
    }

    public void setBatch(int n) {
        mBatch = n;
    }

    public void append(List<Series> more) {
        clearTailLoading();
        if(loadingSkeleton){loadingSkeleton=false;removeAllViews();}
        if (more == null || more.isEmpty()) return;
        mData.addAll(more);
        addBatch();
    }

    public int count() {
        return mData.size();
    }

    public void showLoading(){
        if(!mData.isEmpty())return;
        loadingSkeleton=true;removeAllViews();
        addView(new SkeletonView(getContext(),SkeletonView.GRID,mCols,mCardH,2)
                .gridGeometry(mCardW,mColumnGap,leftAligned),Ui.lp(-1,-2));
    }
    public void finishLoading(){
        clearTailLoading();
        if(loadingSkeleton){loadingSkeleton=false;removeAllViews();}
    }
    public void showTailLoading(){
        if(mData.isEmpty()){showLoading();return;}
        clearTailLoading();
        if(mShown<mData.size())return;
        int missing=(mCols-mShown%mCols)%mCols;
        LinearLayout last=null;
        for(int n=getChildCount()-1;n>=0;n--)if(getChildAt(n) instanceof LinearLayout){last=(LinearLayout)getChildAt(n);break;}
        if(last!=null && missing>0 && last.getChildCount()>0)for(int n=0;n<missing;n++){
            SkeletonView cell=new SkeletonView(getContext(),SkeletonView.GRID,1,mCardH,1).gridGeometry(mCardW,0,true);
            cell.setTag("tail_skeleton");LinearLayout.LayoutParams lp=Ui.lp(Ui.dp(getContext(),mCardW+4),-2);lp.leftMargin=mColumnGap;last.addView(cell,lp);
        }
        SkeletonView row=new SkeletonView(getContext(),SkeletonView.GRID,mCols,mCardH,1).gridGeometry(mCardW,mColumnGap,leftAligned);
        row.setTag("tail_skeleton");addView(row,Ui.lp(-1,-2));
    }
    private void clearTailLoading(){
        for(int n=getChildCount()-1;n>=0;n--){View child=getChildAt(n);
            if("tail_skeleton".equals(child.getTag())){removeViewAt(n);continue;}
            if(child instanceof LinearLayout){LinearLayout row=(LinearLayout)child;for(int k=row.getChildCount()-1;k>=0;k--)if("tail_skeleton".equals(row.getChildAt(k).getTag()))row.removeViewAt(k);}
        }
    }

    private void rebuild() {
        removeAllViews();
        recycled.clear();
        mShown = 0;
        addBatch();
    }

    private void addBatch() {
        // 移除旧的"更多"按钮
        int n = getChildCount();
        if (n > 0) {
            View last = getChildAt(n - 1);
            if (last instanceof TextView && "更多".equals(String.valueOf(last.getTag()))) {
                removeViewAt(n - 1);
            }
        }

        int end = Math.min(mData.size(), mShown + mBatch);
        LinearLayout row = null;
        int col = 0;

        // 若最后一行没填满，先补齐到该行
        if (mShown % mCols != 0) {
            row = (LinearLayout) getChildAt(getChildCount() - 1);
            col = mShown % mCols;
            restoreRow(row);
        }

        for (int i = mShown; i < end; i++) {
            if (col == 0) {
                row = Ui.row(getContext());row.setClipChildren(false);row.setClipToPadding(false);row.setTag(i);centerRow(row);
                LinearLayout.LayoutParams rlp = Ui.lp(-1, -2);
                rlp.bottomMargin = mGap;
                addView(row, rlp);
            }
            row.addView(makeCard(i,col));
            col = (col + 1) % mCols;
        }
        mShown = end;
        post(this::refreshVisible);

        if (mShown < mData.size()) {
            TextView more = Ui.text(getContext(), "加载更多",
                    14, Ui.TEXT_2, false);
            more.setTag("更多");
            more.setGravity(Gravity.CENTER);
            more.setFocusable(true);
            more.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            more.setOnFocusChangeListener((v, has) -> {
                v.setBackground(Ui.roundStroke(getContext(), has ? Ui.TINT_PRIMARY : 0,
                        6, has ? Ui.PRIMARY : 0, has ? 2 : 0));
                ((TextView) v).setTextColor(has ? Ui.TEXT : Ui.TEXT_2);
            });
            more.setOnClickListener(v -> addBatch());
            LinearLayout.LayoutParams mlp = Ui.lp(Ui.dp(getContext(), 240), Ui.dp(getContext(), 48));
            mlp.gravity = Gravity.CENTER_HORIZONTAL;
            mlp.topMargin = Ui.dp(getContext(), 6);
            addView(more, mlp);
        }
    }


    private void centerRow(LinearLayout row){
        if(leftAligned || row.getChildCount()>=mCols){row.setPadding(0,0,0,0);return;}
        int start=row.getTag() instanceof Integer?(Integer)row.getTag():0;
        int slots=centerIncompleteRows?Math.max(1,Math.min(mCols,mData.size()-start)):mCols;
        int content=slots*Ui.dp(getContext(),mCardW+4)+(slots-1)*mColumnGap;
        int inset=Math.max(0,(layoutWidth-getPaddingLeft()-getPaddingRight()-content)/2);
        row.setPadding(inset,0,inset,0);
    }
    private PosterCard makeCard(int index,int col){
        final Series s=mData.get(index);
        PosterCard card=recycled.pollFirst();
        if(card==null)card=new PosterCard(getContext(),s,mCardW,mCardH);else card.bind(s);
        if(mBinder!=null)mBinder.bind(card,s);
        card.setOnClickListener(v->{if(mPick!=null)mPick.onPick(s);});
        LinearLayout.LayoutParams lp=Ui.lp(Ui.dp(getContext(),mCardW+4),-2);
        if(col!=0)lp.leftMargin=mColumnGap;card.setLayoutParams(lp);
        card.setNextFocusLeftId(col==0 && mLeftNavId!=0?mLeftNavId:View.NO_ID);
        return card;
    }
    private void restoreRow(LinearLayout row){
        if(row.getChildCount()!=0 || !(row.getTag() instanceof Integer))return;
        int start=(Integer)row.getTag();
        for(int k=start;k<Math.min(start+mCols,mShown);k++)row.addView(makeCard(k,k-start));
        row.getLayoutParams().height=LayoutParams.WRAP_CONTENT;row.requestLayout();
    }
    private void recycleRow(LinearLayout row){
        if(row.getChildCount()==0 || row.hasFocus() || row.getHeight()==0)return;
        row.getLayoutParams().height=row.getHeight();
        for(int j=row.getChildCount()-1;j>=0;j--){
            View child=row.getChildAt(j);row.removeViewAt(j);
            if(child instanceof PosterCard && recycled.size()<mCols*3)recycled.addLast((PosterCard)child);
        }
        row.requestLayout();
    }

    private final android.graphics.Rect visibleRect=new android.graphics.Rect();
    @Override protected void onVisibilityChanged(View changed,int visibility){
        super.onVisibilityChanged(changed,visibility);
        if(mData!=null)post(this::refreshVisible);
    }
    private void refreshVisible(){
        boolean shown=isShown() && getGlobalVisibleRect(visibleRect);
        int top=visibleRect.top,bottom=visibleRect.bottom;
        int[] location=new int[2];getLocationOnScreen(location);
        int margin=Ui.dp(getContext(),mCardH*2+48);
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);if(!(child instanceof LinearLayout))continue;
            LinearLayout row=(LinearLayout)child;
            boolean active=shown && location[1]+row.getBottom()>top-margin && location[1]+row.getTop()<bottom+margin;
            if(active)restoreRow(row);else recycleRow(row);
            for(int j=0;j<row.getChildCount();j++)if(row.getChildAt(j) instanceof PosterCard)
                ((PosterCard)row.getChildAt(j)).onActiveChanged(active);
        }
        if(shown && mShown<mData.size() && location[1]+getHeight()-bottom<margin)addBatch();
    }

    public void focusFirst() {
        for (int i = 0; i < getChildCount(); i++) {
            View row = getChildAt(i);
            if (row instanceof LinearLayout && row.isShown()) {
                LinearLayout l = (LinearLayout) row;
                for (int j = 0; j < l.getChildCount(); j++) {
                    View c = l.getChildAt(j);
                    if (c.isFocusable()) {
                        c.requestFocus();
                        return;
                    }
                }
            }
        }
    }
}
