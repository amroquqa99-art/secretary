package com.alsekretary.app.localmodel

import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class ModelStoreTest {
    private lateinit var context: Context
    private lateinit var store: ModelStore
    private val bytes="LITERTLMfake-model-test-only".toByteArray()
    @Before fun setup(){context=RuntimeEnvironment.getApplication();File(context.filesDir,"local-models").deleteRecursively();context.getSharedPreferences("local-model-settings",0).edit().clear().commit();store=ModelStore(context,8)}
    @Test fun installSurvivesReopenAndRemainsOptIn(){assertFalse(store.enabled);val model=store.import(ByteArrayInputStream(bytes),"test");assertEquals(model,ModelStore(context,8).selected());assertArrayEquals(bytes,store.verify(model).readBytes());assertFalse(store.enabled)}
    @Test fun failedImportPreservesPreviousSelectionAndPreference(){val first=store.import(ByteArrayInputStream(bytes),"first");store.enabled=true;assertThrows(Exception::class.java){store.import(ByteArrayInputStream("invalid header".toByteArray()),"bad")};assertEquals(first,store.selected());assertTrue(store.enabled);assertTrue(File(context.filesDir,"local-models").listFiles()!!.none { it.name.endsWith(".part") })}
    @Test fun cancelledImportPreservesPreviousModel(){val first=store.import(ByteArrayInputStream(bytes),"first");assertThrows(Exception::class.java){store.import(ByteArrayInputStream(bytes),"second",AtomicBoolean(true))};assertEquals(first,store.selected())}
    @Test fun incorrectDigestCannotReplaceSelection(){val first=store.import(ByteArrayInputStream(bytes),"first");val part=File(context.filesDir,"local-models/test.part").apply {writeBytes(bytes)};assertThrows(Exception::class.java){store.commit(part,"bad","0".repeat(64))};assertEquals(first,store.selected())}
    @Test fun tamperedWeightsNeverVerify(){val model=store.import(ByteArrayInputStream(bytes),"test");store.file(model).writeBytes(bytes.copyOf().apply {this[lastIndex]=0});assertThrows(Exception::class.java){store.verify(model)}}
    @Test fun removeDisablesModeAndLeavesPersonalFiles(){store.import(ByteArrayInputStream(bytes),"test");store.enabled=true;val personal=File(context.filesDir,"keep.txt").apply {writeText("keep")};store.remove();assertNull(store.selected());assertFalse(store.enabled);assertEquals("keep",personal.readText())}
    @Test fun nativeConfigurationNeverAutomaticallyExecutesTools(){val config=ModelTools.configuration(true);assertFalse(config.automaticToolCalling);assertEquals(4,config.tools.size);assertEquals(128,config.maxOutputToken);assertFalse(config.thinkingConfig!!.enableThinking);assertTrue(ModelTools.configuration().tools.isEmpty())}
    @Test fun questionsDoNotAdvertiseActionTools(){assertFalse(ModelPrompt.requestsAction("عندي امتحان بكرة اقترح خطة للدراسة"));assertFalse(ModelPrompt.requestsAction("How can I create tasks?"));assertTrue(ModelPrompt.requestsAction("ممكن تضيف قراءة الفصل؟"));assertTrue(ModelPrompt.requestsAction("Please create a reading task"))}
}
