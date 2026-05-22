package com.appwizard.airhockey

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appwizard.airhockey.data.web.FirebaseConfigInitializer
import com.appwizard.airhockey.data.web.FirestoreWebConfigRepository
import com.appwizard.airhockey.ui.theme.AirHockeyTheme
import com.appwizard.airhockey.ui.viewmodel.WebGateViewModel
import com.appwizard.airhockey.ui.web.AdvancedWebViewScreen
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

private const val WIN_SCORE = 11
private const val PLAYER_PUNCH = 0.24f
private const val PUCK_FRICTION = 0.999f
private const val MIN_PUCK_SPEED_RATIO = 0.42f
private const val MAX_PUCK_SPEED_RATIO = 1.55f
private const val FEEDBACK_EMAIL = "support@neon-hockey.example"

private val AppBackground = Brush.verticalGradient(
    colors = listOf(Color(0xFF07110E), Color(0xFF102B23), Color(0xFF06090D))
)
private val PanelBrush = Brush.verticalGradient(
    colors = listOf(Color(0xCC163B32), Color(0xB0091518))
)
private val PrimaryCyan = Color(0xFF57E7FF)
private val PrimaryGreen = Color(0xFF7CFFB2)
private val PuckWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFFB8C7C2)
private val WarningAmber = Color(0xFFFFC857)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AirHockeyTheme {
                AirHockeyApp()
            }
        }
    }
}

private enum class AppScreen {
    MENU,
    SETTINGS,
    QR_SCANNER,
    LEADERBOARD,
    PLAYING,
    RESULT
}

private enum class GameMode(
    val title: String,
    val bottomLabel: String,
    val topLabel: String
) {
    CPU("Vs CPU", "You", "CPU"),
    PVP("2 Players", "P1", "P2")
}

private enum class Difficulty(
    val title: String,
    val aiSpeedMultiplier: Float,
    val aiTracking: Float,
    val aiPunch: Float
) {
    EASY("Easy", 0.9f, 0.18f, 0.26f),
    MEDIUM("Medium", 1.15f, 0.24f, 0.34f),
    HARD("Hard", 1.38f, 0.32f, 0.42f)
}

private enum class Side {
    BOTTOM,
    TOP
}

private data class MatchSummary(
    val mode: GameMode,
    val difficulty: Difficulty,
    val winnerSide: Side,
    val bottomScore: Int,
    val topScore: Int
) {
    val winnerLabel: String
        get() = when (mode) {
            GameMode.CPU -> if (winnerSide == Side.BOTTOM) "You Win" else "CPU Wins"
            GameMode.PVP -> if (winnerSide == Side.BOTTOM) "Player 1 Wins" else "Player 2 Wins"
        }
}

private data class Vec2(var x: Float, var y: Float)

private sealed interface GameEvent {
    data object Hit : GameEvent
    data object Goal : GameEvent
    data class MatchEnd(val winnerSide: Side, val bottomScore: Int, val topScore: Int) : GameEvent
}

private class GameState {
    var fieldWidth by mutableFloatStateOf(1f)
    var fieldHeight by mutableFloatStateOf(1f)

    var bottomScore by mutableIntStateOf(0)
    var topScore by mutableIntStateOf(0)

    var bottomPaddle by mutableStateOf(Vec2(0f, 0f))
    var topPaddle by mutableStateOf(Vec2(0f, 0f))
    var puck by mutableStateOf(Vec2(0f, 0f))

    var bottomPrev by mutableStateOf(Vec2(0f, 0f))
    var topPrev by mutableStateOf(Vec2(0f, 0f))

    var puckVelocity = Vec2(0f, 0f)

    var initialized by mutableStateOf(false)
    var isMatchEnded by mutableStateOf(false)
    var hitSoundCooldown by mutableFloatStateOf(0f)
    var puckTrail by mutableStateOf(List(8) { Offset.Zero })

    fun resetRound(scoredSide: Side? = null) {
        if (!initialized) return

        if (scoredSide == Side.BOTTOM) bottomScore += 1
        if (scoredSide == Side.TOP) topScore += 1

        val centerX = fieldWidth / 2f
        val centerY = fieldHeight / 2f

        puck = Vec2(centerX, centerY)
        bottomPaddle = Vec2(centerX, fieldHeight * 0.82f)
        topPaddle = Vec2(centerX, fieldHeight * 0.18f)
        bottomPrev = bottomPaddle.copy()
        topPrev = topPaddle.copy()

        val horizontalDirection = if (Random.nextBoolean()) 1f else -1f
        val verticalDirection = if (Random.nextBoolean()) 1f else -1f
        val baseSpeed = fieldHeight * 0.62f
        puckVelocity = Vec2(horizontalDirection * baseSpeed * 0.35f, verticalDirection * baseSpeed)

        val centerOffset = Offset(centerX, centerY)
        puckTrail = List(8) { centerOffset }
    }

    fun fullReset() {
        bottomScore = 0
        topScore = 0
        isMatchEnded = false
        hitSoundCooldown = 0f
        resetRound(scoredSide = null)
    }

    fun pushTrailPoint() {
        val point = Offset(puck.x, puck.y)
        puckTrail = listOf(point) + puckTrail.dropLast(1)
    }

    fun bottomScoreInt(): Int = bottomScore

    fun topScoreInt(): Int = topScore
}

private class AudioFx {
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)

    fun playHit() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
    }

    fun playGoal() {
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 120)
    }

    fun playWin() {
        tone.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 180)
    }

    fun playLose() {
        tone.startTone(ToneGenerator.TONE_CDMA_LOW_L, 180)
    }

    fun release() {
        tone.release()
    }
}

private class HapticFx(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun pulse(durationMs: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    fun hit() = pulse(durationMs = 12L, amplitude = 60)

    fun goal() = pulse(durationMs = 28L, amplitude = 120)

    fun finish() = pulse(durationMs = 64L, amplitude = 180)
}

@Composable
private fun AirHockeyApp() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val store = remember(context) { GameStatsStore(context) }
    val webGateViewModel: WebGateViewModel = viewModel(
        factory = remember(context) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WebGateViewModel(
                        webConfigRepository = FirestoreWebConfigRepository(
                            firebaseConfigInitializer = FirebaseConfigInitializer(context.applicationContext)
                        ),
                        context = context.applicationContext
                    ) as T
                }
            }
        }
    )
    val webGateState by webGateViewModel.appState.collectAsState()

    when (val state = webGateState) {
        WebGateViewModel.AppState.Loading -> {
            AppScreenFrame {
                CircularProgressIndicator(color = PrimaryCyan)
            }
            return
        }
        is WebGateViewModel.AppState.WebView -> {
            AdvancedWebViewScreen(initialUrl = state.url)
            return
        }
        WebGateViewModel.AppState.NormalApp -> Unit
    }

    var gameMode by rememberSaveable { mutableStateOf(GameMode.CPU) }
    var difficulty by rememberSaveable { mutableStateOf(Difficulty.MEDIUM) }
    var appScreen by rememberSaveable { mutableStateOf(AppScreen.MENU) }
    var gameSeed by rememberSaveable { mutableStateOf(0) }

    var settings by remember { mutableStateOf(store.loadSettings()) }
    var stats by remember { mutableStateOf(store.loadStats()) }
    var topResults by remember { mutableStateOf(store.loadTopResults()) }
    var matchSummary by remember { mutableStateOf<MatchSummary?>(null) }

    val audio = remember { AudioFx() }
    val haptic = remember(context) { HapticFx(context) }

    DisposableEffect(Unit) {
        onDispose { audio.release() }
    }

    when (appScreen) {
        AppScreen.MENU -> {
            MenuScreen(
                mode = gameMode,
                difficulty = difficulty,
                settings = settings,
                stats = stats,
                onModeChange = { gameMode = it },
                onDifficultyChange = { difficulty = it },
                onStart = {
                    gameSeed += 1
                    appScreen = AppScreen.PLAYING
                },
                onOpenSettings = { appScreen = AppScreen.SETTINGS },
                onOpenScanner = { appScreen = AppScreen.QR_SCANNER },
                onOpenLeaderboard = { appScreen = AppScreen.LEADERBOARD },
                onExit = { activity?.finish() }
            )
        }

        AppScreen.SETTINGS -> {
            SettingsScreen(
                settings = settings,
                onUpdate = {
                    settings = it
                    store.saveSettings(it)
                },
                onBack = { appScreen = AppScreen.MENU }
            )
        }

        AppScreen.QR_SCANNER -> {
            QrScannerScreen(
                onBack = { appScreen = AppScreen.MENU }
            )
        }

        AppScreen.LEADERBOARD -> {
            LeaderboardScreen(
                results = topResults,
                onBack = { appScreen = AppScreen.MENU }
            )
        }

        AppScreen.PLAYING -> {
            key(gameSeed) {
                AirHockeyGameScreen(
                    mode = gameMode,
                    difficulty = difficulty,
                    settings = settings,
                    audio = audio,
                    haptic = haptic,
                    onBackToMenu = { appScreen = AppScreen.MENU },
                    onMatchEnd = { winnerSide, bottomScore, topScore ->
                        val summary = MatchSummary(
                            mode = gameMode,
                            difficulty = difficulty,
                            winnerSide = winnerSide,
                            bottomScore = bottomScore,
                            topScore = topScore
                        )
                        matchSummary = summary

                        if (gameMode == GameMode.CPU) {
                            stats = store.recordCpuMatch(playerScore = bottomScore, aiScore = topScore)
                        }

                        val topModeLabel = if (gameMode == GameMode.CPU) "CPU" else "P2"
                        topResults = store.recordMatch(
                            MatchRecord(
                                modeTitle = gameMode.title,
                                difficultyTitle = if (gameMode == GameMode.CPU) difficulty.title else "-",
                                bottomLabel = if (gameMode == GameMode.CPU) "You" else "P1",
                                topLabel = topModeLabel,
                                bottomScore = bottomScore,
                                topScore = topScore,
                                winnerLabel = summary.winnerLabel,
                                goalDifference = kotlin.math.abs(bottomScore - topScore),
                                playedAtMillis = System.currentTimeMillis()
                            )
                        )

                        appScreen = AppScreen.RESULT
                    }
                )
            }
        }

        AppScreen.RESULT -> {
            val summary = matchSummary
            if (summary != null) {
                ResultScreen(
                    summary = summary,
                    stats = stats,
                    onPlayAgain = {
                        gameSeed += 1
                        appScreen = AppScreen.PLAYING
                    },
                    onMenu = { appScreen = AppScreen.MENU }
                )
            } else {
                appScreen = AppScreen.MENU
            }
        }
    }
}

@Composable
private fun AppScreenFrame(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(18.dp),
        contentAlignment = contentAlignment
    ) {
        content()
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(listOf(PrimaryCyan.copy(alpha = 0.65f), PrimaryGreen.copy(alpha = 0.28f))),
                shape = RoundedCornerShape(8.dp)
            ),
        color = Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier
                .background(PanelBrush)
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black
        )
        Text(text = subtitle, color = TextMuted, fontSize = 14.sp)
    }
}

@Composable
private fun ArcadeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val colors = if (primary) {
        ButtonDefaults.buttonColors(
            containerColor = PrimaryCyan,
            contentColor = Color(0xFF061012),
            disabledContainerColor = PrimaryCyan.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.75f)
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = Color(0xFF16352F),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF235247),
            disabledContentColor = PrimaryGreen
        )
    }

    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (primary) PrimaryGreen.copy(alpha = 0.65f) else PrimaryCyan.copy(alpha = 0.25f)),
        colors = colors
    ) {
        Text(text = text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ArcadeButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        primary = selected,
        enabled = true
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0x66122420), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = label, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(text = value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun StatusDot(label: String, enabled: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(if (enabled) PrimaryGreen else WarningAmber, CircleShape)
        )
        Text(text = "$label ${if (enabled) "On" else "Off"}", color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun MiniRinkPreview(mode: GameMode) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(118.dp)
            .background(Color(0xFF0E2F27), RoundedCornerShape(8.dp))
            .border(1.dp, PrimaryCyan.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        val w = size.width
        val h = size.height
        val minDim = min(w, h)
        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF1D5A4A), Color(0xFF0E3029)))
        )
        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, h / 2f), Offset(w, h / 2f), minDim * 0.03f)
        drawCircle(Color.White.copy(alpha = 0.22f), minDim * 0.26f, Offset(w / 2f, h / 2f), style = Stroke(minDim * 0.025f))
        drawRect(Color(0xAAE53935), Offset(w * 0.32f, 0f), Size(w * 0.36f, minDim * 0.05f))
        drawRect(Color(0xAA1E88E5), Offset(w * 0.32f, h - minDim * 0.05f), Size(w * 0.36f, minDim * 0.05f))
        drawCircle(Color(0xFFFFA726), minDim * 0.085f, Offset(w * 0.55f, h * 0.24f))
        drawCircle(Color(0xFF42A5F5), minDim * 0.085f, Offset(w * 0.45f, h * 0.77f))
        drawCircle(PuckWhite, minDim * 0.052f, Offset(w * 0.5f, h * if (mode == GameMode.CPU) 0.42f else 0.5f))
    }
}

@Composable
private fun MenuScreen(
    mode: GameMode,
    difficulty: Difficulty,
    settings: AppSettings,
    stats: PlayerStats,
    onModeChange: (GameMode) -> Unit,
    onDifficultyChange: (Difficulty) -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenLeaderboard: () -> Unit,
    onExit: () -> Unit
) {
    val bestScoreText = if (stats.hasBestScore) {
        "+${stats.bestGoalDifference}"
    } else {
        "-"
    }

    AppScreenFrame {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ScreenHeader(
                title = "NEON HOCKEY",
                subtitle = "Fast arcade air hockey"
            )

            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MiniRinkPreview(mode)

                    Text(text = "Game mode", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        GameMode.entries.forEach { entry ->
                            ChoiceChip(
                                text = entry.title,
                                selected = entry == mode,
                                onClick = { onModeChange(entry) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (mode == GameMode.CPU) {
                        Text(text = "CPU difficulty", color = Color.White, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Difficulty.entries.forEach { entry ->
                                ChoiceChip(
                                    text = entry.title,
                                    selected = entry == difficulty,
                                    onClick = { onDifficultyChange(entry) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusDot(label = "Sound", enabled = settings.soundEnabled)
                        StatusDot(label = "Vibration", enabled = settings.vibrationEnabled)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StatTile(label = "Best diff", value = bestScoreText, modifier = Modifier.weight(1f))
                        StatTile(label = "Streak", value = "${stats.currentWinStreak}/${stats.bestWinStreak}", modifier = Modifier.weight(1f))
                        StatTile(label = "CPU W/L", value = "${stats.totalWins}/${stats.totalLosses}", modifier = Modifier.weight(1f))
                    }

                    ArcadeButton(
                        text = "Start Match",
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                        primary = true
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ArcadeButton(text = "Settings", onClick = onOpenSettings, modifier = Modifier.weight(1f))
                        ArcadeButton(text = "Scan QR", onClick = onOpenScanner, modifier = Modifier.weight(1f), primary = true)
                        ArcadeButton(text = "Top-10", onClick = onOpenLeaderboard, modifier = Modifier.weight(1f))
                    }

                    ArcadeButton(text = "Exit", onClick = onExit, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var feedbackText by rememberSaveable { mutableStateOf("") }
    var feedbackImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        feedbackImageUri = uri?.toString()
    }

    AppScreenFrame {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ScreenHeader(title = "SETTINGS", subtitle = "Tune feedback for the table")

            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Sound effects", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(text = "Hits, goals and match end tones", color = TextMuted, fontSize = 12.sp)
                        }
                        ArcadeButton(
                            text = if (settings.soundEnabled) "On" else "Off",
                            onClick = { onUpdate(settings.copy(soundEnabled = !settings.soundEnabled)) },
                            primary = settings.soundEnabled,
                            modifier = Modifier.width(92.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Vibration", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(text = "Small haptic pulses on action", color = TextMuted, fontSize = 12.sp)
                        }
                        ArcadeButton(
                            text = if (settings.vibrationEnabled) "On" else "Off",
                            onClick = { onUpdate(settings.copy(vibrationEnabled = !settings.vibrationEnabled)) },
                            primary = settings.vibrationEnabled,
                            modifier = Modifier.width(92.dp)
                        )
                    }

                    Text(text = "Feedback", color = Color.White, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = feedbackText,
                        onValueChange = { feedbackText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp),
                        label = { Text("Message") },
                        placeholder = { Text("Write what should be improved") },
                        singleLine = false,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0x66122420),
                            unfocusedContainerColor = Color(0x66122420),
                            focusedIndicatorColor = PrimaryCyan,
                            unfocusedIndicatorColor = PrimaryCyan.copy(alpha = 0.35f),
                            focusedLabelColor = PrimaryCyan,
                            unfocusedLabelColor = TextMuted,
                            focusedPlaceholderColor = TextMuted,
                            unfocusedPlaceholderColor = TextMuted
                        )
                    )

                    Text(
                        text = feedbackImageUri?.substringAfterLast('/')?.take(36) ?: "No photo attached",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ArcadeButton(
                            text = "Attach Photo",
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        )
                        ArcadeButton(
                            text = "Send Email",
                            onClick = {
                                sendFeedbackEmail(
                                    context = context,
                                    message = feedbackText,
                                    imageUri = feedbackImageUri?.let(Uri::parse)
                                )
                            },
                            modifier = Modifier.weight(1f),
                            primary = true
                        )
                    }

                    Text(text = "To: $FEEDBACK_EMAIL", color = TextMuted, fontSize = 12.sp)

                    ArcadeButton(text = "Back to Menu", onClick = onBack, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun QrScannerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = remember(context) { context.findActivity() as? LifecycleOwner }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var scannedValue by rememberSaveable { mutableStateOf("") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    AppScreenFrame(contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ScreenHeader(title = "QR SCANNER", subtitle = "Scan future games from the website")

            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (hasCameraPermission && lifecycleOwner != null) {
                        QrCameraPreview(
                            lifecycleOwner = lifecycleOwner,
                            onQrScanned = { value -> scannedValue = value }
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(text = "Camera permission is required", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(text = "Allow camera access to scan game QR codes.", color = TextMuted)
                            ArcadeButton(
                                text = "Allow Camera",
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                modifier = Modifier.fillMaxWidth(),
                                primary = true
                            )
                        }
                    }

                    Text(text = "Last scan", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = scannedValue.ifBlank { "Point the camera at a QR code" },
                        color = if (scannedValue.isBlank()) TextMuted else Color.White,
                        fontSize = 14.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ArcadeButton(
                            text = "Open",
                            onClick = { openScannedQr(context, scannedValue) },
                            modifier = Modifier.weight(1f),
                            primary = scannedValue.startsWith("http://") || scannedValue.startsWith("https://"),
                            enabled = scannedValue.isNotBlank()
                        )
                        ArcadeButton(text = "Back", onClick = onBack, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCameraPreview(
    lifecycleOwner: LifecycleOwner,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(Color.Black, RoundedCornerShape(8.dp))
            .border(1.dp, PrimaryCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                setupQrCamera(
                    context = viewContext,
                    lifecycleOwner = lifecycleOwner,
                    previewView = this,
                    cameraExecutor = cameraExecutor,
                    onQrScanned = onQrScanned
                )
            }
        }
    )
}

@Composable
private fun LeaderboardScreen(results: List<MatchRecord>, onBack: () -> Unit) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    AppScreenFrame(contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ScreenHeader(title = "TOP-10", subtitle = "Best matches by goal difference")

            if (results.isEmpty()) {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text = "No matches yet", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(text = "Finish a match to place it on the board.", color = TextMuted)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.8f)
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(results) { index, record ->
                        val dateText = formatter.format(Date(record.playedAtMillis))
                        val difficultyText = if (record.difficultyTitle == "-") "" else " | ${record.difficultyTitle}"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x99122924), RoundedCornerShape(8.dp))
                                .border(1.dp, PrimaryCyan.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = "${index + 1}. ${record.winnerLabel} (+${record.goalDifference})",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "${record.modeTitle}$difficultyText",
                                color = PrimaryGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${record.bottomLabel} ${record.bottomScore} : ${record.topScore} ${record.topLabel}",
                                color = Color(0xFFE0E0E0),
                                fontSize = 14.sp
                            )
                            Text(text = dateText, color = TextMuted, fontSize = 12.sp)
                        }
                    }
                }
            }

            ArcadeButton(text = "Back to Menu", onClick = onBack, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultScreen(
    summary: MatchSummary,
    stats: PlayerStats,
    onPlayAgain: () -> Unit,
    onMenu: () -> Unit
) {
    AppScreenFrame {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ScreenHeader(title = "MATCH END", subtitle = summary.winnerLabel)

            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "${summary.mode.bottomLabel} ${summary.bottomScore} : ${summary.topScore} ${summary.mode.topLabel}",
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black
                    )

                    if (summary.mode == GameMode.CPU) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            StatTile(label = "Win streak", value = "${stats.currentWinStreak}", modifier = Modifier.weight(1f))
                            StatTile(label = "Best streak", value = "${stats.bestWinStreak}", modifier = Modifier.weight(1f))
                        }
                    }

                    ArcadeButton(text = "Play Again", onClick = onPlayAgain, modifier = Modifier.fillMaxWidth(), primary = true)
                    ArcadeButton(text = "Main Menu", onClick = onMenu, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AirHockeyGameScreen(
    mode: GameMode,
    difficulty: Difficulty,
    settings: AppSettings,
    audio: AudioFx,
    haptic: HapticFx,
    onBackToMenu: () -> Unit,
    onMatchEnd: (winnerSide: Side, bottomScore: Int, topScore: Int) -> Unit
) {
    val game = remember { GameState() }
    var isPaused by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(mode, difficulty, isPaused, settings) {
        var lastTime = 0L
        while (true) {
            withFrameNanos { frameTime ->
                if (!game.initialized) {
                    lastTime = frameTime
                    return@withFrameNanos
                }

                if (isPaused) {
                    lastTime = frameTime
                    return@withFrameNanos
                }

                if (lastTime == 0L) {
                    lastTime = frameTime
                    return@withFrameNanos
                }

                val dt = ((frameTime - lastTime) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
                lastTime = frameTime

                val events = updateGame(game = game, mode = mode, difficulty = difficulty, dt = dt)
                events.forEach { event ->
                    when (event) {
                        GameEvent.Hit -> {
                            if (settings.soundEnabled) audio.playHit()
                            if (settings.vibrationEnabled) haptic.hit()
                        }

                        GameEvent.Goal -> {
                            if (settings.soundEnabled) audio.playGoal()
                            if (settings.vibrationEnabled) haptic.goal()
                        }

                        is GameEvent.MatchEnd -> {
                            if (settings.soundEnabled) {
                                if (mode == GameMode.CPU) {
                                    if (event.winnerSide == Side.BOTTOM) audio.playWin() else audio.playLose()
                                } else {
                                    audio.playWin()
                                }
                            }
                            if (settings.vibrationEnabled) haptic.finish()
                            onMatchEnd(event.winnerSide, event.bottomScore, event.topScore)
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    game.fieldWidth = size.width.toFloat()
                    game.fieldHeight = size.height.toFloat()
                    if (!game.initialized) {
                        game.initialized = true
                        game.fullReset()
                    }
                }
                .pointerInput(mode, isPaused) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (isPaused || !game.initialized || game.isMatchEnded) continue

                            val activeTouches = event.changes.filter { it.pressed }
                            if (activeTouches.isEmpty()) continue

                            val middleY = size.height / 2f
                            val topTouches = activeTouches.filter { it.position.y < middleY }
                            val bottomTouches = activeTouches.filter { it.position.y >= middleY }

                            if (bottomTouches.isNotEmpty()) {
                                moveBottomPaddle(game, averageTouch(bottomTouches))
                            }

                            if (mode == GameMode.PVP && topTouches.isNotEmpty()) {
                                moveTopPaddle(game, averageTouch(topTouches))
                            }

                            activeTouches.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    }
                }
        ) {
            drawField()
            drawGameObjects(game)
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp, start = 12.dp, end = 12.dp)
                .background(Color(0xAA07110E), RoundedCornerShape(8.dp))
                .border(1.dp, PrimaryCyan.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${mode.title} | ${scoreText(mode, game.bottomScoreInt(), game.topScoreInt())}",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArcadeButton(text = "Restart", onClick = { game.fullReset() }, modifier = Modifier.width(96.dp))
                ArcadeButton(text = if (isPaused) "Resume" else "Pause", onClick = { isPaused = !isPaused }, modifier = Modifier.width(96.dp), primary = isPaused)
                ArcadeButton(text = "Menu", onClick = onBackToMenu, modifier = Modifier.width(82.dp))
            }
        }

        if (isPaused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB0000000)),
                contentAlignment = Alignment.Center
            ) {
                GlassPanel {
                    Text(text = "PAUSED", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

private fun averageTouch(changes: List<PointerInputChange>): Offset {
    val avgX = changes.map { it.position.x }.average().toFloat()
    val avgY = changes.map { it.position.y }.average().toFloat()
    return Offset(avgX, avgY)
}

private fun moveBottomPaddle(game: GameState, touch: Offset) {
    if (!game.initialized || game.isMatchEnded) return

    val radius = min(game.fieldWidth, game.fieldHeight) * 0.06f
    val minY = game.fieldHeight * 0.55f
    val maxY = game.fieldHeight - radius

    val clampedX = touch.x.coerceIn(radius, game.fieldWidth - radius)
    val clampedY = touch.y.coerceIn(minY, maxY)

    game.bottomPaddle = Vec2(clampedX, clampedY)
}

private fun moveTopPaddle(game: GameState, touch: Offset) {
    if (!game.initialized || game.isMatchEnded) return

    val radius = min(game.fieldWidth, game.fieldHeight) * 0.06f
    val minY = radius
    val maxY = game.fieldHeight * 0.45f

    val clampedX = touch.x.coerceIn(radius, game.fieldWidth - radius)
    val clampedY = touch.y.coerceIn(minY, maxY)

    game.topPaddle = Vec2(clampedX, clampedY)
}

private fun updateGame(game: GameState, mode: GameMode, difficulty: Difficulty, dt: Float): List<GameEvent> {
    if (game.isMatchEnded) return emptyList()

    val events = mutableListOf<GameEvent>()

    val width = game.fieldWidth
    val height = game.fieldHeight
    val minDim = min(width, height)
    val paddleRadius = minDim * 0.06f
    val puckRadius = minDim * 0.03f
    val goalLeft = width * 0.32f
    val goalRight = width * 0.68f

    game.hitSoundCooldown = (game.hitSoundCooldown - dt).coerceAtLeast(0f)

    if (mode == GameMode.CPU) {
        game.topPrev = game.topPaddle.copy()
        val attackLine = height * 0.48f
        val targetX = game.puck.x.coerceIn(paddleRadius, width - paddleRadius)
        val targetY = if (game.puck.y < attackLine) {
            (game.puck.y - (paddleRadius + puckRadius) * 0.72f).coerceIn(paddleRadius, height * 0.45f)
        } else {
            (height * 0.18f + (game.puck.y - height * 0.2f) * difficulty.aiTracking)
                .coerceIn(paddleRadius, height * 0.36f)
        }

        val dx = targetX - game.topPaddle.x
        val dy = targetY - game.topPaddle.y
        val distance = hypot(dx, dy)
        val attackBoost = if (game.puck.y < attackLine) 1.28f else 1f
        val maxStep = minDim * 0.9f * difficulty.aiSpeedMultiplier * attackBoost * dt

        if (distance > 0.001f) {
            val t = min(1f, maxStep / distance)
            game.topPaddle = Vec2(
                game.topPaddle.x + dx * t,
                game.topPaddle.y + dy * t
            )
        }
    }

    game.puck = Vec2(
        game.puck.x + game.puckVelocity.x * dt,
        game.puck.y + game.puckVelocity.y * dt
    )

    game.puckVelocity = Vec2(
        game.puckVelocity.x * PUCK_FRICTION,
        game.puckVelocity.y * PUCK_FRICTION
    )

    keepPuckSpeedPlayable(game, minSpeed = minDim * MIN_PUCK_SPEED_RATIO, maxSpeed = minDim * MAX_PUCK_SPEED_RATIO)

    if (game.puck.x - puckRadius <= 0f) {
        game.puck = Vec2(puckRadius, game.puck.y)
        game.puckVelocity = Vec2(max(70f, -game.puckVelocity.x), game.puckVelocity.y)
    }
    if (game.puck.x + puckRadius >= width) {
        game.puck = Vec2(width - puckRadius, game.puck.y)
        game.puckVelocity = Vec2(min(-70f, -game.puckVelocity.x), game.puckVelocity.y)
    }

    if (game.puck.y - puckRadius <= 0f) {
        if (game.puck.x in goalLeft..goalRight) {
            game.resetRound(scoredSide = Side.BOTTOM)
            events += GameEvent.Goal

            if (game.bottomScoreInt() >= WIN_SCORE) {
                game.isMatchEnded = true
                events += GameEvent.MatchEnd(
                    winnerSide = Side.BOTTOM,
                    bottomScore = game.bottomScoreInt(),
                    topScore = game.topScoreInt()
                )
            }

            game.bottomPrev = game.bottomPaddle.copy()
            return events
        }

        game.puck = Vec2(game.puck.x, puckRadius)
        game.puckVelocity = Vec2(game.puckVelocity.x, max(70f, -game.puckVelocity.y))
    }

    if (game.puck.y + puckRadius >= height) {
        if (game.puck.x in goalLeft..goalRight) {
            game.resetRound(scoredSide = Side.TOP)
            events += GameEvent.Goal

            if (game.topScoreInt() >= WIN_SCORE) {
                game.isMatchEnded = true
                events += GameEvent.MatchEnd(
                    winnerSide = Side.TOP,
                    bottomScore = game.bottomScoreInt(),
                    topScore = game.topScoreInt()
                )
            }

            game.bottomPrev = game.bottomPaddle.copy()
            return events
        }

        game.puck = Vec2(game.puck.x, height - puckRadius)
        game.puckVelocity = Vec2(game.puckVelocity.x, min(-70f, -game.puckVelocity.y))
    }

    val bottomHit = handlePaddleCollision(
        game = game,
        paddle = game.bottomPaddle,
        paddlePrev = game.bottomPrev,
        paddleRadius = paddleRadius,
        puckRadius = puckRadius,
        punchStrength = PLAYER_PUNCH,
        dt = dt
    )

    val topHit = handlePaddleCollision(
        game = game,
        paddle = game.topPaddle,
        paddlePrev = game.topPrev,
        paddleRadius = paddleRadius,
        puckRadius = puckRadius,
        punchStrength = if (mode == GameMode.CPU) difficulty.aiPunch else PLAYER_PUNCH,
        dt = dt
    )

    if ((bottomHit || topHit) && game.hitSoundCooldown <= 0f) {
        events += GameEvent.Hit
        game.hitSoundCooldown = 0.05f
    }

    keepPuckSpeedPlayable(game, minSpeed = minDim * MIN_PUCK_SPEED_RATIO, maxSpeed = minDim * MAX_PUCK_SPEED_RATIO)

    game.pushTrailPoint()
    game.bottomPrev = game.bottomPaddle.copy()
    game.topPrev = game.topPaddle.copy()
    return events
}

private fun keepPuckSpeedPlayable(game: GameState, minSpeed: Float, maxSpeed: Float) {
    val speed = hypot(game.puckVelocity.x, game.puckVelocity.y)

    if (speed > maxSpeed) {
        val k = maxSpeed / speed
        game.puckVelocity = Vec2(game.puckVelocity.x * k, game.puckVelocity.y * k)
        return
    }

    if (speed >= minSpeed) return

    if (speed < 1f) {
        val verticalDirection = if (game.puck.y < game.fieldHeight / 2f) 1f else -1f
        val horizontalDirection = if (game.puck.x < game.fieldWidth / 2f) 0.35f else -0.35f
        val length = hypot(horizontalDirection, verticalDirection)
        game.puckVelocity = Vec2(
            horizontalDirection / length * minSpeed,
            verticalDirection / length * minSpeed
        )
        return
    }

    val k = minSpeed / speed
    game.puckVelocity = Vec2(game.puckVelocity.x * k, game.puckVelocity.y * k)
}

private fun handlePaddleCollision(
    game: GameState,
    paddle: Vec2,
    paddlePrev: Vec2,
    paddleRadius: Float,
    puckRadius: Float,
    punchStrength: Float,
    dt: Float
): Boolean {
    val dx = game.puck.x - paddle.x
    val dy = game.puck.y - paddle.y
    val minDist = paddleRadius + puckRadius
    val distSq = dx * dx + dy * dy

    if (distSq > minDist * minDist) return false

    val dist = sqrt(max(0.0001f, distSq))
    val nx = dx / dist
    val ny = dy / dist

    game.puck = Vec2(paddle.x + nx * minDist, paddle.y + ny * minDist)

    val dot = game.puckVelocity.x * nx + game.puckVelocity.y * ny
    var newVx = game.puckVelocity.x
    var newVy = game.puckVelocity.y

    if (dot < 0f) {
        newVx -= 2f * dot * nx
        newVy -= 2f * dot * ny
    }

    val paddleVx = (paddle.x - paddlePrev.x) / dt
    val paddleVy = (paddle.y - paddlePrev.y) / dt

    newVx += paddleVx * punchStrength
    newVy += paddleVy * punchStrength

    val normalExitSpeed = newVx * nx + newVy * ny
    if (normalExitSpeed < 120f) {
        val missingExitSpeed = 120f - normalExitSpeed
        newVx += nx * missingExitSpeed
        newVy += ny * missingExitSpeed
    }

    val boost = 1.02f
    game.puckVelocity = Vec2(newVx * boost, newVy * boost)
    return true
}

private fun scoreText(mode: GameMode, bottomScore: Int, topScore: Int): String {
    return "${mode.bottomLabel} $bottomScore : $topScore ${mode.topLabel}"
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawField() {
    val width = size.width
    val height = size.height
    val minDim = min(width, height)

    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF174533), Color(0xFF0E2E22), Color(0xFF174533))
        )
    )

    drawCircle(
        color = Color(0x55FFFFFF),
        radius = minDim * 0.15f,
        center = Offset(width / 2f, height / 2f),
        style = Stroke(width = minDim * 0.01f)
    )

    drawLine(
        color = Color(0x88FFFFFF),
        start = Offset(0f, height / 2f),
        end = Offset(width, height / 2f),
        strokeWidth = minDim * 0.008f
    )

    val goalW = width * 0.36f
    val goalX = (width - goalW) / 2f
    val goalH = minDim * 0.02f

    drawRect(Color(0xAAE53935), topLeft = Offset(goalX, 0f), size = Size(goalW, goalH))
    drawRect(Color(0xAA1E88E5), topLeft = Offset(goalX, height - goalH), size = Size(goalW, goalH))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGameObjects(game: GameState) {
    val minDim = min(size.width, size.height)
    val paddleRadius = minDim * 0.06f
    val puckRadius = minDim * 0.03f

    game.puckTrail.forEachIndexed { index, point ->
        val ratio = 1f - index / game.puckTrail.size.toFloat()
        drawCircle(
            color = Color.White.copy(alpha = 0.08f * ratio),
            radius = puckRadius * (0.35f + ratio * 0.45f),
            center = point
        )
    }

    drawCircle(
        color = Color(0x33000000),
        radius = puckRadius * 1.45f,
        center = Offset(game.puck.x + puckRadius * 0.2f, game.puck.y + puckRadius * 0.3f)
    )

    drawCircle(
        color = Color(0xFF42A5F5),
        radius = paddleRadius,
        center = Offset(game.bottomPaddle.x, game.bottomPaddle.y)
    )

    drawCircle(
        color = Color(0xFFFFA726),
        radius = paddleRadius,
        center = Offset(game.topPaddle.x, game.topPaddle.y)
    )

    drawCircle(
        color = Color.White,
        radius = puckRadius,
        center = Offset(game.puck.x, game.puck.y)
    )
}

private fun setupQrCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    cameraExecutor: ExecutorService,
    onQrScanned: (String) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener(
        {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrAnalyzer(onQrScanned))
                }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }
        },
        ContextCompat.getMainExecutor(context)
    )
}

private class QrAnalyzer(
    private val onQrScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.rawValue?.takeIf { it.isNotBlank() }?.let(onQrScanned)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}

private fun sendFeedbackEmail(context: Context, message: String, imageUri: Uri?) {
    val body = message.ifBlank { "Feedback from Neon Hockey" }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (imageUri == null) "message/rfc822" else "image/*"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Neon Hockey feedback")
        putExtra(Intent.EXTRA_TEXT, body)
        if (imageUri != null) {
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    runCatching {
        context.startActivity(Intent.createChooser(intent, "Send feedback"))
    }
}

private fun openScannedQr(context: Context, value: String) {
    if (value.isBlank()) return

    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
    val intent = if (value.startsWith("http://") || value.startsWith("https://")) {
        Intent(Intent.ACTION_VIEW, uri)
    } else {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
    }

    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open QR result"))
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
