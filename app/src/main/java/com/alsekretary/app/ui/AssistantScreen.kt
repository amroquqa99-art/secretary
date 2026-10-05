package com.alsekretary.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alsekretary.app.domain.AssistantTool

@Composable
fun AssistantScreen(state: MainUiState, vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf("120") }
    var clearConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("السكرتير المحلي",style=MaterialTheme.typography.headlineMedium)
        Text("أوامر وخطة يوم وبحث محلي. الردود بقواعد محددة حالياً؛ نموذج المحادثة والصوت لم يدمجا بعد.",style=MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(state.assistantMessages.isEmpty()) item { GlassCard { Text("ابدأ بـ: أضف مهمة قراءة الفصل، أو خطط يومي، أو ابحث كلمة.") } }
            items(state.assistantMessages,key={it.id}) { m -> GlassCard { Text(if(m.role=="USER")"أنت" else "السكرتير",style=MaterialTheme.typography.labelLarge);Text(m.text) } }
            items(state.assistantProposals.filter{it.status=="PENDING"},key={"proposal:"+it.id}) { p -> GlassCard {
                Text("بانتظار موافقتك",style=MaterialTheme.typography.titleMedium);Text(when(p.call.tool){AssistantTool.CREATE_TASK->"إضافة مهمة";AssistantTool.COMPLETE_TASK->"تسجيل إنجاز";AssistantTool.CREATE_NOTE->"حفظ ملاحظة";AssistantTool.START_FOCUS->"بدء تركيز عادي"});Text(p.call.title)
                if(p.call.body.isNotBlank()) Text(p.call.body)
                Row { Button(onClick={vm.assistantConfirm(p.id)}){Text("تأكيد التنفيذ")};TextButton(onClick={vm.assistantCancel(p.id)}){Text("إلغاء")} }
            } }
        }
        OutlinedTextField(budget,{budget=it},label={Text("ميزانية خطة اليوم بالدقائق")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(input,{input=it},label={Text("اكتب أمرك")},modifier=Modifier.fillMaxWidth(),maxLines=4)
        Row {
            Button(onClick={vm.assistantSubmit(input,budget.toIntOrNull() ?: 120);input=""},enabled=input.isNotBlank() && input.length<=2000 && (budget.toIntOrNull() ?: 0) in 1..1440){Text("إرسال")}
            TextButton(onClick={vm.assistantSubmit("خطط يومي",budget.toIntOrNull() ?: 120)},enabled=(budget.toIntOrNull() ?: 0) in 1..1440){Text("خطة اليوم")}
            TextButton(onClick={clearConfirm=true}){Text("مسح السجل")}
        }
    }
    if(clearConfirm) AlertDialog(onDismissRequest={clearConfirm=false},title={Text("مسح ذاكرة المحادثة؟")},text={Text("يحذف السجل والاقتراحات. مهامك وملاحظاتك تبقى محفوظة.")},confirmButton={TextButton(onClick={vm.assistantClear();clearConfirm=false}){Text("مسح")}},dismissButton={TextButton(onClick={clearConfirm=false}){Text("إلغاء")}})
}
