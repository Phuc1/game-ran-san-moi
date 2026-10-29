package com.example.viewmodel

// Pure Kotlin model types (no Android / Compose imports) so they can be unit-tested on the JVM.

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
