package io.github.kauri_off.xroute

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorPlaybackTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun publishesEditedDraftThenStopsThroughServiceBinder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val framework = TestFramework()
        framework.bind(context)
        val draft = context.getSharedPreferences("editor", Context.MODE_PRIVATE)
        assertTrue(draft.edit().clear().putString("points", "[[55.75,37.62],[55.751,37.621]]")
            .putString("target", "org.example.old").putString("speed", "5").commit())
        try {
            ActivityScenario.launch(Editor::class.java).use { screen ->
                clickWhenEnabled("playback-start")
                compose.waitUntil(5000) { framework.published() != null }
                val session = Session.decode(checkNotNull(framework.published()))
                assertEquals(TestFramework.TRACKER, session.target)
                assertEquals(2, session.route.points.size)
                assertEquals(5 / 3.6, session.route.meanMps, 0.000001)
                val source = SessionPlayback(TestFramework.TRACKER, framework.targetPreferences) { throw AssertionError(it) }
                val other = SessionPlayback("org.example.other", framework.targetPreferences) { throw AssertionError(it) }
                val fix = checkNotNull(source.sample("gps", session.startMs + 1000, 1000000000L))
                assertTrue(fix.latitude > session.route.points.first().lat())
                assertNull(other.sample("gps"))
                screen.recreate()
                compose.waitUntil(5000) {
                    runCatching {
                        val info = compose.onNodeWithTag("playback-progress").fetchSemanticsNode()
                            .config[SemanticsProperties.ProgressBarRangeInfo]
                        info.current in 0f..1f
                    }.getOrDefault(false)
                }
                clickWhenEnabled("playback-stop")
                compose.waitUntil(5000) { framework.published() == null }
                assertNull(source.sample("gps"))
            }
        } finally {
            draft.edit().clear().commit()
        }
    }

    private fun clickWhenEnabled(tag: String) {
        val button = compose.onNodeWithTag(tag)
        compose.waitUntil(10000) {
            runCatching { !button.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }.getOrDefault(false)
        }
        button.performClick()
    }
}
