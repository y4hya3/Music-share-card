package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Displays lightweight, zero-blocking background export progress and completion
 * notifications for MP4 animated canvas / GIF exports (respects API 33+ POST_NOTIFICATIONS).
 */
object ExportNotificationHelper {

    const val CHANNEL_ID = "echosnap_exports"
    private const val NOTIFICATION_ID_EXPORT = 4102

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.export_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.export_notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        runCatching { nm.createNotificationChannel(channel) }
    }

    fun notifyExportProgress(
        context: Context,
        title: String,
        progressPercent: Int,
        formatLabel: String
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)
        val clamped = progressPercent.coerceIn(0, 100)
        val builder = NotificationCompat.Builder(context.applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Exporting $formatLabel…")
            .setContentText("$title ($clamped%)")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, clamped, clamped == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        runCatching {
            NotificationManagerCompat.from(context.applicationContext)
                .notify(NOTIFICATION_ID_EXPORT, builder.build())
        }
    }

    fun notifyExportComplete(
        context: Context,
        title: String,
        locationSummary: String
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)
        val launchIntent = Intent(context.applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context.applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context.applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Saved: $title")
            .setContentText(locationSummary)
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        runCatching {
            NotificationManagerCompat.from(context.applicationContext)
                .notify(NOTIFICATION_ID_EXPORT, builder.build())
        }
    }

    fun cancelExportNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext)
                .cancel(NOTIFICATION_ID_EXPORT)
        }
    }
}
