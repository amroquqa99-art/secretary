package com.alsekretary.app.domain
import org.junit.Test
import org.junit.Assert.*
import java.time.*
class AssistantPlanningTest {
    private val zone=ZoneId.of("Asia/Jerusalem")
    private fun at(date: String,hour: Int)=LocalDate.parse(date).atTime(hour,0).atZone(zone).toInstant().toEpochMilli()
    private val now=at("2026-10-05",9)
    private fun task(id: String,minutes: Int?=30,priority: Int=0,status: TaskStatus=TaskStatus.PLANNED)=Task(id,id,null,status,null,null,minutes,null,null,priority=priority)
    private fun plan(tasks: List<Task>,edges: List<TaskDependency> = emptyList(),calendar: List<CalendarEntry> = emptyList(),budget: Int=120)=AssistantPlanning.week(tasks,edges,calendar,budget,now,zone)
    @Test fun dependenciesRunFirstEvenWhenDependentHasHigherPriority(){val p=plan(listOf(task("parent",30,9),task("child")),listOf(TaskDependency("parent","child")));assertEquals(listOf("child","parent"),p.slots.map { it.task.id });assertTrue(p.slots[1].start>=p.slots[0].end);assertEquals(listOf("child"),p.slots[1].prerequisites)}
    @Test fun blockedPrerequisiteNeverBecomesAssumedComplete(){val p=plan(listOf(task("blocked",status=TaskStatus.BLOCKED),task("later")),listOf(TaskDependency("later","blocked")));assertTrue(p.slots.isEmpty());assertEquals(2,p.unplanned.size)}
    @Test fun overlappingAndCrossDayEventsAreMerged(){val a=CalendarEntry("a","meeting",CalendarItemType.MEETING,at("2026-10-04",20),at("2026-10-05",10));val b=a.copy(id="b",startEpochMillis=at("2026-10-05",9),endEpochMillis=at("2026-10-05",11));val p=plan(listOf(task("work")),calendar=listOf(a,b));assertEquals(at("2026-10-05",11),p.slots.single().start)}
    @Test fun allDayEventsReserveTheirDay(){val event=CalendarEntry("day","away",CalendarItemType.EVENT,at("2026-10-05",0),null,true);assertEquals(at("2026-10-06",9),plan(listOf(task("work")),calendar=listOf(event)).slots.single().start)}
    @Test fun scheduledTasksAreNotDuplicatedAndUnknownEstimatesAreExplicit(){val fixed=CalendarEntry("fixed","scheduled",CalendarItemType.TASK,now,now+30*60_000,"false".toBoolean(),"TASK","fixed");val p=plan(listOf(task("fixed"),task("unknown",null)),calendar=listOf(fixed));assertEquals(listOf("unknown"),p.slots.map { it.task.id });assertTrue(AssistantPlanning.render(p,120,zone).contains("افتراضية"))}
    @Test fun budgetIsPerDayAndLateDeadlinesAreFlagged(){val p=plan(listOf(task("one",60).copy(deadlineEpochMillis=now),task("two",60)),budget=60);assertEquals(listOf("2026-10-05","2026-10-06"),p.slots.map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().toString() });assertTrue(AssistantPlanning.render(p,60,zone).contains("يتجاوز الموعد"))}
    @Test fun dstUsesLocalDatesAndLeavesNoOverlaps(){val instant=at("2026-10-23",20);val tasks=(1..7).map { task("$it",60) };val p=AssistantPlanning.week(tasks,emptyList(),emptyList(),60,instant,zone);assertEquals(7,p.slots.size);assertEquals(7,p.slots.map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.distinct().size);assertTrue(p.slots.zipWithNext().all { (a,b)->a.end<=b.start })}
    @Test fun emptyReviewStatesUnknownsInsteadOfInventing(){val text=AssistantLifeReview.render(AssistantLifeContext(emptyList(),emptyList(),emptyList(),emptyList(),emptyList()),now,zone);assertTrue(text.contains("لا توجد بيانات كافية"));assertTrue(text.contains("لا يوجد فحص يومي"))}
}
