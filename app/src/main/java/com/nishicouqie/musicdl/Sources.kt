package com.nishicouqie.musicdl

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** 数据源：网易云（官方 + 聚合）+ gequbao 兜底。逻辑与桌面版 mcore.py 一致。 */
object Sources {

    const val GD_API = "https://music-api.gdstudio.xyz/api.php"
    const val NE_DETAIL = "https://music.163.com/api/song/detail"
    const val NE_LYRIC = "https://music.163.com/api/song/lyric"
    const val NE_PLAYER = "https://music.163.com/api/song/enhance/player/url/v1"
    const val NE_SEARCH = "https://music.163.com/api/search/get/web"
    const val NE_ACCOUNT = "https://music.163.com/api/nuser/account/get"
    const val ITUNES = "https://itunes.apple.com/search"

    private val LEVEL_CN = mapOf(
        "hires" to "Hi-Res 无损", "lossless" to "无损 FLAC",
        "exhigh" to "320K MP3", "higher" to "192K MP3",
        "standard" to "128K MP3", "jymaster" to "母带"
    )

    class MusicException(
        val userMessage: String,
        val hint: String = "",
        val code: String = "",
        val detail: String = ""
    ) : Exception(userMessage) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("message", userMessage); put("hint", hint)
            put("code", code); put("detail", detail)
        }
    }

    // ---------------------------------------------------------- 工具

    fun norm(s: String?): String {
        var t = (s ?: "").lowercase()
        t = t.replace(Regex("[（(\\[【].*?[)）\\]】]"), "")
        return t.replace(Regex("[\\s\\-_·・.,，。!！?？'\"“”‘’~～]+"), "")
    }

    /** 简易相似度：基于最长公共子序列长度比 */
    fun sim(a: String?, b: String?): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val dp = IntArray(y.length + 1)
        for (i in 1..x.length) {
            var prev = 0
            for (j in 1..y.length) {
                val tmp = dp[j]
                dp[j] = if (x[i - 1] == y[j - 1]) prev + 1 else maxOf(dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return 2.0 * dp[y.length] / (x.length + y.length)
    }

    fun sanitize(s: String): String {
        var t = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
        t = t.replace(Regex("\\s+"), " ").trim().trim('.')
        return t.ifBlank { "unknown" }
    }

    // ---------------------------------------------------------- 搜索

    fun search(keyword: String, limit: Int = 20): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        try {
            val body = Http.form(
                NE_SEARCH,
                mapOf("s" to keyword, "type" to "1", "offset" to "0",
                      "limit" to limit.toString(), "total" to "true"),
                referer = "https://music.163.com/", ua = Http.UA_DESKTOP
            )
            val songs = JSONObject(body).optJSONObject("result")
                ?.optJSONArray("songs")
            if (songs != null) {
                for (i in 0 until songs.length()) {
                    val s = songs.optJSONObject(i) ?: continue
                    val alb = s.optJSONObject("album") ?: JSONObject()
                    val arts = s.optJSONArray("artists") ?: JSONArray()
                    val names = (0 until arts.length())
                        .mapNotNull { arts.optJSONObject(it)?.optString("name") }
                    val fee = s.optInt("fee", 0)
                    list.add(JSONObject().apply {
                        put("id", s.optString("id"))
                        put("title", s.optString("name"))
                        put("artist", names.joinToString("/"))
                        put("album", alb.optString("name"))
                        put("cover", alb.optString("picUrl"))
                        put("duration", s.optLong("duration"))
                        put("vip", fee == 1 || fee == 4)
                    })
                }
            }
        } catch (_: Exception) {
        }
        if (list.isNotEmpty()) {
            enrichCovers(list)
            return list
        }
        // 回退：聚合接口搜索
        try {
            val u = "$GD_API?types=search&source=netease&name=${Http.urlEncode(keyword)}&count=$limit"
            val arr = Http.jsonArray(u, referer = "https://music.gdstudio.xyz/")
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val arts = c.optJSONArray("artist") ?: JSONArray()
                val names = (0 until arts.length()).mapNotNull { arts.optString(it) }
                list.add(JSONObject().apply {
                    put("id", c.optString("id"))
                    put("title", c.optString("name"))
                    put("artist", names.joinToString("/"))
                    put("album", c.optString("album"))
                    put("cover", "")
                    put("duration", 0L)
                    put("vip", false)
                })
            }
        } catch (_: Exception) {
        }
        return list
    }

    /** 搜索接口的 album.picUrl 是 null，用一次批量 song/detail 补齐封面 */
    private fun enrichCovers(rows: MutableList<JSONObject>) {
        val ids = rows.filter { it.optString("cover").isBlank() }
            .mapNotNull { it.optString("id").toLongOrNull() }
        if (ids.isEmpty()) return
        try {
            val idsJson = "[" + ids.joinToString(",") + "]"
            val o = Http.json(
                "$NE_DETAIL?ids=${Http.urlEncode(idsJson)}",
                referer = "https://music.163.com/", ua = Http.UA_DESKTOP, retries = 1
            )
            val songs = o.optJSONArray("songs") ?: return
            val map = HashMap<String, JSONObject>()
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                map[s.optString("id")] = s
            }
            for (r in rows) {
                val s = map[r.optString("id")] ?: continue
                val pic = s.optJSONObject("album")?.optString("picUrl") ?: ""
                if (pic.isNotBlank()) r.put("cover", pic)
            }
        } catch (_: Exception) {
        }
    }

    fun pickBest(rows: List<JSONObject>, keyword: String): JSONObject? {
        if (rows.isEmpty()) return null
        val parts = keyword.trim().split(Regex("[\\s\\-—]+")).filter { it.isNotBlank() }
        val wantT = parts.firstOrNull() ?: keyword
        val wantA = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""
        var best: JSONObject? = null
        var bestSc = -1.0
        for (r in rows) {
            var sc = sim(wantT, r.optString("title")) * 0.7 +
                     sim(wantT, r.optString("artist")) * 0.1
            sc += if (wantA.isNotBlank()) sim(wantA, r.optString("artist")) * 0.2 else 0.2
            if (sc > bestSc) { bestSc = sc; best = r }
        }
        best?.put("_score", bestSc)
        return best
    }

    // ---------------------------------------------------------- 详情

    fun detail(id: String): JSONObject {
        val o = Http.json(
            "$NE_DETAIL?ids=${Http.urlEncode("[${id.toLong()}]")}",
            referer = "https://music.163.com/", ua = Http.UA_DESKTOP
        )
        val songs = o.optJSONArray("songs")
        if (songs == null || songs.length() == 0)
            throw MusicException("网易云查不到 ID $id 这首歌",
                "确认歌曲 ID，或改用歌名搜索", "NOT_FOUND")
        val s = songs.optJSONObject(0)!!
        val alb = s.optJSONObject("album") ?: JSONObject()
        val arts = s.optJSONArray("artists") ?: JSONArray()
        val names = (0 until arts.length())
            .mapNotNull { arts.optJSONObject(it)?.optString("name") }
        return JSONObject().apply {
            put("id", s.optString("id"))
            put("title", s.optString("name"))
            put("artist", names.joinToString("/"))
            put("album", alb.optString("name"))
            put("cover", alb.optString("picUrl"))
            put("duration", s.optLong("duration"))
            put("track", s.optInt("no"))
            put("disc", s.optString("disc"))
            put("albumSize", alb.optInt("size"))
            put("publishTime", s.optLong("publishTime"))
            put("fee", s.optInt("fee"))
        }
    }

    fun itunesLookup(title: String, artist: String): JSONObject? {
        return try {
            val u = "$ITUNES?term=${Http.urlEncode("$title $artist")}" +
                    "&entity=song&limit=10&country=JP"
            val o = Http.json(u, referer = "https://music.apple.com/",
                ua = Http.UA_DESKTOP, retries = 1)
            val arr = o.optJSONArray("results") ?: return null
            var best: JSONObject? = null
            var bestSc = 0.0
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val sc = sim(title, r.optString("trackName")) * 0.65 +
                         sim(artist, r.optString("artistName")) * 0.35
                if (sc > bestSc) { bestSc = sc; best = r }
            }
            if (best != null && bestSc >= 0.72) {
                best.put("_score", bestSc); best
            } else null
        } catch (_: Exception) { null }
    }

    // ---------------------------------------------------------- 歌词

    private val TS = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val META = Regex("^\\[(ar|ti|al|by|au|offset|re|ve|length):(.*)]",
        RegexOption.IGNORE_CASE)

    data class Lrc(val metas: List<String>, val entries: List<Pair<Int, String>>)

    fun parseLrc(text: String?): Lrc {
        val metas = mutableListOf<String>()
        val entries = mutableListOf<Pair<Int, String>>()
        for (raw in (text ?: "").split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (META.containsMatchIn(line)) { metas.add(line); continue }
            val ms = TS.findAll(line).toList()
            if (ms.isEmpty()) continue
            val content = line.substring(ms.last().range.last + 1).trim()
            for (m in ms) {
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                var frac = 0
                val f = m.groupValues[3]
                if (f.isNotEmpty()) {
                    frac = if (f.length >= 3) f.substring(0, 3).toInt()
                           else (f + "0".repeat(3 - f.length)).toInt()
                }
                entries.add((a * 60000 + b * 1000 + frac) to content)
            }
        }
        entries.sortBy { it.first }
        return Lrc(metas, entries)
    }

    fun fmtTs(ms: Int): String =
        "[%02d:%05.2f]".format(ms / 60000, (ms % 60000) / 1000.0)

    /** JSONObject.optString 在值是 JSON null 时会返回字符串 "null"，这里统一成空串 */
    private fun JSONObject.str(key: String): String {
        val v = opt(key) ?: return ""
        return if (v === JSONObject.NULL) "" else v.toString()
    }

    /** 返回 (双语LRC, 纯原文LRC, [(ms,text)]) */
    fun lyric(id: String): Triple<String, String, List<Pair<Int, String>>> {
        var ja = ""; var zh = ""
        try {
            val o = Http.json(
                "$NE_LYRIC?id=$id&lv=1&kv=1&tv=-1",
                referer = "https://music.163.com/", ua = Http.UA_DESKTOP
            )
            ja = o.optJSONObject("lrc")?.str("lyric") ?: ""
            zh = o.optJSONObject("tlyric")?.str("lyric") ?: ""
        } catch (_: Exception) {}
        if (ja.isBlank()) {
            try {
                val o = Http.json(
                    "$GD_API?types=lyric&source=netease&id=$id",
                    referer = "https://music.gdstudio.xyz/", ua = Http.UA_DESKTOP)
                ja = o.str("lyric"); zh = o.str("tlyric")
            } catch (_: Exception) {}
        }
        val j = parseLrc(ja); val z = parseLrc(zh)
        if (j.entries.isEmpty()) return Triple("", "", emptyList())

        val zhMap = HashMap<Int, String>()
        for ((ms, t) in z.entries) if (t.isNotBlank()) zhMap.putIfAbsent(ms, t)

        val merged = mutableListOf<String>()
        for ((ms, t) in j.entries) {
            merged.add(fmtTs(ms) + t)
            var zt = zhMap[ms]
            if (zt == null) {
                for ((k, v) in zhMap) if (kotlin.math.abs(k - ms) <= 60) { zt = v; break }
            }
            if (!zt.isNullOrBlank()) merged.add(fmtTs(ms) + zt)
        }
        val head = j.metas + (z.metas.firstOrNull()?.let { listOf(it) } ?: emptyList())
        val bilingual = (head + merged).joinToString("\n")
        val original = (j.metas + j.entries.map { fmtTs(it.first) + it.second })
            .joinToString("\n")
        return Triple(bilingual, original, j.entries.filter { it.second.isNotBlank() })
    }

    // ---------------------------------------------------------- 音源

    /** 官方播放地址；返回 data[0] */
    fun officialUrl(id: String, level: String, cookie: String): JSONObject? {
        if (cookie.isBlank()) return null
        val enc = if (level == "lossless" || level == "hires" || level == "jymaster")
            "flac" else "mp3"
        val u = "$NE_PLAYER?ids=${Http.urlEncode("[${id.toLong()}]")}" +
                "&level=$level&encodeType=$enc"
        return try {
            val o = Http.json(u, referer = "https://music.163.com/",
                cookie = cookie, ua = Http.UA_DESKTOP, retries = 2)
            o.optJSONArray("data")?.optJSONObject(0)
        } catch (_: Exception) { null }
    }

    fun aggregatorUrl(id: String, br: Int): Triple<String, Int, Long> {
        val u = "$GD_API?types=url&source=netease&id=$id&br=$br"
        val o = Http.json(u, referer = "https://music.gdstudio.xyz/",
            ua = Http.UA_DESKTOP, retries = 2)
        return Triple(o.optString("url"), o.optInt("br"), o.optLong("size"))
    }

    /** gequbao 兜底（实测只有酷我 128K） */
    fun gequbaoUrl(keyword: String): Triple<String, String, String> {
        val jar = HashMap<String, String>()
        fun cookieHeader() = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

        fun fetch(url: String, referer: String? = null, post: String? = null): String {
            val headers = mutableMapOf<String, String>()
            referer?.let { headers["Referer"] = it }
            val text = if (post == null) {
                Http.text(url, referer = referer, cookie = cookieHeader(),
                    ua = Http.UA_MOBILE)
            } else {
                Http.form(url, mapOf("id" to post.split("=")[1]), referer = referer,
                    cookie = cookieHeader(), ua = Http.UA_MOBILE,
                    extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"))
            }
            return text
        }

        val searchHtml = fetch("https://www.gequbao.com/s/" + Http.urlEncode(keyword))
        val re = Regex("/music/(\\d+)\"[^>]*title=\"([^\"]*)\"")
        var bestId = ""; var bestSc = 0.0
        for (m in re.findAll(searchHtml)) {
            val sc = sim(keyword.substringBefore(' '), m.groupValues[2].substringBefore(" - "))
            if (sc > bestSc) { bestSc = sc; bestId = m.groupValues[1] }
        }
        if (bestId.isBlank())
            throw MusicException("gequbao 里也没搜到这首歌", code = "GEQUBAO_NOMATCH")
        val songUrl = "https://www.gequbao.com/music/$bestId"
        val page = fetch(songUrl)
        val mm = Regex("window\\.appData\\s*=\\s*JSON\\.parse\\('(.*?)'\\)", RegexOption.DOT_MATCHES_ALL)
            .find(page)
            ?: throw MusicException("gequbao 页面结构已变更", code = "GEQUBAO_STRUCT")
        val inner = mm.groupValues[1].replace("\"", "\\\"")
        val appJson = JSONObject(org.json.JSONTokener("\"$inner\"").nextValue() as String)
        val playId = appJson.optString("play_id")
        val body = Http.form(
            "https://www.gequbao.com/member/common-play-url",
            mapOf("id" to playId, "purpose" to "play"),
            referer = songUrl, cookie = cookieHeader(), ua = Http.UA_MOBILE,
            extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"))
        val j = JSONObject(body)
        if (j.optInt("code") == 1) {
            val url = j.optJSONObject("data")?.optString("url") ?: ""
            if (url.isNotBlank())
                return Triple(url,
                    if (url.contains("bitrate\$320")) "酷我 320K" else "酷我 128K", songUrl)
        }
        throw MusicException("gequbao 拒绝提供直链（需要验证码或额度用尽）",
            "gequbao 有验证码和每日额度，此路不通很正常", "GEQUBAO_DENIED")
    }

    data class Audio(
        val url: String, val ext: String, val levelCn: String,
        val br: Long, val size: Long, val channel: String
    )

    /** 三级通道取直链 */
    fun resolveAudio(id: String, want: String, cfg: Config,
                     onLog: (String) -> Unit = {}): Audio {
        val errors = mutableListOf<String>()
        val cookie = cfg.activeCookie()

        if (cfg.sourceOrder == "official_first") {
            if (cookie.isBlank()) {
                errors.add("官方通道：未配置 VIP Cookie，已跳过")
            } else {
                val levels = if (want == "flac") listOf("hires", "lossless")
                             else listOf("exhigh")
                for (lv in levels) {
                    val r = officialUrl(id, lv, cookie)
                    val url = r?.optString("url") ?: ""
                    if (url.isNotBlank()) {
                        val real = r!!.optString("level").ifBlank { lv }
                        return Audio(url, want, LEVEL_CN[real] ?: real,
                            r.optLong("br"), r.optLong("size"), "official")
                    }
                    val code = r?.optInt("code") ?: -1
                    errors.add(when (code) {
                        -110 -> "官方通道：账号无此曲播放权益"
                        -200 -> "官方通道：该曲无此音质版本"
                        else -> "官方通道：接口码 $code"
                    })
                    onLog("官方通道 ${LEVEL_CN[lv] ?: lv} 未取到，继续降级…")
                }
            }
        }

        try {
            val br = if (want == "flac") 999 else 320
            val (u, realBr, size) = aggregatorUrl(id, br)
            if (u.isNotBlank())
                return Audio(u, want,
                    if (want == "flac") "无损 FLAC" else "320K MP3",
                    if (realBr in 1..9999) realBr * 1000L else realBr.toLong(),
                    size, "aggregator")
            errors.add("聚合接口：该曲没有这个音质的直链")
        } catch (e: Exception) {
            errors.add("聚合接口：${e.message}")
        }
        onLog("聚合接口未取到，尝试 gequbao 兜底…")

        try {
            val (u, note, _) = gequbaoUrl(id)
            return Audio(u, "mp3", note, 0, 0, "gequbao")
        } catch (e: Exception) {
            errors.add("gequbao：${e.message}")
        }

        throw MusicException("所有音源通道都没能取到直链",
            "歌曲可能需要单曲购买，或三个数据源同时故障。到设置里检查 Cookie。",
            "NO_SOURCE", errors.joinToString("\n"))
    }

    /** 测试 Cookie：查账号 + 真实探针 */
    fun probeCookie(raw: String): JSONObject {
        val cookie = Config.normalizeCookie(raw)
        val out = JSONObject().apply {
            put("ok", false); put("nickname", ""); put("vipText", "")
            put("reason", ""); put("hint", "")
        }
        if (cookie.isBlank()) {
            out.put("reason", "Cookie 为空"); out.put("hint", "请粘贴 MUSIC_U 的值")
            return out
        }
        try {
            val o = Http.json(NE_ACCOUNT, referer = "https://music.163.com/",
                cookie = cookie, ua = Http.UA_DESKTOP, retries = 1, timeout = 20000)
            val p = o.optJSONObject("profile")
            val a = o.optJSONObject("account")
            if (p == null && a == null) {
                out.put("reason", "Cookie 无效或已过期")
                out.put("hint", "重新登录 music.163.com 后复制新的 MUSIC_U")
                return out
            }
            out.put("nickname", p?.optString("nickname")
                ?: a?.optString("userName") ?: "（未知昵称）")
            val vt = p?.optInt("vipType") ?: a?.optInt("vipType") ?: 0
            out.put("vipText", when (vt) {
                11 -> "黑胶 VIP"; 10 -> "音乐包"; 0 -> "普通用户"; else -> "VIP 类型 $vt"
            })
        } catch (e: Exception) {
            out.put("reason", "账号查询失败"); out.put("hint", e.message ?: "检查网络")
            return out
        }
        try {
            val r = officialUrl("455502615", "lossless", cookie)
            if (r != null && r.optString("url").isNotBlank()) {
                val lv = r.optString("level")
                out.put("ok", true)
                out.put("reason", "可用，能取到 ${LEVEL_CN[lv] ?: lv}")
                if (lv != "lossless" && lv.isNotBlank())
                    out.put("hint", "该账号权益可能只覆盖部分曲目，或这首歌没有无损版本")
            } else {
                val code = r?.optInt("code") ?: -1
                out.put("reason", if (code == -110) "Cookie 有效，但该账号没有 VIP 播放权益"
                    else "取直链失败（接口码 $code）")
                out.put("hint", if (code == -110)
                    "到网易云开通黑胶 VIP 即可；不开也能用聚合接口（默认自动降级）"
                    else "稍后重试，或检查账号状态")
            }
        } catch (e: Exception) {
            out.put("reason", "探针请求失败"); out.put("hint", e.message ?: "")
        }
        return out
    }
}
