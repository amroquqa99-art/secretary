package com.alsekretary.app.domain

import java.text.Normalizer

enum class AssistantTool { CREATE_TASK, COMPLETE_TASK, CREATE_NOTE, START_FOCUS }
data class AssistantCall(val tool: AssistantTool, val title: String = "", val targetId: String = "", val body: String = "", val expected: String = "")
data class AssistantReply(val text: String, val call: AssistantCall? = null, val handled: Boolean = true)
data class AssistantMessage(val id: String, val role: String, val text: String, val at: Long)
data class AssistantProposal(val id: String, val call: AssistantCall, val status: String, val result: String?)

/** Deterministic local commands. Neural inference is a separate, unfinished provider. */
object LocalAssistant {
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي').trim()
    fun respond(input: String, tasks: List<Task>, notes: List<Note>, budget: Int = 120): AssistantReply {
        require(input.isNotBlank() && input.length <= 2000) { "اكتب أمراً من 1 إلى 2000 حرف" }
        val text = input.trim()
        val n = normalize(text)
        val create = Regex("^(?:أضف مهمة|اضف مهمة|انشئ مهمة|أنشئ مهمة|اضف مهمه|انشئ مهمه|add task)\\s*[:：]?\\s+(.+)$", RegexOption.IGNORE_CASE)
        create.matchEntire(text)?.let {
            val title = it.groupValues[1].trim()
            require(title.length in 1..300) { "عنوان المهمة من 1 إلى 300 حرف" }
            return AssistantReply("أقترح إضافة مهمة: $title. لن تُحفظ قبل تأكيدك.", AssistantCall(AssistantTool.CREATE_TASK, title = title))
        }
        val note = Regex("^(?:احفظ ملاحظه|احفظ ملاحظة|save note)\\s*[:：]?\\s+(.+)$", setOf(RegexOption.DOT_MATCHES_ALL,RegexOption.IGNORE_CASE))
        note.matchEntire(text)?.let {
            val content = it.groupValues[1].trim()
            val parts = content.split('|', limit = 2)
            if(parts.size != 2 || parts.any { p -> p.isBlank() }) return AssistantReply("اكتب: احفظ ملاحظة العنوان | المحتوى")
            require(parts[0].trim().length <= 300) { "عنوان الملاحظة لا يتجاوز 300 حرف" }
            return AssistantReply("أقترح حفظ ملاحظة «${parts[0].trim()}». راجع المحتوى ثم أكّد.", AssistantCall(AssistantTool.CREATE_NOTE, title = parts[0].trim(), body = parts[1].trim()))
        }
        for ((prefixes, tool) in listOf(listOf("اكمل مهمة", "اكمل مهمه", "complete task") to AssistantTool.COMPLETE_TASK, listOf("ابدأ تركيز", "ابدا تركيز", "start focus") to AssistantTool.START_FOCUS)) {
            val prefix = prefixes.firstOrNull { n.startsWith(normalize(it) + " ") } ?: continue
            val query = n.removePrefix(normalize(prefix)).trim().removePrefix(":").trim()
            val found = tasks.filter { it.status !in setOf(TaskStatus.DONE, TaskStatus.DROPPED) && (normalize(it.title) == query || it.id == query) }
            if(found.size != 1) return AssistantReply(if(found.isEmpty()) "لم أجد مهمة مفتوحة بهذا العنوان. اكتب عنوانها الكامل." else "يوجد أكثر من تطابق. استخدم معرف المهمة: " + found.joinToString { "${it.title} (${it.id})" })
            val t = found.single()
            return AssistantReply(if(tool == AssistantTool.COMPLETE_TASK) "أقترح تسجيل إنجاز «${t.title}». يحتاج تأكيدك." else "أقترح بدء جلسة تركيز عادية لمهمة «${t.title}». يحتاج تأكيدك.", AssistantCall(tool, title = t.title, targetId = t.id, expected = t.toString()))
        }
        if(n in setOf("خطط يومي", "خطط اليوم", "plan today")) return AssistantReply(plan(tasks, budget))
        if(n in setOf("ما مهامي", "مهامي", "my tasks", "شو عندي اليوم")) {
            val open = tasks.filter { it.status !in setOf(TaskStatus.DONE, TaskStatus.DROPPED) }
            return AssistantReply(if(open.isEmpty()) "لا توجد مهام مفتوحة." else "المهام المفتوحة:\n" + open.take(20).joinToString("\n") { "• ${it.title} — ${it.status} [${it.id}]" })
        }
        if(n.startsWith("ابحث ") || n.startsWith("search ")) {
            val query = n.substringAfter(' ').trim()
            if(query.length < 2) return AssistantReply("اكتب كلمتين أو أكثر للبحث، أو كلمة من حرفين على الأقل.")
            val hits = tasks.filter { normalize(it.title).contains(query) }.take(10).map { "مهمة: ${it.title} [${it.id}]" } + notes.filter { normalize(it.title+" "+it.markdown).contains(query) }.take(10).map { "ملاحظة: ${it.title} [${it.id}]" }
            return AssistantReply(if(hits.isEmpty()) "لا توجد نتائج محلية لهذا البحث." else hits.joinToString("\n"))
        }
        return AssistantReply("الأوامر المحلية المتاحة:\n• أضف مهمة عنوان المهمة\n• أكمل مهمة العنوان الكامل\n• ابدأ تركيز العنوان الكامل\n• احفظ ملاحظة العنوان | المحتوى\n• خطط يومي / خطط أسبوعي\n• راجع حياتي / راجع أهدافي\n• ما مهامي\n• ابحث كلمة\nاختر النموذج المحلي من إعداداته للحوار الحر.", handled = false)
    }
    private fun plan(tasks: List<Task>, budget: Int): String {
        require(budget in 1..1440) { "ميزانية اليوم من 1 إلى 1440 دقيقة" }
        val open = tasks.filter { it.status !in setOf(TaskStatus.DONE, TaskStatus.DROPPED, TaskStatus.BLOCKED) }
        // Dependencies are passed by the caller as a filtered ready-task list.
        val ordered = open.sortedWith(compareByDescending<Task> { it.priority }.thenBy { it.deadlineEpochMillis ?: Long.MAX_VALUE }.thenBy { it.id })
        var remaining = budget
        val chosen = ordered.filter { t -> val duration = t.estimatedMinutes ?: 30; if(duration <= remaining) { remaining -= duration; true } else false }
        return if(chosen.isEmpty()) "لا توجد مهمة جاهزة تناسب ميزانية $budget دقيقة. راجع العوائق والتقديرات." else "خطة مقترحة بميزانية $budget دقيقة؛ لم تُغيّر مواعيدك:\n" + chosen.joinToString("\n") { "• ${it.title}: ${it.estimatedMinutes ?: 30} دقيقة${if(it.estimatedMinutes == null) " (افتراض مؤقت)" else ""}" } + "\nمتبقي ${remaining} دقيقة. الاختيار حسب الأولوية ثم الموعد؛ لا يشمل حل تعارضات التقويم تلقائياً."
    }
}
