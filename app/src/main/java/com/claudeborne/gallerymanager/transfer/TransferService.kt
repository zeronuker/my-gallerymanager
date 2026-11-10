package com.claudeborne.gallerymanager.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Keeps the app alive, and the Wi-Fi radio and CPU awake, while a transfer is open, so it carries on with the screen off. */
class TransferService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            getSystemService(NotificationManager::class.java).notify(1, build())
            handler.postDelayed(this, 700)
        }
    }

    /** The notification shows a progress bar over the files moving right now, so a transfer can be followed with the app in the background. */
    private fun build(): Notification {
        val active = TransferHub.items.filter { !it.finished && it.error == null }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Altus Cirrus transfer")
            .setOngoing(true)
        if (active.isEmpty()) return builder.setContentText("Waiting for files").build()
        val size = active.sumOf { it.size }
        val done = active.sumOf { it.done }
        val text = "${active.size} ${if (active.size == 1) "file" else "files"} moving"
        return if (size > 0) builder.setContentText("$text · ${(done * 100 / size).toInt()}%").setProgress(100, (done * 100 / size).toInt(), false).build()
        else builder.setContentText(text).setProgress(0, 0, true).build() // size not known, so the bar sweeps
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "File transfer", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 700)
        if (wifiLock == null) {
            wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "gm:transfer").apply { setReferenceCounted(false); acquire() }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gm:transfer").apply { setReferenceCounted(false); acquire(2 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        wifiLock?.release()
        wakeLock?.release()
    }

    companion object {
        private const val CHANNEL = "transfer"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, TransferService::class.java)) }
    }
}
