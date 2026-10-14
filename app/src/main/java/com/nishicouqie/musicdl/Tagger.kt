package com.nishicouqie.musicdl

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 音频标签写入（不依赖任何第三方库）。
 *
 * MP3 -> ID3v2.3（UTF-16 文本，兼容性最好）
 * FLAC -> Vorbis Comment + PICTURE 元数据块
 *
 * 之所以自己写而不用 jaudiotagger：那个库处理封面时依赖 java.awt，
 * 而 Android 没有 java.awt，跑起来会直接崩。
 */
object Tagger {

    data class Tag(
        val title: String,
        val artist: String,
        val album: String,
        val date: String,
        val genre: String,
        val track: String,
        val disc: String,
        val comment: String,
        val source: String,
        val cover: ByteArray?,
        val coverMime: String,
        val lyricBilingual: String,
        val lyricOriginal: String,
        val synced: List<Pair<Int, String>>
    )

    // ==================================================== 图片尺寸

    /** 从 JPEG/PNG 字节流里读出宽高（不依赖 BitmapFactory，省内存） */
    fun imageSize(b: ByteArray): Pair<Int, Int> {
        try {
            if (b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) {
                var i = 2
                while (i < b.size - 9) {
                    if (b[i] != 0xFF.toByte()) { i++; continue }
                    val m = b[i + 1].toInt() and 0xFF
                    if (m in intArrayOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6,
                            0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)) {
                        val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                        val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                        return w to h
                    }
                    val seg = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                    i += 2 + seg
                }
            } else if (b.size > 24 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) {
                var w = 0; var h = 0
                for (k in 0..3) w = (w shl 8) or (b[16 + k].toInt() and 0xFF)
                for (k in 0..3) h = (h shl 8) or (b[20 + k].toInt() and 0xFF)
                return w to h
            }
        } catch (_: Exception) {
        }
        return 0 to 0
    }

    // ==================================================== MP3 / ID3v2.3

    private fun utf16(s: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = ByteArray(10 + payload.size)
        System.arraycopy(id.toByteArray(Charsets.US_ASCII), 0, out, 0, 4)
        out[4] = ((payload.size ushr 24) and 0xFF).toByte()
        out[5] = ((payload.size ushr 16) and 0xFF).toByte()
        out[6] = ((payload.size ushr 8) and 0xFF).toByte()
        out[7] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 10, payload.size)
        return out
    }

    private fun textFrame(id: String, value: String): ByteArray? {
        if (value.isBlank()) return null
        return frame(id, byteArrayOf(1) + utf16(value))
    }

    private fun buildId3(t: Tag): ByteArray {
        val frames = ArrayList<ByteArray>()
        textFrame("TIT2", t.title)?.let { frames.add(it) }
        textFrame("TPE1", t.artist)?.let { frames.add(it) }
        textFrame("TALB", t.album)?.let { frames.add(it) }
        textFrame("TPE2", t.artist)?.let { frames.add(it) }
        textFrame("TRCK", t.track)?.let { frames.add(it) }
        textFrame("TPOS", t.disc)?.let { frames.add(it) }
        textFrame("TYER", t.date.take(4))?.let { frames.add(it) }
        // TDAT = DDMM，配合 TYER 才能让播放器显示到「日」
        val d = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(t.date)
        if (d != null) textFrame("TDAT", d.groupValues[3] + d.groupValues[2])?.let { frames.add(it) }
        textFrame("TCON", t.genre)?.let { frames.add(it) }
        textFrame("TENC", "音乐下载器 Android")?.let { frames.add(it) }

        if (t.comment.isNotBlank()) {
            frames.add(frame("COMM",
                byteArrayOf(1) + "XXX".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0, 0) + utf16(t.comment)))
        }
        if (t.source.isNotBlank()) {
            // TXXX 的描述字段也必须跟编码一致：UTF-16 + BOM，并以 00 00 结束
            frames.add(frame("TXXX",
                byteArrayOf(1) + utf16("SOURCE") + byteArrayOf(0, 0) + utf16(t.source)))
        }
        t.cover?.let { cov ->
            val mime = (if (t.coverMime.isBlank()) mimeOf(cov) else t.coverMime)
                .toByteArray(Charsets.US_ASCII)
            val body = byteArrayOf(1) + mime + byteArrayOf(0) + byteArrayOf(3) +
                       byteArrayOf(0, 0) + cov
            frames.add(frame("APIC", body))
        }
        if (t.lyricBilingual.isNotBlank()) {
            frames.add(frame("USLT",
                byteArrayOf(1) + "jpn".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0, 0) + utf16(t.lyricBilingual)))
        }
        if (t.synced.isNotEmpty()) {
            val body = java.io.ByteArrayOutputStream()
            body.write(byteArrayOf(1))                                  // encoding
            body.write("jpn".toByteArray(Charsets.US_ASCII))            // language
            body.write(byteArrayOf(2))                                  // format = ms
            body.write(byteArrayOf(1))                                  // type = lyrics
            body.write(byteArrayOf(0, 0))                               // desc = ""
            for ((ms, txt) in t.synced) {
                if (txt.isBlank()) continue
                body.write(utf16(txt))
                body.write(byteArrayOf(0, 0))                           // terminator
                body.write(byteArrayOf(
                    ((ms ushr 24) and 0xFF).toByte(), ((ms ushr 16) and 0xFF).toByte(),
                    ((ms ushr 8) and 0xFF).toByte(), (ms and 0xFF).toByte()))
            }
            frames.add(frame("SYLT", body.toByteArray()))
        }

        var size = 0
        for (f in frames) size += f.size
        val padding = 1024
        size += padding

        val out = java.io.ByteArrayOutputStream(10 + size)
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 0))     // v2.3.0
        out.write(byteArrayOf(0))        // flags
        // syncsafe 长度
        out.write(byteArrayOf(
            ((size ushr 21) and 0x7F).toByte(), ((size ushr 14) and 0x7F).toByte(),
            ((size ushr 7) and 0x7F).toByte(), (size and 0x7F).toByte()))
        for (f in frames) out.write(f)
        out.write(ByteArray(padding))
        return out.toByteArray()
    }

    private fun mimeOf(b: ByteArray): String =
        if (b.size > 8 && b[0] == 0x89.toByte()) "image/png" else "image/jpeg"

    fun writeMp3(file: File, t: Tag) {
        val tagBytes = buildId3(t)
        val tmp = File(file.parentFile, file.name + ".tagtmp")
        RandomAccessFile(file, "r").use { raf ->
            // 跳过已有的 ID3v2 标签
            var skip = 0L
            val head = ByteArray(10)
            if (raf.length() >= 10) {
                raf.readFully(head)
                if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
                    head[2] == '3'.code.toByte()) {
                    skip = 10L + (((head[6].toInt() and 0x7F) shl 21) or
                                  ((head[7].toInt() and 0x7F) shl 14) or
                                  ((head[8].toInt() and 0x7F) shl 7) or
                                  (head[9].toInt() and 0x7F))
                }
            }
            raf.seek(skip)
            BufferedOutputStream(FileOutputStream(tmp), 256 * 1024).use { out ->
                out.write(tagBytes)
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }

    // ==================================================== FLAC

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte())

    private fun be32(v: Int) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun blockHeader(type: Int, len: Int, last: Boolean) = byteArrayOf(
        (((if (last) 1 else 0) shl 7) or (type and 0x7F)).toByte(),
        ((len ushr 16) and 0xFF).toByte(),
        ((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte())

    private fun vorbisComment(t: Tag): ByteArray {
        val vendor = "音乐下载器 Android".toByteArray(Charsets.UTF_8)
        val items = ArrayList<Pair<String, String>>()
        fun add(k: String, v: String) { if (v.isNotBlank()) items.add(k to v) }
        add("TITLE", t.title); add("ARTIST", t.artist); add("ALBUM", t.album)
        add("ALBUMARTIST", t.artist)
        val tr = t.track.split("/")
        add("TRACKNUMBER", tr.getOrNull(0) ?: ""); add("TRACKTOTAL", tr.getOrNull(1) ?: "")
        val dc = t.disc.split("/")
        add("DISCNUMBER", dc.getOrNull(0) ?: ""); add("DISCTOTAL", dc.getOrNull(1) ?: "")
        add("DATE", t.date); add("GENRE", t.genre)
        add("COMMENT", t.comment); add("SOURCE", t.source)
        add("LYRICS", t.lyricBilingual)
        add("UNSYNCEDLYRICS", t.lyricBilingual)
        add("LYRICS_ORIGINAL", t.lyricOriginal)

        val out = java.io.ByteArrayOutputStream()
        out.write(le32(vendor.size)); out.write(vendor)
        out.write(le32(items.size))
        for ((k, v) in items) {
            val kv = "$k=$v".toByteArray(Charsets.UTF_8)
            out.write(le32(kv.size)); out.write(kv)
        }
        return out.toByteArray()
    }

    private fun flacPicture(t: Tag): ByteArray {
        val cov = t.cover ?: return ByteArray(0)
        val mime = (if (t.coverMime.isBlank()) mimeOf(cov) else t.coverMime)
            .toByteArray(Charsets.US_ASCII)
        val desc = ByteArray(0)
        val (w, h) = imageSize(cov)
        val out = java.io.ByteArrayOutputStream()
        out.write(be32(3))               // type = front cover
        out.write(be32(mime.size)); out.write(mime)
        out.write(be32(desc.size)); out.write(desc)
        out.write(be32(w)); out.write(be32(h))
        out.write(be32(24)); out.write(be32(0))
        out.write(be32(cov.size)); out.write(cov)
        return out.toByteArray()
    }

    fun writeFlac(file: File, t: Tag) {
        val raf = RandomAccessFile(file, "r")
        val magic = ByteArray(4)
        raf.readFully(magic)
        if (!(magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() &&
              magic[2] == 'a'.code.toByte() && magic[3] == 'C'.code.toByte()))
            throw IllegalArgumentException("不是 FLAC 文件")

        var streamInfo: ByteArray? = null
        val buf = ByteArray(4)
        while (true) {
            raf.readFully(buf)
            val last = (buf[0].toInt() and 0x80) != 0
            val type = buf[0].toInt() and 0x7F
            val len = ((buf[1].toInt() and 0xFF) shl 16) or
                      ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (type == 0) {
                streamInfo = ByteArray(len)
                raf.readFully(streamInfo)
            } else {
                raf.seek(raf.filePointer + len)
            }
            if (last) break
        }
        val audioStart = raf.filePointer
        val si = streamInfo ?: run { raf.close(); throw IllegalArgumentException("缺 STREAMINFO") }

        val tmp = File(file.parentFile, file.name + ".tagtmp")
        RandomAccessFile(tmp, "rw").use { out ->
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            out.write(blockHeader(0, si.size, false)); out.write(si)

            val vc = vorbisComment(t)
            val pic = flacPicture(t)
            out.write(blockHeader(4, vc.size, pic.isEmpty()))
            out.write(vc)
            if (pic.isNotEmpty()) {
                out.write(blockHeader(6, pic.size, true)); out.write(pic)
            }

            // 原样搬运音频帧
            raf.seek(audioStart)
            val copy = ByteArray(256 * 1024)
            while (true) {
                val n = raf.read(copy)
                if (n <= 0) break
                out.write(copy, 0, n)
            }
        }
        raf.close()
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }

    fun write(file: File, t: Tag) {
        when (file.extension.lowercase()) {
            "mp3" -> writeMp3(file, t)
            "flac" -> writeFlac(file, t)
            else -> throw IllegalArgumentException("不支持的格式：${file.extension}")
        }
    }
}
