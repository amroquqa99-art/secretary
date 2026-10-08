package com.alsekretary.app

import android.net.Uri
import com.alsekretary.app.data.*
import com.alsekretary.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LifeMemoryAndRecoveryTest {
    @Test fun lifeSearchExcludesCredentialsAndProposalsAndCancellationReleasesDatabase() {
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase("alsekretary.db")
        SecretaryDatabase(context).use { db ->
            val repo=SecretaryRepository(db);repeat(10){repo.addQuickTask("النجارة $it")}
            repo.saveNote(Note("note","دفتر النجارة","النجارة 123",1,1))
            db.writableDatabase.execSQL("INSERT INTO habits(id,title,area,target,archived) VALUES('habit','النجارة كل أسبوع','MENTAL',3,0)")
            db.writableDatabase.execSQL("INSERT INTO preferences VALUES('synthetic-key','النجارة private-secret')")
            val assistant=AssistantStore(db);assistant.submit("احفظ ملاحظة النجارة | private-proposal")
            val hits=LifeMemoryStore(db).search("النجارة")
            assertTrue(hits.any{it.type=="habits"});assertTrue(hits.any{it.type=="notes"});assertTrue(hits.any{it.type=="tasks"})
            assertFalse(hits.any{it.excerpt.contains("private-secret") || it.excerpt.contains("private-proposal")})
            assertTrue(assistant.evaluate("ابحث النجارة").text.contains("عادة"))
            val messages=assistant.messages().size
            for(preCanceled in listOf(true,false)) {
                var checks=0
                try { assistant.submit("ابحث النجارة") { preCanceled || ++checks>=5 };fail("Cancelled scan returned") }
                catch(_: java.util.concurrent.CancellationException) { }
                assertFalse(db.readableDatabase.inTransaction());assertEquals(messages,assistant.messages().size)
                assertEquals("PENDING",assistant.proposals().single().status)
            }
            repo.addQuickTask("بعد الإلغاء");assertEquals(11,repo.listTodayTasks(true).size)
            assertTrue(LifeMemoryStore(db).search("في من على").isEmpty())
        }
    }
    @Test fun projectionPreservesMarkdownAndRollbackIsRecoverableAndUnique() {
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase("alsekretary.db")
        SecretaryDatabase(context).use { db ->
            val repo=SecretaryRepository(db);repo.saveNote(Note("../../outside","معرفة","نص عربي 123\n[[رابط]]",1,1))
            val file=File(context.cacheDir,"projection.zip");VaultExport(context,db).export(Uri.fromFile(file))
            ZipFile(file).use { zip ->
                val path=VaultExport.recordPath("notes","../../outside")
                assertTrue(path.matches(Regex("Secretary/notes/[a-f0-9]{64}[.]md")))
                assertTrue(zip.getInputStream(zip.getEntry(path)).bufferedReader().readText().contains("نص عربي 123\n[[رابط]]"))
                assertFalse(zip.entries().asSequence().any{it.name.contains("preferences") || it.name.contains("assistant_actions")})
            }
            val manager=BackupManager(context,db);val password="synthetic-password-123".toCharArray()
            val original=File(context.cacheDir,"original.skr");manager.export(Uri.fromFile(original),password)
            repo.addQuickTask("قبل الاستعادة 123")
            manager.restore(Uri.fromFile(original),password)
            assertTrue(repo.listTodayTasks(true).isEmpty())
            val previous=File(context.cacheDir,"previous.skr")
            manager.exportRollback(manager.rollbackBackups().first().first,Uri.fromFile(previous))
            manager.restore(Uri.fromFile(previous),password)
            assertEquals("قبل الاستعادة 123",repo.listTodayTasks(true).single().title)
            assertTrue(manager.rollbackBackups().size>=2)
            try { manager.exportRollback("../../outside",Uri.fromFile(previous));fail("Unsafe path accepted") }
            catch(_: IllegalArgumentException) { }
        }
    }
}
