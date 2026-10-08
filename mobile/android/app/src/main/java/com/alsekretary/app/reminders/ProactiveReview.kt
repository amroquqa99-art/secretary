package com.alsekretary.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.alsekretary.app.MainActivity
import com.alsekretary.app.data.SecretaryDatabase
import com.alsekretary.app.data.SecretaryRepository
import com.alsekretary.app.domain.TaskStatus
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** An opt-in attention signal; it never changes records or confirms assistant proposals. */
class ProactiveReview(private val context: Context) {
    private val prefs=context.getSharedPreferences("proactive-review",Context.MODE_PRIVATE)
    fun enabled()=prefs.getBoolean("enabled",false)
    fun setEnabled(value: Boolean) { synchronized(deliveryLock) {
        prefs.edit().putBoolean("enabled",value).commit()
        if(!value)context.getSystemService(NotificationManager::class.java).cancel(JOB)
    };schedule() }
    fun schedule() {
        val scheduler=context.getSystemService(JobScheduler::class.java)
        if(enabled())scheduler.schedule(JobInfo.Builder(JOB,ComponentName(context,ProactiveReviewService::class.java)).setPeriodic(60*60_000L).setPersisted(true).build())
        else scheduler.cancel(JOB)
    }
    internal fun deliver(day: String,cancelled: ()->Boolean,send: ()->Unit): Boolean=synchronized(deliveryLock) {
        if(!enabled() || cancelled() || prefs.getString("last-day",null)==day)return@synchronized false
        send();prefs.edit().putString("last-day",day).commit();true
    }
    fun review(cancelled: ()->Boolean) {
        if(!enabled() || LocalTime.now().hour !in 8..21 || cancelled())return
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val today=LocalDate.now().toString()
        val needsAttention=SecretaryDatabase(context).use { db ->
            SecretaryRepository(db).listTodayTasks().any { it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED) &&
                listOfNotNull(it.scheduledEpochMillis,it.deadlineEpochMillis).any { time -> time<=System.currentTimeMillis() } }
        }
        if(!needsAttention || cancelled())return
        deliver(today,cancelled) {
            val manager=context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("proactive-review","مراجعة الحياة",NotificationManager.IMPORTANCE_DEFAULT))
            val open=PendingIntent.getActivity(context,JOB,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            manager.notify(JOB,NotificationCompat.Builder(context,"proactive-review").setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("وقت مراجعة خطتك").setContentText("افتح السكرتير لمراجعة ما يحتاج انتباهك").setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setAutoCancel(true).build())
        }
    }
    companion object {
        private val deliveryLock=Any();const val JOB=7402
        internal fun cancelDelivery(token: AtomicBoolean) { synchronized(deliveryLock) { token.set(true) } }
    }
}

class ProactiveReviewService: JobService() {
    private val executor=Executors.newSingleThreadExecutor()
    private val tokens=ConcurrentHashMap<JobParameters,AtomicBoolean>()
    override fun onStartJob(params: JobParameters): Boolean {
        val token=AtomicBoolean();tokens[params]=token
        executor.execute {
            try { ProactiveReview(applicationContext).review { token.get() } }
            catch(_: Exception) { /* A revoked permission or unavailable DB is retried by the next job. */ }
            finally { tokens.remove(params,token);if(!token.get())jobFinished(params,false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { tokens.remove(params)?.let(ProactiveReview::cancelDelivery);return false }
    override fun onDestroy() { tokens.values.forEach(ProactiveReview::cancelDelivery);tokens.clear();executor.shutdown();super.onDestroy() }
}
