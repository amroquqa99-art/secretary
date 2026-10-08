package com.alsekretary.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

fun formatTime(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
fun formatDate(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))


fun humanLabel(code: String): String = mapOf(
    "INBOX" to "الوارد", "PLANNED" to "مخطط", "ACTIVE" to "نشط", "BLOCKED" to "متعطل", "DONE" to "منجز", "DROPPED" to "مسقط",
    "PENDING" to "بانتظار الرد", "ACCEPTED" to "مقبول", "REJECTED" to "مرفوض", "PRIVATE" to "خاص", "FRIENDS" to "الأصدقاء", "PUBLIC" to "عام", "GROUP" to "المجموعة",
    "LEARNING" to "التعلم", "RELIABILITY" to "الالتزام", "GOAL" to "الأهداف", "CONSISTENCY" to "الاستمرار", "PROJECTS" to "المشاريع", "IMPROVEMENT" to "التحسن"
)[code] ?: code
