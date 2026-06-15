package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scores")
data class ScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val playerName: String,
    val score: Int,
    val snakeLength: Int,
    val speedMode: String, // "DỄ", "TRUNG BÌNH", "KHÓ", "4D HYPER"
    val timestamp: Long = System.currentTimeMillis()
)
