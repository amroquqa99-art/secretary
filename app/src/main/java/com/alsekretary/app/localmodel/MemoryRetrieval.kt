package com.alsekretary.app.localmodel

import com.alsekretary.app.domain.AssistantMessage
import com.alsekretary.app.domain.Goal
import com.alsekretary.app.domain.GoalStatus
import com.alsekretary.app.domain.LocalAssistant
import com.alsekretary.app.domain.Note
import com.alsekretary.app.domain.Task
import com.alsekretary.app.domain.TaskStatus

data class RetrievedMemory(
    val tasks: List<Task>,
    val goals: List<Goal>,
    val notes: List<Note>,
    val messages: List<AssistantMessage>
)

/**
 * Bounded local relevance retrieval for model context.
 *
 * This is deliberately lexical and deterministic. It improves context selection without
 * claiming embedding-based semantic search. All scoring happens on-device and no data leaves
 * the app.
 */
object MemoryRetrieval {
    private val stopWords = setOf(
        "من","في","على","الى","إلى","عن","مع","هذا","هذه","ذلك","التي","الذي","انا","أنا",
        "هو","هي","ما","ماذا","شو","بدي","اريد","أريد","ممكن","please","the","a","an","to",
        "of","in","on","for","my","me","i","is","are","and","or"
    ).map { LocalAssistant.normalize(it) }.toSet()

    fun select(
        query: String,
        tasks: List<Task>,
        goals: List<Goal>,
        notes: List<Note>,
        messages: List<AssistantMessage>,
        maxTasks: Int = 8,
        maxGoals: Int = 4,
        maxNotes: Int = 4,
        maxMessages: Int = 4
    ): RetrievedMemory {
        require(maxTasks in 0..20 && maxGoals in 0..10 && maxNotes in 0..10 && maxMessages in 0..10)
        val normalizedQuery = LocalAssistant.normalize(query)
        val tokens = tokens(normalizedQuery)

        fun score(text: String, recency: Long? = null): Int {
            val normalized = LocalAssistant.normalize(text)
            if (normalized.isBlank()) return 0
            var result = 0
            if (normalizedQuery.length >= 3 && normalized.contains(normalizedQuery)) result += 20
            for (token in tokens) {
                if (normalized == token) result += 8
                else if (normalized.contains(token)) result += 4
            }
            if (tokens.size >= 2) {
                val matched = tokens.count { normalized.contains(it) }
                if (matched >= 2) result += matched * 2
            }
            if (result > 0 && recency != null) {
                val ageDays = ((System.currentTimeMillis() - recency).coerceAtLeast(0L) / 86_400_000L).toInt()
                result += when {
                    ageDays <= 1 -> 3
                    ageDays <= 7 -> 2
                    ageDays <= 30 -> 1
                    else -> 0
                }
            }
            return result
        }

        fun <T> ranked(items: List<T>, limit: Int, text: (T) -> String, recency: (T) -> Long? = { null }): List<T> {
            if (limit == 0) return emptyList()
            val scored = items.mapIndexed { index, item -> Triple(item, score(text(item), recency(item)), index) }
            val relevant = scored.filter { it.second > 0 }
                .sortedWith(compareByDescending<Triple<T,Int,Int>> { it.second }.thenBy { it.third })
                .take(limit)
                .map { it.first }
            return if (relevant.isNotEmpty() || tokens.isNotEmpty()) relevant
            else items.takeLast(limit)
        }

        val openTasks = tasks.filter { it.status !in setOf(TaskStatus.DONE, TaskStatus.DROPPED) }
        val activeGoals = goals.filter { it.status == GoalStatus.ACTIVE }
        val recentMessages = messages.takeLast(30)

        return RetrievedMemory(
            tasks = ranked(openTasks, maxTasks, { listOfNotNull(it.title,it.definitionOfDone,it.failureReason).joinToString(" ") }),
            goals = ranked(activeGoals, maxGoals, { listOf(it.title,it.specific,it.metricName,it.relevantReason,it.achievableNote).joinToString(" ") }),
            notes = ranked(notes, maxNotes, { it.title + " " + it.markdown }, { it.updatedAt }),
            messages = ranked(recentMessages, maxMessages, { it.text }, { it.at })
        )
    }

    private fun tokens(normalized: String): Set<String> = normalized
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .asSequence()
        .map { it.trim() }
        .filter { it.length >= 2 && it !in stopWords }
        .take(24)
        .toSet()
}
