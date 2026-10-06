package com.alsekretary.app.localmodel

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EmbeddingRunner(private val context: Context,private val store: EmbeddingModelStore) {
    private val lock=Any()
    private var activeHandle=0L

    fun cancel() { synchronized(lock) { if(activeHandle!=0L)NativeBridge.cancel(activeHandle) } }

    fun checkResources(checkMemory: Boolean=true,model: InstalledModel?=store.selected()) {
        require(model?.format==ModelFormat.GGUF) { "نزّل نموذج الذاكرة أو استورده أولاً" }
        require(Build.SUPPORTED_ABIS.any { it=="arm64-v8a" || it=="x86_64" }) { "الذاكرة الدلالية تحتاج نظام Android بمعمارية 64 بت" }
        if(checkMemory) {
            val memory=ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            val required=maxOf(1_300_000_000L,requireNotNull(model).bytes*16/10+250_000_000L)
            require(!memory.lowMemory && memory.availMem>=required) { "الذاكرة المتاحة قليلة لتشغيل نموذج الذاكرة؛ أغلق بعض التطبيقات أو استخدم الاسترجاع النصي" }
        }
        if(Build.VERSION.SDK_INT>=29) {
            require((context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE) {
                "الهاتف ساخن؛ انتظر قبل بناء الذاكرة الدلالية"
            }
        }
    }

    fun embedQuery(query: String,model: InstalledModel,token: AtomicBoolean): FloatArray {
        val instruction="Instruct: Retrieve the most relevant private personal-memory item for this user request.\nQuery: "
        return embedAll(listOf(instruction+query),model,token).single()
    }

    fun embedDocuments(texts: List<String>,model: InstalledModel,token: AtomicBoolean): List<FloatArray> =
        embedAll(texts,model,token)

    private fun embedAll(texts: List<String>,model: InstalledModel,token: AtomicBoolean): List<FloatArray> {
        require(texts.isNotEmpty() && texts.size<=500) { "عدد عناصر الفهرسة كبير" }
        require(texts.all { it.isNotBlank() && it.length<=4000 && '\u0000' !in it }) { "نص ذاكرة غير صالح" }
        checkCancelled(token);checkResources(model=model)
        val weights=store.verify(model);checkCancelled(token)
        val watchdog=Executors.newSingleThreadScheduledExecutor()
        var handle=0L
        watchdog.schedule({token.set(true);cancel()},120,TimeUnit.SECONDS)
        try {
            handle=NativeBridge.openEmbedding(weights.absolutePath)
            synchronized(lock) { require(activeHandle==0L);activeHandle=handle }
            checkCancelled(token);checkResources(false,model)
            return texts.map { text ->
                checkCancelled(token)
                NativeBridge.embed(handle,text.toByteArray(Charsets.UTF_8)).also { vector ->
                    require(vector.isNotEmpty() && vector.size<=8192 && vector.all { it.isFinite() }) { "أعاد نموذج الذاكرة متجهاً غير صالح" }
                }
            }
        } catch(e: Exception) {
            checkCancelled(token);throw e
        } finally {
            watchdog.shutdownNow()
            synchronized(lock) { if(activeHandle==handle)activeHandle=0L }
            if(handle!=0L)NativeBridge.close(handle)
        }
    }

    private fun checkCancelled(token: AtomicBoolean) {
        if(token.get())throw CancellationException("توقفت عملية الذاكرة الدلالية")
    }
}
