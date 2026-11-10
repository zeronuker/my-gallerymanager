package com.claudeborne.gallerymanager.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Keeps the app alive, and the Wi-Fi radio and CPU awake, while a transfer is open, so it carries on with the screen off. */
class TransferService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "File transfer", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Gallery Manager transfer")
            .setContentText("Transferring files")
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wifiLock == null) {
            wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "gm:transfer").apply { setReferenceCounted(false); acquire() }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gm:transfer").apply { setReferenceCounted(false); acquire(2 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wifiLock?.release()
        wakeLock?.release()
    }

    companion object {
        private const val CHANNEL = "transfer"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, TransferService::class.java)) }
    }
}
