package com.alsekretary.app.domain

enum class WorkItemKind { TASK, GOAL, PROJECT, UNCLEAR }

data class ClassificationResult(
    val kind: WorkItemKind,
    val confidence: Double,
    val reasons: List<String>,
    val suggestedLabel: String
)

object ProjectClassifier {
    fun classify(description: String): ClassificationResult {
        val text = description.trim()
        if (text.isBlank()) return ClassificationResult(WorkItemKind.UNCLEAR, 0.0, listOf("الوصف فارغ"), "أضف وصفًا")

        val projectSignals = listOf("إطلاق", "بناء", "تطوير", "تنفيذ", "إنتاج", "مشروع", "نسخة", "نظام", "موقع", "تطبيق")
        val taskSignals = listOf("راجع", "اقرأ", "ادرس", "حل", "اكتب", "اتصل", "ساعة", "ساعتين", "اليوم", "غدا", "غدًا")
        val goalSignals = listOf("أصل", "تحقيق", "تعلم", "إتقان", "معدل", "مستوى", "خلال شهر", "خلال سنة")

        val p = projectSignals.count { text.contains(it, ignoreCase = true) }
        val t = taskSignals.count { text.contains(it, ignoreCase = true) }
        val g = goalSignals.count { text.contains(it, ignoreCase = true) }

        return when {
            t >= p + 1 && text.length < 100 -> ClassificationResult(
                WorkItemKind.TASK, 0.82,
                listOf("يبدو كعمل تنفيذي واحد", "لا تظهر مراحل إدارة متعددة"),
                "أنشئه كمهمة داخل مشروع أو هدف"
            )
            p >= 2 || (p >= 1 && text.length >= 45) -> ClassificationResult(
                WorkItemKind.PROJECT, 0.84,
                listOf("هناك نتيجة مركبة", "الوصف يوحي بمراحل وتنفيذ متعدد الخطوات"),
                "أنشئ مشروعًا وحدد النتيجة ومراحل النجاح"
            )
            g >= 1 -> ClassificationResult(
                WorkItemKind.GOAL, 0.74,
                listOf("الوصف يركز على حالة مستقبلية أو مستوى مطلوب"),
                "صغه كهدف SMART"
            )
            else -> ClassificationResult(
                WorkItemKind.UNCLEAR, 0.48,
                listOf("لا توجد إشارات كافية لتحديد النوع"),
                "أضف النتيجة النهائية والخطوات المتوقعة"
            )
        }
    }
}
