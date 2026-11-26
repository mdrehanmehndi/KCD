package com.nx.android.game.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.core.ui.components.CapsuleButton
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.data.DevSync
import com.nx.android.game.Native
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.json.JSONObject

data class DevQaStatus(
    val active: Boolean = false,
    val driver: String = "VULKAN",
    val shadersLoaded: Long = 0,
    val capturedShaders: Int = 0,
    val autoFlicker: Boolean = false,
    val recording: Boolean = false,
    val replaying: Boolean = false,
    val hasRec: Boolean = false,
    val recFrames: Long = 0,
    val replayIdx: Long = 0,
    val replayTotal: Long = 0,
    val frame: Long = 0,
    val lastGlitchFrame: Long = -1,
    val glitchesSaved: Long = 0,
) {
    companion object {
        fun parse(json: String): DevQaStatus = runCatching {
            val o = JSONObject(json)
            DevQaStatus(
                active = o.optBoolean("active", false),
                driver = o.optString("driver", "VULKAN"),
                shadersLoaded = o.optLong("shadersLoaded", 0),
                capturedShaders = o.optInt("capturedShaders", 0),
                autoFlicker = o.optBoolean("autoFlicker", false),
                recording = o.optBoolean("recording", false),
                replaying = o.optBoolean("replaying", false),
                hasRec = o.optBoolean("hasRec", false),
                recFrames = o.optLong("recFrames", 0),
                replayIdx = o.optLong("replayIdx", 0),
                replayTotal = o.optLong("replayTotal", 0),
                frame = o.optLong("frame", 0),
                lastGlitchFrame = o.optLong("lastGlitchFrame", -1),
                glitchesSaved = o.optLong("glitchesSaved", 0),
            )
        }.getOrDefault(DevQaStatus())
    }
}

data class DevBuildInfo(
    val titleId: String,
    val soSizeBytes: Long,
    val soMtimeMs: Long,
    val packDir: String,
    val devDir: String,
)

/**
 * Draggable on-screen corner pill for 1-tap glitch captures during live gameplay.
 * Does NOT pause or open the drawer: triggers capture first and flashes green ONLY
 * after `N+1` is captured and written to disk.
 */
@Composable
fun FloatingGlitchPill(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var awaitingBaseline by remember { mutableStateOf<Long?>(null) }
    var savedLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(awaitingBaseline) {
        val base = awaitingBaseline ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            val st = DevQaStatus.parse(runCatching { Native.qaStatus() }.getOrDefault(""))
            if (st.glitchesSaved > base && st.lastGlitchFrame >= 0) {
                savedLabel = "Saved frame_${st.lastGlitchFrame}"
                awaitingBaseline = null
                return@LaunchedEffect
            }
            delay(40)
        }
        awaitingBaseline = null
    }

    LaunchedEffect(savedLabel) {
        if (savedLabel != null) {
            delay(800)
            savedLabel = null
        }
    }

    val bg by animateColorAsState(
        targetValue = when {
            savedLabel != null -> Color(0xFF235D3A)
            awaitingBaseline != null -> NxPalette.BrandHighlight
            else -> NxPalette.Brand.copy(alpha = 0.90f)
        },
        label = "glitchPillBg",
    )
    val border by animateColorAsState(
        targetValue = if (savedLabel != null) NxPalette.Ready else NxPalette.BrandAccent,
        label = "glitchPillBorder",
    )

    val text = savedLabel
        ?: if (awaitingBaseline != null) "Capturing..." else stringResource(R.string.dev_pill_label)

    Box(
        modifier
            .displayCutoutPadding()
            .padding(top = 14.dp, end = 16.dp)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(enabled = awaitingBaseline == null) {
                val cur = DevQaStatus.parse(runCatching { Native.qaStatus() }.getOrDefault(""))
                awaitingBaseline = cur.glitchesSaved
                runCatching { Native.qaFlagGlitch("floating_pill") }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.CameraAlt, null, tint = NxPalette.Cream, modifier = Modifier.size(14.dp))
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = NxPalette.Cream)
        }
    }
}

@Composable
internal fun DevRows(
    buildInfo: DevBuildInfo,
    glitchPillShown: Boolean,
    onGlitchPillShown: (Boolean) -> Unit,
    autoFlicker: Boolean,
    onAutoFlicker: (Boolean) -> Unit,
    autoReplayOnDevLaunch: Boolean,
    onAutoReplayOnDevLaunch: (Boolean) -> Unit,
    onFlagGlitchNow: () -> Unit,
    onRestartWithEnv: (Map<String, String?>) -> Unit,
) {
    val qaSupported = remember { runCatching { Native.qaSupported() }.getOrDefault(false) }
    var status by remember {
        mutableStateOf(
            if (qaSupported) DevQaStatus.parse(runCatching { Native.qaStatus() }.getOrDefault(""))
            else DevQaStatus()
        )
    }
    var passdumpQueued by remember { mutableStateOf(false) }
    var flushedCount by remember { mutableIntStateOf(-1) }

    val packFile = remember(buildInfo.packDir) { File(buildInfo.packDir) }
    val initialEnv = remember(buildInfo.packDir) { DevSync.readEnvFlags(File(packFile, "env.txt")) }
    var nanSanitize by remember { mutableStateOf(initialEnv["DM_VK_NAN_SANITIZE"] == "1") }
    var expand320 by remember { mutableStateOf(initialEnv["DM_VK_EXPAND_320X180"] == "1") }
    var cbDraws by remember { mutableStateOf(initialEnv["DM_VK_CB_DRAWS"] == "1") }
    var subgroupPin64 by remember { mutableStateOf(initialEnv["DM_VK_SUBGROUP_PIN"] == "64") }

    LaunchedEffect(qaSupported) {
        while (qaSupported) {
            runCatching { status = DevQaStatus.parse(Native.qaStatus()) }
            delay(350)
        }
    }

    // ---- Build & Sync Header ----
    GroupLabel("BUILD & DEV SYNC")
    val sizeMb = buildInfo.soSizeBytes / 1048576.0
    val stamp = remember(buildInfo.soMtimeMs) {
        if (buildInfo.soMtimeMs > 0) {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(buildInfo.soMtimeMs))
        } else "unknown"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NxPalette.SurfaceElevated)
            .border(1.dp, NxPalette.Brand.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "libdmplay.so: %.2f MB (%s)".format(Locale.US, sizeMb, stamp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = NxPalette.Cream,
            )
            Text(
                "Driver: ${status.driver}  ·  Shaders: ${status.shadersLoaded} (captured: ${status.capturedShaders})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Dev-Sync: ACTIVE (${buildInfo.titleId.lowercase()})  ·  Glitches: ${status.glitchesSaved}",
                style = MaterialTheme.typography.bodySmall,
                color = NxPalette.Ready,
            )
        }
    }

    // ---- Glitch Capture ----
    Spacer(Modifier.height(4.dp))
    GroupLabel("GLITCH CAPTURE")
    CapsuleButton(
        "Flag Glitch Now (after 2 frames)",
        onClick = onFlagGlitchNow,
        enabled = qaSupported,
        modifier = Modifier.fillMaxWidth(),
    )
    ToggleRow(
        Icons.Filled.CameraAlt,
        "Show Floating Glitch Pill",
        "Draggable corner button that captures N-1..N+1 without pausing",
        checked = glitchPillShown,
        enabled = qaSupported,
        onChange = onGlitchPillShown,
    )
    ToggleRow(
        Icons.Filled.Warning,
        "Auto-Flicker Detector (16x16 V-shape)",
        "2.0s cooldown + <2 GB free-space guard (OFF by default)",
        checked = autoFlicker,
        enabled = qaSupported,
        onChange = { on ->
            onAutoFlicker(on)
            runCatching { Native.qaSetAutoFlicker(on) }
        },
    )

    // ---- Input Stress Replay ----
    Spacer(Modifier.height(4.dp))
    GroupLabel("INPUT STRESS REPLAY (stress_test.rec)")
    val recLabel = when {
        status.recording -> "Recording inputs (${status.recFrames} frames)"
        status.replaying -> "Replaying frame ${status.replayIdx}/${status.replayTotal}"
        status.hasRec -> "stress_test.rec ready"
        else -> "No stress_test.rec staged yet"
    }
    Text(
        recLabel,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            status.recording -> NxPalette.Danger
            status.replaying -> NxPalette.Ready
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallDevButton(
            text = "Record",
            icon = Icons.Filled.RadioButtonChecked,
            active = status.recording,
            enabled = qaSupported && !status.replaying,
            modifier = Modifier.weight(1f),
        ) { runCatching { Native.qaStartRecord() } }
        SmallDevButton(
            text = "Stop",
            icon = Icons.Filled.Stop,
            active = false,
            enabled = qaSupported && (status.recording || status.replaying),
            modifier = Modifier.weight(1f),
        ) { runCatching { Native.qaStopInput() } }
        SmallDevButton(
            text = "Play Replay",
            icon = Icons.Filled.PlayArrow,
            active = status.replaying,
            enabled = qaSupported && status.hasRec && !status.recording,
            modifier = Modifier.weight(1.2f),
        ) { runCatching { Native.qaStartReplay() } }
    }
    ToggleRow(
        Icons.Filled.PlayArrow,
        "Auto-Replay on Dev Launch",
        "Auto-starts stress_test.rec when launched via Dev mode or am start",
        checked = autoReplayOnDevLaunch,
        enabled = qaSupported,
        onChange = onAutoReplayOnDevLaunch,
    )

    // ---- GPU Debug & Env ----
    Spacer(Modifier.height(4.dp))
    GroupLabel("GPU DEBUG & ENV.TXT")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallDevButton(
            text = if (passdumpQueued) "Passdump Queued" else "Dump Frame Passes",
            icon = Icons.Filled.Layers,
            active = passdumpQueued,
            enabled = qaSupported,
            modifier = Modifier.weight(1f),
        ) {
            if (runCatching { Native.qaDumpPasses() }.getOrDefault(false)) passdumpQueued = true
        }
        SmallDevButton(
            text = if (flushedCount >= 0) "Flushed $flushedCount" else "Flush Shaders",
            icon = Icons.Filled.Build,
            active = false,
            enabled = qaSupported,
            modifier = Modifier.weight(1f),
        ) {
            flushedCount = runCatching { Native.qaFlushShaders() }.getOrDefault(0)
        }
    }
    ToggleRow(Icons.Filled.Tune, "DM_VK_NAN_SANITIZE", null, checked = nanSanitize) { nanSanitize = it }
    ToggleRow(Icons.Filled.Tune, "DM_VK_EXPAND_320X180", null, checked = expand320) { expand320 = it }
    ToggleRow(Icons.Filled.Tune, "DM_VK_CB_DRAWS", null, checked = cbDraws) { cbDraws = it }
    ToggleRow(Icons.Filled.Tune, "DM_VK_SUBGROUP_PIN=64", null, checked = subgroupPin64) { subgroupPin64 = it }
    OutlinedButton(
        onClick = {
            onRestartWithEnv(
                mapOf(
                    "DM_VK_NAN_SANITIZE" to if (nanSanitize) "1" else null,
                    "DM_VK_EXPAND_320X180" to if (expand320) "1" else null,
                    "DM_VK_CB_DRAWS" to if (cbDraws) "1" else null,
                    "DM_VK_SUBGROUP_PIN" to if (subgroupPin64) "64" else null,
                )
            )
        },
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = NxPalette.Brand.copy(alpha = 0.55f),
            contentColor = NxPalette.Cream,
        ),
        border = BorderStroke(1.dp, NxPalette.BrandHighlight),
        modifier = Modifier.fillMaxWidth().height(42.dp),
    ) {
        Icon(Icons.Filled.RestartAlt, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Save env.txt & Restart", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallDevButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) NxPalette.BrandHighlight else NxPalette.SurfaceElevated,
            contentColor = NxPalette.Cream,
            disabledContentColor = NxPalette.Unbuilt,
        ),
        border = BorderStroke(1.dp, if (active) NxPalette.BrandAccent else NxPalette.Brand.copy(alpha = 0.65f)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = modifier.height(38.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 11.sp, maxLines = 1)
    }
}
