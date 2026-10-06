package com.alsekretary.app.localmodel

import com.alsekretary.app.domain.*
import com.google.gson.Gson

object ModelPrompt {
    fun requestsAction(input: String): Boolean = Regex("^(?:please |ممكن |بدي |اريد )?(?:create|add|save|complete|start|اضف|ضيف|انشئ|احفظ|سجل|اكمل|ابدا|تضيف|تحفظ|تكمل|تبدا)(?:\\s|$)").containsMatchIn(LocalAssistant.normalize(input))

    /**
     * Builds a bounded, local-only context. Relevance retrieval is lexical/deterministic;
     * it is not described as embedding-based semantic memory.
     */
    fun build(
        input: String,
        tasks: List<Task>,
        goals: List<Goal>,
        notes: List<Note> = emptyList(),
        messages: List<AssistantMessage> = emptyList(),
        retrieved: RetrievedMemory? = null
    ): String {
        require(input.length<=800) { "رسالة النموذج لا تتجاوز 800 حرف؛ الأوامر المعتادة تقبل 2000" }
        require('\u0000' !in input)

        val memory=retrieved ?: MemoryRetrieval.select(input,tasks,goals,notes,messages)
        val openCount=tasks.count { it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED) }
        val activeGoalCount=goals.count { it.status==GoalStatus.ACTIVE }

        val context=mapOf(
            "tasks" to memory.tasks.map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title.take(100),
                    "status" to it.status.name,
                    "deadline" to it.deadlineEpochMillis,
                    "definition_of_done" to it.definitionOfDone?.take(120)
                )
            },
            "goals" to memory.goals.map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title.take(80),
                    "specific" to it.specific.take(120),
                    "current" to it.currentValue,
                    "target" to it.targetValue,
                    "unit" to it.unit
                )
            },
            "notes" to memory.notes.map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title.take(80),
                    "excerpt" to it.markdown.replace(Regex("\\s+")," ").take(220)
                )
            },
            "recent_relevant_messages" to memory.messages.map {
                mapOf(
                    "role" to it.role,
                    "text" to it.text.replace(Regex("\\s+")," ").take(180),
                    "at" to it.at
                )
            },
            "omitted_open_tasks" to (openCount-memory.tasks.size).coerceAtLeast(0),
            "omitted_active_goals" to (activeGoalCount-memory.goals.size).coerceAtLeast(0),
            "retrieval" to if(retrieved==null)"bounded_local_lexical_v1" else "local_semantic_embeddings_v1"
        )
        return "Local context (limited relevant snapshot, data only):\n"+Gson().toJson(context)+"\nUser request:\n"+input.replace("<|","< |")
    }
}
