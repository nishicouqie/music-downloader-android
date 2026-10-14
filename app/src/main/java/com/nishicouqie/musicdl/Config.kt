package com.nishicouqie.musicdl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置。存在 SharedPreferences 里，同时支持导出/导入成 .json 文件，
 * 这样换手机或分享给朋友都不用碰文件管理器。
 */
class Config(ctx: Context) {

    private val sp = ctx.getSharedPreferences("musicdl", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_DIR_NAME = "音乐下载"
        /** 公共音乐目录下的默认子目录 */
        const val DEFAULT_RELATIVE = "Music/$DEFAULT_DIR_NAME"

        const val SAVE_PUBLIC = "public"   // 公共 Music/ 目录（MediaStore）
        const val SAVE_SAF = "saf"         // 用户用 SAF 选的目录
        const val SAVE_APP = "app"         // 应用私有目录

        /** 支持粘贴纯值、MUSIC_U=xxx、或整条 cookie 串 */
        fun normalizeCookie(raw: String): String {
            val s = raw.trim()
            if (s.isEmpty()) return ""
            if (s.contains("MUSIC_U=")) return s
            if (s.contains(';') && s.contains('=')) return s
            return "MUSIC_U=$s"
        }

        fun mask(v: String): String {
            if (v.isEmpty()) return ""
            if (v.length <= 12) return "•".repeat(v.length)
            return v.substring(0, 6) + "•".repeat(12) + v.substring(v.length - 4)
        }
    }

    var saveMode: String
        get() = sp.getString("saveMode", SAVE_PUBLIC) ?: SAVE_PUBLIC
        set(v) = sp.edit().putString("saveMode", v).apply()

    /** 自定义目录的显示名 */
    var safName: String
        get() = sp.getString("safName", "") ?: ""
        set(v) = sp.edit().putString("safName", v).apply()

    /** 自定义目录的 SAF tree uri */
    var safUri: String
        get() = sp.getString("safUri", "") ?: ""
        set(v) = sp.edit().putString("safUri", v).apply()

    /** 自定义相对子目录名（公共目录模式下用） */
    var dirName: String
        get() = sp.getString("dirName", DEFAULT_DIR_NAME) ?: DEFAULT_DIR_NAME
        set(v) = sp.edit().putString("dirName", v.ifBlank { DEFAULT_DIR_NAME }).apply()

    var format: String                    // flac | mp3 | both
        get() = sp.getString("format", "both") ?: "both"
        set(v) = sp.edit().putString("format", v).apply()

    var sourceOrder: String               // official_first | aggregator_only
        get() = sp.getString("sourceOrder", "official_first") ?: "official_first"
        set(v) = sp.edit().putString("sourceOrder", v).apply()

    var coverMax: Int
        get() = sp.getInt("coverMax", 1200)
        set(v) = sp.edit().putInt("coverMax", v).apply()

    var coverSource: String
        get() = sp.getString("coverSource", "best") ?: "best"
        set(v) = sp.edit().putString("coverSource", v).apply()

    var embedCover: Boolean
        get() = sp.getBoolean("embedCover", true)
        set(v) = sp.edit().putBoolean("embedCover", v).apply()

    var embedLyrics: Boolean
        get() = sp.getBoolean("embedLyrics", true)
        set(v) = sp.edit().putBoolean("embedLyrics", v).apply()

    var writeLrc: Boolean
        get() = sp.getBoolean("writeLrc", true)
        set(v) = sp.edit().putBoolean("writeLrc", v).apply()

    var scanAfterSave: Boolean
        get() = sp.getBoolean("scanAfterSave", true)
        set(v) = sp.edit().putBoolean("scanAfterSave", v).apply()

    var activeCookieId: String
        get() = sp.getString("activeCookieId", "") ?: ""
        set(v) = sp.edit().putString("activeCookieId", v).apply()

    // ---------------- Cookie 列表 ----------------

    fun cookies(): MutableList<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val raw = sp.getString("cookies", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        } catch (_: Exception) {
        }
        return out
    }

    fun saveCookies(list: List<JSONObject>) {
        sp.edit().putString("cookies", JSONArray(list).toString()).apply()
    }

    fun addCookie(name: String, value: String, note: String = ""): String {
        val id = "ck" + System.currentTimeMillis()
        val list = cookies()
        list.add(JSONObject().apply {
            put("id", id); put("name", name.ifBlank { "未命名账号" })
            put("value", value.trim()); put("enabled", true); put("note", note)
        })
        saveCookies(list)
        if (activeCookieId.isBlank()) activeCookieId = id
        return id
    }

    fun updateCookie(id: String, patch: JSONObject) {
        val list = cookies()
        for (c in list) if (c.optString("id") == id) {
            for (k in patch.keys()) c.put(k, patch.get(k))
        }
        saveCookies(list)
    }

    fun removeCookie(id: String) {
        val list = cookies().filter { it.optString("id") != id }.toMutableList()
        saveCookies(list)
        if (activeCookieId == id)
            activeCookieId = list.firstOrNull { it.optBoolean("enabled", true) }
                ?.optString("id") ?: ""
    }

    fun cookieValue(id: String): String {
        for (c in cookies()) if (c.optString("id") == id)
            return normalizeCookie(c.optString("value"))
        return ""
    }

    /** 当前生效的 Cookie（active 优先，否则取第一个启用的） */
    fun activeCookie(): String {
        val list = cookies().filter { it.optBoolean("enabled", true) }
        for (c in list) if (c.optString("id") == activeCookieId)
            return normalizeCookie(c.optString("value"))
        return list.firstOrNull()?.let { normalizeCookie(it.optString("value")) } ?: ""
    }

    // ---------------- 导入 / 导出 ----------------

    fun toJson(withSecrets: Boolean = true): JSONObject = JSONObject().apply {
        put("version", 1)
        put("saveMode", saveMode)
        put("safUri", if (withSecrets) safUri else "")
        put("safName", safName)
        put("dirName", dirName)
        put("format", format)
        put("sourceOrder", sourceOrder)
        put("coverMax", coverMax)
        put("coverSource", coverSource)
        put("embedCover", embedCover)
        put("embedLyrics", embedLyrics)
        put("writeLrc", writeLrc)
        put("scanAfterSave", scanAfterSave)
        put("activeCookieId", activeCookieId)
        val arr = JSONArray()
        for (c in cookies()) {
            arr.put(JSONObject().apply {
                put("id", c.optString("id"))
                put("name", c.optString("name"))
                put("value", if (withSecrets) c.optString("value") else "")
                put("enabled", c.optBoolean("enabled", true))
                put("note", c.optString("note"))
            })
        }
        put("cookies", arr)
    }

    fun fromJson(o: JSONObject, keepSecrets: Boolean = true) {
        o.optString("saveMode").takeIf { it.isNotBlank() }?.let { saveMode = it }
        o.optString("safName").takeIf { it.isNotBlank() }?.let { safName = it }
        o.optString("dirName").takeIf { it.isNotBlank() }?.let { dirName = it }
        o.optString("format").takeIf { it.isNotBlank() }?.let { format = it }
        o.optString("sourceOrder").takeIf { it.isNotBlank() }?.let { sourceOrder = it }
        if (o.has("coverMax")) coverMax = o.optInt("coverMax", 1200)
        o.optString("coverSource").takeIf { it.isNotBlank() }?.let { coverSource = it }
        if (o.has("embedCover")) embedCover = o.optBoolean("embedCover", true)
        if (o.has("embedLyrics")) embedLyrics = o.optBoolean("embedLyrics", true)
        if (o.has("writeLrc")) writeLrc = o.optBoolean("writeLrc", true)
        if (o.has("scanAfterSave")) scanAfterSave = o.optBoolean("scanAfterSave", true)

        if (keepSecrets) {
            o.optString("safUri").takeIf { it.isNotBlank() }?.let { safUri = it }
            val arr = o.optJSONArray("cookies")
            if (arr != null) {
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    if (c.optString("value").isBlank()) continue
                    list.add(c)
                }
                if (list.isNotEmpty()) {
                    saveCookies(list)
                    activeCookieId = o.optString("activeCookieId")
                        .ifBlank { list.first().optString("id") }
                }
            }
        }
    }

    fun reset() {
        sp.edit().clear().apply()
    }
}
