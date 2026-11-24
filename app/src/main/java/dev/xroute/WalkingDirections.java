package dev.xroute;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

final class WalkingDirections {
    static final int MAX_WAYPOINTS = 50;

    static List<Route.Point> fetch(List<Route.Point> waypoints) throws IOException {
        if (waypoints.size() < 2 || waypoints.size() > MAX_WAYPOINTS)
            throw new IOException("Для построения по дорожкам выберите от 2 до 50 точек");
        var connection = (HttpsURLConnection) new URL("https://valhalla1.openstreetmap.de/route").openConnection();
        try {
            JSONArray locations = new JSONArray();
            for (var point : waypoints)
                locations.put(new JSONObject().put("lat", point.lat()).put("lon", point.lon()));
            byte[] request = new JSONObject().put("locations", locations).put("costing", "pedestrian")
                    .put("directions_type", "none").put("shape_format", "polyline6")
                    .toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "XRoute/0.1 (io.github.kauri_off.xroute)");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(request.length);
            try (var out = connection.getOutputStream()) { out.write(request); }
            if (connection.getResponseCode() != 200)
                throw new IOException("Сервис не построил маршрут (HTTP " + connection.getResponseCode() + ")");
            try (var in = connection.getInputStream(); var response = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (response.size() + count > 1000000) throw new IOException("Ответ сервиса слишком большой");
                    response.write(buffer, 0, count);
                }
                return decode(response.toString("UTF-8"));
            }
        } catch (JSONException e) {
            throw new IOException("Некорректный запрос маршрута", e);
        } finally {
            connection.disconnect();
        }
    }

    static List<Route.Point> decode(String response) throws IOException {
        try {
            JSONArray legs = new JSONObject(response).getJSONObject("trip").getJSONArray("legs");
            var geometry = new ArrayList<Route.Point>();
            for (int i = 0; i < legs.length(); i++) {
                String encoded = legs.getJSONObject(i).getString("shape");
                int[] offset = {0};
                int lat = 0, lon = 0;
                while (offset[0] < encoded.length()) {
                    lat += delta(encoded, offset);
                    lon += delta(encoded, offset);
                    Route.Point point = new Route.Point(lat / 1000000.0, lon / 1000000.0);
                    if (geometry.isEmpty() || !geometry.get(geometry.size() - 1).equals(point)) geometry.add(point);
                    if (geometry.size() > 2000) throw new IOException("Маршрут слишком длинный, выберите более короткий участок");
                }
            }
            if (geometry.size() < 2) throw new IOException("Сервис не нашёл пешеходный путь");
            return geometry;
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Некорректный маршрут в ответе сервиса", e);
        }
    }

    private static int delta(String text, int[] offset) throws IOException {
        int value = 0;
        for (int shift = 0; shift <= 30 && offset[0] < text.length(); shift += 5) {
            int digit = text.charAt(offset[0]++) - 63;
            if (digit < 0 || digit > 63 || shift == 30 && digit > 1) break;
            value |= (digit & 31) << shift;
            if (digit < 32) return (value >>> 1) ^ -(value & 1);
        }
        throw new IOException("Повреждённая линия маршрута");
    }
}
