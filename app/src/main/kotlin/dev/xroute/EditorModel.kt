package dev.xroute

import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EditorModel(application: Application) : AndroidViewModel(application) {
    val store = DraftStore(application)
    var draft by mutableStateOf(store.read())
        private set
    val route by derivedStateOf { runCatching { draft.buildRoute() }.getOrNull() }
    var moving by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var preview by mutableStateOf(false)
    var routing by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var fitRequest by mutableIntStateOf(0)
    var names by mutableStateOf(store.titles())
        private set
    private var revision = 0L

    var state by mutableStateOf<PlaybackState?>(null)
        private set
    val controller = PlaybackController(application as Companion, store.editor.getString("target", "").orEmpty()) {
        state = it
    }

    init {
        state = controller.state()
    }

    fun select(index: Int) {
        if (index !in draft.points.indices) return
        moving = false
        draft = draft.copy(selected = index)
    }

    fun edit(next: Draft) {
        draft = next
        revision++
        preview = false
        message = ""
    }

    fun tap(point: Route.Point) {
        if (moving && draft.selected in draft.points.indices) {
            move(draft.selected, point)
        } else if (draft.points.size < 2000) {
            edit(draft.copy(points = draft.points + point, selected = draft.points.size))
        } else message = "Достигнут лимит в 2000 точек"
        moving = false
    }

    fun move(index: Int, point: Route.Point) {
        if (index !in draft.points.indices) return
        edit(draft.copy(points = draft.points.toMutableList().apply { set(index, point) }, selected = index))
    }

    fun delete(all: Boolean) {
        val points = if (all) emptyList() else draft.points.filterIndexed { index, _ -> index != draft.selected }
        moving = false
        edit(draft.copy(points = points, selected = minOf(draft.selected, points.lastIndex),
            waypoints = if (all) emptyList() else draft.waypoints))
    }

    fun save(title: String) {
        store.save(title, draft)
        names = store.titles()
        message = "Маршрут сохранён"
    }

    fun open(title: String) {
        val saved = store.open(title) ?: return
        moving = false
        edit(saved)
        fitRequest++
    }

    fun restoreWaypoints() {
        if (draft.waypoints.isEmpty()) return
        replacePath(draft.copy(points = draft.waypoints, selected = draft.waypointSelection, waypoints = emptyList()))
        message = "Исходные точки восстановлены"
    }

    private fun replacePath(next: Draft) {
        moving = false
        edit(next)
        fitRequest++
    }

    fun buildWalkingRoute() {
        if (routing || draft.points.size !in 2..WalkingDirections.MAX_WAYPOINTS) return
        val request = revision
        val old = draft
        routing = true
        message = "Строим пешеходный путь…"
        viewModelScope.launch {
            try {
                val points = withContext(Dispatchers.IO) {
                    WalkingDirections.fetch(old.points).also { Route(it, 1.0, Route.Profile.LINEAR) }
                }
                if (revision == request) {
                    replacePath(old.copy(points = points, selected = 0, waypoints = old.points, waypointSelection = old.selected))
                    message = "Пешеходный путь добавлен"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision == request) message = "Путь не построен: ${e.message.orEmpty()}"
            } finally {
                routing = false
            }
        }
    }
}
