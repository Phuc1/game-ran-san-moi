package com.example.viewmodel

import kotlin.math.abs
import kotlin.random.Random

/** Side effects the UI layer reacts to (particles, haptics, stopping the loop). */
sealed interface GameEvent {
    data class ShieldBroken(val at: Point) : GameEvent
    data class FoodEaten(val at: Point, val type: FoodType) : GameEvent
    data class TailGrew(val at: Point) : GameEvent
    data class LevelUp(val at: Point) : GameEvent
    data class ObstacleSpawned(val at: Point) : GameEvent
    data object GameOver : GameEvent
}

data class StepResult(val state: GameUiState, val events: List<GameEvent>)

/**
 * Game rules with zero Android dependencies. All randomness comes from [random]
 * so tests can seed it. Not thread-safe: call from a single thread (the main thread in the app).
 */
class GameEngine(private val random: Random = Random.Default) {

    // Turns queued between ticks (max [MAX_QUEUED_TURNS]) so fast double swipes are honoured in order.
    private val pendingDirs = ArrayDeque<Direction>()

    /** Tick delay for a level: gets 15 ms faster per level, never below 40 ms. */
    fun speedMs(level: Int, speed: GameSpeed): Long =
        (speed.ms - (level - 1) * 15L).coerceAtLeast(MIN_TICK_MS)

    /** Builds a fresh game in IDLE state (menu) with initial obstacles and food. */
    fun newGame(
        width: Int,
        height: Int,
        speed: GameSpeed,
        hapticsEnabled: Boolean = true
    ): GameUiState {
        pendingDirs.clear()

        val startLength = 3
        val startY = height / 2
        val snake = (startLength - 1 downTo 0).map { i -> Point((width / 4) + i, startY) }

        val obstacleCount = when (speed) {
            GameSpeed.EASY -> 2
            GameSpeed.MEDIUM -> 4
            GameSpeed.HARD -> 7
            GameSpeed.HYPER_4D -> 10
        }
        // Keep the lane in front of the snake clear at the start
        val candidates = ArrayList<Point>()
        for (x in 0 until width) {
            for (y in 0 until height) {
                val nearSnake = abs(y - startY) <= 1 && x >= (width / 4 - 2) && x <= (width / 4 + startLength + 3)
                if (!nearSnake) candidates.add(Point(x, y))
            }
        }
        candidates.shuffle(random)
        val obstacles = candidates.take(obstacleCount)

        val state = GameUiState(
            snake = snake,
            direction = Direction.RIGHT,
            foods = emptyList(),
            obstacles = obstacles,
            score = 0,
            snakeLength = startLength,
            level = 1,
            currentSpeedMs = speedMs(1, speed),
            gameState = GameState.IDLE,
            selectedSpeed = speed,
            hapticsEnabled = hapticsEnabled,
            gridWidth = width,
            gridHeight = height
        )
        return spawnFood(state)
    }

    /**
     * Queues a turn. It is validated against the LAST queued turn (not the on-screen heading), so
     * UP then LEFT inside one tick becomes two consecutive legal turns instead of a 180 degree reversal.
     */
    fun queueDirection(heading: Direction, newDir: Direction) {
        val last = pendingDirs.lastOrNull() ?: heading
        if (newDir == last || newDir == last.opposite()) return
        if (pendingDirs.size < MAX_QUEUED_TURNS) pendingDirs.addLast(newDir)
    }

    fun clearQueuedTurns() = pendingDirs.clear()

    /** Advances the game by one tick. Pure with respect to [state]; returns the new state and events. */
    fun step(state: GameUiState): StepResult {
        val events = ArrayList<GameEvent>()
        val snake = state.snake.toMutableList()
        val head = snake.first()
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

        // Working copies: everything is committed once at the end, so the shield is spent at most once per tick.
        var shield = state.hasShield
        var obstacles = state.obstacles
        var shake = 0f

        // Wall collision / wrap-around
        if (nextX < 0 || nextX >= w || nextY < 0 || nextY >= h) {
            val deadly = state.selectedSpeed.hasWalls
            if (deadly) {
                if (!shield) return gameOver(state, events)
                shield = false
                shake = maxOf(shake, 0.4f)
            }
            nextX = (nextX + w) % w
            nextY = (nextY + h) % h
            if (deadly) events += GameEvent.ShieldBroken(Point(nextX, nextY))
        }

        val nextHead = Point(nextX, nextY)

        // Self collision (the tail cell is vacated this tick, so stepping onto it is legal)
        if (nextHead != snake.last() && snake.contains(nextHead)) {
            if (!shield) return gameOver(state, events)
            shield = false
            shake = maxOf(shake, 0.5f)
            events += GameEvent.ShieldBroken(nextHead)
        }

        // Obstacle collision
        if (obstacles.contains(nextHead)) {
            if (!shield) return gameOver(state, events)
            obstacles = obstacles.filter { it != nextHead } // shield shatters and destroys the obstacle
            shield = false
            shake = maxOf(shake, 0.6f)
            events += GameEvent.ShieldBroken(nextHead)
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
            events += GameEvent.FoodEaten(nextHead, eaten.type)

            // The multiplier applies to the GAIN only; applying it to the running total compounds exponentially.
            val multiplier = if (state.selectedSpeed == GameSpeed.HYPER_4D) 1.5 else 1.0
            score += (gain * multiplier).toInt()

            length += 1
            val newLevel = ((length - 3) / 3) + 1
            speedMs = speedMs(newLevel, state.selectedSpeed)
            warp = eaten.type == FoodType.PORTAL
            foods = foods.filterIndexed { i, _ -> i != eatenIndex }

            val levelUp = newLevel > level
            level = newLevel
            shake = maxOf(shake, if (levelUp) 0.7f else 0.3f)

            if (levelUp) {
                events += GameEvent.LevelUp(nextHead)
                val occupied = HashSet<Point>().apply {
                    addAll(snake)
                    addAll(obstacles)
                    foods.forEach { add(it.point) }
                }
                // Free cell that is not right in front of the head (otherwise death is unavoidable)
                val candidates = ArrayList<Point>()
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        val p = Point(x, y)
                        if (p !in occupied && abs(p.x - nextHead.x) + abs(p.y - nextHead.y) > SAFE_RADIUS) {
                            candidates.add(p)
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    val p = candidates[random.nextInt(candidates.size)]
                    obstacles = obstacles + p
                    events += GameEvent.ObstacleSpawned(p)
                }
            }
        } else if (snake.size > length) {
            snake.removeAt(snake.size - 1)
        }

        var next = state.copy(
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
            cameraShake = maxOf(state.cameraShake, shake)
        )

        if (ate) {
            events += GameEvent.TailGrew(snake.last())
            next = spawnFood(next) // sees the new head, so food can never appear under the snake
        }
        return StepResult(next, events)
    }

    /** Tops food up to the required count, choosing only from genuinely free cells. */
    fun spawnFood(state: GameUiState): GameUiState {
        val required = if (state.selectedSpeed == GameSpeed.HYPER_4D) 3 else 1
        val foods = state.foods.toMutableList()

        val occupied = HashSet<Point>().apply {
            addAll(state.snake)
            addAll(state.obstacles)
            foods.forEach { add(it.point) }
        }
        val free = ArrayList<Point>()
        for (x in 0 until state.gridWidth) {
            for (y in 0 until state.gridHeight) {
                val p = Point(x, y)
                if (p !in occupied) free.add(p)
            }
        }

        while (foods.size < required && free.isNotEmpty()) {
            val p = free.removeAt(random.nextInt(free.size))
            val roll = random.nextFloat()
            val type = when {
                roll < 0.12f -> FoodType.SHIELD
                roll < 0.28f -> FoodType.PORTAL
                else -> FoodType.NORMAL
            }
            foods.add(Food(p, type))
        }
        return state.copy(foods = foods)
    }

    private fun gameOver(state: GameUiState, events: MutableList<GameEvent>): StepResult {
        pendingDirs.clear()
        events += GameEvent.GameOver
        return StepResult(
            state.copy(gameState = GameState.GAME_OVER, cameraShake = maxOf(state.cameraShake, 0.9f)),
            events
        )
    }

    companion object {
        const val MIN_TICK_MS = 40L
        const val MAX_QUEUED_TURNS = 2
        const val SAFE_RADIUS = 3
    }
}
