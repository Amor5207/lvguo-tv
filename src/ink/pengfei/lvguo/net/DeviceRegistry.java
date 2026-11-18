package ink.pengfei.lvguo.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 匿名设备注册（log5-applog device_register，ttEncrypt + gzip）。
 * 移植自 hongguo-demo/hg/device.py。账号与签名接口认「同一次注册得到的 device_id + install_id」。
 */
final class DeviceRegistry {
    private DeviceRegistry() {}

    private static final String URL = "https://log5-applog-lf.fqnovel.com/service/2/device_register/"
            + "?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1";
    private static final byte[] MAGIC = {0x74, 0x63, 0x05, 0x10, 0x00, 0x00};
    private static final byte[] KDF_SALT = Signer.unhex(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524"
                    + "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");

    static String[] register() throws Exception {
        byte[] body = ttEncrypt(gzip(payload().toString().getBytes("UTF-8")));
        Map<String, String> h = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        h.put("User-Agent", Signer.APP_UA);
        h.put("Content-Type", "application/octet-stream;tt-data=a");
        h.put("log-encode-type", "gzip");
        h.put("x-ss-dp", "8662");
        h.put("x-ss-req-ticket", String.valueOf(now));
        h.put("x-vc-bdturing-sdk-version", "4.0.3.cn");
        h.put("lc", "101");
        h.put("sdk-version", "2");
        h.put("passport-sdk-version", "5051452");
        h.put("x-neptune", "-8|50:51:59:30:40:47:49:39");
        h.put("x-ss-stub", Signer.hex(Signer.md5(body)).toUpperCase(java.util.Locale.US));
        Account.Response r = Account.request(URL, h, body, null);
        JSONObject data = new JSONObject(r.text());
        String dev = data.optString("device_id_str", data.optString("device_id"));
        String ins = data.optString("install_id_str", data.optString("install_id"));
        if (dev.isEmpty() || ins.isEmpty() || "0".equals(dev)) throw new IllegalStateException("设备注册被拒绝");
        return new String[]{dev, ins};
    }

    private static JSONObject payload() throws Exception {
        long now = System.currentTimeMillis();
        JSONObject header = new JSONObject()
                .put("display_name", "红果免费短剧").put("update_version_code", 73932).put("manifest_version_code", 73932)
                .put("app_version_minor", "7.3.9.32").put("aid", 8662).put("channel", "update_64")
                .put("package", "com.phoenix.read").put("app_version", "7.3.9.32").put("version_code", 73932)
                .put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo").put("sdk_target_version", 29).put("git_hash", "3a88f4e")
                .put("os", "Android").put("os_version", "16").put("os_api", 36).put("device_model", "25053RT47C")
                .put("device_brand", "Redmi").put("device_manufacturer", "Xiaomi").put("cpu_abi", "arm64-v8a")
                .put("release_build", "b5e187f").put("density_dpi", 520).put("display_density", "mdpi")
                .put("resolution", "1280x2772").put("language", "zh").put("timezone", 8).put("access", "wifi")
                .put("not_request_sender", 0).put("carrier", "CHINA MOBILE").put("mcc_mnc", "46000")
                .put("rom", "eng.root.20231116.160340").put("rom_version", "W528JS release-keys")
                .put("cdid", UUID.randomUUID().toString()).put("sig_hash", "56a962410c494bbaf0b58dba20cae56f")
                .put("openudid", UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .put("clientudid", UUID.randomUUID().toString()).put("ipv6_list", new JSONArray())
                .put("region", "CN").put("tz_name", "Asia/Shanghai").put("tz_offset", 28800).put("sim_region", "cn")
                .put("sim_serial_number", new JSONArray()).put("oaid_may_support", false)
                .put("req_id", UUID.randomUUID().toString()).put("device_platform", "android")
                .put("custom", new JSONObject().put("account_region_src", "fallback").put("host_bit", 64)
                        .put("account_region", "cn").put("dragon_device_type", "phone"))
                .put("pre_installed_channel", "").put("apk_first_install_time", now).put("is_system_app", 0)
                .put("sdk_flavor", "china").put("guest_mode", 0);
        return new JSONObject().put("magic_tag", "ss_app_log").put("header", header).put("_gen_time", now);
    }

    static byte[] ttEncrypt(byte[] data) throws Exception {
        byte[] rnd = new byte[32];
        new SecureRandom().nextBytes(rnd);
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        byte[] derived = MessageDigest.getInstance("SHA-512").digest(Signer.concat(sha.digest(rnd), KDF_SALT));
        byte[] body = Signer.concat(MessageDigest.getInstance("SHA-512").digest(data), data);
        int pad = 16 - body.length % 16;
        byte[] padded = new byte[body.length + pad];
        System.arraycopy(body, 0, padded, 0, body.length);
        for (int i = body.length; i < padded.length; i++) padded[i] = (byte) pad;
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(java.util.Arrays.copyOfRange(derived, 0, 16), "AES"),
                new IvParameterSpec(java.util.Arrays.copyOfRange(derived, 16, 32)));
        return Signer.concat(MAGIC, rnd, c.doFinal(padded));
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(out);
        gz.write(data); gz.close();
        return out.toByteArray();
    }
}
