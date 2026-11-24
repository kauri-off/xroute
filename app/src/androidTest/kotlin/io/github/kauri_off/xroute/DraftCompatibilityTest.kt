package io.github.kauri_off.xroute

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftCompatibilityTest {
    @Test fun opensAndResavesAnExistingWalkingRoute() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = UUID.randomUUID().toString()
        val files = mutableSetOf<String>()
        val isolated = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("$prefix-$name".also(files::add), mode)
        }
        try {
            isolated.getSharedPreferences("routes", Context.MODE_PRIVATE).edit()
                .putString("Прогулка", "legacy").commit()
            isolated.getSharedPreferences("route-legacy", Context.MODE_PRIVATE).edit()
                .putString("points", "[[10,20],[10.02,20],[10.03,20]]")
                .putInt("selected", 1)
                .putString("speed", "4,5")
                .putInt("profile", 2)
                .putBoolean("round_turns", true)
                .putString("original_waypoints", "[[10,20],[10.03,20]]")
                .putInt("original_selection", 1).commit()
            val store = DraftStore(isolated)
            val draft = requireNotNull(store.open("Прогулка"))
            assertEquals(3, draft.points.size)
            assertEquals(1, draft.selected)
            assertEquals(2, draft.waypoints.size)
            assertEquals(1, draft.waypointSelection)
            assertEquals(Route.Profile.WAVE, draft.profile)
            assertTrue(draft.round)
            assertEquals(1.25, draft.buildRoute().meanMps, 0.0)
            store.save("Копия", draft)
            assertEquals(draft, DraftStore(isolated).open("Копия"))
        } finally {
            files.forEach(app::deleteSharedPreferences)
        }
    }
}
