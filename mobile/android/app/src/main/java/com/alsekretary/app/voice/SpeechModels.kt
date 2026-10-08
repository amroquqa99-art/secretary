package com.alsekretary.app.voice

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream

data class SpeechModelSpec(val language: String, val name: String, val bytes: Long, val sha256: String, val unpackedLimit: Long)

object SpeechModels {
    val english = SpeechModelSpec("en", "vosk-model-small-en-us-0.15", 41205931L, "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", 128L * 1024 * 1024)
    val arabic = SpeechModelSpec("ar", "vosk-model-ar-mgb2-0.4", 333241610L, "357469ae1bb4d7a3810c9cd6b86d33bc135898dfc134e6df8bc2ddd28c5fe77a", 800L * 1024 * 1024)
    fun spec(language: String): SpeechModelSpec = when (language.substringBefore('-')) {
        "ar" -> arabic
        "en" -> english
        else -> throw IllegalArgumentException("لغة الصوت غير مدعومة")
    }
    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw CancellationException() }

    @Synchronized fun install(context: Context, language: String, cancelled: () -> Boolean): File {
        val model = spec(language)
        val root = File(context.filesDir, "speech-models").apply { mkdirs() }
        val target = File(root, "${model.language}-${model.sha256}")
        checkCancelled(cancelled)
        if (File(target, "verified.sha256").takeIf { it.isFile }?.readText() == model.sha256 &&
            File(target, "am/final.mdl").isFile && File(target, "conf/model.conf").isFile) return target
        require(root.usableSpace >= model.bytes + model.unpackedLimit + 32L * 1024 * 1024) { "لا توجد مساحة كافية لتجهيز لغة الصوت المحلية" }
        val staging = File(root, "staging-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val archive = File(staging, "model.zip")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.assets.open("speech/${model.name}.zip").use { input ->
                archive.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancelled(cancelled)
                        val n = input.read(buffer); if (n < 0) break
                        size += n; require(size <= model.bytes) { "حجم حزمة الصوت غير صحيح" }
                        digest.update(buffer, 0, n); out.write(buffer, 0, n)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(java.util.Locale.US, it) }
            require(size == model.bytes && hash == model.sha256) { "فشل التحقق من حزمة الصوت" }
            val unpacked = File(staging, "unpacked").apply { mkdirs() }
            archive.inputStream().use { extract(it, model.name, unpacked, model.unpackedLimit, cancelled) }
            require(File(unpacked, "am/final.mdl").isFile && File(unpacked, "conf/model.conf").isFile) { "حزمة الصوت ناقصة" }
            checkCancelled(cancelled)
            File(unpacked, "verified.sha256").writeText(model.sha256)
            if (target.exists()) require(target.deleteRecursively())
            require(unpacked.renameTo(target)) { "تعذر تثبيت لغة الصوت" }
            return target
        } finally { staging.deleteRecursively() }
    }

    internal fun extract(input: InputStream, prefix: String, destination: File, maxBytes: Long = 800L * 1024 * 1024, cancelled: () -> Boolean) {
        val names = hashSetOf<String>(); var total = 0L; var count = 0
        val base = destination.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                checkCancelled(cancelled)
                val entry = zip.nextEntry ?: break
                require(++count <= 1000) { "حزمة الصوت تحتوي ملفات أكثر من المسموح" }
                require(entry.name.startsWith("$prefix/") && '\\' !in entry.name) { "مسار غير صالح في حزمة الصوت" }
                val path = entry.name.removePrefix("$prefix/").trimEnd('/')
                if (path.isEmpty() && entry.isDirectory) continue
                require(path.isNotEmpty() && path.split('/').none { it.isEmpty() || it == "." || it == ".." } && names.add(path)) { "مسار غير صالح أو مكرر" }
                val file = File(base, path).canonicalFile
                require(file.path.startsWith(base.path + File.separator)) { "ملف خارج مجلد الصوت" }
                if (entry.isDirectory) { require(file.mkdirs() || file.isDirectory); continue }
                require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                file.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancelled(cancelled)
                        val n = zip.read(buffer); if (n < 0) break
                        total += n; require(total <= maxBytes) { "حزمة الصوت أكبر من المسموح" }
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
    }
}
