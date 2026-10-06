package com.alsekretary.app.localmodel

import com.alsekretary.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class MemoryRetrievalTest {
    private fun task(id: String,title: String,status: TaskStatus = TaskStatus.PLANNED) = Task(
        id=id,title=title,projectId=null,status=status,scheduledEpochMillis=null,deadlineEpochMillis=null,
        estimatedMinutes=30,actualMinutes=null,definitionOfDone=null
    )

    private fun goal(id: String,title: String,specific: String = title) = Goal(
        id=id,title=title,area=LifeArea.MENTAL,specific=specific,metricName="progress",
        targetValue=100.0,currentValue=0.0,unit="%",deadlineEpochMillis=null,
        relevantReason="تعلم",achievableNote="خطوات صغيرة"
    )

    @Test fun arabicNormalizationRetrievesRelevantItems() {
        val now=System.currentTimeMillis()
        val memory=MemoryRetrieval.select(
            query="شو صار بموضوع الدراسة في الجامعة؟",
            tasks=listOf(task("t1","مراجعة أوراق الجامعة"),task("t2","شراء أغراض البيت")),
            goals=listOf(goal("g1","إنهاء متطلبات الجامعة"),goal("g2","تحسين اللياقة")),
            notes=listOf(
                Note("n1","طلبات الجامعة","آخر موعد لتسليم المستندات",now,now),
                Note("n2","قائمة مشتريات","حليب وخبز",now,now)
            ),
            messages=listOf(
                AssistantMessage("m1","USER","ناقشنا أوراق الجامعه أمس",now),
                AssistantMessage("m2","USER","شو نطبخ اليوم",now)
            )
        )
        assertEquals("t1",memory.tasks.single().id)
        assertEquals("g1",memory.goals.single().id)
        assertEquals("n1",memory.notes.single().id)
        assertEquals("m1",memory.messages.single().id)
    }

    @Test fun retrievalIsBoundedAndExcludesClosedTasks() {
        val tasks=(1..12).map { task("t$it","بحث الذكاء الاصطناعي رقم $it") } +
            task("done","بحث الذكاء الاصطناعي منتهي",TaskStatus.DONE)
        val memory=MemoryRetrieval.select(
            "بحث الذكاء الاصطناعي",tasks,emptyList(),emptyList(),emptyList(),maxTasks=3
        )
        assertEquals(3,memory.tasks.size)
        assertFalse(memory.tasks.any { it.id=="done" })
    }

    @Test fun unrelatedContentIsNotInjectedIntoContext() {
        val now=System.currentTimeMillis()
        val memory=MemoryRetrieval.select(
            "الجامعة",
            listOf(task("t1","تنظيف الغرفة")),
            listOf(goal("g1","الجري خمسة كيلومتر")),
            listOf(Note("n1","وصفة","أرز وخضار",now,now)),
            listOf(AssistantMessage("m1","USER","اشتريت حليب",now))
        )
        assertTrue(memory.tasks.isEmpty())
        assertTrue(memory.goals.isEmpty())
        assertTrue(memory.notes.isEmpty())
        assertTrue(memory.messages.isEmpty())
    }

    @Test fun promptContainsRelevantMemoryWithoutReadingEverything() {
        val now=System.currentTimeMillis()
        val prompt=ModelPrompt.build(
            "لخص ملاحظة الجامعة",
            listOf(task("t1","أوراق الجامعة"),task("t2","تمرين رياضي")),
            listOf(goal("g1","التقديم للجامعة"),goal("g2","اللياقة")),
            listOf(
                Note("n1","الجامعة","المستندات المطلوبة موجودة هنا",now,now),
                Note("n2","طبخ","وصفة أرز",now,now)
            ),
            emptyList()
        )
        assertTrue(prompt.contains("المستندات المطلوبة"))
        assertFalse(prompt.contains("وصفة أرز"))
        assertTrue(prompt.contains("bounded_local_lexical_v1"))
    }
}
