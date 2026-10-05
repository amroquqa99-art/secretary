package com.alsekretary.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(state: MainUiState,vm: MainViewModel) {
    var domains by remember(state.blockedDomains){mutableStateOf(state.blockedDomains.joinToString("\n"))}
    var password by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null){vm.exportBackup(uri,password);password=""}}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){vm.restoreBackup(uri,password);password="";confirm=""}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item{Text("الإعدادات والبيانات",style=MaterialTheme.typography.headlineMedium)}
        item{GlassCard{
            Text("نسخة احتياطية مشفرة",style=MaterialTheme.typography.titleLarge)
            Text("تشمل بياناتك والمرفقات. احفظ كلمة المرور؛ لا توجد طريقة لاستعادتها. بيانات الدخول وعمليات التعاون المؤجلة لا تدخل النسخة.")
            OutlinedTextField(password,{password=it},label={Text("كلمة مرور النسخة، 8 أحرف على الأقل")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Button(onClick={export.launch("secretary-${System.currentTimeMillis()}.skr")},enabled=password.length>=8 && state.activeFocus==null){Text("تصدير نسخة")}
            Text("الاستعادة تستبدل البيانات المحلية. تحفظ نسخة مشفرة للحالة السابقة قبل الاستبدال.")
            OutlinedTextField(confirm,{confirm=it},label={Text("اكتب استعادة للتأكيد")})
            OutlinedButton(onClick={restore.launch(arrayOf("*/*"))},enabled=password.length>=8 && confirm=="استعادة" && state.activeFocus==null){Text("اختيار النسخة واستعادتها")}
        }}
        item{GlassCard{Text("مواقع مشتتة أثناء الالتزام",style=MaterialTheme.typography.titleLarge);OutlinedTextField(domains,{domains=it},label={Text("نطاق واحد في كل سطر")},modifier=Modifier.fillMaxWidth());Button(onClick={vm.setBlockedDomains(domains)}){Text("حفظ المواقع")};Text("يعمل بمرشح DNS محلي أثناء جلسة صارمة. يحتاج إذن VPN؛ Secure DNS في المتصفح أو VPN آخر قد يتجاوز الحجب.")}}
        item{GlassCard{Text("الخصوصية",style=MaterialTheme.typography.titleLarge);Text("بيانات الحياة والملاحظات محلية. رموز الدخول مشفرة بمفتاح الجهاز. لا يرسل التعاون محتوى الملاحظات أو المهام تلقائياً.")}}
        item{GlassCard{Text("وضع التشغيل");Text("المرحلة الثالثة: تطبيق محلي مع تعاون اختياري. أضيف أساس الأوامر المحلية. الصوت والنماذج المحلية ما زالا قيد التنفيذ.");Text("إصدار 0.4.0 • قاعدة بيانات v6")}}
    }
}
