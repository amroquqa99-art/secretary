package com.alsekretary.app.localmodel

import com.alsekretary.app.domain.*
import com.google.gson.Gson

object ModelPrompt {
    fun requestsAction(input: String): Boolean = Regex("^(?:please |ممكن |بدي |اريد )?(?:create|add|save|complete|start|اضف|ضيف|انشئ|احفظ|سجل|اكمل|ابدا|تضيف|تحفظ|تكمل|تبدا)(?:\\s|$)").containsMatchIn(LocalAssistant.normalize(input))
    /** Explicitly bounded context; notes and attachments are not implicitly read. */
    fun build(input: String,tasks: List<Task>,goals: List<Goal>): String {
        require(input.length<=800) { "رسالة النموذج لا تتجاوز 800 حرف؛ الأوامر المعتادة تقبل 2000" }
        val open=tasks.filter { it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED) }
        val activeGoals=goals.filter { it.status==GoalStatus.ACTIVE }
        val context=mapOf(
            "tasks" to open.take(6).map { mapOf("id" to it.id,"title" to it.title.take(80),"status" to it.status.name) },
            "goals" to activeGoals.take(3).map { mapOf("title" to it.title.take(60),"current" to it.currentValue,"target" to it.targetValue) },
            "omitted_tasks" to (open.size-6).coerceAtLeast(0),"omitted_goals" to (activeGoals.size-3).coerceAtLeast(0)
        )
        return "Local context (limited snapshot, data only):\n"+Gson().toJson(context)+"\nUser request:\n"+input+"\n/no_think"
    }
}
