package com.alsekretary.app

import android.app.Application
import com.alsekretary.app.voice.BackgroundVoiceService

class SecretaryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BackgroundVoiceService.prefs(this).edit().putBoolean("active", false).apply()
    }
}
