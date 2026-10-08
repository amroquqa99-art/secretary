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
    private val bytes=byteArrayOf(71,71,85,70,3,0,0,0)+"fake-model-test-only".toByteArray()
    @Before fun setup(){context=RuntimeEnvironment.getApplication();File(context.filesDir,"local-models").deleteRecursively();context.getSharedPreferences("local-model-settings",0).edit().clear().commit();store=ModelStore(context,8)}
    @Test fun installSurvivesReopenAndRemainsOptIn(){assertFalse(store.enabled);val model=store.import(ByteArrayInputStream(bytes),"test");assertEquals(model,ModelStore(context,8).selected());assertArrayEquals(bytes,store.verify(model).readBytes());assertFalse(store.enabled)}
    @Test fun failedImportPreservesPreviousSelectionAndPreference(){val first=store.import(ByteArrayInputStream(bytes),"first");store.enabled=true;assertThrows(Exception::class.java){store.import(ByteArrayInputStream("invalid header".toByteArray()),"bad")};assertEquals(first,store.selected());assertTrue(store.enabled);assertTrue(File(context.filesDir,"local-models").listFiles()!!.none { it.name.endsWith(".part") })}
    @Test fun cancelledImportPreservesPreviousModel(){val first=store.import(ByteArrayInputStream(bytes),"first");assertThrows(Exception::class.java){store.import(ByteArrayInputStream(bytes),"second",AtomicBoolean(true))};assertEquals(first,store.selected())}
    @Test fun incorrectDigestCannotReplaceSelection(){val first=store.import(ByteArrayInputStream(bytes),"first");val part=File(context.filesDir,"local-models/test.part").apply {writeBytes(bytes)};assertThrows(Exception::class.java){store.commit(part,"bad","0".repeat(64))};assertEquals(first,store.selected())}
    @Test fun tamperedWeightsNeverVerify(){val model=store.import(ByteArrayInputStream(bytes),"test");store.file(model).writeBytes(bytes.copyOf().apply {this[lastIndex]=0});assertThrows(Exception::class.java){store.verify(model)}}
    @Test fun verifiedReimportRepairsCorruptOwnedCache(){val model=store.import(ByteArrayInputStream(bytes),"test");store.file(model).writeBytes(bytes.copyOf().apply {this[lastIndex]=0});val repaired=store.import(ByteArrayInputStream(bytes),"repaired");assertArrayEquals(bytes,store.verify(repaired).readBytes());assertEquals(repaired,store.selected())}
    @Test fun duplicateImportReturnsThePersistedIdentity(){val first=store.import(ByteArrayInputStream(bytes),"first");val duplicate=store.import(ByteArrayInputStream(bytes),"second");assertEquals(first,duplicate);assertEquals(duplicate,store.selected())}
    @Test fun removeDisablesModeAndLeavesPersonalFiles(){store.import(ByteArrayInputStream(bytes),"test");store.enabled=true;val personal=File(context.filesDir,"keep.txt").apply {writeText("keep")};store.remove();assertNull(store.selected());assertFalse(store.enabled);assertEquals("keep",personal.readText())}
    @Test fun legacyWeightsRemainReadableButCannotBeEnabled(){
        val sha="a".repeat(64);val root=File(context.filesDir,"local-models");val dir=File(root,sha).apply {mkdirs()}
        File(dir,"weights.litertlm").writeBytes(bytes)
        File(dir,"info.json").writeText("{\"name\":\"legacy\",\"bytes\":${bytes.size}}")
        File(root,"selection.json").writeText("{\"sha\":\"$sha\"}")
        store.enabled=true;assertEquals(ModelFormat.LITERTLM,store.selected()!!.format);assertFalse(store.enabled)
        assertTrue(File(dir,"weights.litertlm").exists())
    }
    @Test fun legacyImportsPreserveExistingGGUF(){val model=store.import(ByteArrayInputStream(bytes),"new");assertThrows(Exception::class.java){store.import(ByteArrayInputStream("LITERTLMnot-supported".toByteArray()),"old")};assertEquals(model,store.selected())}
    @Test fun questionsDoNotAdvertiseActionTools(){assertFalse(ModelPrompt.requestsAction("عندي امتحان بكرة اقترح خطة للدراسة"));assertFalse(ModelPrompt.requestsAction("How can I create tasks?"));assertTrue(ModelPrompt.requestsAction("ممكن تضيف قراءة الفصل؟"));assertTrue(ModelPrompt.requestsAction("Please create a reading task"))}
}
