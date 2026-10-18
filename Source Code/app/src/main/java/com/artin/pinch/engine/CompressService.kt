package com.artin.pinch.engine

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.artin.pinch.MainActivity
import com.artin.pinch.PinchApp
import com.artin.pinch.R
import com.artin.pinch.data.Prefs
import com.artin.pinch.ui.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps a batch running with the screen off or the app in the background. */
class CompressService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: PowerManager.WakeLock? = null
    private var running = false
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Work.cancel()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(ID_WORK, progressNote(), type)
        if (running) return START_NOT_STICKY
        running = true
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pinch:work")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        scope.launch {
            try {
                Work.runAll(this@CompressService) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastNotify > 800) {
                        lastNotify = now
                        getSystemService(NotificationManager::class.java).notify(ID_WORK, progressNote())
                    }
                }
            } finally {
                running = false
                wake?.let { if (it.isHeld) it.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (Work.phase == Phase.Done && !Work.cancelled) doneNote()
                stopSelf()
            }
        }
        // Progress ticks only fire between files; refresh the bar while a long video runs.
        scope.launch {
            while (running) {
                kotlinx.coroutines.delay(1000)
                if (running) getSystemService(NotificationManager::class.java).notify(ID_WORK, progressNote())
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Work.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNote(): Notification {
        val s = Strings.of(Prefs.lang)
        val total = Work.jobs.size
        val index = (Work.jobs.indexOf(Work.current) + 1).coerceIn(1, total.coerceAtLeast(1))
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, CompressService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, PinchApp.CH_WORK)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(s.notifWorking(index, total))
            .setContentText(Work.current?.m?.name ?: "")
            .setProgress(1000, (Work.overall * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), s.cancel, cancel).build())
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun doneNote() {
        val s = Strings.of(Prefs.lang)
        val n = Notification.Builder(this, PinchApp.CH_DONE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(s.notifDone(s.size(Work.saved)))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_DONE, n)
    }

    companion object {
        const val ACTION_CANCEL = "com.artin.pinch.CANCEL"
        private const val ID_WORK = 1
        private const val ID_DONE = 2
    }
}
