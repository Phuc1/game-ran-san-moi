package com.example.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ScoreEntity
import com.example.data.ScoreRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone
import kotlin.random.Random

/**
 * Thin Android layer: owns coroutines, Room and particles. All game rules live in [GameEngine]
 * (pure Kotlin, unit-tested), and this class only reacts to the [GameEvent]s it emits.
 */
class SnakeViewModel(
    private val repository: ScoreRepository,
    private val statsStorage: StatsStorage,
    private var engine: GameEngine = GameEngine(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(engine.newGame(DEFAULT_GRID, DEFAULT_GRID, GameSpeed.MEDIUM))
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    // Streak + daily best (persisted in SharedPreferences)
    private val _stats = MutableStateFlow(statsStorage.load())
    val stats: StateFlow<PlayerStats> = _stats.asStateFlow()

    // Top scores observed reactively from Room
    val topScores: StateFlow<List<ScoreEntity>> = repository.getTopScores(10)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Particles drawn by the Compose canvas
    val particles = mutableStateListOf<Particle>()

    private var gameLoopJob: Job? = null
    private var effectsJob: Job? = null

    fun selectSpeed(speed: GameSpeed) {
        _uiState.update {
            it.copy(
                selectedSpeed = speed,
                currentSpeedMs = engine.speedMs(it.level, speed)
            )
        }
    }

    fun toggleHaptics() {
        _uiState.update { it.copy(hapticsEnabled = !it.hapticsEnabled) }
    }

    fun changeDirection(newDir: Direction) {
        val state = _uiState.value
        if (state.gameState != GameState.PLAYING) return
        engine.queueDirection(state.direction, newDir)
    }

    /** Local-calendar day number (same for everyone in the same timezone and date). */
    fun todayEpochDay(): Long {
        val now = clock()
        return DailyChallenge.epochDay(now, TimeZone.getDefault().getOffset(now))
    }

    fun todayLabel(): String = DailyChallenge.label(todayEpochDay())

    /** Free-play: uses the speed chosen in the menu and a normal random board. */
    fun startNormalGame() {
        if (_uiState.value.gameState == GameState.PLAYING) return
        if (_uiState.value.isDaily) engine = GameEngine()
        _uiState.update { it.copy(isDaily = false) }
        startGame()
    }

    /** Daily challenge: identical board for every player today, fixed MEDIUM speed. */
    fun startDailyGame() {
        if (_uiState.value.gameState == GameState.PLAYING) return
        val day = todayEpochDay()
        engine = GameEngine(dailySeed = DailyChallenge.seedFor(day))
        _uiState.update { it.copy(isDaily = true, dailyDay = day, selectedSpeed = GameSpeed.MEDIUM) }
        startGame()
    }

    fun startGame() {
        val state = _uiState.value
        if (state.gameState == GameState.PLAYING) return

        if (state.gameState == GameState.GAME_OVER || state.gameState == GameState.IDLE) {
            resetGame()
        }

        _uiState.update { it.copy(gameState = GameState.PLAYING) }
        startGameLoop()
    }

    fun pauseGame() {
        if (_uiState.value.gameState == GameState.PLAYING) {
            _uiState.update { it.copy(gameState = GameState.PAUSED) }
            gameLoopJob?.cancel()
        }
    }

    fun resumeGame() {
        if (_uiState.value.gameState == GameState.PAUSED) {
            _uiState.update { it.copy(gameState = GameState.PLAYING) }
            startGameLoop()
        }
    }

    fun exitToMenu() {
        gameLoopJob?.cancel()
        particles.clear()
        resetGame() // back to IDLE (menu)
    }

    private fun resetGame() {
        val old = _uiState.value
        _uiState.value = engine
            .newGame(old.gridWidth, old.gridHeight, old.selectedSpeed, old.hapticsEnabled)
            .copy(isDaily = old.isDaily, dailyDay = old.dailyDay)
    }

    private fun startGameLoop() {
        gameLoopJob?.cancel()
        gameLoopJob = viewModelScope.launch {
            while (_uiState.value.gameState == GameState.PLAYING) {
                delay(_uiState.value.currentSpeedMs)
                gameTick()
            }
        }
    }

    private fun gameTick() {
        val result = engine.step(_uiState.value)
        _uiState.value = result.state
        result.events.forEach(::handleEvent)

        // Subtle tail trail spark
        val snake = result.state.snake
        if (Random.nextFloat() < 0.2f && snake.isNotEmpty() && result.state.gameState == GameState.PLAYING) {
            val tail = snake.last()
            addParticle(
                x = tail.x + 0.5f,
                y = tail.y + 0.5f,
                vx = (Random.nextFloat() - 0.5f) * 0.05f,
                vy = (Random.nextFloat() - 0.5f) * 0.05f,
                size = 6f + Random.nextFloat() * 8f,
                color = 0xFF8A2BE2,
                decay = 0.04f
            )
        }
        kickEffects()
    }

    private fun handleEvent(event: GameEvent) {
        when (event) {
            is GameEvent.ShieldBroken -> triggerShieldSparks(event.at.x, event.at.y)
            is GameEvent.FoodEaten -> triggerFoodConsumptionSparks(event.at.x, event.at.y, event.type)
            is GameEvent.TailGrew -> triggerTailGrowthSparks(event.at.x, event.at.y)
            is GameEvent.LevelUp -> triggerLevelUpSparks(event.at.x, event.at.y)
            is GameEvent.ObstacleSpawned -> triggerLevelUpSparks(event.at.x, event.at.y)
            is GameEvent.GameOver -> onGameOver()
        }
    }

    // Runs only while there is something to animate; stops by itself (no idle 60 Hz loop).
    private fun kickEffects() {
        if (effectsJob?.isActive == true) return
        effectsJob = viewModelScope.launch {
            while (particles.isNotEmpty() || _uiState.value.cameraShake > 0f) {
                delay(16L)
                updateParticles()
                decayCameraShake()
            }
        }
    }

    fun saveScoreOffline(playerName: String, onFinished: () -> Unit) {
        val name = playerName.ifBlank { "Người chơi" }
        viewModelScope.launch {
            val state = _uiState.value
            repository.insertScore(
                ScoreEntity(
                    playerName = name,
                    score = state.score,
                    snakeLength = state.snakeLength,
                    speedMode = state.selectedSpeed.label
                )
            )
            onFinished()
        }
    }

    fun clearHighScores() {
        viewModelScope.launch {
            repository.clearAllScores()
        }
    }

    private fun onGameOver() {
        gameLoopJob?.cancel()

        val finished = _uiState.value
        val updated = StreakLogic.recordRun(_stats.value, todayEpochDay(), finished.score, finished.isDaily)
        _stats.value = updated
        statsStorage.save(updated)
        _uiState.update { it.copy(roast = Roasts.forGameOver(it.score, it.deathCause)) }

        // Explosion from every snake segment
        _uiState.value.snake.forEach { pt ->
            for (i in 0..3) {
                addParticle(
                    x = pt.x + 0.5f,
                    y = pt.y + 0.5f,
                    vx = (Random.nextFloat() - 0.5f) * 0.25f,
                    vy = (Random.nextFloat() - 0.5f) * 0.25f,
                    size = 12f + Random.nextFloat() * 15f,
                    color = 0xFFFF1493, // Deep Pink
                    decay = 0.02f
                )
            }
        }
    }

    // --- Dynamic Particle Spark Physics ---

    private fun addParticle(x: Float, y: Float, vx: Float, vy: Float, size: Float, color: Long, decay: Float) {
        if (particles.size > 200) {
            particles.removeAt(0) // Prevent memory/allocation bloat, keeping sizing featherweight!
        }
        kickEffects()
        particles.add(
            Particle(
                x = x,
                y = y,
                vx = vx,
                vy = vy,
                size = size,
                color = color,
                alpha = 1.0f,
                decay = decay,
                rotation = Random.nextFloat() * 360f,
                rotationSpeed = (Random.nextFloat() - 0.5f) * 12f
            )
        )
    }

    private fun updateParticles() {
        if (particles.isEmpty()) return
        val updatedList = mutableListOf<Particle>()
        for (p in particles) {
            val newAlpha = p.alpha - p.decay
            if (newAlpha > 0f) {
                updatedList.add(
                    p.copy(
                        x = p.x + p.vx,
                        y = p.y + p.vy,
                        alpha = newAlpha,
                        rotation = (p.rotation + p.rotationSpeed) % 360f
                    )
                )
            }
        }
        particles.clear()
        particles.addAll(updatedList)
    }

    private fun decayCameraShake() {
        if (_uiState.value.cameraShake > 0f) {
            _uiState.update { it.copy(cameraShake = (it.cameraShake - 0.03f).coerceAtLeast(0f)) }
        }
    }

    private fun triggerFoodConsumptionSparks(gridX: Int, gridY: Int, type: FoodType) {
        val color = when (type) {
            FoodType.NORMAL -> 0xFFFF003F  // Cyber Red-Pink
            FoodType.PORTAL -> 0xFFFFD700  // Gold
            FoodType.SHIELD -> 0xFF39FF14  // Electric Neon Green
        }
        val count = when (type) {
            FoodType.NORMAL -> 15
            FoodType.PORTAL -> 35
            FoodType.SHIELD -> 25
        }
        for (i in 0 until count) {
            val velocityScale = if (type == FoodType.PORTAL) 0.22f else 0.15f
            addParticle(
                x = gridX + 0.5f,
                y = gridY + 0.5f,
                vx = (Random.nextFloat() - 0.5f) * velocityScale,
                vy = (Random.nextFloat() - 0.5f) * velocityScale,
                size = 8f + Random.nextFloat() * 12f,
                color = color,
                decay = 0.03f + Random.nextFloat() * 0.03f
            )
        }
    }

    private fun triggerShieldSparks(gridX: Int, gridY: Int) {
        // Rings of neon emerald defense sparks!
        for (i in 0..18) {
            val angle = i * (Math.PI * 2 / 18)
            val dx = Math.cos(angle).toFloat() * 0.12f
            val dy = Math.sin(angle).toFloat() * 0.12f
            addParticle(
                x = gridX + 0.5f,
                y = gridY + 0.5f,
                vx = dx,
                vy = dy,
                size = 10f + Random.nextFloat() * 5f,
                color = 0xFF00FF7F, // Spring Green
                decay = 0.05f
            )
        }
    }

    private fun triggerTailGrowthSparks(gridX: Int, gridY: Int) {
        for (i in 0..5) {
            addParticle(
                x = gridX + 0.5f,
                y = gridY + 0.5f,
                vx = (Random.nextFloat() - 0.5f) * 0.04f,
                vy = (Random.nextFloat() - 0.5f) * 0.04f,
                size = 6f + Random.nextFloat() * 6f,
                color = 0xFF00FFFF, // Neon Cyan
                decay = 0.05f
            )
        }
    }

    private fun triggerLevelUpSparks(gridX: Int, gridY: Int) {
        // Glorious rainbow fireworks explosion for leveling up
        val colors = listOf(0xFF00FFFF, 0xFFFF007F, 0xFFFFAA00, 0xFF39FF14)
        for (i in 0..40) {
            val angle = Random.nextFloat() * Math.PI * 2
            val velocity = 0.08f + Random.nextFloat() * 0.18f
            val vx = Math.cos(angle).toFloat() * velocity
            val vy = Math.sin(angle).toFloat() * velocity
            val randomColor = colors[Random.nextInt(colors.size)]
            addParticle(
                x = gridX + 0.5f,
                y = gridY + 0.5f,
                vx = vx,
                vy = vy,
                size = 10f + Random.nextFloat() * 14f,
                color = randomColor,
                decay = 0.02f + Random.nextFloat() * 0.02f
            )
        }
    }

    private companion object {
        const val DEFAULT_GRID = 20
    }
}
