package com.theatresync.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Crash tests that run on a real Android system (emulator). */
@RunWith(AndroidJUnit4::class)
class CrashSafetyTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    /**
     * The 0.4.0 crash happened in onStop (going to the background, e.g. opening the file
     * picker). Send the app to the background and back many times in a row.
     */
    @Test
    fun backgroundAndForegroundRepeatedlyNeverCrashes() {
        repeat(15) {
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // onStop
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) // onStart/onResume
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Choose a movie").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Choose a movie").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun recreatingTheScreenNeverCrashes() {
        repeat(5) { rule.activityRule.scenario.recreate() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Choose a movie").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Choose a movie").performScrollTo().assertIsDisplayed()
    }

    /** A handled problem lands in the timeline and the GitHub link is built correctly. */
    @Test
    fun handledProblemIsRecordedAndLinksToGitHub() {
        val ctx = rule.activity
        CrashReporter.handled(ctx, "test", IllegalStateException("synthetic test problem"))
        val e = CrashReporter.entries(ctx).first()
        assertEquals(CrashReporter.Kind.HANDLED, e.kind)
        assertTrue(e.title.contains("synthetic test problem"))
        assertTrue(e.report.contains("Where: test"))
        val uri = CrashReporter.gitHubIssueUri(e)
        assertEquals("github.com", uri.host)
        assertTrue(uri.path!!.endsWith("/issues/new"))
        assertEquals("crash", uri.getQueryParameter("labels"))
        assertTrue(uri.getQueryParameter("body")!!.contains("synthetic test problem"))
        CrashReporter.mark(ctx, e.id, CrashReporter.Status.SHARED)
        assertEquals(CrashReporter.Status.SHARED, CrashReporter.entries(ctx).first { it.id == e.id }.status)
    }

    @Test
    fun menuOpensSettingsAndCrashTimeline() {
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithTag("settings_crashes").performClick()
        rule.onNodeWithText("Crash reports").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Settings").assertIsDisplayed()
    }

    /** Owner request: leaving for another app and coming back keeps the page you were on. */
    @Test
    fun pageSurvivesBeingRecreatedInTheBackground() {
        rule.onNodeWithText("Check headphones", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Headphones").assertIsDisplayed()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.recreate() // what Android does when it reclaims the app in the background
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Headphones").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Headphones").assertIsDisplayed()
        rule.onNodeWithTag("pick_device").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settingsShowsAutomaticCrashReports() {
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("settings_github").performClick()
        rule.onNodeWithText("Automatic crash reports").assertIsDisplayed()
    }
}
