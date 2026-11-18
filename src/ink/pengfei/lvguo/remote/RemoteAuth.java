package ink.pengfei.lvguo.remote;

import ink.pengfei.lvguo.App;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 手机遥控的开关与访问密码。
 *
 * - 总开关关闭时，遥控服务拒绝所有接口请求（电视本身照常使用）。
 * - 设了密码时，手机第一次要输入密码；验证通过后电视发一个长期授权，放在 HttpOnly Cookie 里
 *   （不进 URL），每次访问自动续期，关浏览器、重新打开收藏的链接都还能用。
 * - 授权 = 随机编号 + HMAC(电视密钥, 编号)。电视不用记每台手机，多台手机互不影响；
 *   修改或清除密码时换一把新密钥，所有旧授权立即失效。
 */
public final class RemoteAuth {
    private RemoteAuth() {}

    static final String K_ON = "remote_on", K_HASH = "remote_pwd_hash", K_SALT = "remote_pwd_salt", K_SECRET = "remote_secret";
    public static final String COOKIE = "lvguo_remote";
    /** 授权有效期（每次访问都会顺延）。 */
    static final int MAX_AGE_S = 400 * 24 * 3600;
    /** 密码：4~6 位数字（电视遥控器上方便输入）。 */
    public static final int MIN_LEN = 4, MAX_LEN = 6;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static int failures;
    private static long lockedUntil;

    // ------------------------------------------------------------ 开关与密码

    public static boolean remoteOn() { return App.prefs().getBoolean(K_ON, true); }
    public static void setRemoteOn(boolean on) { App.prefs().edit().putBoolean(K_ON, on).commit(); }

    public static boolean hasPassword() {
        String h = App.prefs().getString(K_HASH, "");
        return h != null && !h.isEmpty();
    }

    public static boolean validFormat(String pwd) {
        return pwd != null && pwd.length() >= MIN_LEN && pwd.length() <= MAX_LEN && pwd.matches("[0-9]+");
    }

    /** 设置或修改密码；同时换新密钥，所有手机的旧授权失效。 */
    public static void setPassword(String pwd) {
        if (!validFormat(pwd)) throw new IllegalArgumentException("密码需要 " + MIN_LEN + "~" + MAX_LEN + " 位数字");
        String salt = hex(random(16));
        App.prefs().edit().putString(K_SALT, salt).putString(K_HASH, hash(salt, pwd))
                .putString(K_SECRET, hex(random(32))).commit();
        synchronized (RemoteAuth.class) { failures = 0; lockedUntil = 0; }
    }

    /** 清除密码（不再需要验证）；同样换密钥让旧授权失效。 */
    public static void clearPassword() {
        App.prefs().edit().remove(K_SALT).remove(K_HASH).putString(K_SECRET, hex(random(32))).commit();
    }

    // ------------------------------------------------------------ 验证与授权

    /** 验证结果：ok 时 cookie 为新授权；locked 时 waitS 为还需等待的秒数。 */
    public static final class Login {
        public final boolean ok, locked; public final int waitS; public final String cookie;
        Login(boolean ok, boolean locked, int waitS, String cookie) { this.ok = ok; this.locked = locked; this.waitS = waitS; this.cookie = cookie; }
    }

    /** 校验密码。连续输错 5 次锁定 60 秒，之后每多错一次再锁 60 秒。 */
    public static Login login(String pwd) {
        long now = System.currentTimeMillis();
        synchronized (RemoteAuth.class) {
            if (now < lockedUntil) return new Login(false, true, (int) ((lockedUntil - now + 999) / 1000), null);
        }
        boolean match = hasPassword() && pwd != null
                && constantEquals(hash(App.prefs().getString(K_SALT, ""), pwd), App.prefs().getString(K_HASH, ""));
        synchronized (RemoteAuth.class) {
            if (!match) {
                failures++;
                if (failures >= 5) lockedUntil = now + 60_000L;
                return new Login(false, failures >= 5, failures >= 5 ? 60 : 0, null);
            }
            failures = 0; lockedUntil = 0;
        }
        return new Login(true, false, 0, issue());
    }

    /** 新发一个授权令牌。 */
    static String issue() {
        String id = hex(random(12));
        return id + "." + sign(id);
    }

    /** 这个请求带的 Cookie 头里有没有有效授权。 */
    public static boolean authorized(String cookieHeader) {
        String token = cookieValue(cookieHeader, COOKIE);
        if (token == null) return false;
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return false;
        return constantEquals(sign(token.substring(0, dot)), token.substring(dot + 1));
    }

    /** 访问是否放行：没设密码 = 放行；设了密码则要有有效授权。 */
    public static boolean allowed(String cookieHeader) { return !hasPassword() || authorized(cookieHeader); }

    /** 续期用的 Set-Cookie 值（同一个令牌，有效期从现在顺延）。 */
    public static String renewCookie(String cookieHeader) {
        String token = cookieValue(cookieHeader, COOKIE);
        return token == null ? null : setCookie(token);
    }
    public static String setCookie(String token) {
        return COOKIE + "=" + token + "; Max-Age=" + MAX_AGE_S + "; Path=/; HttpOnly; SameSite=Strict";
    }

    // ------------------------------------------------------------ 工具

    static String cookieValue(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) {
                String v = p.substring(name.length() + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private static String secret() {
        String s = App.prefs().getString(K_SECRET, "");
        if (s == null || s.isEmpty()) {
            s = hex(random(32));
            App.prefs().edit().putString(K_SECRET, s).commit();
        }
        return s;
    }

    static String sign(String id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(id.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String hash(String salt, String pwd) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] out = (salt + ":" + pwd).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 20000; i++) out = d.digest(out);   // 拉长计算，降低离线猜数字密码的速度
            return hex(out);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] random(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(java.util.Locale.US, "%02x", x & 255));
        return s.toString();
    }
}
