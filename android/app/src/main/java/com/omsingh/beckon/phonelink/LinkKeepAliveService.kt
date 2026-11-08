package com.omsingh.beckon.phonelink

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.omsingh.beckon.MainActivity
import com.omsingh.beckon.R
import com.omsingh.beckon.service.BeckonConnectionService

/**
 * Keeps the link with another phone (or a TV) alive while the app is not on screen. Without it Android puts the
 * app to sleep a few seconds after it is left, and the link drops. It runs only while a link is open or waiting
 * for a phone, shows a notification with a Disconnect button, and stops by itself when the link ends.
 */
class LinkKeepAliveService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** Wi-Fi and the processor stay awake while a link is open: without them they doze a while after the screen goes off. */
    private fun holdLocks() {
        try {
            if (wifiLock == null) {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wifi?.createWifiLock(mode, "beckon:link")?.apply { setReferenceCounted(false); acquire() }
            }
            if (cpuLock == null) {
                val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
                cpuLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beckon:link")?.apply { setReferenceCounted(false); acquire() }
            }
        } catch (_: Exception) {
            // A lock refused is not worth ending the link for.
        }
    }

    override fun onDestroy() {
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { cpuLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wifiLock = null
        cpuLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            val hub = PhoneLinkHub.getInstance(application)
            hub.pcRelay.stop()
            hub.client.close()
            hub.host.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        BeckonConnectionService.ensureNotificationChannel(this)
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        holdLocks()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this, 2, Intent(this, LinkKeepAliveService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, BeckonConnectionService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.link_keepalive_title))
            .setContentText(getString(R.string.link_keepalive_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.link_disconnect), disconnect)
            .build()
    }

    companion object {
        private const val ACTION_DISCONNECT = "com.omsingh.beckon.phonelink.DISCONNECT"
        private const val NOTIFICATION_ID = 0xC0FFF0

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, LinkKeepAliveService::class.java))
            } catch (_: Exception) {
                // Starting a foreground service from the background can be refused; the link then lasts as long as Android allows.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkKeepAliveService::class.java))
        }
    }
}
