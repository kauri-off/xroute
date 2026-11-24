package dev.xroute;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import io.github.libxposed.service.XposedService;
import org.json.JSONArray;
import org.json.JSONException;
import org.osmdroid.util.GeoPoint;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static dev.xroute.EditorStyle.*;

public final class Editor extends Activity implements EditorMap.Listener {
    private static final int POINT_LIMIT = 2_000;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final NumberFormat DISTANCE = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru"));
    private static final String[] PROFILES = {"Равномерно", "Разгон и торможение", "Волнами"};

    private final ArrayList<GeoPoint> points = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService directions = Executors.newSingleThreadExecutor();
    private final Runnable refreshVector = this::refreshVector;
    private final Runnable sessionClock = this::updateSessionClock;
    private final Runnable previewFrame = this::updatePreview;

    private Companion companion;
    private SharedPreferences draft;
    private EditorStyle style;
    private EditorMap editorMap;
    private LinearLayout root, header, mapPanel;
    private EditText target, speed;
    private Spinner profile;
    private Switch roundTurns;
    private Button pointPicker, move, delete, scopeRequest, routeRequest, undoRoute, preview, start, stop;
    private TextView connection, scopeStatus, routeStatus, speedStatus, sessionStatus;
    private LinearLayout controls;
    private boolean split;
    private int selected = -1;
    private boolean moving, dragging, updatingFields, destroyed;
    private boolean routing, publishing, scopePending, vectorLoading, vectorFailed;
    private XposedService service;
    private List<String> scope;
    private Session active;
    private boolean sessionStored;
    private String scopeMessage;
    private String sessionMessage;
    private Route route, previewRoute;
    private List<GeoPoint> originalWaypoints = Collections.emptyList();
    private int originalSelection;
    private long generation, previewStarted;
    private double previewScale;
    private boolean previewPlaying;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        companion = (Companion) getApplication();
        draft = getSharedPreferences("editor", MODE_PRIVATE);
        style = new EditorStyle(this);
        restorePoints();

        root = style.column();
        root.setBackgroundColor(BACKGROUND);
        root.setForceDarkAllowed(false);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        configureInsets();
        header = createHeader();
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        var config = getResources().getConfiguration();
        split = config.screenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(split ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content.setPadding(style.dp(12), 0, style.dp(12), style.dp(8));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        mapPanel = createMapPanel();
        controls = createControls();
        if (split) {
            content.addView(mapPanel, new LinearLayout.LayoutParams(0, -1, 1));
            int controlWidth = Math.max(300, Math.min(410, config.screenWidthDp * 43 / 100));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(style.dp(controlWidth), -1);
            params.setMarginStart(style.dp(12));
            content.addView(controls, params);
        } else {
            content.addView(mapPanel, new LinearLayout.LayoutParams(-1, 0, 1.04f));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, 1);
            params.topMargin = style.dp(4);
            content.addView(controls, params);
        }

        updatingFields = true;
        target.setText(draft.getString("target", ""));
        speed.setText(draft.getString("speed", "5"));
        profile.setSelection(Math.max(0, Math.min(draft.getInt("profile", 0), PROFILES.length - 1)));
        roundTurns.setChecked(draft.getBoolean("round_turns", false));
        updatingFields = false;
        target.addTextChangedListener(textWatcher(() -> {
            scopeMessage = null;
            invalidateDraft();
            updateVectorLabels();
        }));
        speed.addTextChangedListener(textWatcher(() -> changeMotion(false)));
        profile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View item, int position, long id) {
                if (route == null || route.profile.ordinal() != position) changeMotion(false);
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        roundTurns.setOnCheckedChangeListener((button, checked) -> changeMotion(true));
        editorMap.renderPoints(points, selected);
        updatePointControls();
        updateRoute(true);
        updateVectorLabels();
        root.requestFocus();
        root.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private void configureInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light, light);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars;
            int keyboard;
            if (Build.VERSION.SDK_INT >= 30) {
                bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                bars = insets.getStableInsets();
                Insets system = insets.getSystemWindowInsets();
                keyboard = system.bottom;
                bars = Insets.of(Math.max(bars.left, system.left), Math.max(bars.top, system.top),
                        Math.max(bars.right, system.right), bars.bottom);
            }
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard));
            boolean typing = keyboard > bars.bottom + style.dp(60);
            if (mapPanel != null) mapPanel.setVisibility(typing ? View.GONE : View.VISIBLE);
            if (header != null) header.setVisibility(typing ? View.GONE : View.VISIBLE);
            if (split && controls != null) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) controls.getLayoutParams();
                int width = typing ? -1 : style.dp(Math.max(300,
                        Math.min(410, getResources().getConfiguration().screenWidthDp * 43 / 100)));
                if (params.width != width) {
                    params.width = width;
                    params.setMarginStart(typing ? 0 : style.dp(12));
                    controls.setLayoutParams(params);
                }
            }
            if (sessionStatus != null) {
                int available = getWindow().getDecorView().getHeight() - bars.top - keyboard;
                sessionStatus.setVisibility(typing && available < style.dp(155) ? View.GONE : View.VISIBLE);
            }
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
    }

    private LinearLayout createHeader() {
        LinearLayout bar = style.row();
        bar.setPadding(style.dp(16), style.dp(12), style.dp(16), style.dp(12));
        LinearLayout titles = style.column();
        TextView title = style.label("XRoute", 26, INK, true);
        title.setAccessibilityHeading(true);
        titles.addView(title);
        TextView subtitle = style.label("Редактор маршрута", 13, MUTED, false);
        subtitle.setPadding(0, style.dp(2), 0, 0);
        titles.addView(subtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        connection = style.label("", 12, INK, true);
        connection.setPadding(style.dp(11), style.dp(8), style.dp(11), style.dp(8));
        bar.addView(connection, new LinearLayout.LayoutParams(-2, -2));
        return bar;
    }

    private LinearLayout createMapPanel() {
        LinearLayout panel = style.column();
        editorMap = new EditorMap(this, style, draft, this);
        panel.addView(editorMap.view, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout tools = style.row();
        tools.setPadding(0, style.dp(6), 0, style.dp(2));
        pointPicker = style.button("Точки", INK, SURFACE, this::choosePoint);
        move = style.button("Перенести", INK, SURFACE, this::toggleMove);
        delete = style.button("Удалить", WARNING, SURFACE, this::deletePoint);
        style.weightedButton(tools, pointPicker, 1.15f, 0);
        style.weightedButton(tools, move, 1.1f, 6);
        style.weightedButton(tools, delete, 1, 6);
        panel.addView(tools, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    private LinearLayout createControls() {
        LinearLayout panel = style.column();
        panel.setBackground(style.shape(SURFACE, 18));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout fields = style.column();
        fields.setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(12));
        scroll.addView(fields, new ScrollView.LayoutParams(-1, -2));

        fields.addView(style.heading("Целевое приложение"));
        style.spacing(fields, 7);
        target = new EditText(this);
        target.setId(View.generateViewId());
        target.setSingleLine(true);
        target.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        target.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        target.setHint("org.example.app");
        target.setContentDescription("Имя пакета целевого приложения");
        target.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_NEXT) return false;
            speed.requestFocus();
            return true;
        });
        style.styleField(target);
        fields.addView(target, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout scopeRow = style.row();
        scopeStatus = style.label("", 12, MUTED, false);
        scopeStatus.setPadding(0, style.dp(8), style.dp(6), style.dp(8));
        scopeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        scopeRow.addView(scopeStatus, new LinearLayout.LayoutParams(0, -2, 1));
        scopeRequest = style.button("Запросить", ACCENT, ACCENT_LIGHT, this::requestScope);
        scopeRequest.setContentDescription("Запросить область действия для пакета в Vector");
        scopeRow.addView(scopeRequest, new LinearLayout.LayoutParams(-2, -2));
        fields.addView(scopeRow, new LinearLayout.LayoutParams(-1, -2));

        style.spacing(fields, 10);
        fields.addView(style.heading("Движение"));
        style.spacing(fields, 7);
        LinearLayout motion = style.row();
        motion.setGravity(Gravity.TOP);
        LinearLayout speedColumn = style.column();
        LinearLayout profileColumn = style.column();
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(0, -2, 0.85f);
        speedParams.setMarginEnd(style.dp(8));
        motion.addView(speedColumn, speedParams);
        motion.addView(profileColumn, new LinearLayout.LayoutParams(0, -2, 1.3f));
        speed = new EditText(this);
        speed.setId(View.generateViewId());
        speed.setSingleLine(true);
        speed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        speed.setImeOptions(EditorInfo.IME_ACTION_DONE);
        speed.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            closeKeyboard();
            return true;
        });
        style.styleField(speed);
        fieldLabel(speedColumn, "Скорость, км/ч", speed);
        speedColumn.addView(speed, new LinearLayout.LayoutParams(-1, -2));
        speedStatus = style.label("", 12, WARNING, false);
        speedStatus.setPadding(0, style.dp(4), 0, 0);
        speedColumn.addView(speedStatus);

        profile = new Spinner(this, Spinner.MODE_DROPDOWN);
        profile.setId(View.generateViewId());
        ArrayAdapter<String> profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, PROFILES) {
            @Override public View getView(int position, View view, android.view.ViewGroup parent) {
                TextView label = (TextView) super.getView(position, view, parent);
                label.setTextColor(INK);
                label.setTextSize(14);
                label.setGravity(Gravity.CENTER_VERTICAL);
                label.setSingleLine(false);
                label.setMaxLines(2);
                return label;
            }
        };
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profile.setAdapter(profileAdapter);
        profile.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        profile.setMinimumHeight(style.dp(48));
        fieldLabel(profileColumn, "Профиль скорости", profile);
        profileColumn.addView(profile, new LinearLayout.LayoutParams(-1, -2));
        fields.addView(motion, new LinearLayout.LayoutParams(-1, -2));

        roundTurns = new Switch(this);
        roundTurns.setText("Скруглять повороты");
        roundTurns.setMinHeight(style.dp(48));
        roundTurns.setTextSize(14);
        roundTurns.setTextColor(INK);
        roundTurns.setThumbTintList(new ColorStateList(new int[][] {
                {android.R.attr.state_checked}, {}
        }, new int[] {ACCENT, MUTED}));
        roundTurns.setTrackTintList(ColorStateList.valueOf(0xffa5c1b6));
        fields.addView(roundTurns, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout directionsRow = style.row();
        routeRequest = style.button("По дорожкам", ACCENT, ACCENT_LIGHT, this::buildWalkingRoute);
        directionsRow.addView(routeRequest, new LinearLayout.LayoutParams(-2, -2));
        TextView provider = style.label("Координаты передаются в Valhalla (FOSSGIS)", 12, MUTED, false);
        provider.setPadding(style.dp(9), 0, 0, 0);
        directionsRow.addView(provider, new LinearLayout.LayoutParams(0, -2, 1));
        fields.addView(directionsRow, new LinearLayout.LayoutParams(-1, -2));
        undoRoute = style.button("Вернуть исходные точки", ACCENT, ACCENT_LIGHT, this::restoreWaypoints);
        LinearLayout.LayoutParams undoParams = new LinearLayout.LayoutParams(-2, -2);
        undoParams.topMargin = style.dp(6);
        fields.addView(undoRoute, undoParams);
        routeStatus = style.label("", 12, WARNING, false);
        routeStatus.setPadding(0, style.dp(5), 0, 0);
        routeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        fields.addView(routeStatus);

        View separator = new View(this);
        separator.setBackgroundColor(STROKE);
        panel.addView(separator, new LinearLayout.LayoutParams(-1, style.dp(1)));
        LinearLayout footer = style.column();
        footer.setPadding(style.dp(9), style.dp(6), style.dp(9), style.dp(8));
        sessionStatus = style.label("", 12, MUTED, false);
        sessionStatus.setPadding(style.dp(4), 0, style.dp(4), style.dp(6));
        sessionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(sessionStatus, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = style.row();
        preview = style.button("Предпросмотр", ACCENT, ACCENT_LIGHT, this::togglePreview);
        start = style.button("Старт", Color.WHITE, ACCENT, () -> publish(true));
        stop = style.button("Стоп", WARNING, 0xfff8e8e6, () -> publish(false));
        style.weightedButton(actions, preview, 1.65f, 0);
        style.weightedButton(actions, start, 1, 5);
        style.weightedButton(actions, stop, 0.85f, 5);
        footer.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    private void fieldLabel(LinearLayout column, String text, View field) {
        TextView label = style.label(text, 12, MUTED, true);
        label.setPadding(0, 0, 0, style.dp(4));
        label.setLabelFor(field.getId());
        column.addView(label);
    }

    private TextWatcher textWatcher(Runnable change) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override public void afterTextChanged(Editable text) {
                if (!updatingFields) change.run();
            }
        };
    }

    @Override public void tap(GeoPoint point) {
        closeKeyboard();
        if (moving && selected >= 0) {
            points.set(selected, point);
            moving = false;
        } else if (points.size() < POINT_LIMIT) {
            points.add(point);
            selected = points.size() - 1;
        } else {
            editorMap.hint("Достигнут лимит в 2000 точек");
            return;
        }
        changePoints();
    }

    @Override public void select(int index) {
        int old = selected;
        selected = index;
        moving = false;
        editorMap.updateSelection(old, selected);
        updatePointControls();
        if (!previewPlaying) updateMapHint();
    }

    @Override public void drag(int index, GeoPoint point, boolean finished) {
        if (selected != index || moving) select(index);
        points.set(index, point);
        dragging = !finished;
        if (finished) {
            changePoints();
        } else {
            invalidateDraft();
            editorMap.renderLine(points, false);
            updateActions();
        }
    }

    private void choosePoint() {
        if (points.isEmpty()) return;
        String[] options = new String[points.size()];
        for (int i = 0; i < points.size(); i++) {
            GeoPoint point = points.get(i);
            options[i] = String.format(Locale.ROOT, "%d     %.6f, %.6f",
                    i + 1, point.getLatitude(), point.getLongitude());
        }
        new AlertDialog.Builder(this).setTitle("Точки маршрута")
                .setSingleChoiceItems(options, selected, (dialog, index) -> {
                    select(index);
                    editorMap.center(points.get(index));
                    dialog.dismiss();
                }).setNegativeButton("Закрыть", null).show();
    }

    private void toggleMove() {
        if (selected < 0) return;
        resetPreview();
        moving = !moving;
        updatePointControls();
        updateMapHint();
    }

    private void deletePoint() {
        if (selected < 0) return;
        points.remove(selected);
        selected = Math.min(selected, points.size() - 1);
        moving = false;
        changePoints();
    }

    private void changePoints() {
        invalidateDraft();
        editorMap.renderPoints(points, selected);
        updatePointControls();
        updateRoute(true);
    }

    private void updatePointControls() {
        pointPicker.setText(selected >= 0 ? "Точка " + (selected + 1) + " ▾" : "Точки");
        pointPicker.setContentDescription(selected >= 0 ? "Выбрать точку. Выбрана точка " + (selected + 1)
                + " из " + points.size() : "Точки маршрута");
        pointPicker.setEnabled(!points.isEmpty());
        move.setEnabled(selected >= 0);
        move.setText(moving ? "Отмена" : "Перенести");
        move.setContentDescription(moving ? "Отменить перенос" : "Перенести выбранную точку");
        delete.setEnabled(selected >= 0);
        delete.setContentDescription("Удалить выбранную точку");
    }

    private void changeMotion(boolean redraw) {
        if (updatingFields) return;
        invalidateDraft();
        updateRoute(redraw);
    }

    private void invalidateDraft() {
        generation++;
        setRouteStatus("", false);
        resetPreview();
    }

    private ArrayList<Route.Point> routePoints() {
        ArrayList<Route.Point> path = new ArrayList<>(points.size());
        for (GeoPoint point : points) path.add(new Route.Point(point.getLatitude(), point.getLongitude()));
        return path;
    }

    private List<GeoPoint> mapPoints(List<Route.Point> path) {
        ArrayList<GeoPoint> coordinates = new ArrayList<>(path.size());
        for (Route.Point point : path) coordinates.add(new GeoPoint(point.lat(), point.lon()));
        return coordinates;
    }

    private void updateRoute(boolean redraw) {
        String entered = speed.getText().toString().trim().replace(',', '.');
        double kmh;
        try {
            kmh = Double.parseDouble(entered);
        } catch (NumberFormatException ignored) {
            kmh = Double.NaN;
        }
        boolean validSpeed = kmh / 3.6 > 0 && kmh <= 360;
        Route shape = null;
        String pathError = null;
        if (points.size() >= 2) {
            try {
                int index = Math.max(0, profile.getSelectedItemPosition());
                shape = new Route(routePoints(), validSpeed ? kmh / 3.6 : 1,
                        Route.Profile.values()[index], roundTurns.isChecked());
            } catch (IllegalArgumentException e) {
                pathError = e.getMessage();
            }
        }
        if (shape != null && !Double.isFinite(shape.durationSeconds())) validSpeed = false;
        speedStatus.setText(validSpeed ? "" : "Введите скорость выше нуля, до 360 км/ч");
        speedStatus.setVisibility(validSpeed ? View.GONE : View.VISIBLE);
        route = validSpeed ? shape : null;
        if (redraw) {
            List<GeoPoint> geometry = shape == null ? new ArrayList<>(points) : mapPoints(shape.geometry());
            editorMap.renderLine(geometry, shape != null);
        }
        String count = points.size() + " " + pointWord(points.size());
        editorMap.caption(shape == null ? count : count + "  ·  "
                + distanceText(shape.durationSeconds() * shape.meanMps)
                + (validSpeed ? "  ·  " + durationText(shape.durationSeconds()) : ""));
        if (pathError != null) editorMap.hint(pathError);
        else updateMapHint();
        updateActions();
    }

    private void updateMapHint() {
        if (moving) {
            editorMap.hint("Коснитесь новой позиции точки " + (selected + 1));
        } else if (points.size() < 2) {
            editorMap.hint("Коснитесь карты, чтобы добавить точку");
        } else if (selected >= 0 && points.size() <= 5) {
            editorMap.hint("Удерживайте точку, чтобы перетащить");
        } else {
            editorMap.hint("");
        }
    }

    private void buildWalkingRoute() {
        if (routing || dragging || points.size() < 2 || points.size() > WalkingDirections.MAX_WAYPOINTS) return;
        closeKeyboard();
        List<Route.Point> from = routePoints();
        long requestGeneration = generation;
        routing = true;
        updateActions();
        setRouteStatus("Строим пешеходный путь…", false);
        directions.execute(() -> {
            try {
                List<Route.Point> result = WalkingDirections.fetch(from);
                if (result.size() > POINT_LIMIT) throw new IOException("Путь длиннее лимита в 2000 точек");
                if (result.size() < 2) throw new IOException("Сервис вернул меньше двух точек");
                new Route(result, 1, Route.Profile.LINEAR);
                main.post(() -> finishWalkingRoute(requestGeneration, result, null));
            } catch (IOException | RuntimeException e) {
                main.post(() -> finishWalkingRoute(requestGeneration, null, message(e)));
            }
        });
    }

    private void finishWalkingRoute(long requestGeneration, List<Route.Point> result, String error) {
        if (destroyed) return;
        routing = false;
        updateActions();
        if (requestGeneration != generation) {
            setRouteStatus("Черновик изменился. Запустите построение ещё раз.", false);
            return;
        }
        if (error != null) {
            setRouteStatus("Путь не построен: " + error, true);
            return;
        }
        originalWaypoints = new ArrayList<>(points);
        originalSelection = selected;
        points.clear();
        points.addAll(mapPoints(result));
        selected = 0;
        moving = false;
        changePoints();
        editorMap.fit();
        setRouteStatus("Пешеходный путь добавлен", false);
        saveDraft();
    }

    private void restoreWaypoints() {
        if (originalWaypoints.isEmpty() || dragging) return;
        points.clear();
        points.addAll(originalWaypoints);
        selected = originalSelection;
        originalWaypoints = Collections.emptyList();
        moving = false;
        changePoints();
        editorMap.fit();
        setRouteStatus("Исходные точки восстановлены", false);
        saveDraft();
    }

    private void setRouteStatus(String value, boolean error) {
        routeStatus.setText(value);
        routeStatus.setTextColor(error ? WARNING : MUTED);
        routeStatus.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void togglePreview() {
        if (previewPlaying) {
            resetPreview();
            updateMapHint();
            return;
        }
        if (route == null) return;
        closeKeyboard();
        moving = false;
        updatePointControls();
        editorMap.fit();
        previewRoute = route;
        previewScale = Math.max(1, route.durationSeconds() / 24);
        previewStarted = SystemClock.elapsedRealtime();
        previewPlaying = true;
        preview.setText("Скрыть просмотр");
        updatePreview();
    }

    private void updatePreview() {
        if (!previewPlaying) return;
        double elapsed = (SystemClock.elapsedRealtime() - previewStarted) / 1000.0 * previewScale;
        double duration = previewRoute.durationSeconds();
        boolean arrived = elapsed >= duration;
        Route.Fix fix = previewRoute.sample(Math.min(elapsed, duration));
        editorMap.showPreview(fix);
        String time = durationText(Math.min(elapsed, duration)) + " / " + durationText(duration);
        String rate = String.format(Locale.forLanguageTag("ru"), "%.1f км/ч", fix.speedMps() * 3.6);
        String scale = previewScale > 1 ? String.format(Locale.forLanguageTag("ru"), "  ·  ×%.1f", previewScale) : "";
        editorMap.hint(arrived ? "Финиш предпросмотра  ·  " + durationText(duration) : time + "  ·  " + rate + scale);
        if (!arrived) main.postDelayed(previewFrame, 33);
    }

    private void resetPreview() {
        if (!previewPlaying) return;
        main.removeCallbacks(previewFrame);
        previewPlaying = false;
        previewRoute = null;
        editorMap.showPreview(null);
        preview.setText("Предпросмотр");
        updateMapHint();
    }

    private String packageName() {
        return target.getText().toString().trim();
    }

    private boolean validTarget() {
        String name = packageName();
        return PACKAGE.matcher(name).matches() && !name.equals(getPackageName());
    }

    private void refreshVector() {
        var bound = companion.service();
        if (bound != service) {
            service = bound;
            scope = null;
            active = null;
            sessionStored = false;
            vectorFailed = false;
            scopePending = false;
            scopeMessage = null;
            sessionMessage = null;
        }
        if (bound == null) {
            vectorLoading = false;
            updateVectorLabels();
            return;
        }
        vectorLoading = true;
        updateVectorLabels();
        companion.vector.execute(() -> {
            List<String> packages = null;
            Session session = null;
            boolean stored = false;
            boolean failed = false;
            boolean read = false;
            try {
                packages = bound.getScope();
            } catch (RuntimeException ignored) {}
            try {
                failed = companion.sessionUnconfirmed(bound);
                if (!failed) {
                    String raw = bound.getRemotePreferences(Session.GROUP).getString(Session.KEY, null);
                    read = true;
                    stored = raw != null;
                    if (stored) session = Session.decode(raw);
                }
            } catch (RuntimeException | JSONException e) {
                failed = true;
            }
            List<String> allowed = packages;
            Session current = session;
            boolean exists = stored, readFailed = failed, readConfirmed = read;
            main.post(() -> {
                if (destroyed || service != bound) return;
                scope = allowed;
                if (readConfirmed) {
                    active = current;
                    sessionStored = exists;
                }
                vectorFailed = readFailed;
                vectorLoading = false;
                updateVectorLabels();
            });
        });
    }

    private void requestScope() {
        if (service == null || !validTarget() || scopePending) return;
        closeKeyboard();
        XposedService bound = service;
        String requested = packageName();
        scopePending = true;
        scopeMessage = "Подтвердите запрос в Vector.";
        updateVectorLabels();
        companion.vector.execute(() -> {
            try {
                bound.requestScope(Collections.singletonList(requested), new XposedService.OnScopeEventListener() {
                    @Override public void onScopeRequestApproved(List<String> packages) {
                        main.post(() -> finishScopeRequest(bound, requested,
                                packages != null && packages.contains(requested), null));
                    }

                    @Override public void onScopeRequestFailed(String reason) {
                        main.post(() -> finishScopeRequest(bound, requested, false, reason));
                    }
                });
            } catch (RuntimeException e) {
                main.post(() -> finishScopeRequest(bound, requested, false, message(e)));
            }
        });
    }

    private void finishScopeRequest(XposedService bound, String requested, boolean approved, String reason) {
        if (destroyed || service != bound) return;
        scopePending = false;
        if (requested.equals(packageName())) {
            scopeMessage = approved ? "Доступ выдан. Перезапустите целевое приложение."
                    : "Добавьте пакет в области действия XRoute в Vector."
                    + (reason == null || reason.trim().isEmpty() ? "" : " " + reason);
        }
        refreshVector();
    }

    private void publish(boolean play) {
        if (service == null || publishing || (play && (route == null || !validTarget()))) return;
        closeKeyboard();
        XposedService bound = service;
        String name = packageName();
        Route snapshot = route;
        publishing = true;
        sessionMessage = play ? "Публикуем маршрут…" : "Останавливаем сессию…";
        updateVectorLabels();
        companion.vector.execute(() -> {
            try {
                if (play && !bound.getScope().contains(name)) {
                    main.post(() -> finishPublish(bound, null, "Пакет отсутствует в области действия XRoute."));
                    return;
                }
                var edit = bound.getRemotePreferences(Session.GROUP).edit();
                Session session = play ? new Session(name, System.currentTimeMillis(), snapshot) : null;
                if (session == null) edit.remove(Session.KEY);
                else edit.putString(Session.KEY, session.encode());
                if (!companion.commitSession(bound, edit)) {
                    throw new IllegalStateException("Vector не подтвердил запись");
                }
                main.post(() -> finishPublish(bound, session, null));
            } catch (RuntimeException | JSONException e) {
                main.post(() -> finishPublish(bound, null, "Ошибка Vector: " + message(e)));
            }
        });
    }

    private void finishPublish(XposedService bound, Session session, String error) {
        if (destroyed) return;
        publishing = false;
        if (service != bound) {
            updateVectorLabels();
            return;
        }
        if (error == null) {
            active = session;
            sessionStored = session != null;
            vectorFailed = false;
            sessionMessage = null;
        } else {
            sessionMessage = error;
        }
        refreshVector();
    }

    private boolean inScope() {
        return scope != null && scope.contains(packageName());
    }

    private void updateVectorLabels() {
        boolean connected = service != null;
        connection.setText(connected ? "●  Vector" : "○  Vector");
        connection.setContentDescription(connected ? "Vector подключён" : "Vector не подключён");
        connection.setBackground(style.shape(connected ? ACCENT_LIGHT : SURFACE, 20));
        String info;
        String name = packageName();
        if (!connected) info = "Включите XRoute в менеджере Vector.";
        else if (name.isEmpty()) info = "Укажите пакет приложения для области действия.";
        else if (!validTarget()) info = "Проверьте имя пакета целевого приложения.";
        else if (scopeMessage != null) info = scopeMessage;
        else if (inScope()) info = "Пакет в области действия Vector.";
        else if (scope == null && vectorLoading) info = "Проверяем область действия…";
        else if (scope == null) info = "Проверьте область действия XRoute в Vector.";
        else info = "Добавьте пакет в область действия Vector.";
        scopeStatus.setText(info);
        scopeRequest.setVisibility(connected && validTarget() && !inScope() ? View.VISIBLE : View.GONE);
        scopeRequest.setEnabled(!scopePending && !publishing);
        updateSessionClock();
        updateActions();
    }

    private void updateSessionClock() {
        main.removeCallbacks(sessionClock);
        String value;
        if (sessionMessage != null) value = sessionMessage;
        else if (service == null) value = "Vector не подключён";
        else if (active != null) {
            double elapsed = (System.currentTimeMillis() - active.startMs()) / 1000.0;
            boolean ended = elapsed >= active.route().durationSeconds();
            value = (ended ? "Финиш · " : "Активная сессия · ") + active.target();
            if (!ended && hasWindowFocus()) main.postDelayed(sessionClock, 1_000);
        } else if (sessionStored) value = "Сохранённая сессия не читается";
        else if (vectorLoading) value = "Проверяем сессию…";
        else if (vectorFailed) value = "Не удалось прочитать сессию Vector";
        else value = "Нет активной сессии";
        if (!value.contentEquals(sessionStatus.getText())) sessionStatus.setText(value);
    }

    private void updateActions() {
        boolean withinDirectionsLimit = points.size() <= WalkingDirections.MAX_WAYPOINTS;
        routeRequest.setEnabled(points.size() >= 2 && withinDirectionsLimit && !routing && !dragging);
        routeRequest.setText(routing ? "Строим…" : "По дорожкам");
        undoRoute.setVisibility(originalWaypoints.isEmpty() ? View.GONE : View.VISIBLE);
        undoRoute.setEnabled(!dragging);
        if (!withinDirectionsLimit && routeStatus.getVisibility() == View.GONE) {
            setRouteStatus("Для построения по дорожкам доступно до "
                    + WalkingDirections.MAX_WAYPOINTS + " точек.", false);
        }
        preview.setEnabled(route != null && !dragging);
        start.setEnabled(service != null && !publishing && route != null && validTarget()
                && inScope() && !dragging);
        start.setText(sessionStored ? "Заново" : "Старт");
        start.setContentDescription(sessionStored ? "Запустить черновик заново" : "Запустить маршрут");
        stop.setEnabled(service != null && !publishing && (sessionStored || vectorFailed));
    }

    private void closeKeyboard() {
        InputMethodManager input = getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(root.getWindowToken(), 0);
        root.requestFocus();
    }

    private void restorePoints() {
        readPoints("points", POINT_LIMIT, points);
        if (!points.isEmpty()) {
            selected = Math.min(points.size() - 1, Math.max(0, draft.getInt("selected", points.size() - 1)));
        }
        ArrayList<GeoPoint> savedWaypoints = new ArrayList<>();
        readPoints("original_waypoints", WalkingDirections.MAX_WAYPOINTS, savedWaypoints);
        if (savedWaypoints.size() >= 2) {
            originalWaypoints = savedWaypoints;
            originalSelection = Math.min(savedWaypoints.size() - 1, Math.max(0, draft.getInt("original_selection", 0)));
        }
    }

    private void readPoints(String key, int limit, List<GeoPoint> destination) {
        try {
            JSONArray saved = new JSONArray(draft.getString(key, "[]"));
            for (int i = 0; i < Math.min(saved.length(), limit); i++) {
                JSONArray point = saved.getJSONArray(i);
                Route.Point checked = new Route.Point(point.getDouble(0), point.getDouble(1));
                destination.add(new GeoPoint(checked.lat(), checked.lon()));
            }
        } catch (JSONException | IllegalArgumentException ignored) {}
    }

    private static String encodePoints(List<GeoPoint> points) {
        JSONArray saved = new JSONArray();
        for (GeoPoint point : points) {
            saved.put(new JSONArray(Arrays.asList(point.getLatitude(), point.getLongitude())));
        }
        return saved.toString();
    }

    private void saveDraft() {
        var edit = draft.edit().putString("points", encodePoints(points)).putInt("selected", selected)
                .putString("target", target.getText().toString()).putString("speed", speed.getText().toString())
                .putInt("profile", Math.max(0, profile.getSelectedItemPosition()))
                .putBoolean("round_turns", roundTurns.isChecked());
        if (originalWaypoints.isEmpty()) {
            edit.remove("original_waypoints").remove("original_selection");
        } else {
            edit.putString("original_waypoints", encodePoints(originalWaypoints))
                    .putInt("original_selection", originalSelection);
        }
        editorMap.save(edit);
        edit.apply();
    }

    private static String distanceText(double meters) {
        DISTANCE.setMaximumFractionDigits(meters < 1_000 ? 0 : 2);
        return meters < 1_000 ? DISTANCE.format(meters) + " м" : DISTANCE.format(meters / 1_000) + " км";
    }

    private static String durationText(double seconds) {
        long s = Math.max(0, (long) seconds);
        if (s < 60) return s == 0 && seconds > 0 ? "<1 с" : s + " с";
        if (s < 3_600) return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3_600, s / 60 % 60, s % 60);
    }

    private static String pointWord(int count) {
        int last = count % 10;
        if (count % 100 >= 11 && count % 100 <= 14) return "точек";
        return last == 1 ? "точка" : last >= 2 && last <= 4 ? "точки" : "точек";
    }

    private static String message(Exception error) {
        String detail = error.getMessage();
        return detail == null || detail.trim().isEmpty() ? "операция не выполнена" : detail;
    }

    @Override protected void onResume() {
        super.onResume();
        editorMap.onResume();
        companion.observe(refreshVector);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && sessionStatus != null) updateSessionClock();
    }

    @Override protected void onPause() {
        companion.unobserve(refreshVector);
        main.removeCallbacks(sessionClock);
        resetPreview();
        saveDraft();
        editorMap.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        directions.shutdownNow();
        editorMap.onDestroy();
        super.onDestroy();
    }
}
