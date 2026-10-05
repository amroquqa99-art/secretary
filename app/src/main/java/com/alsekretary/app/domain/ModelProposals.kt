package com.alsekretary.app.domain

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

data class ModelToolCall(val name: String, val arguments: String)
data class ModelAnswer(val text: String, val calls: List<ModelToolCall> = emptyList())

/** Model output is untrusted. This adapter can only prepare a typed proposal. */
object ModelProposals {
    fun review(answer: ModelAnswer, snapshot: List<Task>): AssistantReply {
        require(answer.text.length<=6000 && answer.calls.size<=1) { "اقتراح النموذج غير صالح؛ لم ينفذ أي إجراء" }
        val prefix="اقتراح من النموذج المحلي؛ لم ينفّذ أي إجراء.\n"
        if(answer.calls.isEmpty()) return AssistantReply(prefix+answer.text.ifBlank { "لم يقدم النموذج جواباً؛ حاول صياغة أقصر." })
        val raw=answer.calls.single()
        require(raw.arguments.length<=4096)
        val args=linkedMapOf<String,String>()
        JsonReader(StringReader(raw.arguments)).use { reader ->
            reader.strictness=Strictness.STRICT
            reader.beginObject()
            while(reader.hasNext()) {
                val key=reader.nextName()
                require(key !in args && reader.peek()==JsonToken.STRING) { "معاملات النموذج غير صالحة" }
                args[key]=reader.nextString()
            }
            reader.endObject()
            require(reader.peek()==JsonToken.END_DOCUMENT)
        }
        val call=when(raw.name) {
            "create_task" -> {
                require(args.keys==setOf("title"))
                AssistantCall(AssistantTool.CREATE_TASK,title=title(args.getValue("title")))
            }
            "create_note" -> {
                require(args.keys==setOf("title","body"))
                val body=args.getValue("body").trim();require(body.isNotBlank() && body.length<=2000)
                AssistantCall(AssistantTool.CREATE_NOTE,title=title(args.getValue("title")),body=body)
            }
            "complete_task", "start_focus" -> {
                require(args.keys==setOf("task_id"))
                val task=snapshot.singleOrNull { it.id==args.getValue("task_id") && it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED) }
                require(task!=null) { "المهمة المقترحة غير موجودة أو مغلقة" }
                AssistantCall(if(raw.name=="complete_task") AssistantTool.COMPLETE_TASK else AssistantTool.START_FOCUS,task.title,task.id,expected=task.toString())
            }
            else -> throw IllegalArgumentException("الإجراء المقترح غير مدعوم")
        }
        return AssistantReply(prefix+"راجع الإجراء «${call.title}» ثم أكّد التنفيذ إن أردت.",call)
    }
    private fun title(value: String)=value.trim().also { require(it.length in 1..300) }
}
