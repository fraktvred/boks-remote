package dev.boksremote.dmx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.boksremote.BoksApp
import dev.boksremote.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while the DMX bridge runs, so Android doesn't stop the
 * network receiver or Bluetooth link when the screen is off. All bridge logic lives in [DmxBridge];
 * the notification only mirrors its state, so a show can be checked from the lock screen.
 */
class DmxBridgeService : Service() {
    private var updates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BoksApp
        if (intent?.action == ACTION_STOP) {
            app.stopBridge()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "DMX bridge", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, build("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        updates?.cancel()
        updates = app.scope.launch {
            // Only distinct summaries are posted, so the packet rate ticking doesn't re-notify every second.
            combine(app.bridge.status, app.dmxConfig) { s, c -> summary(s, c) }
                .distinctUntilChanged()
                // A post after stop would leave a stale, non-foreground notification behind.
                .collect { if (app.bridge.status.value.running) nm.notify(NOTIFICATION_ID, build(it)) }
        }
        // If Android kills the process, the bridge state is gone too; don't restart an empty service.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        updates?.cancel()
        super.onDestroy()
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DmxBridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DMX bridge running")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "dmx-bridge"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.boksremote.STOP_BRIDGE"

        fun summary(s: DmxStatus, c: DmxConfig): String = when {
            s.networkError != null -> s.networkError
            s.reconnecting -> "Light disconnected, reconnecting…"
            s.packetsPerSecond > 0 -> "Receiving ${c.protocol.label} universe ${c.universe} from ${s.source ?: "the network"}"
            s.addresses.isEmpty() -> "No Wi-Fi or hotspot: connect the phone to the lighting network"
            else -> "Waiting for ${c.protocol.label} universe ${c.universe} on ${s.addresses.first()}"
        }
    }
}
