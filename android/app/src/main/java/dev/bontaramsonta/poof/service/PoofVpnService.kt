package dev.bontaramsonta.poof.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.bontaramsonta.poof.PoofApp
import dev.bontaramsonta.poof.core.SessionEvent
import dev.bontaramsonta.poof.core.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process, and so the GoBackend tunnel, alive while a Session is
 * connecting, connected or ending. The tunnel itself runs in GoBackend's own
 * VpnService; revoke detection lives in TunnelManager.
 */
class PoofVpnService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as PoofApp
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        scope.launch { app.controller.state.collect(::render) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService demands startForeground within seconds, even
        // if the state flow has not caught up yet.
        if (!foreground) render(app.controller.state.value, force = true)
        if (intent?.action == ACTION_DISCONNECT) app.controller.dispatch(SessionEvent.Disconnect)
        return START_NOT_STICKY
    }

    private fun render(state: SessionState, force: Boolean = false) {
        val notification = when (state) {
            is SessionState.Launching -> app.notifier.connecting(state.country, Notifier.Step.Launching)
            is SessionState.Handshaking -> app.notifier.connecting(state.record.country, Notifier.Step.Waiting)
            // Connected: the controller posts the one-time Connected notification.
            is SessionState.Connected, is SessionState.Ending -> null
            else -> if (force) {
                app.notifier.connecting("", Notifier.Step.Launching)
            } else {
                stop()
                return
            }
        }
        if (notification != null) {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
            ServiceCompat.startForeground(this, Notifier.SESSION_ID, notification, type)
            foreground = true
        }
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_DISCONNECT = "dev.bontaramsonta.poof.DISCONNECT"
    }
}
