package com.alsekretary.app.localmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

enum class ModelFormat(val suffix: String) { LITERTLM("litertlm"), GGUF("gguf") }
data class InstalledModel(val sha: String,val name: String,val bytes: Long,val format: ModelFormat=ModelFormat.LITERTLM)
object ModelCatalog {
    const val NAME="Qwen2.5 0.5B — Q4_K_M"
    const val SIZE=491400032L
    const val SHA="74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
    const val REVISION="9217f5db79a29953eb74d5343926648285ec7e67"
    const val URL="https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/$REVISION/qwen2.5-0.5b-instruct-q4_k_m.gguf"
}

/** Owned copies of public weights. Never included in the personal data backup. */
class ModelStore(context: Context,private val minimumBytes: Long=16L*1024*1024) {
    private val root=File(context.filesDir,"local-models").apply { mkdirs() }
    private val selection=AtomicFile(File(root,"selection.json"))
    private val preferences=context.getSharedPreferences("local-model-settings",Context.MODE_PRIVATE)
    var enabled: Boolean
        get()=preferences.getBoolean("enabled",false) && selected()?.format==ModelFormat.GGUF
        set(value){preferences.edit().putBoolean("enabled",value).apply()}
    fun selected(): InstalledModel? = runCatching {
        val sha=JSONObject(String(selection.readFully(),Charsets.UTF_8)).getString("sha")
        require(sha.matches(Regex("[a-f0-9]{64}")))
        val metadata=JSONObject(File(root,"$sha/info.json").readText())
        InstalledModel(sha,metadata.getString("name"),metadata.getLong("bytes"),ModelFormat.valueOf(metadata.optString("format","LITERTLM"))).also { require(file(it).isFile && file(it).length()==it.bytes) }
    }.getOrNull()
    fun file(model: InstalledModel): File {
        require(model.sha.matches(Regex("[a-f0-9]{64}")))
        return File(root,"${model.sha}/weights.${model.format.suffix}")
    }
    private fun digest(file: File): String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val buffer=ByteArray(262144);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)} }
        return hash.digest().joinToString("") { "%02x".format(java.util.Locale.US,it.toInt() and 255) }
    }
    fun verify(model: InstalledModel): File = file(model).also {
        require(it.length()==model.bytes && digest(it)==model.sha) { "ملف النموذج تالف؛ أعد تنزيله أو استيراده" }
    }
    internal fun commit(part: File,name: String,expected: String?=null,cancel: AtomicBoolean=AtomicBoolean()): InstalledModel {
        require(part.length() in minimumBytes..1500L*1024*1024) { "حجم النموذج غير مدعوم" }
        val header=ByteArray(8);DataInputStream(part.inputStream()).use { it.readFully(header) }
        require(String(header.copyOfRange(0,4),Charsets.US_ASCII)=="GGUF" && header[4].toInt() in 2..3 && header.sliceArray(5..7).all { it.toInt()==0 }) { "اختر ملف GGUF مدعوماً؛ ملفات LiteRT السابقة لا تعمل بالمحرك الجديد" }
        val sha=digest(part)
        require(expected==null || sha==expected) { "فشل التحقق من النموذج؛ لم يتغير النموذج السابق" }
        checkCancelled(cancel)
        var model=InstalledModel(sha,name.take(120),part.length(),ModelFormat.GGUF)
        val destination=File(root,sha)
        val cached=if(destination.exists())runCatching {
            val metadata=JSONObject(File(destination,"info.json").readText())
            InstalledModel(sha,metadata.getString("name"),metadata.getLong("bytes"),ModelFormat.valueOf(metadata.optString("format","LITERTLM"))).also { require(it.bytes==model.bytes && it.format==model.format);verify(it) }
        }.getOrNull() else null
        // Replace only a corrupt owned cache after the incoming copy has passed verification.
        if(destination.exists() && cached==null)require(destination.deleteRecursively())
        if(!destination.exists()) {
            val staging=File(root,"install-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                require(part.renameTo(File(staging,"weights.${model.format.suffix}")))
                File(staging,"info.json").writeText(JSONObject().put("name",model.name).put("bytes",model.bytes).put("format",model.format.name).toString())
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
    fun import(input: InputStream,name: String,cancel: AtomicBoolean=AtomicBoolean()): InstalledModel {
        val part=File(root,"import-${UUID.randomUUID()}.part")
        try {
            input.use { source -> part.outputStream().use { sink ->
                val buffer=ByteArray(262144);var total=0L
                while(true){checkCancelled(cancel);val n=source.read(buffer);if(n<0)break;total+=n;require(total<=1500L*1024*1024 && root.usableSpace>=8L*1024*1024) { "لا توجد مساحة كافية أو الملف أكبر من الحد" };sink.write(buffer,0,n)}
            } }
            return commit(part,name,cancel=cancel)
        } finally { part.delete() }
    }
    fun importUri(context: Context,uri: Uri,cancel: AtomicBoolean): InstalledModel {
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())it.getString(0) else null } ?: "نموذج مستورد"
        return import(requireNotNull(context.contentResolver.openInputStream(uri)),name,cancel)
    }
    fun download(cancel: AtomicBoolean,onProgress: (Long)->Unit): InstalledModel {
        val part=File(root,"download-${ModelCatalog.SHA}.part")
        if(part.length()>ModelCatalog.SIZE)part.delete()
        require(root.usableSpace>=ModelCatalog.SIZE-part.length()+64L*1024*1024) { "تحتاج مساحة إضافية لتنزيل النموذج" }
        val offset=part.length();onProgress(offset);checkCancelled(cancel)
        if(offset<ModelCatalog.SIZE) {
            val connection=URL(ModelCatalog.URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=20000;connection.readTimeout=20000
                if(offset>0)connection.setRequestProperty("Range","bytes=$offset-")
                val response=connection.responseCode
                require(response==200 || response==206) { "تعذر تنزيل النموذج ($response)؛ يمكنك إعادة المحاولة" }
                require(connection.url.protocol=="https")
                val append=offset>0 && response==206
                if(response==206)require(connection.getHeaderField("Content-Range")?.startsWith("bytes ${if(append)offset else 0}-")==true)
                var total=if(append)offset else 0L
                connection.inputStream.use { source -> FileOutputStream(part,append).use { sink ->
                    val buffer=ByteArray(262144)
                    while(true){checkCancelled(cancel);val n=source.read(buffer);if(n<0)break;total+=n;require(total<=ModelCatalog.SIZE);sink.write(buffer,0,n);onProgress(total)}
                } }
            } finally { connection.disconnect() }
        }
        checkCancelled(cancel)
        require(part.length()==ModelCatalog.SIZE) { "التنزيل غير مكتمل؛ أعد المحاولة لاستكماله" }
        try { return commit(part,ModelCatalog.NAME,ModelCatalog.SHA,cancel) }
        catch(e: CancellationException){throw e}
        catch(e: Exception){part.delete();throw e}
    }
    fun remove() { enabled=false;selection.delete();root.listFiles()?.forEach { it.deleteRecursively() } }
    private fun checkCancelled(cancel: AtomicBoolean) { if(cancel.get())throw CancellationException("توقفت العملية") }
}
