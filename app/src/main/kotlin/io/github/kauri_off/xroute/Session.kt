package io.github.kauri_off.xroute

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

@JvmRecord
internal data class Session(val target: String, val startMs: Long, val route: Route) {
    init {
        require(PACKAGE_NAME.matches(target) && target != BuildConfig.APPLICATION_ID) {
            "Укажите имя пакета целевого приложения"
        }
        require(startMs > 0) { "Некорректное время старта" }
    }

    @Throws(JSONException::class)
    fun encode(): String {
        val points = JSONArray().apply {
            for (point in route.points) put(JSONArray().put(point.lat()).put(point.lon()))
        }
        return JSONObject()
            .put("v", 2)
            .put("roundTurns", route.roundTurns)
            .put("target", target)
            .put("startMs", startMs)
            .put("meanMps", route.meanMps)
            .put("profile", route.profile.name)
            .put("points", points)
            .toString()
    }

    companion object {
        const val GROUP = "playback"
        const val KEY = "session"
        private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

        @JvmStatic
        @Throws(JSONException::class)
        fun decode(raw: String): Session {
            val json = JSONObject(raw)
            val version = json.getInt("v")
            if (version !in 1..2) throw JSONException("Неизвестная версия маршрута")
            val array = json.getJSONArray("points")
            if (array.length() > 2000) throw JSONException("Слишком много точек")
            val points = List(array.length()) {
                val point = array.getJSONArray(it)
                Route.Point(point.getDouble(0), point.getDouble(1))
            }
            val route = Route(
                points,
                json.getDouble("meanMps"),
                Route.Profile.valueOf(json.getString("profile")),
                version == 2 && json.getBoolean("roundTurns")
            )
            return Session(json.getString("target"), json.getLong("startMs"), route)
        }
    }
}
