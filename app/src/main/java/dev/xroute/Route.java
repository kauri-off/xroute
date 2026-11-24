package dev.xroute;

import java.util.Arrays;
import java.util.List;

import static java.lang.Math.*;

public final class Route {
    public record Point(double lat, double lon) {
        public Point {
            if (!(abs(lat) <= 90 && abs(lon) <= 180)) throw new IllegalArgumentException("Координаты вне диапазона");
        }
    }

    public enum Profile {
        LINEAR, SMOOTH, WAVE;

        double progress(double t) {
            return switch (this) {
                case LINEAR -> t;
                case SMOOTH -> t * t * (3 - 2 * t);
                case WAVE -> t - 0.25 * sin(4 * PI * t) / (4 * PI);
            };
        }

        double rate(double t) {
            return switch (this) {
                case LINEAR -> 1;
                case SMOOTH -> 6 * t * (1 - t);
                case WAVE -> 1 - 0.25 * cos(4 * PI * t);
            };
        }
    }

    public record Fix(Point point, double speedMps, double bearingDegrees) {}

    private static final double RADIUS = 6371008.8;
    public final List<Point> points;
    public final double meanMps;
    public final Profile profile;
    public final boolean roundTurns;
    private final List<Point> geometry;
    private final double[] cumulative;

    public Route(List<Point> points, double meanMps, Profile profile) {
        this(points, meanMps, profile, false);
    }

    public Route(List<Point> points, double meanMps, Profile profile, boolean roundTurns) {
        if (points.size() < 2 || points.size() > 2000) throw new IllegalArgumentException("Нужно от 2 до 2000 точек");
        if (!(meanMps > 0 && meanMps <= 100)) throw new IllegalArgumentException("Скорость должна быть больше 0 и не выше 360 км/ч");
        this.points = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(points));
        this.meanMps = meanMps;
        this.profile = java.util.Objects.requireNonNull(profile);
        this.roundTurns = roundTurns;
        double[] edges = new double[points.size() - 1];
        for (int i = 0; i < edges.length; i++) {
            edges[i] = arc(points.get(i), points.get(i + 1));
            if (edges[i] * RADIUS < 0.01 || PI - edges[i] < 1e-7)
                throw new IllegalArgumentException("Уточните соседние точки маршрута");
        }
        geometry = roundTurns ? rounded(this.points, edges) : this.points;
        cumulative = new double[geometry.size()];
        for (int i = 1; i < geometry.size(); i++)
            cumulative[i] = cumulative[i - 1] + arc(geometry.get(i - 1), geometry.get(i)) * RADIUS;
        if (!Double.isFinite(durationSeconds())) throw new IllegalArgumentException("Укажите большую скорость");
    }

    public List<Point> geometry() { return geometry; }

    public double durationSeconds() { return cumulative[cumulative.length - 1] / meanMps; }

    private static double arc(Point a, Point b) {
        double h = pow(sin(toRadians(b.lat - a.lat) / 2), 2)
                + cos(toRadians(a.lat)) * cos(toRadians(b.lat)) * pow(sin(toRadians(b.lon - a.lon) / 2), 2);
        return 2 * asin(sqrt(min(1, h)));
    }

    private static List<Point> rounded(List<Point> points, double[] edges) {
        var shape = new java.util.ArrayList<Point>();
        shape.add(points.get(0));
        for (int i = 1; i < points.size() - 1; i++) {
            Point corner = points.get(i);
            double cut = min(5 / RADIUS, 0.2 * min(edges[i - 1], edges[i]));
            Point entry = advance(corner, bearing(corner, points.get(i - 1)), cut);
            Point exit = advance(corner, bearing(corner, points.get(i + 1)), cut);
            shape.add(entry);
            for (int step = 1; step < 8; step++) {
                double t = step / 8.0;
                Point left = interpolate(entry, corner, t);
                Point right = interpolate(corner, exit, t);
                shape.add(interpolate(left, right, t));
            }
            shape.add(exit);
        }
        shape.add(points.get(points.size() - 1));
        return java.util.Collections.unmodifiableList(shape);
    }

    private static Point interpolate(Point a, Point b, double fraction) {
        return advance(a, bearing(a, b), arc(a, b) * fraction);
    }

    private static double bearing(Point a, Point b) {
        double angle = toRadians(b.lon - a.lon);
        double x = cos(toRadians(b.lat)) * sin(angle);
        double y = cos(toRadians(a.lat)) * sin(toRadians(b.lat))
                - sin(toRadians(a.lat)) * cos(toRadians(b.lat)) * cos(angle);
        return atan2(x, y);
    }

    public Fix sample(double elapsedSeconds) {
        double length = cumulative[cumulative.length - 1];
        double t = max(0, elapsedSeconds * meanMps / length);
        if (t >= 1) return new Fix(geometry.get(geometry.size() - 1), 0, 0);
        double distance = length * profile.progress(t);
        int index = Arrays.binarySearch(cumulative, distance);
        int segment = min(geometry.size() - 2, index >= 0 ? index : -index - 2);
        Point a = geometry.get(segment), b = geometry.get(segment + 1);
        double course = bearing(a, b);
        double arc = (distance - cumulative[segment]) / RADIUS;
        Point point = advance(a, course, arc);
        return new Fix(point, meanMps * profile.rate(t), (toDegrees(bearing(point, b)) + 360) % 360);
    }

    private static Point advance(Point a, double course, double arc) {
        double lat = toRadians(a.lat);
        double north = sin(arc) * cos(course);
        double east = sin(arc) * sin(course);
        double horizontal = cos(lat) * cos(arc) - sin(lat) * north;
        double vertical = sin(lat) * cos(arc) + cos(lat) * north;
        double nextLat = toDegrees(atan2(vertical, hypot(horizontal, east)));
        double nextLon = a.lon + toDegrees(atan2(east, horizontal));
        return new Point(nextLat, (nextLon + 540) % 360 - 180);
    }
}
