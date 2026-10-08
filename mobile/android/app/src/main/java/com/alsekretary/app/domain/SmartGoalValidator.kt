package com.alsekretary.app.domain

data class SmartCheck(val ok: Boolean, val reason: String)
data class SmartValidation(
    val specific: SmartCheck,
    val measurable: SmartCheck,
    val achievable: SmartCheck,
    val relevant: SmartCheck,
    val timeBound: SmartCheck
) {
    val score: Int get() = listOf(specific, measurable, achievable, relevant, timeBound).count { it.ok }
    val isStrong: Boolean get() = score >= 4 && specific.ok && measurable.ok && timeBound.ok
}

object SmartGoalValidator {
    fun validate(goal: Goal): SmartValidation {
        val specific = SmartCheck(
            goal.title.trim().length >= 8 && goal.specific.trim().length >= 12,
            if (goal.specific.trim().length >= 12) "النتيجة محددة" else "حدد النتيجة بصورة أدق"
        )
        val measurable = SmartCheck(
            goal.metricName.isNotBlank() && goal.targetValue != null,
            if (goal.metricName.isNotBlank() && goal.targetValue != null) "يوجد معيار قياس" else "أضف رقمًا أو معيارًا قابلًا للقياس"
        )
        val achievable = SmartCheck(
            goal.achievableNote.trim().length >= 8,
            if (goal.achievableNote.trim().length >= 8) "تم توضيح قابلية التحقيق" else "وضح لماذا الهدف واقعي بمواردك الحالية"
        )
        val relevant = SmartCheck(
            goal.relevantReason.trim().length >= 8,
            if (goal.relevantReason.trim().length >= 8) "مرتبط بسبب واضح" else "اربط الهدف بقيمة أو اتجاه أكبر"
        )
        val timeBound = SmartCheck(
            goal.deadlineEpochMillis != null,
            if (goal.deadlineEpochMillis != null) "يوجد موعد نهائي" else "حدد تاريخًا نهائيًا"
        )
        return SmartValidation(specific, measurable, achievable, relevant, timeBound)
    }
}
