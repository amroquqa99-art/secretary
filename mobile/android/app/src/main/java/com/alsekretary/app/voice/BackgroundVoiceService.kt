package com.alsekretary.app.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alsekretary.app.MainActivity
import com.alsekretary.app.data.AssistantStore
import com.alsekretary.app.data.SecretaryDatabase
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** An explicitly started, visible microphone session. Only woken commands enter the inbox. */
class BackgroundVoiceService : Service() {
    companion object {
        private const val STOP = "com.alsekretary.app.STOP_VOICE"
        private val requests = AtomicLong()
        private fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("voice-session", "استماع السكرتير", NotificationManager.IMPORTANCE_LOW))
        }
        internal fun notificationAvailable(context: Context): Boolean {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("voice-session")
            return NotificationManagerCompat.from(context).areNotificationsEnabled() && channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE &&
                (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        }
        fun prefs(context: Context) = context.getSharedPreferences("background-voice", Context.MODE_PRIVATE)
        fun start(context: Context, language: String, phrase: String) {
            SpeechModels.spec(language); WakePolicy(phrase)
            require(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "امنح إذن الميكروفون أولاً" }
            createChannel(context)
            require(notificationAvailable(context)) { "فعّل إشعارات استماع السكرتير لإظهار زر الإيقاف" }
            val request = requests.incrementAndGet()
            prefs(context).edit().putString("language", language).putString("phrase", phrase).apply()
            ContextCompat.startForegroundService(context, Intent(context, BackgroundVoiceService::class.java).putExtra("request", request).putExtra("language", language).putExtra("phrase", phrase))
        }
        fun stop(context: Context) {
            requests.incrementAndGet()
            prefs(context).edit().putBoolean("active", false).putString("status", "توقف الاستماع").apply()
            context.stopService(Intent(context, BackgroundVoiceService::class.java))
        }
    }
    private lateinit var speech: OfflineSpeech
    private lateinit var voice: LocalVoice
    private val handler = Handler(Looper.getMainLooper())
    private val commands = Executors.newSingleThreadExecutor()
    private var session = 0L
    @Volatile private var alive = true
    private var busy = false
    private fun current(token: Long) = alive && session == token && requests.get() == token
    private fun status(text: String) { prefs(this).edit().putString("status", text).apply() }

    override fun onCreate() {
        super.onCreate()
        speech = OfflineSpeech(this) { status(it) }
        voice = LocalVoice(this) { /* Foreground session state does not contain private speech. */ }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stop(this); return START_NOT_STICKY }
        val token = intent?.getLongExtra("request", 0) ?: 0
        if (token == 0L || requests.get() != token) { stopSelf(startId); return START_NOT_STICKY }
        createChannel(this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !notificationAvailable(this)) {
            status("إذن الميكروفون أو الإشعارات غير متوفر"); stopSelf(startId); return START_NOT_STICKY
        }
        try {
            val language = intent!!.getStringExtra("language") ?: "ar"
            val policy = WakePolicy(intent.getStringExtra("phrase") ?: "يا سكرتير")
            SpeechModels.spec(language)
            session = token; busy = false; voice.stop(); speech.stop()
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 0, Intent(this, BackgroundVoiceService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startForeground(7403, NotificationCompat.Builder(this, "voice-session").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("السكرتير يستمع محلياً").setContentText("جلسة بحد أقصى ساعة؛ اضغط إيقاف لإنهائها")
                .setContentIntent(open).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).addAction(0, "إيقاف", stop).build())
            prefs(this).edit().putBoolean("active", true).apply()
            speech.listen(language, continuous = true, allowed = { notificationAvailable(this) }, onEnded = { if (current(token)) stopSelf() }) { text ->
                if (!current(token) || busy) return@listen
                val normalized = com.alsekretary.app.domain.LocalAssistant.normalize(text)
                if (normalized in setOf("توقف الاستماع", "اوقف الاستماع", "stop listening")) { stop(this); return@listen }
                val command = policy.command(text, SystemClock.elapsedRealtime()) ?: return@listen
                if (com.alsekretary.app.domain.LocalAssistant.normalize(command) in setOf("توقف الاستماع", "اوقف الاستماع", "stop listening")) { stop(this); return@listen }
                busy = true; speech.pause(true)
                commands.execute {
                    val succeeded = runCatching {
                        if (!current(token)) return@runCatching false
                        SecretaryDatabase(this).use { db -> AssistantStore(db).submit(command, cancelled = { !current(token) }) }
                        true
                    }.getOrDefault(false)
                    handler.post {
                        if (!current(token)) return@post
                        val resume = { if (current(token)) { speech.pause(false); busy = false } }
                        if (!voice.speak(backgroundReply(language, succeeded), language, onFailure = resume, onDone = resume)) resume()
                    }
                }
            }
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ if (current(token)) stop(this) }, 3_600_000)
        } catch (_: Exception) { status("تعذر بدء جلسة الصوت المحلي"); stopSelf(startId) }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        alive = false
        requests.compareAndSet(session, session + 1)
        speech.close(); voice.close(); commands.shutdown(); handler.removeCallbacksAndMessages(null)
        prefs(this).edit().putBoolean("active", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
