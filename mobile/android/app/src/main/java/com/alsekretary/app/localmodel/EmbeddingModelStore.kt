package com.alsekretary.app.localmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

object EmbeddingModelCatalog {
    const val NAME="Qwen3 Embedding 0.6B — Q8_0"
    const val SIZE=639150592L
    const val SHA="06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439"
    const val REVISION="d20cf9c16f82914a21dbd9c645f56895fb1d7750"
    const val URL="https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/$REVISION/Qwen3-Embedding-0.6B-Q8_0.gguf"
}

class EmbeddingModelStore(context: Context,private val minimumBytes: Long=16L*1024*1024) {
    private val root=File(context.filesDir,"local-embedding-model").apply { mkdirs() }
    private val selection=AtomicFile(File(root,"selection.json"))
    private val preferences=context.getSharedPreferences("semantic-memory-settings",Context.MODE_PRIVATE)

    var enabled: Boolean
        get()=preferences.getBoolean("enabled",false) && selected()!=null
        set(value){preferences.edit().putBoolean("enabled",value).apply()}

    fun selected(): InstalledModel? = runCatching {
        val sha=JSONObject(String(selection.readFully(),Charsets.UTF_8)).getString("sha")
        require(sha.matches(Regex("[a-f0-9]{64}")))
        val metadata=JSONObject(File(root,"$sha/info.json").readText())
        InstalledModel(sha,metadata.getString("name"),metadata.getLong("bytes"),ModelFormat.GGUF)
            .also { require(file(it).isFile && file(it).length()==it.bytes) }
    }.getOrNull()

    fun file(model: InstalledModel): File {
        require(model.sha.matches(Regex("[a-f0-9]{64}")) && model.format==ModelFormat.GGUF)
        return File(root,"${model.sha}/weights.gguf")
    }

    fun verify(model: InstalledModel): File = file(model).also {
        require(it.length()==model.bytes && digest(it)==model.sha) { "ملف نموذج الذاكرة تالف؛ أعد تنزيله أو استيراده" }
    }

    internal fun commit(part: File,name: String,expected: String?=null,cancel: AtomicBoolean=AtomicBoolean()): InstalledModel {
        require(part.length() in minimumBytes..1500L*1024*1024) { "حجم نموذج الذاكرة غير مدعوم" }
        val header=ByteArray(8)
        DataInputStream(part.inputStream()).use { it.readFully(header) }
        require(String(header.copyOfRange(0,4),Charsets.US_ASCII)=="GGUF" && header[4].toInt() in 2..3 && header.sliceArray(5..7).all { it.toInt()==0 }) {
            "اختر ملف GGUF مدعوماً"
        }
        val sha=digest(part)
        require(expected==null || sha==expected) { "فشل التحقق من نموذج الذاكرة؛ لم يتغير النموذج السابق" }
        checkCancelled(cancel)
        var model=InstalledModel(sha,name.take(120),part.length(),ModelFormat.GGUF)
        val destination=File(root,sha)
        val cached=if(destination.exists())runCatching {
            val metadata=JSONObject(File(destination,"info.json").readText())
            InstalledModel(sha,metadata.getString("name"),metadata.getLong("bytes"),ModelFormat.GGUF)
                .also { require(it.bytes==model.bytes);verify(it) }
        }.getOrNull() else null
        if(destination.exists() && cached==null)require(destination.deleteRecursively())
        if(!destination.exists()) {
            val staging=File(root,"install-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                require(part.renameTo(File(staging,"weights.gguf")))
                File(staging,"info.json").writeText(JSONObject().put("name",model.name).put("bytes",model.bytes).toString())
                require(staging.renameTo(destination))
            } finally { staging.deleteRecursively() }
        } else { model=requireNotNull(cached);part.delete() }
        checkCancelled(cancel)
        val output=selection.startWrite()
        try { output.write(JSONObject().put("sha",sha).toString().toByteArray());selection.finishWrite(output) }
        catch(e: Exception){selection.failWrite(output);throw e}
        enabled=false
        return model
    }

    fun importUri(context: Context,uri: Uri,cancel: AtomicBoolean): InstalledModel {
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst())it.getString(0) else null
        } ?: "نموذج ذاكرة مستورد"
        val part=File(root,"import-${UUID.randomUUID()}.part")
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { source -> copy(source,part,cancel,1500L*1024*1024) }
            return commit(part,name,cancel=cancel)
        } finally { part.delete() }
    }

    fun download(cancel: AtomicBoolean,onProgress: (Long)->Unit): InstalledModel {
        val part=File(root,"download-${EmbeddingModelCatalog.SHA}.part")
        if(part.length()>EmbeddingModelCatalog.SIZE)part.delete()
        require(root.usableSpace>=EmbeddingModelCatalog.SIZE-part.length()+64L*1024*1024) { "تحتاج مساحة إضافية لتنزيل نموذج الذاكرة" }
        val offset=part.length();onProgress(offset);checkCancelled(cancel)
        if(offset<EmbeddingModelCatalog.SIZE) {
            val connection=URL(EmbeddingModelCatalog.URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=20000;connection.readTimeout=20000
                if(offset>0)connection.setRequestProperty("Range","bytes=$offset-")
                val response=connection.responseCode
                require(response==200 || response==206) { "تعذر تنزيل نموذج الذاكرة ($response)؛ يمكنك إعادة المحاولة" }
                require(connection.url.protocol=="https")
                val append=offset>0 && response==206
                if(response==206) {
                    val expectedStart=if(append)offset else 0
                    require(connection.getHeaderField("Content-Range")?.startsWith("bytes $expectedStart-")==true)
                }
                var total=if(append)offset else 0L
                connection.inputStream.use { source -> FileOutputStream(part,append).use { sink ->
                    val buffer=ByteArray(262144)
                    while(true) {
                        checkCancelled(cancel);val n=source.read(buffer);if(n<0)break
                        total+=n;require(total<=EmbeddingModelCatalog.SIZE)
                        sink.write(buffer,0,n);onProgress(total)
                    }
                } }
            } finally { connection.disconnect() }
        }
        checkCancelled(cancel)
        require(part.length()==EmbeddingModelCatalog.SIZE) { "تنزيل نموذج الذاكرة غير مكتمل؛ أعد المحاولة لاستكماله" }
        try { return commit(part,EmbeddingModelCatalog.NAME,EmbeddingModelCatalog.SHA,cancel) }
        catch(e: CancellationException){throw e}
        catch(e: Exception){part.delete();throw e}
    }

    fun remove() { enabled=false;selection.delete();root.listFiles()?.forEach { it.deleteRecursively() } }

    private fun copy(source: InputStream,target: File,cancel: AtomicBoolean,max: Long) {
        target.outputStream().use { sink ->
            val buffer=ByteArray(262144);var total=0L
            while(true) {
                checkCancelled(cancel);val n=source.read(buffer);if(n<0)break
                total+=n;require(total<=max && root.usableSpace>=8L*1024*1024) { "لا توجد مساحة كافية أو الملف أكبر من الحد" }
                sink.write(buffer,0,n)
            }
        }
    }
    private fun digest(file: File): String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer=ByteArray(262144)
            while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}
        }
        return hash.digest().joinToString("") { "%02x".format(java.util.Locale.US,it.toInt() and 255) }
    }
    private fun checkCancelled(cancel: AtomicBoolean) { if(cancel.get())throw CancellationException("توقفت العملية") }
}
