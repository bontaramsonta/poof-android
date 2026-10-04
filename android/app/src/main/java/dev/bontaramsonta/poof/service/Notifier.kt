package dev.bontaramsonta.poof.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.bontaramsonta.poof.MainActivity
import dev.bontaramsonta.poof.R
import dev.bontaramsonta.poof.core.Countries
import dev.bontaramsonta.poof.core.SessionRecord

/** The Session's notifications (spec §6.3). */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Session", NotificationManager.IMPORTANCE_LOW),
        )
    }

    enum class Step(val text: String) { Launching("Launching an Exit"), Waiting("Waiting for it to answer") }

    fun connecting(country: String, step: Step): Notification = base()
        .setContentTitle("Connecting to ${Countries.displayName(country)}")
        .setContentText(step.text)
        .setOngoing(true)
        .build()

    /** Posted once at the first handshake, never updated. */
    fun postConnected(record: SessionRecord, sinceMillis: Long) = post(
        SESSION_ID,
        base()
            .setContentTitle("Connected to ${Countries.displayName(record.country)}")
            .setContentText(record.exitIp)
            .setOngoing(true)
            .setWhen(sinceMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .addAction(0, "Disconnect and destroy", disconnectIntent())
            .build(),
    )

    fun postRevoked(destroyed: Boolean) = post(
        REVOKED_ID,
        base()
            .setContentTitle(if (destroyed) "Disconnected, Exit destroyed" else "Disconnected")
            .apply { if (!destroyed) setContentText("The Exit will self-destruct within ~6 minutes") }
            .setAutoCancel(true)
            .build(),
    )

    private fun base() = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ),
        )

    private fun disconnectIntent() = PendingIntent.getService(
        context,
        0,
        Intent(context, PoofVpnService::class.java).setAction(PoofVpnService.ACTION_DISCONNECT),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(id: Int, notification: Notification) {
        // A denied POST_NOTIFICATIONS is tolerated (spec §6.3).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33
        ) {
            manager.notify(id, notification)
        }
    }

    companion object {
        const val CHANNEL = "session"
        const val SESSION_ID = 1
        const val REVOKED_ID = 2
    }
}
