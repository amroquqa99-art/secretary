package com.alsekretary.app.domain
import org.junit.Assert.*
import org.junit.Test
class ProgressAnalyticsTest {
    private fun task(id: String,minutes:Int?)=Task(id,id,"p",TaskStatus.PLANNED,null,null,minutes,null,"done")
    @Test fun criticalPathUsesLongestDependencyChain() {
        val tasks=listOf(task("a",30),task("b",60),task("c",20),task("d",45))
        val edges=listOf(TaskDependency("b","a"),TaskDependency("c","a"),TaskDependency("d","b"),TaskDependency("d","c"))
        val signal=ProgressAnalytics.project("p",tasks,edges,emptyList(),0)
        assertEquals(135,signal.criticalMinutes);assertEquals(listOf("a","b","d"),signal.criticalPath)
    }
    @Test fun missingDurationsAreReported() {assertEquals(1,ProgressAnalytics.project("p",listOf(task("a",null)),emptyList(),emptyList(),0).unknownDurations)}
    @Test fun noHealthDataDoesNotInventRecoveryScore() {assertNull(ProgressAnalytics.recovery(emptyList()))}
    @Test fun parentWaitsForChildren() {
        val parent=task("p",10);val child=task("c",30).copy(parentId="p")
        val signal=ProgressAnalytics.project("p",listOf(parent,child),emptyList(),emptyList(),0)
        assertEquals(40,signal.criticalMinutes);assertEquals(1,signal.blocked)
    }
    @Test fun goalRequiresMeasurements() {
        val g=Goal("g","learn",LifeArea.MENTAL,"specific","count",10.0,5.0,null,86400000,"reason","plan")
        assertEquals("بيانات غير كافية",ProgressAnalytics.goal(g,emptyList(),0).health)
    }
}
