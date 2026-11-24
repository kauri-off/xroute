package dev.xroute;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import io.github.libxposed.service.XposedService;
import org.json.JSONArray;
import org.json.JSONException;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.*;
import java.io.File;
import java.util.ArrayList;

public final class Editor extends Activity implements MapEventsReceiver {
    private final ArrayList<GeoPoint> points = new ArrayList<>();
    private final ArrayList<Marker> markers = new ArrayList<>();
    private final Polyline line = new Polyline();
    private MapView map;
    private EditText target, speed;
    private Spinner profile;
    private TextView status;
    private SharedPreferences draft;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        draft = getSharedPreferences("editor", MODE_PRIVATE);
        Configuration.getInstance().setUserAgentValue("XRoute/0.1 (dev.xroute)");
        Configuration.getInstance().setOsmdroidBasePath(new File(getCacheDir(), "osm"));
        Configuration.getInstance().setOsmdroidTileCache(new File(getCacheDir(), "osm/tiles"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        map = new MapView(this);
        map.setMultiTouchControls(true);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.getController().setZoom((double) draft.getFloat("zoom", 15));
        map.getController().setCenter(new GeoPoint((double) draft.getFloat("lat", 55.75f),
                (double) draft.getFloat("lon", 37.62f)));
        map.getOverlays().add(new MapEventsOverlay(this));
        map.getOverlays().add(line);
        root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView attribution = new TextView(this);
        attribution.setText("© OpenStreetMap contributors");
        attribution.setOnClickListener(v -> startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.openstreetmap.org/copyright"))));
        root.addView(attribution);
        target = new EditText(this);
        target.setSingleLine(true);
        target.setHint("Имя пакета приложения");
        target.setText(draft.getString("target", ""));
        root.addView(target);
        LinearLayout settings = new LinearLayout(this);
        speed = new EditText(this);
        speed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        speed.setText(draft.getString("speed", "5"));
        settings.addView(speed);
        TextView units = new TextView(this);
        units.setText("км/ч");
        settings.addView(units);
        profile = new Spinner(this);
        profile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Равномерно", "Разгон и торможение", "Волнами"}));
        profile.setSelection(draft.getInt("profile", 0));
        settings.addView(profile);
        root.addView(settings);
        LinearLayout actions = new LinearLayout(this);
        button(actions, "Убрать точку", v -> {
            if (points.isEmpty()) return;
            points.remove(points.size() - 1);
            map.getOverlays().remove(markers.remove(markers.size() - 1));
            redraw();
        });
        button(actions, "Старт", v -> publish(true));
        button(actions, "Стоп", v -> publish(false));
        root.addView(actions);
        status = new TextView(this);
        root.addView(status);
        try {
            JSONArray saved = new JSONArray(draft.getString("points", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONArray p = saved.getJSONArray(i);
                append(new GeoPoint(p.getDouble(0), p.getDouble(1)));
            }
        } catch (JSONException ignored) {}
        redraw();
    }

    private void button(LinearLayout row, String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(action);
        row.addView(button);
    }

    private void append(GeoPoint point) {
        points.add(point);
        Marker marker = new Marker(map);
        marker.setPosition(point);
        marker.setTitle("Точка " + points.size());
        markers.add(marker);
        map.getOverlays().add(marker);
    }

    private void redraw() {
        line.setPoints(points);
        map.invalidate();
        status.setText("Точек: " + points.size() + ". Нажмите на карту, чтобы добавить точку.");
    }

    @Override public boolean singleTapConfirmedHelper(GeoPoint p) {
        if (points.size() >= 2000) return true;
        append(p);
        redraw();
        return true;
    }

    @Override public boolean longPressHelper(GeoPoint p) { return false; }

    private void publish(boolean start) {
        XposedService service = ((Companion) getApplication()).service;
        if (service == null) {
            status.setText("Нет соединения с Vector. Включите модуль в менеджере.");
            return;
        }
        try {
            var edit = service.getRemotePreferences(Session.GROUP).edit();
            if (start) {
                var path = new ArrayList<Route.Point>();
                for (GeoPoint p : points) path.add(new Route.Point(p.getLatitude(), p.getLongitude()));
                Route route = new Route(path, Double.parseDouble(speed.getText().toString().replace(',', '.')) / 3.6,
                        Route.Profile.values()[profile.getSelectedItemPosition()]);
                Session session = new Session(target.getText().toString().trim(), System.currentTimeMillis(), route);
                if (!service.getScope().contains(session.target())) {
                    status.setText("Добавьте пакет в область действия модуля в Vector и перезапустите целевое приложение.");
                    return;
                }
                edit.putString(Session.KEY, session.encode());
            } else {
                edit.remove(Session.KEY);
            }
            if (!edit.commit()) throw new IllegalStateException("Vector не подтвердил запись");
            status.setText(start ? "Маршрут опубликован" : "Воспроизведение остановлено");
        } catch (NumberFormatException e) {
            status.setText("Введите скорость в км/ч");
        } catch (RuntimeException | JSONException e) {
            status.setText("Ошибка: " + e.getMessage());
        }
    }

    @Override protected void onResume() { super.onResume(); map.onResume(); }

    @Override protected void onPause() {
        JSONArray saved = new JSONArray();
        for (GeoPoint p : points) saved.put(new JSONArray(java.util.Arrays.asList(p.getLatitude(), p.getLongitude())));
        draft.edit().putString("points", saved.toString()).putString("target", target.getText().toString())
                .putString("speed", speed.getText().toString()).putInt("profile", profile.getSelectedItemPosition())
                .putFloat("zoom", (float) map.getZoomLevelDouble()).putFloat("lat", (float) map.getMapCenter().getLatitude())
                .putFloat("lon", (float) map.getMapCenter().getLongitude()).apply();
        map.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() { map.onDetach(); super.onDestroy(); }
}
