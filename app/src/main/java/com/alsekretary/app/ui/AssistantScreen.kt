package com.alsekretary.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alsekretary.app.domain.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.SystemClock
import com.alsekretary.app.voice.LocalVoice

@Composable
fun AssistantScreen(state: MainUiState, vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf("120") }
    var clearConfirm by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var language by remember { mutableStateOf("ar") }
    var voiceStatus by remember { mutableStateOf("") }
    val voice=remember(context) { LocalVoice(context) { voiceStatus=it } }
    val gate=remember { VoiceConfirmation() }
    val currentState by rememberUpdatedState(state)
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voiceStatus=if(granted) "تم منح الإذن؛ اضغط أمر صوتي أو تأكيد صوتي مجدداً" else "إذن الميكروفون مرفوض؛ يمكنك الكتابة"
    }
    fun hasPermission(): Boolean {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)return true
        permission.launch(Manifest.permission.RECORD_AUDIO);return false
    }
    DisposableEffect(voice,owner) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP){gate.clear();voice.stop()} }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer);gate.clear();voice.close() }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("السكرتير المحلي",style=MaterialTheme.typography.headlineMedium)
        Text("أوامر ومراجعة أهداف وخطة أسبوع محلية. الردود بقواعد محددة؛ نموذج المحادثة لم يدمج بعد. الصوت يعتمد على الحزم المحلية المثبتة في الهاتف.",style=MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item { GlassCard {
                Text("الصوت المحلي داخل هذه الشاشة",style=MaterialTheme.typography.titleSmall)
                Row { FilterChip(selected=language=="ar",onClick={gate.clear();voice.stop();language="ar"},label={Text("العربية")});FilterChip(selected=language=="en-US",onClick={gate.clear();voice.stop();language="en-US"},label={Text("English")}) }
                Row {
                    TextButton(onClick={if(hasPermission()){gate.clear();voice.listen(language) { input=it }}}) { Text("أمر صوتي") }
                    TextButton(onClick={gate.clear();voice.speak(state.assistantMessages.lastOrNull { it.role=="ASSISTANT" }?.text ?: "لا يوجد رد بعد",language)}) { Text("قراءة الرد") }
                    TextButton(onClick={gate.clear();voice.stop();voiceStatus="توقف الصوت"}) { Text("إيقاف") }
                }
                Text(voiceStatus,style=MaterialTheme.typography.bodySmall)
                Text("الكلام يدخل حقل الأمر للمراجعة والإرسال. لا يوجد استماع في الخلفية أو كلمة تنبيه بعد.",style=MaterialTheme.typography.bodySmall)
            } }
            if(state.assistantMessages.isEmpty()) item { GlassCard { Text("ابدأ بـ: أضف مهمة قراءة الفصل، أو خطط يومي، أو ابحث كلمة.") } }
            items(state.assistantMessages,key={it.id}) { m -> GlassCard { Text(if(m.role=="USER")"أنت" else "السكرتير",style=MaterialTheme.typography.labelLarge);Text(m.text) } }
            items(state.assistantProposals.filter{it.status=="PENDING"},key={"proposal:"+it.id}) { p -> GlassCard {
                Text("بانتظار موافقتك",style=MaterialTheme.typography.titleMedium);Text(when(p.call.tool){AssistantTool.CREATE_TASK->"إضافة مهمة";AssistantTool.COMPLETE_TASK->"تسجيل إنجاز";AssistantTool.CREATE_NOTE->"حفظ ملاحظة";AssistantTool.START_FOCUS->"بدء تركيز عادي"});Text(p.call.title)
                if(p.call.body.isNotBlank()) Text(p.call.body)
                Row { Button(onClick={gate.clear();voice.stop();vm.assistantConfirm(p.id)}){Text("تأكيد التنفيذ")};TextButton(onClick={gate.clear();voice.stop();vm.assistantCancel(p.id)}){Text("إلغاء")} }
                TextButton(onClick={
                    if(hasPermission()) {
                        gate.clear()
                        val action=when(p.call.tool){AssistantTool.CREATE_TASK->"إضافة مهمة";AssistantTool.COMPLETE_TASK->"تسجيل إنجاز";AssistantTool.CREATE_NOTE->"حفظ ملاحظة";AssistantTool.START_FOCUS->"بدء جلسة تركيز عادية"}
                        val prompt=if(language=="ar") "أقترح $action: ${p.call.title}. ${p.call.body} للتنفيذ قل: أكد التنفيذ." else "Proposed action: ${p.call.tool.name}. ${p.call.title}. ${p.call.body} To proceed say: confirm execution."
                        voice.speak(prompt,language) {
                            val latest=currentState.assistantProposals.firstOrNull { it.id==p.id }
                            if(latest?.status=="PENDING" && latest.call==p.call) {
                                gate.arm(latest,SystemClock.elapsedRealtime())
                                voice.listen(language) { text ->
                                    val current=currentState.assistantProposals.firstOrNull { it.id==p.id }
                                    if(gate.consume(text,current,SystemClock.elapsedRealtime()))vm.assistantConfirm(p.id)
                                    else voiceStatus="لم تتطابق عبارة التأكيد أو انتهت المهلة؛ لم أنفّذ الاقتراح"
                                }
                            } else voiceStatus="تغير الاقتراح؛ لم يبدأ التأكيد"
                        }
                    }
                }) { Text("تأكيد صوتي") }
            } }
        }
        OutlinedTextField(budget,{budget=it},label={Text("ميزانية المهام المقترحة / اليوم بالدقائق")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(input,{input=it},label={Text("اكتب أمرك")},modifier=Modifier.fillMaxWidth(),maxLines=4)
        Row {
            Button(onClick={vm.assistantSubmit(input,budget.toIntOrNull() ?: 120);input=""},enabled=input.isNotBlank() && input.length<=2000 && (budget.toIntOrNull() ?: 0) in 1..1440){Text("إرسال")}
            TextButton(onClick={vm.assistantSubmit("خطط يومي",budget.toIntOrNull() ?: 120)},enabled=(budget.toIntOrNull() ?: 0) in 1..1440){Text("خطة اليوم")}
        }
        Row {
            TextButton(onClick={vm.assistantSubmit("خطط أسبوعي",budget.toIntOrNull() ?: 120)},enabled=(budget.toIntOrNull() ?: 0) in 1..1440){Text("خطة الأسبوع")}
            TextButton(onClick={vm.assistantSubmit("راجع حياتي",120)}){Text("مراجعة حياتي")}
            TextButton(onClick={gate.clear();voice.stop();clearConfirm=true}){Text("مسح السجل")}
        }
    }
    if(clearConfirm) AlertDialog(onDismissRequest={clearConfirm=false},title={Text("مسح ذاكرة المحادثة؟")},text={Text("يحذف السجل والاقتراحات. مهامك وملاحظاتك تبقى محفوظة.")},confirmButton={TextButton(onClick={vm.assistantClear();clearConfirm=false}){Text("مسح")}},dismissButton={TextButton(onClick={clearConfirm=false}){Text("إلغاء")}})
}
