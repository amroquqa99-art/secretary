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
    val context=androidx.compose.ui.platform.LocalContext.current
    val proactivePermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)vm.setProactiveReview(true)}
    var projectionConfirm by remember { mutableStateOf(false) }
    var rollbackName by remember { mutableStateOf<String?>(null) }
    val projection=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)vm.exportVault(uri)}
    val rollback=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)rollbackName?.let { vm.exportRollback(it,uri) };rollbackName=null}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item{Text("الإعدادات والبيانات",style=MaterialTheme.typography.headlineMedium)}
        item { BackgroundVoiceSettings() }
        item{GlassCard{
            Text("نسخة احتياطية مشفرة",style=MaterialTheme.typography.titleLarge)
            Text("تشمل بياناتك والمرفقات. احفظ كلمة المرور؛ لا توجد طريقة لاستعادتها. بيانات الدخول وعمليات التعاون المؤجلة لا تدخل النسخة.")
            OutlinedTextField(password,{password=it},label={Text("كلمة مرور النسخة، 8 أحرف على الأقل")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Button(onClick={export.launch("secretary-${System.currentTimeMillis()}.skr")},enabled=password.length>=8 && state.activeFocus==null){Text("تصدير نسخة")}
            Text("الاستعادة تستبدل البيانات المحلية. تحفظ نسخة مشفرة للحالة السابقة قبل الاستبدال.")
            OutlinedTextField(confirm,{confirm=it},label={Text("اكتب استعادة للتأكيد")})
            OutlinedButton(onClick={restore.launch(arrayOf("*/*"))},enabled=password.length>=8 && confirm=="استعادة" && state.activeFocus==null){Text("اختيار النسخة واستعادتها")}
        }}
        item { GlassCard {
            Text("مبادرة السكرتير",style=MaterialTheme.typography.titleLarge)
            Text("إشعار محلي عام، مرة يومياً بين 08:00 و21:59 عند وجود موعد مهمة يحتاج انتباهك. لا يغير بياناتك. يحتاج إذن إشعارات الهاتف.")
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Switch(checked=state.proactiveEnabled,onCheckedChange={enabled->if(enabled && android.os.Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)proactivePermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else vm.setProactiveReview(enabled)});Text("تنبيه مراجعة الخطة") } }
        }
        item { GlassCard {
            Text("الحالات السابقة قبل الاستعادة",style=MaterialTheme.typography.titleLarge)
            Text("نسخ مشفرة محفوظة قبل الاستعادة. لتستعيدها تحتاج كلمة مرور عملية الاستعادة التي أنشأتها.")
            state.rollbackBackups.forEach { (name,time) -> TextButton(onClick={rollbackName=name;rollback.launch(name)}) { Text("تصدير ${formatDate(time)} ${formatTime(time)}") } }
        }}
        item { GlassCard {
            Text("ذاكرة LifeOS على الكمبيوتر",style=MaterialTheme.typography.titleLarge)
            Text("تصدير سجل حياتك إلى Markdown للبحث على الكمبيوتر. هذه نسخة للقراءة، وليست مزامنة تغييرات.")
            Button(onClick={projectionConfirm=true}) { Text("تصدير سجل الحياة") } }
        }
        item{GlassCard{Text("مواقع مشتتة أثناء الالتزام",style=MaterialTheme.typography.titleLarge);OutlinedTextField(domains,{domains=it},label={Text("نطاق واحد في كل سطر")},modifier=Modifier.fillMaxWidth());Button(onClick={vm.setBlockedDomains(domains)}){Text("حفظ المواقع")};Text("يعمل بمرشح DNS محلي أثناء جلسة صارمة. يحتاج إذن VPN؛ المواقع المسموحة تُرسل استفسارات DNS الخاصة بها إلى Cloudflare؛ المواقع المحجوبة لا تُرسل. Secure DNS أو VPN آخر قد يتجاوز الحجب.")}}
        item{GlassCard{Text("الخصوصية",style=MaterialTheme.typography.titleLarge);Text("بيانات الحياة والملاحظات محلية. رموز الدخول مشفرة بمفتاح الجهاز. لا يرسل التعاون محتوى الملاحظات أو المهام تلقائياً.")}}
        item{GlassCard{Text("وضع التشغيل");Text("الأهداف والمشاريع والمهام والتقويم والعادات والملاحظات والمراجعات محلية. الحوار الحر يحتاج نموذجاً محلياً مثبتاً.");Text("إصدار 0.9.1 • قاعدة بيانات v7")}}
    }
    if(projectionConfirm)AlertDialog(onDismissRequest={projectionConfirm=false},title={Text("تصدير سجل الحياة؟")},text={Text("الملف غير مشفر ويحتوي بياناتك الشخصية. اختر مكاناً خاصاً. لا يشمل بيانات الدخول أو الاقتراحات التنفيذية أو ملفات النماذج.")},confirmButton={TextButton(onClick={projectionConfirm=false;projection.launch("secretary-vault.zip")}) { Text("اختيار مكان وحفظ") }},dismissButton={TextButton(onClick={projectionConfirm=false}) { Text("إلغاء") }})

}
