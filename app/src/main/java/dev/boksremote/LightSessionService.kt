package dev.boksremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxStatus
import dev.boksremote.speaker.CycleConfig
import dev.boksremote.speaker.CycleStatus
import dev.boksremote.speaker.SleepStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while something drives the light from the background (the DMX
 * bridge, Cycle or Sleep fade), so Android doesn't stop the network receiver, the timer or the Bluetooth link when
 * the screen is off. [BoksApp] starts and stops it; the notification only mirrors their state, so a
 * show can be checked from the lock screen.
 */
class LightSessionService : Service() {
    private var updates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BoksApp
        if (intent?.action == ACTION_STOP) {
            app.stopLightSession()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        // Keeps the channel id of the DMX-only days, so a user's notification settings for it carry over.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lightboks running", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, build(Summary("Lightboks", "Starting…")), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        updates?.cancel()
        updates = app.scope.launch {
            // Only distinct summaries are posted, so the packet rate ticking doesn't re-notify every second.
            // The user's own Cycle and Sleep fade, which are what keeps this service running.
            val stage = app.ownStage
            combine(app.bridge.status, app.dmxConfig, stage.cycle.status, stage.cycle.config, stage.sleep, ::summary)
                .distinctUntilChanged()
                // A post after stop would leave a stale, non-foreground notification behind.
                .collect { if (it != null) nm.notify(NOTIFICATION_ID, build(it)) }
        }
        // If Android kills the process, the session state is gone too; don't restart an empty service.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        updates?.cancel()
        super.onDestroy()
    }

    private fun build(s: Summary): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LightSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(s.title)
            .setContentText(s.text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    data class Summary(val title: String, val text: String)

    companion object {
        private const val CHANNEL = "dmx-bridge"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.boksremote.STOP_LIGHT_SESSION"

        /** The bridge takes the notification while it runs, since Cycle is paused meanwhile; null when none runs. */
        fun summary(bridge: DmxStatus, dmx: DmxConfig, cycle: CycleStatus, cycleConfig: CycleConfig, sleep: SleepStatus): Summary? {
            val fading = if (sleep.running) " · fading out over ${sleep.minutes} min" else ""
            return when {
                bridge.running -> Summary("DMX bridge running", bridgeSummary(bridge, dmx))
                cycle.running -> Summary("Cycling palettes", "${cycle.current} · changes every ${CycleConfig.intervalLabel(cycleConfig.intervalSeconds)}$fading")
                sleep.running -> Summary("Sleep fade", "Fading out over ${sleep.minutes} min, then switching the light off")
                else -> null
            }
        }

        fun bridgeSummary(s: DmxStatus, c: DmxConfig): String = when {
            s.networkError != null -> s.networkError
            s.reconnecting -> "Light disconnected, reconnecting…"
            s.packetsPerSecond > 0 -> "Receiving ${c.protocol.label} universe ${c.universe} from ${s.source ?: "the network"}"
            s.addresses.isEmpty() -> "No Wi-Fi or hotspot: connect the phone to the lighting network"
            else -> "Waiting for ${c.protocol.label} universe ${c.universe} on ${s.addresses.first()}"
        }
    }
}
