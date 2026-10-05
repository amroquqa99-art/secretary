package com.alsekretary.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A read-only proposal. Finishing a proposed prerequisite is a condition, not a fact. */
data class PlannedSlot(val task: Task, val start: Long, val end: Long, val prerequisites: List<String>, val assumedDuration: Boolean)
data class WeekPlan(val slots: List<PlannedSlot>, val unplanned: List<Task>, val reserved: Int)
object AssistantPlanning {
    fun week(tasks: List<Task>, edges: List<TaskDependency>, calendar: List<CalendarEntry>, budget: Int, now: Long, zone: ZoneId): WeekPlan {
        require(budget in 1..1440)
        TaskPlanning.validateGraph(tasks.map { it.id }.toSet(),edges)
        val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val end=today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val fixed=calendar.filter { it.type!=CalendarItemType.MILESTONE && it.startEpochMillis<end }
        val alreadyScheduled=fixed.filter { it.linkedEntityType=="TASK" }.mapNotNull { it.linkedEntityId }.toSet()
        val remaining=tasks.filter { it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED) && it.id !in alreadyScheduled && (it.scheduledEpochMillis==null || it.scheduledEpochMillis<end) }.toMutableList()
        val slots=mutableListOf<PlannedSlot>();val completed=tasks.filter { it.status==TaskStatus.DONE }.map { it.id }.toMutableSet()
        val finish=mutableMapOf<String,Long>()
        for(day in 0L..6L) {
            val date=today.plusDays(day)
            val start=maxOf(now,date.atTime(9,0).atZone(zone).toInstant().toEpochMilli())
            val stop=date.atTime(21,0).atZone(zone).toInstant().toEpochMilli()
            if(start>=stop)continue
            val occupied=fixed.mapNotNull { item ->
                val s=if(item.allDay) Instant.ofEpochMilli(item.startEpochMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() else item.startEpochMillis
                val e=item.endEpochMillis ?: if(item.allDay) Instant.ofEpochMilli(s).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() else s+30*60_000L
                if(e<=start || s>=stop)null else maxOf(s,start) to minOf(e,stop)
            }.sortedBy { it.first }
            val free=mutableListOf<Pair<Long,Long>>();var cursor=start
            occupied.forEach { (s,e) -> if(s>cursor)free.add(cursor to s);cursor=maxOf(cursor,e) }
            if(cursor<stop)free.add(cursor to stop)
            var available=budget
            for((a,b) in free) {
                var at=a
                while(at<b && available>0) {
                    val candidates=remaining.filter { t ->
                        t.status!=TaskStatus.BLOCKED && edges.filter { it.taskId==t.id }.all { it.prerequisiteId in completed } &&
                            (t.estimatedMinutes ?: 30) in 1..available &&
                            maxOf(at,edges.filter { it.taskId==t.id }.maxOfOrNull { finish[it.prerequisiteId] ?: at } ?: at)+(t.estimatedMinutes ?: 30)*60_000L<=b
                    }.sortedWith(compareByDescending<Task> { it.priority }.thenBy { it.deadlineEpochMillis ?: Long.MAX_VALUE }.thenBy { it.id })
                    val task=candidates.firstOrNull() ?: break
                    val prerequisites=edges.filter { it.taskId==task.id }.map { it.prerequisiteId }
                    val slotStart=maxOf(at,prerequisites.maxOfOrNull { finish[it] ?: at } ?: at)
                    val duration=task.estimatedMinutes ?: 30;val slotEnd=slotStart+duration*60_000L
                    slots.add(PlannedSlot(task,slotStart,slotEnd,prerequisites.filter { it in finish },task.estimatedMinutes==null))
                    remaining.remove(task);completed.add(task.id);finish[task.id]=slotEnd;at=slotEnd;available-=duration
                }
            }
        }
        return WeekPlan(slots,remaining,fixed.size)
    }
    fun render(plan: WeekPlan,budget: Int,zone: ZoneId): String {
        val format=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        return "خطة 7 أيام مقترحة؛ لم تتغير المواعيد. نافذة افتراضية 09:00–21:00 بميزانية $budget دقيقة للمهام الإضافية يومياً (${zone.id}).\n"+
            "حُجزت ${plan.reserved} عناصر تقويم مسبقة؛ العنصر بلا مدة يفترض 30 دقيقة، وعناصر اليوم الكامل تحجز يومها. المعالم لا تحجز وقت عمل.\n"+
            (if(plan.slots.isEmpty()) "لا توجد مهام يمكن توزيعها ضمن القيود الحالية." else plan.slots.joinToString("\n") { s ->
                "• ${format.format(Instant.ofEpochMilli(s.start))}–${format.format(Instant.ofEpochMilli(s.end))}: ${s.task.title} [${s.task.id}]"+
                    (if(s.assumedDuration) " (30 دقيقة افتراضية)" else "")+
                    (if(s.prerequisites.isNotEmpty()) " — مشروط بإنجاز: ${s.prerequisites.joinToString()}" else "")+
                    (if(s.task.deadlineEpochMillis!=null && s.end>s.task.deadlineEpochMillis) " — يتجاوز الموعد النهائي" else "")
            })+"\nغير موزع: ${plan.unplanned.size} (عائق أو تبعية أو مدة لا تتسع). المهام المجدولة سابقاً تبقى في التقويم؛ لا تُضاف ثانية."
    }
}

data class AssistantLifeContext(val goals: List<Goal>, val projects: List<Project>, val tasks: List<Task>, val items: List<ProjectItem>, val checks: List<DailyCheck>)
object AssistantLifeReview {
    fun render(context: AssistantLifeContext,now: Long,zone: ZoneId): String {
        val goals=context.goals.filter { it.status==GoalStatus.ACTIVE };val projects=context.projects.filter { it.status !in setOf(ProjectStatus.CANCELLED,ProjectStatus.ARCHIVED,ProjectStatus.COMPLETED) }
        val open=context.tasks.filter { it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED) }
        val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val check=context.checks.filter { it.day<=today }.maxByOrNull { it.day }
        val lines=mutableListOf("مراجعة محلية من بياناتك المسجلة؛ لا تحتوي استنتاجاً من نموذج عصبي.","أهداف نشطة: ${goals.size}؛ مشاريع مفتوحة: ${projects.size}؛ مهام مفتوحة: ${open.size}.")
        if(goals.isEmpty())lines.add("الأهداف: لا توجد بيانات كافية لمراجعة اتجاه حياتك.")
        goals.take(20).forEach { goal ->
            val path=GoalHierarchy.lineage(goal,context.goals).joinToString(" ← ") { "${it.horizon.label}: ${it.title}" }
            val progress=if(goal.targetValue!=null && goal.targetValue>0 && goal.currentValue!=null) "${goal.currentValue}/${goal.targetValue} ${goal.unit.orEmpty()}" else "تقدم غير مسجل"
            lines.add("• $path [$progress] [${goal.id}]؛ مشاريع مباشرة: ${projects.count { it.goalId==goal.id }}")
            if(goal.deadlineEpochMillis!=null && goal.deadlineEpochMillis<now)lines.add("  الموعد مضى والهدف ما زال نشطاً؛ راجع حالته أو موعده.")
            if(GoalHierarchy.lineage(goal,context.goals).any { it.status==GoalStatus.CANCELLED })lines.add("  أحد الأهداف الأعلى ملغى؛ راجع صلة هذا الهدف به.")
        }
        projects.take(20).forEach { project ->
            val blockers=context.items.filter { it.projectId==project.id && !it.resolved && it.kind in setOf(WorkKind.BLOCKER,WorkKind.RISK,WorkKind.DECISION) }
            lines.add("• مشروع ${project.title} [${project.id}]: ${open.count { it.projectId==project.id }} مهام مفتوحة؛ ${blockers.size} عوائق/مخاطر/قرارات غير محسومة."+
                if(project.goalId==null) " لا يرتبط بهدف مسجل." else " هدف: ${project.goalId}.")
            blockers.take(3).forEach { lines.add("  ${it.kind.label}: ${it.title} [${it.id}]") }
        }
        lines.add("مهام تجاوزت الموعد: ${open.count { it.deadlineEpochMillis!=null && it.deadlineEpochMillis<now }}؛ دون تقدير مدة: ${open.count { it.estimatedMinutes==null }}.")
        lines.add(if(check==null) "النوم والطاقة: لا يوجد فحص يومي مسجل." else "آخر فحص ${check.day}: نوم ${check.sleepHours ?: "غير مسجل"} ساعة؛ طاقة ${check.energy ?: "غير مسجلة"}/5. ${check.nextAction}")
        if(goals.size>20 || projects.size>20)lines.add("عرض أول 20 هدفاً و20 مشروعاً فقط؛ الأعداد تشمل الكل.")
        lines.add("الربط يوضح الغرض؛ لا نجمع قياسات مختلفة لتقدير تقدم الهدف الأعلى. المراجعة والخطة اقتراحات دون تعديل بياناتك.")
        return lines.joinToString("\n")
    }
}
