package com.alsekretary.app.domain

import org.junit.Assert.*
import org.junit.Test

class SmartGoalValidatorTest {
    @Test fun completeGoalPassesCoreSmartChecks() {
        val goal = Goal(
            "g1", "بناء ثلاثة مشاريع Python عملية", LifeArea.MENTAL,
            "إنهاء مسار Python وبناء ثلاثة مشاريع قابلة للتشغيل", "projects", 3.0, 0.0, "مشاريع",
            System.currentTimeMillis()+86_400_000L*90, "يدعم مساري في الذكاء الاصطناعي", "لدي خطة ووقت يومي للتعلم"
        )
        val result = SmartGoalValidator.validate(goal)
        assertTrue(result.specific.ok)
        assertTrue(result.measurable.ok)
        assertTrue(result.relevant.ok)
        assertTrue(result.timeBound.ok)
        assertTrue(result.isStrong)
    }

    @Test fun vagueGoalFails() {
        val goal = Goal("g2", "تعلم", LifeArea.MENTAL, "Python", "", null, null, null, null, "", "")
        val result = SmartGoalValidator.validate(goal)
        assertFalse(result.isStrong)
        assertFalse(result.measurable.ok)
        assertFalse(result.timeBound.ok)
    }
}
