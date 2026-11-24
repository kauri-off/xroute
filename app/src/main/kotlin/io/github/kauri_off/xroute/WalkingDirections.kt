package io.github.kauri_off.xroute

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object WalkingDirections {
    const val MAX_WAYPOINTS = 50

    @Throws(IOException::class)
    fun fetch(waypoints: List<Route.Point>): List<Route.Point> {
        if (waypoints.size !in 2..MAX_WAYPOINTS) {
            throw IOException("Для построения по дорожкам выберите от 2 до 50 точек")
        }
        val connection = URL("https://valhalla1.openstreetmap.de/route").openConnection() as HttpsURLConnection
        try {
            val locations = JSONArray().apply {
                for (point in waypoints) put(JSONObject().put("lat", point.lat()).put("lon", point.lon()))
            }
            val request = JSONObject().put("locations", locations).put("costing", "pedestrian")
                .put("directions_type", "none").put("shape_format", "polyline6").toString().toByteArray()
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "XRoute/0.1 (io.github.kauri_off.xroute)")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(request.size)
            connection.outputStream.use { it.write(request) }
            val status = connection.responseCode
            if (status != 200) throw IOException("Сервис не построил маршрут (HTTP $status)")
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 1_000_000) throw IOException("Ответ сервиса слишком большой")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            return decode(response)
        } catch (error: JSONException) {
            throw IOException("Некорректный запрос маршрута", error)
        } finally {
            connection.disconnect()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decode(response: String): List<Route.Point> {
        val geometry = ArrayList<Route.Point>()
        try {
            val legs = JSONObject(response).getJSONObject("trip").getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val shape = Polyline(legs.getJSONObject(i).getString("shape"))
                var latitude = 0L
                var longitude = 0L
                while (shape.hasNext) {
                    latitude += shape.delta()
                    longitude += shape.delta()
                    val point = Route.Point(latitude / 1_000_000.0, longitude / 1_000_000.0)
                    if (geometry.lastOrNull() != point) geometry.add(point)
                    if (geometry.size > 2000) throw IOException("Маршрут слишком длинный, выберите более короткий участок")
                }
            }
        } catch (error: JSONException) {
            throw IOException("Некорректный маршрут в ответе сервиса", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Некорректный маршрут в ответе сервиса", error)
        }
        if (geometry.size < 2) throw IOException("Сервис не нашёл пешеходный путь")
        return geometry
    }

    private class Polyline(private val text: String) {
        private var offset = 0
        val hasNext: Boolean get() = offset < text.length

        fun delta(): Int {
            var value = 0
            for (shift in 0..30 step 5) {
                if (!hasNext) break
                val digit = text[offset++].code - 63
                if (digit !in 0..63 || (shift == 30 && digit > 1)) break
                value = value or ((digit and 31) shl shift)
                if (digit < 32) return (value ushr 1) xor -(value and 1)
            }
            throw IOException("Повреждённая линия маршрута")
        }
    }
}
