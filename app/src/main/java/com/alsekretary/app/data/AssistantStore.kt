package com.alsekretary.app.data

import android.content.ContentValues
import com.alsekretary.app.domain.*
import org.json.JSONObject
import java.util.UUID

class AssistantStore(private val db: SecretaryDatabase) {
    private val repo = SecretaryRepository(db)
    fun messages(): List<AssistantMessage> = db.readableDatabase.rawQuery("SELECT id,role,content,created_at FROM assistant_messages ORDER BY rowid DESC LIMIT 100", null).use { c -> buildList { while(c.moveToNext()) add(AssistantMessage(c.getString(0),c.getString(1),c.getString(2),c.getLong(3))) }.reversed() }
    fun proposals(): List<AssistantProposal> = db.readableDatabase.rawQuery("SELECT id,payload,status,result FROM assistant_actions ORDER BY rowid DESC LIMIT 50", null).use { c -> buildList { while(c.moveToNext()) add(AssistantProposal(c.getString(0),decode(c.getString(1)),c.getString(2),if(c.isNull(3))null else c.getString(3))) } }
    private fun encode(call: AssistantCall) = JSONObject().put("tool",call.tool.name).put("title",call.title).put("target",call.targetId).put("body",call.body).put("expected",call.expected).toString()
    private fun decode(payload: String): AssistantCall { val j=JSONObject(payload);return AssistantCall(AssistantTool.valueOf(j.getString("tool")),j.getString("title"),j.getString("target"),j.getString("body"),j.getString("expected")) }
    private fun message(role: String,text: String) { db.writableDatabase.insertOrThrow("assistant_messages",null,ContentValues().apply { put("id",UUID.randomUUID().toString());put("role",role);put("content",text);put("created_at",System.currentTimeMillis()) }) }
    fun submit(input: String,budget: Int = 120): AssistantReply {
        require(input.isNotBlank() && input.length<=2000) { "اكتب أمراً من 1 إلى 2000 حرف" }
        val tasks=repo.listTodayTasks(true)
        val edges=repo.planningDependencies()
        val ready=tasks.filter { TaskPlanning.unmet(it.id,tasks,edges).isEmpty() }
        val now=System.currentTimeMillis();val zone=java.time.ZoneId.systemDefault()
        val command=LocalAssistant.normalize(input)
        val reply=when(command) {
            "خطط اسبوعي", "خطط الاسبوع", "plan week" -> {
                val from=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                val to=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
                AssistantReply(AssistantPlanning.render(AssistantPlanning.week(tasks,edges,repo.listCalendarEntries(from,to),budget,now,zone),budget,zone))
            }
            "راجع حياتي", "راجع اهدافي", "سياقي", "review life", "review goals", "my context" -> AssistantReply(AssistantLifeReview.render(AssistantLifeContext(repo.listGoals(true),repo.listProjects(true),tasks,repo.listProjectItems(),repo.listDailyChecks()),now,zone))
            else -> LocalAssistant.respond(input,if(LocalAssistant.normalize(input) in setOf("خطط يومي","خطط اليوم","plan today"))ready else tasks,repo.listNotes(),budget)
        }
        val d=db.writableDatabase;d.beginTransaction()
        try {
            message("USER",input.trim());message("ASSISTANT",reply.text)
            reply.call?.let { d.insertOrThrow("assistant_actions",null,ContentValues().apply { put("id",UUID.randomUUID().toString());put("payload",encode(it));put("status","PENDING");put("created_at",System.currentTimeMillis()) }) }
            d.setTransactionSuccessful()
        } finally {d.endTransaction()}
        return reply
    }
    fun confirm(id: String): String {
        val d=db.writableDatabase;d.beginTransaction()
        try {
            val row=d.rawQuery("SELECT payload,status,result FROM assistant_actions WHERE id=?",arrayOf(id)).use { c -> require(c.moveToFirst()) { "الاقتراح غير موجود" };Triple(c.getString(0),c.getString(1),if(c.isNull(2))null else c.getString(2)) }
            if(row.second=="DONE") { d.setTransactionSuccessful(); return row.third.orEmpty() }
            require(row.second=="PENDING") { "الاقتراح ملغى أو غير قابل للتنفيذ" }
            val call=decode(row.first)
            val result=when(call.tool) {
                AssistantTool.CREATE_TASK -> { require(call.title.isNotBlank() && call.title.length<=300);val task=repo.addQuickTask(call.title);"حفظت المهمة ${task.title} [${task.id}]" }
                AssistantTool.CREATE_NOTE -> { require(call.title.isNotBlank() && call.title.length<=300 && call.body.isNotBlank() && call.body.length<=2000);val now=System.currentTimeMillis();val note=repo.saveNote(Note(UUID.randomUUID().toString(),call.title,call.body,now,now));"حفظت الملاحظة ${note.title} [${note.id}]" }
                AssistantTool.COMPLETE_TASK -> { val task=repo.taskById(call.targetId);require(task!=null && task.toString()==call.expected && task.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED)) { "تغيرت المهمة؛ اطلب اقتراحاً جديداً" };repo.markDone(call.targetId);"سجلت إنجاز ${call.title}" }
                AssistantTool.START_FOCUS -> { require(repo.activeFocus()==null) { "توجد جلسة نشطة" };val task=repo.taskById(call.targetId);require(task!=null && task.toString()==call.expected) { "تغيرت المهمة؛ اطلب اقتراحاً جديداً" };repo.startFocus(call.targetId,FocusMode.NORMAL,null,emptySet());"بدأت جلسة التركيز العادية: ${call.title}" }
            }
            d.update("assistant_actions",ContentValues().apply { put("status","DONE");put("result",result);put("executed_at",System.currentTimeMillis()) },"id=?",arrayOf(id))
            message("ASSISTANT",result);d.setTransactionSuccessful();return result
        } finally {d.endTransaction()}
    }
    fun cancel(id: String) { db.writableDatabase.update("assistant_actions",ContentValues().apply { put("status","CANCELLED") },"id=? AND status='PENDING'",arrayOf(id)) }
    fun validateStoredActions() { db.readableDatabase.rawQuery("SELECT payload FROM assistant_actions",null).use{c->while(c.moveToNext())decode(c.getString(0))} }
    fun clear() { val d=db.writableDatabase;d.beginTransaction();try{d.delete("assistant_actions",null,null);d.delete("assistant_messages",null,null);d.setTransactionSuccessful()}finally{d.endTransaction()} }
}
