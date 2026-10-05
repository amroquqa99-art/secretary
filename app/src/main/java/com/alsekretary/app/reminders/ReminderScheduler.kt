package com.alsekretary.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alsekretary.app.MainActivity
import com.alsekretary.app.data.SecretaryDatabase
import com.alsekretary.app.data.SecretaryRepository
import com.alsekretary.app.domain.TaskStatus
import java.util.concurrent.Executors

/** Best effort inexact alarms; does not claim minute-precise delivery. */
class ReminderScheduler(private val context: Context) {
    private val prefs=context.getSharedPreferences("reminders",Context.MODE_PRIVATE)
    private val alarms=context.getSystemService(AlarmManager::class.java)
    fun enabled()=prefs.getBoolean("enabled",false)
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled",value).apply() }
    private fun pending(key: String,due: Long=0,title: String=""): PendingIntent {
        val intent=Intent(context,ReminderReceiver::class.java).apply {
            data=Uri.Builder().scheme("alsekretary").authority("reminder").appendPath(key).build()
            putExtra("key",key);putExtra("due",due);putExtra("title",title)
        }
        return PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun sync(repo: SecretaryRepository) {
        val now=System.currentTimeMillis()
        val old=prefs.getStringSet("keys",emptySet()).orEmpty().toSet()
        old.forEach { alarms.cancel(pending(it)) }
        val current=linkedMapOf<String,Pair<Long,String>>()
        if(enabled()) {
            repo.listTodayTasks().filter{it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED)}.forEach { task ->
                (task.scheduledEpochMillis ?: task.deadlineEpochMillis)?.let { due -> if(due>now) current["task:${task.id}"]=due to task.title }
            }
            repo.listCalendarEntries(now,Long.MAX_VALUE).filter{it.linkedEntityType!="TASK"}.forEach { e ->
                current["calendar:${e.id}"]=e.startEpochMillis to e.title
            }
        }
        current.forEach { (key,event) ->
            val trigger=(event.first-10*60_000L).coerceAtLeast(now+1000)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger,pending(key,event.first,event.second))
        }
        prefs.edit().putStringSet("keys",current.keys.toSet()).apply()
    }
    fun deliver(repo: SecretaryRepository,key: String,due: Long) {
        if(!enabled() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val title=if(key.startsWith("task:")) {
            repo.taskById(key.removePrefix("task:"))?.takeIf { it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED) && (it.scheduledEpochMillis ?: it.deadlineEpochMillis)==due }?.title
        } else {
            repo.listCalendarEntries(due,due+1).firstOrNull { "calendar:${it.id}"==key }?.title
        } ?: return
        if(prefs.getLong("delivered:$key",-1)==due) return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("plan","مواعيد السكرتير",NotificationManager.IMPORTANCE_DEFAULT))
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,"plan")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("موعدك القادم")
            .setContentText(title).setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify(key,0,notification)
        prefs.edit().putLong("delivered:$key",due).apply()
    }
}

class ReminderReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val key=intent.getStringExtra("key") ?: return
        val due=intent.getLongExtra("due",-1)
        val pending=goAsync()
        executor.execute {
            val database=SecretaryDatabase(context.applicationContext)
            try { ReminderScheduler(context).deliver(SecretaryRepository(database),key,due) }
            finally { database.close();pending.finish() }
        }
    }
    companion object { private val executor=Executors.newSingleThreadExecutor() }
}
class ReminderRestoreReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending=goAsync()
        executor.execute {
            val database=SecretaryDatabase(context.applicationContext)
            try { ReminderScheduler(context).sync(SecretaryRepository(database)) }
            finally { database.close();pending.finish() }
        }
    }
    companion object { private val executor=Executors.newSingleThreadExecutor() }
}
