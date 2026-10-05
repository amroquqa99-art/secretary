package com.alsekretary.app.voice
import android.Manifest
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.*
import java.util.Locale
import java.time.Duration
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LocalVoiceTest {
    private lateinit var voice: LocalVoice
    private var status=""
    @Before fun setup(){voice=LocalVoice(RuntimeEnvironment.getApplication()) { status=it };ShadowTextToSpeech.getLastTextToSpeechInstance().let { Shadows.shadowOf(it).onInitListener.onInit(TextToSpeech.SUCCESS) };ShadowLooper.idleMainLooper()}
    @After fun cleanup(){voice.close()}
    private fun grant(){Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)}
    private fun results(text: String)=Bundle().apply {putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf(text))}
    @Test fun rejectsNetworkAndUninstalledVoicesWithoutSpeaking(){ShadowTextToSpeech.addVoice(Voice("network",Locale("ar"),300,300,true,emptySet()));ShadowTextToSpeech.addVoice(Voice("download",Locale("ar"),300,300,false,setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)));assertFalse(voice.speak("اختبار","ar"));assertNull(Shadows.shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)}
    @Test fun selectsInstalledOfflineVoiceAndStopsOnClose(){val offline=Voice("local",Locale.US,300,300,false,emptySet());ShadowTextToSpeech.addVoice(offline);assertTrue(voice.speak("test","en-US"));val engine=ShadowTextToSpeech.getLastTextToSpeechInstance();assertEquals(offline,Shadows.shadowOf(engine).currentVoice);voice.close();assertTrue(Shadows.shadowOf(engine).isShutdown)}
    @Test fun unavailableAsrDoesNotCreateCloudRecognizer(){ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false);Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);voice.listen("ar") { fail("Must not return speech") };assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer());assertTrue(status.contains("غير متوفر"))}
    @Test @Config(sdk=[26]) fun oldAndroidKeepsTextFallback(){grant();assertFalse(voice.recognitionAvailable());voice.listen("ar") { fail("No system on-device API on 26") };assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())}
    @Test fun deniedPermissionNeverStartsMic(){grant();Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);voice.listen("ar") { fail("Permission denied") };assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer());assertTrue(status.contains("إذن"))}
    @Test fun stoppedSessionCannotFeedNewConfirmation(){grant();var oldCount=0;var received="";voice.listen("ar") { oldCount++ };val old=Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer());voice.stop();voice.listen("ar") { received=it };old.triggerOnResults(results("أكد التنفيذ"));assertEquals(0,oldCount);assertEquals("",received);val current=Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer());current.triggerOnResults(results("قراءة"));assertEquals("قراءة",received);assertTrue(old.isDestroyed)}
    @Test fun stalledRecognitionExpiresAndDestroysMic(){grant();var received=false;voice.listen("ar") { received=true };val recognizer=Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer());ShadowLooper.idleMainLooper(60,java.util.concurrent.TimeUnit.SECONDS);assertTrue(recognizer.isDestroyed);recognizer.triggerOnResults(results("أكد التنفيذ"));assertFalse(received);assertTrue(status.contains("مهلة"))}
}
