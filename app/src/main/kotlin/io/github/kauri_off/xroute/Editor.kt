package io.github.kauri_off.xroute

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.osmdroid.util.GeoPoint
import java.util.Locale

class Editor : ComponentActivity(), EditorMap.Listener {
    private val model by viewModels<EditorModel>()
    private lateinit var map: EditorMap
    private var expanded by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val systemBars = SystemBarStyle.light(Color.TRANSPARENT, Color.DKGRAY)
        enableEdgeToEdge(statusBarStyle = systemBars, navigationBarStyle = systemBars)
        map = EditorMap(this, EditorStyle(this), model.store.editor, this)
        expanded = savedInstanceState?.getBoolean("fullscreen") == true
        setContent {
            BackHandler(expanded) { expanded = false }
            LaunchedEffect(expanded) {
                map.fullscreen(expanded)
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (expanded) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
            }
            EditorScreen(model, map, expanded)
        }
    }

    override fun toggleFullscreen() { expanded = !expanded }
    override fun tap(point: GeoPoint) { model.tap(point.routePoint()) }
    override fun select(index: Int) { model.select(index) }
    override fun drag(index: Int, point: GeoPoint, finished: Boolean) {
        model.dragging = !finished
        if (finished) {
            model.move(index, point.routePoint())
        } else {
            model.preview = false
            val geometry = model.draft.points.map(Route.Point::geoPoint).toMutableList()
            if (index in geometry.indices) geometry[index] = point
            map.renderLine(geometry, false)
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        model.controller.resume()
    }

    override fun onPause() {
        model.controller.pause()
        model.preview = false
        model.dragging = false
        val edit = model.store.write(model.draft, model.store.editor.edit())
        edit.putString("target", model.controller.state().target)
        map.save(edit)
        edit.apply()
        map.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("fullscreen", expanded)
    }

    override fun onDestroy() {
        map.onDestroy()
        super.onDestroy()
    }
}

internal fun Route.Point.geoPoint() = GeoPoint(lat(), lon())
private fun GeoPoint.routePoint() = Route.Point(latitude, longitude)

internal fun duration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return if (total < 3600) String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    else String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
}
