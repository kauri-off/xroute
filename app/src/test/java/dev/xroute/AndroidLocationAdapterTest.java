package dev.xroute;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AndroidLocationAdapterTest {
    private static final String GPS = LocationManager.GPS_PROVIDER;

    @Test public void filtersPlaybackDistanceWhileThePhysicalFixStaysStill() throws Throwable {
        var received = new ArrayList<Location>();
        var active = new AtomicBoolean(false);
        var listener = gpsListener(received::add,
                (provider, time, elapsed) -> active.get() ? location(provider, time, elapsed, time * .00001) : null,
                () -> true, 20);

        Location initial = location(GPS, 1, 1, 0);
        listener.onLocationChanged(initial);
        listener.onLocationChanged(location(GPS, 2, 2, 0));
        assertEquals(1, received.size());
        assertSame(initial, received.get(0));

        active.set(true);
        listener.onLocationChanged(location(GPS, 10, 10, 0));
        listener.onLocationChanged(location(GPS, 20, 20, 0));
        listener.onLocationChanged(location(GPS, 30, 30, 0));

        assertEquals(List.of(1L, 10L, 30L), received.stream().map(Location::getTime).toList());
        assertEquals(.0003, received.get(2).getLongitude(), 0.000001);

        active.set(false);
        listener.onLocationChanged(location(GPS, 40, 40, 0));
        listener.onLocationChanged(location(GPS, 50, 50, .0001));
        Location real = location(GPS, 60, 60, .0002);
        listener.onLocationChanged(real);
        assertEquals(List.of(1L, 10L, 30L, 60L), received.stream().map(Location::getTime).toList());
        assertSame(real, received.get(3));
    }

    @Test public void leavesNonGpsAndApproximateDeliveriesUnchanged() throws Throwable {
        var received = new ArrayList<Location>();
        var precise = new AtomicBoolean(true);
        var listener = gpsListener(received::add,
                (provider, time, elapsed) -> location(provider, time, elapsed, 1), precise::get, 0);

        Location network = location(LocationManager.NETWORK_PROVIDER, 1, 1, 0);
        listener.onLocationChanged(network);
        precise.set(false);
        Location gps = location(GPS, 2, 2, 0);
        listener.onLocationChanged(gps);

        assertSame(network, received.get(0));
        assertSame(gps, received.get(1));
    }

    @Test public void usesOneSnapshotAndOriginalTimestampsForABatch() throws Throwable {
        var batches = new ArrayList<List<Location>>();
        LocationListener receiver = new LocationListener() {
            @Override public void onLocationChanged(Location location) { fail(); }
            @Override public void onLocationChanged(List<Location> locations) { batches.add(locations); }
        };
        PlaybackSource playback = new PlaybackSource() {
            @Override public Location sample(String provider, long time, long elapsed) { throw new AssertionError(); }
            @Override public PlaybackSource snapshot() {
                return (provider, time, elapsed) -> location(provider, time, elapsed, 1);
            }
        };
        var listener = gpsListener(receiver, playback, () -> true, 0);

        listener.onLocationChanged(List.of(location(GPS, 10, 20, 0), location(GPS, 30, 40, 0)));

        assertEquals(1, batches.size());
        assertEquals(List.of(10L, 30L), batches.get(0).stream().map(Location::getTime).toList());
        assertEquals(List.of(20L, 40L), batches.get(0).stream().map(Location::getElapsedRealtimeNanos).toList());
        assertEquals(1, batches.get(0).get(1).getLongitude(), 0);
    }

    @Test public void keepsPassiveGpsFixesAndRemovalSeparateFromTheGpsRegistration() throws Throwable {
        var received = new ArrayList<Location>();
        var executors = new ArrayList<Executor>();
        var firstQueue = new ArrayList<Runnable>();
        var secondQueue = new ArrayList<Runnable>();
        var group = new AndroidLocationAdapter.ListenerGroup(received::add,
                (provider, time, elapsed) -> location(provider, time, elapsed, 1), () -> true);
        group.register(true, 0, firstQueue::add, (listener, dispatch) -> executors.add(dispatch));
        group.register(false, 0, secondQueue::add, (listener, dispatch) -> executors.add(dispatch));
        var gps = group.registered(true);
        var passive = group.registered(false);
        assertNotSame(gps, passive);
        assertSame(executors.get(0), executors.get(1));

        Location original = location(GPS, 10, 20, 0);
        executors.get(0).execute(() -> gps.onLocationChanged(original));
        executors.get(1).execute(() -> passive.onLocationChanged(original));
        assertTrue(firstQueue.isEmpty());
        secondQueue.forEach(Runnable::run);
        assertEquals(1, received.get(0).getLongitude(), 0);
        assertSame(original, received.get(1));

        var removed = new ArrayList<LocationListener>();
        var failure = new IllegalStateException("GPS removal failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> group.removeAll(listener -> {
            removed.add(listener);
            if (listener == gps) throw failure;
        })));
        assertEquals(List.of(gps, passive), removed);
        assertSame(gps, group.registered(true));
        assertNull(group.registered(false));

        removed.clear();
        group.removeAll(removed::add);
        assertEquals(List.of(gps), removed);
        assertNull(group.registered(true));
    }

    @Test public void failedPassiveRegistrationKeepsTheExistingGpsRegistration() throws Throwable {
        var firstQueue = new ArrayList<Runnable>();
        var group = new AndroidLocationAdapter.ListenerGroup(location -> {}, (provider, time, elapsed) -> null, () -> true);
        Executor gpsExecutor = (Executor) group.register(true, 20, firstQueue::add, (listener, dispatch) -> dispatch);
        LocationListener gps = group.registered(true);

        assertThrows(IllegalArgumentException.class, () -> group.register(false, 0, Runnable::run,
                (listener, dispatch) -> { throw new IllegalArgumentException("No passive provider"); }));

        assertSame(gps, group.registered(true));
        assertNull(group.registered(false));
        gpsExecutor.execute(() -> fail("A rejected registration replaced the executor"));
        assertEquals(1, firstQueue.size());
    }

    private static LocationListener gpsListener(LocationListener listener, PlaybackSource source,
                                                BooleanSupplier precise, float distance) throws Throwable {
        var group = new AndroidLocationAdapter.ListenerGroup(listener, source, precise);
        return (LocationListener) group.register(true, distance, null, (bridge, ignored) -> bridge);
    }

    private static Location location(String provider, long time, long elapsed, double longitude) {
        Location location = new Location(provider);
        location.setTime(time);
        location.setElapsedRealtimeNanos(elapsed);
        location.setLongitude(longitude);
        return location;
    }
}
