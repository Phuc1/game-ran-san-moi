package com.example.viewmodel

import kotlin.math.max
import kotlin.random.Random

// Pure Kotlin (no Android / java.time, minSdk is 24) so every rule here is unit-testable on the JVM.

/** Persisted player progress. [lastPlayedDay] / [dailyDay] are local-calendar epoch days (-1 = never). */
data class PlayerStats(
    val lastPlayedDay: Long = -1L,
    val streak: Int = 0,
    val dailyDay: Long = -1L,
    val dailyBest: Int = 0
)

interface StatsStorage {
    fun load(): PlayerStats
    fun save(stats: PlayerStats)
}

object DailyChallenge {
    private const val MS_PER_DAY = 86_400_000L

    /** Local-calendar day number for [nowMillis] given the device's UTC offset. */
    fun epochDay(nowMillis: Long, utcOffsetMillis: Int): Long =
        Math.floorDiv(nowMillis + utcOffsetMillis, MS_PER_DAY)

    /** Same value for every player on the same calendar day. */
    fun seedFor(epochDay: Long): Long = epochDay * 1_000_003L + 7_919L

    /** "dd/MM" for an epoch day (civil-from-days algorithm, no java.time needed). */
    fun label(epochDay: Long): String {
        val (_, month, day) = civilFromEpochDay(epochDay)
        return day.toString().padStart(2, '0') + "/" + month.toString().padStart(2, '0')
    }

    fun civilFromEpochDay(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }
}

object StreakLogic {
    /** Updates streak and the daily best after a finished run. */
    fun recordRun(prev: PlayerStats, today: Long, score: Int, wasDaily: Boolean): PlayerStats {
        val streak = when {
            prev.lastPlayedDay == today -> max(prev.streak, 1)
            prev.lastPlayedDay == today - 1 -> prev.streak + 1
            prev.lastPlayedDay > today -> max(prev.streak, 1) // clock/timezone moved backwards: don't punish
            else -> 1
        }
        var dailyDay = prev.dailyDay
        var dailyBest = prev.dailyBest
        if (wasDaily) {
            if (prev.dailyDay != today) {
                dailyDay = today
                dailyBest = score
            } else {
                dailyBest = max(dailyBest, score)
            }
        }
        return PlayerStats(lastPlayedDay = today, streak = streak, dailyDay = dailyDay, dailyBest = dailyBest)
    }

    /** Streak to display: it is already broken if the last run was before yesterday. */
    fun activeStreak(stats: PlayerStats, today: Long): Int =
        if (stats.lastPlayedDay >= today - 1) stats.streak else 0

    /** Today's daily best, or 0 if the daily challenge hasn't been played today. */
    fun dailyBestToday(stats: PlayerStats, today: Long): Int = if (stats.dailyDay == today) stats.dailyBest else 0
}

/** Light-hearted game-over lines, chosen by how the run ended and how well it went. */
object Roasts {
    private val wall = listOf(
        "Tường đứng im từ nãy giờ, là bạn tự lao vào đấy.",
        "Bức tường thắng 1-0. Không cần VAR.",
        "Rắn kiểu gì mà đâm đầu vào tường vậy bạn ơi?"
    )
    private val self = listOf(
        "Tự cắn đuôi mình. Đói đến vậy sao?",
        "Rắn này tự đánh bại chính nó. Đối thủ đáng gờm nhất là… bạn.",
        "Đuôi bạn vừa ký đơn xin nghỉ việc."
    )
    private val obstacle = listOf(
        "Chướng ngại vật nằm đó từ đầu mà. Mắt bạn để đâu?",
        "Viên đá không nhường đường. Đá không bao giờ nhường.",
        "Va chạm kinh điển. Đá 1, rắn 0."
    )
    private val generic = listOf(
        "Ván này tới đây thôi. Làm lại ván nữa nào!",
        "Thua nhanh để mạnh nhanh. Chơi lại thôi!"
    )
    private val beginner = listOf(
        "Mới khởi động thôi, chưa tính nhé.",
        "Điểm này thì chỉ có thể lên thôi!"
    )
    private val strong = listOf(
        "Quá đỉnh! Chụp màn hình khoe bạn bè đi.",
        "Điểm này đáng để thách đấu cả hội."
    )
    private val legend = listOf(
        "Huyền thoại! Bạn có chắc mình không phải rắn thật?",
        "Con số này xứng đáng được in lên áo."
    )

    fun forGameOver(score: Int, cause: DeathCause?, random: Random = Random.Default): String {
        val pool = when {
            score >= 250 -> legend
            score >= 100 -> strong
            score < 30 -> beginner + causePool(cause)
            else -> causePool(cause)
        }
        return pool[random.nextInt(pool.size)]
    }

    private fun causePool(cause: DeathCause?): List<String> = when (cause) {
        DeathCause.WALL -> wall
        DeathCause.SELF -> self
        DeathCause.OBSTACLE -> obstacle
        null -> generic
    }
}

object ShareText {
    /** Replace with the Play Store link once the game is published. */
    const val APP_URL = "https://github.com/Phuc1/game-ran-san-moi"

    fun build(
        score: Int,
        snakeLength: Int,
        modeLabel: String,
        isDaily: Boolean,
        dayLabel: String,
        streak: Int
    ): String {
        val head = if (isDaily) {
            "🐍 Rắn Săn Mồi – Thử thách $dayLabel: $score điểm (dài $snakeLength)."
        } else {
            "🐍 Rắn Săn Mồi: $score điểm, rắn dài $snakeLength ở chế độ $modeLabel."
        }
        val tail = buildString {
            if (streak >= 2) append(" 🔥 Chuỗi $streak ngày.")
            append(" Bạn đánh bại được không? $APP_URL")
        }
        return head + tail
    }
}
