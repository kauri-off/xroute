package dev.xroute;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteTest {
    @Test public void followsDistanceAcrossUnequalLegsAndDateLine() {
        Route route = new Route(List.of(new Route.Point(0, 179), new Route.Point(0, -180),
                new Route.Point(0, -178)), 2, Route.Profile.LINEAR);
        double degree = Math.toRadians(1) * 6371008.8;
        assertEquals(-179.5, route.sample(1.5 * degree / 2).point().lon(), 1e-8);
        assertEquals(90, route.sample(degree).bearingDegrees(), 1e-8);
        assertEquals(179, route.sample(-1).point().lon(), 1e-8);
        assertEquals(new Route.Fix(new Route.Point(0, -178), 0, 0), route.sample(2 * degree));
    }

    @Test public void leavesNorthPoleAlongDestinationMeridian() {
        Route route = new Route(List.of(new Route.Point(90, 5), new Route.Point(80, 125)),
                1, Route.Profile.LINEAR);
        var midpoint = route.sample(route.durationSeconds() / 2).point();
        assertEquals(85, midpoint.lat(), 1e-8);
        assertEquals(125, midpoint.lon(), 1e-8);
    }

    @Test public void speedMatchesTravelledDistance() {
        for (Route.Profile profile : Route.Profile.values()) {
            Route route = new Route(List.of(new Route.Point(0, 0), new Route.Point(0, 1)), 2, profile);
            for (int time : new int[]{100, 12000, 51000}) {
                double delta = route.sample(time + 0.5).point().lon() - route.sample(time - 0.5).point().lon();
                assertEquals(Math.toRadians(delta) * 6371008.8, route.sample(time).speedMps(), 1e-6);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsRepeatedWaypoint() {
        new Route(List.of(new Route.Point(1, 2), new Route.Point(1, 2)), 1, Route.Profile.WAVE);
    }
}
