package com.alsekretary.app.domain
import org.junit.Test
import org.junit.Assert.*
class LocalAssistantTest {
    private fun task(id: String,title: String,minutes: Int? = 20,priority: Int = 0)=Task(id,title,null,TaskStatus.PLANNED,null,null,minutes,null,null,priority=priority)
    @Test fun commandsPreserveOriginalTextAndNeverExecute() {
        val r=LocalAssistant.respond("أضف مهمة قِراءة Chapter One",emptyList(),emptyList())
        assertEquals(AssistantTool.CREATE_TASK,r.call!!.tool)
        assertEquals("قِراءة Chapter One",r.call!!.title)
        assertEquals("Mixed CASE",LocalAssistant.respond("Add task Mixed CASE",emptyList(),emptyList()).call!!.title)
    }
    @Test fun ambiguousCompletionDoesNotChooseATask() {
        val tasks=listOf(task("one","قراءة"),task("two","قراءة"))
        assertNull(LocalAssistant.respond("أكمل مهمة قراءة",tasks,emptyList()).call)
        assertEquals("one",LocalAssistant.respond("أكمل مهمة one",tasks,emptyList()).call!!.targetId)
    }
    @Test fun budgetSkipsOversizedTasksAndLabelsMissingEstimates() {
        val r=LocalAssistant.respond("خطط يومي",listOf(task("big","كبيرة",100,5),task("small","صغيرة",20,3),task("unknown","غير مقدرة",null,1)),emptyList(),50)
        assertFalse(r.text.contains("كبيرة"));assertTrue(r.text.contains("صغيرة"));assertTrue(r.text.contains("افتراض مؤقت"));assertNull(r.call)
    }
    @Test fun unknownTextDoesNotInventACommand() { assertNull(LocalAssistant.respond("احذف كل بياناتي",emptyList(),emptyList()).call) }
}
