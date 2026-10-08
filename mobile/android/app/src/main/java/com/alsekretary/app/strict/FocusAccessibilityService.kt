package com.alsekretary.app.strict

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.alsekretary.app.data.StrictModeStore

class FocusAccessibilityService : AccessibilityService() {
    private lateinit var store: StrictModeStore
    private var lastBlockedPackage: String? = null
    private var lastBlockAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = StrictModeStore(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::store.isInitialized || !store.active) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg !in store.blockedPackages) return

        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPackage && now - lastBlockAt < 1_500L) return
        lastBlockedPackage = pkg
        lastBlockAt = now

        performGlobalAction(GLOBAL_ACTION_HOME)
        Toast.makeText(this, "وضع الالتزام نشط — أكمل المهمة أولًا", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() = Unit
}
