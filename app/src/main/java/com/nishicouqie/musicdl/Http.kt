package com.nishicouqie.musicdl

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** 极简 HTTP 客户端：不引第三方库，够用即可 */
object Http {

    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    class HttpError(val code: Int, msg: String) : Exception(msg)

    private fun open(
        url: String,
        method: String,
        referer: String?,
        cookie: String?,
        ua: String,
        timeout: Int,
        body: ByteArray?,
        contentType: String?
    ): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Accept-Encoding", "gzip")
        referer?.let { c.setRequestProperty("Referer", it) }
        cookie?.let { c.setRequestProperty("Cookie", it) }
        contentType?.let { c.setRequestProperty("Content-Type", it) }
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
        }
        return c
    }

    private fun read(r: InputStream?): ByteArray {
        if (r == null) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        r.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun stream(c: HttpURLConnection): InputStream? {
        val raw = try { c.inputStream } catch (e: Exception) { c.errorStream }
        val enc = c.contentEncoding
        return if (raw != null && enc != null && enc.contains("gzip", true))
            GZIPInputStream(raw) else raw
    }

    fun bytes(
        url: String,
        referer: String? = null,
        cookie: String? = null,
        ua: String = UA_MOBILE,
        timeout: Int = 30000,
        body: ByteArray? = null,
        contentType: String? = null,
        retries: Int = 3
    ): ByteArray {
        var last: Exception? = null
        for (i in 0 until retries) {
            try {
                val method = if (body == null) "GET" else "POST"
                val c = open(url, method, referer, cookie, ua, timeout, body, contentType)
                if (body != null) c.outputStream.use { it.write(body) }
                val code = c.responseCode
                if (code !in 200..299) {
                    c.disconnect()
                    throw HttpError(code, "HTTP $code")
                }
                val data = read(stream(c))
                c.disconnect()
                return data
            } catch (e: Exception) {
                last = e
                Thread.sleep(600L * (i + 1))
            }
        }
        throw last ?: RuntimeException("请求失败")
    }

    fun text(url: String, referer: String? = null, cookie: String? = null,
             ua: String = UA_MOBILE, timeout: Int = 30000, retries: Int = 3): String =
        String(bytes(url, referer, cookie, ua, timeout, retries = retries),
            Charsets.UTF_8)

    fun form(url: String, fields: Map<String, String>, referer: String? = null,
             cookie: String? = null, ua: String = UA_MOBILE,
             timeout: Int = 30000, extraHeaders: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder()
        for ((k, v) in fields) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(URLEncoder.encode(k, "UTF-8")).append('=')
                .append(URLEncoder.encode(v, "UTF-8"))
        }
        val body = sb.toString().toByteArray(Charsets.UTF_8)
        var last: Exception? = null
        for (i in 0 until 3) {
            try {
                val c = open(url, "POST", referer, cookie, ua, timeout, body,
                    "application/x-www-form-urlencoded; charset=UTF-8")
                for ((k, v) in extraHeaders) c.setRequestProperty(k, v)
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                if (code !in 200..299) { c.disconnect(); throw HttpError(code, "HTTP $code") }
                val t = String(read(stream(c)), Charsets.UTF_8)
                c.disconnect()
                return t
            } catch (e: Exception) {
                last = e
                Thread.sleep(600L * (i + 1))
            }
        }
        throw last ?: RuntimeException("请求失败")
    }

    fun json(url: String, referer: String? = null, cookie: String? = null,
             ua: String = UA_MOBILE, timeout: Int = 30000, retries: Int = 3): JSONObject =
        JSONObject(text(url, referer, cookie, ua, timeout, retries))

    fun jsonArray(url: String, referer: String? = null, ua: String = UA_MOBILE,
                  timeout: Int = 30000, retries: Int = 3): JSONArray =
        JSONArray(text(url, referer, null, ua, timeout, retries))

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 带进度的下载；onProgress(已下载, 总大小, 速度B/s) */
    fun download(
        url: String,
        referer: String?,
        cookie: String?,
        out: java.io.OutputStream,
        onProgress: ((Long, Long, Double) -> Unit)?,
        cancelled: () -> Boolean
    ): Long {
        val c = open(url, "GET", referer, cookie, UA_MOBILE, 60000, null, null)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw HttpError(code, "下载被拒绝（HTTP $code）")
        }
        val total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
        var done = 0L
        var lastT = System.currentTimeMillis()
        var lastB = 0L
        val buf = ByteArray(128 * 1024)
        stream(c)?.use { ins ->
            while (true) {
                if (cancelled()) { c.disconnect(); throw CancelledException() }
                val n = ins.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (onProgress != null && now - lastT >= 250) {
                    val speed = (done - lastB) * 1000.0 / (now - lastT)
                    onProgress(done, total, speed)
                    lastT = now
                    lastB = done
                }
            }
        }
        c.disconnect()
        if (total > 0 && done < total)
            throw HttpError(0, "下载不完整（$done / $total）")
        onProgress?.invoke(done, if (total > 0) total else done, 0.0)
        return done
    }

    class CancelledException : Exception("已取消")
}
