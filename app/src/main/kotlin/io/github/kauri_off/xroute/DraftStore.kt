package io.github.kauri_off.xroute

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.UUID

internal data class Draft(
    val points: List<Route.Point> = emptyList(),
    val selected: Int = -1,
    val speed: String = "5",
    val profile: Route.Profile = Route.Profile.LINEAR,
    val round: Boolean = false,
    val waypoints: List<Route.Point> = emptyList(),
    val waypointSelection: Int = 0
) {
    fun buildRoute(): Route {
        val kmh = speed.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        return Route(points, kmh / 3.6, profile, round)
    }
}

internal class DraftStore(private val context: Context) {
    val editor = prefs("editor")
    private val library = prefs("routes")
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun titles() = library.all.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun open(title: String): Draft? = library.getString(title, null)?.let { read(prefs("route-$it")) }

    fun delete(title: String) {
        val id = library.getString(title, null) ?: return
        if (library.edit().remove(title).commit()) context.deleteSharedPreferences("route-$id")
    }

    fun save(title: String, draft: Draft) {
        val id = library.getString(title, null) ?: UUID.randomUUID().toString()
        write(draft, prefs("route-$id").edit()).apply()
        library.edit().putString(title, id).apply()
    }

    fun read(source: SharedPreferences = editor): Draft {
        val points = readPoints(source, "points", 2000)
        val waypoints = readPoints(source, "original_waypoints", WalkingDirections.MAX_WAYPOINTS)
        return Draft(
            points,
            source.getInt("selected", points.lastIndex).coerceAtLeast(0).coerceAtMost(points.lastIndex),
            source.getString("speed", "5") ?: "5",
            Route.Profile.entries.getOrElse(source.getInt("profile", 0)) { Route.Profile.LINEAR },
            source.getBoolean("round_turns", false),
            waypoints.takeIf { it.size >= 2 }.orEmpty(),
            source.getInt("original_selection", 0).coerceIn(0, maxOf(0, waypoints.lastIndex))
        )
    }

    fun write(draft: Draft, edit: SharedPreferences.Editor): SharedPreferences.Editor = edit
        .putString("points", encode(draft.points)).putInt("selected", draft.selected)
        .putString("speed", draft.speed).putInt("profile", draft.profile.ordinal)
        .putBoolean("round_turns", draft.round)
        .putString("original_waypoints", encode(draft.waypoints)).putInt("original_selection", draft.waypointSelection)

    private fun readPoints(source: SharedPreferences, key: String, limit: Int): List<Route.Point> = buildList {
        val array = runCatching { JSONArray(source.getString(key, "[]")) }.getOrNull() ?: return@buildList
        for (i in 0 until minOf(array.length(), limit)) {
            val pair = array.optJSONArray(i) ?: break
            val point = runCatching { Route.Point(pair.getDouble(0), pair.getDouble(1)) }.getOrNull() ?: break
            add(point)
        }
    }

    private fun encode(points: List<Route.Point>) = JSONArray().apply {
        points.forEach { put(JSONArray().put(it.lat()).put(it.lon())) }
    }.toString()
}
