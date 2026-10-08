package com.alsekretary.app.ui

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alsekretary.app.domain.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Composable
fun LifeManagementScreen(state: MainUiState, vm: MainViewModel) {
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> vm.setReminders(allowed) }
    val context=LocalContext.current
    var attachmentProject by remember { mutableStateOf<String?>(null) }
    val attachmentPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val project=attachmentProject
        if(uri!=null && project!=null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.saveProjectItem(ProjectItem(UUID.randomUUID().toString(),project,WorkKind.FILE,uri.lastPathSegment ?: "ملف مرفق","",uri=uri.toString()))
        }
    }
    var itemEditor by remember { mutableStateOf<ProjectItem?>(null) }
    var tab by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf<TaskStatus?>(null) }
    var query by remember { mutableStateOf("") }
    var projectId by remember { mutableStateOf<String?>(null) }
    var taskEditor by remember { mutableStateOf<Task?>(null) }
    var milestoneEditor by remember { mutableStateOf<Milestone?>(null) }
    var habitEditor by remember { mutableStateOf<Habit?>(null) }
    var habitDay by remember { mutableStateOf(LocalDate.now().toString()) }
    val today=runCatching{LocalDate.parse(habitDay)}.getOrDefault(LocalDate.now())
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("متابعة التنفيذ",style=MaterialTheme.typography.headlineMedium) }
        item { Row {
            Switch(state.remindersEnabled,{enabled ->
                if(enabled && Build.VERSION.SDK_INT>=33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.setReminders(enabled)
            })
            Text("تذكير قبل الموعد بعشر دقائق؛ قد يؤخره النظام")
        } }
        item { Row { listOf("مهام","عادات","مشاريع","مراجعة").forEachIndexed { i,title -> TextButton(onClick={tab=i}) { Text(if(tab==i) "• $title" else title) } } } }
        when(tab) {
            0 -> {
                item { Button(onClick={taskEditor=Task(UUID.randomUUID().toString(),"",projectId,TaskStatus.INBOX,null,null,null,null,null)}) { Text("مهمة جديدة") } }
                item { OutlinedTextField(query,{query=it},label={Text("بحث")},modifier=Modifier.fillMaxWidth()) }
                item { EnumChooser("الحالة",filter?.name ?: "الكل",listOf("الكل")+TaskStatus.entries.map{it.name}) { filter=TaskStatus.entries.firstOrNull{v->v.name==it} } }
                items(state.tasks.filter { (filter==null || it.status==filter) && it.title.contains(query,true) },key={it.id}) { t ->
                    GlassCard {
                        Text(t.title,style=MaterialTheme.typography.titleMedium)
                        Text("${humanLabel(t.status.name)} • الأولوية ${t.priority} • ${t.estimatedMinutes ?: 0} دقيقة")
                        if(t.repeatDays!=null) Text("يتكرر كل ${t.repeatDays} يوم بعد الإنجاز")
                        val unmet=TaskPlanning.unmet(t.id,state.tasks,state.planningEdges)
                        if(unmet.isNotEmpty()) Text("ينتظر: " + state.tasks.filter{it.id in unmet}.joinToString{it.title},color=MaterialTheme.colorScheme.error)
                        t.definitionOfDone?.let { Text("ينتهي عندما: $it") }
                        Row {
                            TextButton(onClick={taskEditor=Task(UUID.randomUUID().toString(),"",t.projectId,TaskStatus.INBOX,null,null,null,null,null,parentId=t.id)}) { Text("مهمة فرعية") }
                            TextButton(onClick={taskEditor=t}) { Text("تعديل") }; if(t.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED)) TextButton(onClick={vm.markDone(t.id)},enabled=TaskPlanning.unmet(t.id,state.tasks,state.planningEdges).isEmpty()) { Text("إنجاز") } }
                    }
                }
            }
            1 -> {
                item { Text("سجل يومي محلي؛ الهدف عدد الأيام في الأسبوع.");OutlinedTextField(habitDay,{habitDay=it},label={Text("اليوم YYYY-MM-DD")}) }
                item { Button(onClick={habitEditor=Habit(UUID.randomUUID().toString(),"",LifeArea.PHYSICAL)}) { Text("عادة جديدة") } }
                items(state.habits,key={it.id}) { h ->
                    val start=today.minusDays((today.dayOfWeek.value-1).toLong())
                    val count=state.habitChecks.count { it.habitId==h.id && LocalDate.parse(it.day) in start..today }
                    val checked=state.habitChecks.any{it.habitId==h.id && it.day==today.toString()}
                    val validDay=runCatching{LocalDate.parse(habitDay)<=LocalDate.now()}.getOrDefault(false)
                    val history=state.habitChecks.filter{it.habitId==h.id}.map{LocalDate.parse(it.day)}.sortedDescending()
                    var streak=0; var day=LocalDate.now()
                    if(day !in history) day=day.minusDays(1)
                    while(day in history){streak++;day=day.minusDays(1)}
                    GlassCard {
                        Text(h.title,style=MaterialTheme.typography.titleMedium)
                        Text("${h.area.arabicName} • هذا الأسبوع $count / ${h.targetPerWeek}")
                        Text("استمرار $streak يوم • آخر 28 يوماً ${history.count{it>=LocalDate.now().minusDays(27)}} يوم")
                        Text(history.take(14).joinToString("، "),style=MaterialTheme.typography.bodySmall)
                        Row { TextButton(onClick={vm.toggleHabit(h.id,today.toString())},enabled=validDay) { Text(if(checked) "إلغاء تسجيل اليوم" else "سجل اليوم") }; TextButton(onClick={habitEditor=h}) { Text("تعديل") } }
                    }
                }
            }
            2 -> {
                item { EnumChooser("المشروع",state.projects.firstOrNull{it.id==projectId}?.title ?: "اختر مشروعًا",state.projects.map{it.title}) { title -> projectId=state.projects.first{it.title==title}.id } }
                val p=state.projects.firstOrNull{it.id==projectId}
                if(p!=null) {
                    val tasks=state.tasks.filter{it.projectId==p.id}
                    val included=tasks.filter{it.status!=TaskStatus.DROPPED}
                    val done=included.count{it.status==TaskStatus.DONE}
                    item { GlassCard { Text(p.title,style=MaterialTheme.typography.titleLarge);Text(p.outcome);Text("مهام مكتملة $done / ${included.size} • عوائق ${tasks.count{it.status==TaskStatus.BLOCKED}}")
                        Text("المتبقي المقدر: ${included.filter{it.status!=TaskStatus.DONE}.sumOf{it.estimatedMinutes?:0}} دقيقة")
                        if(included.isNotEmpty()) LinearProgressIndicator(progress={done.toFloat()/included.size},modifier=Modifier.fillMaxWidth())
                        Button(onClick={taskEditor=Task(UUID.randomUUID().toString(),"",p.id,TaskStatus.PLANNED,null,null,null,null,null)}) { Text("أضف مهمة للمشروع") }
                    } }
                    item {
                        val signal=state.dashboard?.projectSignals?.firstOrNull{it.projectId==p.id}
                        if(signal!=null) GlassCard {
                            Text("الحالة: ${signal.health} • متأخر ${signal.overdue} • قرارات ${signal.pendingDecisions}")
                            Text("المسار الحرج: "+signal.criticalPath.mapNotNull{id->state.tasks.firstOrNull{it.id==id}?.title}.joinToString(" ← "))
                            Text("الحد الأدنى المقدر ${signal.criticalMinutes} دقيقة • بلا تقدير ${signal.unknownDurations} مهام")
                        }
                    }
                    item { Row { Button(onClick={itemEditor=ProjectItem(UUID.randomUUID().toString(),p.id,WorkKind.BLOCKER,"","" )}){Text("عنصر مشروع")};TextButton(onClick={attachmentProject=p.id;attachmentPicker.launch(arrayOf("*/*"))}){Text("أرفق ملفاً")} } }
                    items(state.projectItems.filter{it.projectId==p.id},key={"item:"+it.id}) { work -> GlassCard {
                        Text("${work.kind.label}: ${work.title}",style=MaterialTheme.typography.titleMedium);Text(work.detail)
                        Text(if(work.resolved) "مغلق" else "مفتوح")
                        Row { TextButton(onClick={itemEditor=work}){Text("تعديل")};TextButton(onClick={vm.saveProjectItem(work.copy(resolved=!work.resolved))}){Text(if(work.resolved)"إعادة فتح" else "إغلاق")}
                            work.uri?.let { uri -> TextButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}}){Text("فتح الملف")} }
                        }
                    } }
                    item { Button(onClick={milestoneEditor=Milestone(UUID.randomUUID().toString(),p.id,"",null)}) { Text("مرحلة جديدة") } }
                    items(state.milestones.filter{it.projectId==p.id},key={"m:"+it.id}) { m -> GlassCard {
                        Text(m.title,style=MaterialTheme.typography.titleMedium)
                        Text(if(m.completed) "مكتملة" else "الموعد: ${formatTaskDateTime(m.dueAt).ifBlank { "غير محدد" }}")
                        Row { TextButton(onClick={vm.saveMilestone(m.copy(completed=!m.completed))}) { Text(if(m.completed) "إعادة فتح" else "إنجاز") };TextButton(onClick={milestoneEditor=m}) { Text("تعديل") } }
                    } }
                    items(tasks,key={it.id}) { t -> GlassCard { Text(t.title);Text(humanLabel(t.status.name));TextButton(onClick={taskEditor=t}) { Text("فتح المهمة") } } }
                } else item { Text("اختر مشروعًا لعرض مهامه وعوائقه وحمل العمل.") }
            }
            3 -> {
                item { GlassCard {
                    Text("التعافي من التعثر",style=MaterialTheme.typography.titleLarge)
                    val blocked=state.tasks.filter{it.status==TaskStatus.BLOCKED}
                    if(blocked.isEmpty())Text("لا توجد مهام متعطلة")
                    blocked.forEach { task ->
                        Text(task.title);Text(task.failureReason.orEmpty())
                        Row{TextButton(onClick={taskEditor=task}){Text("راجع المهمة والعائق")};TextButton(onClick={vm.saveTask(task.copy(status=TaskStatus.PLANNED),state.dependencies.filter{it.taskId==task.id}.map{it.prerequisiteId}.toSet())}){Text("حُلّ العائق؛ أعد التخطيط")}}
                    }
                } }
                item { DailyReviewForm(state,vm) }
                item { ReviewForm(state,vm) }
                items(state.suggestions,key={it.title}) { suggestion -> GlassCard { Text(suggestion.title);Text("الدليل: ${suggestion.evidence}");Text(suggestion.action) } }
                items(state.dailyChecks.takeLast(7).reversed(),key={"daily:"+it.day}) { c -> GlassCard { Text("يوم ${c.day} • نوم ${c.sleepHours ?: "غير مسجل"} • طاقة ${c.energy ?: "غير مسجلة"}");Text(c.wins);Text(c.obstacles);Text(c.nextAction) } }
                items(state.reviews,key={it.id}) { r -> GlassCard { Text("أسبوع ${r.week} • طاقة ${r.energy}/5");Text("الإنجازات: ${r.wins}");Text("العوائق: ${r.blockers}");Text("الخطوة التالية: ${r.nextAction}") } }
            }
        }
    }
    taskEditor?.let { t -> TaskEditor(t,state.projects,state.tasks,state.dependencies,{taskEditor=null}) { task,dependencies -> vm.saveTask(task,dependencies);taskEditor=null } }
    itemEditor?.let { item -> ProjectItemEditor(item,{itemEditor=null},{vm.saveProjectItem(it);itemEditor=null},{vm.deleteProjectItem(item.id);itemEditor=null}) }
    milestoneEditor?.let { m -> MilestoneEditor(m,{milestoneEditor=null},{vm.saveMilestone(it);milestoneEditor=null},{vm.deleteMilestone(m.id);milestoneEditor=null}) }
    habitEditor?.let { h -> HabitEditor(h,{habitEditor=null}) { vm.saveHabit(it);habitEditor=null } }
}

@Composable
private fun EnumChooser(label: String,value: String,options: List<String>,onSelect:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={expanded=true}) { Text("$label: ${humanLabel(value)}") }; DropdownMenu(expanded,{expanded=false}) { options.forEach { v -> DropdownMenuItem(text={Text(humanLabel(v))},onClick={onSelect(v);expanded=false}) } } }
}
private fun formatTaskDateTime(epoch: Long?): String = epoch?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().toString() } ?: ""
private fun parseTime(text: String): Long? = if(text.isBlank()) null else LocalDateTime.parse(text.trim()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
private fun TaskEditor(t: Task,projects: List<Project>,tasks: List<Task>,edges: List<TaskDependency>,dismiss:()->Unit,save:(Task,Set<String>)->Unit) {
    var title by remember { mutableStateOf(t.title) }; var dod by remember { mutableStateOf(t.definitionOfDone.orEmpty()) }
    var estimate by remember { mutableStateOf(t.estimatedMinutes?.toString().orEmpty()) }; var actual by remember { mutableStateOf(t.actualMinutes?.toString().orEmpty()) }
    var scheduled by remember { mutableStateOf(formatTaskDateTime(t.scheduledEpochMillis)) }; var deadline by remember { mutableStateOf(formatTaskDateTime(t.deadlineEpochMillis)) }
    var status by remember { mutableStateOf(t.status) }; var priority by remember { mutableStateOf(t.priority) }; var project by remember { mutableStateOf(t.projectId) }
    var parent by remember { mutableStateOf(t.parentId) }
    var failureCategory by remember { mutableStateOf(t.failureCategory ?: FailureCategory.OTHER) }
    var failureReason by remember { mutableStateOf(t.failureReason.orEmpty()) }
    var repeat by remember { mutableStateOf(t.repeatDays?.toString().orEmpty()) }
    var prerequisites by remember { mutableStateOf(edges.filter{it.taskId==t.id}.map{it.prerequisiteId}.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=dismiss,title={Text("تفاصيل المهمة")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { OutlinedTextField(title,{title=it},label={Text("العنوان")}) }
        item { OutlinedTextField(dod,{dod=it},label={Text("تعريف الإنجاز")}) }
        item { EnumChooser("الحالة",status.name,TaskStatus.entries.map{it.name}){status=TaskStatus.valueOf(it)} }
        item { EnumChooser("الأولوية",priority.toString(),(0..5).map{it.toString()}){priority=it.toInt()} }
        item { EnumChooser("المشروع",projects.firstOrNull{it.id==project}?.title ?: "بدون",listOf("بدون")+projects.map{it.title}){v->project=projects.firstOrNull{it.title==v}?.id} }
        item { OutlinedTextField(estimate,{estimate=it},label={Text("المدة المقدرة بالدقائق")}) }
        item { OutlinedTextField(actual,{actual=it},label={Text("المدة الفعلية بالدقائق")}) }
        item { EnumChooser("المهمة الأم",tasks.firstOrNull{it.id==parent}?.title ?: "بدون",listOf("بدون")+tasks.filter{it.id!=t.id && it.projectId==project}.map{it.title}){v->parent=tasks.firstOrNull{it.title==v && it.id!=t.id && it.projectId==project}?.id} }
        if(status in setOf(TaskStatus.BLOCKED,TaskStatus.DROPPED) || (t.scheduledEpochMillis!=null && scheduled!=formatTaskDateTime(t.scheduledEpochMillis))) {
            item { EnumChooser("سبب التعثر",failureCategory.label,FailureCategory.entries.map{it.label}){v->failureCategory=FailureCategory.entries.first{it.label==v}} }
            item { OutlinedTextField(failureReason,{failureReason=it},label={Text("ما الذي منع التنفيذ؟")}) }
        }
        item { OutlinedTextField(repeat,{repeat=it},label={Text("التكرار بالأيام: 1–365، أو فارغ")}) }
        item { Text("المهام المطلوبة أولاً") }
        items(tasks.filter{it.id!=t.id},key={it.id}) { candidate ->
            Row { Checkbox(candidate.id in prerequisites,{checked -> prerequisites=if(checked) prerequisites+candidate.id else prerequisites-candidate.id}); Text(candidate.title) }
        }
        item { Text("الموعد: YYYY-MM-DDTHH:MM وفق توقيت الجهاز. اتركه فارغًا للإزالة.") }
        item { OutlinedTextField(scheduled,{scheduled=it},label={Text("وقت التنفيذ")}) }
        item { OutlinedTextField(deadline,{deadline=it},label={Text("آخر موعد")}) }
        error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
    }},confirmButton={Button(onClick={
        runCatching {
            require(title.isNotBlank()) { "اكتب عنوان المهمة" }
            val e=if(estimate.isBlank()) null else estimate.toInt(); val a=if(actual.isBlank()) null else actual.toInt()
            require(e==null || e>0);require(a==null || a>=0)
            val repeatDays=if(repeat.isBlank()) null else repeat.toInt()
            require(repeatDays==null || repeatDays in 1..365)
            val newEdges=edges.filter{it.taskId!=t.id}+prerequisites.map{TaskDependency(t.id,it)}
            val allEdges=newEdges+tasks.filter{it.id!=t.id && it.parentId!=null}.map{TaskDependency(it.parentId!!,it.id)}+listOfNotNull(parent?.let{TaskDependency(it,t.id)})
            TaskPlanning.validateGraph(tasks.map{it.id}.toSet()+t.id,allEdges)
            if(status in setOf(TaskStatus.BLOCKED,TaskStatus.DROPPED)) require(failureReason.isNotBlank()) { "اكتب سبب التعثر" }
            if(status in setOf(TaskStatus.ACTIVE,TaskStatus.DONE) && status!=t.status) require(TaskPlanning.unmet(t.id,tasks,allEdges).isEmpty()) { "أكمل المهام المطلوبة أولاً" }
            save(t.copy(title=title.trim(),definitionOfDone=dod.takeIf{it.isNotBlank()},estimatedMinutes=e,actualMinutes=a,scheduledEpochMillis=parseTime(scheduled),deadlineEpochMillis=parseTime(deadline),status=status,priority=priority,projectId=project,repeatDays=repeatDays,parentId=parent,failureCategory=failureCategory,failureReason=failureReason.takeIf{it.isNotBlank()}),prerequisites)
        }.onFailure { error="تحقق من العنوان والأرقام وصيغة التاريخ: ${it.message.orEmpty()}" }
    }){Text("حفظ")}},dismissButton={TextButton(onClick=dismiss){Text("إلغاء")}})
}

@Composable
private fun HabitEditor(h: Habit,dismiss:()->Unit,save:(Habit)->Unit) {
    var title by remember { mutableStateOf(h.title) };var area by remember { mutableStateOf(h.area) };var target by remember { mutableStateOf(h.targetPerWeek) }
    AlertDialog(onDismissRequest=dismiss,title={Text("العادة")},text={Column {
        OutlinedTextField(title,{title=it},label={Text("العنوان")})
        EnumChooser("الجانب",area.arabicName,LifeArea.entries.map{it.arabicName}){v->area=LifeArea.entries.first{it.arabicName==v}}
        EnumChooser("أيام الأسبوع",target.toString(),(1..7).map{it.toString()}){target=it.toInt()}
        TextButton(onClick={save(h.copy(archived=true))},enabled=h.title.isNotBlank()){Text("أرشفة العادة")}
    }},confirmButton={Button(onClick={save(h.copy(title=title.trim(),area=area,targetPerWeek=target))},enabled=title.isNotBlank()){Text("حفظ")}},dismissButton={TextButton(onClick=dismiss){Text("إلغاء")}})
}

@Composable
private fun ReviewForm(state: MainUiState,vm: MainViewModel) {
    val today=LocalDate.now();val week=today.minusDays((today.dayOfWeek.value-1).toLong()).toString()
    val previous=state.reviews.firstOrNull{it.week==week}
    var wins by remember(previous?.id) { mutableStateOf(previous?.wins.orEmpty()) }
    var blockers by remember(previous?.id) { mutableStateOf(previous?.blockers.orEmpty()) }
    var action by remember(previous?.id) { mutableStateOf(previous?.nextAction.orEmpty()) }
    var energy by remember(previous?.id) { mutableStateOf(previous?.energy ?: 3) }
    var capacity by remember(state.capacity) { mutableStateOf(state.capacity.toString()) }
    var saved by remember { mutableStateOf(false) }
    GlassCard {
        Text("مراجعة أسبوع $week",style=MaterialTheme.typography.titleLarge)
        OutlinedTextField(capacity,{capacity=it},label={Text("القدرة الأسبوعية بالساعات")})
        TextButton(onClick={capacity.toDoubleOrNull()?.let(vm::setCapacity)},enabled=capacity.toDoubleOrNull()?.let{it.isFinite() && it>0 && it<=168}==true){Text("تحديث القدرة")}
        OutlinedTextField(wins,{wins=it;saved=false},label={Text("ما الذي أنجزته؟")})
        OutlinedTextField(blockers,{blockers=it;saved=false},label={Text("ما الذي عطلك؟")})
        OutlinedTextField(action,{action=it;saved=false},label={Text("تغيير عملي للأسبوع القادم")})
        EnumChooser("الطاقة",energy.toString(),(1..5).map{it.toString()}){energy=it.toInt();saved=false}
        Button(onClick={vm.saveReview(WeeklyReview(previous?.id ?: UUID.randomUUID().toString(),week,wins,blockers,action,energy));saved=true},enabled=action.isNotBlank()){Text("حفظ المراجعة")}
        if(saved) Text("تم حفظ المراجعة محليًا")
    }
}


@Composable
private fun MilestoneEditor(m: Milestone,dismiss:()->Unit,save:(Milestone)->Unit,delete:()->Unit) {
    var title by remember { mutableStateOf(m.title) }
    var due by remember { mutableStateOf(formatTaskDateTime(m.dueAt)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=dismiss,title={Text("مرحلة المشروع")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title,{title=it},label={Text("النتيجة المطلوبة")})
        OutlinedTextField(due,{due=it},label={Text("الموعد YYYY-MM-DDTHH:MM")})
        if(m.title.isNotBlank()) TextButton(onClick=delete) { Text("حذف المرحلة") }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={Button(onClick={runCatching { require(title.isNotBlank());save(m.copy(title=title.trim(),dueAt=parseTime(due))) }.onFailure { error="تحقق من العنوان وصيغة الموعد" }},enabled=title.isNotBlank()) { Text("حفظ") }},dismissButton={TextButton(onClick=dismiss) { Text("إلغاء") }})
}


@Composable
private fun ProjectItemEditor(item: ProjectItem,dismiss:()->Unit,save:(ProjectItem)->Unit,delete:()->Unit) {
    var title by remember { mutableStateOf(item.title) };var detail by remember { mutableStateOf(item.detail) };var kind by remember { mutableStateOf(item.kind) }
    AlertDialog(onDismissRequest=dismiss,title={Text("عنصر المشروع")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        EnumChooser("النوع",kind.label,WorkKind.entries.map{it.label}){v->kind=WorkKind.entries.first{it.label==v}}
        OutlinedTextField(title,{title=it},label={Text("العنوان أو الاسم")})
        OutlinedTextField(detail,{detail=it},label={Text("التفاصيل، الأدلة، أو الإجراء المطلوب")})
        if(item.title.isNotBlank()) TextButton(onClick=delete){Text("حذف")}
    }},confirmButton={Button(onClick={save(item.copy(title=title.trim(),detail=detail,kind=kind))},enabled=title.isNotBlank()){Text("حفظ")}},dismissButton={TextButton(onClick=dismiss){Text("إلغاء")}})
}

@Composable
private fun DailyReviewForm(state: MainUiState,vm: MainViewModel) {
    var day by remember { mutableStateOf(LocalDate.now().toString()) }
    val existing=state.dailyChecks.firstOrNull{it.day==day}
    var sleep by remember(day,existing) { mutableStateOf(existing?.sleepHours?.toString().orEmpty()) }
    var energy by remember(day,existing) { mutableStateOf(existing?.energy?.toString().orEmpty()) }
    var wins by remember(day,existing) { mutableStateOf(existing?.wins.orEmpty()) }
    var obstacles by remember(day,existing) { mutableStateOf(existing?.obstacles.orEmpty()) }
    var next by remember(day,existing) { mutableStateOf(existing?.nextAction.orEmpty()) }
    val valid=runCatching{LocalDate.parse(day)<=LocalDate.now() && (sleep.isBlank() || sleep.toDouble().let{it.isFinite() && it in 0.0..24.0}) && (energy.isBlank() || energy.toInt() in 1..5)}.getOrDefault(false)
    GlassCard {
        Text("مراجعة اليوم والتعافي",style=MaterialTheme.typography.titleLarge)
        OutlinedTextField(day,{day=it},label={Text("اليوم YYYY-MM-DD")})
        OutlinedTextField(sleep,{sleep=it},label={Text("ساعات النوم، أو فارغ")})
        OutlinedTextField(energy,{energy=it},label={Text("الطاقة 1–5، أو فارغ")})
        OutlinedTextField(wins,{wins=it},label={Text("الإنجازات")})
        OutlinedTextField(obstacles,{obstacles=it},label={Text("ما الذي عطل التنفيذ؟")})
        OutlinedTextField(next,{next=it},label={Text("خطوة التعافي التالية")})
        Button(onClick={vm.saveDailyCheck(DailyCheck(day,sleep.toDoubleOrNull(),energy.toIntOrNull(),wins,obstacles,next))},enabled=valid){Text("حفظ اليوم")}
    }
}
