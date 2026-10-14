package dev.boksremote.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.boksremote.audio.BandResponse
import dev.boksremote.audio.MeasurementException
import dev.boksremote.audio.Place
import dev.boksremote.audio.RoomCorrection
import dev.boksremote.audio.RoomMeter
import dev.boksremote.audio.SoundGoal
import dev.boksremote.audio.Spectrum
import dev.boksremote.audio.fitToMicrophone
import dev.boksremote.speaker.EqCurve
import dev.boksremote.speaker.EqMode
import dev.boksremote.speaker.EqPresetStore
import dev.boksremote.speaker.SpeakerController
import dev.boksremote.speaker.SpeakerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.roundToInt

private const val MAX_SPOTS = 3

/** The wizard's pages, in order. */
private enum class TuneStep(val title: String) {
    INTRO("How it works"),
    GOAL("What it's for"),
    SETUP("Get ready"),
    REFERENCE("Close to the speaker"),
    SPOTS("Where people listen"),
    RESULT("Your room EQ"),
}

/**
 * Guided room measurement, one page per step: what it does, where the speaker plays and the sound
 * wanted, a pre-flight check (microphone, Bluetooth output, volume, quiet room), a reference close
 * to the speaker, then up to three listening spots. The difference is what the room does, and
 * [RoomCorrection] fits the six bands so room plus EQ lands on the chosen sound. Measuring runs on
 * a flat curve; closing without keeping the result puts the old EQ back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomCorrectionWizard(
    s: SpeakerState,
    speaker: SpeakerController,
    store: EqPresetStore,
    shapes: List<BandResponse>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meter = remember { RoomMeter(context) }
    val original = remember { s.eq to s.eqBands }
    val userVolume = remember { s.volume }
    // The reference may need the speaker turned down; the spots are measured at the same volume,
    // since a speaker's voicing can change with level.
    var measureVolume by remember { mutableStateOf<Int?>(null) }
    var step by remember { mutableStateOf(TuneStep.INTRO) }
    var place by remember { mutableStateOf(Place.INDOORS) }
    var sound by remember { mutableStateOf(SoundGoal.BALANCED) }
    var kept by remember { mutableStateOf(false) }
    var reference by remember { mutableStateOf<DoubleArray?>(null) }
    var spots by remember { mutableStateOf(listOf<DoubleArray>()) }
    var result by remember { mutableStateOf<RoomCorrection.Result?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hearingCorrection by remember { mutableStateOf(true) }
    var naming by remember { mutableStateOf(false) }
    var hasMic by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var bluetooth by remember { mutableStateOf(meter.outputIsBluetooth()) }
    var externalMics by remember { mutableStateOf(meter.externalMics()) }
    // By id: AudioDeviceInfo objects are re-created on every query.
    var micId by remember { mutableStateOf<Int?>(null) }
    val mic = externalMics.firstOrNull { it.id == micId }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }
    val measuring = job?.isActive == true

    fun chooseMic(id: Int?) {
        if (id == micId) return
        micId = id
        // Reference and spots must come from the same microphone, or its colouring stays in the result.
        reference = null
        spots = emptyList()
        measureVolume = null
    }

    // The audio route changes outside the app (connecting the speaker in system settings), so the
    // setup page watches it instead of asking the user to come back and retry.
    LaunchedEffect(step) {
        while (step == TuneStep.SETUP) {
            bluetooth = meter.outputIsBluetooth()
            val found = meter.externalMics()
            // A newly plugged-in microphone is almost certainly the one the user wants to measure with.
            val plugged = found.firstOrNull { new -> externalMics.none { it.id == new.id } }
            externalMics = found
            when {
                plugged != null -> chooseMic(plugged.id)
                found.none { it.id == micId } -> chooseMic(null)
            }
            delay(1_000)
        }
    }


    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            userVolume?.takeIf { it != speaker.state.value.volume }?.let(speaker::setVolume)
            if (!kept) {
                val (mode, bands) = original
                if (mode == EqMode.USER) speaker.setCustomEq(bands ?: EqCurve.FLAT) else mode?.let(speaker::setEq)
            }
        }
    }

    @Suppress("MissingPermission") // only reachable past the setup page, which requires hasMic
    fun measure(isReference: Boolean, then: (DoubleArray) -> Unit) {
        error = null
        job = scope.launch {
            suspend fun volume(v: Int?) {
                if (v != null && v != speaker.state.value.volume) { speaker.setVolume(v); delay(500) }
            }
            try {
                if (speaker.state.value.eq != EqMode.USER || speaker.state.value.eqBands != EqCurve.FLAT) {
                    speaker.setCustomEq(EqCurve.FLAT)
                    delay(800)
                }
                meter.mic = mic
                if (isReference) {
                    volume(userVolume)
                    val response = speaker.fitToMicrophone { meter.measure() }
                    val used = speaker.state.value.volume
                    if (used != measureVolume) spots = emptyList()
                    measureVolume = used
                    then(response)
                } else {
                    volume(measureVolume)
                    then(meter.measure())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MeasurementException) {
                error = e.message
            } catch (e: Exception) {
                error = "Measurement failed: ${e.message}"
            }
        }
    }

    fun go(to: TuneStep) {
        error = null
        if (step == TuneStep.RESULT && to != TuneStep.RESULT) {
            // Leaving the result: the next measurement flattens the EQ anyway, and until then the
            // speaker shouldn't keep playing a correction the user hasn't accepted.
            result = null
            speaker.setCustomEq(EqCurve.FLAT)
        }
        step = to
    }

    // Refitting is cheap and needs no new measurement, so the result page can switch goals live.
    fun fit() {
        val range = if (mic != null) RoomCorrection.EXTERNAL_MIC_RANGE else RoomCorrection.MIN_HZ..RoomCorrection.MAX_HZ
        val fit = RoomCorrection.suggest(reference!!, spots, shapes, place, sound, range)
        result = fit
        hearingCorrection = true
        speaker.setCustomEq(fit.bands)
    }

    fun showResult() {
        userVolume?.takeIf { it != speaker.state.value.volume }?.let(speaker::setVolume)
        go(TuneStep.RESULT)
        fit()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Tune to this room", style = MaterialTheme.typography.titleLarge)
            StepIndicator(step)

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                },
                label = "tune step",
            ) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (page) {
                        TuneStep.INTRO -> {
                            Body("Your phone listens to a few test tones and suggests an EQ that fits your room.")
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Instruction(Icons.Default.Speaker, "Up close")
                                Instruction(Icons.Default.Hearing, "Where you listen")
                                Instruction(Icons.Default.Equalizer, "Hear & save")
                            }
                            HintText("About two minutes. Your current EQ comes back if you stop.")
                        }
                        TuneStep.GOAL -> {
                            ControlLabel("Where is the speaker?")
                            OptionCard(place == Place.INDOORS, Icons.Default.Home, "Indoors", "Walls and corners pile up bass at some spots and cancel it at others. The EQ mostly cuts the peaks.") { place = Place.INDOORS }
                            OptionCard(place == Place.OUTDOORS, Icons.Default.Park, "Outdoors", "No walls to help the bass along, so it thins out with distance. The EQ may lift it a bit more.") { place = Place.OUTDOORS }
                            Spacer(Modifier.height(4.dp))
                            ControlLabel("How should it sound?")
                            SoundGoal.entries.forEach { goal ->
                                OptionCard(sound == goal, goal.icon, goal.label, goal.description) { sound = goal }
                            }
                            HintText("Whatever you pick, the room's own peaks and dips are still evened out. You can switch the sound on the result page without measuring again.")
                        }
                        TuneStep.SETUP -> {
                            Body("A few things before the test tones start.")
                            CheckItem(
                                done = hasMic,
                                icon = Icons.Default.Mic,
                                title = "Microphone access",
                                text = "The phone listens while the speaker plays.",
                                action = if (hasMic) null else ("Allow" to { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }),
                            )
                            CheckItem(
                                done = bluetooth,
                                icon = Icons.Default.Bluetooth,
                                title = "Phone audio plays on ${s.name ?: "the speaker"}",
                                text = when {
                                    bluetooth -> "Connected over Bluetooth."
                                    externalMics.isNotEmpty() -> "The tones come from the phone, so connect it to the speaker over Bluetooth for audio. If your microphone has a headphone output, the phone may be playing there instead."
                                    else -> "The tones come from the phone, so connect it to the speaker over Bluetooth for audio."
                                },
                            )
                            CheckItem(done = null, icon = Icons.AutoMirrored.Filled.VolumeUp, title = "Your usual volume", text = "Set the volume you normally listen at; the EQ is tuned for that level.")
                            CheckItem(done = null, icon = Icons.AutoMirrored.Filled.VolumeOff, title = "A quiet room", text = "Pause other music and ask people to keep talking down for a minute.")
                            Spacer(Modifier.height(4.dp))
                            ControlLabel("Measure with")
                            if (externalMics.isEmpty()) {
                                HintText("The phone's microphone works fine. For a more accurate result, especially in the deep bass, plug in a USB measurement microphone and it shows up here.")
                            } else {
                                OptionCard(mic == null, Icons.Default.Smartphone, "Phone microphone", "Built in. Fine for most rooms.") { chooseMic(null) }
                                externalMics.forEach { m ->
                                    OptionCard(mic?.id == m.id, Icons.Default.SettingsVoice, m.productName.toString().ifBlank { "External microphone" }, "Hears the deep bass and high treble more accurately, so the EQ covers 40 Hz to 16 kHz.") { chooseMic(m.id) }
                                }
                            }
                        }
                        TuneStep.REFERENCE -> {
                            if (mic != null) {
                                Body("Hold the microphone about 50 cm in front of the speaker, at the height of its centre, pointing at it. Keep still until the tones stop, and point it the same way at every spot.")
                            } else {
                                Body("Hold the phone about 50 cm in front of the speaker, at the height of its centre, with the microphone towards it. Keep still until the tones stop.")
                            }
                            HintText("A few rising tones play, from deep bass to high treble. If the first ones are too loud for the microphone, the speaker turns down and plays them again.")
                            if (reference != null && !measuring) Done("Measured. Measure again if something disturbed it.")
                            TurnedDown(measureVolume, userVolume)
                        }
                        TuneStep.SPOTS -> {
                            if (place == Place.OUTDOORS) {
                                Body("Now stand where the crowd will be, a few metres out in front, and hold the phone at ear height.")
                                HintText("Bass changes most with distance outdoors. Measuring 2 or 3 spots, near and further back, gives a steadier result. Shield the phone from wind.")
                            } else {
                                Body("Now stand where people will be, like the sofa or the middle of the dance floor, and hold the phone at ear height.")
                                HintText("Each spot hears the room a little differently. Measuring 2 or 3 spots, a metre or more apart, gives a steadier result.")
                            }
                            if (mic != null) HintText("Hold the microphone at ear height, pointing at the speaker like before.")
                            SpotProgress(spots.size, measuring)
                            TurnedDown(measureVolume, userVolume)
                        }
                        TuneStep.RESULT -> result?.let { r ->
                            Choice(SoundGoal.entries, sound, { it.label }) { if (it != sound) { sound = it; fit() } }
                            val aim = if (sound == SoundGoal.BALANCED) "an even sound" else sound.label.lowercase()
                            if (RoomCorrection.isNegligible(r)) {
                                Body("The sound at your spots is already within about ±${r.beforeRmsDb.roundToInt()} dB of $aim. A correction wouldn't make an audible difference, so there's nothing to change.")
                            } else {
                                Body("At the spots you measured, the sound is about ±${"%.1f".format(r.beforeRmsDb)} dB away from $aim. With this EQ that drops to about ±${"%.1f".format(r.afterRmsDb)} dB.")
                            }
                            RoomPlot(r, shapes)
                            PlotLegend(showTarget = sound != SoundGoal.BALANCED)
                            EqGraph(r.bands, onChange = null)
                            OutlinedButton(onClick = {
                                hearingCorrection = !hearingCorrection
                                speaker.setCustomEq(if (hearingCorrection) r.bands else EqCurve.FLAT)
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (hearingCorrection) "Playing with EQ · tap to hear without" else "Playing without EQ · tap to hear with")
                            }
                        }
                    }
                }
            }

            if (measuring) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val back = TuneStep.entries.getOrNull(step.ordinal - 1)
                when {
                    step == TuneStep.RESULT -> TextButton(onClick = { reference = null; spots = emptyList(); measureVolume = null; go(TuneStep.REFERENCE) }) { Text("Measure again") }
                    back != null -> TextButton(onClick = { go(back) }, enabled = !measuring) { Text("Back") }
                    else -> TextButton(onClick = onDismiss) { Text("Cancel") }
                }
                Spacer(Modifier.weight(1f))
                when (step) {
                    TuneStep.INTRO -> Button(onClick = { go(TuneStep.GOAL) }) { Text("Start") }
                    TuneStep.GOAL -> Button(onClick = { go(TuneStep.SETUP) }) { Text("Next") }
                    TuneStep.SETUP -> Button(onClick = { go(TuneStep.REFERENCE) }, enabled = hasMic && bluetooth) { Text("Next") }
                    TuneStep.REFERENCE -> if (reference == null || measuring) {
                        MeasureButton(measuring, "Measure") { measure(isReference = true) { reference = it; go(TuneStep.SPOTS) } }
                    } else {
                        OutlinedButton(onClick = { measure(isReference = true) { reference = it } }) { Text("Redo") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { go(TuneStep.SPOTS) }) { Text("Next") }
                    }
                    TuneStep.SPOTS -> {
                        if (spots.size < MAX_SPOTS) {
                            val label = if (spots.isEmpty()) "Measure" else "Spot ${spots.size + 1}"
                            if (spots.isEmpty() || measuring) {
                                MeasureButton(measuring, label) { measure(isReference = false) { spots = spots + it } }
                            } else {
                                OutlinedButton(onClick = { measure(isReference = false) { spots = spots + it } }) { Text(label) }
                            }
                        }
                        if (spots.isNotEmpty() && !measuring) {
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = ::showResult) { Text("See result") }
                        }
                    }
                    TuneStep.RESULT -> {
                        val r = result
                        if (r == null || RoomCorrection.isNegligible(r)) {
                            Button(onClick = onDismiss) { Text("Done") }
                        } else {
                            Button(onClick = { naming = true }) { Text("Save as preset") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (naming) {
        val initial = if (sound == SoundGoal.BALANCED) "Room" else "Room · ${sound.label}"
        PaletteNameDialog("Name your custom EQ", initial = initial, confirm = "Save", placeholder = "My EQ", onDismiss = { naming = false }) { name ->
            result?.let { store.add(name, it.bands); speaker.setCustomEq(it.bands) }
            kept = true
            naming = false
            onDismiss()
        }
    }
}

/** One segment per step, filled up to the current one, with "Step n of m · title" underneath. */
@Composable
private fun StepIndicator(step: TuneStep) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TuneStep.entries.forEach {
                val color by animateColorAsState(if (it <= step) colors.primary else colors.surfaceContainerHighest, label = "step segment")
                Box(Modifier.weight(1f).height(4.dp).background(color, RoundedCornerShape(2.dp)))
            }
        }
        Text(
            "Step ${step.ordinal + 1} of ${TuneStep.entries.size} · ${step.title}",
            style = MaterialTheme.typography.titleSmall,
            color = colors.primary,
        )
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

/** One of the intro's three steps: an icon over a short label, sharing the row equally. */
@Composable
private fun RowScope.Instruction(icon: ImageVector, text: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IconBadge(icon)
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * A pre-flight item. [done] true or false is checked by the app (only false blocks the wizard);
 * null is advice the app can't verify, shown without a tick.
 */
@Composable
private fun CheckItem(done: Boolean?, icon: ImageVector, title: String, text: String, action: Pair<String, () -> Unit>? = null) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        when (done) {
            true -> Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = colors.primary, modifier = Modifier.size(24.dp))
            false -> Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "Not done", tint = colors.error, modifier = Modifier.size(24.dp))
            null -> Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        action?.let { (label, onClick) -> OutlinedButton(onClick = onClick) { Text(label) } }
    }
}

private val SoundGoal.label get() = when (this) {
    SoundGoal.BALANCED -> "Balanced"
    SoundGoal.BASS -> "Thick bass"
    SoundGoal.VOICES -> "Clear voices"
}

private val SoundGoal.description get() = when (this) {
    SoundGoal.BALANCED -> "As even as the room allows, close to how the speaker is voiced."
    SoundGoal.BASS -> "A deliberate lift below about 120 Hz for parties, on top of an evened-out room."
    SoundGoal.VOICES -> "Less boom, a little more presence. For speech, podcasts and acoustic sets."
}

private val SoundGoal.icon get() = when (this) {
    SoundGoal.BALANCED -> Icons.Default.Tune
    SoundGoal.BASS -> Icons.Default.GraphicEq
    SoundGoal.VOICES -> Icons.Default.RecordVoiceOver
}

/** A selectable card with an icon, a title and a line of explanation; one of a group. */
@Composable
private fun OptionCard(selected: Boolean, icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Says so when the speaker was turned down for measuring, so the quieter tones aren't a surprise. */
@Composable
private fun TurnedDown(measureVolume: Int?, userVolume: Int?) {
    if (measureVolume == null || userVolume == null || measureVolume >= userVolume) return
    HintText("Turned the speaker down to ${measureVolume * 100 / 255} % while measuring, so the phone's microphone doesn't overload. Your volume comes back for the result.")
}

@Composable
private fun Done(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A tick per measured spot, the one being measured, and the optional ones still open. */
@Composable
private fun SpotProgress(measured: Int, measuring: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(MAX_SPOTS) { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when {
                    i < measured -> Icons.Default.CheckCircle to colors.primary
                    i == measured && measuring -> Icons.Default.Mic to colors.primary
                    else -> Icons.Default.RadioButtonUnchecked to colors.outline
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text("Spot ${i + 1}", style = MaterialTheme.typography.labelLarge, color = if (i < measured) colors.onSurface else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MeasureButton(measuring: Boolean, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !measuring) {
        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (measuring) "Listening…" else label)
    }
}

/**
 * What the room does (grey), the chosen sound when it isn't flat (dashed) and what the room sounds
 * like with the suggested EQ (accent), 40 Hz to 16 kHz.
 */
@Composable
private fun RoomPlot(r: RoomCorrection.Result, shapes: List<BandResponse>) {
    val colors = MaterialTheme.colorScheme
    val grid = Spectrum.GRID
    val after = DoubleArray(grid.size) { g -> r.roomDb[g] + shapes.indices.sumOf { shapes[it].responseDb(grid[g], r.bands[it].toDouble()) } }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(176.dp)) {
        val lo = log10(grid.first()); val hi = log10(grid.last())
        fun x(hz: Double) = ((log10(hz) - lo) / (hi - lo) * size.width).toFloat()
        val plotH = size.height - 16.dp.toPx() // room for the frequency labels underneath
        fun y(db: Double) = (plotH / 2 - db.coerceIn(-15.0, 15.0) / 15 * plotH / 2).toFloat()
        drawLine(colors.outlineVariant, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
        for ((hz, label) in listOf(100.0 to "100 Hz", 1_000.0 to "1 kHz", 10_000.0 to "10 kHz")) {
            drawLine(colors.outlineVariant.copy(alpha = 0.4f), Offset(x(hz), 0f), Offset(x(hz), plotH), 1.dp.toPx())
            val text = measurer.measure(label, labelStyle)
            drawText(text, topLeft = Offset(x(hz) - text.size.width / 2f, plotH + 2.dp.toPx()))
        }
        fun line(values: DoubleArray, color: androidx.compose.ui.graphics.Color, dashed: Boolean = false) {
            val p = Path()
            grid.forEachIndexed { g, hz -> if (g == 0) p.moveTo(x(hz), y(values[g])) else p.lineTo(x(hz), y(values[g])) }
            val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null
            drawPath(p, color, style = Stroke(2.dp.toPx(), pathEffect = dash))
        }
        line(r.roomDb, colors.onSurfaceVariant.copy(alpha = 0.6f))
        if (r.targetDb.any { it != 0.0 }) line(r.targetDb, colors.tertiary, dashed = true)
        line(after, colors.primary)
    }
}

@Composable
private fun PlotLegend(showTarget: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("— Room", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showTarget) Text("- - Aim", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        Text("— With EQ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
