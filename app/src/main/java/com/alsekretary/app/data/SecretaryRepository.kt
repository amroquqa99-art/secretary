package com.alsekretary.app.data

import android.content.ContentValues
import android.database.Cursor
import com.alsekretary.app.domain.*
import java.util.UUID
import kotlin.math.roundToInt

class SecretaryRepository(private val db: SecretaryDatabase) {
    fun listTodayTasks(includeDropped: Boolean = false): List<Task> {
        val where = if (includeDropped) "" else "WHERE status != 'DROPPED'"
        val cursor = db.readableDatabase.rawQuery(
            "SELECT id,title,project_id,status,scheduled_at,deadline,estimated_minutes,actual_minutes,definition_of_done,priority,repeat_days,generated_from,parent_id,failure_category,failure_reason FROM tasks $where ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'PLANNED' THEN 1 WHEN 'INBOX' THEN 2 WHEN 'BLOCKED' THEN 3 ELSE 4 END, priority DESC, scheduled_at ASC",
            null
        )
        return cursor.use { c -> generateSequence { if (c.moveToNext()) c else null }.map { rowToTask(it) }.toList() }
    }

    fun taskById(id: String): Task? = db.readableDatabase.rawQuery(
        "SELECT id,title,project_id,status,scheduled_at,deadline,estimated_minutes,actual_minutes,definition_of_done,priority,repeat_days,generated_from,parent_id,failure_category,failure_reason FROM tasks WHERE id=?",
        arrayOf(id)
    ).use { if (it.moveToFirst()) rowToTask(it) else null }

    fun addQuickTask(title: String): Task {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id); put("title", title); put("status", TaskStatus.INBOX.name)
            put("priority", 0); put("created_at", now); put("updated_at", now)
        }
        db.writableDatabase.insertOrThrow("tasks", null, values)
        recordEvent("TASK_CREATED", "TASK", id, "source=quick_capture")
        return Task(id, title, null, TaskStatus.INBOX, null, null, null, null, null)
    }

    fun markDone(taskId: String) {
        val task = taskById(taskId) ?: return
        if(task.status == TaskStatus.DONE) return
        saveTask(task.copy(status=TaskStatus.DONE))
    }

    fun setTaskBlocked(taskId: String, reason: String) {
        taskById(taskId)?.let { saveTask(it.copy(status=TaskStatus.BLOCKED,failureCategory=FailureCategory.EXTERNAL,failureReason=reason)) }
    }

    fun saveTask(task: Task, prerequisites: Set<String>? = null) {
        require(task.title.isNotBlank())
        require(task.repeatDays == null || task.repeatDays in 1..365)
        val old = taskById(task.id)
        require(old?.status != TaskStatus.DONE || task.status == TaskStatus.DONE || old.repeatDays == null) { "المهمة المتكررة المكتملة محفوظة كسجل؛ عدّل النسخة التالية" }
        val edges = if(prerequisites == null) listDependencies() else listDependencies().filter { it.taskId != task.id } + prerequisites.map { TaskDependency(task.id,it) }
        val tasks = listTodayTasks(true).filter { it.id != task.id } + task
        val planningEdges=(edges+tasks.filter{it.parentId!=null}.map{TaskDependency(it.parentId!!,it.id)}).distinct()
        TaskPlanning.validateGraph(tasks.map { it.id }.toSet(),planningEdges)
        if(task.status == TaskStatus.ACTIVE || (task.status == TaskStatus.DONE && (old?.status != task.status || prerequisites != null))) {
            require(TaskPlanning.unmet(task.id,tasks,planningEdges).isEmpty()) { "أكمل المهام المطلوبة أولاً" }
        }
        require(task.estimatedMinutes == null || task.estimatedMinutes > 0)
        require(task.actualMinutes == null || task.actualMinutes >= 0)
        require(task.priority in 0..5)
        require(task.parentId == null || tasks.any{it.id==task.parentId && it.projectId==task.projectId}) { "المهمة الأم يجب أن تكون ضمن المشروع نفسه" }
        if(task.status in setOf(TaskStatus.BLOCKED,TaskStatus.DROPPED) && old?.status != task.status) require(task.failureCategory!=null && !task.failureReason.isNullOrBlank()) { "سجل سبب التعثر" }
        require(task.projectId == null || listProjects(true).any { it.id == task.projectId })
        val postponed=old?.scheduledEpochMillis!=null && task.scheduledEpochMillis!=null && task.scheduledEpochMillis>old.scheduledEpochMillis
        if(postponed) require(task.failureCategory!=null && !task.failureReason.isNullOrBlank()){"اذكر سبب تأجيل المهمة"}
        val sqlDb = db.writableDatabase
        sqlDb.beginTransaction()
        try {
            val exists = taskById(task.id) != null
            val values = ContentValues().apply {
                put("id",task.id); put("title",task.title.trim()); putNullable("project_id",task.projectId)
                put("status",task.status.name); putNullable("scheduled_at",task.scheduledEpochMillis)
                putNullable("deadline",task.deadlineEpochMillis); putNullable("estimated_minutes",task.estimatedMinutes)
                putNullable("actual_minutes",task.actualMinutes); putNullable("definition_of_done",task.definitionOfDone)
                putNullable("parent_id",task.parentId);putNullable("failure_category",task.failureCategory?.name);putNullable("failure_reason",task.failureReason)
                putNullable("repeat_days",task.repeatDays); putNullable("generated_from",task.generatedFrom)
                put("priority",task.priority); put("updated_at",System.currentTimeMillis())
                if(!exists) put("created_at",System.currentTimeMillis())
            }
            if(exists) sqlDb.update("tasks",values,"id=?",arrayOf(task.id)) else sqlDb.insertOrThrow("tasks",null,values)
            if(prerequisites != null) {
                sqlDb.delete("task_dependencies","task_id=?",arrayOf(task.id))
                prerequisites.forEach { id -> sqlDb.insertOrThrow("task_dependencies",null,ContentValues().apply { put("task_id",task.id);put("prerequisite_id",id) }) }
            }
            if(postponed || (task.status in setOf(TaskStatus.BLOCKED,TaskStatus.DROPPED) && old?.status != task.status)) {
                sqlDb.insertOrThrow("task_failures",null,ContentValues().apply { put("task_id",task.id);put("category",task.failureCategory!!.name);put("reason",task.failureReason);put("at",System.currentTimeMillis()) })
            }
            if(postponed) recordEvent("TASK_DELAYED","TASK",task.id,task.failureReason)
            if(task.status == TaskStatus.DONE && old?.status != TaskStatus.DONE) {
                recordEvent("TASK_COMPLETED","TASK",task.id,"actual_minutes=${task.actualMinutes}")
                TaskPlanning.nextOccurrence(task,System.currentTimeMillis(),UUID.randomUUID().toString(),java.time.ZoneId.systemDefault())?.let { next -> saveTask(next,prerequisites ?: edges.filter { it.taskId == task.id }.map { it.prerequisiteId }.toSet()) }
            }
            recordEvent(if(!exists) "TASK_CREATED" else "TASK_UPDATED","TASK",task.id,"status=${task.status}")
            sqlDb.setTransactionSuccessful()
        } finally { sqlDb.endTransaction() }
    }

    fun planningDependencies(): List<TaskDependency> = (listDependencies()+listTodayTasks(true).filter{it.parentId!=null}.map{TaskDependency(it.parentId!!,it.id)}).distinct()
    fun listDependencies(): List<TaskDependency> = db.readableDatabase.rawQuery("SELECT task_id,prerequisite_id FROM task_dependencies",null).use { c ->
        buildList { while(c.moveToNext()) add(TaskDependency(c.getString(0),c.getString(1))) }
    }
    fun listMilestones(): List<Milestone> = db.readableDatabase.rawQuery("SELECT id,project_id,title,due_at,completed FROM milestones ORDER BY due_at",null).use { c ->
        buildList { while(c.moveToNext()) add(Milestone(c.getString(0),c.getString(1),c.getString(2),c.getLongOrNull(3),c.getInt(4)!=0)) }
    }
    fun saveMilestone(m: Milestone) {
        require(m.title.isNotBlank() && listProjects(true).any { it.id == m.projectId })
        db.writableDatabase.beginTransaction()
        try {
            val values=ContentValues().apply { put("id",m.id);put("project_id",m.projectId);put("title",m.title.trim());putNullable("due_at",m.dueAt);put("completed",if(m.completed)1 else 0) }
            db.writableDatabase.insertWithOnConflict("milestones",null,values,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            recordEvent("MILESTONE_UPDATED","MILESTONE",m.id,"completed=${m.completed}")
            db.writableDatabase.setTransactionSuccessful()
        } finally { db.writableDatabase.endTransaction() }
    }
    fun deleteMilestone(id: String) {
        db.writableDatabase.delete("milestones","id=?",arrayOf(id))
        recordEvent("MILESTONE_DELETED","MILESTONE",id,null)
    }

    fun listProjectItems(): List<ProjectItem> = db.readableDatabase.rawQuery("SELECT id,project_id,kind,title,detail,resolved,uri FROM project_items ORDER BY kind,title",null).use { c -> buildList { while(c.moveToNext()) add(ProjectItem(c.getString(0),c.getString(1),WorkKind.valueOf(c.getString(2)),c.getString(3),c.getString(4),c.getInt(5)!=0,c.getStringOrNull(6))) } }
    fun saveProjectItem(item: ProjectItem) {
        require(item.title.isNotBlank() && listProjects(true).any{it.id==item.projectId})
        val values=ContentValues().apply { put("id",item.id);put("project_id",item.projectId);put("kind",item.kind.name);put("title",item.title);put("detail",item.detail);put("resolved",if(item.resolved)1 else 0);putNullable("uri",item.uri) }
        db.writableDatabase.insertWithOnConflict("project_items",null,values,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        recordEvent("PROJECT_ITEM_UPDATED","PROJECT",item.projectId,"kind=${item.kind};resolved=${item.resolved}")
    }
    fun deleteProjectItem(id: String) { db.writableDatabase.delete("project_items","id=?",arrayOf(id));recordEvent("PROJECT_ITEM_DELETED","ITEM",id,null) }
    fun listDailyChecks(): List<DailyCheck> = db.readableDatabase.rawQuery("SELECT day,sleep_hours,energy,wins,obstacles,next_action FROM daily_checks ORDER BY day",null).use { c -> buildList { while(c.moveToNext()) add(DailyCheck(c.getString(0),c.getDoubleOrNull(1),c.getIntOrNull(2),c.getString(3),c.getString(4),c.getString(5))) } }
    fun saveDailyCheck(c: DailyCheck) {
        require(java.time.LocalDate.parse(c.day)<=java.time.LocalDate.now())
        require(c.sleepHours==null || (c.sleepHours.isFinite() && c.sleepHours in 0.0..24.0))
        require(c.energy==null || c.energy in 1..5)
        val values=ContentValues().apply { put("day",c.day);putNullable("sleep_hours",c.sleepHours);putNullable("energy",c.energy);put("wins",c.wins);put("obstacles",c.obstacles);put("next_action",c.nextAction) }
        db.writableDatabase.insertWithOnConflict("daily_checks",null,values,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        recordEvent("DAILY_REVIEW_SAVED","REVIEW",c.day,null)
    }
    fun failureCounts(since: Long): Map<FailureCategory,Int> = db.readableDatabase.rawQuery("SELECT category,COUNT(*) FROM task_failures WHERE at>=? GROUP BY category",arrayOf(since.toString())).use { c -> buildMap { while(c.moveToNext()) put(FailureCategory.valueOf(c.getString(0)),c.getInt(1)) } }
    fun listGoalObservations(): List<GoalObservation> = db.readableDatabase.rawQuery("SELECT goal_id,at,fraction FROM goal_observations ORDER BY at",null).use { c -> buildList { while(c.moveToNext()) add(GoalObservation(c.getString(0),c.getLong(1),c.getDouble(2))) } }
    fun resolveDecision(id: String,status: DecisionStatus) {
        require(status!=DecisionStatus.PENDING)
        db.writableDatabase.update("decisions",ContentValues().apply{put("status",status.name)},"id=?",arrayOf(id))
        recordEvent("DECISION_RESOLVED","DECISION",id,"status=$status")
    }

    fun listHabits(): List<Habit> = db.readableDatabase.rawQuery("SELECT id,title,area,target,archived FROM habits WHERE archived=0",null).use { c ->
        buildList { while(c.moveToNext()) add(Habit(c.getString(0),c.getString(1),LifeArea.valueOf(c.getString(2)),c.getInt(3),c.getInt(4)!=0)) }
    }
    fun listHabitChecks(): List<HabitCheck> = db.readableDatabase.rawQuery("SELECT habit_id,day FROM habit_checks",null).use { c ->
        buildList { while(c.moveToNext()) add(HabitCheck(c.getString(0),c.getString(1))) }
    }
    fun saveHabit(h: Habit) {
        require(h.title.isNotBlank() && h.targetPerWeek in 1..7)
        val values = ContentValues().apply { put("id",h.id);put("title",h.title.trim());put("area",h.area.name);put("target",h.targetPerWeek);put("archived",if(h.archived)1 else 0) }
        db.writableDatabase.insertWithOnConflict("habits",null,values,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        recordEvent("HABIT_UPDATED","HABIT",h.id,null)
    }
    fun toggleHabit(id: String, day: String) {
        require(java.time.LocalDate.parse(day)<=java.time.LocalDate.now()) { "لا تسجل إنجازاً في المستقبل" }
        require(listHabits().any { it.id == id })
        val database=db.writableDatabase
        database.beginTransaction()
        try {
            val removed=database.delete("habit_checks","habit_id=? AND day=?",arrayOf(id,day))
            if(removed==0) database.insertOrThrow("habit_checks",null,ContentValues().apply { put("habit_id",id);put("day",day) })
            recordEvent(if(removed==0) "HABIT_CHECKED" else "HABIT_UNCHECKED","HABIT",id,"day=$day")
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    fun listReviews(): List<WeeklyReview> = db.readableDatabase.rawQuery("SELECT id,week,wins,blockers,next_action,energy FROM weekly_reviews ORDER BY week DESC",null).use { c ->
        buildList { while(c.moveToNext()) add(WeeklyReview(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5))) }
    }
    fun saveReview(r: WeeklyReview) {
        require(r.energy in 1..5 && r.nextAction.isNotBlank())
        java.time.LocalDate.parse(r.week)
        val values=ContentValues().apply { put("id",r.id);put("week",r.week);put("wins",r.wins);put("blockers",r.blockers);put("next_action",r.nextAction);put("energy",r.energy) }
        db.writableDatabase.insertWithOnConflict("weekly_reviews",null,values,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        recordEvent("REVIEW_SAVED","REVIEW",r.id,null)
    }
    fun capacityHours(): Double = db.readableDatabase.rawQuery("SELECT value FROM preferences WHERE key='weekly_capacity'",null).use { if(it.moveToFirst()) it.getString(0).toDoubleOrNull() ?: 40.0 else 40.0 }
    fun setCapacity(hours: Double) {
        require(hours.isFinite() && hours > 0 && hours <= 168)
        db.writableDatabase.insertWithOnConflict("preferences",null,ContentValues().apply { put("key","weekly_capacity");put("value",hours.toString()) },android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    // Goals -----------------------------------------------------------------
    fun listGoals(includeCancelled: Boolean = false): List<Goal> {
        val where = if (includeCancelled) "" else "WHERE status!='CANCELLED'"
        return db.readableDatabase.rawQuery(
            "SELECT id,title,area_code,specific,metric_name,target_value,current_value,unit,deadline,relevant_reason,achievable_note,status,started_at,horizon,parent_goal_id FROM goals $where ORDER BY updated_at DESC",
            null
        ).use { c -> generateSequence { if (c.moveToNext()) c else null }.map(::rowToGoal).toList() }
    }

    fun saveGoal(goal: Goal) {
        require(goal.title.isNotBlank() && goal.title.length <= 300) { "عنوان الهدف من 1 إلى 300 حرف" }
        require(listOfNotNull(goal.targetValue,goal.currentValue).all { it.isFinite() }) { "قيمة الهدف غير صالحة" }
        val all=listGoals(true)
        GoalHierarchy.validate(all.filterNot { it.id == goal.id } + goal)
        if(goal.parentGoalId!=all.firstOrNull { it.id==goal.id }?.parentGoalId && goal.parentGoalId!=null) {
            require(all.any { it.id==goal.parentGoalId && it.status!=GoalStatus.CANCELLED }) { "لا تربط هدفاً جديداً بهدف ملغى" }
        }
        val database=db.writableDatabase;database.beginTransaction()
        try { saveGoalInsideTransaction(goal);database.setTransactionSuccessful() } finally {database.endTransaction()}
    }

    private fun saveGoalInsideTransaction(goal: Goal) {
        val now = System.currentTimeMillis()
        val prior = listGoals(true).firstOrNull{it.id==goal.id}
        val existing = prior != null
        val values = ContentValues().apply {
            put("horizon",goal.horizon.name);putNullable("parent_goal_id",goal.parentGoalId)
            put("id", goal.id); put("title", goal.title); put("area_code", goal.area.name); put("specific", goal.specific)
            put("metric_name", goal.metricName); putNullable("target_value", goal.targetValue); putNullable("current_value", goal.currentValue)
            putNullable("unit", goal.unit); putNullable("deadline", goal.deadlineEpochMillis); put("relevant_reason", goal.relevantReason)
            put("achievable_note", goal.achievableNote); put("status", goal.status.name); put("updated_at", now); put("started_at",prior?.startedAt ?: goal.startedAt ?: now)
            if (!existing) put("created_at", now)
        }
        if (existing) db.writableDatabase.update("goals", values, "id=?", arrayOf(goal.id))
        else db.writableDatabase.insertOrThrow("goals", null, values)
        if(prior!=null && prior.targetValue!=goal.targetValue) db.writableDatabase.delete("goal_observations","goal_id=?",arrayOf(goal.id))
        if(goal.targetValue!=null && goal.targetValue>0 && (prior==null || prior.currentValue!=goal.currentValue || prior.targetValue!=goal.targetValue)) {
            db.writableDatabase.insertOrThrow("goal_observations",null,ContentValues().apply { put("goal_id",goal.id);put("at",now);put("fraction",((goal.currentValue ?: 0.0)/goal.targetValue).coerceIn(0.0,1.0)) })
        }
        recordEvent(if (existing) "GOAL_UPDATED" else "GOAL_CREATED", "GOAL", goal.id, "smart_score=${SmartGoalValidator.validate(goal).score}")
    }

    fun cancelGoal(id: String) {
        val values = ContentValues().apply { put("status", GoalStatus.CANCELLED.name); put("updated_at", System.currentTimeMillis()) }
        db.writableDatabase.update("goals", values, "id=?", arrayOf(id))
        recordEvent("GOAL_CANCELLED", "GOAL", id, null)
    }

    // Projects --------------------------------------------------------------
    fun listProjects(includeArchived: Boolean = false): List<Project> {
        val where = if (includeArchived) "" else "WHERE status!='ARCHIVED' AND status!='CANCELLED'"
        return db.readableDatabase.rawQuery(
            "SELECT id,title,outcome,goal_id,status,progress,deadline,load_score,override_reason FROM projects $where ORDER BY updated_at DESC",
            null
        ).use { c -> generateSequence { if (c.moveToNext()) c else null }.map(::rowToProject).toList() }
    }

    fun saveProject(project: Project, classifier: ClassificationResult? = null) {
        val now = System.currentTimeMillis()
        val existing = scalarInt("SELECT COUNT(*) FROM projects WHERE id=?", arrayOf(project.id)) > 0
        val values = ContentValues().apply {
            put("id", project.id); put("title", project.title); put("outcome", project.outcome); putNullable("goal_id", project.goalId)
            put("status", project.status.name); put("progress", project.progress.coerceIn(0.0, 1.0)); putNullable("deadline", project.deadlineEpochMillis)
            put("load_score", project.loadScore.coerceIn(0, 100)); putNullable("override_reason", project.overrideReason); put("updated_at", now)
            if (!existing) put("created_at", now)
        }
        if (existing) db.writableDatabase.update("projects", values, "id=?", arrayOf(project.id))
        else db.writableDatabase.insertOrThrow("projects", null, values)
        if (!existing && classifier != null) {
            val feedback = ContentValues().apply {
                put("project_id", project.id); put("classifier_kind", classifier.kind.name); put("classifier_confidence", classifier.confidence)
                putNullable("override_reason", project.overrideReason); put("created_at", now)
            }
            db.writableDatabase.insert("project_feedback", null, feedback)
        }
        recordEvent(if (existing) "PROJECT_UPDATED" else "PROJECT_CREATED", "PROJECT", project.id, project.overrideReason?.let { "override=$it" })
    }

    fun archiveProject(id: String) {
        val values = ContentValues().apply { put("status", ProjectStatus.ARCHIVED.name); put("updated_at", System.currentTimeMillis()) }
        db.writableDatabase.update("projects", values, "id=?", arrayOf(id))
        recordEvent("PROJECT_ARCHIVED", "PROJECT", id, null)
    }

    // Calendar --------------------------------------------------------------
    fun listCalendarEntries(from: Long, to: Long): List<CalendarEntry> {
        val events = db.readableDatabase.rawQuery(
            "SELECT id,title,type,start_at,end_at,all_day,linked_entity_type,linked_entity_id,notes FROM calendar_events WHERE start_at<? AND COALESCE(end_at,start_at+1800000)>? ORDER BY start_at ASC",
            arrayOf(to.toString(), from.toString())
        ).use { c -> generateSequence { if (c.moveToNext()) c else null }.map(::rowToCalendar).toMutableList() }

        db.readableDatabase.rawQuery(
            "SELECT id,title,scheduled_at,estimated_minutes FROM tasks WHERE scheduled_at>=? AND scheduled_at<? AND status!='DROPPED'",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val start = c.getLong(2)
                val mins = c.getIntOrNull(3) ?: 30
                events += CalendarEntry(
                    id = "task:${c.getString(0)}", title = c.getString(1), type = CalendarItemType.TASK,
                    startEpochMillis = start, endEpochMillis = start + mins * 60_000L,
                    linkedEntityType = "TASK", linkedEntityId = c.getString(0)
                )
            }
        }
        listMilestones().filter { !it.completed && it.dueAt != null && it.dueAt >= from && it.dueAt < to }.forEach { m ->
            events.add(CalendarEntry("milestone:${m.id}",m.title,CalendarItemType.MILESTONE,m.dueAt!!,null,true,"MILESTONE",m.id))
        }
        return events.sortedBy { it.startEpochMillis }
    }

    fun saveCalendarEntry(entry: CalendarEntry) {
        require(entry.type != CalendarItemType.TASK) { "Scheduled tasks are edited through task data" }
        val now = System.currentTimeMillis()
        val existing = scalarInt("SELECT COUNT(*) FROM calendar_events WHERE id=?", arrayOf(entry.id)) > 0
        val values = ContentValues().apply {
            put("id", entry.id); put("title", entry.title); put("type", entry.type.name); put("start_at", entry.startEpochMillis)
            putNullable("end_at", entry.endEpochMillis); put("all_day", if (entry.allDay) 1 else 0); putNullable("linked_entity_type", entry.linkedEntityType)
            putNullable("linked_entity_id", entry.linkedEntityId); putNullable("notes", entry.notes); put("updated_at", now); if (!existing) put("created_at", now)
        }
        if (existing) db.writableDatabase.update("calendar_events", values, "id=?", arrayOf(entry.id))
        else db.writableDatabase.insertOrThrow("calendar_events", null, values)
        recordEvent(if (existing) "CALENDAR_ITEM_UPDATED" else "CALENDAR_ITEM_CREATED", "CALENDAR", entry.id, "type=${entry.type.name}")
    }

    fun deleteCalendarEntry(id: String) {
        db.writableDatabase.delete("calendar_events", "id=?", arrayOf(id))
        recordEvent("CALENDAR_ITEM_DELETED", "CALENDAR", id, null)
    }

    // Notes -----------------------------------------------------------------
    fun listNotes(): List<Note> = db.readableDatabase.rawQuery(
        "SELECT id,title,markdown,created_at,updated_at FROM notes ORDER BY updated_at DESC", null
    ).use { c -> generateSequence { if (c.moveToNext()) c else null }.map(::rowToNote).toList() }

    fun saveNote(note: Note): Note {
        val now = System.currentTimeMillis()
        val existing = scalarInt("SELECT COUNT(*) FROM notes WHERE id=?", arrayOf(note.id)) > 0
        val normalized = note.copy(updatedAt = now, createdAt = if (existing) note.createdAt else now)
        val values = ContentValues().apply {
            put("id", normalized.id); put("title", normalized.title); put("markdown", normalized.markdown)
            put("created_at", normalized.createdAt); put("updated_at", normalized.updatedAt)
        }
        if (existing) db.writableDatabase.update("notes", values, "id=?", arrayOf(note.id)) else db.writableDatabase.insertOrThrow("notes", null, values)
        recordEvent(if (existing) "NOTE_UPDATED" else "NOTE_CREATED", "NOTE", note.id, null)
        return normalized
    }

    fun deleteNote(id: String) {
        db.writableDatabase.delete("notes", "id=?", arrayOf(id))
        recordEvent("NOTE_DELETED", "NOTE", id, null)
    }

    // Focus -----------------------------------------------------------------
    fun startFocus(taskId: String, mode: FocusMode, targetMinutes: Int?, blockedPackages: Set<String>): FocusSession {
        activeFocus()?.let { return it }
        require(TaskPlanning.unmet(taskId,listTodayTasks(true),planningDependencies()).isEmpty()) { "أكمل المهام المطلوبة أولاً" }
        require(taskById(taskId)?.status !in setOf(null, TaskStatus.DONE, TaskStatus.DROPPED))
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val session = FocusSession(id, taskId, mode, FocusStatus.RUNNING, now, null, targetMinutes, blockedPackages)
        val values = ContentValues().apply {
            put("id", id); put("task_id", taskId); put("mode", mode.name); put("status", FocusStatus.RUNNING.name)
            put("started_at", now); putNullable("target_minutes", targetMinutes); put("blocked_packages", blockedPackages.joinToString("\u001F"))
        }
        db.writableDatabase.insertOrThrow("focus_sessions", null, values)
        val taskValues = ContentValues().apply { put("status", TaskStatus.ACTIVE.name); put("updated_at", now) }
        db.writableDatabase.update("tasks", taskValues, "id=?", arrayOf(taskId))
        recordEvent("FOCUS_STARTED", "TASK", taskId, "mode=${mode.name}")
        return session
    }

    fun activeFocus(): FocusSession? = db.readableDatabase.rawQuery(
        "SELECT id,task_id,mode,status,started_at,ended_at,target_minutes,blocked_packages,exit_reason FROM focus_sessions WHERE status='RUNNING' ORDER BY started_at DESC LIMIT 1", null
    ).use { if (it.moveToFirst()) rowToFocus(it) else null }

    fun finishFocus(sessionId: String, status: FocusStatus, reason: String? = null): FocusSession? {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val result = finishFocusInternal(sessionId,status,reason)
            database.setTransactionSuccessful()
            return result
        } finally { database.endTransaction() }
    }

    private fun finishFocusInternal(sessionId: String, status: FocusStatus, reason: String?): FocusSession? {
        val session = focusById(sessionId) ?: return null
        if (session.status != FocusStatus.RUNNING) return session
        require(status != FocusStatus.RUNNING)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply { put("status", status.name); put("ended_at", now); putNullable("exit_reason", reason) }
        db.writableDatabase.update("focus_sessions", values, "id=?", arrayOf(sessionId))
        val minutes = ((now - session.startedAt) / 60_000L).toInt().coerceAtLeast(0)
        when (status) {
            FocusStatus.COMPLETED -> {
                taskById(session.taskId)?.let { saveTask(it.copy(status=TaskStatus.DONE, actualMinutes=minutes)) }
            }
            FocusStatus.BLOCKED -> setTaskBlocked(session.taskId, reason ?: "غير محدد")
            FocusStatus.EMERGENCY_EXIT, FocusStatus.CANCELLED -> recordEvent("FOCUS_INTERRUPTED", "TASK", session.taskId, "reason=${reason.orEmpty()};actual_minutes=$minutes")
            FocusStatus.RUNNING -> Unit
        }
        recordEvent("FOCUS_ENDED", "FOCUS", sessionId, "status=${status.name};minutes=$minutes")
        return session.copy(status = status, endedAt = now, exitReason = reason)
    }

    private fun focusById(id: String): FocusSession? = db.readableDatabase.rawQuery(
        "SELECT id,task_id,mode,status,started_at,ended_at,target_minutes,blocked_packages,exit_reason FROM focus_sessions WHERE id=?", arrayOf(id)
    ).use { if (it.moveToFirst()) rowToFocus(it) else null }

    // Dashboard -------------------------------------------------------------
    fun dashboardSnapshot(): DashboardSnapshot {
        val goals = listGoals()
        val projects = listProjects()
        val tasks = listTodayTasks()
        val activeGoals = goals.count { it.status == GoalStatus.ACTIVE }
        val atRisk = projects.count { it.status == ProjectStatus.AT_RISK || it.loadScore >= 85 }
        val relevantTasks = tasks.filter { it.status != TaskStatus.INBOX }
        val doneTasks = relevantTasks.count { it.status == TaskStatus.DONE }
        val execution = if (relevantTasks.isEmpty()) 0 else (doneTasks * 100.0 / relevantTasks.size).roundToInt().coerceIn(0, 100)

        val windowStart=System.currentTimeMillis()
        val windowEnd=windowStart+7L*86400000
        val committedTasks=tasks.filter { t -> t.status in setOf(TaskStatus.PLANNED,TaskStatus.ACTIVE,TaskStatus.BLOCKED) &&
            (t.status==TaskStatus.ACTIVE || t.scheduledEpochMillis?.let{it<windowEnd}==true || t.deadlineEpochMillis?.let{it<windowEnd}==true) }
        val meetingMinutes=listCalendarEntries(windowStart,windowEnd).filter{it.type in setOf(CalendarItemType.EVENT,CalendarItemType.MEETING) && !it.allDay}
            .sumOf{((it.endEpochMillis ?: it.startEpochMillis)-it.startEpochMillis).coerceAtLeast(0)/60000}.toInt()
        val committedMinutes = committedTasks.sumOf { it.estimatedMinutes ?: 30 }+meetingMinutes
        val availableHours = capacityHours()
        val committedHours = committedMinutes / 60.0
        val load = if (availableHours <= 0) 0 else ((committedHours / availableHours) * 100).roundToInt().coerceIn(0, 150)

        val areaScores = linkedMapOf<LifeArea, Int?>()
        LifeArea.entries.forEach { area ->
            val areaGoals = goals.filter { it.area == area && it.status == GoalStatus.ACTIVE }
            val progress = if (areaGoals.isEmpty()) 0.0 else areaGoals.map { g ->
                val target = g.targetValue ?: 0.0
                if (target <= 0.0) 0.0 else ((g.currentValue ?: 0.0) / target).coerceIn(0.0, 1.0)
            }.average()
            areaScores[area] = if(areaGoals.isEmpty()) null else (progress * 100).roundToInt()
        }

        val durationObservations = db.readableDatabase.rawQuery(
            "SELECT estimated_minutes,actual_minutes FROM tasks WHERE status='DONE' AND estimated_minutes IS NOT NULL AND actual_minutes IS NOT NULL AND project_id=(SELECT project_id FROM tasks WHERE status='DONE' AND project_id IS NOT NULL ORDER BY updated_at DESC LIMIT 1) ORDER BY updated_at DESC LIMIT 20", null
        ).use { c -> buildList { while (c.moveToNext()) add(DurationObservation(c.getInt(0), c.getInt(1))) } }
        val durationSignal = BehaviorRules.durationUnderestimation(durationObservations)
        val insights = buildList {
            if (durationSignal.triggered) add(
                BehaviorInsight("duration-under", "أنت تقلل تقدير مدة بعض المهام", durationSignal.evidence, durationSignal.confidence, "زد تقدير المهام المشابهة قبل إضافتها إلى التقويم.")
            )
            if (load > 100) add(
                BehaviorInsight("overload", "حمل الالتزامات أعلى من القدرة", "الالتزامات المقدرة ${"%.1f".format(committedHours)} ساعة مقابل $availableHours ساعة متاحة.", 0.95, "خفف النطاق أو أوقف مشروعًا منخفض الأولوية قبل قبول التزام جديد.")
            )
        }

        val now=System.currentTimeMillis()
        val goalSignals=goals.map{ProgressAnalytics.goal(it,listGoalObservations(),now)}
        val projectItems=listProjectItems()
        val projectSignals=projects.map{ProgressAnalytics.project(it.id,listTodayTasks(true),listDependencies(),projectItems,now)}
        return DashboardSnapshot(
            executionScore = execution,
            loadScore = load,
            goalsOnTrack = goalSignals.count { it.health=="على المسار" && goals.any{g->g.id==it.goalId && g.status==GoalStatus.ACTIVE} },
            totalActiveGoals = activeGoals,
            projectsAtRisk = projectSignals.count{it.health in setOf("معرض للتأخير","متعطل")},
            recoveryScore = ProgressAnalytics.recovery(listDailyChecks().filter { java.time.LocalDate.parse(it.day)>=java.time.LocalDate.now().minusDays(6) }),
            areaScores = areaScores,
            availableHours = availableHours,
            committedHours = committedHours,
            insights = insights,
            pendingDecisions = readDecisions(),
            goalSignals = goalSignals, projectSignals = projectSignals, failures = failureCounts(now-30L*86400000),
            explanation = listOf(
                "التنفيذ = نسبة الإنجاز للمهام المسجلة، وليس تقييم جودة حياتك.",
                "التعافي = متوسط النوم مقارنة بـ8 ساعات والطاقة المبلغ عنها خلال 7 أيام؛ لا بيانات يعني لا درجة، وليس تشخيصاً طبياً.",
                "قوة إشارة أخطاء المدة درجة إرشادية من حجم العينة وتكرار الخطأ، وليست احتمالاً معايراً إحصائياً.",
                "توقع الهدف يحتاج قياسين يفصل بينهما يوم على الأقل؛ النموذج خطي مبسط قابل للتغير.",
                "المسار الحرج مجموع مدد الاعتماديات؛ المهام بلا مدة تعرض كبيانات ناقصة، ولا يفترض توفر الموارد بالتوازي.",
                "الحمل = المهام المجدولة أو المستحقة خلال 7 أيام، والمتأخرة، والاجتماعات، ÷ القدرة الأسبوعية. المهمة بلا مدة تقدر مؤقتاً بـ30 دقيقة؛ backlog غير المجدول لا يدخل.",
                "درجة الجانب مبنية حاليًا على تقدم أهداف SMART النشطة المرتبطة به، وليست رقمًا مزيفًا ثابتًا."
            )
        )
    }

    fun projectSummary(): Project? = listProjects().firstOrNull()
    fun goalSummary(): Goal? = listGoals().firstOrNull()

    private fun readDecisions(): List<ExecutiveDecision> {
        val c = db.readableDatabase.rawQuery(
            "SELECT id,title,reason,evidence,expected_effect,risk,status FROM decisions WHERE status='PENDING' ORDER BY created_at DESC", null
        )
        return c.use { cursor -> generateSequence { if (cursor.moveToNext()) cursor else null }.map {
            ExecutiveDecision(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), DecisionStatus.valueOf(it.getString(6)))
        }.toList() }
    }

    private fun recordEvent(type: String, entityType: String?, entityId: String?, payload: String?) {
        val values = ContentValues().apply {
            put("event_type", type); putNullable("entity_type", entityType); putNullable("entity_id", entityId); putNullable("payload", payload); put("created_at", System.currentTimeMillis())
        }
        db.writableDatabase.insert("behavior_events", null, values)
    }

    private fun scalarInt(sql: String, args: Array<String>? = null): Int = db.readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun rowToGoal(c: Cursor) = Goal(
        id=c.getString(0), title=c.getString(1), area=LifeArea.valueOf(c.getString(2)), specific=c.getString(3), metricName=c.getString(4),
        targetValue=c.getDoubleOrNull(5), currentValue=c.getDoubleOrNull(6), unit=c.getStringOrNull(7), deadlineEpochMillis=c.getLongOrNull(8),
        relevantReason=c.getString(9), achievableNote=c.getString(10), status=GoalStatus.valueOf(c.getString(11)), startedAt=c.getLongOrNull(12), horizon=GoalHorizon.valueOf(c.getString(13)), parentGoalId=c.getStringOrNull(14)
    )

    private fun rowToProject(c: Cursor) = Project(
        id=c.getString(0), title=c.getString(1), outcome=c.getString(2), goalId=c.getStringOrNull(3), status=ProjectStatus.valueOf(c.getString(4)),
        progress=c.getDouble(5), deadlineEpochMillis=c.getLongOrNull(6), loadScore=c.getInt(7), overrideReason=c.getStringOrNull(8)
    )

    private fun rowToTask(c: Cursor) = Task(
        id=c.getString(0), title=c.getString(1), projectId=c.getStringOrNull(2), status=TaskStatus.valueOf(c.getString(3)),
        scheduledEpochMillis=c.getLongOrNull(4), deadlineEpochMillis=c.getLongOrNull(5), estimatedMinutes=c.getIntOrNull(6),
        actualMinutes=c.getIntOrNull(7), definitionOfDone=c.getStringOrNull(8), priority=c.getInt(9), repeatDays=c.getIntOrNull(10), generatedFrom=c.getStringOrNull(11), parentId=c.getStringOrNull(12), failureCategory=c.getStringOrNull(13)?.let { FailureCategory.valueOf(it) }, failureReason=c.getStringOrNull(14)
    )

    private fun rowToCalendar(c: Cursor) = CalendarEntry(
        id=c.getString(0), title=c.getString(1), type=CalendarItemType.valueOf(c.getString(2)), startEpochMillis=c.getLong(3),
        endEpochMillis=c.getLongOrNull(4), allDay=c.getInt(5)==1, linkedEntityType=c.getStringOrNull(6), linkedEntityId=c.getStringOrNull(7), notes=c.getStringOrNull(8)
    )

    private fun rowToNote(c: Cursor) = Note(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))

    private fun rowToFocus(c: Cursor): FocusSession {
        val packages = c.getString(7).split('\u001F').filter { it.isNotBlank() }.toSet()
        return FocusSession(
            id=c.getString(0), taskId=c.getString(1), mode=FocusMode.valueOf(c.getString(2)), status=FocusStatus.valueOf(c.getString(3)),
            startedAt=c.getLong(4), endedAt=c.getLongOrNull(5), targetMinutes=c.getIntOrNull(6), blockedPackages=packages, exitReason=c.getStringOrNull(8)
        )
    }
}

private fun ContentValues.putNullable(key: String, value: String?) { if (value == null) putNull(key) else put(key, value) }
private fun ContentValues.putNullable(key: String, value: Long?) { if (value == null) putNull(key) else put(key, value) }
private fun ContentValues.putNullable(key: String, value: Int?) { if (value == null) putNull(key) else put(key, value) }
private fun ContentValues.putNullable(key: String, value: Double?) { if (value == null) putNull(key) else put(key, value) }
private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)
private fun Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
