package dev.boksremote.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.boksremote.audio.BandShape
import dev.boksremote.audio.MeasurementException
import dev.boksremote.audio.RoomCorrection
import dev.boksremote.audio.RoomMeter
import dev.boksremote.audio.Spectrum
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

/**
 * Guided room measurement. A reference close to the speaker, then up to three listening spots;
 * the difference is what the room does, and [RoomCorrection] fits the six bands to undo it.
 * Measuring runs on a flat curve; closing without keeping the result puts the old EQ back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomCorrectionSheet(
    s: SpeakerState,
    speaker: SpeakerController,
    store: EqPresetStore,
    shapes: List<BandShape>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meter = remember { RoomMeter(context) }
    val original = remember { s.eq to s.eqBands }
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
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }
    val measuring = job?.isActive == true

    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            if (!kept) {
                val (mode, bands) = original
                if (mode == EqMode.USER) speaker.setCustomEq(bands ?: EqCurve.FLAT) else mode?.let(speaker::setEq)
            }
        }
    }

    @Suppress("MissingPermission") // only reachable once hasMic is true
    fun measure(then: (DoubleArray) -> Unit) {
        error = null
        job = scope.launch {
            try {
                if (speaker.state.value.eq != EqMode.USER || speaker.state.value.eqBands != EqCurve.FLAT) {
                    speaker.setCustomEq(EqCurve.FLAT)
                    delay(800)
                }
                then(meter.measure())
            } catch (e: CancellationException) {
                throw e
            } catch (e: MeasurementException) {
                error = e.message
            } catch (e: Exception) {
                error = "Measurement failed: ${e.message}"
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Tune to this room", style = MaterialTheme.typography.titleLarge)
            val r = result
            when {
                !hasMic -> {
                    Body("Boks Remote listens with the phone's microphone while the speaker plays test tones, then suggests a custom EQ that evens out what the room does to the sound. Nothing is recorded or kept.")
                    Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                }
                !meter.outputIsBluetooth() -> {
                    Body("Play the phone's audio through ${s.name ?: "the speaker"} over Bluetooth first. The test tones come from the phone, so they have to reach the speaker.")
                }
                reference == null -> {
                    StepLabel(1, "Close to the speaker")
                    Body("Set the volume you normally listen at. Hold the phone about 50 cm in front of the speaker, at the height of its centre, and keep still. Each measurement plays two rising tones from deep bass to high treble, about 10 seconds in all.")
                    MeasureButton(measuring, "Measure") { measure { reference = it } }
                }
                r == null -> {
                    StepLabel(2, "Where people will listen")
                    Body("Stand where people will be and hold the phone at ear height. Measuring 2 or 3 different spots gives a steadier result than one.")
                    if (spots.isNotEmpty()) HintText("${spots.size} of $MAX_SPOTS spots measured")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (spots.size < MAX_SPOTS) {
                            MeasureButton(measuring, if (spots.isEmpty()) "Measure spot" else "Measure another spot") {
                                measure { spots = spots + it }
                            }
                        }
                        if (spots.isNotEmpty() && !measuring) {
                            OutlinedButton(onClick = {
                                val fit = RoomCorrection.suggest(reference!!, spots, shapes)
                                result = fit
                                hearingCorrection = true
                                speaker.setCustomEq(fit.bands)
                            }) { Text("See result") }
                        }
                    }
                }
                else -> {
                    if (RoomCorrection.isNegligible(r)) {
                        Body("Your room barely colours the sound here (about ±${r.beforeRmsDb.roundToInt()} dB). A correction wouldn't make an audible difference, so there's nothing to change.")
                    } else {
                        Body("The room shifts the sound by about ±${"%.1f".format(r.beforeRmsDb)} dB at the spots you measured. With this EQ that drops to about ±${"%.1f".format(r.afterRmsDb)} dB.")
                    }
                    RoomPlot(r, shapes)
                    PlotLegend()
                    EqGraph(r.bands, onChange = null)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            hearingCorrection = !hearingCorrection
                            speaker.setCustomEq(if (hearingCorrection) r.bands else EqCurve.FLAT)
                        }, modifier = Modifier.weight(1f)) { Text(if (hearingCorrection) "Hear without" else "Hear with") }
                        Button(onClick = { naming = true }, modifier = Modifier.weight(1f)) { Text("Save as preset") }
                    }
                    TextButton(onClick = { reference = null; spots = emptyList(); result = null }) { Text("Measure again") }
                }
            }
            if (measuring) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (naming) {
        PaletteNameDialog("Name your custom EQ", initial = "Room", confirm = "Save", placeholder = "My EQ", onDismiss = { naming = false }) { name ->
            result?.let { store.add(name, it.bands); speaker.setCustomEq(it.bands) }
            kept = true
            naming = false
            onDismiss()
        }
    }
}

@Composable
private fun StepLabel(n: Int, text: String) {
    Text("Step $n · $text", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun MeasureButton(measuring: Boolean, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !measuring) {
        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (measuring) "Listening…" else label)
    }
}

/** What the room does (grey) and what is left with the suggested EQ (accent), 40 Hz to 16 kHz. */
@Composable
private fun RoomPlot(r: RoomCorrection.Result, shapes: List<BandShape>) {
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
        fun line(values: DoubleArray, color: androidx.compose.ui.graphics.Color) {
            val p = Path()
            grid.forEachIndexed { g, hz -> if (g == 0) p.moveTo(x(hz), y(values[g])) else p.lineTo(x(hz), y(values[g])) }
            drawPath(p, color, style = Stroke(2.dp.toPx()))
        }
        line(r.roomDb, colors.onSurfaceVariant.copy(alpha = 0.6f))
        line(after, colors.primary)
    }
}

@Composable
private fun PlotLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("— Room", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("— With EQ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
