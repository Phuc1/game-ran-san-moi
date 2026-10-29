package com.example.viewmodel

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMetaTest {

    // ---- calendar ------------------------------------------------------------------------

    @Test
    fun civilDatesMatchKnownEpochDays() {
        assertEquals(Triple(1970, 1, 1), DailyChallenge.civilFromEpochDay(0))
        assertEquals(Triple(2000, 3, 1), DailyChallenge.civilFromEpochDay(11017))
        assertEquals(Triple(2024, 2, 29), DailyChallenge.civilFromEpochDay(19782)) // leap day
        assertEquals(Triple(2026, 10, 29), DailyChallenge.civilFromEpochDay(20755))
        assertEquals(Triple(2026, 12, 31), DailyChallenge.civilFromEpochDay(20818))
    }

    @Test
    fun labelIsZeroPaddedDayMonth() {
        assertEquals("29/10", DailyChallenge.label(20755))
        assertEquals("01/01", DailyChallenge.label(0))
    }

    @Test
    fun epochDayRespectsTheTimezoneOffset() {
        val utcLate = 20755L * 86_400_000L + 20 * 3_600_000L // 20:00 UTC on day 20755
        assertEquals(20755L, DailyChallenge.epochDay(utcLate, 0))
        assertEquals(20756L, DailyChallenge.epochDay(utcLate, 7 * 3_600_000)) // 03:00 next day in UTC+7
        assertEquals(-1L, DailyChallenge.epochDay(-1L, 0)) // floorDiv, not truncation
    }

    @Test
    fun seedIsStablePerDayAndDifferentAcrossDays() {
        assertEquals(DailyChallenge.seedFor(20755), DailyChallenge.seedFor(20755))
        assertTrue(DailyChallenge.seedFor(20755) != DailyChallenge.seedFor(20756))
    }

    // ---- streak --------------------------------------------------------------------------

    @Test
    fun firstRunStartsAStreakOfOne() {
        val s = StreakLogic.recordRun(PlayerStats(), today = 100, score = 10, wasDaily = false)
        assertEquals(1, s.streak)
        assertEquals(100L, s.lastPlayedDay)
    }

    @Test
    fun consecutiveDaysExtendTheStreak_sameDayDoesNot() {
        var s = StreakLogic.recordRun(PlayerStats(), 100, 10, false)
        s = StreakLogic.recordRun(s, 100, 20, false)
        assertEquals(1, s.streak)
        s = StreakLogic.recordRun(s, 101, 20, false)
        assertEquals(2, s.streak)
        s = StreakLogic.recordRun(s, 102, 20, false)
        assertEquals(3, s.streak)
    }

    @Test
    fun missingADayResetsTheStreak() {
        var s = StreakLogic.recordRun(PlayerStats(), 100, 10, false)
        s = StreakLogic.recordRun(s, 101, 10, false)
        s = StreakLogic.recordRun(s, 103, 10, false) // skipped 102
        assertEquals(1, s.streak)
    }

    @Test
    fun clockMovingBackwardsKeepsTheStreak() {
        var s = StreakLogic.recordRun(PlayerStats(), 100, 10, false)
        s = StreakLogic.recordRun(s, 101, 10, false)
        s = StreakLogic.recordRun(s, 100, 10, false)
        assertEquals(2, s.streak)
    }

    @Test
    fun dailyBestTracksTheMaximumPerDayAndResetsNextDay() {
        var s = StreakLogic.recordRun(PlayerStats(), 100, 50, wasDaily = true)
        s = StreakLogic.recordRun(s, 100, 30, wasDaily = true)
        assertEquals(50, StreakLogic.dailyBestToday(s, 100))
        s = StreakLogic.recordRun(s, 100, 80, wasDaily = true)
        assertEquals(80, StreakLogic.dailyBestToday(s, 100))
        assertEquals(0, StreakLogic.dailyBestToday(s, 101)) // new day, not played yet
        s = StreakLogic.recordRun(s, 101, 20, wasDaily = true)
        assertEquals(20, StreakLogic.dailyBestToday(s, 101))
    }

    @Test
    fun displayedStreakDropsToZeroOnceItIsBroken() {
        val s = StreakLogic.recordRun(StreakLogic.recordRun(PlayerStats(), 100, 10, false), 101, 10, false)
        assertEquals(2, StreakLogic.activeStreak(s, 101)) // played today
        assertEquals(2, StreakLogic.activeStreak(s, 102)) // can still be saved today
        assertEquals(0, StreakLogic.activeStreak(s, 103)) // missed a full day
        assertEquals(0, StreakLogic.activeStreak(PlayerStats(), 100))
    }

    @Test
    fun normalRunsDoNotTouchTheDailyBest() {
        val s = StreakLogic.recordRun(PlayerStats(), 100, 999, wasDaily = false)
        assertEquals(0, StreakLogic.dailyBestToday(s, 100))
    }

    // ---- roasts / share ------------------------------------------------------------------

    @Test
    fun roastIsNeverBlankForAnyScoreOrCause() {
        val causes = DeathCause.values().toList() + listOf<DeathCause?>(null)
        for (seed in 0 until 50) {
            for (score in listOf(0, 10, 29, 30, 99, 100, 249, 250, 1000)) {
                for (cause in causes) {
                    assertFalse(Roasts.forGameOver(score, cause, Random(seed)).isBlank())
                }
            }
        }
    }

    @Test
    fun highScoresGetPraiseNotRoasts() {
        val praise = (0 until 100).map { Roasts.forGameOver(300, DeathCause.WALL, Random(it)) }.toSet()
        assertTrue(praise.none { it.contains("tường", ignoreCase = true) })
    }

    @Test
    fun shareTextContainsScoreLinkAndStreakOnlyWhenRelevant() {
        val daily = ShareText.build(120, 18, "TRUNG BÌNH", true, "29/10", 3)
        assertTrue(daily.contains("120"))
        assertTrue(daily.contains("29/10"))
        assertTrue(daily.contains("Chuỗi 3"))
        assertTrue(daily.contains(ShareText.APP_URL))

        val plain = ShareText.build(40, 7, "KHÓ", false, "29/10", 1)
        assertTrue(plain.contains("KHÓ"))
        assertFalse(plain.contains("Chuỗi"))
        assertFalse(plain.contains("Thử thách"))
    }
}
