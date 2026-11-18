// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** ABI v1: create an upstream request and normalize its response. */
class PlaybackModule {
    private val signer = Signer(mobileV5 = true)
    fun prepare(vid: String, identity: String): String {
        require(vid.matches(Regex("[0-9]{6,24}")))
        val dev = JSONObject(identity)
        val now = System.currentTimeMillis()
        val params = linkedMapOf("aid" to "8662", "app_name" to "novelread", "version_code" to "74332", "version_name" to "7.4.3.32", "channel" to "vivo_8662_64", "device_platform" to "android", "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332", "iid" to dev.getString("iid"), "cdid" to dev.getString("cdid"), "device_id" to dev.getString("device_id"), "_rticket" to now.toString())
        val payload = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$vid"]},"dr_scene":"player"}""".toByteArray(Charsets.UTF_8)
        val buffer = ByteArrayOutputStream(); GZIPOutputStream(buffer).use { it.write(payload) }; val body = buffer.toByteArray()
        val signed = signer.signPlayback("https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1", params, body, now)
        val headers = JSONObject(signed.headers); headers.put("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"); headers.put("Accept", "application/json"); headers.put("Content-Encoding", "gzip")
        return JSONObject().put("url", signed.url).put("headers", headers).put("body", Base64.encodeToString(body, Base64.NO_WRAP)).toString()
    }
    fun normalize(vid: String, response: String): String {
        val root = JSONObject(response); require(root.optInt("code", 0) == 0)
        val data = root.getJSONObject("data"); val rows = data.optJSONObject("1004") ?: data
        val node = rows.getJSONObject(vid)
        val value = node.get("video_model")
        val model = if (value is JSONObject) value else JSONObject(value.toString())
        val source = model.getJSONArray("video_list")
        val canonical = org.json.JSONArray()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val url = item.optString("main_url", "")
            if (url.isEmpty()) continue
            val row = JSONObject().put("main_url", url).put("backup_url", item.optString("backup_url", ""))
            row.put("video_meta", item.optJSONObject("video_meta") ?: JSONObject())
            row.put("encrypt_info", item.optJSONObject("encrypt_info") ?: JSONObject())
            canonical.put(row)
        }
        require(canonical.length() > 0)
        val result = JSONObject().put("video_model", JSONObject().put("video_list", canonical))
        return JSONObject().put("code", 0).put("data", JSONObject().put(vid, result)).toString()
    }
}
