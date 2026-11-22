package ink.pengfei.lvguo.net;

import ink.pengfei.lvguo.App;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录会话：红果网页扫码拿到的 cookie（sessionid / sid_guard / sid_tt …），App 通道同样认可。
 *
 * - cookie 持久化在 prefs，进程重启后免登录；
 * - sid_guard = "sid|签发时间|有效秒数|到期日"，据此知道还剩多久；
 * - 服务端在任何响应里回写的 Set-Cookie 都会被吸收（心跳续期就是靠这个把到期时间往后推）。
 */
public final class Session {
    private Session() {}

    private static final String KEY_COOKIES = "acct_cookies", KEY_PROFILE = "acct_profile";
    private static final String KEYSTORE_ALIAS = "lvguo_session_aes_v1", ENC_PREFIX = "enc:v1:";
    private static Map<String, String> jar;
    private static Profile profile;
    private static volatile long generation;
    public static long generation() { return generation; }
    private static final List<Runnable> listeners = new ArrayList<>();

    /** 账号资料（user/info）。 */
    public static final class Profile {
        public String uid = "", name = "", avatar = "", cover = "", coverColor = "", signature = "";
        public boolean vip; public long vipExpire; public int gender;
        public long watchMs; public int activeDays;
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("uid", uid).put("name", name).put("avatar", avatar).put("cover", cover).put("coverColor", coverColor)
                        .put("signature", signature).put("vip", vip).put("vipExpire", vipExpire).put("gender", gender)
                        .put("watchMs", watchMs).put("activeDays", activeDays);
            } catch (Exception ignored) { }
            return o;
        }
        static Profile from(JSONObject o) {
            Profile p = new Profile();
            if (o == null) return p;
            p.uid = o.optString("uid"); p.name = o.optString("name"); p.avatar = o.optString("avatar");
            p.cover = o.optString("cover"); p.coverColor = o.optString("coverColor"); p.signature = o.optString("signature");
            p.vip = o.optBoolean("vip"); p.vipExpire = o.optLong("vipExpire"); p.gender = o.optInt("gender");
            p.watchMs = o.optLong("watchMs"); p.activeDays = o.optInt("activeDays");
            return p;
        }
    }

    // ------------------------------------------------------------ cookie

    private static synchronized Map<String, String> jar() {
        if (jar != null) return jar;
        jar = new LinkedHashMap<>();
        try {
            String stored = App.prefs().getString(KEY_COOKIES, "{}");
            boolean wasPlaintext = stored != null && !stored.isEmpty() && !stored.startsWith(ENC_PREFIX) && !"{}".equals(stored);
            String json = decryptCookies(stored);
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); jar.put(k, o.getString(k)); }
            if (wasPlaintext && !jar.isEmpty()) save();
        } catch (Exception ignored) { }
        return jar;
    }

    private static void save() {
        JSONObject o = new JSONObject();
        try { for (Map.Entry<String, String> e : jar().entrySet()) o.put(e.getKey(), e.getValue()); } catch (Exception ignored) { }
        App.prefs().edit().putString(KEY_COOKIES, encryptCookies(o.toString())).apply();
    }

    private static javax.crypto.SecretKey sessionSecretKey() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            java.security.Key k = ks.getKey(KEYSTORE_ALIAS, null);
            if (k instanceof javax.crypto.SecretKey) return (javax.crypto.SecretKey) k;
        }
        javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static String encryptCookies(String plain) {
        if (plain == null || plain.isEmpty() || "{}".equals(plain)) return "{}";
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, sessionSecretKey());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return ENC_PREFIX + android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
        } catch (Throwable ignored) {
            // 部分老电视 ROM 的 KeyStore 实现可能不可用，降级回明文而不影响登录。
            return plain;
        }
    }

    private static String decryptCookies(String stored) {
        if (stored == null || stored.isEmpty()) return "{}";
        if (!stored.startsWith(ENC_PREFIX)) return stored;
        try {
            byte[] raw = android.util.Base64.decode(stored.substring(ENC_PREFIX.length()), android.util.Base64.NO_WRAP);
            if (raw.length <= 12) return "{}";
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, sessionSecretKey(), new javax.crypto.spec.GCMParameterSpec(128, raw, 0, 12));
            byte[] plain = cipher.doFinal(raw, 12, raw.length - 12);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    public static synchronized boolean loggedIn() {
        Map<String, String> j = jar();
        return j.containsKey("sessionid") || j.containsKey("sessionid_ss");
    }

    public static synchronized String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar().entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static synchronized String cookieHeaderForGeneration(long expected) throws java.io.IOException {
        if (generation != expected) throw new java.io.IOException("会话已切换");
        return cookieHeader();
    }

    /** 吸收一条 Set-Cookie。返回是否改动了登录票（sessionid / sid_guard）。 */
    public static synchronized boolean absorb(String setCookie) {
        if (setCookie == null) return false;
        String first = setCookie.split(";", 2)[0];
        int eq = first.indexOf('=');
        if (eq <= 0) return false;
        String name = first.substring(0, eq).trim(), value = first.substring(eq + 1).trim();
        if (value.isEmpty() || value.equalsIgnoreCase("deleted")) return false;
        String old = jar().put(name, value);
        save();
        return !value.equals(old) && (name.startsWith("sessionid") || name.equals("sid_guard") || name.equals("sid_tt"));
    }

    public static synchronized boolean absorbForGeneration(String cookie, long expected) {
        return generation == expected && absorb(cookie);
    }

    /** 登录成功：整体替换 cookie，并清掉「已过期」标记。 */
    public static void begin(Map<String, String> cookies) {
        // Store operations may inspect Session while holding the Store monitor; use the
        // same lock order here so stale cloud results cannot land between the account
        // change and clearing the old account's local library.
        synchronized (ink.pengfei.lvguo.store.Store.class) {
            synchronized (Session.class) {
                generation++;
                // Persist removal before storing the next account's credentials, so
                // interruption cannot restore a new login alongside the old library.
                ink.pengfei.lvguo.store.Store.clearAccountData();
                jar = new LinkedHashMap<>(cookies);
                profile = null;
                save();
                App.prefs().edit().remove(KEY_PROFILE).remove(KEY_EXPIRED).apply();
            }
        }
        notifyChanged();
    }

    private static final String KEY_EXPIRED = "login_expired";
    /** 服务端确认登录已失效：记下原因再退出，扫码页据此提示「登录已过期」。 */
    public static void expire() {
        expireIfCurrent(generation());
    }
    public static void expireIfCurrent(long expected) {
        endSession(expected, true);
    }
    /** 上次是不是因为过期才回到扫码页。 */
    public static boolean wasExpired() { return App.prefs().getBoolean(KEY_EXPIRED, false); }

    public static void logout() {
        endSession(null, false);
    }
    private static void endSession(Long expected, boolean expired) {
        synchronized (ink.pengfei.lvguo.store.Store.class) {
            synchronized (Session.class) {
                if (expected != null && generation != expected) return;
                generation++;
                ink.pengfei.lvguo.store.Store.clearAccountData();
                jar = new LinkedHashMap<>();
                profile = null;
                android.content.SharedPreferences.Editor editor = App.prefs().edit().remove(KEY_COOKIES).remove(KEY_PROFILE).remove("acct_tt_token");
                if (expired) editor.putBoolean(KEY_EXPIRED, true);
                editor.apply();
            }
        }
        notifyChanged();
    }

    /** 会话到期时间（毫秒）；未知返回 0。 */
    public static synchronized long expiresAt() {
        String guard = jar().get("sid_guard");
        if (guard == null) return 0;
        try {
            String[] parts = java.net.URLDecoder.decode(guard, "UTF-8").split("\\|");
            if (parts.length >= 3) return (Long.parseLong(parts[1]) + Long.parseLong(parts[2])) * 1000L;
        } catch (Exception ignored) { }
        return 0;
    }

    /** 剩余天数（未知返回 -1）。 */
    public static int daysLeft() {
        long at = expiresAt();
        if (at <= 0) return -1;
        return (int) Math.max(0, (at - System.currentTimeMillis()) / 86_400_000L);
    }

    // ------------------------------------------------------------ 资料

    public static synchronized Profile profile() {
        if (profile == null) {
            try { profile = Profile.from(new JSONObject(App.prefs().getString(KEY_PROFILE, "{}"))); }
            catch (Exception e) { profile = new Profile(); }
        }
        return profile;
    }

    public static synchronized void setProfileIfCurrent(Profile p, long expected) {
        if (generation == expected) setProfile(p);
    }

    public static void setProfile(Profile p) {
        synchronized (Session.class) {
            profile = p;
            App.prefs().edit().putString(KEY_PROFILE, p.toJson().toString()).apply();
        }
        notifyChanged();
    }

    // ------------------------------------------------------------ 监听

    public static void listen(Runnable r) { synchronized (listeners) { listeners.add(r); } }
    public static void unlisten(Runnable r) { synchronized (listeners) { listeners.remove(r); } }
    static void notifyChanged() {
        List<Runnable> copy;
        synchronized (listeners) { copy = new ArrayList<>(listeners); }
        for (Runnable r : copy) try { r.run(); } catch (RuntimeException ignored) { }
    }
}
