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
import kotlin.math.abs
import kotlin.random.Random

enum class Direction {
    UP, DOWN, LEFT, RIGHT
}

fun Direction.opposite(): Direction = when (this) {
    Direction.UP -> Direction.DOWN
    Direction.DOWN -> Direction.UP
    Direction.LEFT -> Direction.RIGHT
    Direction.RIGHT -> Direction.LEFT
}

enum class GameState {
    IDLE, PLAYING, PAUSED, GAME_OVER
}

enum class FoodType {
    NORMAL,  // Red, +10 score, small explosion
    PORTAL,  // Gold, +30 score, wraps grid instantly, massive warp sparks
    SHIELD   // Green, +20 score, gives temporary armor to pass through wall/self once
}

data class Point(val x: Int, val y: Int)

data class Food(val point: Point, val type: FoodType)

data class Particle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val size: Float,
    val color: Long, // Hex color
    var alpha: Float,
    val decay: Float,
    val rotation: Float = 0f,
    val rotationSpeed: Float = 0f
)

enum class GameSpeed(val label: String, val ms: Long, val hasWalls: Boolean) {
    EASY("DỄ", 350L, false),
    MEDIUM("TRUNG BÌNH", 260L, false),
    HARD("KHÓ", 180L, true),
    HYPER_4D("HYPER 4D", 120L, true)
}

data class GameUiState(
    val snake: List<Point> = emptyList(),
    val direction: Direction = Direction.RIGHT,
    val foods: List<Food> = emptyList(),
    val obstacles: List<Point> = emptyList(),
    val score: Int = 0,
    val snakeLength: Int = 3,
    val level: Int = 1,
    val currentSpeedMs: Long = 150L,
    val gameState: GameState = GameState.IDLE,
    val hasShield: Boolean = false,
    val borderCollision: Boolean = false,
    val cameraShake: Float = 0f,
    val selectedSpeed: GameSpeed = GameSpeed.MEDIUM,
    val isWarpActive: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val gridWidth: Int = 20,
    val gridHeight: Int = 20
)

class SnakeViewModel(
    private val repository: ScoreRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    // Top scores observed reactively from Room
    val topScores: StateFlow<List<ScoreEntity>> = repository.getTopScores(10)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Particles list for seamless 60fps neon dust emission in Compose Canvas
    val particles = mutableStateListOf<Particle>()

    private var gameLoopJob: Job? = null
    private var effectsJob: Job? = null

    // Turns queued between ticks (max 2) so fast double-swipes are honoured in order
    private val pendingDirs = ArrayDeque<Direction>()

    init {
        resetGame()
    }

    private fun calculateSpeedMs(level: Int, speed: GameSpeed): Long {
        val baseMs = speed.ms
        // Gradually speeds up by 15ms per level
        val decrement = (level - 1) * 15L
        return (baseMs - decrement).coerceAtLeast(40L)
    }

    fun selectSpeed(speed: GameSpeed) {
        _uiState.update { 
            it.copy(
                selectedSpeed = speed,
                currentSpeedMs = calculateSpeedMs(it.level, speed)
            ) 
        }
    }

    fun toggleHaptics() {
        _uiState.update { it.copy(hapticsEnabled = !it.hapticsEnabled) }
    }

    fun changeDirection(newDir: Direction) {
        if (_uiState.value.gameState != GameState.PLAYING) return
        // Validate against the LAST queued turn, not the on-screen heading:
        // prevents UP->LEFT inside one tick from reversing into the neck.
        val last = pendingDirs.lastOrNull() ?: _uiState.value.direction
        if (newDir == last || newDir == last.opposite()) return
        if (pendingDirs.size < 2) pendingDirs.addLast(newDir)
    }

    fun startGame() {
        if (_uiState.value.gameState == GameState.PLAYING) return
        
        if (_uiState.value.gameState == GameState.GAME_OVER || _uiState.value.gameState == GameState.IDLE) {
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
        pendingDirs.clear()
        val w = _uiState.value.gridWidth
        val h = _uiState.value.gridHeight
        val startLength = 3
        val initialSnake = mutableListOf<Point>()
        val startY = h / 2
        for (i in startLength - 1 downTo 0) {
            initialSnake.add(Point((w / 4) + i, startY))
        }

        val initialSpeed = _uiState.value.selectedSpeed

        // Dynamic initial obstacle spawning count based on speed mode chosen
        val obstacleCount = when (initialSpeed) {
            GameSpeed.EASY -> 2
            GameSpeed.MEDIUM -> 4
            GameSpeed.HARD -> 7
            GameSpeed.HYPER_4D -> 10
        }
        val initialObstacles = mutableListOf<Point>()
        while (initialObstacles.size < obstacleCount) {
            val p = Point(Random.nextInt(w), Random.nextInt(h))
            // Ensure no obstacles spawn too close to the starting snake's horizontal placement
            val nearSnake = Math.abs(p.y - startY) <= 1 && p.x >= (w / 4 - 2) && p.x <= (w / 4 + startLength + 3)
            if (!nearSnake && !initialObstacles.contains(p)) {
                initialObstacles.add(p)
            }
        }

        _uiState.value = GameUiState(
            snake = initialSnake,
            direction = Direction.RIGHT,
            foods = emptyList(),
            obstacles = initialObstacles,
            score = 0,
            snakeLength = startLength,
            level = 1,
            currentSpeedMs = calculateSpeedMs(1, initialSpeed),
            gameState = GameState.IDLE,
            hasShield = false,
            borderCollision = false,
            cameraShake = 0f,
            selectedSpeed = initialSpeed,
            hapticsEnabled = _uiState.value.hapticsEnabled,
            gridWidth = w,
            gridHeight = h
        )

        // Spawn first batch of food
        spawnFood()
    }

    private fun startGameLoop() {
        gameLoopJob?.cancel()
        gameLoopJob = viewModelScope.launch {
            while (_uiState.value.gameState == GameState.PLAYING) {
                val delayTime = _uiState.value.currentSpeedMs
                delay(delayTime)
                gameTick()
            }
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

    private fun gameTick() {
        val state = _uiState.value
        val snake = state.snake.toMutableList()
        val head = snake.first()
        // Consume at most one queued turn per tick; fall back to the current heading.
        val dir = pendingDirs.removeFirstOrNull() ?: state.direction

        var nextX = head.x
        var nextY = head.y
        when (dir) {
            Direction.UP -> nextY -= 1
            Direction.DOWN -> nextY += 1
            Direction.LEFT -> nextX -= 1
            Direction.RIGHT -> nextX += 1
        }

        val w = state.gridWidth
        val h = state.gridHeight

        // Working copies: every mutation below is applied to these locals and committed
        // in ONE state update at the end, so the shield can never be spent twice in a tick.
        var shield = state.hasShield
        var obstacles = state.obstacles
        var shake = 0f

        // Wall collision / wrap-around
        if (nextX < 0 || nextX >= w || nextY < 0 || nextY >= h) {
            if (state.selectedSpeed.hasWalls) {
                if (!shield) {
                    triggerGameOver()
                    return
                }
                shield = false
                shake = maxOf(shake, 0.4f)
            }
            val shieldUsed = state.selectedSpeed.hasWalls
            nextX = (nextX + w) % w
            nextY = (nextY + h) % h
            if (shieldUsed) triggerShieldSparks(nextX, nextY)
        }

        val nextHead = Point(nextX, nextY)

        // Self collision (the tail cell is vacated this tick, so stepping onto it is legal)
        if (nextHead != snake.last() && snake.contains(nextHead)) {
            if (!shield) {
                triggerGameOver()
                return
            }
            shield = false
            shake = maxOf(shake, 0.5f)
            triggerShieldSparks(nextHead.x, nextHead.y)
        }

        // Obstacle collision
        if (obstacles.contains(nextHead)) {
            if (!shield) {
                triggerGameOver()
                return
            }
            // Shield shatters and destroys the obstacle
            obstacles = obstacles.filter { it != nextHead }
            shield = false
            shake = maxOf(shake, 0.6f)
            triggerShieldSparks(nextHead.x, nextHead.y)
        }

        snake.add(0, nextHead)

        val eatenIndex = state.foods.indexOfFirst { it.point == nextHead }
        val ate = eatenIndex != -1

        var score = state.score
        var length = state.snakeLength
        var level = state.level
        var speedMs = state.currentSpeedMs
        var foods = state.foods
        var warp = state.isWarpActive

        if (ate) {
            val eaten = state.foods[eatenIndex]
            val gain = when (eaten.type) {
                FoodType.NORMAL -> 10
                FoodType.PORTAL -> 30
                FoodType.SHIELD -> 20
            }
            if (eaten.type == FoodType.SHIELD) shield = true

            triggerFoodConsumptionSparks(nextHead.x, nextHead.y, eaten.type)

            // Multiplier applies to the GAIN only (applying it to the running total compounds exponentially).
            val multiplier = if (state.selectedSpeed == GameSpeed.HYPER_4D) 1.5 else 1.0
            score += (gain * multiplier).toInt()

            length += 1
            val newLevel = ((length - 3) / 3) + 1
            speedMs = calculateSpeedMs(newLevel, state.selectedSpeed)
            warp = eaten.type == FoodType.PORTAL
            foods = foods.filterIndexed { i, _ -> i != eatenIndex }

            val levelUp = newLevel > level
            level = newLevel
            shake = maxOf(shake, if (levelUp) 0.7f else 0.3f)

            if (levelUp) {
                triggerLevelUpSparks(nextHead.x, nextHead.y)
                // New obstacle on a free cell that is not right in front of the head (unavoidable death otherwise)
                val occupied = HashSet<Point>().apply {
                    addAll(snake)
                    addAll(obstacles)
                    foods.forEach { add(it.point) }
                }
                val candidates = ArrayList<Point>()
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        val p = Point(x, y)
                        if (p !in occupied && abs(p.x - nextHead.x) + abs(p.y - nextHead.y) > 3) {
                            candidates.add(p)
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    val p = candidates[Random.nextInt(candidates.size)]
                    obstacles = obstacles + p
                    triggerLevelUpSparks(p.x, p.y)
                }
            }
        } else if (snake.size > length) {
            snake.removeAt(snake.size - 1)
        }

        // Single atomic commit (snake included, so spawnFood below sees the new head)
        _uiState.update {
            it.copy(
                snake = snake,
                direction = dir,
                foods = foods,
                obstacles = obstacles,
                hasShield = shield,
                score = score,
                snakeLength = length,
                level = level,
                currentSpeedMs = speedMs,
                isWarpActive = warp,
                cameraShake = maxOf(it.cameraShake, shake)
            )
        }

        if (ate) {
            triggerTailGrowthSparks(snake.last().x, snake.last().y)
            spawnFood()
        }

        // Subtle tail trail spark
        if (Random.nextFloat() < 0.2f && snake.isNotEmpty()) {
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

    private fun spawnFood() {
        val state = _uiState.value
        val w = state.gridWidth
        val h = state.gridHeight
        val required = if (state.selectedSpeed == GameSpeed.HYPER_4D) 3 else 1
        val foods = state.foods.toMutableList()

        // Pick only from genuinely free cells (never overlaps snake / obstacles / other food)
        val occupied = HashSet<Point>().apply {
            addAll(state.snake)
            addAll(state.obstacles)
            foods.forEach { add(it.point) }
        }
        val free = ArrayList<Point>()
        for (x in 0 until w) {
            for (y in 0 until h) {
                val p = Point(x, y)
                if (p !in occupied) free.add(p)
            }
        }

        while (foods.size < required && free.isNotEmpty()) {
            val p = free.removeAt(Random.nextInt(free.size))
            val roll = Random.nextFloat()
            val type = when {
                roll < 0.12f -> FoodType.SHIELD
                roll < 0.28f -> FoodType.PORTAL
                else -> FoodType.NORMAL
            }
            foods.add(Food(p, type))
        }

        _uiState.update { it.copy(foods = foods) }
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

    private fun triggerGameOver() {
        _uiState.update { it.copy(gameState = GameState.GAME_OVER, cameraShake = 0.9f) }
        gameLoopJob?.cancel()
        kickEffects()

        // Cataclysmic graphic explosion spark burst on entire board!
        val snake = _uiState.value.snake
        snake.forEach { pt ->
            // Explosion sparks spawning from every segment
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
}
