package com.omsingh.beckon.phonelink

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omsingh.beckon.MainActivity
import com.omsingh.beckon.R
import com.omsingh.beckon.service.BeckonConnectionService
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Shows this phone's screen to the phone it is linked to, for as long as the person allows.
 *
 * Android makes the person agree (the system's own "start recording" box) every time, and requires a
 * notification for as long as the screen is being captured: tapping Stop in it ends it at once. Pictures
 * go only to the phone that scanned this one's code, over the encrypted link, a few times a second, and only
 * when something on the screen changed.
 */
class ScreenShareService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)

        BeckonConnectionService.ensureNotificationChannel(this)
        val notification = notification()
        // The notification must be up before the capture starts (Android refuses otherwise).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Started with nothing to show (the system's box was not agreed to): the notification had to be up first.
        if (resultCode == 0 || data == null) {
            stopSharing()
            return START_NOT_STICKY
        }
        if (!running) begin(resultCode, data)
        return START_NOT_STICKY
    }

    private fun begin(resultCode: Int, data: Intent) {
        val hub = PhoneLinkHub.getInstance(application)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val worker = HandlerThread("beckon-screen-share").also { it.start() }
        val workerHandler = Handler(worker.looper)
        thread = worker
        handler = workerHandler

        val captured = try {
            manager.getMediaProjection(resultCode, data)
        } catch (_: Exception) {
            null
        }
        if (captured == null) {
            stopSharing()
            return
        }
        projection = captured
        // Android 14 wants a callback before anything is captured; it also tells us if the system ended it.
        captured.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSharing()
                }
            },
            workerHandler,
        )

        val metrics = resources.displayMetrics
        val scale = min(1f, MAX_SIDE / max(metrics.widthPixels, metrics.heightPixels).toFloat())
        val width = max(2, (metrics.widthPixels * scale).toInt() and 1.inv())
        val height = max(2, (metrics.heightPixels * scale).toInt() and 1.inv())

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        display = captured.createVirtualDisplay(
            "beckon-link",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            workerHandler,
        )

        running = true
        hub.setSharingScreen(true)
        workerHandler.post(object : Runnable {
            override fun run() {
                if (!running) return
                sendLatest(imageReader, width, height, hub)
                workerHandler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        })
    }

    /** Sends the newest picture, if the screen changed since the last one. */
    private fun sendLatest(imageReader: ImageReader, width: Int, height: Int, hub: PhoneLinkHub) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bitmap = if (rowPixels == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
            val out = ByteArrayOutputStream(64 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (bitmap !== padded) padded.recycle()
            bitmap.recycle()
            hub.client.sendFrame(width, height, out.toByteArray())
        } catch (_: Exception) {
            // One picture lost; the next one will do.
        } finally {
            image.close()
        }
    }

    private fun stopSharing() {
        val wasRunning = running
        running = false
        handler?.removeCallbacksAndMessages(null)
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null
        reader = null
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
        val hub = PhoneLinkHub.getInstance(application)
        hub.setSharingScreen(false)
        if (wasRunning) hub.client.screenEnded()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopSharing()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, BeckonConnectionService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.link_sharing_notification_title))
            .setContentText(getString(R.string.link_sharing_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.link_sharing_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.omsingh.beckon.phonelink.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val NOTIFICATION_ID = 0xC0FFEF
        private const val MAX_SIDE = 1280f
        private const val FRAME_INTERVAL_MS = 350L
        private const val JPEG_QUALITY = 60

        /** Starts showing the screen, with what the system's permission box answered. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenShareService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
