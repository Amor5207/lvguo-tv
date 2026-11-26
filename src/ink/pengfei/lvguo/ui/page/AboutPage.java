package ink.pengfei.lvguo.ui.page;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.ui.Page;
import ink.pengfei.lvguo.ui.Ui;
import ink.pengfei.lvguo.ui.widget.RoundFrame;

/**
 * 关于：展示图标、名称、版本号、播放模块版本与开源地址二维码，右屏为免责声明。
 * 发现新版本时这里出现唯一的「更新」按钮（签名模块类更新只在这里提示，不弹首页弹窗）。
 */
public class AboutPage extends Page {
    public static final String GITHUB = "https://github.com/Amor5207/lvguo-tv";
    private View updateAction;

    @Override protected View build() {
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Ui.BG);
        // 页面本身接住焦点（不可见），避免焦点留在设置行上被再次「确定」
        root.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) root.setDefaultFocusHighlightEnabled(false);

        LinearLayout panes = Ui.row(ctx);
        panes.setGravity(Gravity.CENTER_VERTICAL);
        Ui.padding(panes, 88, 40, 88, 40);

        // 左：品牌 + 开源地址
        LinearLayout left = Ui.column(ctx);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        RoundFrame iconFrame = new RoundFrame(ctx, 24);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(ink.pengfei.lvguo.R.drawable.app_icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iconFrame.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        left.addView(iconFrame, Ui.lp(Ui.dp(ctx, 96), Ui.dp(ctx, 96)));
        TextView name = Ui.text(ctx, "绿果TV", 26, Ui.TEXT, true);
        LinearLayout.LayoutParams nl = Ui.lp(-2, -2); nl.topMargin = Ui.dp(ctx, 14);
        left.addView(name, nl);
        String v = "";
        try { v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView ver = Ui.text(ctx, "版本号 " + v, 14, Ui.TEXT_3, false);
        LinearLayout.LayoutParams vl = Ui.lp(-2, -2); vl.topMargin = Ui.dp(ctx, 6);
        left.addView(ver, vl);
        String next = ink.pengfei.lvguo.update.UpdateManager.availableVersion();
        if (next != null) {
            updateAction = Ui.action(ctx, "更新到 " + next, -1, true);
            updateAction.setOnClickListener(x -> ink.pengfei.lvguo.update.UpdateManager.onItemClick(host));
            LinearLayout.LayoutParams al = Ui.lp(-2, -2); al.topMargin = Ui.dp(ctx, 14);
            left.addView(updateAction, al);
        }

        LinearLayout gh = Ui.row(ctx);
        gh.setGravity(Gravity.CENTER_VERTICAL);
        Ui.padding(gh, 14, 14, 18, 14);
        gh.setBackground(Ui.round(ctx, Ui.SURFACE, 16));
        RoundFrame qrFrame = new RoundFrame(ctx, 8);
        qrFrame.setBackground(Ui.round(ctx, 0xFFFFFFFF, 8));
        ImageView qr = new ImageView(ctx);
        Bitmap bmp = qr(GITHUB);
        if (bmp != null) qr.setImageBitmap(bmp);
        qrFrame.addView(qr, new FrameLayout.LayoutParams(Ui.dp(ctx, 76), Ui.dp(ctx, 76), Gravity.CENTER));
        gh.addView(qrFrame, Ui.lp(Ui.dp(ctx, 86), Ui.dp(ctx, 86)));
        LinearLayout ghText = Ui.column(ctx);
        ghText.addView(Ui.text(ctx, "开源地址", 14, Ui.TEXT, true));
        TextView url = Ui.text(ctx, "github.com/Amor5207/lvguo-tv", 13, Ui.ACCENT_TEXT, false);
        LinearLayout.LayoutParams ul = Ui.lp(-2, -2); ul.topMargin = Ui.dp(ctx, 5);
        ghText.addView(url, ul);
        TextView scan = Ui.text(ctx, "手机扫码打开，欢迎反馈问题", 12, Ui.TEXT_3, false);
        LinearLayout.LayoutParams scl = Ui.lp(-2, -2); scl.topMargin = Ui.dp(ctx, 5);
        ghText.addView(scan, scl);
        LinearLayout.LayoutParams gtl = Ui.lp(-2, -2); gtl.leftMargin = Ui.dp(ctx, 14);
        gh.addView(ghText, gtl);
        LinearLayout.LayoutParams ghl = Ui.lp(-2, -2); ghl.topMargin = Ui.dp(ctx, 26);
        left.addView(gh, ghl);
        int leftW = Math.min(Ui.dp(ctx, 340), (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.36f));
        panes.addView(left, Ui.lp(leftW, -2));

        // 右：免责声明（纯文本，一屏放下）
        LinearLayout right = Ui.column(ctx);
        right.addView(Ui.text(ctx, "免责声明", 18, Ui.TEXT, true));
        String[] paras = {
                "绿果TV 是个人开发的开源第三方客户端，与红果短剧及其关联公司无关，未获其授权或背书。",
                "剧集、封面、评论等内容均来自红果短剧公开服务，版权归原权利人所有；本应用不存储、不修改、不分发任何视频。",
                "登录凭证仅保存在本机。",
                "本项目仅供学习交流，请勿用于商业用途，使用后果由使用者自行承担。如权利人认为侵犯了合法权益，请通过 GitHub 联系，我们会及时处理。",
        };
        for (int i = 0; i < paras.length; i++) {
            LinearLayout pr = Ui.row(ctx);
            pr.setGravity(Gravity.TOP);
            TextView dot = Ui.text(ctx, String.valueOf(i + 1), 12, Ui.ACCENT_TEXT, true);
            dot.setGravity(Gravity.CENTER);
            dot.setBackground(Ui.round(ctx, Ui.TINT_PRIMARY, 10));
            LinearLayout.LayoutParams dl = Ui.lp(Ui.dp(ctx, 20), Ui.dp(ctx, 20)); dl.topMargin = Ui.dp(ctx, 2);
            pr.addView(dot, dl);
            TextView p = Ui.text(ctx, paras[i], 14, Ui.TEXT_2, false);
            p.setLineSpacing(Ui.dp(ctx, 4), 1);
            LinearLayout.LayoutParams pl = Ui.lp(0, -2, 1); pl.leftMargin = Ui.dp(ctx, 12);
            pr.addView(p, pl);
            LinearLayout.LayoutParams prl = Ui.lp(-1, -2); prl.topMargin = Ui.dp(ctx, i == 0 ? 16 : 14);
            right.addView(pr, prl);
        }
        LinearLayout.LayoutParams rl = Ui.lp(0, -2, 1); rl.leftMargin = Ui.dp(ctx, 64);
        panes.addView(right, rl);

        root.addView(panes, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    @Override public void onShow() { focusFirst(); }
    @Override public void focusFirst() { if (updateAction != null) updateAction.requestFocus(); else if (root != null) root.requestFocus(); }

    private static Bitmap qr(String text) {
        try {
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
            com.google.zxing.common.BitMatrix m = new com.google.zxing.MultiFormatWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 228, 228, hints);
            Bitmap bmp = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.RGB_565);
            for (int y = 0; y < m.getHeight(); y++) for (int x = 0; x < m.getWidth(); x++) bmp.setPixel(x, y, m.get(x, y) ? 0xFF111111 : 0xFFFFFFFF);
            return bmp;
        } catch (Exception e) { return null; }
    }
}
