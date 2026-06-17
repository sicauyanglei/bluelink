package com.bluelink.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * P3-3: 前台服务，保证传输任务在后台不被系统杀死，并在通知栏显示进度。
 *
 * 使用方式：
 *   TransferForegroundService.startTransfer(context, "下载", "file.zip")
 *   TransferForegroundService.updateProgress(context, 50, 100, "file.zip")
 *   TransferForegroundService.complete(context, "下载完成: file.zip")
 *   TransferForegroundService.stopTransfer(context)
 */
class TransferForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "bluelink_transfer_channel"
        private const val CHANNEL_NAME = "文件传输"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.bluelink.transfer.START"
        private const val ACTION_UPDATE = "com.bluelink.transfer.UPDATE"
        private const val ACTION_COMPLETE = "com.bluelink.transfer.COMPLETE"
        private const val ACTION_STOP = "com.bluelink.transfer.STOP"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_PROGRESS = "progress"
        private const val EXTRA_TOTAL = "total"
        private const val EXTRA_MESSAGE = "message"

        fun startTransfer(context: Context, title: String, fileName: String) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FILE, fileName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, progress: Int, total: Int, fileName: String) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_FILE, fileName)
            }
            context.startService(intent)
        }

        fun complete(context: Context, message: String) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(EXTRA_MESSAGE, message)
            }
            context.startService(intent)
        }

        fun stopTransfer(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "传输中"
                val file = intent.getStringExtra(EXTRA_FILE) ?: ""
                startForeground(NOTIFICATION_ID, buildNotification(title, file, 0, 100, true))
            }
            ACTION_UPDATE -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val total = intent.getIntExtra(EXTRA_TOTAL, 100)
                val file = intent.getStringExtra(EXTRA_FILE) ?: ""
                notifyUpdate(buildNotification("传输中", file, progress, total, true))
            }
            ACTION_COMPLETE -> {
                val msg = intent.getStringExtra(EXTRA_MESSAGE) ?: "传输完成"
                notifyUpdate(buildCompleteNotification(msg))
                stopSelf()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "文件传输进度通知"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(
        title: String,
        fileName: String,
        progress: Int,
        total: Int,
        indeterminate: Boolean
    ): Notification {
        val contentText = if (fileName.isNotEmpty()) fileName else "正在传输..."
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (total > 0 && !indeterminate) {
            builder.setProgress(total, progress, false)
            val percent = if (total > 0) (progress * 100 / total) else 0
            builder.setContentText("$contentText  $percent%")
        } else {
            builder.setProgress(total, progress, indeterminate)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.setContentIntent(pi)
        return builder.build()
    }

    private fun buildCompleteNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("传输完成")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyUpdate(notification: Notification) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }
}
