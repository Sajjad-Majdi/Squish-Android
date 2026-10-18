package com.artin.pinch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.artin.pinch.data.History
import com.artin.pinch.data.Prefs

class PinchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        Prefs.init(this)
        History.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Compressing", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        lateinit var app: PinchApp
        const val CH_WORK = "work"
        const val CH_DONE = "done"
    }
}
