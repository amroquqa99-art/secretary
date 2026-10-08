package com.alsekretary.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alsekretary.app.domain.*
import kotlinx.coroutines.delay

@Composable
fun FocusLaunchDialog(task: Task, apps: List<AppCandidate>, onDismiss: () -> Unit, onStart: (FocusMode, Int?, Set<String>) -> Unit) {
    val context=LocalContext.current
    var accessibilityReady by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val enabled=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        accessibilityReady=enabled.contains(context.packageName+"/.strict.FocusAccessibilityService") || enabled.contains(context.packageName+"/com.alsekretary.app.strict.FocusAccessibilityService")
        onPauseOrDispose { }
    }
    var mode by remember { mutableStateOf(FocusMode.NORMAL) }
    var target by remember { mutableStateOf(task.estimatedMinutes?.toString().orEmpty()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var appPicker by remember { mutableStateOf(false) }

    AlertDialog(onDismissRequest=onDismiss,title={Text("ابدأ التنفيذ")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(task.title,fontWeight=FontWeight.Bold)
            task.definitionOfDone?.let{Text("Definition of Done: $it",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)}
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FocusMode.entries.forEach { m -> FilterChip(selected=mode==m,onClick={mode=m},label={Text(when(m){FocusMode.NORMAL->"عادي";FocusMode.TIMEBOX->"Timebox";FocusMode.STRICT->"صارم"})}) }
            }
            if(mode==FocusMode.TIMEBOX) OutlinedTextField(target,{target=it},label={Text("مدة استرشادية بالدقائق")},modifier=Modifier.fillMaxWidth())
            if(mode==FocusMode.STRICT) {
                Text("النجاح مرتبط بإنجاز المهمة، لا بانتهاء الوقت. سيتم منع التطبيقات التي تختارها حتى الإكمال أو مسار الطوارئ.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(!accessibilityReady)OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("فعّل خدمة حجب التطبيقات أولاً")}
                OutlinedButton(onClick={appPicker=true},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Block,null);Spacer(Modifier.width(6.dp));Text("التطبيقات الممنوعة (${selected.size})")}
            }
        }
    },confirmButton={Button(onClick={onStart(mode,target.toIntOrNull(),selected)},enabled=mode!=FocusMode.STRICT||(selected.isNotEmpty() && accessibilityReady)){Text("ابدأ")}},dismissButton={TextButton(onClick=onDismiss){Text("إلغاء")}})

    if(appPicker) AlertDialog(onDismissRequest={appPicker=false},title={Text("اختر المشتتات")},text={
        LazyColumn(Modifier.heightIn(max=430.dp)) { items(apps,key={it.packageName}) { app -> Row(Modifier.fillMaxWidth().clickable { selected = if(app.packageName in selected) selected-app.packageName else selected+app.packageName }.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(app.packageName in selected,{checked->selected=if(checked)selected+app.packageName else selected-app.packageName});Column{Text(app.label);Text(app.packageName,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}} } }
    },confirmButton={Button(onClick={appPicker=false}){Text("تم")}})
}

@Composable
fun FocusScreen(session: FocusSession, task: Task?, onComplete: () -> Unit, onBlocked: (String) -> Unit, onEmergency: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var blockedDialog by remember { mutableStateOf(false) }
    var emergencyDialog by remember { mutableStateOf(false) }
    LaunchedEffect(session.id) { while(true){ now=System.currentTimeMillis(); delay(1000) } }
    val elapsed = ((now-session.startedAt)/1000L).coerceAtLeast(0)
    val hours=elapsed/3600; val mins=(elapsed%3600)/60; val secs=elapsed%60

    Column(Modifier.fillMaxSize().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { IconButton(onClick=onBack){Icon(Icons.Default.ArrowForward,"رجوع")}; Spacer(Modifier.weight(1f)); AssistChip(onClick={},label={Text(when(session.mode){FocusMode.NORMAL->"FOCUS";FocusMode.TIMEBOX->"TIMEBOX";FocusMode.STRICT->"STRICT"})}) }
        Spacer(Modifier.height(30.dp))
        Text("النتيجة المطلوبة",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(task?.title ?: "مهمة",fontSize=26.sp,fontWeight=FontWeight.Bold)
        task?.definitionOfDone?.let { Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp)) }
        Spacer(Modifier.weight(.8f))
        FlipStyleClock(hours.toInt(),mins.toInt(),secs.toInt())
        Text("الوقت المنقضي",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=12.dp))
        session.targetMinutes?.let { if(session.mode==FocusMode.TIMEBOX) Text("المدة الاسترشادية $it دقيقة — لا تمنع الإكمال المبكر",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.weight(1f))
        if(session.mode==FocusMode.STRICT) {
            OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Security,null);Spacer(Modifier.width(7.dp));Text("تأكد من تفعيل خدمة الالتزام")}
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick=onComplete,modifier=Modifier.fillMaxWidth().height(54.dp)){Icon(Icons.Default.CheckCircle,null);Spacer(Modifier.width(8.dp));Text("أنجزت المهمة")}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={blockedDialog=true},modifier=Modifier.weight(1f)){Text("هناك عائق")}
            if(session.mode==FocusMode.STRICT) TextButton(onClick={emergencyDialog=true},modifier=Modifier.weight(1f)){Text("خروج طارئ")}
        }
    }

    if(blockedDialog) ReasonDialog("ما العائق؟","الالتزام يتوقف لأن الاستمرار دون إزالة العائق لا يخدم المهمة.",onDismiss={blockedDialog=false}){onBlocked(it);blockedDialog=false}
    if(emergencyDialog) EmergencyExitDialog(onDismiss={emergencyDialog=false}){onEmergency(it);emergencyDialog=false}
}

@Composable
private fun FlipStyleClock(h:Int,m:Int,s:Int) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        ClockCell("%02d".format(java.util.Locale.US,h));Text(":",fontSize=36.sp,fontWeight=FontWeight.Bold);ClockCell("%02d".format(java.util.Locale.US,m));Text(":",fontSize=36.sp,fontWeight=FontWeight.Bold);ClockCell("%02d".format(java.util.Locale.US,s))
    }
}

@Composable private fun ClockCell(value:String){Card(shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){Box(Modifier.size(74.dp,88.dp),contentAlignment=Alignment.Center){Text(value,fontSize=34.sp,fontWeight=FontWeight.Bold)}}}

@Composable
private fun ReasonDialog(title:String,description:String,onDismiss:()->Unit,onConfirm:(String)->Unit){var reason by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column{Text(description,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp);Spacer(Modifier.height(10.dp));OutlinedTextField(reason,{reason=it},modifier=Modifier.fillMaxWidth(),label={Text("السبب")})}},confirmButton={Button(onClick={onConfirm(reason.trim())},enabled=reason.isNotBlank()){Text("تأكيد")}},dismissButton={TextButton(onClick=onDismiss){Text("رجوع")}})}

@Composable
private fun EmergencyExitDialog(onDismiss:()->Unit,onConfirm:(String)->Unit){
    var reason by remember{mutableStateOf("")};var seconds by remember{mutableIntStateOf(30)}
    LaunchedEffect(Unit){while(seconds>0){delay(1000);seconds--}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("الخروج الطارئ")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("هذا المسار موجود للطوارئ، لكنه يسجل كسر الالتزام ويطلب سببًا. الانتظار يقلل القرار الاندفاعي.",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp);OutlinedTextField(reason,{reason=it},label={Text("لماذا يجب الخروج؟")},modifier=Modifier.fillMaxWidth());if(seconds>0)Text("يمكن التأكيد بعد $seconds ثانية",color=MaterialTheme.colorScheme.primary)}},confirmButton={Button(onClick={onConfirm(reason.trim())},enabled=seconds==0&&reason.isNotBlank()){Text("خروج")}},dismissButton={TextButton(onClick=onDismiss){Text("أكمل المهمة")}})
}
