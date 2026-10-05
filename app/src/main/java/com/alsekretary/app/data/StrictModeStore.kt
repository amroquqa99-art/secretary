package com.alsekretary.app.data

import android.content.Context

class StrictModeStore(context: Context) {
    private val prefs = context.getSharedPreferences("strict_mode", Context.MODE_PRIVATE)

    var blockedDomains: Set<String>
        get()=prefs.getStringSet("blocked_domains",emptySet()).orEmpty()
        set(value){prefs.edit().putStringSet("blocked_domains",value).apply()}

    var active: Boolean
        get() = prefs.getBoolean("active", false)
        set(value) { prefs.edit().putBoolean("active", value).apply() }

    var blockedPackages: Set<String>
        get() = prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet("blocked_packages", value).apply() }

    var activeFocusId: String?
        get() = prefs.getString("active_focus_id", null)
        set(value) { prefs.edit().putString("active_focus_id", value).apply() }
}
