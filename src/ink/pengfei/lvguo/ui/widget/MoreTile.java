package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.ui.Ui;

import java.util.List;

/**
 * 横向行末尾的「查看全部」：和海报同尺寸。
 * 封面位置是后面几部剧的 2×2 拼图（压暗），中间写「+N」——一眼就知道后面还有多少；
 * 「查看全部」写在拼图中间，下方没有标题，和其它封面顶部对齐。焦点样式与海报一致（主题色描边，不放大）。
 */
public class MoreTile extends LinearLayout {
    public MoreTile(Context c, int wDp, int hDp, List<String> covers, int remaining, Runnable open) {
        super(c);
        setOrientation(VERTICAL);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
        setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2));
        int w = Ui.dp(c, wDp), h = Ui.dp(c, hDp), gap = Ui.dp(c, 2);

        RoundFrame frame = new RoundFrame(c, 12);
        frame.setBackground(Ui.round(c, Ui.SURFACE, 12));
        // 2×2 拼图
        int cw = (w - gap) / 2, ch = (h - gap) / 2;
        for (int i = 0; i < 4 && covers != null && i < covers.size(); i++) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Ui.SURFACE_2);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, ch);
            lp.leftMargin = (i % 2) * (cw + gap);
            lp.topMargin = (i / 2) * (ch + gap);
            frame.addView(iv, lp);
            String url = covers.get(i);
            if (url != null && url.startsWith("http")) ImageLoader.load(url, iv, cw, ch);
        }
        android.view.View dim = new android.view.View(c);
        dim.setBackgroundColor(0x99000000);
        frame.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout center = new LinearLayout(c);
        center.setOrientation(VERTICAL);
        center.setGravity(Gravity.CENTER);
        TextView big = Ui.text(c, remaining > 0 ? "+" + remaining : "›", remaining > 99 ? 24 : 28, 0xFFFFFFFF, true);
        big.setGravity(Gravity.CENTER);
        center.addView(big, new LayoutParams(-2, -2));
        TextView unit = Ui.text(c, "查看全部", 13, 0xE6FFFFFF, true);
        LayoutParams ul = new LayoutParams(-2, -2); ul.topMargin = Ui.dp(c, 4);
        center.addView(unit, ul);
        frame.addView(center, new FrameLayout.LayoutParams(-1, -1));
        addView(frame, new LayoutParams(w, h));

        setOnFocusChangeListener((v, has) -> {
            frame.setRing(has, Ui.FOCUS_RING, 3f);
            dim.setBackgroundColor(has ? 0x80000000 : 0x99000000);
        });
        setOnClickListener(v -> open.run());
    }
}
