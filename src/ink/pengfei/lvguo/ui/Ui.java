package ink.pengfei.lvguo.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import ink.pengfei.lvguo.App;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设计系统：颜色 / 尺寸 / 形状 / 文字 / 聚焦状态。
 *
 * TV 端采用 dp 布局，卡片随可用宽度调整，保持遥控器焦点清晰可见。
 * 所有颜色集中在这里，避免散落硬编码。
 */
public final class Ui {
    /** Consume the entire BACK gesture inside the top dialog, including key repeats. */
    public static void closeOnBack(android.app.Dialog dialog){
        final boolean[] pressed={false};
        final long openedAt=android.os.SystemClock.uptimeMillis();
        dialog.setOnKeyListener((d,key,event)->{
            // 返回键：标准 BACK 之外，部分投影/盒子遥控器的返回发的是 ESC 或手柄 B 键
            if(key!=android.view.KeyEvent.KEYCODE_BACK && key!=android.view.KeyEvent.KEYCODE_ESCAPE && key!=android.view.KeyEvent.KEYCODE_BUTTON_B)return false;
            // An event from the gesture that opened this dialog must not close it.
            if(event.getDownTime()<openedAt)return true;
            if(event.getAction()==android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)pressed[0]=true;
            if(event.getAction()==android.view.KeyEvent.ACTION_UP){
                boolean close=pressed[0] && !event.isCanceled();pressed[0]=false;
                if(close)dialog.dismiss();
            }
            return true;
        });
    }

    // ========================================================================
    //  主题 / 调色板
    //  颜色不再是 final —— 它们是「当前主题」的快照，由 applyTheme() 从深色 /
    //  浅色两套调色板填充。所有页面在 build() 时读这些字段，切主题后 Activity
    //  recreate()，视图整棵重建即完成换肤。单一数据源，避免散落硬编码。
    //
    //  命名沿用旧名（BG/SURFACE/PRIMARY/TEXT…）以兼容既有 184 处调用；
    //  另按 Apple HIG 语义补充别名（LABEL/SEPARATOR/FILL/GROUPED_BG…）。
    //  品牌绿分两个角色：PRIMARY 作填充/描边（亮绿），ACCENT_TEXT 作强调文字
    //  （深底用亮绿、浅底压深到对比达标），ON_ACCENT 是落在 PRIMARY 填充上的墨色。
    // ========================================================================

    public static final int MODE_SYSTEM = 0, MODE_LIGHT = 1, MODE_DARK = 2, MODE_SCHEDULE = 3;
    /** TV 默认浅色（白天用深色太暗）；晚上想要深色可选「按时间段」。 */
    private static final int DEFAULT_MODE = MODE_LIGHT;
    /** 旧版本的暗色时间段预设（起始小时, 结束小时）：只用于把老设置迁移成自由时分。 */
    private static final int[][] LEGACY_DARK_WINDOWS = {{18, 7}, {19, 7}, {20, 7}, {21, 7}, {22, 6}};
    /** 深色时间段默认 19:00 – 次日 07:00（存「一天中的第几分钟」，可设任意时分）。 */
    private static final int DEFAULT_DARK_START = 19 * 60, DEFAULT_DARK_END = 7 * 60;
    /** 「按时间段」的结果在一次运行里只算一次：用着用着到点了也不切换，下次启动才按新时间生效。 */
    private static Boolean scheduledDark;
    public static int currentMode = DEFAULT_MODE;
    public static boolean isDark = true;

    // ---- 背景层
    public static int BG = 0xFF0D1110;
    public static int BG_RAIL = 0xFF0D1110;
    public static int GROUPED_BG = 0xFF0D1110;
    public static int SURFACE = 0xFF1A1F1D;
    public static int SURFACE_2 = 0xFF242B28;
    public static int SURFACE_3 = 0xFF2F3833;
    public static int ELEVATED = 0xFF1A1F1D;

    // ---- 填充 / 分隔（iOS systemFill / separator 语义）
    public static int FILL = 0x1FFFFFFF;
    public static int FILL_2 = 0x14FFFFFF;
    public static int SEPARATOR = 0x1FFFFFFF;

    // ---- 强调色
    public static int PRIMARY = 0xFFC6ED92;        // 品牌绿：填充 / 聚焦描边 / 进度
    public static int PRIMARY_DIM = 0xFF9CBF6D;
    public static int ACCENT_TEXT = 0xFFC6ED92;    // 可读的强调文字（浅色主题会压深）
    public static int TINT_PRIMARY = 0x24C6ED92;   // 强调色淡洗底
    public static int ON_ACCENT = 0xFF14200B;      // 落在 PRIMARY 填充上的墨色
    public static int ACCENT = 0xFFB8D9CE;         // 次强调（青瓷）
    public static int GOLD = 0xFFE8B65A;           // 仅用于榜单名次

    // ---- 文字（label 四级）
    public static int TEXT = 0xFFF5F7F6;
    public static int TEXT_2 = 0xFFBFC8C4;
    public static int TEXT_3 = 0xFF8A938F;
    public static int TEXT_4 = 0xFF6E756F;

    // ---- 骨架屏占位块
    public static int SKELETON = 0xFF252929;

    // ---- tvOS 焦点语言：聚焦元素变成实心高亮（深色主题白底黑字，浅色主题黑底白字）
    public static int FOCUS_FILL = 0xFFF5F5F7;
    public static int ON_FOCUS = 0xFF111113;
    public static int FOCUS_RING = 0xFFFFFFFF;

    // ---- 尺寸
    /** 导航栏收起宽度（内容区左边距）；聚焦导航时展开到 RAIL_OPEN_W 覆盖在内容上。 */
    public static final int RAIL_W = 108;
    public static final int RAIL_OPEN_W = 252;
    /** TV 海报卡片使用适合当前六列布局的紧凑尺寸，横向行也能一次看到更多卡片。 */
    public static final int CARD_W = 104;
    public static final int CARD_H = 130;
    /** 网格按可用宽度计算列数，不设列数上限。 */
    public static final int GRID_CARD_W = CARD_W;
    public static final int GRID_CARD_H = CARD_H;

    private Ui() {}

    // ------------------------------------------------------------ 主题切换

    /** 读持久化的主题模式（默认浅色）。 */
    public static int savedMode() {
        return App.prefs().getInt("theme_mode", DEFAULT_MODE);
    }

    /** 老版本存的是预设编号 dark_window：第一次读取时换算成开始 / 结束分钟数，用户原来的选择不丢。 */
    private static void migrateDarkWindow() {
        android.content.SharedPreferences p = App.prefs();
        if (p.contains("dark_start") || !p.contains("dark_window")) return;
        int i = Math.max(0, Math.min(LEGACY_DARK_WINDOWS.length - 1, p.getInt("dark_window", 1)));
        p.edit().putInt("dark_start", LEGACY_DARK_WINDOWS[i][0] * 60).putInt("dark_end", LEGACY_DARK_WINDOWS[i][1] * 60)
                .remove("dark_window").apply();
    }
    public static int darkStart() { migrateDarkWindow(); return App.prefs().getInt("dark_start", DEFAULT_DARK_START); }
    public static int darkEnd() { migrateDarkWindow(); return App.prefs().getInt("dark_end", DEFAULT_DARK_END); }
    public static String darkWindowLabel() { return timeRange(darkStart(), darkEnd()); }

    /** 「21:30」这样的时间（参数是一天中的第几分钟）。 */
    public static String clock(int minute) {
        minute = ((minute % 1440) + 1440) % 1440;
        return String.format(java.util.Locale.US, "%02d:%02d", minute / 60, minute % 60);
    }
    /** 时间段文字：结束早于（或等于）开始时表示跨到第二天，例如「21:30 – 次日 07:00」。 */
    public static String timeRange(int start, int end) {
        return clock(start) + " – " + (end <= start ? "次日 " : "") + clock(end);
    }

    /** 用户主动改了时间段：重新计算（这是用户的操作，不算「使用中自动切换」）。 */
    public static void saveDarkWindow(int start, int end) {
        App.prefs().edit().putInt("dark_start", ((start % 1440) + 1440) % 1440).putInt("dark_end", ((end % 1440) + 1440) % 1440)
                .remove("dark_window").apply();
        scheduledDark = null;
    }
    private static boolean inDarkWindow() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        int s = darkStart(), e = darkEnd();
        if (s == e) return false;
        return s > e ? (m >= s || m < e) : (m >= s && m < e);
    }

    public static void saveMode(int mode) {
        scheduledDark = null;
        App.prefs().edit().putInt("theme_mode", mode).apply();
    }

    /** 把三态模式解析成「是否深色」。跟随系统时读 uiMode 的夜间标志（未知时默认深色）。 */
    public static boolean resolveDark(Context c, int mode) {
        if (mode == MODE_LIGHT) return false;
        if (mode == MODE_DARK) return true;
        if (mode == MODE_SCHEDULE) {
            if (scheduledDark == null) scheduledDark = inDarkWindow();
            return scheduledDark;
        }
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night != Configuration.UI_MODE_NIGHT_NO;
    }

    /** 在建视图之前调用：按持久化模式把调色板装入上面的字段。 */
    public static void applyTheme(Context c) {
        currentMode = savedMode();
        isDark = resolveDark(c, currentMode);
        if (isDark) loadDark(); else loadLight();
    }

    /**
     * 强制深色调色板，不改持久化模式。给始终 on-dark 的界面用（播放器叠在黑底视频上，
     * 浅色文字才看得见）。返回这类界面后，承载页应在 onResume 里重新 applyTheme() 恢复。
     */
    public static void applyDark() {
        isDark = true;
        loadDark();
    }

    /** tvOS 风深色：近黑背景 + 分层 surface + 亮品牌绿。 */
    private static void loadDark() {
        BG = 0xFF0D1110;  BG_RAIL = 0xFF0D1110;  GROUPED_BG = 0xFF0D1110;
        SURFACE = 0xFF1A1F1D;  SURFACE_2 = 0xFF242B28;  SURFACE_3 = 0xFF2F3833;
        ELEVATED = 0xFF1A1F1D;
        FILL = 0x1FFFFFFF;  FILL_2 = 0x14FFFFFF;  SEPARATOR = 0x1FFFFFFF;
        PRIMARY = 0xFFC6ED92;  PRIMARY_DIM = 0xFF9CBF6D;  ACCENT_TEXT = 0xFFC6ED92;
        TINT_PRIMARY = 0x24C6ED92;  ON_ACCENT = 0xFF14200B;
        ACCENT = 0xFFB8D9CE;  GOLD = 0xFFE8B65A;
        TEXT = 0xFFF5F7F6;  TEXT_2 = 0xFFBFC8C4;  TEXT_3 = 0xFF8A938F;  TEXT_4 = 0xFF6E756F;
        SKELETON = 0xFF252929;
        // 聚焦统一用主题色：亮绿实底 + 深色字；卡片用亮绿描边
        FOCUS_FILL = PRIMARY;  ON_FOCUS = ON_ACCENT;  FOCUS_RING = PRIMARY;
    }

    /** iOS 系统浅色：分组灰底 + 纯白卡面 + 压深的品牌绿。 */
    private static void loadLight() {
        BG = 0xFFF2F2F7;  BG_RAIL = 0xFFF7F7FB;  GROUPED_BG = 0xFFF2F2F7;
        SURFACE = 0xFFFFFFFF;  SURFACE_2 = 0xFFF2F3F5;  SURFACE_3 = 0xFFE7E8EC;
        ELEVATED = 0xFFFFFFFF;
        FILL = 0x14000000;  FILL_2 = 0x0A000000;  SEPARATOR = 0x333C3C43;
        // 填充用稍深的品牌绿（纯 lime 落白底会糊），墨色用白；强调文字用更深的叶绿达 AA。
        // 浅色：取自 Logo 的鲜亮叶绿，不再用偏沉的橄榄绿。
        PRIMARY = 0xFF2DB35A;  PRIMARY_DIM = 0xFF6CCB86;  ACCENT_TEXT = 0xFF1C9A47;
        TINT_PRIMARY = 0x242DB35A;  ON_ACCENT = 0xFFFFFFFF;
        ACCENT = 0xFF3C7A6B;  GOLD = 0xFFB07A14;
        TEXT = 0xFF1C1C1E;  TEXT_2 = 0xFF55555A;  TEXT_3 = 0xFF8A8A8E;  TEXT_4 = 0xFFB0B0B5;
        SKELETON = 0xFFE3E3E8;
        FOCUS_FILL = PRIMARY;  ON_FOCUS = ON_ACCENT;  FOCUS_RING = PRIMARY;
    }

    /** 把一个不透明 RGB 色套上指定 8-bit alpha（高位），用于半透明叠层随主题走。 */
    public static int alpha(int rgb, int a) {
        return (rgb & 0x00FFFFFF) | (a << 24);
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int dp(View v, float val) {
        return dp(v.getContext(), val);
    }

    // ------------------------------------------------------------ 形状

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(Context c, int color, float radiusDp,
                                               int strokeColor, float strokeDp) {
        GradientDrawable d = round(c, color, radiusDp);
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    public static GradientDrawable focusRing(Context c, boolean hot) {
        // 深色底 + 高亮描边；不加高光的纯色更耐看
        return roundStroke(c, hot ? SURFACE_2 : 0x00000000, 12,
                hot ? PRIMARY : 0x00000000, 2);
    }

    /** 顶部到底部的黑色渐变遮罩（用于 Hero 文字可读性） */
    public static GradientDrawable scrimBottom(Context c) {
        return new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF20B0E11, 0xCC0B0E11, 0x400B0E11, 0x000B0E11});
    }

    public static GradientDrawable scrimLeft(Context c) {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF20B0E11, 0xE00B0E11, 0x800B0E11, 0x000B0E11});
    }

    public static GradientDrawable pill(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, 100));
        return d;
    }

    // ------------------------------------------------------------ 文字

    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s == null ? "" : s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(android.graphics.Typeface.defaultFromStyle(bold?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLineSpacing(dp(c, 5), 1f);
        return t;
    }

    public static TextView ellipsis(Context c, String s, float sizeSp, int color, int maxLines) {
        TextView t = text(c, s, sizeSp, color, false);
        t.setMaxLines(maxLines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static void icon(TextView view, int type, int color, int size) {
        IconDrawable drawable = new IconDrawable(type, color);
        int px = dp(view, size);
        drawable.setBounds(0, 0, px, px);
        view.setCompoundDrawables(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(view, 8));
    }

    /** Keep a button's pictogram and label in the same foreground color in every state. */
    private static final class ActionText extends TextView {
        boolean centerGroup;   // true：图标+文字作为一组整体居中（而不是图标贴最左、文字单独居中）
        ActionText(Context context){super(context);}
        @Override public void setTextColor(int color){super.setTextColor(color);tintIcons(this,color);}
        @Override public void setCompoundDrawables(android.graphics.drawable.Drawable left,android.graphics.drawable.Drawable top,android.graphics.drawable.Drawable right,android.graphics.drawable.Drawable bottom){
            super.setCompoundDrawables(left,top,right,bottom);tintIcons(this,getCurrentTextColor());
        }
        @Override protected void onDraw(android.graphics.Canvas canvas){
            android.graphics.drawable.Drawable[] d=getCompoundDrawables();
            if(centerGroup && d[0]!=null && getText()!=null){
                float textW=getPaint().measureText(getText().toString());
                float content=d[0].getIntrinsicWidth()+getCompoundDrawablePadding()+textW;
                float avail=getWidth()-getPaddingLeft()-getPaddingRight();
                float shift=Math.max(0,(avail-content)/2f);
                if(shift>0){canvas.save();canvas.translate(shift,0);super.onDraw(canvas);canvas.restore();return;}
            }
            super.onDraw(canvas);
        }
    }
    /** 图标紧挨文字、整组居中的动作按钮（手机验证码登录用）。 */
    public static TextView actionCentered(Context c, String label, int type, boolean primary) {
        TextView v = action(c, label, type, primary);
        if (v instanceof ActionText) ((ActionText) v).centerGroup = true;
        v.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);   // 文字左对齐紧贴图标，整组由 onDraw 居中
        return v;
    }
    public static void tintIcons(TextView view,int color){
        for(android.graphics.drawable.Drawable drawable:view.getCompoundDrawables())if(drawable instanceof IconDrawable)((IconDrawable)drawable).setColor(color);
    }
    public static void buttonState(TextView view,boolean focused,boolean selected){
        Context c=view.getContext();
        if(focused||view.getScaleX()!=1f)focus(view);   // 按钮聚焦不缩放
        view.setBackground(pill(c,focused?FOCUS_FILL:selected?TINT_PRIMARY:FILL_2));
        int foreground=focused?ON_FOCUS:selected?ACCENT_TEXT:TEXT;
        view.setTextColor(foreground);tintIcons(view,foreground);
        view.setTypeface(android.graphics.Typeface.defaultFromStyle(focused||selected?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL));
    }
    /**
     * 可选中块的统一画法（选集格子、页签、筛选胶囊、键盘键…）：
     * 静止=淡底+正文色；选中=品牌色淡底+品牌色粗体；聚焦=实心高亮+反色粗体。
     */
    public static void tile(TextView v, boolean focused, boolean selected, float radiusDp) {
        Context c = v.getContext();
        v.setBackground(round(c, focused ? FOCUS_FILL : selected ? TINT_PRIMARY : FILL_2, radiusDp));
        v.setTextColor(focused ? ON_FOCUS : selected ? ACCENT_TEXT : TEXT);
        v.setTypeface(android.graphics.Typeface.defaultFromStyle(focused || selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        tintIcons(v, v.getCurrentTextColor());
    }

    public static TextView action(Context c, String label, int type, boolean primary) {
        TextView view = new ActionText(c);view.setText(label);view.setTextSize(14);view.setIncludeFontPadding(false);
        view.setTypeface(android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.NORMAL));
        view.setSingleLine(true);view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setMinHeight(dp(c, 44));
        padding(view, 20, 0, 20, 0);
        if(type>=0)icon(view, type, TEXT, 18);else view.setCompoundDrawables(null,null,null,null);
        buttonState(view,false,false);
        view.setOnFocusChangeListener((v, has) -> {
            buttonState(view,has,false);
        });
        return view;
    }

    // ------------------------------------------------------------ 聚焦状态

    /** 统一清理焦点变换状态；焦点只通过描边、实底和标题字重表达，不放大或浮起。 */
    public static void focus(View v) {
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationZ(0f);
    }

    /** 是否在弹窗（而不是 Activity 主窗口）里。 */
    private static boolean inDialog(View v) {
        Context c = v.getContext();
        while (c instanceof android.content.ContextWrapper && !(c instanceof android.app.Activity)) c = ((android.content.ContextWrapper) c).getBaseContext();
        return c instanceof android.app.Activity && v.getRootView() != ((android.app.Activity) c).getWindow().getDecorView();
    }

    /** 页面进入：从 (dx, dy) dp 处淡入滑到原位。 */
    public static void enter(View v, float dxDp, float dyDp, long ms) {
        v.animate().cancel();
        v.setAlpha(0f); v.setTranslationX(dp(v.getContext(), (int) dxDp)); v.setTranslationY(dp(v.getContext(), (int) dyDp));
        v.animate().alpha(1f).translationX(0).translationY(0).setDuration(ms).withLayer()   // 硬件层：低配机也顺滑
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
    }

    /** 弹窗出现：从 94% 放大到 100% 并淡入。 */
    public static void popIn(View v) {
        v.setAlpha(0f); v.setScaleX(.94f); v.setScaleY(.94f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).withLayer()
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
    }

    // ------------------------------------------------------------ 常用容器

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View spacer(Context c) {
        return new View(c);
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    /** 加一层点击/聚焦都无反馈的透明层（用于占位） */
    public static View blank(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
    }

    public static void hide(View v) {
        if (v != null) v.setVisibility(View.GONE);
    }

    public static void show(View v) {
        if (v != null) v.setVisibility(View.VISIBLE);
    }

    public static void padding(View v, float l, float t, float r, float b) {
        v.setPadding(dp(v, l), dp(v, t), dp(v, r), dp(v, b));
    }

    public static void margin(View v, float l, float t, float r, float b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) p;
            m.setMargins(dp(v, l), dp(v, t), dp(v, r), dp(v, b));
            v.setLayoutParams(m);
        }
    }
}
