package com.alsekretary.app.localmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.alsekretary.app.domain.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private enum class MemoryKind { TASK, GOAL, NOTE, MESSAGE }
private data class MemoryDocument(
    val kind: MemoryKind,
    val id: String,
    val revision: String,
    val text: String
)

data class SemanticMemoryStats(
    val documents: Int,
    val indexed: Int,
    val modelSha: String?
)

private class MemoryVectorCache(context: Context) : SQLiteOpenHelper(context,"semantic-memory-cache.db",null,1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE vectors(
                kind TEXT NOT NULL,
                item_id TEXT NOT NULL,
                revision TEXT NOT NULL,
                model_sha TEXT NOT NULL,
                dims INTEGER NOT NULL,
                vector BLOB NOT NULL,
                PRIMARY KEY(kind,item_id,model_sha)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_vector_model ON vectors(model_sha)")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS vectors")
        onCreate(db)
    }
    fun get(document: MemoryDocument,modelSha: String): FloatArray? =
        readableDatabase.rawQuery(
            "SELECT revision,dims,vector FROM vectors WHERE kind=? AND item_id=? AND model_sha=?",
            arrayOf(document.kind.name,document.id,modelSha)
        ).use { c ->
            if(!c.moveToFirst() || c.getString(0)!=document.revision)return@use null
            val dims=c.getInt(1);val blob=c.getBlob(2)
            if(dims !in 1..8192 || blob.size!=dims*4)return@use null
            val buffer=ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(dims){buffer.float}
        }
    fun put(document: MemoryDocument,modelSha: String,vector: FloatArray) {
        require(vector.isNotEmpty() && vector.size<=8192 && vector.all { it.isFinite() })
        val buffer=ByteBuffer.allocate(vector.size*4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach(buffer::putFloat)
        writableDatabase.execSQL(
            """INSERT OR REPLACE INTO vectors(kind,item_id,revision,model_sha,dims,vector)
               VALUES(?,?,?,?,?,?)""",
            arrayOf(document.kind.name,document.id,document.revision,modelSha,vector.size,buffer.array())
        )
    }
    fun clear() = writableDatabase.delete("vectors",null,null)
    fun count(modelSha: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM vectors WHERE model_sha=?",arrayOf(modelSha)
    ).use { c -> c.moveToFirst();c.getInt(0) }
}

class SemanticMemory(
    context: Context,
    private val modelStore: EmbeddingModelStore,
    private val runner: EmbeddingRunner
) {
    private val cache=MemoryVectorCache(context)

    fun clear() { cache.clear() }

    fun stats(tasks: List<Task>,goals: List<Goal>,notes: List<Note>,messages: List<AssistantMessage>): SemanticMemoryStats {
        val docs=documents(tasks,goals,notes,messages)
        val sha=modelStore.selected()?.sha
        return SemanticMemoryStats(docs.size,sha?.let(cache::count) ?: 0,sha)
    }

    fun rebuild(
        tasks: List<Task>,
        goals: List<Goal>,
        notes: List<Note>,
        messages: List<AssistantMessage>,
        token: AtomicBoolean
    ): SemanticMemoryStats {
        val model=requireNotNull(modelStore.selected()) { "نزّل نموذج الذاكرة أولاً" }
        require(modelStore.enabled) { "فعّل الذاكرة الدلالية أولاً" }
        val docs=documents(tasks,goals,notes,messages)
        require(docs.isNotEmpty()) { "لا توجد بيانات قابلة للفهرسة" }
        cache.clear()
        val vectors=runner.embedDocuments(docs.map { it.text },model,token)
        require(vectors.size==docs.size)
        docs.zip(vectors).forEach { (doc,vector) ->
            if(token.get())throw java.util.concurrent.CancellationException("توقفت الفهرسة")
            cache.put(doc,model.sha,vector)
        }
        return SemanticMemoryStats(docs.size,docs.size,model.sha)
    }

    fun retrieve(
        query: String,
        tasks: List<Task>,
        goals: List<Goal>,
        notes: List<Note>,
        messages: List<AssistantMessage>,
        token: AtomicBoolean
    ): RetrievedMemory? {
        if(!modelStore.enabled)return null
        val model=modelStore.selected() ?: return null
        val docs=documents(tasks,goals,notes,messages)
        if(docs.isEmpty())return null
        val queryVector=runner.embedQuery(query,model,token)
        val scored=docs.mapNotNull { doc ->
            val vector=cache.get(doc,model.sha) ?: return@mapNotNull null
            if(vector.size!=queryVector.size)return@mapNotNull null
            doc to dot(queryVector,vector)
        }
        if(scored.isEmpty())return null

        fun ids(kind: MemoryKind,limit: Int): Set<String> = scored.asSequence()
            .filter { it.first.kind==kind && it.second>=0.35f }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first.id }
            .toSet()

        val taskIds=ids(MemoryKind.TASK,8)
        val goalIds=ids(MemoryKind.GOAL,4)
        val noteIds=ids(MemoryKind.NOTE,4)
        val messageIds=ids(MemoryKind.MESSAGE,4)
        if(taskIds.isEmpty() && goalIds.isEmpty() && noteIds.isEmpty() && messageIds.isEmpty())return null
        return RetrievedMemory(
            tasks.filter { it.id in taskIds }.sortedBy { taskIds.indexOf(it.id) },
            goals.filter { it.id in goalIds }.sortedBy { goalIds.indexOf(it.id) },
            notes.filter { it.id in noteIds }.sortedBy { noteIds.indexOf(it.id) },
            messages.filter { it.id in messageIds }.sortedBy { messageIds.indexOf(it.id) }
        )
    }

    private fun documents(
        tasks: List<Task>,
        goals: List<Goal>,
        notes: List<Note>,
        messages: List<AssistantMessage>
    ): List<MemoryDocument> = buildList {
        tasks.filter { it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED) }.take(150).forEach { task ->
            val text=listOfNotNull(task.title,task.definitionOfDone,task.failureReason).joinToString("\n").take(4000)
            add(document(MemoryKind.TASK,task.id,text))
        }
        goals.filter { it.status==GoalStatus.ACTIVE }.take(60).forEach { goal ->
            val text=listOf(goal.title,goal.specific,goal.metricName,goal.relevantReason,goal.achievableNote).joinToString("\n").take(4000)
            add(document(MemoryKind.GOAL,goal.id,text))
        }
        notes.sortedByDescending { it.updatedAt }.take(150).forEach { note ->
            add(document(MemoryKind.NOTE,note.id,(note.title+"\n"+note.markdown).take(4000)))
        }
        messages.takeLast(120).forEach { message ->
            add(document(MemoryKind.MESSAGE,message.id,(message.role+"\n"+message.text).take(4000)))
        }
    }.take(480)

    private fun document(kind: MemoryKind,id: String,text: String): MemoryDocument {
        val normalized=text.trim()
        return MemoryDocument(kind,id,hash(normalized),normalized)
    }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun dot(a: FloatArray,b: FloatArray): Float {
        var sum=0f
        for(i in a.indices)sum+=a[i]*b[i]
        return max(-1f,sum.coerceAtMost(1f))
    }

    private fun <T> Set<T>.indexOf(value: T): Int {
        var i=0
        for(item in this){if(item==value)return i;i++}
        return Int.MAX_VALUE
    }
}
