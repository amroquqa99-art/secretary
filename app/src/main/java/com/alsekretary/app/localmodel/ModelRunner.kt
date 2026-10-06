package com.alsekretary.app.localmodel

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.alsekretary.app.domain.ModelAnswer
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ModelRun(val answer: ModelAnswer,val elapsedMs: Long,val processPssKb: Long)

/** CPU-only foreground inference. The native layer has no action or network API. */
class ModelRunner(private val context: Context,private val store: ModelStore) {
    private val lock=Any()
    private var activeHandle=0L
    fun cancel() { synchronized(lock) { if(activeHandle!=0L)NativeBridge.cancel(activeHandle) } }
    fun checkResources(checkMemory: Boolean=true,model: InstalledModel?=store.selected()) {
        require(model?.format==ModelFormat.GGUF) { "المحرك الجديد يحتاج ملف GGUF؛ نزّل النموذج أو استورده" }
        require(Build.SUPPORTED_ABIS.any { it=="arm64-v8a" || it=="x86_64" }) { "النموذج يحتاج نظام Android بمعمارية 64 بت" }
        if(checkMemory) {
            val memory=ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            val required=maxOf(1_200_000_000L,requireNotNull(model).bytes*2+200_000_000L)
            require(!memory.lowMemory && memory.availMem>=required) { "الذاكرة المتاحة قليلة لتشغيل هذا النموذج؛ أغلق بعض التطبيقات أو استخدم الأوامر المكتوبة" }
        }
        if(Build.VERSION.SDK_INT>=29)require((context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE) { "الهاتف ساخن؛ انتظر قبل تشغيل النموذج" }
    }
    fun run(prompt: String,model: InstalledModel,token: AtomicBoolean,allowTools: Boolean=false,input: String=prompt): ModelRun {
        require(prompt.length<=3000) { "السياق طويل؛ اختصر الرسالة" }
        checkCancelled(token);checkResources(model=model)
        val start=SystemClock.elapsedRealtime();val weights=store.verify(model);checkCancelled(token)
        val watchdog=Executors.newSingleThreadScheduledExecutor()
        var handle=0L
        watchdog.schedule({token.set(true);cancel()},120,TimeUnit.SECONDS)
        try {
            handle=NativeBridge.open(weights.absolutePath)
            synchronized(lock) { require(activeHandle==0L);activeHandle=handle }
            checkCancelled(token);checkResources(false,model)
            val response=NativeBridge.generate(handle,
                ModelToolProtocol.system(input,allowTools).toByteArray(Charsets.UTF_8),
                prompt.toByteArray(Charsets.UTF_8),
                (if(allowTools)ModelToolProtocol.grammar else "").toByteArray(Charsets.UTF_8),128)
            checkCancelled(token)
            return ModelRun(ModelToolProtocol.decode(response.toString(Charsets.UTF_8),allowTools),SystemClock.elapsedRealtime()-start,Debug.getPss())
        } catch(e: Exception) {
            checkCancelled(token);throw e
        } finally {
            watchdog.shutdownNow()
            synchronized(lock) { if(activeHandle==handle)activeHandle=0L }
            if(handle!=0L)NativeBridge.close(handle)
        }
    }
    private fun checkCancelled(token: AtomicBoolean) { if(token.get())throw CancellationException("توقف توليد الرد أو انتهت مهلته") }
}
