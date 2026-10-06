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
        Text("يعمل محلياً بعد التنزيل. يحتاج نظام 64 بت وذاكرة متاحة كافية. جودة العربية ما زالت ضعيفة وتجريبية؛ راجع الردود والاقتراحات. أداء هاتفك لم يُختبر بعد.",style=MaterialTheme.typography.bodySmall)
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


@Composable
fun SemanticMemorySettings(memory: SemanticMemoryUiState,vm: MainViewModel) {
    var remove by remember { mutableStateOf(false) }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)vm.semanticImport(uri) }
    GlassCard {
        Text("الذاكرة الدلالية — تجريبية",style=MaterialTheme.typography.titleMedium)
        Text(memory.installed?.name ?: "${EmbeddingModelCatalog.NAME} • تنزيل ${EmbeddingModelCatalog.SIZE/(1024*1024)} ميغابايت")
        Text(
            "تستخدم embeddings محلية لاختيار المهام والأهداف والملاحظات والرسائل الأكثر ارتباطاً بالسؤال. لا تُرسل بياناتك إلى خادم، والفهرس مشتق ويمكن إعادة بنائه.",
            style=MaterialTheme.typography.bodySmall
        )
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("استخدام الذاكرة الدلالية",Modifier.weight(1f))
            Switch(
                checked=memory.enabled,
                onCheckedChange=vm::semanticEnable,
                enabled=!memory.busy && memory.installed!=null
            )
        }
        Text("الفهرس: ${memory.indexedDocuments} / ${memory.totalDocuments} عنصر",style=MaterialTheme.typography.bodySmall)
        if(memory.busy) {
            val progress=memory.progress
            if(progress!=null) {
                LinearProgressIndicator(progress={ (progress.toFloat()/EmbeddingModelCatalog.SIZE).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                Text("${progress/(1024*1024)} / ${EmbeddingModelCatalog.SIZE/(1024*1024)} ميغابايت")
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick=vm::cancelSemanticMemory){Text("إيقاف العملية")}
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick=vm::semanticDownload){Text(if(memory.installed==null)"تنزيل نموذج الذاكرة" else "إعادة تنزيل الرسمي")}
                TextButton(onClick={importer.launch(arrayOf("application/octet-stream","*/*"))}){Text("استيراد .gguf")}
            }
            Button(
                onClick=vm::semanticRebuild,
                enabled=memory.enabled && memory.installed!=null && memory.totalDocuments>0
            ){Text("بناء / تحديث الفهرس")}
            TextButton(onClick={remove=true}){Text("إزالة نموذج الذاكرة والفهرس")}
        }
        Text(memory.status,style=MaterialTheme.typography.bodySmall)
        Text(
            "بعد تغييرات كبيرة في المهام أو الملاحظات أعد بناء الفهرس. إذا كان العنصر معدلاً ولم تعد بصمته تطابق الفهرس فلن يُستخدم vector قديم له.",
            style=MaterialTheme.typography.bodySmall
        )
    }
    if(remove)AlertDialog(
        onDismissRequest={remove=false},
        title={Text("إزالة الذاكرة الدلالية؟")},
        text={Text("سيُحذف نموذج الـembedding والفهرس المشتق فقط. المهام والأهداف والملاحظات وسجل المحادثة الأصلي لا يُحذف.")},
        confirmButton={TextButton(onClick={vm.semanticRemove();remove=false}){Text("إزالة")}},
        dismissButton={TextButton(onClick={remove=false}){Text("إلغاء")}}
    )
}
