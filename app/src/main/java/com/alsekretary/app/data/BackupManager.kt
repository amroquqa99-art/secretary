package com.alsekretary.app.data

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import com.alsekretary.app.domain.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.UUID

class BackupManager(private val context: Context,private val db: SecretaryDatabase) {
    private val tables=listOf("areas","goals","projects","tasks","behavior_events","decisions","calendar_events","notes","focus_sessions","project_feedback","habits","habit_checks","weekly_reviews","preferences","task_dependencies","milestones","goal_observations","daily_checks","project_items","task_failures","assistant_messages","assistant_actions")
    private fun bounded(input: java.io.InputStream,max: Int): ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=max){"ملف أكبر من الحد"};out.write(buffer,0,n)}
        return out.toByteArray()
    }
    fun export(uri: Uri,password: CharArray) {
        val bytes=pack(password)
        context.contentResolver.openOutputStream(uri,"wt")!!.use{it.write(bytes)}
    }
    fun pack(password: CharArray): ByteArray {
        val payload=JSONObject().put("schema",7);val data=JSONObject()
        val database=db.readableDatabase;database.beginTransaction()
        try {
            tables.forEach{table->val rows=JSONArray();database.rawQuery("SELECT * FROM $table",null).use { c ->
                while(c.moveToNext()){val row=JSONObject();c.columnNames.forEachIndexed{i,name->row.put(name,when(c.getType(i)){Cursor.FIELD_TYPE_NULL->JSONObject.NULL;Cursor.FIELD_TYPE_INTEGER->c.getLong(i);Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);Cursor.FIELD_TYPE_BLOB->error("نوع غير مدعوم");else->c.getString(i)})};rows.put(row)}
            };data.put(table,rows)}
            database.setTransactionSuccessful()
        } finally {database.endTransaction()}
        payload.put("tables",data)
        val buffer=ByteArrayOutputStream();var attachmentBytes=0
        ZipOutputStream(buffer).use{zip->
            zip.putNextEntry(ZipEntry("database.json"));zip.write(payload.toString().toByteArray(Charsets.UTF_8));zip.closeEntry()
            val items=data.getJSONArray("project_items")
            for(i in 0 until items.length()) {val item=items.getJSONObject(i);val ref=item.optString("uri")
                if(ref.isBlank() || ref=="null")continue
                val bytes=context.contentResolver.openInputStream(Uri.parse(ref))?.use{bounded(it,30*1024*1024)} ?: error("تعذر قراءة المرفق: ${item.getString("title")}")
                attachmentBytes+=bytes.size;require(attachmentBytes<=80*1024*1024){"المرفقات تتجاوز 80MB"}
                zip.putNextEntry(ZipEntry("attachments/"+item.getString("id")));zip.write(bytes);zip.closeEntry()
            }
        }
        return BackupCrypto.encrypt(buffer.toByteArray(),password)
    }
    fun restore(uri: Uri,password: CharArray) {
        require(SecretaryRepository(db).activeFocus()==null){"أنه جلسة التركيز قبل الاستعادة"}
        val packed=context.contentResolver.openInputStream(uri)!!.use{bounded(it,128*1024*1024)}
        val plain=BackupCrypto.decrypt(packed,password);val entries=mutableMapOf<String,ByteArray>();var total=0
        ZipInputStream(ByteArrayInputStream(plain)).use{zip->while(true){val entry=zip.nextEntry ?: break
            require(entry.name=="database.json" || (entry.name.startsWith("attachments/") && entry.name.removePrefix("attachments/").matches(Regex("[A-Za-z0-9_-]{1,100}")))){"مسار مرفق غير صالح"}
            require(entry.name !in entries){"عنصر مكرر"};val bytes=bounded(zip,80*1024*1024);total+=bytes.size;require(total<=100*1024*1024);entries[entry.name]=bytes
        }}
        val payload=JSONObject(String(entries["database.json"] ?: error("بيانات النسخة غير موجودة"),Charsets.UTF_8));require(payload.getInt("schema") in 5..7){"نسخة قاعدة غير مدعومة"}
        val data=payload.getJSONObject("tables")
        if(payload.getInt("schema")==5){require(!data.has("assistant_messages") && !data.has("assistant_actions"));data.put("assistant_messages",JSONArray());data.put("assistant_actions",JSONArray())}
        if(payload.getInt("schema")<7) {
            val goals=data.getJSONArray("goals")
            for(i in 0 until goals.length()) { val g=goals.getJSONObject(i);require(!g.has("horizon") && !g.has("parent_goal_id"));g.put("horizon","YEAR");g.put("parent_goal_id",JSONObject.NULL) }
        }
        require(data.keys().asSequence().toSet()==tables.toSet())
        // Keep an encrypted rollback copy; credentials and social state are not replaced.
        val prior=File(context.filesDir,"backups").apply{mkdirs()}
        File(prior,"before-restore.skr").writeBytes(pack(password))
        val created=mutableListOf<File>();val database=db.writableDatabase;database.beginTransaction()
        try {
            tables.reversed().forEach{database.delete(it,null,null)}
            tables.forEach{table->
                val columns=database.rawQuery("PRAGMA table_info($table)",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
                val rows=data.getJSONArray(table);require(rows.length()<=100000){"عدد سجلات غير صالح"}
                for(i in 0 until rows.length()) {val row=rows.getJSONObject(i);require(row.keys().asSequence().toSet()==columns)
                    if(table=="project_items" && !row.isNull("uri")) {
                        val bytes=entries["attachments/"+row.getString("id")] ?: error("مرفق ناقص")
                        val dir=File(context.filesDir,"attachments").apply{mkdirs()};val f=File(dir,UUID.randomUUID().toString());f.writeBytes(bytes);created.add(f)
                        row.put("uri",FileProvider.getUriForFile(context,context.packageName+".files",f).toString())
                    }
                    val values = ContentValues()
                    columns.forEach { name ->
                        val value = row.opt(name)
                        when {
                            value == null || value === JSONObject.NULL -> values.putNull(name)
                            value is Float || value is Double -> values.put(name, (value as Number).toDouble())
                            value is Number -> values.put(name, value.toLong())
                            value is String -> values.put(name, value)
                            else -> error("قيمة سجل غير صالحة")
                        }
                    }
                    database.insertOrThrow(table,null,values)
                }
            }
            val repo=SecretaryRepository(db);val tasks=repo.listTodayTasks(true)
            TaskPlanning.validateGraph(tasks.map{it.id}.toSet(),repo.planningDependencies())
            require(repo.listGoals(true).all{listOfNotNull(it.targetValue,it.currentValue).all(Double::isFinite)})
            GoalHierarchy.validate(repo.listGoals(true))
            require(repo.activeFocus()==null){"النسخة تحتوي جلسة نشطة؛ أنهها قبل إنشاء النسخة"}
            AssistantStore(db).validateStoredActions() // Reject unknown tool payloads before committing.
            database.execSQL("UPDATE assistant_actions SET status='CANCELLED' WHERE status='PENDING'")
            database.setTransactionSuccessful()
        } catch(e: Exception){created.forEach{it.delete()};throw e}finally{database.endTransaction()}
    }
}
