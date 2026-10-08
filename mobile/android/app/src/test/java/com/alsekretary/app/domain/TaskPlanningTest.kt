package com.alsekretary.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TaskPlanningTest {
    private fun task(id: String,status: TaskStatus=TaskStatus.PLANNED)=Task(id,id,null,status,null,null,30,null,null)
    @Test fun acceptsDiamond() {
        TaskPlanning.validateGraph(setOf("a","b","c","d"),listOf(TaskDependency("b","a"),TaskDependency("c","a"),TaskDependency("d","b"),TaskDependency("d","c")))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsIndirectCycle() {
        TaskPlanning.validateGraph(setOf("a","b","c"),listOf(TaskDependency("a","b"),TaskDependency("b","c"),TaskDependency("c","a")))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnknownTask() {
        TaskPlanning.validateGraph(setOf("a"),listOf(TaskDependency("a","missing")))
    }
    @Test fun droppedPrerequisiteStillBlocks() {
        assertEquals(listOf("a"),TaskPlanning.unmet("b",listOf(task("a",TaskStatus.DROPPED),task("b")),listOf(TaskDependency("b","a"))))
        assertTrue(TaskPlanning.unmet("b",listOf(task("a",TaskStatus.DONE),task("b")),listOf(TaskDependency("b","a"))).isEmpty())
    }
    @Test fun repetitionPreservesLocalTimeAcrossDST() {
        val zone=ZoneId.of("Europe/Berlin")
        val before=ZonedDateTime.of(2026,3,28,9,0,0,0,zone)
        val original=task("a").copy(scheduledEpochMillis=before.toInstant().toEpochMilli(),repeatDays=1,actualMinutes=80)
        val next=TaskPlanning.nextOccurrence(original,before.toInstant().toEpochMilli(),"b",zone)!!
        val after=java.time.Instant.ofEpochMilli(next.scheduledEpochMillis!!).atZone(zone)
        assertEquals(9,after.hour); assertEquals(29,after.dayOfMonth)
        assertEquals(23L*60*60*1000,next.scheduledEpochMillis!!-original.scheduledEpochMillis!!)
        assertEquals("a",next.generatedFrom); assertNull(next.actualMinutes); assertEquals(TaskStatus.PLANNED,next.status)
    }
    @Test fun delayedCompletionDoesNotCreateBacklog() {
        val zone=ZoneId.of("UTC")
        val schedule=ZonedDateTime.parse("2026-09-01T09:00:00Z").toInstant().toEpochMilli()
        val completed=ZonedDateTime.parse("2026-10-05T18:00:00Z").toInstant().toEpochMilli()
        val next=TaskPlanning.nextOccurrence(task("a").copy(scheduledEpochMillis=schedule,repeatDays=7),completed,"b",zone)!!
        assertEquals("2026-10-12T09:00:00Z",java.time.Instant.ofEpochMilli(next.scheduledEpochMillis!!).toString())
    }
}
