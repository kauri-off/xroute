package dev.xroute

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EditorEditingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savesClearsAndRestoresTheRouteAcrossFullscreenAndRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("editor", Context.MODE_PRIVATE)
        val name = "Маршрут ${UUID.randomUUID().toString().take(8)}"
        val library = context.getSharedPreferences("routes", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("points", "[[55,37],[55.01,37.02]]").putString("speed", "4,5").commit()
        try {
            ActivityScenario.launch(Editor::class.java).use { screen ->
                compose.onNodeWithText("Сохранить…").performScrollTo().performClick()
                compose.onNodeWithText("Название маршрута").performTextInput(name)
                compose.onNodeWithText("Сохранить").performClick()
                compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Удалить ▾")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Удалить ▾").performClick()
                compose.onNodeWithText("Удалить все точки…").performClick()
                compose.onNodeWithText("Удалить все").performClick()
                compose.onNodeWithText("Точки").assertIsDisplayed()
                compose.onNodeWithText("Открыть…").performScrollTo().performClick()
                compose.onNodeWithText(name).performClick()
                compose.onNodeWithText("Точка 2 ▾").assertIsDisplayed()
                screen.onActivity { activity ->
                    val control = requireNotNull(activity.window.decorView.findMapControl("На весь экран"))
                    assertTrue(control.performClick())
                }
                compose.onNodeWithTag("playback-stop").assertDoesNotExist()
                screen.recreate()
                compose.waitForIdle()
                screen.onActivity { activity ->
                    val control = requireNotNull(activity.window.decorView.findMapControl("Свернуть карту"))
                    assertTrue(control.performClick())
                }
                compose.onNodeWithTag("playback-stop").assertIsDisplayed()
            }
            val restored = DraftStore(context).read()
            assertEquals(2, restored.points.size)
            assertEquals("4,5", restored.speed)
        } finally {
            library.getString(name, null)?.let { context.deleteSharedPreferences("route-$it") }
            library.edit().remove(name).commit()
            prefs.edit().clear().commit()
        }
    }

    private fun View.findMapControl(label: String): View? {
        if (contentDescription == label) return this
        if (this is ViewGroup) for (i in 0 until childCount) {
            getChildAt(i).findMapControl(label)?.let { return it }
        }
        return null
    }
}
