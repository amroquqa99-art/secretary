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
import com.alsekretary.app.localmodel.*
import java.util.concurrent.atomic.AtomicBoolean

private const val RANGE_PAST_DAYS = 45L
private const val RANGE_FUTURE_DAYS = 120L

data class MainUiState(
    val assistantMessages: List<AssistantMessage> = emptyList(),
    val assistantProposals: List<AssistantProposal> = emptyList(),
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
    val goalHierarchy: List<Goal> = emptyList(),
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
    private val assistantStore = com.alsekretary.app.data.AssistantStore(database)
    private val modelStore=ModelStore(application)
    private val modelRunner=ModelRunner(application,modelStore)
    private val embeddingStore=EmbeddingModelStore(application)
    private val embeddingRunner=EmbeddingRunner(application,embeddingStore)
    private val semanticMemory=SemanticMemory(application,embeddingStore,embeddingRunner)
    private val aiBusy=AtomicBoolean(false)
    private val modelBusy=AtomicBoolean(false)
    private val semanticBusy=AtomicBoolean(false)
    private val modelLock=Any()
    private val semanticLock=Any()
    private var modelToken: AtomicBoolean?=null
    private var semanticToken: AtomicBoolean?=null
    private val _model=MutableStateFlow(ModelUiState(installed=modelStore.selected(),enabled=modelStore.enabled))
    val model: StateFlow<ModelUiState> = _model.asStateFlow()
    private val _semantic=MutableStateFlow(SemanticMemoryUiState(installed=embeddingStore.selected(),enabled=embeddingStore.enabled))
    val semantic: StateFlow<SemanticMemoryUiState> = _semantic.asStateFlow()
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
            assistantMessages = assistantStore.messages(), assistantProposals = assistantStore.proposals(),
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
            goals = repo.listGoals(), goalHierarchy = repo.listGoals(true),
            calendarEntries = repo.listCalendarEntries(from, to),
            notes = repo.listNotes(),
            activeFocus = repo.activeFocus(),
            launchableApps = queryLaunchableApps()
        )
        val memoryStats=semanticMemory.stats(_state.value.tasks,_state.value.goals,_state.value.notes,_state.value.assistantMessages)
        _semantic.value=_semantic.value.copy(
            installed=embeddingStore.selected(),
            enabled=embeddingStore.enabled,
            indexedDocuments=memoryStats.indexed,
            totalDocuments=memoryStats.documents
        )
    }

    private fun modelOperation(label: String,block: suspend (AtomicBoolean)->String) {
        if(!aiBusy.compareAndSet(false,true)){_notice.value="انتظر انتهاء عملية الذكاء المحلي أو أوقفها";return}
        modelBusy.set(true)
        val token=AtomicBoolean(false)
        synchronized(modelLock){modelToken=token}
        _model.value=_model.value.copy(busy=true,progress=null,status=label)
        viewModelScope.launch(Dispatchers.IO) {
            var result="توقفت العملية"
            try { result=block(token) }
            catch(e: java.util.concurrent.CancellationException){result="توقفت العملية؛ لم يحفظ رد أو اقتراح جديد"}
            catch(e: Exception){result=e.message ?: "تعذر تشغيل النموذج";_error.value=result}
            catch(e: LinkageError){result="محرك النموذج غير مدعوم على هذا الجهاز";_error.value=result}
            finally {
                synchronized(modelLock){if(modelToken===token)modelToken=null}
                _model.value=ModelUiState(modelStore.selected(),modelStore.enabled,false,null,result)
                modelBusy.set(false)
                aiBusy.set(false)
            }
        }
    }
    fun cancelModel() {
        synchronized(modelLock){modelToken?.set(true)}
        if(modelBusy.get()) {
            _model.value=_model.value.copy(status="جارٍ الإيقاف؛ تحميل المحرك قد يحتاج وقتاً لينتهي")
            viewModelScope.launch(Dispatchers.IO){modelRunner.cancel();embeddingRunner.cancel()}
        }
    }
    fun modelDownload()=modelOperation("تنزيل النموذج؛ تبقى الشاشة مفتوحة") { token ->
        modelStore.download(token) { bytes -> _model.value=_model.value.copy(progress=bytes) }
        "اكتمل التنزيل والتحقق. فعّل المحادثة المحلية إن أردت تجربتها."
    }
    fun modelImport(uri: android.net.Uri)=modelOperation("نسخ النموذج والتحقق منه") { token ->
        modelStore.importUri(getApplication(),uri,token)
        "تم الاستيراد. توافق الملف لا يتأكد إلا عند التشغيل."
    }
    fun modelEnable(enabled: Boolean) {
        if(aiBusy.get()){_notice.value="أوقف عملية الذكاء المحلي الحالية أولاً";return}
        operation {
            if(enabled){require(modelStore.selected()!=null) { "نزّل النموذج أو استورده أولاً" };modelRunner.checkResources()}
            modelStore.enabled=enabled
            _model.value=_model.value.copy(enabled=enabled,status=if(enabled)"الحوار المحلي التجريبي مفعّل" else "الأوامر المكتوبة مفعّلة؛ النموذج متوقف")
        }
    }
    fun modelRemove()=modelOperation("إزالة ملفات النماذج") { _ -> modelStore.remove();"أزيلت ملفات النماذج؛ بيانات حياتك محفوظة" }

    private fun semanticOperation(label: String,block: suspend (AtomicBoolean)->String) {
        if(!aiBusy.compareAndSet(false,true)){_notice.value="انتظر انتهاء عملية الذكاء المحلي أو أوقفها";return}
        semanticBusy.set(true)
        val token=AtomicBoolean(false)
        synchronized(semanticLock){semanticToken=token}
        _semantic.value=_semantic.value.copy(busy=true,progress=null,status=label)
        viewModelScope.launch(Dispatchers.IO) {
            var result="توقفت العملية"
            try { result=block(token) }
            catch(e: java.util.concurrent.CancellationException){result="توقفت عملية الذاكرة؛ لم تتغير بيانات حياتك"}
            catch(e: Exception){result=e.message ?: "تعذر تشغيل الذاكرة الدلالية";_error.value=result}
            catch(e: LinkageError){result="محرك الذاكرة غير مدعوم على هذا الجهاز";_error.value=result}
            finally {
                synchronized(semanticLock){if(semanticToken===token)semanticToken=null}
                val s=_state.value
                val stats=semanticMemory.stats(s.tasks,s.goals,s.notes,s.assistantMessages)
                _semantic.value=SemanticMemoryUiState(
                    installed=embeddingStore.selected(),enabled=embeddingStore.enabled,busy=false,progress=null,
                    indexedDocuments=stats.indexed,totalDocuments=stats.documents,status=result
                )
                semanticBusy.set(false)
                aiBusy.set(false)
            }
        }
    }
    fun cancelSemanticMemory() {
        synchronized(semanticLock){semanticToken?.set(true)}
        if(semanticBusy.get()) {
            _semantic.value=_semantic.value.copy(status="جارٍ إيقاف عملية الذاكرة")
            viewModelScope.launch(Dispatchers.IO){embeddingRunner.cancel()}
        }
    }
    fun semanticDownload()=semanticOperation("تنزيل نموذج الذاكرة الدلالية؛ تبقى الشاشة مفتوحة") { token ->
        embeddingStore.download(token) { bytes -> _semantic.value=_semantic.value.copy(progress=bytes) }
        "اكتمل تنزيل نموذج الذاكرة والتحقق من بصمته. فعّله ثم ابنِ الفهرس."
    }
    fun semanticImport(uri: android.net.Uri)=semanticOperation("نسخ نموذج الذاكرة والتحقق منه") { token ->
        embeddingStore.importUri(getApplication(),uri,token)
        "تم استيراد نموذج الذاكرة. توافقه يتأكد عند بناء الفهرس."
    }
    fun semanticEnable(enabled: Boolean) {
        if(aiBusy.get()){_notice.value="أوقف عملية الذكاء المحلي الحالية أولاً";return}
        operation {
            if(enabled){require(embeddingStore.selected()!=null){"نزّل نموذج الذاكرة أو استورده أولاً"};embeddingRunner.checkResources()}
            embeddingStore.enabled=enabled
            _semantic.value=_semantic.value.copy(
                installed=embeddingStore.selected(),enabled=enabled,
                status=if(enabled)"الذاكرة الدلالية مفعّلة؛ ابنِ الفهرس بعد تغييرات كبيرة" else "الذاكرة الدلالية متوقفة؛ سيستخدم السكرتير الاسترجاع النصي"
            )
        }
    }
    fun semanticRebuild()=semanticOperation("بناء فهرس الذاكرة الدلالية محلياً") { token ->
        var tasks: List<Task> = emptyList()
        var goals: List<Goal> = emptyList()
        var notes: List<Note> = emptyList()
        var messages: List<AssistantMessage> = emptyList()
        operationMutex.withLock {
            tasks=repo.listTodayTasks(true)
            goals=repo.listGoals()
            notes=repo.listNotes()
            messages=assistantStore.messages()
        }
        val stats=semanticMemory.rebuild(tasks,goals,notes,messages,token) { done,total ->
            _semantic.value=_semantic.value.copy(status="فهرسة الذاكرة: $done / $total")
        }
        "اكتمل بناء ${stats.indexed} من ${stats.documents} عنصر ذاكرة محلياً."
    }
    fun semanticRemove()=semanticOperation("إزالة نموذج الذاكرة والفهرس المشتق") { _ ->
        embeddingStore.remove();semanticMemory.clear()
        "أزيل نموذج الذاكرة والفهرس المشتق؛ بياناتك الأصلية لم تُحذف."
    }
    fun assistantSubmit(text: String,budget: Int)=modelOperation("جارٍ إعداد الرد") { token ->
        var snapshot: List<Task> = emptyList()
        var goals: List<Goal> = emptyList()
        var notes: List<Note> = emptyList()
        var messages: List<AssistantMessage> = emptyList()
        val selected=operationMutex.withLock {
            val reply=assistantStore.evaluate(text,budget)
            if(reply.handled || !modelStore.enabled) {
                synchronized(modelLock){if(token.get())throw java.util.concurrent.CancellationException();assistantStore.rememberReply(text,reply)}
                refreshNow();null
            } else {
                val weights=requireNotNull(modelStore.selected()) { "النموذج غير موجود؛ أعد تنزيله" }
                snapshot=repo.listTodayTasks(true)
                goals=repo.listGoals()
                notes=repo.listNotes()
                messages=assistantStore.messages()
                weights
            }
        }
        if(selected==null) "اكتمل الرد بالأوامر المحلية" else {
            var semantic: RetrievedMemory?=null
            if(embeddingStore.enabled && _semantic.value.indexedDocuments>0) {
                _model.value=_model.value.copy(status="يبحث محلياً في الذاكرة الدلالية")
                try { semantic=semanticMemory.retrieve(text,snapshot,goals,notes,messages,token) }
                catch(e: java.util.concurrent.CancellationException){throw e}
                catch(_: Exception) { semantic=null }
            }
            val prompt=ModelPrompt.build(text,snapshot,goals,notes,messages,semantic)
            _model.value=_model.value.copy(status="النموذج يولد الرد محلياً؛ يمكنك إيقافه")
            val generated=modelRunner.run(prompt,selected,token,ModelPrompt.requestsAction(text),text)
            operationMutex.withLock {
                synchronized(modelLock) {
                    if(token.get() || !modelStore.enabled || modelStore.selected()?.sha!=selected.sha)throw java.util.concurrent.CancellationException()
                    assistantStore.rememberReply(text,ModelProposals.review(generated.answer,snapshot,ModelPrompt.requestsAction(text)))
                }
                refreshNow()
            }
            "اكتمل الرد في ${generated.elapsedMs/1000} ث؛ ذاكرة العملية المقاسة ${generated.processPssKb/1024} ميغابايت (ليست ذروة الاستهلاك)"
        }
    }
    fun assistantConfirm(id: String) = operation { assistantStore.confirm(id);refreshNow() }
    fun assistantCancel(id: String) = operation { assistantStore.cancel(id);refreshNow() }
    fun assistantClear() { cancelModel();operation { assistantStore.clear();refreshNow() } }

    fun setBlockedDomains(text: String) = operation {
        val domains=text.lines().map{it.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').trimEnd('.')}.filter{it.isNotBlank()}.toSet()
        require(domains.all{it.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) && it.contains('.')}){"اكتب أسماء نطاقات فقط، كل موقع في سطر"}
        strictStore.blockedDomains=domains;refreshNow()
    }
    fun exportBackup(uri: android.net.Uri,password: String) = operation { val chars=password.toCharArray();try{com.alsekretary.app.data.BackupManager(getApplication(),database).export(uri,chars);_notice.value="تم تصدير النسخة المشفرة"}finally{chars.fill('\u0000')} }
    fun restoreBackup(uri: android.net.Uri,password: String) { cancelModel();cancelSemanticMemory();operation { val chars=password.toCharArray();try{com.alsekretary.app.data.BackupManager(getApplication(),database).restore(uri,chars);semanticMemory.clear();refreshNow();_notice.value="تمت الاستعادة؛ أُفرغ فهرس الذاكرة المشتق ويحتاج إعادة بناء"}finally{chars.fill('\u0000')} } }
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

    override fun onCleared() {
        synchronized(modelLock){modelToken?.set(true)}
        synchronized(semanticLock){semanticToken?.set(true)}
        modelRunner.cancel();embeddingRunner.cancel();super.onCleared()
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
