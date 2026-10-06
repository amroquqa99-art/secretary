package com.alsekretary.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alsekretary.app.localmodel.*

@Composable
fun ModelSettings(model: ModelUiState,vm: MainViewModel) {
    var remove by remember { mutableStateOf(false) }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)vm.modelImport(uri) }
    GlassCard {
        Text("نموذج المحادثة — تجريبي",style=MaterialTheme.typography.titleMedium)
        Text(model.installed?.name ?: "${ModelCatalog.NAME} • تنزيل ${ModelCatalog.SIZE/(1024*1024)} ميغابايت")
        Text("يعمل محلياً بعد التنزيل. يحتاج نظام 64 بت وذاكرة متاحة كافية. النموذج قد يخطئ؛ راجع ردوده واقتراحاته. أداء هاتفك لم يُختبر بعد.",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("المحادثة المحلية الاختيارية",Modifier.weight(1f))
            Switch(checked=model.enabled,onCheckedChange=vm::modelEnable,enabled=!model.busy && model.installed?.format==ModelFormat.GGUF)
        }
        if(model.installed?.format==ModelFormat.LITERTLM)Text("النموذج السابق يحتاج استبداله بتنزيل GGUF. ملفه محفوظ حتى تختار إزالته.",style=MaterialTheme.typography.bodySmall)
        if(model.busy) {
            val progress=model.progress
            if(progress!=null) {
                LinearProgressIndicator(progress={ (progress.toFloat()/ModelCatalog.SIZE).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                Text("${progress/(1024*1024)} / ${ModelCatalog.SIZE/(1024*1024)} ميغابايت")
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick=vm::cancelModel){Text("إيقاف العملية")}
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick=vm::modelDownload){Text(if(model.installed==null)"تنزيل النموذج" else "تنزيل Qwen")}
                TextButton(onClick={importer.launch(arrayOf("application/octet-stream","*/*"))}){Text("استيراد .gguf")}
            }
            TextButton(onClick={remove=true}){Text("إزالة ملفات النماذج")}
        }
        Text(model.status,style=MaterialTheme.typography.bodySmall)
        Text("اترك الشاشة مفتوحة أثناء التنزيل. عند الإيقاف يمكنك استكمال التنزيل لاحقاً. كل إجراء يقترحه النموذج ينتظر تأكيدك.",style=MaterialTheme.typography.bodySmall)
    }
    if(remove)AlertDialog(onDismissRequest={remove=false},title={Text("إزالة النماذج المحلية؟")},text={Text("يحذف كل أوزان النماذج المستوردة وملف التنزيل الجزئي. مهامك وملاحظاتك وسجل محادثتك تبقى محفوظة.")},confirmButton={TextButton(onClick={vm.modelRemove();remove=false}){Text("إزالة")}},dismissButton={TextButton(onClick={remove=false}){Text("إلغاء")}})
}
