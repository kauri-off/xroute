package dev.xroute;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;

record Session(String target, long startMs, Route route) {
    static final String GROUP = "playback";
    static final String KEY = "session";

    Session {
        if (!target.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") || target.equals("dev.xroute"))
            throw new IllegalArgumentException("Укажите имя пакета целевого приложения");
        if (startMs <= 0) throw new IllegalArgumentException("Некорректное время старта");
    }

    String encode() throws JSONException {
        JSONArray points = new JSONArray();
        for (Route.Point p : route.points) points.put(new JSONArray().put(p.lat()).put(p.lon()));
        return new JSONObject().put("v", 1).put("target", target).put("startMs", startMs)
                .put("meanMps", route.meanMps).put("profile", route.profile.name()).put("points", points).toString();
    }

    static Session decode(String raw) throws JSONException {
        JSONObject json = new JSONObject(raw);
        if (json.getInt("v") != 1) throw new JSONException("Неизвестная версия маршрута");
        JSONArray array = json.getJSONArray("points");
        if (array.length() > 2000) throw new JSONException("Слишком много точек");
        var points = new ArrayList<Route.Point>();
        for (int i = 0; i < array.length(); i++) {
            JSONArray p = array.getJSONArray(i);
            points.add(new Route.Point(p.getDouble(0), p.getDouble(1)));
        }
        return new Session(json.getString("target"), json.getLong("startMs"),
                new Route(points, json.getDouble("meanMps"), Route.Profile.valueOf(json.getString("profile"))));
    }
}
