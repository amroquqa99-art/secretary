package com.alsekretary.app.data
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.alsekretary.app.domain.*
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AssistantStoreTest {
    private lateinit var context: Context
    private lateinit var db: SecretaryDatabase
    private lateinit var store: AssistantStore
    private lateinit var repo: SecretaryRepository
    @Before fun setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("alsekretary.db");db=SecretaryDatabase(context);store=AssistantStore(db);repo=SecretaryRepository(db)}
    @After fun cleanup(){db.close()}
    @Test fun confirmationIsDurableAndExactlyOnce(){
        store.submit("أضف مهمة مراجعة")
        assertTrue(repo.listTodayTasks().isEmpty())
        val id=store.proposals().single().id
        db.close();db=SecretaryDatabase(context);store=AssistantStore(db);repo=SecretaryRepository(db)
        store.confirm(id);store.confirm(id)
        assertEquals(1,repo.listTodayTasks().size);assertEquals("DONE",store.proposals().single().status)
    }
    @Test fun cancellationCannotExecute(){store.submit("أضف مهمة ملغاة");val id=store.proposals().single().id;store.cancel(id);assertThrows(IllegalArgumentException::class.java){store.confirm(id)};assertTrue(repo.listTodayTasks().isEmpty())}
    @Test fun changedTaskRejectsOldApproval(){val t=repo.addQuickTask("قراءة");store.submit("أكمل مهمة قراءة");val id=store.proposals().single().id;repo.saveTask(t.copy(definitionOfDone="معيار جديد"));assertThrows(IllegalArgumentException::class.java){store.confirm(id)};assertEquals(TaskStatus.INBOX,repo.taskById(t.id)!!.status);assertEquals("PENDING",store.proposals().single().status)}
    @Test fun plansRespectDependencies(){val a=repo.addQuickTask("متطلب");val b=repo.addQuickTask("لاحقة");repo.saveTask(b,setOf(a.id));val r=store.submit("خطط يومي");assertTrue(r.text.contains("متطلب"));assertFalse(r.text.contains("لاحقة"));assertTrue(store.proposals().isEmpty())}
    @Test fun failedCompletionRollsBackActionAndTask(){val a=repo.addQuickTask("متطلب");val b=repo.addQuickTask("لاحقة");repo.saveTask(b,setOf(a.id));store.submit("أكمل مهمة لاحقة");val id=store.proposals().single().id;assertThrows(IllegalArgumentException::class.java){store.confirm(id)};assertEquals("PENDING",store.proposals().single().status);assertEquals(TaskStatus.INBOX,repo.taskById(b.id)!!.status)}
    @Test fun backupRestoresChatButCancelsUnexecutedActions(){
        store.submit("أضف مهمة تنتظر")
        val manager=BackupManager(context,db);val password="test-password".toCharArray()
        val bytes=manager.pack(password);store.clear()
        val file=java.io.File(context.cacheDir,"chat.skr").apply{writeBytes(bytes)}
        manager.restore(android.net.Uri.fromFile(file),password)
        assertEquals(2,store.messages().size);val action=store.proposals().single();assertEquals("CANCELLED",action.status)
        assertThrows(IllegalArgumentException::class.java){store.confirm(action.id)};assertTrue(repo.listTodayTasks().isEmpty())
    }
    @Test fun legacyV5BackupIsReadable(){
        repo.addQuickTask("قديمة");val manager=BackupManager(context,db);val password="test-password".toCharArray()
        val plain=BackupCrypto.decrypt(manager.pack(password),password)
        val buffer=java.io.ByteArrayOutputStream()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(plain)).use{input->java.util.zip.ZipOutputStream(buffer).use{out->
            while(true){val entry=input.nextEntry ?: break;var bytes=input.readBytes()
                if(entry.name=="database.json"){val j=org.json.JSONObject(String(bytes,Charsets.UTF_8));j.put("schema",5);j.getJSONObject("tables").remove("assistant_messages");j.getJSONObject("tables").remove("assistant_actions");bytes=j.toString().toByteArray(Charsets.UTF_8)}
                out.putNextEntry(java.util.zip.ZipEntry(entry.name));out.write(bytes);out.closeEntry()
            }
        }}
        store.submit("أضف مهمة لاحقة");val file=java.io.File(context.cacheDir,"legacy.skr").apply{writeBytes(BackupCrypto.encrypt(buffer.toByteArray(),password))}
        manager.restore(android.net.Uri.fromFile(file),password)
        assertEquals("قديمة",repo.listTodayTasks().single().title);assertTrue(store.messages().isEmpty())
    }
    @Test fun v5DatabaseUpgradePreservesTasksAndAddsAssistantMemory(){
        db.close();context.deleteDatabase("alsekretary.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("alsekretary.db"),null).use{old->
            for(name in listOf("createV1Tables","createV2Tables","seedAreas","createV3Tables","createV4Tables","createV5Tables"))SecretaryDatabase::class.java.getDeclaredMethod(name,android.database.sqlite.SQLiteDatabase::class.java).apply{isAccessible=true}.invoke(db,old)
            old.execSQL("INSERT INTO tasks(id,title,status,created_at,updated_at) VALUES('old','kept','INBOX',123,123)");old.version=5
        }
        db=SecretaryDatabase(context);store=AssistantStore(db);repo=SecretaryRepository(db)
        assertEquals("kept",repo.taskById("old")!!.title);assertEquals(6,db.readableDatabase.version);assertTrue(store.messages().isEmpty())
    }
}
