package dev.xroute;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;

import static dev.xroute.EditorStyle.*;

final class EditorMap {
    interface Listener {
        void tap(GeoPoint point);
        void select(int index);
        void drag(int index, GeoPoint point, boolean finished);
    }

    final FrameLayout view;
    private final MapView map;
    private final TextView summary, hint;
    private final Polyline border, line;
    private final PreviewMarker preview = new PreviewMarker();
    private final ArrayList<Marker> markers = new ArrayList<>();
    private final EditorStyle style;
    private final Listener listener;

    EditorMap(Activity activity, EditorStyle style, SharedPreferences draft, Listener listener) {
        this.style = style;
        this.listener = listener;
        var config = Configuration.getInstance();
        config.setUserAgentValue("XRoute/0.1 (" + activity.getPackageName() + ")");
        config.setOsmdroidBasePath(new File(activity.getCacheDir(), "osm"));
        config.setOsmdroidTileCache(new File(activity.getCacheDir(), "osm/tiles"));
        view = new FrameLayout(activity);
        view.setBackground(style.shape(0xffdce9e4, 18));
        view.setClipToOutline(true);
        map = new MapView(activity);
        map.setMultiTouchControls(true);
        map.setVerticalMapRepetitionEnabled(false);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.getMapOverlay().setLoadingBackgroundColor(0xffdce9e4);
        map.getMapOverlay().setLoadingLineColor(0xffd0e0da);
        map.getZoomController().setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER);
        map.getController().setZoom((double) draft.getFloat("zoom", 15));
        map.getController().setCenter(new GeoPoint(
                draft.getFloat("lat", 55.75f), draft.getFloat("lon", 37.62f)));
        view.addView(map, new FrameLayout.LayoutParams(-1, -1));
        map.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint point) {
                listener.tap(point);
                return true;
            }

            @Override public boolean longPressHelper(GeoPoint point) {
                return false;
            }
        }));
        border = routeLine(Color.WHITE, 8);
        line = routeLine(ACCENT, 4);

        summary = style.label("", 13, INK, true);
        summary.setPadding(style.dp(12), style.dp(9), style.dp(12), style.dp(9));
        summary.setBackground(style.shape(0xfaffffff, 11));
        summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams summaryParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        summaryParams.setMargins(style.dp(10), style.dp(10), style.dp(60), 0);
        view.addView(summary, summaryParams);

        LinearLayout tools = style.column();
        FrameLayout.LayoutParams toolsParams = new FrameLayout.LayoutParams(style.dp(48), -2,
                Gravity.TOP | Gravity.END);
        toolsParams.topMargin = style.dp(10);
        toolsParams.setMarginEnd(style.dp(10));
        view.addView(tools, toolsParams);
        mapButton(tools, "Показать весь маршрут", "fit", this::fit);
        mapButton(tools, "Увеличить масштаб", "plus", () -> map.getController().zoomIn());
        mapButton(tools, "Уменьшить масштаб", "minus", () -> map.getController().zoomOut());
        mapButton(tools, "Установить точку в центре карты", "center", () -> {
            IGeoPoint center = map.getMapCenter();
            listener.tap(new GeoPoint(center.getLatitude(), center.getLongitude()));
        });

        hint = style.label("", 12, INK, true);
        hint.setPadding(style.dp(10), style.dp(7), style.dp(10), style.dp(7));
        hint.setBackground(style.shape(0xebffffff, 11));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        hintParams.setMarginStart(style.dp(10));
        hintParams.setMarginEnd(style.dp(10));
        hintParams.bottomMargin = style.dp(43);
        view.addView(hint, hintParams);

        TextView attribution = style.label("© OpenStreetMap contributors", 11, INK, false);
        attribution.setGravity(Gravity.CENTER_VERTICAL);
        attribution.setPadding(style.dp(9), style.dp(3), style.dp(9), style.dp(3));
        attribution.setMinHeight(style.dp(30));
        attribution.setBackground(style.ripple(0xefffffff, 9));
        attribution.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"));
            try {
                activity.startActivity(intent);
            } catch (android.content.ActivityNotFoundException ignored) {
                android.widget.Toast.makeText(activity, "www.openstreetmap.org/copyright",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        FrameLayout.LayoutParams creditParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        creditParams.setMarginStart(style.dp(7));
        creditParams.bottomMargin = style.dp(7);
        view.addView(attribution, creditParams);
        view.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            boolean compact = bottom - top < style.dp(230);
            boolean horizontal = tools.getOrientation() == LinearLayout.HORIZONTAL;
            if (compact == horizontal) return;
            tools.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            toolsParams.width = compact ? -2 : style.dp(48);
            toolsParams.gravity = (compact ? Gravity.BOTTOM : Gravity.TOP) | Gravity.END;
            toolsParams.topMargin = compact ? 0 : style.dp(10);
            toolsParams.bottomMargin = compact ? style.dp(40) : 0;
            tools.setLayoutParams(toolsParams);
            for (int i = 0; i < tools.getChildCount(); i++) {
                View child = tools.getChildAt(i);
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
                params.width = style.dp(48);
                params.bottomMargin = compact ? 0 : style.dp(5);
                params.setMarginStart(compact && i > 0 ? style.dp(5) : 0);
                child.setLayoutParams(params);
            }
            hintParams.bottomMargin = compact ? style.dp(93) : style.dp(43);
            hint.setLayoutParams(hintParams);
        });
    }

    private Polyline routeLine(int color, int width) {
        Polyline path = new Polyline(map);
        path.getOutlinePaint().setColor(color);
        path.getOutlinePaint().setStrokeWidth(style.dp(width));
        path.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        path.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        path.setOnClickListener((polyline, mapView, point) -> false);
        map.getOverlays().add(path);
        return path;
    }

    private void mapButton(LinearLayout column, String label, String icon, Runnable action) {
        ImageButton button = new ImageButton(view.getContext());
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setBackground(style.ripple(0xfaffffff, 12));
        button.setImageDrawable(new MapIcon(icon));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, style.dp(48));
        params.bottomMargin = style.dp(5);
        column.addView(button, params);
    }

    void renderPoints(List<GeoPoint> points, int selected) {
        ArrayList<Overlay> overlays = new ArrayList<>(points.size() + 1);
        markers.clear();
        boolean dense = points.size() > 45;
        for (int i = 0; i < points.size(); i++) {
            int index = i;
            Marker marker = new Marker(map) {
                @Override public boolean onTouchEvent(MotionEvent event, MapView view) {
                    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && mIsDragged) {
                        mIsDragged = false;
                        listener.drag(index, getPosition(), true);
                        return true;
                    }
                    return super.onTouchEvent(event, view);
                }
            };
            marker.setPosition(points.get(i));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            marker.setInfoWindow(null);
            marker.setDraggable(true);
            marker.setDragOffset(0);
            marker.setIcon(new Pin(index, index == selected, dense));
            marker.setOnMarkerClickListener((clicked, mapView) -> {
                listener.select(index);
                return true;
            });
            marker.setOnMarkerDragListener(new Marker.OnMarkerDragListener() {
                @Override public void onMarkerDragStart(Marker dragged) {
                    GeoPoint original = points.get(index);
                    dragged.setPosition(original);
                    listener.drag(index, original, false);
                }

                @Override public void onMarkerDrag(Marker dragged) {
                    listener.drag(index, dragged.getPosition(), false);
                }

                @Override public void onMarkerDragEnd(Marker dragged) {
                    listener.drag(index, dragged.getPosition(), true);
                }
            });
            markers.add(marker);
            if (index != selected) overlays.add(marker);
        }
        if (selected >= 0) overlays.add(markers.get(selected));
        overlays.add(preview);
        map.getOverlays().subList(3, map.getOverlays().size()).clear();
        map.getOverlays().addAll(overlays);
        map.invalidate();
    }

    void updateSelection(int oldIndex, int index) {
        if (oldIndex == index) return;
        boolean dense = markers.size() > 45;
        if (oldIndex >= 0 && oldIndex < markers.size()) {
            markers.get(oldIndex).setIcon(new Pin(oldIndex, false, dense));
        }
        if (index >= 0) {
            Marker marker = markers.get(index);
            marker.setIcon(new Pin(index, true, dense));
            map.getOverlays().remove(marker);
            map.getOverlays().add(map.getOverlays().size() - 1, marker);
        }
        map.invalidate();
    }

    void renderLine(List<GeoPoint> geometry, boolean geodesic) {
        border.setGeodesic(geodesic);
        line.setGeodesic(geodesic);
        border.setPoints(geometry);
        line.setPoints(geometry);
        map.invalidate();
    }

    void caption(String value) {
        summary.setText(value);
    }

    void hint(String value) {
        if (!value.contentEquals(hint.getText())) hint.setText(value);
        int visibility = value.isEmpty() ? View.GONE : View.VISIBLE;
        if (visibility != hint.getVisibility()) hint.setVisibility(visibility);
    }

    void showPreview(Route.Fix fix) {
        preview.fix = fix;
        map.invalidate();
    }

    void fit() {
        List<GeoPoint> points = line.getActualPoints();
        if (points.isEmpty() || !map.isLayoutOccurred()) return;
        if (points.size() == 1) {
            map.getController().animateTo(points.get(0));
            return;
        }
        int shortSide = Math.min(map.getWidth(), map.getHeight());
        if (shortSide <= 0) return;
        map.zoomToBoundingBox(routeBounds(points), false,
                Math.min(style.dp(46), shortSide / 5), 19, null);
    }

    private static BoundingBox routeBounds(List<GeoPoint> points) {
        GeoPoint first = points.get(0);
        double north = first.getLatitude(), south = north;
        double previous = first.getLongitude(), unwrapped = previous;
        double east = unwrapped, west = unwrapped;
        for (int i = 1; i < points.size(); i++) {
            GeoPoint point = points.get(i);
            north = Math.max(north, point.getLatitude());
            south = Math.min(south, point.getLatitude());
            double longitude = point.getLongitude();
            unwrapped += wrapLongitude(longitude - previous);
            east = Math.max(east, unwrapped);
            west = Math.min(west, unwrapped);
            previous = longitude;
        }
        var tiles = MapView.getTileSystem();
        north = Math.min(tiles.getMaxLatitude(), Math.max(tiles.getMinLatitude(), north));
        south = Math.min(tiles.getMaxLatitude(), Math.max(tiles.getMinLatitude(), south));
        return east - west >= 360
                ? new BoundingBox(north, 180, south, -180)
                : new BoundingBox(north, wrapLongitude(east), south, wrapLongitude(west));
    }

    private static double wrapLongitude(double longitude) {
        double wrapped = longitude % 360;
        if (wrapped > 180) return wrapped - 360;
        if (wrapped < -180) return wrapped + 360;
        return wrapped;
    }

    void center(GeoPoint point) {
        map.getController().animateTo(point);
    }

    void save(SharedPreferences.Editor edit) {
        var center = map.getMapCenter();
        edit.putFloat("zoom", (float) map.getZoomLevelDouble())
                .putFloat("lat", (float) center.getLatitude())
                .putFloat("lon", (float) center.getLongitude());
    }

    void onResume() { map.onResume(); }
    void onPause() { map.onPause(); }
    void onDestroy() { map.onDetach(); }

    private final class PreviewMarker extends Overlay {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final Point screen = new Point();
        private Route.Fix fix;

        @Override public void draw(Canvas canvas, Projection projection) {
            if (fix == null) return;
            projection.toPixels(new GeoPoint(fix.point().lat(), fix.point().lon()), screen);
            canvas.save();
            canvas.translate(screen.x, screen.y);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(0, 0, style.dp(14), paint);
            paint.setColor(0xff123b36);
            canvas.drawCircle(0, 0, style.dp(11), paint);
            if (fix.speedMps() > 0) {
                canvas.rotate((float) fix.bearingDegrees());
                arrow.reset();
                arrow.moveTo(0, -style.dp(7));
                arrow.lineTo(style.dp(5), style.dp(5));
                arrow.lineTo(0, style.dp(2));
                arrow.lineTo(-style.dp(5), style.dp(5));
                arrow.close();
                paint.setColor(Color.WHITE);
                canvas.drawPath(arrow, paint);
            } else {
                paint.setColor(Color.WHITE);
                canvas.drawCircle(0, 0, style.dp(4), paint);
            }
            canvas.restore();
        }
    }

    private final class Pin extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String number;
        private final boolean selected, dense;

        Pin(int index, boolean selected, boolean dense) {
            number = Integer.toString(index + 1);
            this.selected = selected;
            this.dense = dense;
        }

        @Override public void draw(Canvas canvas) {
            float x = getBounds().exactCenterX(), y = getBounds().exactCenterY();
            int radius = style.dp(selected ? 16 : dense ? 4 : 12);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, radius + style.dp(2), paint);
            paint.setColor(selected ? INK : ACCENT);
            canvas.drawCircle(x, y, radius, paint);
            if (!dense || selected) {
                paint.setColor(Color.WHITE);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(style.dp(number.length() > 3 ? 10 : 12));
                canvas.drawText(number, x, y - (paint.ascent() + paint.descent()) / 2, paint);
            }
        }

        @Override public int getIntrinsicWidth() { return style.dp(36); }
        @Override public int getIntrinsicHeight() { return style.dp(36); }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private final class MapIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String type;

        MapIcon(String type) {
            this.type = type;
            paint.setColor(INK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setStrokeWidth(1.8f);
            switch (type) {
                case "plus", "minus" -> {
                    canvas.drawLine(5, 12, 19, 12, paint);
                    if (type.equals("plus")) canvas.drawLine(12, 5, 12, 19, paint);
                }
                case "center" -> {
                    canvas.drawCircle(12, 12, 7, paint);
                    canvas.drawLine(12, 2, 12, 7, paint);
                    canvas.drawLine(12, 17, 12, 22, paint);
                    canvas.drawLine(2, 12, 7, 12, paint);
                    canvas.drawLine(17, 12, 22, 12, paint);
                    canvas.drawCircle(12, 12, 1, paint);
                }
                case "fit" -> {
                    Path corners = new Path();
                    corners.moveTo(4, 9); corners.lineTo(4, 4); corners.lineTo(9, 4);
                    corners.moveTo(15, 4); corners.lineTo(20, 4); corners.lineTo(20, 9);
                    corners.moveTo(20, 15); corners.lineTo(20, 20); corners.lineTo(15, 20);
                    corners.moveTo(9, 20); corners.lineTo(4, 20); corners.lineTo(4, 15);
                    canvas.drawPath(corners, paint);
                }
            }
            canvas.restore();
        }

        @Override public int getIntrinsicWidth() { return style.dp(24); }
        @Override public int getIntrinsicHeight() { return style.dp(24); }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
