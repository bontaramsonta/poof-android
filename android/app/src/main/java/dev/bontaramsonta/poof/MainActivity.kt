package dev.bontaramsonta.poof

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bontaramsonta.poof.theme.PoofTheme
import dev.bontaramsonta.poof.ui.PoofScreens
import dev.bontaramsonta.poof.ui.ScreenActions

class MainActivity : ComponentActivity() {
    private val controller get() = (application as PoofApp).controller

    /** Runs once VPN consent is granted. */
    private var afterConsent: (() -> Unit)? = null

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val next = afterConsent
        afterConsent = null
        if (result.resultCode == RESULT_OK) next?.invoke()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // A denial is tolerated and never asked again.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = ScreenActions(
            controller = controller,
            connect = { country -> withConsent { controller.connect(country) } },
            reconnect = { withConsent { controller.reconnect() } },
        )
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val token by controller.token.collectAsStateWithLifecycle()
            val stats by controller.stats.collectAsStateWithLifecycle()
            PoofTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PoofScreens(state, token, stats, actions)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.uiVisible.value = true
    }

    override fun onStop() {
        controller.uiVisible.value = false
        super.onStop()
    }

    private fun withConsent(action: () -> Unit) {
        val consent = VpnService.prepare(this)
        val proceed = {
            askNotificationsOnce()
            action()
        }
        if (consent == null) {
            proceed()
        } else {
            afterConsent = proceed
            vpnConsent.launch(consent)
        }
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("poof-ui", MODE_PRIVATE)
        if (prefs.getBoolean(ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val ASKED_NOTIFICATIONS = "asked-notifications"
    }
}
