package ink.pengfei.lvguo.update;

import org.json.JSONObject;

/** 服务端返回的一个可用新版本。 */
final class UpdateInfo {
    final String versionName, url, sha256, notes, kind;
    final int versionCode, lastFullVersionCode;
    final long size;

    UpdateInfo(JSONObject j) {
        this(j, 0);
    }

    UpdateInfo(JSONObject j, int currentVersionCode) {
        versionName = j.optString("versionName");
        versionCode = j.optInt("versionCode");
        url = j.optString("url");
        sha256 = j.optString("sha256").toLowerCase(java.util.Locale.US);
        notes = j.optString("notes");
        size = j.optLong("size");
        String rawKind = j.optString("kind", "full");
        int lastFull = j.optInt("lastFullVersionCode", "full".equals(rawKind) ? versionCode : 0);
        lastFullVersionCode = lastFull;
        // 若当前旧版本与最新版本之间存在未安装的 full 重要更新（currentVersionCode < lastFullVersionCode），
        // 即使最新版自身标记为 signature 静默更新，也必须按 full 弹窗提示更新。
        if ("signature".equals(rawKind) && currentVersionCode > 0 && lastFull > currentVersionCode) {
            kind = "full";
        } else {
            kind = rawKind;
        }
    }

    /** 只更新了签名播放模块：已能热更新，不打扰用户，只在关于页展示。 */
    boolean quiet() { return "signature".equals(kind); }

    /** 字段齐全且下载地址是 https，才认为可信。 */
    boolean valid() {
        return versionCode > 0 && !versionName.isEmpty() && url.startsWith("https://") && sha256.length() == 64 && size > 0;
    }

    String fileName() { return "update-" + versionCode + ".apk"; }
}
