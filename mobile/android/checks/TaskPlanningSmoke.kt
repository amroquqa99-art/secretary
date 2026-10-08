import com.alsekretary.app.domain.*
import java.time.*

fun main() {
    fun task(id: String,status: TaskStatus=TaskStatus.PLANNED)=Task(id,id,null,status,null,null,30,null,null)
    fun rejected(block:()->Unit) { check(runCatching(block).exceptionOrNull() is IllegalArgumentException) }
    val diamond=listOf(TaskDependency("b","a"),TaskDependency("c","a"),TaskDependency("d","b"),TaskDependency("d","c"))
    TaskPlanning.validateGraph(setOf("a","b","c","d"),diamond)
    rejected { TaskPlanning.validateGraph(setOf("a","b","c"),listOf(TaskDependency("a","b"),TaskDependency("b","c"),TaskDependency("c","a"))) }
    rejected { TaskPlanning.validateGraph(setOf("a"),listOf(TaskDependency("a","missing"))) }
    check(TaskPlanning.unmet("b",listOf(task("a",TaskStatus.DROPPED),task("b")),listOf(TaskDependency("b","a")))==listOf("a"))
    check(TaskPlanning.unmet("b",listOf(task("a",TaskStatus.DONE),task("b")),listOf(TaskDependency("b","a"))).isEmpty())
    val zone=ZoneId.of("Europe/Berlin")
    val before=ZonedDateTime.of(2026,3,28,9,0,0,0,zone).toInstant().toEpochMilli()
    val next=TaskPlanning.nextOccurrence(task("a").copy(scheduledEpochMillis=before,repeatDays=1,actualMinutes=80),before,"b",zone)!!
    check(Instant.ofEpochMilli(next.scheduledEpochMillis!!).atZone(zone).hour==9)
    check(next.scheduledEpochMillis!!-before==23L*60*60*1000)
    check(next.generatedFrom=="a" && next.actualMinutes==null && next.status==TaskStatus.PLANNED)
    val completed=Instant.parse("2026-10-05T18:00:00Z").toEpochMilli()
    val scheduled=Instant.parse("2026-09-01T09:00:00Z").toEpochMilli()
    val delayed=TaskPlanning.nextOccurrence(task("a").copy(scheduledEpochMillis=scheduled,repeatDays=7),completed,"b",ZoneId.of("UTC"))!!
    check(Instant.ofEpochMilli(delayed.scheduledEpochMillis!!).toString()=="2026-10-12T09:00:00Z")
    check(TaskPlanning.nextOccurrence(task("a"),completed,"b",zone)==null)
    println("PASS: Kotlin domain compiled; diamond dependencies, cycles, unknown nodes, dropped prerequisites, completion gating, DST, delayed repetition, nonrepeating tasks")
}
