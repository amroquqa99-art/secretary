package com.alsekretary.app.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alsekretary.app.domain.*

private enum class AppScreen { HOME, PLAN, BRAIN, YOU, DASHBOARD, FOCUS }

@Composable
fun SecretaryApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice){notice?.let{snackbar.showSnackbar(it);viewModel.clearNotice()}}
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it);viewModel.clearError() } }
    var screen by remember { mutableStateOf(if(state.activeFocus != null) AppScreen.FOCUS else AppScreen.HOME) }
    var captureOpen by remember { mutableStateOf(false) }
    val context=LocalContext.current
    var pendingVpn by remember{mutableStateOf<Triple<String,Int?,Set<String>>?>(null)}
    val vpnPermission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val pending=pendingVpn;pendingVpn=null
        if(result.resultCode==Activity.RESULT_OK && pending!=null)viewModel.startFocus(pending.first,FocusMode.STRICT,pending.second,pending.third)
    }
    var focusTask by remember { mutableStateOf<Task?>(null) }

    LaunchedEffect(state.activeFocus?.id) { if(state.activeFocus != null) screen = AppScreen.FOCUS }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (screen !in setOf(AppScreen.DASHBOARD, AppScreen.FOCUS)) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                    NavItem("الرئيسية", Icons.Default.Home, screen == AppScreen.HOME) { screen = AppScreen.HOME }
                    NavItem("التخطيط", Icons.Default.CalendarMonth, screen == AppScreen.PLAN) { screen = AppScreen.PLAN }
                    NavigationBarItem(selected=false,onClick={captureOpen=true},icon={Icon(Icons.Default.AddCircle,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(34.dp))},label={Text("إضافة")})
                    NavItem("العقل", Icons.Default.AutoStories, screen == AppScreen.BRAIN) { screen = AppScreen.BRAIN }
                    NavItem("أنا", Icons.Default.Person, screen == AppScreen.YOU) { screen = AppScreen.YOU }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when(screen) {
                AppScreen.HOME -> HomeScreen(state,onDashboard={screen=AppScreen.DASHBOARD},onDone=viewModel::markDone,onStart={focusTask=it})
                AppScreen.PLAN -> PlanScreen(state,viewModel::saveGoal,viewModel::cancelGoal,viewModel::saveProject,viewModel::archiveProject,viewModel::saveCalendar,viewModel::deleteCalendar)
                AppScreen.BRAIN -> BrainScreen(state,viewModel::saveNote,viewModel::deleteNote)
                AppScreen.YOU -> PersonalHub(state, viewModel)
                AppScreen.DASHBOARD -> DashboardScreen(state.dashboard,state.goals,state.projects,onDecision=viewModel::resolveDecision,onBack={screen=AppScreen.HOME})
                AppScreen.FOCUS -> {
                    val session=state.activeFocus
                    if(session==null) LaunchedEffect(Unit){screen=AppScreen.HOME} else FocusScreen(session,state.tasks.firstOrNull{it.id==session.taskId},onComplete={viewModel.finishFocus(FocusStatus.COMPLETED);screen=AppScreen.HOME},onBlocked={viewModel.finishFocus(FocusStatus.BLOCKED,it);screen=AppScreen.HOME},onEmergency={viewModel.finishFocus(FocusStatus.EMERGENCY_EXIT,it);screen=AppScreen.HOME},onBack={screen=AppScreen.HOME})
                }
            }
        }
    }

    if(captureOpen) QuickCaptureDialog(onDismiss={captureOpen=false},onSave={viewModel.quickCapture(it);captureOpen=false})
    focusTask?.let { task -> FocusLaunchDialog(task,state.launchableApps,onDismiss={focusTask=null}) { mode,target,packages -> val permission=if(mode==FocusMode.STRICT && state.blockedDomains.isNotEmpty())VpnService.prepare(context) else null
        if(permission!=null){pendingVpn=Triple(task.id,target,packages);vpnPermission.launch(permission)}else viewModel.startFocus(task.id,mode,target,packages)
        focusTask=null } }
}

@Composable private fun RowScope.NavItem(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,selected:Boolean,onClick:()->Unit){NavigationBarItem(selected=selected,onClick=onClick,icon={Icon(icon,null)},label={Text(label)})}

@Composable
private fun HomeScreen(state:MainUiState,onDashboard:()->Unit,onDone:(String)->Unit,onStart:(Task)->Unit){
    val open=state.tasks.filter{it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED,TaskStatus.BLOCKED)}
    val ready=open.filter{TaskPlanning.unmet(it.id,state.tasks,state.planningEdges).isEmpty()}
    val next=ready.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp,18.dp,18.dp,28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("السكرتير",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("اعرف ما عليك، ثم نفّذ.",color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton(onClick=onDashboard){Icon(Icons.Default.Insights,"التحليل",tint=MaterialTheme.colorScheme.primary)}}}
        state.activeFocus?.let{active->item{Card(onClick={onStart(state.tasks.first{t->t.id==active.taskId})},colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.error.copy(.12f)),shape=RoundedCornerShape(22.dp)){Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.LockClock,null);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text("جلسة تنفيذ ما زالت نشطة",fontWeight=FontWeight.Bold);Text("ارجع للجلسة قبل بدء التزام جديد",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}}
        item{SectionLabel("الآن")}
        item{if(next==null)GlassCard{Text("لا توجد مهمة جاهزة للتنفيذ.")}else PrimaryActionCard(next,onDone,onStart)}
        item{SectionLabel("بعد ذلك")}
        items(ready.drop(1).take(4),key={it.id}){task->CompactTaskCard(task,onDone,onStart)}
        val blocked=state.tasks.filter{it.status==TaskStatus.BLOCKED || (it.status !in setOf(TaskStatus.DONE,TaskStatus.DROPPED) && TaskPlanning.unmet(it.id,state.tasks,state.planningEdges).isNotEmpty())}
        item{SectionLabel("يحتاج انتباهك")}
        item{val d=state.dashboard;GlassCard{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("قرارات",(d?.pendingDecisions?.size?:0).toString(),Modifier.weight(1f));Metric("مشاريع بخطر",(d?.projectsAtRisk?:0).toString(),Modifier.weight(1f));Metric("عوائق",blocked.size.toString(),Modifier.weight(1f))}}}
    }
}

@Composable
private fun PrimaryActionCard(task:Task,onDone:(String)->Unit,onStart:(Task)->Unit){Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primary.copy(.12f)),shape=RoundedCornerShape(28.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text(task.title,fontSize=24.sp,fontWeight=FontWeight.Bold);task.definitionOfDone?.let{Text("ينتهي عندما: $it",color=MaterialTheme.colorScheme.onSurfaceVariant)};Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick={onStart(task)},modifier=Modifier.weight(1f)){Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(6.dp));Text("ابدأ")};OutlinedButton(onClick={onDone(task.id)}){Text("تم")}}}}}

@Composable
private fun CompactTaskCard(task:Task,onDone:(String)->Unit,onStart:(Task)->Unit){GlassCard{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(task.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);task.estimatedMinutes?.let{Text("تقدير ${it}د",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)}};IconButton(onClick={onStart(task)}){Icon(Icons.Default.PlayCircleOutline,"ابدأ")};IconButton(onClick={onDone(task.id)}){Icon(Icons.Default.CheckCircleOutline,"تم")}}}}

@Composable
private fun YouScreen(state:MainUiState,onDashboard:()->Unit){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){item{Text("أنا",fontSize=28.sp,fontWeight=FontWeight.Bold)};item{Card(onClick=onDashboard,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primary.copy(.12f)),shape=RoundedCornerShape(24.dp)){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Insights,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("لوحة القيادة التنفيذية",fontWeight=FontWeight.Bold);Text("أرقام قابلة للتفسير، سلوك وقرارات",color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.ChevronLeft,null)}}};item{GlassCard{Text("السكرتير المحلي",fontWeight=FontWeight.Bold);Text("الـAI المحلي والصوت وWake Word يدخلان في المرحلة الرابعة بعد إغلاق طبقة التطبيق الأساسية.",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}

@Composable
private fun DashboardScreen(snapshot:DashboardSnapshot?,goals:List<Goal>,projects:List<Project>,onDecision:(String,DecisionStatus)->Unit,onBack:()->Unit){if(snapshot==null)return;LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp,12.dp,18.dp,30.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
    item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Default.ArrowForward,"رجوع")};Column{Text("لوحة القيادة",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("كل رقم يجب أن يشرح نفسه",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
    item{GlassCard{Text("ملخص تنفيذي",fontWeight=FontWeight.Bold,fontSize=18.sp);Spacer(Modifier.height(10.dp));Row{Metric("تنفيذ","${snapshot.executionScore}%",Modifier.weight(1f));Metric("حمل","${snapshot.loadScore}%",Modifier.weight(1f));Metric("تعافي",snapshot.recoveryScore?.let{"$it%"} ?: "لا بيانات",Modifier.weight(1f))};Text("أهداف على المسار ${snapshot.goalsOnTrack}/${snapshot.totalActiveGoals} • مشاريع بخطر ${snapshot.projectsAtRisk}",color=MaterialTheme.colorScheme.onSurfaceVariant)}}
    item{SectionLabel("جوانب الحياة")};item{GlassCard{snapshot.areaScores.forEach{(area,score)->Column(Modifier.padding(vertical=5.dp)){Row{Text(area.arabicName,Modifier.weight(1f));Text(if(score==null)"لا بيانات" else "$score%",fontWeight=FontWeight.Bold)};LinearProgressIndicator(progress={(score ?: 0)/100f},modifier=Modifier.fillMaxWidth())}}}}
    item{SectionLabel("الأهداف")};items(goals.take(4),key={it.id}){g->val ratio=if((g.targetValue?:0.0)<=0)0f else ((g.currentValue?:0.0)/g.targetValue!!).toFloat().coerceIn(0f,1f);GlassCard{Text(g.title,fontWeight=FontWeight.Bold);LinearProgressIndicator(progress={ratio},modifier=Modifier.fillMaxWidth());Text("${(ratio*100).toInt()}% • ${g.area.arabicName}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        snapshot.goalSignals.firstOrNull{it.goalId==g.id}?.let { signal ->
            Text("${signal.health} • قياسات ${signal.samples}")
            signal.velocityPerWeek?.let{Text("التقدم الأسبوعي ${"%.1f".format(it*100)} نقطة مئوية")}
            signal.requiredPerWeek?.let{Text("المطلوب أسبوعياً ${"%.1f".format(it*100)} نقطة مئوية")}
            signal.forecastAt?.let{Text("توقع خطي تقريبي: ${formatDate(it)}")}
        }}}
    item{SectionLabel("المشاريع")};items(projects.take(4),key={it.id}){p->GlassCard{Row{Text(p.title,Modifier.weight(1f),fontWeight=FontWeight.Bold);Text("Load ${p.loadScore}",color=if(p.loadScore>=85)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)};LinearProgressIndicator(progress={p.progress.toFloat()},modifier=Modifier.fillMaxWidth());Text(p.outcome,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)}}
    item{SectionLabel("القدرة والحمل")};item{GlassCard{Text("متاح ${snapshot.availableHours.toInt()}h • ملتزم ${"%.1f".format(snapshot.committedHours)}h",fontWeight=FontWeight.Bold);LinearProgressIndicator(progress={(snapshot.committedHours/snapshot.availableHours).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());if(snapshot.committedHours>snapshot.availableHours)Text("تجاوز +${"%.1f".format(snapshot.committedHours-snapshot.availableHours)}h",color=MaterialTheme.colorScheme.error)}}
    item{SectionLabel("تحليل السلوك")};if(snapshot.insights.isEmpty())item{GlassCard{Text("لا توجد عينة كافية بعد لإطلاق استنتاجات سلوكية.");Text("السكرتير ينتظر أدلة بدل اختراع نصائح.",color=MaterialTheme.colorScheme.onSurfaceVariant)}}else items(snapshot.insights,key={it.id}){i->GlassCard{Text(i.title,fontWeight=FontWeight.Bold);Text(i.evidence,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("قوة الإشارة وفق القاعدة ${(i.confidence*100).toInt()}%",color=MaterialTheme.colorScheme.primary);Text("اقتراح: ${i.suggestedAction}")}}
    item{SectionLabel("مركز القرارات")};if(snapshot.pendingDecisions.isEmpty())item{GlassCard{Text("لا توجد قرارات معلقة.")}}else items(snapshot.pendingDecisions,key={it.id}){d->GlassCard{Text(d.title,fontWeight=FontWeight.Bold);Text(d.reason,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("الدليل: ${d.evidence}",fontSize=12.sp);Text("الأثر: ${d.expectedEffect}",fontSize=12.sp);Text("المخاطرة: ${d.risk}",fontSize=12.sp);Row{TextButton(onClick={onDecision(d.id,DecisionStatus.ACCEPTED)}){Text("قبول")};TextButton(onClick={onDecision(d.id,DecisionStatus.REJECTED)}){Text("رفض")}}}}
    item{SectionLabel("أسباب التعثر — 30 يوماً")};item{GlassCard{if(snapshot.failures.isEmpty())Text("لا توجد أسباب مسجلة بعد") else snapshot.failures.forEach{(reason,count)->Text("${reason.label}: $count")}}}
    item{SectionLabel("كيف حُسبت الأرقام؟")};item{GlassCard{snapshot.explanation.forEach{Text("• $it",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
}}

@Composable
private fun QuickCaptureDialog(onDismiss:()->Unit,onSave:(String)->Unit){var text by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text("التقاط سريع")},text={Column{Text("اكتب الفكرة كما هي؛ تنظيمها لا يجب أن يعطل الالتقاط.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(10.dp));OutlinedTextField(text,{text=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("مثال: بكرة راجع فصل التشريح الثالث")})}},confirmButton={Button(onClick={onSave(text)},enabled=text.isNotBlank()){Text("حفظ")}},dismissButton={TextButton(onClick=onDismiss){Text("إلغاء")}})}
