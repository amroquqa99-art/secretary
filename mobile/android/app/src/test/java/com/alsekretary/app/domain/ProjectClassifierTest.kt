package com.alsekretary.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectClassifierTest {
    @Test fun studySessionIsTask() {
        assertEquals(WorkItemKind.TASK, ProjectClassifier.classify("ادرس التشريح ساعتين اليوم").kind)
    }

    @Test fun appLaunchIsProject() {
        assertEquals(WorkItemKind.PROJECT, ProjectClassifier.classify("بناء وإطلاق تطبيق السكرتير للأندرويد مع عدة مراحل وتنفيذ كامل").kind)
    }
}
