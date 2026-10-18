package dev.boksremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.os.IBinder
import android.view.View
import android.widget.RemoteViews
import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxStatus
import dev.boksremote.speaker.CycleConfig
import dev.boksremote.speaker.CycleStatus
import dev.boksremote.speaker.SleepStatus
import dev.boksremote.speaker.SpeakerController
import dev.boksremote.ui.deviceView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while a device is open or something drives the light from the
 * background (the DMX bridge, Cycle or Sleep fade), so Android doesn't stop the network receiver, the
 * timer or the Bluetooth link when the screen is off. [BoksApp] starts and stops it.
 *
 * Its notification is a small remote, usable from the lock screen: a row for the open device and one
 * for the user's scene light beside it, each with its volume or brightness and a step down, a toggle
 * (mute, or Cycle's play and pause) and a step up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightSessionService : Service() {
    private var updates: Job? = null
    /** Started in the foreground, rather than only to take a button press from a stale notification. */
    private var running = false
    private val app get() = application as BoksApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> app.stopLightSession()
            ACTION_DETACH -> app.detach()
            ACTION_PRESS -> press(
                RemoteTarget.valueOf(intent.getStringExtra(EXTRA_TARGET) ?: return START_NOT_STICKY),
                RemoteButton.valueOf(intent.getStringExtra(EXTRA_BUTTON) ?: return START_NOT_STICKY),
            )
            else -> {
                start()
                return START_NOT_STICKY
            }
        }
        if (!running) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun start() {
        val nm = getSystemService(NotificationManager::class.java)
        // Keeps the channel id of the DMX-only days, so a user's notification settings for it carry over.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Remote", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, build(Remote(Summary("Boks Remote", "Starting…"), emptyList(), open = false)), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        running = true
        updates?.cancel()
        updates = app.scope.launch {
            // Only distinct remotes are posted, so the packet rate ticking doesn't re-notify every second,
            // and at most a few a second, which is all Android takes while a slider in the app is dragged.
            remote().distinctUntilChanged().conflate().collect {
                nm.notify(NOTIFICATION_ID, build(it))
                delay(POST_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        running = false
        updates?.cancel()
        super.onDestroy()
    }

    /** The user's own Cycle and Sleep fade, which are what keeps this service running, and the open device. */
    private fun remote(): Flow<Remote> {
        val stage = app.ownStage
        val session = combine(app.bridge.status, app.dmxConfig, stage.cycle.status, stage.cycle.config, stage.sleep, ::summary)
        val bridge = combine(app.bridge.status, app.dmxConfig, ::bridgeSummary)
        val open = app.speaker.flatMapLatest { device -> device?.let { row(it, RemoteTarget.OPEN, bridge) } ?: flowOf(null) }
        val sceneLight = combine(stage.light, app.speaker) { light, opened -> (light as? SpeakerController)?.takeIf { it !== opened } }
            .distinctUntilChanged()
            .flatMapLatest { light -> light?.let { row(it, RemoteTarget.SCENE_LIGHT, bridge) } ?: flowOf(null) }
        return combine(session, open, sceneLight, app.speaker) { summary, openRow, lightRow, opened ->
            Remote(summary, listOfNotNull(openRow, lightRow), open = opened != null)
        }
    }

    private fun row(device: SpeakerController, target: RemoteTarget, bridge: Flow<String>): Flow<RemoteRow> {
        val stage = app.panelOf(device).stage
        return combine(device.state, stage.status, stage.cycle.status, stage.light, bridge) { s, status, cycle, light, bridgeText ->
            val scene = light === device
            remoteRow(target, deviceView(s, status, scene, cycle), cycle, scene, bridgeText)
        }
    }

    /** Through the device's Stage, as its quick settings would; the target is looked up afresh, in case it changed. */
    private fun press(target: RemoteTarget, button: RemoteButton) {
        val device = when (target) {
            RemoteTarget.OPEN -> app.speaker.value
            RemoteTarget.SCENE_LIGHT -> app.ownStage.light.value as? SpeakerController
        } ?: return
        val panel = app.panelOf(device)
        val s = device.state.value
        when (button) {
            RemoteButton.VOLUME_DOWN, RemoteButton.VOLUME_UP ->
                s.volume?.let { device.setVolume(stepLevel(it, up = button == RemoteButton.VOLUME_UP, VOLUME_STEP_PERCENT)) }
            RemoteButton.MUTE, RemoteButton.UNMUTE -> device.toggleMute()
            RemoteButton.DIM, RemoteButton.BRIGHTEN ->
                s.brightness?.let { panel.control.setBrightness(stepLevel(it, up = button == RemoteButton.BRIGHTEN, BRIGHTNESS_STEP_PERCENT)) }
            RemoteButton.PAUSE_CYCLE -> panel.stage.holdCycle()
            RemoteButton.PLAY_CYCLE -> panel.stage.startCycle()
        }
    }

    private fun build(r: Remote): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val first = r.rows.firstOrNull()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            // Also what the remote reads as where its own views aren't shown.
            .setContentTitle(r.summary?.title ?: first?.name)
            .setContentText(r.summary?.text ?: first?.status)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (first != null) {
            val all = RemoteViews(packageName, R.layout.notification_remote)
            r.rows.forEach { all.addView(R.id.remote_rows, rowView(it)) }
            builder.setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(rowView(first))
                .setCustomBigContentView(all)
                .setSubText(r.summary?.title)
        }
        if (r.open) builder.addAction(Notification.Action.Builder(null, "Detach", service(ACTION_DETACH, 1)).build())
        if (r.summary != null) builder.addAction(Notification.Action.Builder(null, "Stop", service(ACTION_STOP, 2)).build())
        return builder.build()
    }

    private fun rowView(row: RemoteRow) = RemoteViews(packageName, R.layout.notification_remote_row).apply {
        setTextViewText(R.id.remote_name, row.name)
        setTextViewText(R.id.remote_status, row.status)
        // Two buttons take the outer slots, so the steps line up with a row of three.
        val slots = if (row.buttons.size == 2) listOf(row.buttons[0], null, row.buttons[1]) else row.buttons
        BUTTONS.forEachIndexed { i, id ->
            val button = slots.getOrNull(i)
            if (button == null) {
                setViewVisibility(id, if (row.buttons.isEmpty()) View.GONE else View.INVISIBLE)
                return@forEachIndexed
            }
            setViewVisibility(id, View.VISIBLE)
            setImageViewResource(id, button.icon)
            setContentDescription(id, button.label)
            // The shade's own text colours, light and dark; a theme attribute resolved against the app's theme instead.
            setColorStateList(id, "setImageTintList", tint(android.R.color.system_neutral1_900), tint(android.R.color.system_neutral1_50))
            val press = Intent(this@LightSessionService, LightSessionService::class.java)
                .setAction(ACTION_PRESS)
                .putExtra(EXTRA_TARGET, row.target.name)
                .putExtra(EXTRA_BUTTON, button.name)
            // One request code per target and button, so each keeps its own extras.
            val code = PRESS_CODES + row.target.ordinal * RemoteButton.entries.size + button.ordinal
            setOnClickPendingIntent(id, PendingIntent.getService(this@LightSessionService, code, press, PendingIntent.FLAG_IMMUTABLE))
        }
    }

    private fun tint(color: Int) = ColorStateList.valueOf(getColor(color))

    private fun service(action: String, code: Int) =
        PendingIntent.getService(this, code, Intent(this, LightSessionService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    data class Summary(val title: String, val text: String)

    /** What the notification shows: the background session, if one runs, and a row per device. */
    private data class Remote(val summary: Summary?, val rows: List<RemoteRow>, val open: Boolean)

    companion object {
        private const val CHANNEL = "dmx-bridge"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.boksremote.STOP_LIGHT_SESSION"
        private const val ACTION_DETACH = "dev.boksremote.DETACH_DEVICE"
        private const val ACTION_PRESS = "dev.boksremote.REMOTE_PRESS"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_BUTTON = "button"
        private const val PRESS_CODES = 16
        private const val POST_INTERVAL_MS = 250L
        private const val VOLUME_STEP_PERCENT = 5
        private const val BRIGHTNESS_STEP_PERCENT = 10
        private val BUTTONS = listOf(R.id.remote_button_1, R.id.remote_button_2, R.id.remote_button_3)

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
