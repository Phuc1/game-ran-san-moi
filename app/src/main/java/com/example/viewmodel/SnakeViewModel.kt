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
import kotlin.random.Random

enum class Direction {
    UP, DOWN, LEFT, RIGHT
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
    private var particleLoopJob: Job? = null
    private var timeMillis = 0L

    init {
        resetGame()
        startParticleSimulation()
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
        val currentDir = _uiState.value.direction
        // Prevent 180-degree immediate reverse collisions
        val isValid = when (newDir) {
            Direction.UP -> currentDir != Direction.DOWN
            Direction.DOWN -> currentDir != Direction.UP
            Direction.LEFT -> currentDir != Direction.RIGHT
            Direction.RIGHT -> currentDir != Direction.LEFT
        }
        if (isValid && _uiState.value.gameState == GameState.PLAYING) {
            _uiState.update { it.copy(direction = newDir) }
        }
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

    private fun resetGame() {
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

    private fun startParticleSimulation() {
        particleLoopJob?.cancel()
        particleLoopJob = viewModelScope.launch {
            while (true) {
                delay(16L) // ~60 FPS
                timeMillis += 16L
                updateParticles()
                decayCameraShake()
            }
        }
    }

    private fun gameTick() {
        val state = _uiState.value
        val snake = state.snake.toMutableList()
        val head = snake.first()
        val dir = state.direction

        // Compute next head position
        var nextX = head.x
        var nextY = head.y

        when (dir) {
            Direction.UP -> nextY -= 1
            Direction.DOWN -> nextY += 1
            Direction.LEFT -> nextX -= 1
            Direction.RIGHT -> nextX += 1
        }

        val hasWalls = state.selectedSpeed.hasWalls
        val w = state.gridWidth
        val h = state.gridHeight

        // Wall collision check / Wrap around
        if (nextX < 0 || nextX >= w || nextY < 0 || nextY >= h) {
            if (hasWalls) {
                if (state.hasShield) {
                    // Expend shield instead of death, wrap anyway
                    _uiState.update { it.copy(hasShield = false, cameraShake = 0.4f) }
                    triggerShieldSparks(nextX, nextY)
                    nextX = (nextX + w) % w
                    nextY = (nextY + h) % h
                } else {
                    triggerGameOver()
                    return
                }
            } else {
                // Wrap around
                nextX = (nextX + w) % w
                nextY = (nextY + h) % h
            }
        }

        val nextHead = Point(nextX, nextY)

        // Self-collision checking
        if (snake.contains(nextHead) && nextHead != snake.last()) {
            if (state.hasShield) {
                _uiState.update { it.copy(hasShield = false, cameraShake = 0.5f) }
                triggerShieldSparks(nextHead.x, nextHead.y)
            } else {
                triggerGameOver()
                return
            }
        }

        // Obstacle-collision checking
        if (state.obstacles.contains(nextHead)) {
            if (state.hasShield) {
                // Shield shatters, destroys obstacle satisfyingly!
                val updatedObstacles = state.obstacles.filter { it != nextHead }
                _uiState.update { it.copy(obstacles = updatedObstacles, hasShield = false, cameraShake = 0.6f) }
                triggerShieldSparks(nextHead.x, nextHead.y)
            } else {
                triggerGameOver()
                return
            }
        }

        // Add head
        snake.add(0, nextHead)

        // Check if head eats food
        var foodIndex = -1
        for (i in state.foods.indices) {
            if (state.foods[i].point == nextHead) {
                foodIndex = i
                break
            }
        }

        if (foodIndex != -1) {
            // Ate food!
            val eatenFood = state.foods[foodIndex]
            val scoreGained = when (eatenFood.type) {
                FoodType.NORMAL -> 10
                FoodType.PORTAL -> 30
                FoodType.SHIELD -> 20
            }

            var shieldState = state.hasShield
            if (eatenFood.type == FoodType.SHIELD) {
                shieldState = true
            }

            // Spawn explosive particle sparks on eaten food
            triggerFoodConsumptionSparks(nextHead.x, nextHead.y, eatenFood.type)

            // Trigger score increment & camera shake
            val newScore = state.score + scoreGained
            val multiplier = if (state.selectedSpeed == GameSpeed.HYPER_4D) 1.5 else 1.0
            val finalScore = (newScore * multiplier).toInt()

            val newLength = state.snakeLength + 1
            val newLevel = ((newLength - 3) / 3) + 1
            val nextSpeedMs = calculateSpeedMs(newLevel, state.selectedSpeed)
            val levelUpTriggered = newLevel > state.level

            val newObstacles = state.obstacles.toMutableList()
            if (levelUpTriggered) {
                triggerLevelUpSparks(nextHead.x, nextHead.y)
                // Add new obstacles dynamically as difficulty increases with levels!
                var attempts = 0
                while (attempts < 50) {
                    val p = Point(Random.nextInt(w), Random.nextInt(h))
                    if (!snake.contains(p) && state.foods.none { it.point == p } && !newObstacles.contains(p)) {
                        newObstacles.add(p)
                        triggerLevelUpSparks(p.x, p.y) // Beautiful sparks at new obstacle position
                        break
                    }
                    attempts++
                }
            }

            _uiState.update {
                it.copy(
                    score = finalScore,
                    snakeLength = newLength,
                    level = newLevel,
                    currentSpeedMs = nextSpeedMs,
                    hasShield = shieldState,
                    obstacles = newObstacles,
                    cameraShake = if (levelUpTriggered) 0.7f else 0.3f,
                    isWarpActive = eatenFood.type == FoodType.PORTAL
                )
            }

            // Spawn ambient sparkle around head
            triggerTailGrowthSparks(snake.last().x, snake.last().y)

            // Spawn new foods
            val currentFoods = state.foods.toMutableList()
            currentFoods.removeAt(foodIndex)
            _uiState.update { it.copy(foods = currentFoods) }
            
            spawnFood()
        } else {
            // Keep length constant - remove tail
            if (snake.size > state.snakeLength) {
                snake.removeAt(snake.size - 1)
            }
        }

        _uiState.update { it.copy(snake = snake) }

        // Spawn a subtle tail trail spark occasionally
        if (Random.nextFloat() < 0.2f && snake.isNotEmpty()) {
            val tail = snake.last()
            addParticle(
                x = tail.x + 0.5f,
                y = tail.y + 0.5f,
                vx = (Random.nextFloat() - 0.5f) * 0.05f,
                vy = (Random.nextFloat() - 0.5f) * 0.05f,
                size = 6f + Random.nextFloat() * 8f,
                color = 0xFF8A2BE2, // Purple
                decay = 0.04f
            )
        }
    }

    private fun spawnFood() {
        val state = _uiState.value
        val currentFoods = state.foods.toMutableList()
        val snake = state.snake
        val w = state.gridWidth
        val h = state.gridHeight

        // Generate food items so they don't overlap snake or other foods
        val requiredFoodCount = if (state.selectedSpeed == GameSpeed.HYPER_4D) 3 else 1
        
        while (currentFoods.size < requiredFoodCount) {
            var attempts = 0
            var foundPoint: Point? = null
            while (attempts < 100) {
                val p = Point(Random.nextInt(w), Random.nextInt(h))
                if (!snake.contains(p) && currentFoods.none { it.point == p } && !state.obstacles.contains(p)) {
                    foundPoint = p
                    break
                }
                attempts++
            }

            val targetPoint = foundPoint ?: Point(Random.nextInt(w), Random.nextInt(h))

            // Choose food type with neon-balancing
            val roll = Random.nextFloat()
            val type = when {
                roll < 0.12f -> FoodType.SHIELD // Green Shield
                roll < 0.28f -> FoodType.PORTAL // Golden Portal
                else -> FoodType.NORMAL        // Crimson Core
            }

            currentFoods.add(Food(targetPoint, type))
        }

        _uiState.update { it.copy(foods = currentFoods) }
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
