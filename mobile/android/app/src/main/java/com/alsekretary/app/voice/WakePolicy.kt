package com.alsekretary.app.voice

import com.alsekretary.app.domain.LocalAssistant

class WakePolicy(phrase: String) {
    private val wake = tokens(phrase)
    private var armedUntil = 0L
    init { require(phrase.length <= 80 && wake.size in 2..5) { "اختر عبارة تنبيه من 2 إلى 5 كلمات" } }
    companion object {
        private val words = Regex("[\\p{L}\\p{M}\\p{N}]+")
        private fun tokens(text: String) = words.findAll(text).map { LocalAssistant.normalize(it.value) }.toList()
    }
    fun command(text: String, now: Long): String? {
        val matches = words.findAll(text).toList(); val values = matches.map { LocalAssistant.normalize(it.value) }
        if (values.take(wake.size) == wake) {
            armedUntil = now + 15_000
            if (values.size == wake.size) return null
            armedUntil = 0
            return text.substring(matches[wake.size].range.first).trim().take(2000)
        }
        if (armedUntil == 0L || now > armedUntil) { armedUntil = 0; return null }
        armedUntil = 0
        return text.trim().takeIf { it.isNotBlank() }?.take(2000)
    }
    fun reset() { armedUntil = 0 }
}

fun backgroundReply(language: String, succeeded: Boolean): String = if (language.startsWith("ar")) {
    if (succeeded) "الرد محفوظ داخل التطبيق. راجع الاقتراح قبل التنفيذ." else "تعذر معالجة الأمر. راجع التطبيق."
} else if (succeeded) "The reply is saved in the app. Review any proposal before execution." else "The command could not be processed. Open the app."

class ForegroundVoiceTransition(private val stopBackground: () -> Unit, private val stopAudio: () -> Unit, private val clearConfirmation: () -> Unit) {
    fun run() { stopBackground(); stopAudio(); clearConfirmation() }
}
