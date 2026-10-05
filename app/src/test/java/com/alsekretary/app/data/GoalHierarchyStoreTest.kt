package com.alsekretary.app.data
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.alsekretary.app.domain.*
import java.io.*
import java.util.zip.*
import org.json.JSONObject
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class GoalHierarchyStoreTest {
    private lateinit var context: Context;private lateinit var db: SecretaryDatabase;private lateinit var repo: SecretaryRepository
    private val password="test-password".toCharArray()
    private fun goal(id: String,horizon: GoalHorizon,parent: String?=null)=Goal(id,id,LifeArea.MENTAL,"specific","books",10.0,1.0,"book",null,"why","how",horizon=horizon,parentGoalId=parent)
    @Before fun setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("alsekretary.db");db=SecretaryDatabase(context);repo=SecretaryRepository(db)}
    @After fun cleanup(){db.close()}
    private fun transformedBackup(transform: (JSONObject)->Unit): android.net.Uri {
        val plain=BackupCrypto.decrypt(BackupManager(context,db).pack(password),password);val out=ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(plain)).use { input->ZipOutputStream(out).use { zip->while(true){val e=input.nextEntry ?: break;var bytes=input.readBytes();if(e.name=="database.json"){val j=JSONObject(String(bytes,Charsets.UTF_8));transform(j);bytes=j.toString().toByteArray(Charsets.UTF_8)};zip.putNextEntry(ZipEntry(e.name));zip.write(bytes);zip.closeEntry()} } }
        return android.net.Uri.fromFile(File.createTempFile("goals-",".skr",context.cacheDir).apply {writeBytes(BackupCrypto.encrypt(out.toByteArray(),password))})
    }
    @Test fun hierarchySurvivesReopenAndBackup(){repo.saveGoal(goal("life",GoalHorizon.LIFETIME));repo.saveGoal(goal("year",GoalHorizon.YEAR,"life"));repo.saveGoal(goal("day",GoalHorizon.DAY,"year"));val uri=transformedBackup { };db.close();db=SecretaryDatabase(context);repo=SecretaryRepository(db);assertEquals(listOf("life","year","day"),GoalHierarchy.lineage(repo.listGoals().first { it.id=="day" },repo.listGoals()).map { it.id });repo.cancelGoal("day");BackupManager(context,db).restore(uri,password);assertEquals(GoalStatus.ACTIVE,repo.listGoals().first { it.id=="day" }.status)}
    @Test fun invalidParentChangesPreserveExistingGoals(){val life=goal("life",GoalHorizon.LIFETIME);repo.saveGoal(life);repo.saveGoal(goal("day",GoalHorizon.DAY,"life"));for(g in listOf(life.copy(horizon=GoalHorizon.DAY),life.copy(parentGoalId="day"),goal("other",GoalHorizon.WEEK,"missing"),goal("other",GoalHorizon.WEEK,"life").copy(area=LifeArea.PHYSICAL))){assertThrows(Exception::class.java){repo.saveGoal(g)}};assertEquals(life.horizon,repo.listGoals().first { it.id=="life" }.horizon);assertEquals(2,repo.listGoals().size)}
    @Test fun malformedRestoredHierarchyRollsBackWholeDatabase(){repo.saveGoal(goal("life",GoalHorizon.LIFETIME));repo.saveGoal(goal("day",GoalHorizon.DAY,"life"));val uri=transformedBackup { j->val a=j.getJSONObject("tables").getJSONArray("goals");for(i in 0 until a.length())if(a.getJSONObject(i).getString("id")=="day")a.getJSONObject(i).put("parent_goal_id","missing") };val t=repo.addQuickTask("keep");assertThrows(Exception::class.java){BackupManager(context,db).restore(uri,password)};assertEquals("keep",repo.taskById(t.id)!!.title);assertEquals("life",repo.listGoals().first { it.id=="day" }.parentGoalId)}
    @Test fun v6BackupUpgradesGoalsAndPreservesConversation(){repo.saveGoal(goal("old",GoalHorizon.YEAR));val store=AssistantStore(db);store.submit("أضف مهمة قديمة");val uri=transformedBackup { j->j.put("schema",6);val a=j.getJSONObject("tables").getJSONArray("goals");for(i in 0 until a.length()){a.getJSONObject(i).remove("horizon");a.getJSONObject(i).remove("parent_goal_id")} };repo.cancelGoal("old");store.clear();BackupManager(context,db).restore(uri,password);assertEquals(GoalHorizon.YEAR,repo.listGoals().single().horizon);assertNull(repo.listGoals().single().parentGoalId);assertEquals(2,store.messages().size);assertEquals("CANCELLED",store.proposals().single().status)}
    @Test fun v6DatabaseMigrationPreservesExistingGoal(){db.close();context.deleteDatabase("alsekretary.db");android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("alsekretary.db"),null).use { old->for(name in listOf("createV1Tables","createV2Tables","seedAreas","createV3Tables","createV4Tables","createV5Tables","createV6Tables"))SecretaryDatabase::class.java.getDeclaredMethod(name,android.database.sqlite.SQLiteDatabase::class.java).apply { isAccessible=true }.invoke(db,old);old.execSQL("INSERT INTO goals(id,title,area_code,specific,metric_name,relevant_reason,achievable_note,status,created_at,updated_at) VALUES('old','kept','MENTAL','specific','metric','why','how','ACTIVE',1,1)");old.version=6 };db=SecretaryDatabase(context);repo=SecretaryRepository(db);val g=repo.listGoals().single();assertEquals("kept",g.title);assertEquals(GoalHorizon.YEAR,g.horizon);assertEquals(7,db.readableDatabase.version)}
    @Test fun cancelledAncestorRemainsAvailableForEditingAndReview(){repo.saveGoal(goal("life",GoalHorizon.LIFETIME));repo.saveGoal(goal("day",GoalHorizon.DAY,"life"));repo.cancelGoal("life");repo.saveGoal(goal("unrelated",GoalHorizon.YEAR));val day=repo.listGoals().first { it.id=="day" };repo.saveGoal(day.copy(title="changed"));assertEquals(3,repo.listGoals(true).size);assertTrue(AssistantStore(db).submit("راجع حياتي").text.contains("أحد الأهداف الأعلى ملغى"));repo.saveGoal(day.copy(parentGoalId=null));assertNull(repo.listGoals().first { it.id=="day" }.parentGoalId)}
    @Test fun weeklyPlanAndReviewNeverCreateActionsOrCalendarChanges(){repo.saveGoal(goal("life",GoalHorizon.LIFETIME));repo.addQuickTask("work");val store=AssistantStore(db);assertTrue(store.submit("راجع حياتي").text.contains("life"));assertTrue(store.submit("خطط أسبوعي").text.contains("work"));assertTrue(store.proposals().isEmpty());assertNull(repo.listTodayTasks().single().scheduledEpochMillis)}
}
