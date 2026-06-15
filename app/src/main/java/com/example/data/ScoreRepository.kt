package com.example.data

import kotlinx.coroutines.flow.Flow

class ScoreRepository(private val scoreDao: ScoreDao) {
    fun getTopScores(limit: Int = 10): Flow<List<ScoreEntity>> {
        return scoreDao.getTopScores(limit)
    }

    suspend fun insertScore(score: ScoreEntity) {
        scoreDao.insertScore(score)
    }

    suspend fun clearAllScores() {
        scoreDao.clearAllScores()
    }
}
