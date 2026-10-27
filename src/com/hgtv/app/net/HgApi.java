package com.hgtv.app.net;

import com.hgtv.app.App;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 红果内容接口客户端。
 *
 * 榜单、详情和播放器接口复用这组设备参数；是否接受请求由具体接口决定。
 * 搜索使用 OnlineSearch 的官方公开分页数据，不依赖这里的空响应端点。
 */
public final class HgApi {

    public static final String HOST = "api5-normal-sinfonlinea.fqnovel.com";

    /** 播放编码开关：>=4 时全部档位下发标准 H.265，否则低档位是字节私有 ByteVC2（系统解不了） */
    public static final int DEVICE_LEVEL_H265 = 4;

    private static final int TIMEOUT_CONNECT = 8000;
    private static final int TIMEOUT_READ = 12000;

    private HgApi() {}

    // ------------------------------------------------------------ 基础请求

    private static Map<String, String> query() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("aid", "8662");
        q.put("app_id", "8662");
        q.put("device_platform", "android");
        q.put("platform", "android");
        q.put("version_code", "74132");
        q.put("update_version_code", "74132");
        q.put("manifest_version_code", "74132");
        q.put("version_name", "7.4.1.32");
        q.put("device_type", "22041211AC");
        q.put("device_brand", "Redmi");
        q.put("cdid", App.cdid);
        q.put("device_id", App.deviceId);
        q.put("iid", App.iid);
        q.put("os", "android");
        q.put("os_version", "13");
        q.put("os_api", "33");
        q.put("language", "zh");
        q.put("ac", "wifi");
        q.put("ssmix", "a");
        return q;
    }

    public static JSONObject call(String path, JSONObject body, Map<String, String> extra)
            throws Exception {
        Exception error=null;JSONObject last=null;
        for(int attempt=0;attempt<2;attempt++){
            try{
                last=callOnce(path,body,extra,attempt==0?HOST:"api3-normal-sinfonlinea.fqnovel.com");
                if(!last.optBoolean("_empty") && last.optInt("_http_status",200)<500 && last.optInt("code",0)!=101116)return last;
            }catch(java.io.IOException e){error=e;}
        }
        if(last!=null)return last;
        throw error==null?new java.io.IOException("网络请求未完成"):error;
    }
    private static JSONObject callOnce(String path,JSONObject body,Map<String,String> extra,String host)throws Exception {
        Map<String, String> q = query();
        if (extra != null) q.putAll(extra);
        // _rticket 与 header 里的 x-ss-req-ticket 必须是同一个值：
        // 用两次 currentTimeMillis 会差几毫秒，服务端会当作异常请求直接返回空包。
        final String now = String.valueOf(System.currentTimeMillis());
        q.put("_rticket", now);

        StringBuilder sb = new StringBuilder("https://").append(host).append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }

        byte[] data = body == null ? null : body.toString().getBytes("UTF-8");

        HttpURLConnection c = (HttpURLConnection) new URL(sb.toString()).openConnection();
        c.setRequestMethod(body == null ? "GET" : "POST");
        c.setConnectTimeout(TIMEOUT_CONNECT);
        c.setReadTimeout(TIMEOUT_READ);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", App.UA);
        c.setRequestProperty("X-Xs-From-Web", "0");
        c.setRequestProperty("x-ss-req-ticket", now);
        c.setRequestProperty("sdk-version", "2");
        if (data != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("X-SS-STUB", md5Upper(data));
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
        } else {
            c.setRequestProperty("X-SS-STUB", md5Upper(new byte[0]));
        }

        try{
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = readAll(in);
        c.disconnect();
        android.util.Log.i("HGAPI", (body == null ? "GET " : "POST ") + path
                + " -> " + code + " len=" + text.length());
        if (text.isEmpty()) {
            JSONObject o = new JSONObject();
            o.put("_empty", true);
            o.put("_http_status", code);
            return o;
        }
        JSONObject result=new JSONObject(text);if(code>=400)result.put("_http_status",code);return result;
        }finally{c.disconnect();}
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String md5Upper(byte[] d) throws Exception {
        byte[] r = MessageDigest.getInstance("MD5").digest(d);
        StringBuilder sb = new StringBuilder(r.length * 2);
        for (byte b : r) sb.append(String.format(java.util.Locale.US, "%02X", b & 0xff));
        return sb.toString();
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }
}
