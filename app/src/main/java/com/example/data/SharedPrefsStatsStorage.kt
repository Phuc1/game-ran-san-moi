package com.example.data

import android.content.Context
import com.example.viewmodel.PlayerStats
import com.example.viewmodel.StatsStorage

/** Tiny key-value store for streak / daily best (no Room migration needed). */
class SharedPrefsStatsStorage(context: Context) : StatsStorage {
    private val prefs = context.applicationContext
        .getSharedPreferences("player_stats", Context.MODE_PRIVATE)

    override fun load() = PlayerStats(
        lastPlayedDay = prefs.getLong(KEY_LAST_DAY, -1L),
        streak = prefs.getInt(KEY_STREAK, 0),
        dailyDay = prefs.getLong(KEY_DAILY_DAY, -1L),
        dailyBest = prefs.getInt(KEY_DAILY_BEST, 0)
    )

    override fun save(stats: PlayerStats) {
        prefs.edit()
            .putLong(KEY_LAST_DAY, stats.lastPlayedDay)
            .putInt(KEY_STREAK, stats.streak)
            .putLong(KEY_DAILY_DAY, stats.dailyDay)
            .putInt(KEY_DAILY_BEST, stats.dailyBest)
            .apply()
    }

    private companion object {
        const val KEY_LAST_DAY = "lastPlayedDay"
        const val KEY_STREAK = "streak"
        const val KEY_DAILY_DAY = "dailyDay"
        const val KEY_DAILY_BEST = "dailyBest"
    }
}
