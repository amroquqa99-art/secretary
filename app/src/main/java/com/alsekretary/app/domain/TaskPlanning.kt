package com.alsekretary.app.domain

import java.time.Instant
import java.time.ZoneId

object TaskPlanning {
    fun validateGraph(taskIds: Set<String>, edges: List<TaskDependency>) {
        require(edges.all { it.taskId in taskIds && it.prerequisiteId in taskIds }) { "اعتمادية تشير إلى مهمة غير موجودة" }
        val links = edges.groupBy { it.taskId }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "اعتماديات دائرية: لا يمكن للمهمة انتظار نفسها" }
            links[id].orEmpty().forEach { visit(it.prerequisiteId) }
            visiting.remove(id)
            visited.add(id)
        }
        taskIds.forEach { visit(it) }
    }

    fun unmet(taskId: String, tasks: List<Task>, edges: List<TaskDependency>): List<String> {
        val statuses = tasks.associate { it.id to it.status }
        return edges.filter { it.taskId == taskId && statuses[it.prerequisiteId] != TaskStatus.DONE }.map { it.prerequisiteId }
    }

    // Repeat from the completion date; missed occurrences are not created in bulk.
    fun nextOccurrence(task: Task, completedAt: Long, id: String, zone: ZoneId): Task? {
        val days = task.repeatDays ?: return null
        require(days in 1..365)
        val anchor = Instant.ofEpochMilli(completedAt).atZone(zone)
        val original = task.scheduledEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val next = anchor.toLocalDate().plusDays(days.toLong())
            .atTime(original?.toLocalTime() ?: anchor.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
        val deadline = task.deadlineEpochMillis?.let { due ->
            if (task.scheduledEpochMillis != null) next + (due - task.scheduledEpochMillis) else next
        }
        return task.copy(id=id, status=TaskStatus.PLANNED, scheduledEpochMillis=next,
            deadlineEpochMillis=deadline, actualMinutes=null, generatedFrom=task.id,parentId=null,failureCategory=null,failureReason=null)
    }
}
