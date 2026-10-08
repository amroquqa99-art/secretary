package com.alsekretary.app.domain

data class DurationObservation(val expectedMinutes: Int, val actualMinutes: Int)

data class PatternSignal(
    val triggered: Boolean,
    val confidence: Double,
    val evidence: String
)

object BehaviorRules {
    fun durationUnderestimation(observations: List<DurationObservation>): PatternSignal {
        if (observations.size < 5) return PatternSignal(false, 0.0, "نحتاج 5 عينات متشابهة على الأقل")
        val errors = observations.map { (it.actualMinutes - it.expectedMinutes).toDouble() / it.expectedMinutes.coerceAtLeast(1) }
        val sorted = errors.sorted()
        val median = sorted[sorted.size / 2]
        val positiveRate = errors.count { it > 0.20 }.toDouble() / errors.size
        val confidence = (0.45 + 0.45 * positiveRate + 0.10 * (observations.size.coerceAtMost(10) / 10.0)).coerceAtMost(0.99)
        val triggered = median > 0.20 && confidence >= 0.80
        return PatternSignal(
            triggered,
            confidence,
            "وسيط خطأ التقدير ${(median * 100).toInt()}% عبر ${observations.size} مهام؛ ${(positiveRate * 100).toInt()}% تجاوزت 20%"
        )
    }
}
