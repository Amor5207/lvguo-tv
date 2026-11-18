package ink.pengfei.lvguo.net;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 备用取流通道。红果服务端调整签名校验时，本机签名取到的是空响应；
 * 这时改从自建服务取同一份播放模型——服务端的签名可以独立修复并立即生效，电视不用等发版。
 * 只传播放模型（JSON），视频仍由电视直连 CDN。
 */
final class PlayFallback {
    private PlayFallback() {}
    private static final String BASE = "https://lvguo.pengfei.ink";
    /** 密钥放在不入库的 assets/play.key（构建时打进安装包）；没有该文件时备用通道关闭。 */
    private static String key() {
        try (InputStream in = ink.pengfei.lvguo.App.ctx().getAssets().open("play.key")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8").trim();
        } catch (Exception e) { return ""; }
    }

    static String play(String vid, PlaybackConfig config) throws Exception {
        String key = key();
        if (key.isEmpty()) throw new java.io.IOException("备用通道未配置");
        String ts = Long.toString(System.currentTimeMillis() / 1000);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
        byte[] sig = mac.doFinal((vid + "|" + ts).getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : sig) hex.append(String.format("%02x", b));
        HttpURLConnection c = (HttpURLConnection) new URL(config.modelUrl + "?vid=" + vid + "&ts=" + ts + "&sig=" + hex).openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "LvguoTV");
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("备用通道 HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
