package com.alsekretary.app.voice

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The worker owns native handles through cleanup; stop only fences callbacks and unblocks audio. */
class OfflineSpeech(private val context: Context, private val onStatus: (String) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val paused = AtomicBoolean()
    private val audioLock = Any()
    private var audio: AudioRecord? = null
    companion object { private val sessionLock = Any() }
    private fun post(token: Long, callback: () -> Unit) { handler.post { if (!closed.get() && generation.get() == token) callback() } }

    fun listen(language: String, continuous: Boolean = false, allowed: () -> Boolean = { true }, onEnded: () -> Unit = {}, onText: (String) -> Unit) {
        if (closed.get()) return
        stop(); paused.set(false)
        val token = generation.get()
        val cancelled = { closed.get() || generation.get() != token }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("امنح إذن الميكروفون أولاً"); return
        }
        executor.execute {
            synchronized(sessionLock) {
                var model: Model? = null; var recognizer: Recognizer? = null; var recorder: AudioRecord? = null
                try {
                    if (cancelled()) throw CancellationException()
                    require(allowed()) { "توقف الاستماع؛ إشعار الإيقاف غير متاح" }
                    val spec = SpeechModels.spec(language)
                    val memory = ActivityManager.MemoryInfo()
                    context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                    require(!memory.lowMemory && memory.availMem >= if (spec.language == "ar") 1_100_000_000L else 500_000_000L) { "الذاكرة المتاحة لا تكفي للصوت المحلي؛ أغلق التطبيقات وحاول مجدداً" }
                    post(token) { onStatus("جارٍ تجهيز لغة الصوت محلياً…") }
                    val directory = SpeechModels.install(context, language, cancelled)
                    if (cancelled()) throw CancellationException()
                    model = Model(directory.path)
                    if (cancelled()) throw CancellationException()
                    recognizer = Recognizer(model, 16000f)
                    require(allowed()) { "توقف الاستماع؛ إشعار الإيقاف غير متاح" }
                    val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    require(minimum > 0) { "الميكروفون لا يدعم معدل الصوت المطلوب" }
                    recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
                    require(recorder.state == AudioRecord.STATE_INITIALIZED) { "تعذر تهيئة الميكروفون" }
                    synchronized(audioLock) { if (cancelled()) throw CancellationException();require(allowed()) { "توقف الاستماع؛ إشعار الإيقاف غير متاح" }; audio = recorder; recorder.startRecording() }
                    require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "تعذر بدء الميكروفون" }
                    post(token) { onStatus("أستمع محلياً…") }
                    val deadline = SystemClock.elapsedRealtime() + if (continuous) 3_600_000L else 60_000L
                    val buffer = ShortArray(4096); var wasPaused = false
                    while (!cancelled() && SystemClock.elapsedRealtime() < deadline) {
                        val n = recorder.read(buffer, 0, buffer.size)
                        if (cancelled()) break
                        require(allowed()) { "توقف الاستماع؛ إشعار الإيقاف غير متاح" }
                        require(n > 0) { "توقف الميكروفون" }
                        if (paused.get()) { wasPaused = true; continue }
                        if (wasPaused) { recognizer.reset(); wasPaused = false }
                        if (recognizer.acceptWaveForm(buffer, n)) {
                            val text = JSONObject(recognizer.result).optString("text").trim()
                            if (text.isNotBlank()) {
                                post(token) { onText(text) }
                                if (!continuous) break
                            }
                        }
                    }
                    if (!cancelled() && !continuous && SystemClock.elapsedRealtime() >= deadline) post(token) { onStatus("انتهت مهلة الاستماع؛ أعد المحاولة") }
                    if (!cancelled() && continuous) post(token) { onStatus("انتهت جلسة الاستماع بعد ساعة") }
                } catch (_: CancellationException) {
                } catch (e: Exception) { post(token) { onStatus(e.message?.takeIf { it.any { c -> c in '\u0600'..'\u06ff' } } ?: "تعذر التعرف المحلي؛ استخدم الكتابة") } }
                finally {
                    synchronized(audioLock) { if (audio === recorder) audio = null; runCatching { recorder?.stop() }; recorder?.release() }
                    try { recognizer?.close() } finally { model?.close() }
                    post(token, onEnded)
                }
            }
        }
    }
    fun pause(value: Boolean) { paused.set(value) }
    fun stop() { generation.incrementAndGet(); synchronized(audioLock) { runCatching { audio?.stop() } } }
    fun close() { if (closed.compareAndSet(false, true)) { stop(); executor.shutdown(); handler.removeCallbacksAndMessages(null) } }
}
