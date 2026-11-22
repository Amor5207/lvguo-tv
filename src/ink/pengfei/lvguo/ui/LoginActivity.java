package ink.pengfei.lvguo.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.net.QrLogin;
import ink.pengfei.lvguo.net.Session;
import ink.pengfei.lvguo.ui.widget.RoundFrame;

/**
 * 扫码登录页。未登录时第一屏；登录成功后进入主界面。
 * 左侧文案，右侧红果 App 扫码二维码（约 60 秒刷新一次），轮询确认状态。
 */
public class LoginActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private QrLogin login;
    private ImageView qrImage;
    private TextView hint;
    private View refreshButton;
    private volatile boolean active;
    /** 正在轮询的那一代二维码；按代记，换新码时不会被旧请求的 in-flight 状态挡住。 */
    private volatile int pollingGen = -1;
    private int generation;
    // 手机号登录
    private View qrSide, phoneForm, stepsBox;
    private android.widget.EditText phoneInput, codeInput;
    private View leftColumn, loginDesc, qrCard;
    private LinearLayout loginRow;
    private TextView sendBtn, phoneStatus, tabQr, tabPhone, loginTitle;
    private boolean loginExpired;
    private boolean phoneMode;
    private boolean startupFrameDrawn;
    private long codeSentAt;
    private final ink.pengfei.lvguo.net.lvguosign.LvguoAccount account = new ink.pengfei.lvguo.net.lvguosign.LvguoAccount();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (ink.pengfei.lvguo.care.RestMode.enforce(this)){ink.pengfei.lvguo.ui.StartupGate.drawn();return;}   // 强制休息时段内：直接进休息页
        // 已登录直接进主界面，不先建一遍登录页（启动 Activity 每次冷启动都会经过这里）
        if (Session.loggedIn()) { finishToApp(); return; }
        Ui.applyTheme(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        reportStartupDrawn();
    }

    private void reportStartupDrawn(){
        final android.view.View decor=getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){
                android.view.ViewTreeObserver observer=decor.getViewTreeObserver();
                if(observer.isAlive())observer.removeOnPreDrawListener(this);
                startupFrameDrawn=true;
                ink.pengfei.lvguo.ui.StartupGate.drawn();
                return true;
            }
        });
    }

    private TextView countdown;
    private View countdownBar, countdownTrack, scannedMask;
    private long codeIssuedAt, codeExpiresAt;
    private int autoRefreshes;
    private final Runnable ticker = new Runnable() { @Override public void run() { tick(); } };

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        // 顶部品牌条：logo + 名称 + 版本
        LinearLayout brand = Ui.row(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(ink.pengfei.lvguo.R.drawable.logo_mark);
        brand.addView(logo, Ui.lp(Ui.dp(this, 36), Ui.dp(this, 36)));
        TextView appName = Ui.text(this, "绿果TV", 20, Ui.TEXT, true);
        LinearLayout.LayoutParams an = Ui.lp(-2, -2); an.leftMargin = Ui.dp(this, 12);
        brand.addView(appName, an);
        TextView ver = Ui.text(this, "v" + version(), 13, Ui.TEXT_3, false);
        LinearLayout.LayoutParams vl = Ui.lp(-2, -2); vl.leftMargin = Ui.dp(this, 10);
        brand.addView(ver, vl);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        bl.setMargins(Ui.dp(this, 56), Ui.dp(this, 32), 0, 0);
        root.addView(brand, bl);

        LinearLayout row = Ui.row(this);
        loginRow = row;
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 左侧：标题 + 三步说明 + 安全承诺
        LinearLayout left = Ui.column(this);
        boolean expired = ink.pengfei.lvguo.net.Session.wasExpired();
        loginExpired = expired;
        TextView title = Ui.text(this, expired ? "请重新扫码登录" : "用红果 App 扫码登录", 28, Ui.TEXT, true);
        loginTitle = title;
        if (expired) {
            // 登录过期回来的：标题后面同一行一个「登录已过期」标签，说清楚发生了什么
            LinearLayout titleRow = Ui.row(this);
            titleRow.addView(title);
            TextView notice = Ui.text(this, "登录已过期", 14, 0xFFFF9F0A, true);
            Ui.padding(notice, 12, 4, 12, 4);
            notice.setBackground(Ui.round(this, 0x26FF9F0A, 14));
            LinearLayout.LayoutParams nl = Ui.lp(-2, -2); nl.leftMargin = Ui.dp(this, 14);
            titleRow.addView(notice, nl);
            left.addView(titleRow);
        } else left.addView(title);
        TextView desc = Ui.text(this, expired ? "登录已失效，请重新登录。"
                : "", 15, Ui.TEXT_2, false);
        desc.setLineSpacing(Ui.dp(this, 4), 1);
        loginDesc = desc;
        if (!expired) desc.setVisibility(View.GONE);
        LinearLayout.LayoutParams dl = Ui.lp(-1, -2); dl.topMargin = Ui.dp(this, 8);
        left.addView(desc, dl);

        // 顶部切换：扫码登录 / 手机号登录
        LinearLayout.LayoutParams tabsLp = Ui.lp(-2, -2); tabsLp.topMargin = Ui.dp(this, 20);
        left.addView(buildTabs(), tabsLp);

        stepsBox = Ui.column(this);
        fillSteps(QR_STEPS);
        left.addView(stepsBox, Ui.lp(-1, -2));



        // 右侧：二维码卡（白底，深浅主题下都能扫）+ 有效期倒计时
        LinearLayout right = Ui.column(this);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        RoundFrame card = new RoundFrame(this, 22);
        card.setBackground(Ui.round(this, 0xFFFFFFFF, 22));
        qrImage = new ImageView(this);
        qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(qrImage, new FrameLayout.LayoutParams(Ui.dp(this, 228), Ui.dp(this, 228), Gravity.CENTER));
        // 已扫码遮罩：盖在二维码上，告诉用户去手机上确认
        LinearLayout mask = Ui.column(this);
        mask.setGravity(Gravity.CENTER);
        mask.setBackgroundColor(0xF2FFFFFF);
        FrameLayout okCircle = new FrameLayout(this);
        okCircle.setBackground(Ui.round(this, Ui.PRIMARY, 28));
        IconView ok = new IconView(this, IconView.CHECK, 28);
        ok.setColor(Ui.ON_ACCENT);
        okCircle.addView(ok, new FrameLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28), Gravity.CENTER));
        mask.addView(okCircle, Ui.lp(Ui.dp(this, 56), Ui.dp(this, 56)));
        TextView m1 = Ui.text(this, "扫码成功", 20, 0xFF1C1C1E, true);
        LinearLayout.LayoutParams m1l = Ui.lp(-2, -2); m1l.topMargin = Ui.dp(this, 14);
        mask.addView(m1, m1l);
        TextView m2 = Ui.text(this, "请在手机上点「确认登录」", 14, 0xFF6E6E73, false);
        LinearLayout.LayoutParams m2l = Ui.lp(-2, -2); m2l.topMargin = Ui.dp(this, 6);
        mask.addView(m2, m2l);
        mask.setVisibility(View.GONE);
        scannedMask = mask;
        card.addView(mask, new FrameLayout.LayoutParams(-1, -1));
        right.addView(card, Ui.lp(Ui.dp(this, 264), Ui.dp(this, 264)));
        qrCard = card;

        // 二维码下方只留一行「xx 秒后刷新」（状态提示也写在这一行），不再放说明文字和进度条
        countdown = Ui.text(this, "正在获取二维码…", 14, Ui.TEXT_3, false);
        countdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cl = Ui.lp(Ui.dp(this, 264), -2); cl.topMargin = Ui.dp(this, 14);
        right.addView(countdown, cl);
        hint = countdown;
        countdownTrack = new View(this); countdownBar = new View(this);

        refreshButton = Ui.action(this, "重新获取二维码", IconView.REFRESH, false);
        refreshButton.setOnClickListener(v -> { autoRefreshes = 0; newCode(); });
        refreshButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams rl = Ui.lp(-2, Ui.dp(this, 46)); rl.topMargin = Ui.dp(this, 14);
        right.addView(refreshButton, rl);

        // 左侧垂直居中，右侧始终按自身内容垂直居中；固定左右列宽与间距，切换模式或弹出短信验证时左侧不位移
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams leftLp = Ui.lp(Ui.dp(this, 430), -2);
        leftLp.rightMargin = Ui.dp(this, 72);
        leftLp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(left, leftLp);
        leftColumn = left;
        FrameLayout rightSlot = new FrameLayout(this);
        qrSide = right;
        rightSlot.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        phoneForm = buildPhoneForm();
        phoneForm.setVisibility(View.GONE);
        rightSlot.addView(phoneForm, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        LinearLayout.LayoutParams rightLp = Ui.lp(Ui.dp(this, 380), -2);
        rightLp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(rightSlot, rightLp);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER);
        rowLp.topMargin = Ui.dp(this, 36);   // 给顶部品牌条让位，避免和标题挤在一起
        root.addView(row, rowLp);

        return root;
    }

    private View buildTabs() {
        LinearLayout box = Ui.row(this);
        box.setBackground(Ui.round(this, Ui.FILL, 20));
        Ui.padding(box, 4, 4, 4, 4);
        tabQr = tab("扫码登录", true, v -> switchMode(false));
        tabPhone = tab("手机号登录", false, v -> switchMode(true));
        box.addView(tabQr, Ui.lp(Ui.dp(this, 112), Ui.dp(this, 38)));
        box.addView(tabPhone, Ui.lp(Ui.dp(this, 112), Ui.dp(this, 38)));
        return box;
    }
    private TextView tab(String label, boolean active, View.OnClickListener click) {
        TextView t = Ui.text(this, label, 15, active ? Ui.ON_ACCENT : Ui.TEXT_2, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(active ? Ui.round(this, Ui.PRIMARY, 16) : null);
        t.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) t.setDefaultFocusHighlightEnabled(false);
        t.setOnClickListener(click);
        t.setOnFocusChangeListener((v, f) -> { if (f) click.onClick(v); paintTabs(); });
        return t;
    }
    private void paintTabs() {
        if (tabQr == null || tabPhone == null) return;
        paintTab(tabQr, !phoneMode); paintTab(tabPhone, phoneMode);
    }
    /** 选中用品牌色，焦点通过字重表达。 */
    private void paintTab(TextView t, boolean selected) {
        t.setBackground(selected ? Ui.round(this, Ui.PRIMARY, 16) : null);
        t.setTextColor(selected ? Ui.ON_ACCENT : Ui.TEXT_2);
        t.setTypeface(null, selected || t.hasFocus() ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private View buildPhoneForm() {
        LinearLayout box = Ui.column(this);
        phoneInput = input("请输入手机号", 11, android.text.InputType.TYPE_CLASS_PHONE);
        box.addView(field(phoneInput, "+86"), inputLp(0));
        LinearLayout codeRow = Ui.row(this);
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        codeInput = input("请输入验证码", 8, android.text.InputType.TYPE_CLASS_NUMBER);
        codeRow.addView(field(codeInput, null), Ui.lp(0, Ui.dp(this, 56), 1));
        sendBtn = Ui.text(this, "发送验证码", 15, Ui.PRIMARY, true);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setFocusable(true);
        paintSend(false);
        sendBtn.setOnFocusChangeListener((v, f) -> paintSend(f));
        sendBtn.setOnClickListener(v -> doSendCode());
        // 手机号输完「下一步」到验证码；验证码输完「完成」直接登录
        phoneInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        phoneInput.setOnEditorActionListener((v, a, ev) -> { codeInput.requestFocus(); return true; });
        codeInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        codeInput.setOnEditorActionListener((v, a, ev) -> { hideIme(codeInput); doLogin(); return true; });
        LinearLayout.LayoutParams sbl = Ui.lp(Ui.dp(this, 124), Ui.dp(this, 56)); sbl.leftMargin = Ui.dp(this, 10);
        codeRow.addView(sendBtn, sbl);
        box.addView(codeRow, inputLp(12));
        // 与输入框同高同圆角；未聚焦为主色淡底，聚焦时整块填充主色（与「发送验证码」一致）
        TextView loginBtn = Ui.text(this, "登录", 18, Ui.PRIMARY, true);
        loginBtn.setGravity(Gravity.CENTER);
        loginBtn.setFocusable(true);
        loginBtn.setBackground(Ui.round(this, Ui.TINT_PRIMARY, 14));
        loginBtn.setOnFocusChangeListener((v, f) -> {
            v.setBackground(Ui.round(this, f ? Ui.PRIMARY : Ui.TINT_PRIMARY, 14));
            ((TextView) v).setTextColor(f ? Ui.ON_ACCENT : Ui.PRIMARY);
        });
        loginBtn.setOnClickListener(v -> doLogin());
        box.addView(loginBtn, inputLp(16));
        phoneStatus = Ui.text(this, "", 13, Ui.TEXT_3, false);
        phoneStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        phoneStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams psl = Ui.lp(-1, -2); psl.topMargin = Ui.dp(this, 12);
        box.addView(phoneStatus, psl);
        formViews = new View[box.getChildCount()];
        for (int i = 0; i < formViews.length; i++) formViews[i] = box.getChildAt(i);
        mfaBox = buildMfaBox();
        mfaBox.setVisibility(View.GONE);
        box.addView(mfaBox, Ui.lp(-1, -2));
        return box;
    }

    // ------------------------------------------------------------ 二次验证（红果要求本人用登录手机发一条短信）
    private View[] formViews;
    private LinearLayout mfaBox;
    private View mfaQrCol;
    private TextView mfaNumber, mfaContent, mfaStatus, mfaSent;
    private ImageView mfaQr;
    private int mfaRound;

    private LinearLayout buildMfaBox() {
        LinearLayout b = Ui.column(this);
        b.addView(Ui.text(this, "需要完成短信验证", 20, Ui.TEXT, true), Ui.lp(-1, -2));
        TextView d = Ui.text(this, "用登录的手机号发送以下短信，完成后点「我已发送」", 14, Ui.TEXT_2, false);
        d.setLineSpacing(Ui.dp(this, 3), 1);
        LinearLayout.LayoutParams dl = Ui.lp(-1, -2); dl.topMargin = Ui.dp(this, 8); b.addView(d, dl);

        LinearLayout card = Ui.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.round(this, Ui.FILL, 16));
        Ui.padding(card, 16, 14, 16, 14);
        LinearLayout info = Ui.column(this);
        info.addView(Ui.text(this, "发送到", 12, Ui.TEXT_3, false));
        mfaNumber = Ui.text(this, "", 22, Ui.TEXT, true); mfaNumber.setFontFeatureSettings("tnum");
        info.addView(mfaNumber);
        TextView cl = Ui.text(this, "短信内容", 12, Ui.TEXT_3, false);
        LinearLayout.LayoutParams cll = Ui.lp(-2, -2); cll.topMargin = Ui.dp(this, 10); info.addView(cl, cll);
        mfaContent = Ui.text(this, "", 22, Ui.PRIMARY, true);
        info.addView(mfaContent);
        card.addView(info, Ui.lp(0, -2, 1));
        // 手机扫码打开网页短信引导页（与 Web 版同一套 HTTPS 入口），兼容微信/相机识别后一键跳短信。
        LinearLayout qrCol = Ui.column(this); qrCol.setGravity(Gravity.CENTER_HORIZONTAL);
        mfaQrCol = qrCol;
        RoundFrame qf = new RoundFrame(this, 10); qf.setBackground(Ui.round(this, 0xFFFFFFFF, 10));
        mfaQr = new ImageView(this);
        qf.addView(mfaQr, new FrameLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96), Gravity.CENTER));
        qrCol.addView(qf, Ui.lp(Ui.dp(this, 108), Ui.dp(this, 108)));
        LinearLayout.LayoutParams qtl = Ui.lp(-2, -2); qtl.topMargin = Ui.dp(this, 4);
        qrCol.addView(Ui.text(this, "手机扫码直接发", 11, Ui.TEXT_3, false), qtl);
        LinearLayout.LayoutParams qcl = Ui.lp(-2, -2); qcl.leftMargin = Ui.dp(this, 14); card.addView(qrCol, qcl);
        LinearLayout.LayoutParams cardLp = Ui.lp(-1, -2); cardLp.topMargin = Ui.dp(this, 14); b.addView(card, cardLp);

        LinearLayout btns = Ui.row(this);
        mfaSent = Ui.actionCentered(this, "我已发送", IconView.CHECK, true);
        mfaSent.setOnClickListener(v -> startMfaCheck());
        btns.addView(mfaSent, Ui.lp(0, Ui.dp(this, 52), 1));
        TextView cancel = Ui.action(this, "返回", -1, false);
        cancel.setOnClickListener(v -> closeMfa(null));
        LinearLayout.LayoutParams cxl = Ui.lp(Ui.dp(this, 100), Ui.dp(this, 52)); cxl.leftMargin = Ui.dp(this, 10);
        btns.addView(cancel, cxl);
        LinearLayout.LayoutParams bl = Ui.lp(-1, -2); bl.topMargin = Ui.dp(this, 14); b.addView(btns, bl);
        mfaStatus = Ui.text(this, "", 13, Ui.TEXT_3, false);
        mfaStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        mfaStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = Ui.lp(-1, -2); sl.topMargin = Ui.dp(this, 10); b.addView(mfaStatus, sl);
        return b;
    }
    private void setMfaStatus(String text) {
        if (mfaStatus == null) return;
        mfaStatus.setText(text == null ? "" : text);
        mfaStatus.setVisibility(text != null && !text.isEmpty() ? View.VISIBLE : View.GONE);
    }
    private void showMfa(ink.pengfei.lvguo.net.lvguosign.LvguoAccount.Mfa m) {
        for (View v : formViews) v.setVisibility(View.GONE);
        mfaNumber.setText(m.number); mfaContent.setText(m.content);
        android.graphics.Bitmap bmp = smsQr(m.number, m.content);
        if (mfaQrCol != null) mfaQrCol.setVisibility(bmp != null ? View.VISIBLE : View.GONE);
        mfaQr.setImageBitmap(bmp);
        mfaSent.setEnabled(true);
        setMfaStatus("");
        mfaBox.setVisibility(View.VISIBLE);
        realignLeft();
        mfaSent.requestFocus();
    }
    private void closeMfa(String message) {
        mfaRound++; account.cancelMfa();
        mfaBox.setVisibility(View.GONE);
        realignLeft();
        for (View v : formViews) v.setVisibility(View.VISIBLE);
        if (message != null) setPhoneStatus(message);
        else phoneStatus.setVisibility(phoneStatus.length() > 0 ? View.VISIBLE : View.GONE);
        codeInput.requestFocus();
    }
    /** 每 3 秒确认一次，最多等 90 秒；用户没发或服务端还没收到时保持等待。 */
    private void startMfaCheck() {
        final int round = ++mfaRound; final long deadline = System.currentTimeMillis() + 90_000;
        mfaSent.setEnabled(false); setMfaStatus("正在确认短信…");
        checkMfa(round, deadline);
    }
    private void checkMfa(final int round, final long deadline) {
        App.pool().execute(() -> {
            try {
                java.util.Map<String, String> jar = account.verifyMfa();
                ink.pengfei.lvguo.net.Account.rememberLoginDevice(account.deviceIds());
                ink.pengfei.lvguo.net.Account.rememberTtToken(account.ttToken());
                ink.pengfei.lvguo.net.Session.begin(jar);
                ui.post(() -> { if (round == mfaRound) finishToApp(); });
            } catch (ink.pengfei.lvguo.net.lvguosign.LvguoAccount.MfaPending p) {
                ui.post(() -> {
                    if (round != mfaRound) return;
                    if (System.currentTimeMillis() > deadline) { mfaSent.setEnabled(true); setMfaStatus("尚未收到短信，请确认已用登录手机发送"); return; }
                    long left = (deadline - System.currentTimeMillis()) / 1000;
                    setMfaStatus("正在等待红果确认短信…（" + left + " 秒）");
                    ui.postDelayed(() -> { if (round == mfaRound) checkMfa(round, deadline); }, 3000);
                });
            } catch (Throwable t) {
                final String fm = t.getMessage() == null ? "验证失败" : t.getMessage();
                ui.post(() -> { if (round == mfaRound) closeMfa(fm); });
            }
        });
    }
    private static String smsTarget(String number, String content) {
        if (number == null || content == null) return null;
        String cleanNumber = number.replace(" ", "");
        if (!cleanNumber.matches("\\+?[0-9]{5,20}") || content.isEmpty() || content.length() > 256) return null;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch < 0x20 || ch == 0x7f) return null;
        }
        try {
            return "https://lvguo.pengfei.ink/shared/sms.html#n="
                    + java.net.URLEncoder.encode(cleanNumber, "UTF-8")
                    + "&m=" + java.net.URLEncoder.encode(content, "UTF-8");
        } catch (Exception e) { return null; }
    }
    private static android.graphics.Bitmap smsQr(String number, String content) {
        String target = smsTarget(number, content);
        if (target == null) return null;
        try {
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
            hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
            com.google.zxing.common.BitMatrix m = new com.google.zxing.MultiFormatWriter().encode(target, com.google.zxing.BarcodeFormat.QR_CODE, 240, 240, hints);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(m.getWidth(), m.getHeight(), android.graphics.Bitmap.Config.RGB_565);
            for (int y = 0; y < m.getHeight(); y++) for (int x = 0; x < m.getWidth(); x++) bmp.setPixel(x, y, m.get(x, y) ? 0xFF111111 : 0xFFFFFFFF);
            return bmp;
        } catch (Exception e) { return null; }
    }
    private android.widget.EditText input(String hint, int maxLen, int type) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setHint(hint); e.setTextSize(18); e.setTextColor(Ui.TEXT); e.setHintTextColor(Ui.TEXT_4);
        e.setInputType(type); e.setSingleLine(true);
        e.setBackground(null);
        e.setPadding(0, 0, 0, 0);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setFocusable(true); e.setFocusableInTouchMode(true);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLen)});
        // 电视上方向键移到输入框不会自动弹键盘：按确定 / 点击时主动弹出
        e.setOnKeyListener((v, code, ev) -> {
            if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (ev.getAction() == android.view.KeyEvent.ACTION_UP) showIme(e);
                return true;
            }
            return false;
        });
        e.setOnClickListener(v -> showIme(e));
        return e;
    }
    /** 输入框外壳：聚焦时整块高亮（浅底 + 主色描边），一眼看出焦点在哪。 */
    private View field(android.widget.EditText e, String prefix) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.padding(row, 18, 0, 18, 0);
        if (prefix != null) {
            TextView p = Ui.text(this, prefix, 18, Ui.TEXT_2, true);
            row.addView(p);
            View bar = new View(this);
            bar.setBackgroundColor(Ui.FILL);
            LinearLayout.LayoutParams bl = Ui.lp(Ui.dp(this, 1), Ui.dp(this, 22)); bl.leftMargin = Ui.dp(this, 14); bl.rightMargin = Ui.dp(this, 14);
            row.addView(bar, bl);
        }
        row.addView(e, Ui.lp(0, -1, 1));
        paintField(row, false);
        e.setOnFocusChangeListener((v, f) -> paintField(row, f));
        return row;
    }
    private void paintField(View row, boolean focused) {
        row.setBackground(focused ? Ui.roundStroke(this, Ui.SURFACE_2, 14, Ui.PRIMARY, 2) : Ui.round(this, Ui.FILL, 14));
    }
    private void paintSend(boolean focused) {
        sendBtn.setBackground(Ui.round(this, focused ? Ui.PRIMARY : Ui.TINT_PRIMARY, 14));
        sendBtn.setTextColor(focused ? Ui.ON_ACCENT : Ui.PRIMARY);
    }
    private void showIme(View e) {
        e.requestFocus();
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
    }
    private void hideIme(View e) {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(e.getWindowToken(), 0);
    }
    private LinearLayout.LayoutParams inputLp(int topDp) {
        LinearLayout.LayoutParams p = Ui.lp(-1, Ui.dp(this, 56)); p.topMargin = Ui.dp(this, topDp); return p;
    }

    private static final String[] QR_STEPS = {"打开手机上的「红果免费短剧」App", "进入「我的」，点右上角扫一扫", "扫描右侧二维码，在手机上确认登录"};
    private static final String[] PHONE_STEPS = {"输入手机号，点「发送验证码」", "填写收到的短信验证码", "点「登录」；如需安全验证，按提示发一条短信"};

    /** 左侧位置固定，不随右侧高度变化而位移。 */
    private void realignLeft() {
        if (leftColumn != null) leftColumn.setTranslationY(0f);
    }

    /** 左侧三步指引：扫码与手机号两种模式各一套。 */
    private void fillSteps(String[] steps) {
        LinearLayout box = (LinearLayout) stepsBox;
        box.removeAllViews();
        for (int i = 0; i < steps.length; i++) {
            LinearLayout st = Ui.row(this);
            st.setGravity(Gravity.CENTER_VERTICAL);
            TextView num = Ui.text(this, String.valueOf(i + 1), 14, Ui.PRIMARY, true);
            num.setGravity(Gravity.CENTER);
            num.setBackground(Ui.round(this, Ui.TINT_PRIMARY, 14));
            st.addView(num, Ui.lp(Ui.dp(this, 28), Ui.dp(this, 28)));
            TextView t = Ui.text(this, steps[i], 15, Ui.TEXT, false);
            LinearLayout.LayoutParams tl = Ui.lp(-2, -2); tl.leftMargin = Ui.dp(this, 14);
            st.addView(t, tl);
            LinearLayout.LayoutParams sl = Ui.lp(-1, -2); sl.topMargin = Ui.dp(this, i == 0 ? 22 : 12);
            box.addView(st, sl);
        }
    }

    private void switchMode(boolean phone) {
        if (phoneMode == phone && qrSide != null) { paintTabs(); return; }
        phoneMode = phone;
        paintTabs();
        if (loginTitle != null) loginTitle.setText(phone ? "手机号登录" : (loginExpired ? "请重新扫码登录" : "用红果 App 扫码登录"));
        qrSide.setVisibility(phone ? View.GONE : View.VISIBLE);
        fillSteps(phone ? PHONE_STEPS : QR_STEPS);
        phoneForm.setVisibility(phone ? View.VISIBLE : View.GONE);
        if (phone) {
            generation++; pollingGen = -1; ui.removeCallbacks(ticker);
            App.pool().execute(account::warmDevice);
            if (phoneInput != null) phoneInput.requestFocus();
        } else {
            if (login == null || codeExpiresAt == 0 || codeExpiresAt <= System.currentTimeMillis()) newCode();
            else { tick(); poll(generation); }
        }
    }

    /** 手机号登录被风控挡住时，提示用户改用扫码：扫码 tab 轻微脉冲一下。 */
    private void hintQr() {
        if (tabQr == null) return;
        tabQr.animate().scaleX(1.12f).scaleY(1.12f).setDuration(180)
            .withEndAction(() -> tabQr.animate().scaleX(1f).scaleY(1f).setDuration(180).start()).start();
    }
    private void setPhoneStatus(String text) {
        if (phoneStatus == null) return;
        phoneStatus.setText(text == null ? "" : text);
        phoneStatus.setVisibility(text != null && !text.isEmpty() ? View.VISIBLE : View.GONE);
    }
    private void doSendCode() {
        String phone = phoneInput.getText().toString();
        if (!ink.pengfei.lvguo.net.lvguosign.LvguoAccount.validCN(phone)) { setPhoneStatus("请输入正确的 11 位手机号"); return; }
        if (System.currentTimeMillis() - codeSentAt < 60_000) return;
        codeSentAt = System.currentTimeMillis();
        sendBtn.setText("发送中…");
        setPhoneStatus("");
        App.pool().execute(() -> {
            String msg; boolean ok = false;
            try { account.sendCode(phone); msg = "验证码已发送"; ok = true; }
            catch (Throwable t) { msg = t.getMessage() == null ? "发送失败" : t.getMessage(); codeSentAt = 0; }
            final String fm = msg; final boolean fok = ok;
            ui.post(() -> { setPhoneStatus(fok ? fm : (fm + " · 可改用「扫码登录」")); if (!fok) { sendBtn.setText("发送验证码"); hintQr(); } else startSendCountdown(); });
        });
    }
    private void startSendCountdown() {
        Runnable[] r = new Runnable[1];
        r[0] = () -> {
            long left = 60 - (System.currentTimeMillis() - codeSentAt) / 1000;
            if (left <= 0 || phoneMode == false) { sendBtn.setText("发送验证码"); return; }
            sendBtn.setText(left + "s");
            ui.postDelayed(r[0], 1000);
        };
        r[0].run();
    }
    private void doLogin() {
        String phone = phoneInput.getText().toString(), code = codeInput.getText().toString();
        if (!ink.pengfei.lvguo.net.lvguosign.LvguoAccount.validCN(phone)) { setPhoneStatus("请输入正确的手机号"); return; }
        if (code.trim().length() < 4) { setPhoneStatus("请输入验证码"); return; }
        setPhoneStatus("登录中…");
        App.pool().execute(() -> {
            try {
                java.util.Map<String, String> jar = account.smsLogin(phone, code.trim());
                ink.pengfei.lvguo.net.Account.rememberLoginDevice(account.deviceIds());
                ink.pengfei.lvguo.net.Account.rememberTtToken(account.ttToken());
                ink.pengfei.lvguo.net.Session.begin(jar);
                ui.post(this::finishToApp);
            } catch (ink.pengfei.lvguo.net.lvguosign.LvguoAccount.MfaRequired need) {
                ui.post(() -> showMfa(need.mfa));   // 红果要求本人短信验证：引导用户完成，而不是直接失败
            } catch (Throwable t) {
                final String fm = t.getMessage() == null ? "登录失败" : t.getMessage();
                ui.post(() -> { setPhoneStatus(fm + " · 可改用「扫码登录」"); hintQr(); });
            }
        });
    }

    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    /** 每秒刷新倒计时；到期自动换一张新码（连续自动换 10 次后停下，等用户手动刷新，避免长时间空转）。 */
    private void tick() {
        ui.removeCallbacks(ticker);
        if (!active || codeExpiresAt == 0) return;
        long left = codeExpiresAt - System.currentTimeMillis();
        if (left <= 0) {
            codeExpiresAt = 0;
            generation++;   // 停止旧码轮询
            if (autoRefreshes++ < 10) { newCode(); return; }
            hint.setText("二维码已过期");
            countdown.setText("");
            countdownTrack.setVisibility(View.INVISIBLE);
            refreshButton.setVisibility(View.VISIBLE);
            refreshButton.requestFocus();
            return;
        }
        int sec = (int) ((left + 999) / 1000);
        countdown.setText(sec + " 秒后刷新");
        float frac = Math.max(0f, Math.min(1f, left / (float) Math.max(1, codeExpiresAt - codeIssuedAt)));
        countdownBar.setPivotX(0);
        countdownBar.setScaleX(frac);
        ui.postDelayed(ticker, 1000);
    }

    @Override protected void onResume() {
        super.onResume();
        if (ink.pengfei.lvguo.care.RestMode.enforce(this)) return;
        if(startupFrameDrawn)ink.pengfei.lvguo.ui.StartupGate.drawn();
        active = true;
        if (Session.loggedIn()) { finishToApp(); return; }
        if (!phoneMode) {
            if (login == null || codeExpiresAt == 0 || codeExpiresAt <= System.currentTimeMillis()) newCode();
            else { tick(); poll(generation); }   // 暂停期间轮询链已断，码还有效就接着轮询
        }
    }

    @Override protected void onPause() { super.onPause(); active = false; ui.removeCallbacks(ticker); }

    private void newCode() {
        final int gen = ++generation;
        hint.setText("正在获取二维码…");
        refreshButton.setVisibility(View.GONE);
        scannedMask.setVisibility(View.GONE);
        countdown.setText("");
        countdownTrack.setVisibility(View.INVISIBLE);
        qrImage.setAlpha(0.25f);
        App.io().execute(() -> {
            QrLogin session = new QrLogin();
            try {
                session.start();
                Bitmap bmp = BitmapFactory.decodeByteArray(session.image, 0, session.image.length);
                ui.post(() -> {
                    if (gen != generation || !active) return;
                    login = session;
                    if (bmp != null) { qrImage.setImageBitmap(bmp); qrImage.setAlpha(1f); }
                    hint.setText("请用手机红果 App 扫码");
                    codeIssuedAt = System.currentTimeMillis();
                    codeExpiresAt = Math.max(codeIssuedAt + 5000, session.expiresAt);
                    countdownTrack.setVisibility(View.VISIBLE);
                    tick();
                    poll(gen);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (gen != generation || !active) return;
                    hint.setText("二维码获取失败，请检查网络");
                    countdown.setText(e.getMessage() == null ? "" : e.getMessage());
                    refreshButton.setVisibility(View.VISIBLE);
                    refreshButton.requestFocus();
                });
            }
        });
    }

    private void poll(final int gen) {
        if (gen != generation || !active || pollingGen == gen) return;
        pollingGen = gen;
        App.io().execute(() -> {
            QrLogin.State state;
            try { state = login.poll(); }
            catch (Exception e) { state = null; }
            final QrLogin.State result = state;
            ui.post(() -> {
                if (pollingGen == gen) pollingGen = -1;
                if (gen != generation || !active) return;
                if (result == QrLogin.State.CONFIRMED) { onLoggedIn(); return; }
                if (result == QrLogin.State.SCANNED) { hint.setText("已扫码，等待手机确认"); scannedMask.setVisibility(View.VISIBLE); }
                else if (result == QrLogin.State.EXPIRED) { codeExpiresAt = System.currentTimeMillis(); tick(); return; }
                else if (result == QrLogin.State.CANCELED) { autoRefreshes = 0; hint.setText("已取消，二维码已刷新"); newCode(); return; }
                ui.postDelayed(() -> poll(gen), 1800);
            });
        });
    }

    private void onLoggedIn() {
        codeExpiresAt = 0; ui.removeCallbacks(ticker);
        hint.setText("登录成功，正在进入…");
        countdown.setText("");
        countdownTrack.setVisibility(View.INVISIBLE);
        App.io().execute(() -> {
            try { ink.pengfei.lvguo.net.Account.profile(); } catch (Exception ignored) { }
            ui.post(this::finishToApp);
        });
    }

    private void finishToApp() {
        startActivity(new android.content.Intent(this, RootActivity.class));
        finish();
    }
}
