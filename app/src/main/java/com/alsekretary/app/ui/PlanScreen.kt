package com.alsekretary.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alsekretary.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class PlanSection { CALENDAR, GOALS, PROJECTS }
enum class CalendarViewMode { DAY, WEEK, MONTH, AGENDA }

@Composable
fun PlanScreen(
    state: MainUiState,
    onSaveGoal: (Goal) -> Unit,
    onCancelGoal: (String) -> Unit,
    onSaveProject: (Project, ClassificationResult) -> Unit,
    onArchiveProject: (String) -> Unit,
    onSaveCalendar: (CalendarEntry) -> Unit,
    onDeleteCalendar: (String) -> Unit
) {
    var section by remember { mutableStateOf(PlanSection.CALENDAR) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("التخطيط", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("الوقت، الأهداف والمشاريع في مكان واحد", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = section == PlanSection.CALENDAR, onClick = { section = PlanSection.CALENDAR }, label = { Text("التقويم") }, leadingIcon = { Icon(Icons.Default.CalendarMonth, null) })
            FilterChip(selected = section == PlanSection.GOALS, onClick = { section = PlanSection.GOALS }, label = { Text("الأهداف") }, leadingIcon = { Icon(Icons.Default.Flag, null) })
            FilterChip(selected = section == PlanSection.PROJECTS, onClick = { section = PlanSection.PROJECTS }, label = { Text("المشاريع") }, leadingIcon = { Icon(Icons.Default.AccountTree, null) })
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) {
            when (section) {
                PlanSection.CALENDAR -> CalendarPanel(state.calendarEntries, onSaveCalendar, onDeleteCalendar)
                PlanSection.GOALS -> GoalsPanel(state.goalHierarchy, onSaveGoal, onCancelGoal)
                PlanSection.PROJECTS -> ProjectsPanel(state.projects, state.goals, onSaveProject, onArchiveProject)
            }
        }
    }
}

@Composable
private fun CalendarPanel(entries: List<CalendarEntry>, onSave: (CalendarEntry) -> Unit, onDelete: (String) -> Unit) {
    var mode by remember { mutableStateOf(CalendarViewMode.WEEK) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var editor by remember { mutableStateOf<CalendarEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CalendarViewMode.entries.forEach { m ->
                AssistChip(onClick = { mode = m }, label = { Text(when(m){ CalendarViewMode.DAY->"يوم"; CalendarViewMode.WEEK->"أسبوع"; CalendarViewMode.MONTH->"شهر"; CalendarViewMode.AGENDA->"أجندة" }) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = if (mode == m) MaterialTheme.colorScheme.primary.copy(.15f) else MaterialTheme.colorScheme.surface))
            }
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "موعد جديد") }
        }
        Spacer(Modifier.height(8.dp))
        DateNavigator(selectedDate, onPrevious = { selectedDate = when(mode){CalendarViewMode.MONTH->selectedDate.minusMonths(1); CalendarViewMode.WEEK->selectedDate.minusWeeks(1); else->selectedDate.minusDays(1)} },
            onToday = { selectedDate = LocalDate.now() }, onNext = { selectedDate = when(mode){CalendarViewMode.MONTH->selectedDate.plusMonths(1); CalendarViewMode.WEEK->selectedDate.plusWeeks(1); else->selectedDate.plusDays(1)} })
        Spacer(Modifier.height(8.dp))
        when (mode) {
            CalendarViewMode.DAY -> DayView(selectedDate, entries, { editor = it })
            CalendarViewMode.WEEK -> WeekView(selectedDate, entries, onDate = { selectedDate = it }, onEntry = { editor = it })
            CalendarViewMode.MONTH -> MonthView(selectedDate, entries, onDate = { selectedDate = it; mode = CalendarViewMode.DAY })
            CalendarViewMode.AGENDA -> AgendaView(entries, { editor = it })
        }
    }

    if (creating) CalendarEntryDialog(initialDate = selectedDate, initial = null, onDismiss = { creating = false }, onSave = { onSave(it); creating = false })
    editor?.let { current ->
        CalendarEntryDialog(initialDate = selectedDate, initial = current, onDismiss = { editor = null }, onSave = { onSave(it); editor = null }, onDelete = if (current.type == CalendarItemType.TASK || current.linkedEntityType == "MILESTONE") null else ({ onDelete(current.id); editor = null }))
    }
}

@Composable
private fun DateNavigator(date: LocalDate, onPrevious: () -> Unit, onToday: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.Default.ChevronRight, null) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.format(DateTimeFormatter.ofPattern("yyyy / MM / dd")), fontWeight = FontWeight.Bold)
            Text(date.dayOfWeek.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onToday) { Text("اليوم") }
        IconButton(onClick = onNext) { Icon(Icons.Default.ChevronLeft, null) }
    }
}

@Composable
private fun DayView(date: LocalDate, entries: List<CalendarEntry>, onEntry: (CalendarEntry) -> Unit) {
    val dayEntries = entries.filter { it.localDate() == date }.sortedBy { it.startEpochMillis }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (dayEntries.isEmpty()) item { GlassCard { Text("لا توجد التزامات لهذا اليوم."); Text("المساحة الفارغة تعتبر قدرة متاحة، وليست مشكلة.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(dayEntries.size) { index -> CalendarEntryCard(dayEntries[index], onEntry) }
    }
}

@Composable
private fun WeekView(anchor: LocalDate, entries: List<CalendarEntry>, onDate: (LocalDate) -> Unit, onEntry: (CalendarEntry) -> Unit) {
    val start = anchor.minusDays(((anchor.dayOfWeek.value % 7)).toLong())
    val week = (0..6).map { start.plusDays(it.toLong()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            week.forEach { date ->
                val count = entries.count { it.localDate() == date }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (date == anchor) MaterialTheme.colorScheme.primary.copy(.16f) else MaterialTheme.colorScheme.surface).clickable { onDate(date) }.padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(date.dayOfWeek.name.take(2), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(date.dayOfMonth.toString(), fontWeight = FontWeight.Bold)
                    if (count > 0) Text(count.toString(), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        DayView(anchor, entries, onEntry)
    }
}

@Composable
private fun MonthView(anchor: LocalDate, entries: List<CalendarEntry>, onDate: (LocalDate) -> Unit) {
    val first = anchor.withDayOfMonth(1)
    val offset = first.dayOfWeek.value % 7
    val gridStart = first.minusDays(offset.toLong())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth()) { listOf("ح","ن","ث","ر","خ","ج","س").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { d ->
                    val date = gridStart.plusDays((week * 7 + d).toLong())
                    val count = entries.count { it.localDate() == date }
                    Column(
                        Modifier.weight(1f).height(66.dp).padding(2.dp).clip(RoundedCornerShape(12.dp)).background(if (date == LocalDate.now()) MaterialTheme.colorScheme.primary.copy(.12f) else MaterialTheme.colorScheme.surface).clickable { onDate(date) }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(date.dayOfMonth.toString(), color = if (date.month == first.month) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (count > 0) Text("•".repeat(count.coerceAtMost(3)), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaView(entries: List<CalendarEntry>, onEntry: (CalendarEntry) -> Unit) {
    val upcoming = entries.filter { it.startEpochMillis >= System.currentTimeMillis() - 86_400_000L }.sortedBy { it.startEpochMillis }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(upcoming.size) { i ->
            CalendarEntryCard(upcoming[i], onEntry, showDate = true)
        }
    }
}

@Composable
private fun CalendarEntryCard(entry: CalendarEntry, onClick: (CalendarEntry) -> Unit, showDate: Boolean = false) {
    Card(onClick = { onClick(entry) }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(42.dp).clip(RoundedCornerShape(4.dp)).background(if(entry.type == CalendarItemType.TASK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontWeight = FontWeight.SemiBold)
                Text(buildString {
                    if (showDate) append(entry.localDate()).append(" • ")
                    append(formatTime(entry.startEpochMillis))
                    entry.endEpochMillis?.let { append(" – ").append(formatTime(it)) }
                    append(" • ").append(entry.type.name)
                }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CalendarEntryDialog(initialDate: LocalDate, initial: CalendarEntry?, onDismiss: () -> Unit, onSave: (CalendarEntry) -> Unit, onDelete: (() -> Unit)? = null) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var date by remember(initial) { mutableStateOf(initial?.localDate()?.toString() ?: initialDate.toString()) }
    var start by remember(initial) { mutableStateOf(initial?.let { formatTime(it.startEpochMillis) } ?: "09:00") }
    var end by remember(initial) { mutableStateOf(initial?.endEpochMillis?.let(::formatTime) ?: "10:00") }
    var type by remember(initial) { mutableStateOf(initial?.type ?: CalendarItemType.EVENT) }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }
    val editable = initial?.type != CalendarItemType.TASK && initial?.linkedEntityType != "MILESTONE"
    val parsedStart = parseDateTime(date, start)
    val parsedEnd = parseDateTime(date, end)

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(initial == null) "موعد جديد" else "تعديل الموعد") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!editable) Text("هذا عنصر مرتبط؛ عدّل المهمة من المتابعة، أو المرحلة من مساحة المشروع.", color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(title, { title = it }, enabled = editable, label = { Text("العنوان") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { date = it }, enabled = editable, label = { Text("التاريخ YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, enabled = editable, label = { Text("من HH:mm") }, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it }, enabled = editable, label = { Text("إلى") }, modifier = Modifier.weight(1f))
            }
            if (editable) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(CalendarItemType.EVENT, CalendarItemType.MEETING, CalendarItemType.MILESTONE).forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.name) }) }
                }
            }
            OutlinedTextField(notes, { notes = it }, enabled = editable, label = { Text("ملاحظات") }, modifier = Modifier.fillMaxWidth())
            if ((parsedStart == null || parsedEnd == null) && editable) Text("التاريخ أو الوقت غير صالح.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (onDelete != null) TextButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, null); Text("حذف") }
        }
    }, confirmButton = {
        Button(enabled = editable && title.isNotBlank() && parsedStart != null && parsedEnd != null && parsedEnd > parsedStart, onClick = {
            onSave(CalendarEntry(initial?.id ?: UUID.randomUUID().toString(), title.trim(), type, parsedStart!!, parsedEnd, notes = notes.ifBlank { null }))
        }) { Text("حفظ") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
private fun GoalsPanel(goals: List<Goal>, onSave: (Goal) -> Unit, onCancel: (String) -> Unit) {
    var editing by remember { mutableStateOf<Goal?>(null) }
    var create by remember { mutableStateOf(false) }
    val visible=goals.filter { it.status!=GoalStatus.CANCELLED }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { SectionLabel("أهداف SMART"); Spacer(Modifier.weight(1f)); FilledTonalButton(onClick = { create = true }) { Icon(Icons.Default.Add, null); Text("هدف") } } }
        if (visible.isEmpty()) item { GlassCard { Text("لا توجد أهداف بعد.") } }
        items(visible.size) { i -> GoalCard(visible[i], goals, onClick = { editing = visible[i] }) }
    }
    if (create) GoalDialog(null, goals, { create = false }, { onSave(it); create = false })
    editing?.let { goal -> GoalDialog(goal, goals, { editing = null }, { onSave(it); editing = null }, { onCancel(goal.id); editing = null }) }
}

@Composable
private fun GoalCard(goal: Goal, goals: List<Goal>, onClick: () -> Unit) {
    val validation = SmartGoalValidator.validate(goal)
    val ratio = if ((goal.targetValue ?: 0.0) <= 0) 0f else ((goal.currentValue ?: 0.0) / goal.targetValue!!).toFloat().coerceIn(0f,1f)
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row { Text(goal.title, Modifier.weight(1f), fontWeight = FontWeight.Bold); AssistChip(onClick={}, label={Text("SMART ${validation.score}/5")}) }
            Text(GoalHierarchy.lineage(goal, goals).joinToString(" ← ") { "${it.horizon.label}: ${it.title}${if(it.status==GoalStatus.CANCELLED) " (ملغى)" else ""}" },fontSize=12.sp)
            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
            Text("${(ratio*100).toInt()}% • ${goal.area.arabicName} • ${goal.deadlineEpochMillis?.let(::formatDate) ?: "بدون موعد"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GoalDialog(initial: Goal?, goals: List<Goal>, onDismiss: () -> Unit, onSave: (Goal) -> Unit, onCancel: (() -> Unit)? = null) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var area by remember(initial) { mutableStateOf(initial?.area ?: LifeArea.MENTAL) }
    var specific by remember(initial) { mutableStateOf(initial?.specific.orEmpty()) }
    var metric by remember(initial) { mutableStateOf(initial?.metricName.orEmpty()) }
    var current by remember(initial) { mutableStateOf(initial?.currentValue?.toString() ?: "0") }
    var target by remember(initial) { mutableStateOf(initial?.targetValue?.toString().orEmpty()) }
    var unit by remember(initial) { mutableStateOf(initial?.unit.orEmpty()) }
    var deadline by remember(initial) { mutableStateOf(initial?.deadlineEpochMillis?.let(::formatDate).orEmpty()) }
    var relevant by remember(initial) { mutableStateOf(initial?.relevantReason.orEmpty()) }
    var achievable by remember(initial) { mutableStateOf(initial?.achievableNote.orEmpty()) }
    var horizon by remember(initial) { mutableStateOf(initial?.horizon ?: GoalHorizon.YEAR) }
    var parentGoalId by remember(initial) { mutableStateOf(initial?.parentGoalId) }
    val parents=goals.filter { it.id!=initial?.id && it.area==area && it.horizon.ordinal<horizon.ordinal && it.status!=GoalStatus.CANCELLED }
    val candidate = Goal(initial?.id ?: "preview", title, area, specific, metric, target.toDoubleOrNull(), current.toDoubleOrNull(), unit.ifBlank{null}, parseDateStart(deadline), relevant, achievable, initial?.status ?: GoalStatus.ACTIVE, initial?.startedAt, horizon, parentGoalId)
    val hierarchyError=runCatching { GoalHierarchy.validate(goals.filterNot { it.id==candidate.id } + candidate) }.exceptionOrNull()?.message
    val validation = SmartGoalValidator.validate(candidate)

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(initial==null) "هدف SMART جديد" else "تعديل الهدف") }, text = {
        Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title,{title=it},label={Text("الهدف")},modifier=Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) { LifeArea.entries.forEach { a -> FilterChip(selected=area==a,onClick={area=a},label={Text(a.arabicName)}) } }
            Text("أفق الهدف")
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)) { GoalHorizon.entries.forEach { h -> FilterChip(selected=horizon==h,onClick={horizon=h;parentGoalId=null},label={Text(h.label)}) } }
            Text("الهدف الأعلى — اختياري")
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                FilterChip(selected=parentGoalId==null,onClick={parentGoalId=null},label={Text("مستقل")})
                parents.forEach { p -> FilterChip(selected=parentGoalId==p.id,onClick={parentGoalId=p.id},label={Text(p.title)}) }
            }
            goals.firstOrNull { it.id==parentGoalId && it.status==GoalStatus.CANCELLED }?.let { Text("الهدف الأعلى ملغى: ${it.title}. يمكنك فك الارتباط أو اختيار هدف آخر.",color=MaterialTheme.colorScheme.error) }
            hierarchyError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Text("الربط يوضح الغرض؛ تقدم الأب يقاس بمقياسه الخاص ولا يجمع وحدات الأبناء.",fontSize=12.sp)
            OutlinedTextField(specific,{specific=it},label={Text("Specific — النتيجة المحددة")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(metric,{metric=it},label={Text("مقياس التقدم")},modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(current,{current=it},label={Text("الحالي")},modifier=Modifier.weight(1f))
                OutlinedTextField(target,{target=it},label={Text("المستهدف")},modifier=Modifier.weight(1f))
                OutlinedTextField(unit,{unit=it},label={Text("الوحدة")},modifier=Modifier.weight(1f))
            }
            OutlinedTextField(deadline,{deadline=it},label={Text("الموعد YYYY-MM-DD")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(relevant,{relevant=it},label={Text("Relevant — لماذا يهم؟")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(achievable,{achievable=it},label={Text("Achievable — لماذا يمكن تحقيقه؟")},modifier=Modifier.fillMaxWidth())
            Text("SMART ${validation.score}/5", color = if(validation.isStrong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            listOf(validation.specific, validation.measurable, validation.achievable, validation.relevant, validation.timeBound).forEach { Text((if(it.ok) "✓ " else "• ") + it.reason, fontSize=12.sp, color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(onCancel != null) TextButton(onClick=onCancel){Text("إلغاء الهدف")}
        }
    }, confirmButton = { Button(onClick = { onSave(candidate.copy(id = initial?.id ?: UUID.randomUUID().toString())) }, enabled = title.isNotBlank() && title.length<=300 && hierarchyError==null) { Text("حفظ") } }, dismissButton = { TextButton(onClick=onDismiss){Text("رجوع")} })
}

@Composable
private fun ProjectsPanel(projects: List<Project>, goals: List<Goal>, onSave: (Project, ClassificationResult) -> Unit, onArchive: (String) -> Unit) {
    var editing by remember { mutableStateOf<Project?>(null) }
    var create by remember { mutableStateOf(false) }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom=24.dp)) {
        item { Row(verticalAlignment=Alignment.CenterVertically){SectionLabel("المشاريع"); Spacer(Modifier.weight(1f)); FilledTonalButton(onClick={create=true}){Icon(Icons.Default.Add,null);Text("مشروع")}} }
        items(projects.size) { i -> ProjectCard(projects[i]) { editing = projects[i] } }
    }
    if(create) ProjectDialog(null, goals, onDismiss={create=false}, onSave={p,c->onSave(p,c);create=false})
    editing?.let { p -> ProjectDialog(p, goals, onDismiss={editing=null}, onSave={project,c->onSave(project,c);editing=null}, onArchive={onArchive(p.id);editing=null}) }
}

@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit) {
    Card(onClick=onClick,shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Column(Modifier.weight(1f)){Text(project.title,fontWeight=FontWeight.Bold,fontSize=18.sp);Text(project.outcome,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)}; AssistChip(onClick={},label={Text("Load ${project.loadScore}")}) }
            LinearProgressIndicator(progress={project.progress.toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
            Text("${(project.progress*100).toInt()}% • ${project.status.name}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectDialog(initial: Project?, goals: List<Goal>, onDismiss: () -> Unit, onSave: (Project, ClassificationResult) -> Unit, onArchive: (() -> Unit)? = null) {
    var title by remember(initial){mutableStateOf(initial?.title.orEmpty())}
    var outcome by remember(initial){mutableStateOf(initial?.outcome.orEmpty())}
    var goalId by remember(initial){mutableStateOf(initial?.goalId)}
    var progress by remember(initial){mutableStateOf(((initial?.progress ?: 0.0)*100).toInt().toString())}
    var load by remember(initial){mutableStateOf((initial?.loadScore ?: 0).toString())}
    var deadline by remember(initial){mutableStateOf(initial?.deadlineEpochMillis?.let(::formatDate).orEmpty())}
    var overrideReason by remember(initial){mutableStateOf(initial?.overrideReason.orEmpty())}
    val classifier = remember(title,outcome){ProjectClassifier.classify("$title. $outcome")}
    val needsOverride = initial == null && classifier.kind != WorkItemKind.PROJECT
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(initial==null)"مشروع جديد" else "تعديل المشروع")},text={
        Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(title,{title=it},label={Text("اسم المشروع")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(outcome,{outcome=it},label={Text("النتيجة النهائية / Outcome")},modifier=Modifier.fillMaxWidth(),minLines=2)
            GlassCard { Text("تصنيف النظام: ${classifier.kind}",fontWeight=FontWeight.Bold); classifier.reasons.forEach{Text("• $it",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}; Text(classifier.suggestedLabel,color=MaterialTheme.colorScheme.primary,fontSize=12.sp) }
            if(needsOverride){
                Text("يمكنك إنشاءه رغم الاعتراض، لكن اكتب السبب كي يصبح Feedback لتخصيص المصنف.",fontSize=12.sp)
                OutlinedTextField(overrideReason,{overrideReason=it},label={Text("سبب تجاوز الاقتراح")},modifier=Modifier.fillMaxWidth())
            }
            Text("الهدف المرتبط",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){ FilterChip(selected=goalId==null,onClick={goalId=null},label={Text("بدون")}); goals.forEach{g->FilterChip(selected=goalId==g.id,onClick={goalId=g.id},label={Text(g.title.take(16))})} }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(progress,{progress=it},label={Text("التقدم %")},modifier=Modifier.weight(1f));OutlinedTextField(load,{load=it},label={Text("الحمل 0-100")},modifier=Modifier.weight(1f))}
            OutlinedTextField(deadline,{deadline=it},label={Text("الموعد YYYY-MM-DD")},modifier=Modifier.fillMaxWidth())
            if(onArchive!=null)TextButton(onClick=onArchive){Text("أرشفة المشروع")}
        }
    },confirmButton={Button(enabled=title.isNotBlank()&&outcome.isNotBlank()&&(!needsOverride||overrideReason.isNotBlank()),onClick={
        onSave(Project(initial?.id?:UUID.randomUUID().toString(),title.trim(),outcome.trim(),goalId,initial?.status?:ProjectStatus.ACTIVE,(progress.toDoubleOrNull()?.div(100.0)?:0.0).coerceIn(0.0,1.0),parseDateStart(deadline),(load.toIntOrNull()?:0).coerceIn(0,100),overrideReason.ifBlank{null}),classifier)
    }){Text("حفظ")}},dismissButton={TextButton(onClick=onDismiss){Text("إلغاء")}})
}

private fun CalendarEntry.localDate(): LocalDate = Instant.ofEpochMilli(startEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
private fun parseDateTime(date: String, time: String): Long? = runCatching { LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
private fun parseDateStart(date: String): Long? = if(date.isBlank()) null else runCatching { LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
