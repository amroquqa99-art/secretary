package com.alsekretary.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.alsekretary.app.data.SecretaryDatabase
import com.alsekretary.app.data.SecretaryRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PersonalOsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun ArabicCapturePersistsThroughRealScreensOnEnglishDevice() {
        compose.onNodeWithText("إضافة").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("مهمة تجريبية 123")
        compose.onNodeWithText("حفظ").performClick()
        SecretaryDatabase(compose.activity).use { db ->
            val repo=SecretaryRepository(db)
            compose.waitUntil(10_000){repo.listTodayTasks(true).any{it.title=="مهمة تجريبية 123"}}
            compose.onNodeWithText("مهمة تجريبية 123").assertExists()
            assertEquals(LayoutDirection.Rtl,compose.onNodeWithText("إضافة").fetchSemanticsNode().layoutInfo.layoutDirection)
            compose.onNodeWithText("العقل").performClick()
            compose.onNodeWithContentDescription("ملاحظة جديدة").performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("معرفة تجريبية")
            compose.onAllNodes(hasSetTextAction())[1].performTextInput("محتوى عربي 123\n[[رابط]]")
            compose.onNodeWithContentDescription("حفظ الملاحظة").performClick()
            compose.waitUntil(10_000){repo.listNotes().any{it.title=="معرفة تجريبية"}}
            assertEquals("محتوى عربي 123\n[[رابط]]",repo.listNotes().single{it.title=="معرفة تجريبية"}.markdown)
        }
    }
    @Test fun typingAnAssistantCommandStopsAnExplicitBackgroundSession() {
        compose.onNodeWithText("أنا").performClick()
        val prefs=com.alsekretary.app.voice.BackgroundVoiceService.prefs(compose.activity)
        prefs.edit().putBoolean("active",true).commit()
        compose.onNodeWithText("اكتب أمرك").performTextInput("أضف مهمة قراءة 123")
        assertFalse(prefs.getBoolean("active",false))
        compose.onNodeWithText("أضف مهمة قراءة 123").assertExists()
    }
}
