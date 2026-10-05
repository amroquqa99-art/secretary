package com.alsekretary.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.core.content.FileProvider
import com.alsekretary.app.domain.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.*
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AndroidStorageTest {
    private lateinit var context: Context
    private lateinit var db: SecretaryDatabase
    private lateinit var repo: SecretaryRepository
    private val password = "test-password-123".toCharArray()
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("alsekretary.db")
        db = SecretaryDatabase(context)
        repo = SecretaryRepository(db)
    }
    @After fun cleanup() { db.close() }
    private fun task(id: String, parent: String? = null, repeat: Int? = null) = Task(id, "مهمة $id", null, TaskStatus.PLANNED, 1_700_000_000_000, null, 20, null, "نتيجة", repeatDays = repeat, parentId = parent)
    private fun file(bytes: ByteArray): Uri {
        val f = File.createTempFile("backup-", ".skr", context.cacheDir)
        f.writeBytes(bytes)
        return Uri.fromFile(f)
    }
    @Test fun freshDatabaseHasOnlyAreasAndUnknownRecovery() {
        assertEquals(7, db.readableDatabase.version)
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM areas", null).use { assertTrue(it.moveToFirst()); assertEquals(4, it.getInt(0)) }
        assertTrue(repo.listTodayTasks(true).isEmpty())
        assertTrue(repo.listGoals(true).isEmpty())
        assertTrue(repo.dashboardSnapshot().areaScores.values.all { it == null })
        assertNull(repo.dashboardSnapshot().recoveryScore)
    }
    @Test fun recurrenceIsExactlyOnceAndSurvivesReopen() {
        repo.saveTask(task("repeat", repeat = 2))
        repo.markDone("repeat"); repo.markDone("repeat")
        db.close(); db = SecretaryDatabase(context); repo = SecretaryRepository(db)
        val tasks = repo.listTodayTasks(true)
        assertEquals(2, tasks.size)
        assertEquals(TaskStatus.DONE, repo.taskById("repeat")!!.status)
        assertEquals(1, tasks.count { it.generatedFrom == "repeat" })
    }
    @Test fun parentCannotCompleteBeforeChildAndFailedEditChangesNothing() {
        repo.saveTask(task("parent")); repo.saveTask(task("child", parent = "parent"))
        assertThrows(IllegalArgumentException::class.java) { repo.markDone("parent") }
        assertEquals(TaskStatus.PLANNED, repo.taskById("parent")!!.status)
        assertThrows(IllegalArgumentException::class.java) { repo.saveTask(task("child", parent = "parent").copy(status = TaskStatus.BLOCKED)) }
        assertEquals(TaskStatus.PLANNED, repo.taskById("child")!!.status)
        repo.markDone("child"); repo.markDone("parent")
        assertEquals(TaskStatus.DONE, repo.taskById("parent")!!.status)
    }
    @Test fun focusCompletionRollsBackWhenTaskCannotComplete() {
        repo.saveTask(task("focused")); repo.saveTask(task("prerequisite"))
        val session = repo.startFocus("focused", FocusMode.NORMAL, null, emptySet())
        db.writableDatabase.execSQL("INSERT INTO task_dependencies VALUES('focused','prerequisite')")
        assertThrows(IllegalArgumentException::class.java) { repo.finishFocus(session.id, FocusStatus.COMPLETED) }
        assertEquals(session.id, repo.activeFocus()!!.id)
        assertEquals(TaskStatus.ACTIVE, repo.taskById("focused")!!.status)
        repo.markDone("prerequisite"); repo.finishFocus(session.id, FocusStatus.COMPLETED)
        assertNull(repo.activeFocus())
        assertEquals(TaskStatus.DONE, repo.taskById("focused")!!.status)
    }
    @Test fun encryptedBackupRestoresAttachmentsButKeepsSocialState() {
        repo.saveProject(Project("p", "مشروع", "نتيجة", null, ProjectStatus.ACTIVE, 0.0, null))
        repo.saveTask(task("kept").copy(projectId = "p"))
        val original = File(context.filesDir, "sample.txt").apply { writeText("مرفق حقيقي") }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(context.filesDir, "attachments/sample.txt").apply { parentFile!!.mkdirs(); writeBytes(original.readBytes()) })
        repo.saveProjectItem(ProjectItem("attachment", "p", WorkKind.FILE, "ملف", "", uri = uri.toString()))
        repo.setCapacity(12.0)
        val manager = BackupManager(context, db)
        val backup = manager.pack(password)
        repo.addQuickTask("will disappear"); repo.setCapacity(33.0)
        db.writableDatabase.execSQL("INSERT INTO social_cache VALUES('account','{}',123)")
        manager.restore(file(backup), password)
        assertEquals(listOf("kept"), repo.listTodayTasks(true).map { it.id })
        assertEquals(12.0, repo.capacityHours(), 0.0)
        val restored = repo.listProjectItems().single()
        assertEquals("مرفق حقيقي", context.contentResolver.openInputStream(Uri.parse(restored.uri))!!.bufferedReader().use { it.readText() })
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM social_cache", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        assertTrue(File(context.filesDir, "backups/before-restore.skr").isFile)
    }
    @Test fun invalidRestoreRollsBackAlreadyDeletedRows() {
        repo.saveTask(task("before"))
        val manager = BackupManager(context, db)
        val plain = BackupCrypto.decrypt(manager.pack(password), password)
        val output = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(plain)).use { input ->
            ZipOutputStream(output).use { zip ->
                while (true) {
                    val e = input.nextEntry ?: break
                    var bytes = input.readBytes()
                    if (e.name == "database.json") {
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        json.getJSONObject("tables").getJSONArray("tasks").getJSONObject(0).put("unexpected_column", "invalid")
                        bytes = json.toString().toByteArray(Charsets.UTF_8)
                    }
                    zip.putNextEntry(ZipEntry(e.name)); zip.write(bytes); zip.closeEntry()
                }
            }
        }
        val invalid = BackupCrypto.encrypt(output.toByteArray(), password)
        repo.addQuickTask("current data")
        val before = repo.listTodayTasks(true).toSet()
        assertThrows(IllegalArgumentException::class.java) { manager.restore(file(invalid), password) }
        assertEquals(before, repo.listTodayTasks(true).toSet())
    }
    @Test fun wrongPasswordCannotReplaceLocalData() {
        repo.saveTask(task("before"))
        val manager = BackupManager(context, db)
        val uri = file(manager.pack(password))
        repo.addQuickTask("after backup")
        val before = repo.listTodayTasks(true).toSet()
        assertThrows(Exception::class.java) { manager.restore(uri, "wrong-password".toCharArray()) }
        assertEquals(before, repo.listTodayTasks(true).toSet())
    }
    @Test fun realV4UpgradePreservesRows() {
        db.close(); context.deleteDatabase("alsekretary.db")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("alsekretary.db"), null).use { old ->
            for (name in listOf("createV1Tables", "createV2Tables", "seedAreas", "createV3Tables", "createV4Tables")) {
                SecretaryDatabase::class.java.getDeclaredMethod(name, SQLiteDatabase::class.java).apply { isAccessible = true }.invoke(db, old)
            }
            old.execSQL("INSERT INTO tasks(id,title,status,created_at,updated_at) VALUES('legacy','saved','INBOX',123,123)")
            old.execSQL("INSERT INTO goals(id,title,area_code,specific,metric_name,relevant_reason,achievable_note,status,created_at,updated_at) VALUES('goal','saved','MENTAL','specific','pages','reason','plan','ACTIVE',123,123)")
            old.version = 4
        }
        db = SecretaryDatabase(context); repo = SecretaryRepository(db)
        assertEquals("saved", repo.taskById("legacy")!!.title)
        assertEquals(123L, repo.listGoals(true).single().startedAt)
        assertEquals(7, db.readableDatabase.version)
    }
}
