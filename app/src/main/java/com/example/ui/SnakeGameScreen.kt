package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ScoreEntity
import com.example.viewmodel.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Cyber/Neon Color Scheme for our 4D layout
object NeonTheme {
    val DeepSpace = Color(0xFF07070F)
    val DashboardBg = Color(0xFF0F0E1D)
    val GridAccent = Color(0xFF1E1C38)
    
    val NeonCyan = Color(0xFF00FFFF)
    val NeonPink = Color(0xFFFF007F)
    val NeonGold = Color(0xFFFFAA00)
    val NeonGreen = Color(0xFF39FF14)
    val OffWhite = Color(0xFFF0EFFF)
    val MutedGray = Color(0xFF8B8A9F)
}

@Composable
fun SnakeGameApp(
    viewModel: SnakeViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val topScores by viewModel.topScores.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    // Observe state changes to trigger gentle tactile haptic feedback inside the app
    LaunchedEffect(state.score) {
        if (state.score > 0 && state.hapticsEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LaunchedEffect(state.gameState) {
        if (state.gameState == GameState.GAME_OVER && state.hapticsEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // High Score saving modal state
    var showSaveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.gameState) {
        if (state.gameState == GameState.GAME_OVER) {
            showSaveDialog = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NeonTheme.DeepSpace)
    ) {
        // Continuous, subtle background dust float in 4D space
        val infiniteTransition = rememberInfiniteTransition(label = "BgDust")
        val backgroundPulsingAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = BureaucraticFloatAnimationSpec(),
                repeatMode = RepeatMode.Restart
            ),
            label = "Angle"
        )

        AnimatedContent(
            targetState = state.gameState,
            transitionSpec = {
                fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
            },
            label = "ScreenSwitcher"
        ) { targetState ->
            when (targetState) {
                GameState.IDLE -> {
                    MainMenuScreen(
                        selectedSpeed = state.selectedSpeed,
                        onSpeedSelected = { viewModel.selectSpeed(it) },
                        hapticsEnabled = state.hapticsEnabled,
                        onToggleHaptics = { viewModel.toggleHaptics() },
                        topScores = topScores,
                        onStartGame = { viewModel.startGame() },
                        onClearScores = { viewModel.clearHighScores() }
                    )
                }
                GameState.PLAYING, GameState.PAUSED, GameState.GAME_OVER -> {
                    GamePlayLayout(
                        state = state,
                        particles = viewModel.particles,
                        onDirectionChange = { viewModel.changeDirection(it) },
                        onPauseToggle = {
                            if (state.gameState == GameState.PLAYING) viewModel.pauseGame()
                            else viewModel.resumeGame()
                        },
                        onExitClicked = {
                            viewModel.pauseGame()
                            showSaveDialog = false
                            viewModel.startGame() // Reset or trigger back
                        }
                    )
                }
            }
        }

        // Save high score dialog on game over
        if (showSaveDialog && state.gameState == GameState.GAME_OVER) {
            HighScoreSaveDialog(
                score = state.score,
                snakeLength = state.snakeLength,
                speedMode = state.selectedSpeed.label,
                onDismiss = { showSaveDialog = false },
                onSave = { name ->
                    viewModel.saveScoreOffline(name) {
                        showSaveDialog = false
                    }
                }
            )
        }
    }
}

// Custom speed slider or slow float speed for background particles
private fun BureaucraticFloatAnimationSpec(): DurationBasedAnimationSpec<Float> {
    return tween(60000, easing = LinearEasing)
}

@Composable
fun MainMenuScreen(
    selectedSpeed: GameSpeed,
    onSpeedSelected: (GameSpeed) -> Unit,
    hapticsEnabled: Boolean,
    onToggleHaptics: () -> Unit,
    topScores: List<ScoreEntity>,
    onStartGame: () -> Unit,
    onClearScores: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "LogoPulse")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Glow"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Futuristic Cyber Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(8.dp)
                    .height(90.dp)
            ) {
                // Outer cyber halo backing shadow
                Box(
                    modifier = Modifier
                        .scale(glowScale * 1.3f)
                        .size(140.dp, 60.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    NeonTheme.NeonCyan.copy(alpha = 0.4f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Text(
                    text = "RẮN SĂN MỒI 4D",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.SansSerif,
                    color = NeonTheme.NeonCyan,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .testTag("app_logo_title")
                        .shadow(4.dp, shape = CircleShape)
                )
            }


        }

        // Mid-Section: Scoreboard or Instructions
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NeonTheme.DashboardBg)
                .border(1.dp, NeonTheme.GridAccent, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = NeonTheme.NeonGold,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "BẢNG XẾP HẠNG OFFLINE",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonTheme.OffWhite,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                    if (topScores.isNotEmpty()) {
                        IconButton(
                            onClick = onClearScores,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear scores",
                                tint = NeonTheme.NeonPink.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (topScores.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = NeonTheme.MutedGray.copy(alpha = 0.5f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Chưa có kỷ lục nào. Chơi ngay để ghi danh!",
                                fontSize = 12.sp,
                                color = NeonTheme.MutedGray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(topScores) { index, item ->
                            val isTop1 = index == 0
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isTop1) NeonTheme.NeonCyan.copy(alpha = 0.08f)
                                        else NeonTheme.GridAccent.copy(alpha = 0.3f)
                                    )
                                    .border(
                                        width = if (isTop1) 1.dp else 0.dp,
                                        color = if (isTop1) NeonTheme.NeonCyan.copy(alpha = 0.2f) else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "#${index + 1}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isTop1) NeonTheme.NeonGold else NeonTheme.NeonCyan,
                                        modifier = Modifier.width(28.dp)
                                    )
                                    Text(
                                        text = item.playerName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NeonTheme.OffWhite
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "[${item.speedMode}]",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = NeonTheme.MutedGray
                                    )
                                }
                                Text(
                                    text = "${item.score} đ",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isTop1) NeonTheme.NeonCyan else NeonTheme.NeonPink
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Settings Grid
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Speed Difficulty Segment
            Text(
                text = "TỐC ĐỘ / CHẾ ĐỘ CHƠI",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = NeonTheme.MutedGray,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(15.dp))
                    .background(NeonTheme.DashboardBg)
                    .border(1.dp, NeonTheme.GridAccent, RoundedCornerShape(15.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                GameSpeed.values().forEach { speed ->
                    val isSelected = selectedSpeed == speed
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (isSelected) NeonTheme.NeonPink.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) NeonTheme.NeonPink.copy(alpha = 0.5f) else Color.Transparent,
                                shape = RoundedCornerShape(11.dp)
                            )
                            .clickable { onSpeedSelected(speed) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = speed.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) NeonTheme.NeonPink else NeonTheme.MutedGray,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = if (speed.hasWalls) "Có Tường" else "Xuyên Biên",
                                fontSize = 8.sp,
                                color = if (isSelected) NeonTheme.NeonPink.copy(alpha = 0.7f) else NeonTheme.MutedGray.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Extra Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tactile Feedback Box
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleHaptics() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = if (hapticsEnabled) Icons.Default.Settings else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (hapticsEnabled) NeonTheme.NeonCyan else NeonTheme.MutedGray,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hapticsEnabled) "Rung Phản Hồi: BẬT" else "Rung Phản Hồi: TẮT",
                        fontSize = 11.sp,
                        color = if (hapticsEnabled) NeonTheme.OffWhite else NeonTheme.MutedGray,
                        fontWeight = FontWeight.Medium
                    )
                }

                // File size reference
                Text(
                    text = "SIZE: ~5MB • 60 FPS",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonTheme.NeonGreen
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // PLAY BUTTON
            Button(
                onClick = onStartGame,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .testTag("play_game_button")
                    .shadow(12.dp, shape = RoundedCornerShape(16.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonTheme.NeonCyan,
                    contentColor = NeonTheme.DeepSpace
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = NeonTheme.DeepSpace
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CHƠI NGAY",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

// Modify scale and vector helpers for UI representation
@Composable
fun Modifier.scale(scale: Float) = this.then(
    graphicsLayer(scaleX = scale, scaleY = scale)
)

@Composable
fun GamePlayLayout(
    state: GameUiState,
    particles: List<Particle>,
    onDirectionChange: (Direction) -> Unit,
    onPauseToggle: () -> Unit,
    onExitClicked: () -> Unit
) {
    // Collect swipe gesture events
    var swipeOffsetStartX by remember { mutableStateOf(0f) }
    var swipeOffsetStartY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    // Request keyboard focus for laptop arrow & WASD keys
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(state.gameState) {
        if (state.gameState == GameState.PLAYING) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionUp -> {
                            onDirectionChange(Direction.UP)
                            true
                        }
                        Key.DirectionDown -> {
                            onDirectionChange(Direction.DOWN)
                            true
                        }
                        Key.DirectionLeft -> {
                            onDirectionChange(Direction.LEFT)
                            true
                        }
                        Key.DirectionRight -> {
                            onDirectionChange(Direction.RIGHT)
                            true
                        }
                        Key.W -> {
                            onDirectionChange(Direction.UP)
                            true
                        }
                        Key.S -> {
                            onDirectionChange(Direction.DOWN)
                            true
                        }
                        Key.A -> {
                            onDirectionChange(Direction.LEFT)
                            true
                        }
                        Key.D -> {
                            onDirectionChange(Direction.RIGHT)
                            true
                        }
                        Key.Spacebar -> {
                            onPauseToggle()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // TOP SCORE DASHBOARD PANEL
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NeonTheme.DashboardBg)
                .border(1.dp, NeonTheme.GridAccent, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Score column
                Column {
                    Text(
                        text = "ĐIỂM SỐ",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        color = NeonTheme.MutedGray
                    )
                    Text(
                        text = "${state.score}",
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = NeonTheme.NeonCyan,
                        modifier = Modifier.testTag("score_label")
                    )
                }

                // Level column
                Column {
                    Text(
                        text = "CẤP ĐỘ",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        color = NeonTheme.MutedGray
                    )
                    Text(
                        text = "Lvl ${state.level}",
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = NeonTheme.NeonPink
                    )
                }

                // Speed column
                Column {
                    Text(
                        text = "TỐC ĐỘ",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        color = NeonTheme.MutedGray
                    )
                    Text(
                        text = "${state.currentSpeedMs}ms",
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = NeonTheme.NeonGold
                    )
                }
            }

            // Power-up status pills
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.hasShield) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(NeonTheme.NeonGreen.copy(alpha = 0.2f))
                            .border(1.dp, NeonTheme.NeonGreen, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "SHIELD",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonTheme.NeonGreen
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                if (state.isWarpActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(NeonTheme.NeonGold.copy(alpha = 0.2f))
                            .border(1.dp, NeonTheme.NeonGold, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "WARP ACTIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonTheme.NeonGold
                        )
                    }
                }
            }

            // Controls on top bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onPauseToggle,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = NeonTheme.GridAccent.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.size(36.dp)
                ) {
                    if (state.gameState == GameState.PLAYING) {
                        Canvas(modifier = Modifier.size(14.dp)) {
                            val barWidth = 4.dp.toPx()
                            val barHeight = 12.dp.toPx()
                            val gap = 4.dp.toPx()
                            drawRect(color = NeonTheme.OffWhite, topLeft = Offset(0f, 0f), size = Size(barWidth, barHeight))
                            drawRect(color = NeonTheme.OffWhite, topLeft = Offset(barWidth + gap, 0f), size = Size(barWidth, barHeight))
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Mute/Pause button",
                            tint = NeonTheme.OffWhite,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onExitClicked,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = NeonTheme.NeonPink.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit game",
                        tint = NeonTheme.NeonPink,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // CANVAS GAME BOARD WITH SWIPE GESTURES GENTLY INTEGRATED
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .aspectRatio(1f) // Square Board
                .clip(RoundedCornerShape(18.dp))
                .background(NeonTheme.DeepSpace)
                .border(2.dp, NeonTheme.GridAccent, RoundedCornerShape(18.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            swipeOffsetStartX = offset.x
                            swipeOffsetStartY = offset.y
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                        },
                        onDragEnd = {
                            val diffX = swipeOffsetStartX
                            val diffY = swipeOffsetStartY
                            // Compute swipe calculations manually to give 100% swipe responsive controls
                        }
                    )
                }
                .pointerInput(Unit) {
                    // Simpler gesture swipe detector
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val (x, y) = dragAmount
                        if (Math.abs(x) > Math.abs(y)) {
                            if (x > 15f) onDirectionChange(Direction.RIGHT)
                            else if (x < -15f) onDirectionChange(Direction.LEFT)
                        } else {
                            if (y > 15f) onDirectionChange(Direction.DOWN)
                            else if (y < -15f) onDirectionChange(Direction.UP)
                        }
                    }
                }
        ) {
            // High-end Canvas Drawing Component
            SnakeCanvas(
                state = state,
                particles = particles
            )

            // PAUSED / GAME OVER OVERLAY SCREEN (Inside the board boundary)
            if (state.gameState == GameState.PAUSED) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "ĐANG TẠM DỪNG",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = NeonTheme.NeonCyan,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Nhấp nút tiếp tục để quay lại trận đấu",
                            fontSize = 11.sp,
                            color = NeonTheme.MutedGray
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onPauseToggle,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonTheme.NeonCyan, contentColor = NeonTheme.DeepSpace)
                        ) {
                            Text("TIẾP TỤC CHƠI", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // RETRO-ARCADE BACKLIT DIGITAL D-PAD CONTROLLER PANELS
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            contentAlignment = Alignment.Center
        ) {
            // Elegant Analog D-Pad layout using absolute 48dp target buttons
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(NeonTheme.DashboardBg)
                    .border(1.dp, NeonTheme.GridAccent, CircleShape)
            ) {
                // Central ornamental core
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(NeonTheme.GridAccent)
                        .align(Alignment.Center)
                )

                // D-Pad buttons
                // UP
                IconButton(
                    onClick = { onDirectionChange(Direction.UP) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                        .size(46.dp)
                        .testTag("dpad_up")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move Up",
                        tint = if (state.direction == Direction.UP) NeonTheme.NeonCyan else NeonTheme.OffWhite,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // DOWN
                IconButton(
                    onClick = { onDirectionChange(Direction.DOWN) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(46.dp)
                        .testTag("dpad_down")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move Down",
                        tint = if (state.direction == Direction.DOWN) NeonTheme.NeonCyan else NeonTheme.OffWhite,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // LEFT
                IconButton(
                    onClick = { onDirectionChange(Direction.LEFT) },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(46.dp)
                        .testTag("dpad_left")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack, // Standard fallback
                        contentDescription = "Move Left",
                        tint = if (state.direction == Direction.LEFT) NeonTheme.NeonCyan else NeonTheme.OffWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // RIGHT
                IconButton(
                    onClick = { onDirectionChange(Direction.RIGHT) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .size(46.dp)
                        .testTag("dpad_right")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward, // Standard fallback
                        contentDescription = "Move Right",
                        tint = if (state.direction == Direction.RIGHT) NeonTheme.NeonCyan else NeonTheme.OffWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SnakeCanvas(
    state: GameUiState,
    particles: List<Particle>
) {
    // Infinite animators to drive constant neon pulsing and grid waving
    val infiniteTransition = rememberInfiniteTransition(label = "CanvasWave")
    val gridOscillationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GridAngle"
    )

    val neonPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "NeonPulse"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("game_board")
    ) {
        val boardWidth = size.width
        val boardHeight = size.height

        val gridCols = state.gridWidth
        val gridRows = state.gridHeight

        val cellWidth = boardWidth / gridCols
        val cellHeight = boardHeight / gridRows

        // Use random math relative to time to construct a gentle 4D breathing grid parallax vector!
        val waveRad = Math.toRadians(gridOscillationAngle.toDouble())
        val horizontalGridShift = sin(waveRad).toFloat() * 5f
        val verticalGridShift = cos(waveRad).toFloat() * 5f

        // Apply edge-to-edge camera shake offsets directly to the draw transform scope
        val random = Random(System.nanoTime())
        val shakeIntensity = state.cameraShake * 24f
        val shakeX = if (shakeIntensity > 0f) (random.nextFloat() - 0.5f) * shakeIntensity else 0f
        val shakeY = if (shakeIntensity > 0f) (random.nextFloat() - 0.5f) * shakeIntensity else 0f

        withTransform({
            translate(left = shakeX, top = shakeY)
        }) {
            // Draw discrete grid matrix with parallax perspective shifts
            for (col in 0..gridCols) {
                val posX = col * cellWidth + horizontalGridShift
                drawLine(
                    color = NeonTheme.GridAccent,
                    start = Offset(posX, 0f),
                    end = Offset(posX, boardHeight),
                    strokeWidth = 1f
                )
            }
            for (row in 0..gridRows) {
                val posY = row * cellHeight + verticalGridShift
                drawLine(
                    color = NeonTheme.GridAccent,
                    start = Offset(0f, posY),
                    end = Offset(boardWidth, posY),
                    strokeWidth = 1f
                )
            }

            // DRAW OBSTACLES (Neon Gold Pulsing Barriers with wireframe cross meshes)
            state.obstacles.forEach { obstacle ->
                val rectSize = Size(cellWidth * 0.7f, cellHeight * 0.7f)
                val topLeftOffset = Offset(
                    obstacle.x * cellWidth + cellWidth * 0.15f,
                    obstacle.y * cellHeight + cellHeight * 0.15f
                )
                val centerOffset = Offset(
                    obstacle.x * cellWidth + cellWidth * 0.5f,
                    obstacle.y * cellHeight + cellHeight * 0.5f
                )
                val glowRadius = cellWidth * 0.6f

                // Outer ambient pulse glow
                drawCircle(
                    color = NeonTheme.NeonGold.copy(alpha = 0.25f * neonPulseAlpha),
                    radius = glowRadius,
                    center = centerOffset
                )

                // Fill block backing
                drawRoundRect(
                    color = NeonTheme.DashboardBg,
                    topLeft = topLeftOffset,
                    size = rectSize,
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // High-intensity amber contour
                drawRoundRect(
                    color = NeonTheme.NeonGold,
                    topLeft = topLeftOffset,
                    size = rectSize,
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Inner cross-mesh hazard indicators
                val marginOffset = 3.dp.toPx()
                drawLine(
                    color = NeonTheme.NeonGold.copy(alpha = 0.5f),
                    start = Offset(topLeftOffset.x + marginOffset, topLeftOffset.y + marginOffset),
                    end = Offset(topLeftOffset.x + rectSize.width - marginOffset, topLeftOffset.y + rectSize.height - marginOffset),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = NeonTheme.NeonGold.copy(alpha = 0.5f),
                    start = Offset(topLeftOffset.x + rectSize.width - marginOffset, topLeftOffset.y + marginOffset),
                    end = Offset(topLeftOffset.x + marginOffset, topLeftOffset.y + rectSize.height - marginOffset),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // DRAW MULTIPLE FOOD ITEMS (Pulsing glowing gems)
            state.foods.forEach { food ->
                val foodCenter = Offset(
                    food.point.x * cellWidth + cellWidth / 2,
                    food.point.y * cellHeight + cellHeight / 2
                )
                val foodRadius = (cellWidth / 2f) * 0.75f

                when (food.type) {
                    FoodType.NORMAL -> {
                        // Diamond cut ruby
                        val path = Path().apply {
                            moveTo(foodCenter.x, foodCenter.y - foodRadius)
                            lineTo(foodCenter.x + foodRadius, foodCenter.y)
                            lineTo(foodCenter.x, foodCenter.y + foodRadius)
                            lineTo(foodCenter.x - foodRadius, foodCenter.y)
                            close()
                        }
                        // Glimmer shadow
                        drawCircle(
                            color = NeonTheme.NeonPink.copy(alpha = 0.25f * neonPulseAlpha),
                            radius = foodRadius * 1.5f,
                            center = foodCenter
                        )
                        drawPath(path = path, color = NeonTheme.NeonPink)
                        // Central white star shimmer
                        drawCircle(
                            color = Color.White,
                            radius = foodRadius * 0.35f,
                            center = foodCenter
                        )
                    }
                    FoodType.PORTAL -> {
                        // Golden portal halo
                        drawCircle(
                            color = NeonTheme.NeonGold.copy(alpha = 0.15f),
                            radius = foodRadius * 2.1f,
                            center = foodCenter
                        )
                        drawCircle(
                            color = NeonTheme.NeonGold.copy(alpha = 0.3f),
                            radius = foodRadius * 1.4f,
                            center = foodCenter,
                            style = Stroke(width = 3f)
                        )
                        // Pulsing star coordinates
                        withTransform({
                            rotate(degrees = gridOscillationAngle * 2.5f, pivot = foodCenter)
                        }) {
                            drawRect(
                                color = NeonTheme.NeonGold,
                                size = Size(foodRadius * 1.2f, foodRadius * 1.2f),
                                topLeft = Offset(foodCenter.x - (foodRadius * 0.6f), foodCenter.y - (foodRadius * 0.6f))
                            )
                            drawRect(
                                color = Color.White,
                                size = Size(foodRadius * 0.6f, foodRadius * 0.6f),
                                topLeft = Offset(foodCenter.x - (foodRadius * 0.3f), foodCenter.y - (foodRadius * 0.3f))
                            )
                        }
                    }
                    FoodType.SHIELD -> {
                        // Electric emerald sphere shield
                        drawCircle(
                            color = NeonTheme.NeonGreen.copy(alpha = 0.25f * neonPulseAlpha),
                            radius = foodRadius * 1.8f,
                            center = foodCenter,
                            style = Stroke(width = 2f)
                        )
                        drawCircle(
                            color = NeonTheme.NeonGreen,
                            radius = foodRadius * 0.75f,
                            center = foodCenter
                        )
                        // Concentric shield lines
                        drawCircle(
                            color = Color.White,
                            radius = foodRadius * 0.4f,
                            center = foodCenter,
                            style = Stroke(width = 2f)
                        )
                    }
                }
            }

            // DRAW SNAKE BODY WITH ORGANIC GLOW TAPER DOWN
            val snake = state.snake
            snake.forEachIndexed { index, pt ->
                val segmentCenter = Offset(
                    pt.x * cellWidth + cellWidth / 2,
                    pt.y * cellHeight + cellHeight / 2
                )

                // Organic taper calculation: tail gets thinner and fades out gracefully
                val sizeFraction = if (snake.size <= 1) 1.0f else {
                    1.0f - 0.45f * (index.toFloat() / snake.size)
                }
                val alphaFraction = if (snake.size <= 1) 1.0f else {
                    1.0f - 0.65f * (index.toFloat() / snake.size)
                }

                val bodyRadius = (cellWidth / 2f) * 0.9f * sizeFraction
                val glowRadius = bodyRadius * 1.6f

                val isHead = index == 0
                val colorCycle = if (state.selectedSpeed == GameSpeed.HYPER_4D) {
                    // Cycles colors smoothly across full rainbow spectrum in special hyper 4D mode!
                    val elapsedHue = (gridOscillationAngle + index * 12f) % 360f
                    Color.hsv(elapsedHue, 0.95f, 1.0f)
                } else {
                    if (isHead) NeonTheme.NeonCyan else NeonTheme.NeonCyan.copy(alpha = alphaFraction)
                }

                if (isHead) {
                    // Head inner plasma core
                    drawCircle(
                        color = colorCycle.copy(alpha = 0.3f),
                        radius = glowRadius,
                        center = segmentCenter
                    )
                    drawCircle(
                        color = colorCycle,
                        radius = bodyRadius,
                        center = segmentCenter
                    )
                    // White core power spark
                    drawCircle(
                        color = Color.White,
                        radius = bodyRadius * 0.45f,
                        center = segmentCenter
                    )

                    // Draw stylized robot eyes in snake's movement direction
                    val eyeRadius = bodyRadius * 0.18f
                    val eyeOffset = bodyRadius * 0.35f
                    when (state.direction) {
                        Direction.UP -> {
                            drawCircle(Color.Black, eyeRadius, Offset(segmentCenter.x - eyeOffset, segmentCenter.y - eyeOffset))
                            drawCircle(Color.Black, eyeRadius, Offset(segmentCenter.x + eyeOffset, segmentCenter.y - eyeOffset))
                        }
                        Direction.DOWN -> {
                            drawCircle(Color.Black, eyeRadius, Offset(segmentCenter.x - eyeOffset, segmentCenter.y + eyeOffset))
                            drawCircle(Color.Black, eyeRadius, Offset(segmentCenter.x + eyeOffset, segmentCenter.y + eyeOffset))
                        }
                        Direction.LEFT -> {
                            drawCircle(Color.Black, eyeRadius, Offset(segmentCenter.x - eyeOffset, segmentCenter.y - eyeOffset))
                            drawCircle(Color.Black, eyeRadius, Offset(segmentCenter.x - eyeOffset, segmentCenter.y + eyeOffset))
                        }
                        Direction.RIGHT -> {
                            drawCircle(Color.Black, eyeRadius, Offset(segmentCenter.x + eyeOffset, segmentCenter.y - eyeOffset))
                            drawCircle(Color.Black, eyeRadius, Offset(segmentCenter.x + eyeOffset, segmentCenter.y + eyeOffset))
                        }
                    }
                } else {
                    // Body nodes: trailing shadows
                    drawCircle(
                        color = colorCycle.copy(alpha = 0.15f * alphaFraction),
                        radius = glowRadius,
                        center = segmentCenter
                    )
                    drawCircle(
                        color = colorCycle,
                        radius = bodyRadius,
                        center = segmentCenter
                    )
                    // Concentric center pattern for details
                    drawCircle(
                        color = NeonTheme.DeepSpace,
                        radius = bodyRadius * 0.35f,
                        center = segmentCenter
                    )
                }
            }

            // DRAW CONTINUOUS 60FPS FLOATING PARTICLES
            particles.forEach { p ->
                val ptOffset = Offset(p.x * cellWidth, p.y * cellHeight)
                val particleColor = Color(p.color).copy(alpha = p.alpha)

                withTransform({
                    rotate(degrees = p.rotation, pivot = ptOffset)
                }) {
                    // Draw sparks as small stars / diamonds
                    drawRect(
                        color = particleColor,
                        topLeft = Offset(ptOffset.x - p.size / 2, ptOffset.y - p.size / 2),
                        size = Size(p.size, p.size)
                    )
                    // Inner brighter dot
                    drawCircle(
                        color = Color.White.copy(alpha = p.alpha),
                        radius = p.size * 0.25f,
                        center = ptOffset
                    )
                }
            }
        }
    }
}

@Composable
fun HighScoreSaveDialog(
    score: Int,
    snakeLength: Int,
    speedMode: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var playerName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(NeonTheme.DeepSpace)
                .border(2.dp, NeonTheme.NeonPink.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = NeonTheme.NeonGold,
                    modifier = Modifier
                        .size(48.dp)
                        .padding(bottom = 8.dp)
                )

                Text(
                    text = "TRÒ CHƠI KẾT THÚC!",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = NeonTheme.NeonPink,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Stats Dashboard
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NeonTheme.DashboardBg)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("ĐIỂM SỐ", fontSize = 11.sp, color = NeonTheme.MutedGray)
                        Text("$score", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NeonTheme.NeonCyan)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("ĐỘ DÀI", fontSize = 11.sp, color = NeonTheme.MutedGray)
                        Text("$snakeLength", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NeonTheme.OffWhite)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("TỐC ĐỘ", fontSize = 11.sp, color = NeonTheme.MutedGray)
                        Text(speedMode, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NeonTheme.NeonGold)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Lưu lại kỷ lục offline của bạn:",
                    fontSize = 12.sp,
                    color = NeonTheme.OffWhite,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Modern Fill TextField
                TextField(
                    value = playerName,
                    onValueChange = { if (it.length <= 14) playerName = it },
                    placeholder = { Text("Nhập biệt danh...", color = NeonTheme.MutedGray.copy(alpha = 0.7f)) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = NeonTheme.DashboardBg,
                        unfocusedContainerColor = NeonTheme.DashboardBg,
                        focusedTextColor = NeonTheme.OffWhite,
                        unfocusedTextColor = NeonTheme.OffWhite,
                        focusedIndicatorColor = NeonTheme.NeonCyan,
                        unfocusedIndicatorColor = NeonTheme.GridAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("player_name_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("BỎ QUA", color = NeonTheme.MutedGray, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onSave(playerName) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonTheme.NeonCyan,
                            contentColor = NeonTheme.DeepSpace
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.5f)
                            .testTag("save_score_button")
                    ) {
                        Text("LƯU KỶ LỤC", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

// Space spacey removed in favor of standard Arrangement.SpaceEvenly
