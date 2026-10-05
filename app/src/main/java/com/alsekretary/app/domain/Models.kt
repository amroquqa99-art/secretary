package com.alsekretary.app.domain

enum class LifeArea(val arabicName: String) {
    PHYSICAL("البدني"),
    MENTAL("العقلي"),
    EMOTIONAL_SOCIAL("العاطفي / الاجتماعي"),
    SPIRITUAL("الروحي")
}

enum class GoalStatus { DRAFT, ACTIVE, PAUSED, COMPLETED, CANCELLED }
enum class ProjectStatus { IDEA, PLANNED, ACTIVE, WAITING, BLOCKED, PAUSED, AT_RISK, COMPLETED, CANCELLED, ARCHIVED }
enum class TaskStatus { INBOX, PLANNED, ACTIVE, BLOCKED, DONE, DROPPED }
enum class DecisionStatus { PENDING, ACCEPTED, MODIFIED, REJECTED }
enum class CalendarItemType { EVENT, MEETING, TASK, HABIT, MILESTONE, FOCUS }
enum class FocusMode { NORMAL, TIMEBOX, STRICT }
enum class FocusStatus { RUNNING, COMPLETED, BLOCKED, EMERGENCY_EXIT, CANCELLED }

data class Goal(
    val id: String,
    val title: String,
    val area: LifeArea,
    val specific: String,
    val metricName: String,
    val targetValue: Double?,
    val currentValue: Double?,
    val unit: String?,
    val deadlineEpochMillis: Long?,
    val relevantReason: String,
    val achievableNote: String,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val startedAt: Long? = null
)

data class Project(
    val id: String,
    val title: String,
    val outcome: String,
    val goalId: String?,
    val status: ProjectStatus,
    val progress: Double,
    val deadlineEpochMillis: Long?,
    val loadScore: Int = 0,
    val overrideReason: String? = null
)

data class Task(
    val id: String,
    val title: String,
    val projectId: String?,
    val status: TaskStatus,
    val scheduledEpochMillis: Long?,
    val deadlineEpochMillis: Long?,
    val estimatedMinutes: Int?,
    val actualMinutes: Int?,
    val definitionOfDone: String?,
    val priority: Int = 0,
    val repeatDays: Int? = null,
    val generatedFrom: String? = null,
    val parentId: String? = null,
    val failureCategory: FailureCategory? = null,
    val failureReason: String? = null
)

data class CalendarEntry(
    val id: String,
    val title: String,
    val type: CalendarItemType,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val allDay: Boolean = false,
    val linkedEntityType: String? = null,
    val linkedEntityId: String? = null,
    val notes: String? = null
)

data class Note(
    val id: String,
    val title: String,
    val markdown: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class FocusSession(
    val id: String,
    val taskId: String,
    val mode: FocusMode,
    val status: FocusStatus,
    val startedAt: Long,
    val endedAt: Long?,
    val targetMinutes: Int?,
    val blockedPackages: Set<String>,
    val exitReason: String? = null
)

data class AppCandidate(val label: String, val packageName: String)

data class BehaviorInsight(
    val id: String,
    val title: String,
    val evidence: String,
    val confidence: Double,
    val suggestedAction: String
)

data class ExecutiveDecision(
    val id: String,
    val title: String,
    val reason: String,
    val evidence: String,
    val expectedEffect: String,
    val risk: String,
    val status: DecisionStatus = DecisionStatus.PENDING
)

data class DashboardSnapshot(
    val executionScore: Int,
    val loadScore: Int,
    val goalsOnTrack: Int,
    val totalActiveGoals: Int,
    val projectsAtRisk: Int,
    val recoveryScore: Int?,
    val areaScores: Map<LifeArea, Int?>,
    val availableHours: Double,
    val committedHours: Double,
    val insights: List<BehaviorInsight>,
    val pendingDecisions: List<ExecutiveDecision>,
    val explanation: List<String> = emptyList(),
    val goalSignals: List<GoalSignal> = emptyList(),
    val projectSignals: List<ProjectSignal> = emptyList(),
    val failures: Map<FailureCategory,Int> = emptyMap()
)


data class Habit(val id: String, val title: String, val area: LifeArea, val targetPerWeek: Int = 7, val archived: Boolean = false)
data class HabitCheck(val habitId: String, val day: String)
data class WeeklyReview(val id: String, val week: String, val wins: String, val blockers: String, val nextAction: String, val energy: Int)


data class TaskDependency(val taskId: String, val prerequisiteId: String)
data class Milestone(val id: String, val projectId: String, val title: String, val dueAt: Long?, val completed: Boolean = false)


enum class FailureCategory(val label: String) {
    UNDERESTIMATION("تقدير مدة غير كافٍ"), AMBIGUITY("مهمة غامضة"), EXTERNAL("عائق خارجي"), OVERLOAD("التزامات زائدة"), DISTRACTION("تشتت"), OTHER("غير ذلك")
}
enum class WorkKind(val label: String) { PERSON("شخص"), MEETING("اجتماع"), DECISION("قرار"), RISK("خطر"), BLOCKER("عائق"), FILE("ملف") }
data class ProjectItem(val id: String, val projectId: String, val kind: WorkKind, val title: String, val detail: String, val resolved: Boolean = false, val uri: String? = null)
data class GoalObservation(val goalId: String, val at: Long, val fraction: Double)
data class GoalSignal(val goalId: String, val health: String, val velocityPerWeek: Double?, val requiredPerWeek: Double?, val forecastAt: Long?, val samples: Int)
data class ProjectSignal(val projectId: String, val completed: Int, val total: Int, val criticalPath: List<String>, val criticalMinutes: Int, val unknownDurations: Int, val blocked: Int, val overdue: Int, val pendingDecisions: Int, val health: String)
data class DailyCheck(val day: String, val sleepHours: Double?, val energy: Int?, val wins: String, val obstacles: String, val nextAction: String)
data class ReviewSuggestion(val title: String, val evidence: String, val action: String)
