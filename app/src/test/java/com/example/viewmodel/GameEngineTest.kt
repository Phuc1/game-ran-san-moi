package com.example.viewmodel

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {

    private fun engine(seed: Int = 1) = GameEngine(Random(seed))

    /** Horizontal snake, head first, heading right, on a 20x20 grid. */
    private fun line(headX: Int, y: Int, length: Int) = (0 until length).map { Point(headX - it, y) }

    private fun playing(
        snake: List<Point> = line(5, 5, 3),
        dir: Direction = Direction.RIGHT,
        speed: GameSpeed = GameSpeed.MEDIUM,
        foods: List<Food> = emptyList(),
        obstacles: List<Point> = emptyList(),
        shield: Boolean = false,
        score: Int = 0,
        level: Int = 1,
    ) = GameUiState(
        snake = snake,
        direction = dir,
        foods = foods,
        obstacles = obstacles,
        score = score,
        snakeLength = snake.size,
        level = level,
        gameState = GameState.PLAYING,
        hasShield = shield,
        selectedSpeed = speed,
    )

    // ---- score ----------------------------------------------------------------------------

    @Test
    fun hyperMultiplierAppliesToGainOnly_notToRunningTotal() {
        val e = engine()
        var s = playing(speed = GameSpeed.HYPER_4D, foods = listOf(Food(Point(6, 5), FoodType.NORMAL)))
        s = e.step(s).state
        assertEquals(15, s.score) // 10 * 1.5

        s = s.copy(foods = listOf(Food(Point(7, 5), FoodType.NORMAL)))
        s = e.step(s).state
        // Old bug: (15 + 10) * 1.5 = 37. Correct: 15 + 15 = 30.
        assertEquals(30, s.score)
    }

    @Test
    fun normalModeScoresArePlainGains() {
        val e = engine()
        val s = e.step(playing(foods = listOf(Food(Point(6, 5), FoodType.PORTAL)))).state
        assertEquals(30, s.score)
        assertTrue(s.isWarpActive)
    }

    // ---- input queue ---------------------------------------------------------------------

    @Test
    fun directReversalIsRejected() {
        val e = engine()
        val s = playing()
        e.queueDirection(s.direction, Direction.LEFT) // opposite of RIGHT
        val next = e.step(s).state
        assertEquals(Direction.RIGHT, next.direction)
        assertEquals(Point(6, 5), next.snake.first())
        assertTrue(next.gameState != GameState.GAME_OVER)
    }

    @Test
    fun doubleSwipeBecomesTwoLegalTurns_notAReversalIntoTheNeck() {
        val e = engine()
        var s = playing()
        e.queueDirection(s.direction, Direction.UP)
        e.queueDirection(s.direction, Direction.LEFT) // legal relative to UP, would be a reversal vs RIGHT

        s = e.step(s).state
        assertEquals(Direction.UP, s.direction)
        assertEquals(Point(5, 4), s.snake.first())

        s = e.step(s).state
        assertEquals(Direction.LEFT, s.direction)
        assertEquals(Point(4, 4), s.snake.first())
        assertTrue(s.gameState != GameState.GAME_OVER)
    }

    @Test
    fun queueIsCappedAtTwoTurns() {
        val e = engine()
        var s = playing()
        e.queueDirection(s.direction, Direction.UP)
        e.queueDirection(s.direction, Direction.LEFT)
        e.queueDirection(s.direction, Direction.DOWN) // third: dropped
        repeat(3) { s = e.step(s).state }
        assertEquals(Direction.LEFT, s.direction)
        assertEquals(Point(3, 4), s.snake.first())
    }

    // ---- walls / wrap --------------------------------------------------------------------

    @Test
    fun easyModeWrapsAroundTheEdge() {
        val r = engine().step(playing(snake = line(19, 5, 3), speed = GameSpeed.EASY))
        assertEquals(Point(0, 5), r.state.snake.first())
        assertTrue(r.state.gameState != GameState.GAME_OVER)
    }

    @Test
    fun hardModeWallKillsWithoutShield() {
        val r = engine().step(playing(snake = line(19, 5, 3), speed = GameSpeed.HARD))
        assertEquals(GameState.GAME_OVER, r.state.gameState)
        assertTrue(r.events.contains(GameEvent.GameOver))
    }

    // ---- shield --------------------------------------------------------------------------

    @Test
    fun shieldAbsorbsWallOnce_andIsNotRestoredByFoodEatenInTheSameTick() {
        // Wall hit consumes the shield; food sits on the wrapped cell and is eaten in the same tick.
        val s = playing(
            snake = line(19, 5, 3),
            speed = GameSpeed.HARD,
            shield = true,
            foods = listOf(Food(Point(0, 5), FoodType.NORMAL)),
        )
        val next = engine().step(s).state
        assertEquals(GameState.PLAYING, next.gameState)
        assertFalse(next.hasShield) // old code re-applied the stale `true`
        assertEquals(Point(0, 5), next.snake.first())
    }

    @Test
    fun shieldBreaksOneObstacleThenNextObstacleKills() {
        val e = engine()
        var s = playing(obstacles = listOf(Point(6, 5), Point(7, 5)), shield = true)

        s = e.step(s).state
        assertEquals(GameState.PLAYING, s.gameState)
        assertFalse(s.hasShield)
        assertFalse(s.obstacles.contains(Point(6, 5)))
        assertTrue(s.obstacles.contains(Point(7, 5)))

        s = e.step(s).state
        assertEquals(GameState.GAME_OVER, s.gameState)
    }

    @Test
    fun shieldFoodGrantsShield() {
        val next = engine().step(playing(foods = listOf(Food(Point(6, 5), FoodType.SHIELD)))).state
        assertTrue(next.hasShield)
        assertEquals(20, next.score)
    }

    // ---- self collision ------------------------------------------------------------------

    @Test
    fun steppingOntoTheTailCellIsLegal() {
        // Tail is at (4,5); head (5,5) moves LEFT into it while the tail moves away.
        val snake = listOf(Point(5, 5), Point(5, 6), Point(4, 6), Point(4, 5))
        val next = engine().step(playing(snake = snake, dir = Direction.LEFT)).state
        assertEquals(GameState.PLAYING, next.gameState)
        assertEquals(Point(4, 5), next.snake.first())
    }

    @Test
    fun bitingTheBodyKills() {
        val snake = listOf(Point(5, 5), Point(5, 6), Point(4, 6), Point(4, 5), Point(3, 5))
        val next = engine().step(playing(snake = snake, dir = Direction.LEFT)).state
        assertEquals(GameState.GAME_OVER, next.gameState)
    }

    // ---- food spawning -------------------------------------------------------------------

    @Test
    fun foodNeverSpawnsOnSnakeObstacleOrOtherFood() {
        for (seed in 0 until 200) {
            val e = engine(seed)
            val s = e.newGame(20, 20, GameSpeed.HYPER_4D)
            assertEquals(3, s.foods.size)
            val cells = s.foods.map { it.point }
            assertEquals(cells.size, cells.toSet().size)
            assertTrue(cells.none { it in s.snake || it in s.obstacles })
        }
    }

    @Test
    fun foodDoesNotAppearUnderTheNewHead() {
        // Old code spawned food BEFORE committing the new snake, so it could land on the new head.
        for (seed in 0 until 200) {
            val e = engine(seed)
            val s = playing(foods = listOf(Food(Point(6, 5), FoodType.NORMAL)))
            val next = e.step(s).state
            assertTrue(next.foods.none { it.point in next.snake })
        }
    }

    @Test
    fun spawnFoodOnFullGridTerminatesWithNoFood() {
        val full = GameUiState(
            snake = listOf(Point(0, 0), Point(1, 0), Point(0, 1), Point(1, 1)),
            gridWidth = 2,
            gridHeight = 2,
        )
        assertTrue(engine().spawnFood(full).foods.isEmpty())
    }

    @Test
    fun spawnFoodUsesTheOnlyFreeCell() {
        val nearlyFull = GameUiState(
            snake = listOf(Point(0, 0), Point(1, 0), Point(0, 1)),
            gridWidth = 2,
            gridHeight = 2,
        )
        assertEquals(listOf(Point(1, 1)), engine().spawnFood(nearlyFull).foods.map { it.point })
    }

    // ---- level up ------------------------------------------------------------------------

    @Test
    fun levelUpObstacleNeverSpawnsNextToTheHead() {
        for (seed in 0 until 200) {
            val e = engine(seed)
            // length 5 -> eating makes 6 -> level 2
            val s = playing(snake = line(5, 5, 5), foods = listOf(Food(Point(6, 5), FoodType.NORMAL)))
            val r = e.step(s)
            assertTrue(r.events.any { it is GameEvent.LevelUp })
            val spawned = r.events.filterIsInstance<GameEvent.ObstacleSpawned>().single().at
            val head = r.state.snake.first()
            assertTrue(abs(spawned.x - head.x) + abs(spawned.y - head.y) > GameEngine.SAFE_RADIUS)
            assertTrue(spawned !in r.state.snake)
            assertEquals(2, r.state.level)
        }
    }

    // ---- misc ----------------------------------------------------------------------------

    @Test
    fun speedGetsFasterPerLevelAndFloorsAtMinimum() {
        val e = engine()
        assertEquals(260L, e.speedMs(1, GameSpeed.MEDIUM))
        assertEquals(230L, e.speedMs(3, GameSpeed.MEDIUM))
        assertEquals(GameEngine.MIN_TICK_MS, e.speedMs(100, GameSpeed.HYPER_4D))
    }

    @Test
    fun newGameKeepsTheStartingLaneClear() {
        for (seed in 0 until 100) {
            val s = engine(seed).newGame(20, 20, GameSpeed.HYPER_4D)
            val y = 20 / 2
            assertEquals(10, s.obstacles.size)
            assertTrue(s.obstacles.none { abs(it.y - y) <= 1 && it.x in (20 / 4 - 2)..(20 / 4 + 3 + 3) })
            assertEquals(GameState.IDLE, s.gameState)
        }
    }
}
