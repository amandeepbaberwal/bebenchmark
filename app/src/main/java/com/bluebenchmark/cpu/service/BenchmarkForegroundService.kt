package com.bluebenchmark.cpu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.bluebenchmark.cpu.MainActivity

/**
 * Holds PARTIAL_WAKE_LOCK so the scheduler never sleeps worker threads when
 * the screen dims. No thermal abort — runs until user cancels or service stops.
 */
class BenchmarkForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlueBench::burn").apply {
                try {
                    acquire(35 * 60 * 1000L)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val text = intent?.getStringExtra(EXTRA_TEXT) ?: "CPU benchmark running"
            if (!foregroundStarted) {
                startForegroundCompat(1, buildNotification(text))
                foregroundStarted = true
            } else {
                updateNotification(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start benchmark foreground service", e)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    fun updateNotification(text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Benchmark", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            } catch (_: Exception) {
            }
            val b = Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(com.bluebenchmark.cpu.R.string.app_name))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setSmallIcon(com.bluebenchmark.cpu.R.drawable.ic_notification)
                .setOngoing(true)
            return b.build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle(getString(com.bluebenchmark.cpu.R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setSmallIcon(com.bluebenchmark.cpu.R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                id,
                n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(id, n)
        }
    }

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlueBenchmarkService"
        const val CHANNEL = "bluebench"
        const val EXTRA_TEXT = "text"
    }
}
