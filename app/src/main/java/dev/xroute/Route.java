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
    private final double[] cumulative;

    public Route(List<Point> points, double meanMps, Profile profile) {
        if (points.size() < 2 || points.size() > 2000) throw new IllegalArgumentException("Нужно от 2 до 2000 точек");
        if (!(meanMps > 0 && meanMps <= 100)) throw new IllegalArgumentException("Скорость должна быть больше 0 и не выше 360 км/ч");
        this.points = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(points));
        this.meanMps = meanMps;
        this.profile = java.util.Objects.requireNonNull(profile);
        cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1), b = points.get(i);
            double h = pow(sin(toRadians(b.lat - a.lat) / 2), 2)
                    + cos(toRadians(a.lat)) * cos(toRadians(b.lat)) * pow(sin(toRadians(b.lon - a.lon) / 2), 2);
            double arc = 2 * asin(sqrt(min(1, h)));
            if (arc * RADIUS < 0.01 || PI - arc < 1e-7) throw new IllegalArgumentException("Уточните соседние точки маршрута");
            cumulative[i] = cumulative[i - 1] + arc * RADIUS;
        }
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
        if (t >= 1) return new Fix(points.get(points.size() - 1), 0, 0);
        double distance = length * profile.progress(t);
        int index = Arrays.binarySearch(cumulative, distance);
        int segment = min(points.size() - 2, index >= 0 ? index : -index - 2);
        Point a = points.get(segment), b = points.get(segment + 1);
        double course = bearing(a, b);
        double arc = (distance - cumulative[segment]) / RADIUS;
        double lat = toRadians(a.lat);
        double nextLat = asin(max(-1, min(1, sin(lat) * cos(arc) + cos(lat) * sin(arc) * cos(course))));
        double lonDelta = atan2(sin(course) * sin(arc) * cos(lat), cos(arc) - sin(lat) * sin(nextLat));
        Point point = new Point(toDegrees(nextLat), (a.lon + toDegrees(lonDelta) + 540) % 360 - 180);
        return new Fix(point, meanMps * profile.rate(t), (toDegrees(bearing(point, b)) + 360) % 360);
    }
}
