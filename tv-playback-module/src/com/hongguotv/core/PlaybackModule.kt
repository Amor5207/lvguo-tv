// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** ABI v1: create an upstream request and normalize its response. */
class PlaybackModule {
    private val signer = Signer(mobileV5 = true)
    // 2026-11-20 起上游对旧 V5 POST 一律回 200 空 body（dr_scene 已移入请求体）；
    // video_tab 空 body 签名 GET 是同一上游当前的可用播放路线。
    fun prepare(vid: String, identity: String): String {
        require(vid.matches(Regex("[0-9]{6,24}")))
        val dev = JSONObject(identity)
        val now = System.currentTimeMillis()
        val params = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to "74332", "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12",
            "update_version_code" to "74332",
            "iid" to dev.getString("iid"), "cdid" to dev.getString("cdid"), "device_id" to dev.getString("device_id"),
            "video_id" to vid, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024",
            "source" to "0", "from_video_id" to "",
        )
        val signed = signer.signPlayback("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/", params, null, now)
        val headers = JSONObject(signed.headers)
        headers.put("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        headers.put("Accept", "application/json")
        return JSONObject().put("url", signed.url).put("headers", headers).toString()
    }

    /** 多线路本地签名取流：支持 dynamic（按 drScene 拼路径）、videotab（GET）、legacy（/player/v1/ POST）。 */
    fun prepareRoute(route: String, vid: String, identity: String, drScene: String): String {
        if (route == "videotab") return prepare(vid, identity)
        require(vid.matches(Regex("[0-9]{6,24}")))
        val dev = JSONObject(identity)
        val now = System.currentTimeMillis()
        val scene = if (route == "legacy") "player" else drScene.trim().ifEmpty { "default" }
        val safeScene = if (scene.matches(Regex("[A-Za-z0-9_-]{1,32}"))) scene else "default"
        val endpoint = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/$safeScene/v1/"
        val payload = "{\"biz_param\":{\"detail_page_version\":0,\"device_level\":1,\"disable_digg_stat\":false,\"disable_video_relate_book\":false,\"need_all_video_definition\":true,\"need_mp4_align\":false,\"use_os_player\":false,\"use_server_dns\":false,\"video_platform\":1024},\"mixed_video_id_map\":{\"1004\":[\"$vid\"]},\"video_id\":\"$vid\",\"content_type\":1}"
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val params = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to "74332", "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12",
            "update_version_code" to "74332",
            "iid" to dev.getString("iid"), "cdid" to dev.getString("cdid"), "device_id" to dev.getString("device_id"),
            "_rticket" to "0",
        )
        val signed = signer.signPlayback(endpoint, params, gz, now)
        val headers = JSONObject(signed.headers)
        headers.put("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)")
        headers.put("Accept", "application/json")
        headers.put("Content-Encoding", "gzip")
        return JSONObject()
            .put("url", signed.url)
            .put("method", "POST")
            .put("body", Base64.encodeToString(gz, Base64.NO_WRAP))
            .put("headers", headers)
            .toString()
    }
    fun normalize(vid: String, response: String): String {
        val root = JSONObject(response); require(root.optInt("code", 0) == 0)
        val data = root.getJSONObject("data")
        val rows = data.optJSONObject("video_model_data") ?: data.optJSONObject("1004") ?: data
        val node = rows.getJSONObject(vid)
        val value = node.get("video_model")
        var model = if (value is JSONObject) value else JSONObject(value.toString())
        var seed: ByteArray? = null
        var canonical = extractCanonical(model, null)
        if (vid != "123456" && (canonical.length() == 0 || hasProprietary(canonical))) {
            val fb = extractFallbackUrl(model.opt("fallback_api"))
            if (fb.isNotEmpty()) {
                val resolved = resolveCompatibleFallback(fb)
                if (resolved != null) {
                    model = resolved.first
                    seed = resolved.second
                    val candidate = extractCanonical(model, seed)
                    if (candidate.length() > 0) canonical = candidate
                }
            }
        }
        require(canonical.length() > 0)
        // 只把电视硬件解码器真正能放的码流交给 APP；专有码流交由服务端备用通道协商。
        canonical = filterPlayable(canonical)
        require(canonical.length() > 0) { "播放模型没有可解码流" }
        val result = JSONObject().put("video_model", JSONObject().put("video_list", canonical))
        return JSONObject().put("code", 0).put("data", JSONObject().put(vid, result)).toString()
    }
    private fun filterPlayable(list: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val meta = list.optJSONObject(i)?.optJSONObject("video_meta")
            val codec = meta?.optString("codec_type", "") ?: ""
            if (isProprietary(codec)) continue
            out.put(list.optJSONObject(i))
        }
        return out
    }
    private fun extractCanonical(model: JSONObject, seed: ByteArray?): JSONArray {
        val listObj = model.opt("video_list")
        val items = mutableListOf<JSONObject>()
        if (listObj is JSONArray) {
            for (i in 0 until listObj.length()) listObj.optJSONObject(i)?.let { items.add(it) }
        } else if (listObj is JSONObject) {
            val keys = listObj.keys()
            while (keys.hasNext()) listObj.optJSONObject(keys.next())?.let { items.add(it) }
        }
        val canonical = JSONArray()
        for (item in items) {
            val url = decodeUrl(item.optString("main_url", ""), seed)
            if (url.isEmpty()) continue
            val backupRaw = item.optString("backup_url", "").ifEmpty { item.optString("backup_url_1", "") }
            val backup = decodeUrl(backupRaw, seed)
            val meta = item.optJSONObject("video_meta") ?: JSONObject()
            if (meta.optString("definition", "").isEmpty()) meta.put("definition", item.optString("definition", item.optString("quality_desc", "")))
            if (meta.optString("codec_type", "").isEmpty()) meta.put("codec_type", item.optString("codec_type", ""))
            if (meta.optInt("vwidth", 0) == 0) meta.put("vwidth", item.optInt("vwidth", item.optInt("width", 0)))
            if (meta.optInt("vheight", 0) == 0) meta.put("vheight", item.optInt("vheight", item.optInt("height", 0)))
            if (meta.optLong("size", 0L) == 0L) meta.put("size", item.optLong("size", 0L))
            if (meta.optDouble("fps", 0.0) == 0.0) meta.put("fps", item.optDouble("fps", 0.0))
            val enc = item.optJSONObject("encrypt_info") ?: JSONObject()
            if (!enc.has("encrypt") && item.has("encrypt")) enc.put("encrypt", item.optBoolean("encrypt", false))
            if (enc.optString("spade_a", "").isEmpty() && item.optString("spade_a", "").isNotEmpty()) enc.put("spade_a", item.optString("spade_a", ""))
            if (enc.optString("kid", "").isEmpty() && item.optString("kid", "").isNotEmpty()) enc.put("kid", item.optString("kid", ""))
            val row = JSONObject().put("main_url", url).put("backup_url", backup).put("video_meta", meta).put("encrypt_info", enc)
            canonical.put(row)
        }
        return canonical
    }
    // ByteVC1/ByteVC2/VVC 都要厂商专有解码器，电视的硬件 HEVC 解码器放不了。
    private fun isProprietary(codec: String): Boolean {
        val c = codec.lowercase()
        return c.contains("bytevc") || c.contains("bvc") || c.contains("vvc")
    }
    private fun hasProprietary(list: JSONArray): Boolean {
        for (i in 0 until list.length()) {
            val meta = list.optJSONObject(i)?.optJSONObject("video_meta")
            val codec = meta?.optString("codec_type", "") ?: ""
            if (isProprietary(codec)) return true
        }
        return false
    }
    private fun onlyProprietary(list: JSONArray): Boolean {
        if (list.length() == 0) return false
        for (i in 0 until list.length()) {
            val meta = list.optJSONObject(i)?.optJSONObject("video_meta")
            val codec = meta?.optString("codec_type", "") ?: ""
            if (!isProprietary(codec)) return false
        }
        return true
    }
    private fun extractFallbackUrl(raw: Any?): String {
        if (raw == null) return ""
        if (raw is JSONObject) return extractFallbackUrl(raw.opt("fallback_api"))
        if (raw is JSONArray && raw.length() > 0) return extractFallbackUrl(raw.opt(0))
        val text = raw.toString().trim()
        if (text.startsWith("{")) {
            try { return extractFallbackUrl(JSONObject(text).opt("fallback_api")) } catch (_: Exception) {}
        }
        return text
    }
    private fun replaceCodecQuery(raw: String, codecQuery: String): String {
        val idx = raw.indexOf('?')
        if (idx < 0) return "$raw?$codecQuery"
        val base = raw.substring(0, idx)
        val query = raw.substring(idx + 1)
        val kept = mutableListOf<String>()
        for (part in query.split("&")) {
            if (part.isEmpty()) continue
            val name = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            if (name != "codec_type" && name != "force_codec") kept.add(part)
        }
        kept.add(codecQuery)
        return "$base?${kept.joinToString("&")}"
    }
    private fun resolveCompatibleFallback(fbUrl: String): Pair<JSONObject, ByteArray?>? {
        // 只有同时带 force_codec=h264 时，上游才会返回 360P~1080P 的全部 H.265 版本；
        // 单独 codec_type=1 只回 1080P，低分辨率电视会因此无档可选。
        for (codec in listOf("codec_type=1&force_codec=h264", "codec_type=1", "codec_type=3", "codec_type=0&force_codec=h264")) {
            val pair = fetchFallback(replaceCodecQuery(fbUrl, codec)) ?: continue
            val list = extractCanonical(pair.first, pair.second)
            if (list.length() > 0 && !onlyProprietary(list)) return pair
        }
        return fetchFallback(fbUrl)
    }
    private fun fetchFallback(rawUrl: String): Pair<JSONObject, ByteArray?>? {
        return try {
            val u = URL(rawUrl)
            val host = u.host.lowercase()
            if (u.protocol != "https" || (!host.endsWith(".snssdk.com") && !host.endsWith(".fqnovel.com"))) return null
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 8000; conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode != 200) return null
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val outer = JSONObject(text)
                val inner = outer.optJSONObject("video_info")?.optJSONObject("data") ?: outer.optJSONObject("data") ?: return null
                val seedStr = inner.optString("key_seed", "").trim()
                val seed = if (seedStr.isNotEmpty()) Base64.decode(seedStr, Base64.DEFAULT) else null
                Pair(inner, seed)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
    private fun decodeUrl(raw: String, seed: ByteArray?): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("https://") || trimmed.startsWith("http://")) return trimmed
        return try {
            val decoded = Base64.decode(trimmed, Base64.DEFAULT)
            if (seed != null && seed.isNotEmpty() && decoded.size >= 20 && (decoded[0].toInt() and 0xFF) == 0xa8 && decoded[2].toInt() == 1 && decoded[3].toInt() == 0) {
                val salt = "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val sha = MessageDigest.getInstance("SHA-512")
                val first = sha.digest(seed)
                sha.reset()
                val second = sha.digest(first + salt)
                val cipherLen = (decoded.size - 4) / 16 * 16
                if (cipherLen > 0) {
                    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(second, 0, 16, "AES"), IvParameterSpec(second, 16, 16))
                    val plain = cipher.doFinal(decoded, 4, cipherLen)
                    var end = plain.size
                    val pad = plain[end - 1].toInt() and 0xFF
                    if (pad in 1..16 && pad <= end) end -= pad
                    while (end > 0 && plain[end - 1].toInt() == 0) end--
                    val urlText = String(plain, 0, end, Charsets.UTF_8).trim()
                    if (urlText.startsWith("https://") || urlText.startsWith("http://")) return urlText
                }
            }
            val text = String(decoded, Charsets.UTF_8).trim()
            if (text.startsWith("https://") || text.startsWith("http://")) text else ""
        } catch (_: Exception) {
            ""
        }
    }
}
