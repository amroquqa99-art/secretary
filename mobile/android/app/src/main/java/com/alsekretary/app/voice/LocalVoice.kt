package com.alsekretary.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

/** Uses only the system's explicit on-device ASR and voices marked offline. No cloud fallback. */
class LocalVoice(private val context: Context,private val onStatus: (String)->Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer?=null
    private var tts: TextToSpeech?=null
    private var ready=false
    private var closed=false
    private var recording=false
    private var speechId: String?=null
    private var spoken: (() -> Unit)?=null
    private var generation=0
    private var result: ((String) -> Unit)?=null
    init {
        tts=TextToSpeech(context) { status -> handler.post {
            if(!closed){ready=status==TextToSpeech.SUCCESS;onStatus(if(ready) "الصوت المحلي جاهز للفحص" else "تعذر تشغيل محرك النطق المحلي")}
        } }
        tts?.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {handler.post {if(!closed && id==speechId){val callback=spoken;spoken=null;speechId=null;callback?.invoke()} } }
            @Deprecated("Legacy callback") override fun onError(id: String?) {handler.post {if(!closed && id==speechId){spoken=null;speechId=null;onStatus("فشل النطق؛ لم يبدأ تأكيد صوتي")}}}
        })
    }
    fun recognitionAvailable(): Boolean=Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    fun listen(language: String,callback: (String)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(closed)return
        if(recording){onStatus("انتظر انتهاء الاستماع الحالي");return}
        stop()
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){onStatus("امنح إذن الميكروفون أولاً");return}
        if(Build.VERSION.SDK_INT<31 || !recognitionAvailable()){onStatus("التعرف الصوتي المحلي غير متوفر؛ يحتاج Android 12 ومحركاً محلياً مثبتاً يدعم اللغة");return}
        try {
            recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val token=generation
            result=callback;recording=true
            recognizer!!.setRecognitionListener(object: RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {if(token==generation && !closed)onStatus("أستمع محلياً…")}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {if(token==generation && !closed)onStatus("جارٍ تحويل الصوت محلياً…")}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int,params: Bundle?) {}
                override fun onError(error: Int) {if(token!=generation || closed)return;recording=false;result=null;onStatus(when(error){SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"إذن الميكروفون مرفوض";SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE->"لغة التعرف المحلي غير مثبتة أو غير مدعومة";SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"لم ألتقط كلاماً واضحاً؛ أعد المحاولة";else->"تعذر التعرف المحلي ($error)؛ لم يُرسل الصوت للسحابة"})}
                override fun onResults(results: Bundle?) {
                    if(token!=generation || closed)return
                    recording=false
                    val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    val callback=result;result=null
                    if(text.isNullOrBlank())onStatus("لم ألتقط كلاماً واضحاً") else {onStatus("سمعت: $text");callback?.invoke(text)}
                }
            })
            handler.postDelayed({
                if(!closed && token==generation && recording) { stop();onStatus("انتهت مهلة الاستماع؛ لم ينفذ أي تأكيد") }
            },60_000)
            recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,language)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
            })
        } catch(e: Exception) {recording=false;result=null;recognizer?.destroy();recognizer=null;onStatus("تعذر بدء التعرف المحلي؛ استخدم الكتابة")}
    }
    fun speak(text: String,language: String,onDone: (() -> Unit)?=null): Boolean {
        if(closed || !ready){onStatus("محرك النطق لم يجهز بعد");return false}
        stop()
        val engine=tts ?: return false
        val locale=Locale.forLanguageTag(language)
        val voice=engine.voices?.filter { it.locale.language==locale.language && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale==locale }.thenBy { it.name })?.firstOrNull()
        if(voice==null){onStatus("لا يوجد صوت محلي مثبت لهذه اللغة؛ ثبّته من إعدادات النطق في الهاتف");return false}
        if(engine.setVoice(voice)!=TextToSpeech.SUCCESS){onStatus("تعذر اختيار الصوت المحلي");return false}
        val limit=TextToSpeech.getMaxSpeechInputLength()
        if(text.length>limit){onStatus("النص أطول من حد النطق؛ اقرأه على الشاشة");return false}
        speechId=UUID.randomUUID().toString();spoken=onDone
        if(engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,speechId)!=TextToSpeech.SUCCESS){speechId=null;spoken=null;onStatus("تعذر نطق النص");return false}
        onStatus("ينطق محلياً…");return true
    }
    fun stop() {generation++;result=null;spoken=null;speechId=null;recording=false;recognizer?.cancel();recognizer?.destroy();recognizer=null;tts?.stop()}
    fun close() {if(closed)return;stop();closed=true;tts?.shutdown();tts=null;handler.removeCallbacksAndMessages(null)}
}
