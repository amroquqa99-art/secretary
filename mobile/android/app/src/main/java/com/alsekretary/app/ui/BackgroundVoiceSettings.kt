package com.alsekretary.app.ui

import android.Manifest
import android.content.SharedPreferences
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.alsekretary.app.voice.BackgroundVoiceService

@Composable fun BackgroundVoiceSettings() {
    val context = LocalContext.current
    val prefs = remember(context) { BackgroundVoiceService.prefs(context) }
    var active by remember { mutableStateOf(prefs.getBoolean("active", false)) }
    var status by remember { mutableStateOf(prefs.getString("status", "الاستماع متوقف").orEmpty()) }
    var language by remember { mutableStateOf(prefs.getString("language", "ar").orEmpty()) }
    var phrase by remember { mutableStateOf(prefs.getString("phrase", "يا سكرتير").orEmpty()) }
    fun start() { runCatching { BackgroundVoiceService.start(context, language, phrase) }.onFailure { status = it.message ?: "تعذر بدء الاستماع" } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) start() else status = "يحتاج الاستماع إذن الميكروفون وإشعار الإيقاف"
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            active = p.getBoolean("active", false); status = p.getString("status", "الاستماع متوقف").orEmpty()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    GlassCard {
        Text("التنبيه الصوتي الاختياري", style = MaterialTheme.typography.titleLarge)
        Text("استماع محلي بحد أقصى ساعة، مع إشعار إيقاف. الكلام قبل عبارة التنبيه لا يُحفظ؛ بعدها يُحفظ الأمر والرد في المحادثة. التغييرات تبقى اقتراحات تحتاج تأكيدك داخل التطبيق.")
        Row {
            FilterChip(selected = language == "ar", enabled = !active, onClick = { language = "ar"; phrase = "يا سكرتير" }, label = { Text("العربية") })
            FilterChip(selected = language.startsWith("en"), enabled = !active, onClick = { language = "en-US"; phrase = "hey secretary" }, label = { Text("English") })
        }
        OutlinedTextField(phrase, { if (it.length <= 80) phrase = it }, enabled = !active, label = { Text("عبارة التنبيه، من 2 إلى 5 كلمات") }, modifier = Modifier.fillMaxWidth())
        Text("يمكن قول العبارة والأمر معاً، أو الأمر خلال 15 ثانية بعدها. النطق في الخلفية عام؛ تفاصيل الرد داخل التطبيق. العربية تحتاج مساحة متاحة تقارب 1.3 GB للتجهيز الأول وذاكرة متاحة؛ قد يتوقف الاستماع بسبب قيود الهاتف والبطارية.")
        Row {
            Button(enabled = !active, onClick = {
                val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO)
                permission.launch(permissions)
            }) { Text("بدء جلسة الاستماع") }
            TextButton(onClick = { BackgroundVoiceService.stop(context) }) { Text("إيقاف الاستماع") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
