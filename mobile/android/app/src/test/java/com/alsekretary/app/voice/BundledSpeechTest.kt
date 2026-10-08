package com.alsekretary.app.voice

import android.Manifest
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import com.alsekretary.app.data.AssistantStore
import com.alsekretary.app.data.SecretaryDatabase
import com.alsekretary.app.data.SecretaryRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledSpeechTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write("fixture".toByteArray()); z.closeEntry() } }
    }.toByteArray()
    @Test fun extractionRejectsTraversalForeignPrefixAndInvalidComponents() {
        for (name in listOf("other/file", "model/../escape", "model/./file", "model/a//file", "model/a\\file")) {
            val target = File(context.cacheDir, "safe-${name.hashCode()}").apply { mkdirs() }
            assertThrows(IllegalArgumentException::class.java) { SpeechModels.extract(ByteArrayInputStream(zip(name)), "model", target) { false } }
        }
        val target = File(context.cacheDir, "valid").apply { mkdirs() }
        SpeechModels.extract(ByteArrayInputStream(zip("model/conf/model.conf", "model/am/final.mdl")), "model", target) { false }
        assertEquals("fixture", File(target, "conf/model.conf").readText())
    }
    @Test fun cancellationInterruptsExtractionAndInstallerCleansStaging() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            SpeechModels.extract(ByteArrayInputStream(zip("model/a", "model/b")), "model", File(context.cacheDir, "cancel").apply { mkdirs() }) { ++checks > 2 }
        }
        checks = 0
        assertThrows(CancellationException::class.java) { SpeechModels.install(context, "en-US") { ++checks > 3 } }
        assertTrue(File(context.filesDir, "speech-models").listFiles().orEmpty().none { it.name.startsWith("staging-") })
        assertFalse(File(context.filesDir, "speech-models/en-${SpeechModels.english.sha256}").exists())
    }
    @Test fun actualBundledEnglishArchiveIsVerifiedAndInstalledOffline() {
        val model = SpeechModels.install(context, "en-US") { false }
        assertTrue(File(model, "am/final.mdl").length() > 0)
        assertTrue(File(model, "conf/model.conf").isFile)
        assertEquals(SpeechModels.english.sha256, File(model, "verified.sha256").readText())
        assertEquals(model, SpeechModels.install(context, "en") { false })
        assertThrows(IllegalArgumentException::class.java) { SpeechModels.spec("unknown") }
    }
    @Test fun microphonePermissionIsRequiredBeforeAServiceCanBeStarted() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertThrows(IllegalArgumentException::class.java) { BackgroundVoiceService.start(context, "ar", "يا سكرتير") }
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertFalse(BackgroundVoiceService.prefs(context).getBoolean("active", false))
    }
    @Test fun foregroundTransitionStopsBackgroundAudioAndConfirmationTogether() {
        val events = mutableListOf<String>()
        BackgroundVoiceService.prefs(context).edit().putBoolean("active", true).commit()
        ForegroundVoiceTransition({ BackgroundVoiceService.stop(context); events.add("background") }, { events.add("audio") }, { events.add("confirmation") }).run()
        assertFalse(BackgroundVoiceService.prefs(context).getBoolean("active", false))
        assertEquals(listOf("background", "audio", "confirmation"), events)
    }
    @Test fun disabledMicrophoneChannelBlocksStartEvenWhenAppNotificationsAreEnabled() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("voice-session","fixture",NotificationManager.IMPORTANCE_NONE))
        assertTrue(NotificationManagerCompat.from(context).areNotificationsEnabled())
        assertFalse(BackgroundVoiceService.notificationAvailable(context))
        assertThrows(IllegalArgumentException::class.java) { BackgroundVoiceService.start(context,"ar","يا سكرتير") }
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertEquals(NotificationManager.IMPORTANCE_NONE,manager.getNotificationChannel("voice-session").importance)
    }
    @Test fun onlyWokenCommandsAreRememberedAndTheyStayPending() {
        context.deleteDatabase("alsekretary.db")
        val policy = WakePolicy("يا سكرتير")
        SecretaryDatabase(context).use { db ->
            val store = AssistantStore(db); val repo = SecretaryRepository(db)
            listOf("حديث عادي", "أضف مهمة كلام محيط", "أكد التنفيذ").forEach { text -> policy.command(text, 100)?.let { store.submit(it) } }
            assertTrue(store.messages().isEmpty())
            policy.command("يا سكرتير أضف مهمة اختبار 123", 200)!!.let { store.submit(it) }
            assertEquals(2, store.messages().size)
            assertEquals("PENDING", store.proposals().single().status)
            assertTrue(repo.listTodayTasks(true).isEmpty())
        }
        assertEquals("الرد محفوظ داخل التطبيق. راجع الاقتراح قبل التنفيذ.", backgroundReply("ar", true))
    }
}
