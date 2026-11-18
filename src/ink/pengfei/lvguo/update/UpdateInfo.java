package ink.pengfei.lvguo.update;

import org.json.JSONObject;

/** 服务端返回的一个可用新版本。 */
final class UpdateInfo {
    final String versionName, url, sha256, notes;
    final int versionCode;
    final long size;
    final boolean force;

    UpdateInfo(JSONObject j) {
        versionName = j.optString("versionName");
        versionCode = j.optInt("versionCode");
        url = j.optString("url");
        sha256 = j.optString("sha256").toLowerCase(java.util.Locale.US);
        notes = j.optString("notes");
        size = j.optLong("size");
        force = j.optBoolean("force");
    }

    /** 字段齐全且下载地址是 https，才认为可信。 */
    boolean valid() {
        return versionCode > 0 && !versionName.isEmpty() && url.startsWith("https://") && sha256.length() == 64 && size > 0;
    }

    String fileName() { return "update-" + versionCode + ".apk"; }
}
