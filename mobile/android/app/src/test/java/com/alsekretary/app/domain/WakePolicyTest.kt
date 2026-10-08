package com.alsekretary.app.domain

import com.alsekretary.app.voice.WakePolicy
import org.junit.Assert.*
import org.junit.Test

class WakePolicyTest {
    @Test fun ordinarySpeechDoesNotArmAndWindowExpires() {
        val policy = WakePolicy("hey secretary")
        assertNull(policy.command("ambient conversation", 1))
        assertNull(policy.command("hey secretary", 100))
        assertNull(policy.command("too late", 15101))
        assertNull(policy.command("hey secretary", 20000))
        assertEquals("Add task Read 123", policy.command("Add task Read 123", 20001))
        assertNull(policy.command("next ambient sentence", 20002))
    }
    @Test fun ArabicMarksAreMatchedAndAuthoredCommandIsPreserved() {
        val policy = WakePolicy("يا مُساعد")
        assertEquals("أضف مهمة قِراءة 123", policy.command("يا مساعد، أضف مهمة قِراءة 123", 1))
        assertEquals("Read Chapter 123", WakePolicy("hey secretary").command("HEY SECRETARY: Read Chapter 123", 1))
        assertNull(policy.command("يا مساعد", 2))
        policy.reset(); assertNull(policy.command("أمر عابر", 3))
    }
    @Test fun rejectsAmbiguousOrUnboundedPhrases() {
        for (phrase in listOf("", "secretary", "one two three four five six", "long ".repeat(30))) assertThrows(IllegalArgumentException::class.java) { WakePolicy(phrase) }
    }
}
