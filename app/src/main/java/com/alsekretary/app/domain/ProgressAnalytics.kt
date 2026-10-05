package com.alsekretary.app.domain

import kotlin.math.roundToInt

object ProgressAnalytics {
    private const val WEEK = 604800000.0
    fun goal(goal: Goal, observations: List<GoalObservation>, now: Long): GoalSignal {
        val values=observations.filter { it.goalId==goal.id && it.at<=now }.sortedBy { it.at }.takeLast(12)
        val progress=if((goal.targetValue ?: 0.0)>0) ((goal.currentValue ?: 0.0)/goal.targetValue!!).coerceIn(0.0,1.0) else null
        val required=if(progress!=null && goal.deadlineEpochMillis!=null && goal.deadlineEpochMillis>now) (1-progress)/((goal.deadlineEpochMillis-now)/WEEK) else null
        val span=if(values.size>=2) values.last().at-values.first().at else 0L
        val velocity=if(span>=86400000L) (values.last().fraction-values.first().fraction)/(span/WEEK) else null
        val forecast=if(progress!=null && velocity!=null && velocity>0 && progress<1) now+(((1-progress)/velocity)*WEEK).toLong() else null
        val health=when {
            progress!=null && progress>=1 -> "مكتمل"
            goal.deadlineEpochMillis!=null && goal.deadlineEpochMillis<now -> "متأخر"
            velocity==null -> "بيانات غير كافية"
            required==null -> "بلا موعد نهائي"
            velocity>=required -> "على المسار"
            else -> "معرض للتأخير"
        }
        return GoalSignal(goal.id,health,velocity,required,forecast,values.size)
    }
    fun project(projectId: String,tasks: List<Task>,edges: List<TaskDependency>,items: List<ProjectItem>,now: Long): ProjectSignal {
        val relevant=tasks.filter{it.projectId==projectId && it.status!=TaskStatus.DROPPED}
        val all=tasks.associateBy{it.id}
        val links=(edges+tasks.filter{it.parentId!=null}.map{TaskDependency(it.parentId!!,it.id)}).distinct()
        TaskPlanning.validateGraph(tasks.map{it.id}.toSet(),links)
        val memo=mutableMapOf<String,Pair<Int,List<String>>>()
        fun length(id:String): Pair<Int,List<String>> = memo.getOrPut(id) {
            val t=all[id]
            if(t==null || t.status==TaskStatus.DONE || t.status==TaskStatus.DROPPED) 0 to emptyList()
            else {
                val prior=links.filter{it.taskId==id}.map{length(it.prerequisiteId)}.maxByOrNull{it.first} ?: (0 to emptyList())
                // Parent duration counts only the parent's own effort; child work is separate.
                prior.first+(t.estimatedMinutes ?: 0) to (prior.second+id)
            }
        }
        val critical=relevant.map{length(it.id)}.maxByOrNull{it.first} ?: (0 to emptyList())
        val blocked=relevant.count{it.status==TaskStatus.BLOCKED || TaskPlanning.unmet(it.id,tasks,links).isNotEmpty()}
        val overdue=relevant.count{it.status!=TaskStatus.DONE && it.deadlineEpochMillis!=null && it.deadlineEpochMillis<now}
        val openItems=items.filter{it.projectId==projectId && !it.resolved}
        val health=when {
            relevant.isEmpty() -> "بلا مهام"
            relevant.all{it.status==TaskStatus.DONE} -> "مكتمل"
            overdue>0 || openItems.any{it.kind==WorkKind.RISK} -> "معرض للتأخير"
            blocked>0 || openItems.any{it.kind==WorkKind.BLOCKER} -> "متعطل"
            else -> "نشط"
        }
        return ProjectSignal(projectId,relevant.count{it.status==TaskStatus.DONE},relevant.size,critical.second,critical.first,
            relevant.count{it.status!=TaskStatus.DONE && it.estimatedMinutes==null},blocked,overdue,openItems.count{it.kind==WorkKind.DECISION},health)
    }
    fun recovery(checks: List<DailyCheck>): Int? {
        val values=checks.takeLast(7).mapNotNull { c ->
            val sleep=c.sleepHours?.let{(it/8.0).coerceIn(0.0,1.0)}
            val energy=c.energy?.let{(it-1)/4.0}
            val available=listOfNotNull(sleep,energy)
            if(available.isEmpty()) null else available.average()
        }
        return if(values.isEmpty()) null else (values.average()*100).roundToInt()
    }
    fun suggestions(tasks: List<Task>,failures: Map<FailureCategory,Int>): List<ReviewSuggestion> = buildList {
        val blocked=tasks.filter{it.status==TaskStatus.BLOCKED}
        if(blocked.isNotEmpty()) add(ReviewSuggestion("أزل العوائق قبل زيادة الالتزامات","${blocked.size} مهام متعطلة", "اختر عائقاً واحداً وحدد إجراء لإزالته"))
        val ambiguous=tasks.count{it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED) && it.definitionOfDone.isNullOrBlank()}
        if(ambiguous>0) add(ReviewSuggestion("وضح نتيجة العمل","$ambiguous مهام مفتوحة بلا تعريف إنجاز", "اكتب نتيجة يمكن التحقق منها لكل مهمة"))
        failures.maxByOrNull{it.value}?.takeIf{it.value>=2}?.let { add(ReviewSuggestion("سبب تعثر متكرر", "${it.key.label}: ${it.value} حالات مسجلة", "اختر تغييراً واحداً لمعالجة هذا السبب في المراجعة")) }
    }
}
