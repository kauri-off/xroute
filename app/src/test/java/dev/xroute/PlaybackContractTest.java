package dev.xroute;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.*;

public class PlaybackContractTest {
    private final List<Route.Point> points = List.of(new Route.Point(55.75, 37.6),
            new Route.Point(55.75, 37.61), new Route.Point(55.751, 37.61));

    @Test public void roundTripKeepsWaypointsAndCurveAcrossProcesses() throws Exception {
        Route curved = new Route(points, 1.5, Route.Profile.SMOOTH, true);
        Session session = new Session("dev.walk.tracker", 12345, curved);
        String encoded = session.encode();
        Route decoded = Session.decode(encoded).route();
        assertEquals(points, decoded.points);
        assertEquals(curved.geometry(), decoded.geometry());
        assertFalse(decoded.geometry().contains(points.get(1)));
        assertEquals(points.get(0), decoded.geometry().get(0));
        assertEquals(points.get(2), decoded.sample(decoded.durationSeconds() + 1).point());
        var old = new JSONObject(encoded).put("v", 1);
        old.remove("roundTurns");
        Route straight = Session.decode(old.toString()).route();
        assertEquals(points, straight.geometry());
        assertTrue(decoded.durationSeconds() < straight.durationSeconds());
    }

    @Test public void repeatedLegJoinIsRemoved() throws Exception {
        var result = WalkingDirections.decode("{\"trip\":{\"legs\":[{\"shape\":\"??AA\"},{\"shape\":\"AAAA\"}]}}");
        assertEquals(List.of(new Route.Point(0, 0), new Route.Point(.000001, .000001),
                new Route.Point(.000002, .000002)), result);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsOverflowingDuration() {
        new Route(points, Double.MIN_VALUE, Route.Profile.SMOOTH);
    }

    @Test(expected = IOException.class) public void rejectsTruncatedShape() throws Exception {
        WalkingDirections.decode("{\"trip\":{\"legs\":[{\"shape\":\"?_\"}]}}");
    }

    @Test(expected = IllegalArgumentException.class) public void cannotTargetCompanion() {
        new Session(BuildConfig.APPLICATION_ID, 1, new Route(points, 1, Route.Profile.LINEAR));
    }
}
