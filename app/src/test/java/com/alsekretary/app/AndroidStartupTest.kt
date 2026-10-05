package com.alsekretary.app

import android.view.ViewGroup
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidStartupTest {
    @Test fun launcherActivityCreatesComposeContent() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertFalse(activity.isFinishing)
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertTrue(content.childCount > 0)
            assertEquals("السكرتير", activity.getString(R.string.app_name))
        }
    }
}
