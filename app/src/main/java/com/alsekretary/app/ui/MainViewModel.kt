package com.alsekretary.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.alsekretary.app.data.SecretaryDatabase
import com.alsekretary.app.data.SecretaryRepository
import com.alsekretary.app.data.StrictModeStore
import com.alsekretary.app.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val RANGE_PAST_DAYS = 45L
private const val RANGE_FUTURE_DAYS = 120L

data class MainUiState(
    val blockedDomains: Set<String> = emptySet(),
    val social: com.alsekretary.app.social.SocialState = com.alsekretary.app.social.SocialState(),
    val socialSearch: List<org.json.JSONObject> = emptyList(),
    val projectItems: List<ProjectItem> = emptyList(),
    val dailyChecks: List<DailyCheck> = emptyList(),
    val suggestions: List<ReviewSuggestion> = emptyList(),
    val remindersEnabled: Boolean = false,
    val dependencies: List<TaskDependency> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val habitChecks: List<HabitCheck> = emptyList(),
    val reviews: List<WeeklyReview> = emptyList(),
    val capacity: Double = 40.0,
    val tasks: List<Task> = emptyList(),
    val dashboard: DashboardSnapshot? = null,
    val projects: List<Project> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val calendarEntries: List<CalendarEntry> = emptyList(),
    val notes: List<Note> = emptyList(),
    val activeFocus: FocusSession? = null,
    val launchableApps: List<AppCandidate> = emptyList()
) {
    val planningEdges: List<TaskDependency> get() = (dependencies+tasks.filter{it.parentId!=null}.map{TaskDependency(it.parentId!!,it.id)}).distinct()
    val project: Project? get() = projects.firstOrNull()
    val goal: Goal? get() = goals.firstOrNull()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SecretaryDatabase(application)
    private val repo = SecretaryRepository(database)
    private val socialSync = com.alsekretary.app.social.SocialSync(application,database)
    private val reminderScheduler = com.alsekretary.app.reminders.ReminderScheduler(application)
    private val strictStore = StrictModeStore(application)
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val operationMutex = Mutex()
    private val _notice=MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun clearNotice(){_notice.value=null}
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }

    private fun operation(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            operationMutex.withLock {
                try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { _error.value = e.message ?: "تعذر حفظ التغيير" }
            }
        }
    }

    init { refresh(); operation { socialSync.schedule();runCatching{socialSync.sync()};refreshNow() } }

    fun refresh() = operation { refreshNow() }

    private fun refreshNow() {
        val active=repo.activeFocus()
        strictStore.active=active?.mode==FocusMode.STRICT
        strictStore.activeFocusId=active?.id
        if(active!=null && active.mode==FocusMode.STRICT)strictStore.blockedPackages=active.blockedPackages
        val now = System.currentTimeMillis()
        val from = now - RANGE_PAST_DAYS * 86_400_000L
        val to = now + RANGE_FUTURE_DAYS * 86_400_000L
        reminderScheduler.sync(repo)
        _state.value = MainUiState(
            blockedDomains = strictStore.blockedDomains,
            social = socialSync.state(), socialSearch = _state.value.socialSearch,
            projectItems = repo.listProjectItems(), dailyChecks = repo.listDailyChecks(),
            suggestions = ProgressAnalytics.suggestions(repo.listTodayTasks(),repo.failureCounts(now-30L*86400000)),
            remindersEnabled = reminderScheduler.enabled(),
            dependencies = repo.listDependencies(), milestones = repo.listMilestones(),
            habits = repo.listHabits(), habitChecks = repo.listHabitChecks(), reviews = repo.listReviews(), capacity = repo.capacityHours(),
            tasks = repo.listTodayTasks(true),
            dashboard = repo.dashboardSnapshot(),
            projects = repo.listProjects(),
            goals = repo.listGoals(),
            calendarEntries = repo.listCalendarEntries(from, to),
            notes = repo.listNotes(),
            activeFocus = repo.activeFocus(),
            launchableApps = queryLaunchableApps()
        )
    }

    fun setBlockedDomains(text: String) = operation {
        val domains=text.lines().map{it.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').trimEnd('.')}.filter{it.isNotBlank()}.toSet()
        require(domains.all{it.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) && it.contains('.')}){"اكتب أسماء نطاقات فقط، كل موقع في سطر"}
        strictStore.blockedDomains=domains;refreshNow()
    }
    fun exportBackup(uri: android.net.Uri,password: String) = operation { val chars=password.toCharArray();try{com.alsekretary.app.data.BackupManager(getApplication(),database).export(uri,chars);_notice.value="تم تصدير النسخة المشفرة"}finally{chars.fill('\u0000')} }
    fun restoreBackup(uri: android.net.Uri,password: String) = operation { val chars=password.toCharArray();try{com.alsekretary.app.data.BackupManager(getApplication(),database).restore(uri,chars);refreshNow();_notice.value="تمت الاستعادة"}finally{chars.fill('\u0000')} }
    fun socialLogin(url: String,username: String,password: String,name: String,register: Boolean) = operation { try{socialSync.login(url,username,password,name,register)}finally{refreshNow()} }
    fun socialLogout() = operation { socialSync.logout();_state.value=_state.value.copy(socialSearch=emptyList());refreshNow() }
    fun searchSocial(name: String) = operation { _state.value=_state.value.copy(socialSearch=socialSync.searchUser(name).objects()) }
    fun socialAction(path: String,body: org.json.JSONObject,method: String="POST") = operation { socialSync.enqueue(path,body,method);refreshNow();try{socialSync.sync()}finally{refreshNow()} }
    fun syncSocial() = operation { try{socialSync.sync()}finally{refreshNow()} }
    fun discardSocial(id: String) = operation { socialSync.discard(id);refreshNow() }
    fun setReminders(enabled: Boolean) = operation { reminderScheduler.setEnabled(enabled); refreshNow() }

    fun quickCapture(text: String) = operation {
        if (text.isBlank()) return@operation
        repo.addQuickTask(text.trim())
        refreshNow()
    }

    fun saveTask(task: Task, prerequisites: Set<String>) = operation { repo.saveTask(task,prerequisites); refreshNow() }
    fun saveProjectItem(item: ProjectItem) = operation { repo.saveProjectItem(item); refreshNow() }
    fun deleteProjectItem(id: String) = operation { repo.deleteProjectItem(id); refreshNow() }
    fun saveDailyCheck(check: DailyCheck) = operation { repo.saveDailyCheck(check); refreshNow() }
    fun resolveDecision(id: String,status: DecisionStatus) = operation { repo.resolveDecision(id,status);refreshNow() }
    fun saveMilestone(m: Milestone) = operation { repo.saveMilestone(m); refreshNow() }
    fun deleteMilestone(id: String) = operation { repo.deleteMilestone(id); refreshNow() }
    fun saveHabit(habit: Habit) = operation { repo.saveHabit(habit); refreshNow() }
    fun toggleHabit(id: String, day: String) = operation { repo.toggleHabit(id,day); refreshNow() }
    fun saveReview(review: WeeklyReview) = operation { repo.saveReview(review); refreshNow() }
    fun setCapacity(hours: Double) = operation { repo.setCapacity(hours); refreshNow() }

    fun markDone(id: String) = operation { repo.markDone(id); refreshNow() }

    fun saveGoal(goal: Goal) = operation { repo.saveGoal(goal); refreshNow() }
    fun cancelGoal(id: String) = operation { repo.cancelGoal(id); refreshNow() }

    fun saveProject(project: Project, classifier: ClassificationResult) = operation { repo.saveProject(project, classifier); refreshNow() }
    fun archiveProject(id: String) = operation { repo.archiveProject(id); refreshNow() }

    fun saveCalendar(entry: CalendarEntry) = operation { repo.saveCalendarEntry(entry); refreshNow() }
    fun deleteCalendar(id: String) = operation { repo.deleteCalendarEntry(id); refreshNow() }

    fun saveNote(note: Note) = operation { repo.saveNote(note); refreshNow() }
    fun deleteNote(id: String) = operation { repo.deleteNote(id); refreshNow() }

    fun startFocus(taskId: String, mode: FocusMode, targetMinutes: Int?, blockedPackages: Set<String>) = operation {
        val session = repo.startFocus(taskId, mode, targetMinutes, blockedPackages)
        if (session.mode == FocusMode.STRICT) {
            strictStore.blockedPackages = session.blockedPackages
            strictStore.activeFocusId = session.id
            strictStore.active = true
            if(strictStore.blockedDomains.isNotEmpty()) androidx.core.content.ContextCompat.startForegroundService(getApplication(),Intent(getApplication(),com.alsekretary.app.strict.FocusDnsVpnService::class.java))
        }
        refreshNow()
    }

    fun finishFocus(status: FocusStatus, reason: String? = null) = operation {
        val session = _state.value.activeFocus ?: return@operation
        repo.finishFocus(session.id, status, reason)
        strictStore.active = false
        getApplication<Application>().stopService(Intent(getApplication(),com.alsekretary.app.strict.FocusDnsVpnService::class.java))
        strictStore.activeFocusId = null
        refreshNow()
    }

    private fun queryLaunchableApps(): List<AppCandidate> {
        val pm = getApplication<Application>().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val self = getApplication<Application>().packageName
        return apps.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == self) return@mapNotNull null
            AppCandidate(info.loadLabel(pm).toString(), pkg)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
}
