package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real activity on an emulator. Run across several Android versions in CI
 * so layout/lifecycle regressions on older or newer OS releases are caught before users see them.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun menuIsGone() =
        rule.onAllNodesWithTag("play_game_button").fetchSemanticsNodes().isEmpty()

    @Test
    fun menuShowsTitleAndBothStartButtons() {
        rule.onNodeWithTag("app_logo_title").assertIsDisplayed()
        rule.onNodeWithTag("daily_challenge_button").assertIsDisplayed()
        rule.onNodeWithTag("play_game_button").assertIsDisplayed()
    }

    @Test
    fun freePlayStartsAndKeepsRunningForSeveralTicks() {
        rule.onNodeWithTag("play_game_button").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { menuIsGone() }
        Thread.sleep(2_000) // a few game ticks; a crash in the loop/engine would fail the run
        assert(menuIsGone())
    }

    @Test
    fun dailyChallengeStarts() {
        rule.onNodeWithTag("daily_challenge_button").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { menuIsGone() }
        Thread.sleep(2_000)
        assert(menuIsGone())
    }
}
