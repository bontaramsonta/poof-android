package dev.bontaramsonta.poof

import android.app.Application
import dev.bontaramsonta.poof.data.ControlPlaneClient
import dev.bontaramsonta.poof.data.SecretStore
import dev.bontaramsonta.poof.service.Notifier
import dev.bontaramsonta.poof.session.SessionController
import kotlinx.coroutines.MainScope

class PoofApp : Application() {
    lateinit var notifier: Notifier
        private set
    lateinit var controller: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        val http = ControlPlaneClient.defaultHttpClient()
        controller = SessionController(
            context = this,
            store = SecretStore(this),
            clientFor = { token -> ControlPlaneClient(BuildConfig.CONTROL_PLANE_URL, { token }, http) },
            notifier = notifier,
            scope = MainScope(),
        )
    }
}
