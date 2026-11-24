package io.github.kauri_off.xroute;

import android.location.Location;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FusedAdapterSdkTest {
    @Test public void taskTransformationPreservesPendingNullFailureAndCancellation() throws Exception {
        var tasks = new FusedAdapterTasks(Task.class.getClassLoader());
        var pending = new TaskCompletionSource<String>();
        var completions = new ArrayList<Boolean>();
        AtomicInteger calls = new AtomicInteger();
        Task<?> mapped = (Task<?>) tasks.mapSuccess(pending.getTask(), value -> {
            calls.incrementAndGet();
            return "route";
        });
        assertFalse(mapped.isComplete());
        assertTrue(tasks.observe(mapped, completions::add));
        pending.setResult("original");
        assertEquals("route", mapped.getResult());
        assertEquals(Arrays.asList(true), completions);
        assertEquals(1, calls.get());

        var failure = new IllegalStateException("original error");
        Task<?> failed = (Task<?>) tasks.mapSuccess(Tasks.forException(failure), value -> failMapping());
        assertSame(failure, failed.getException());
        Task<?> canceled = (Task<?>) tasks.mapSuccess(Tasks.forCanceled(), value -> failMapping());
        assertTrue(canceled.isCanceled());
        assertTrue(tasks.observe(canceled, completions::add));
        assertEquals(Arrays.asList(true, false), completions);
        Task<?> empty = (Task<?>) tasks.mapSuccess(Tasks.forResult(null), value -> value);
        assertTrue(empty.isSuccessful());
        assertNull(empty.getResult());
    }

    @Test public void removesOnlyDistanceFromEligibleRequest() {
        var requests = FusedAdapterRequests.find(LocationRequest.class.getClassLoader());
        assertNotNull(requests);
        LocationRequest original = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateDistanceMeters(60).setMinUpdateIntervalMillis(500)
                .setMaxUpdateDelayMillis(4000).setGranularity(Granularity.GRANULARITY_FINE).build();
        var changed = requests.adapt(original);
        assertNotNull(changed);
        assertEquals(60, changed.distance(), 0);
        assertEquals(60, original.getMinUpdateDistanceMeters(), 0);
        assertEquals(new LocationRequest.Builder(original).setMinUpdateDistanceMeters(0).build(), changed.request());
        LocationRequest limited = new LocationRequest.Builder(original).setMaxUpdates(2).build();
        assertNull(requests.adapt(limited));
        LocationRequest passive = new LocationRequest.Builder(original).setPriority(Priority.PRIORITY_PASSIVE).build();
        assertNull(requests.adapt(passive));
    }

    @Test public void switchesDistanceFilteringFromDeviceToRouteAndBack() throws Exception {
        var results = new FusedAdapterResults(LocationResult.class);
        var delivery = new FusedAdapterResults.Delivery(60);
        Location initial = fix("fused", 55, 500, 500000000);
        Location nearby = fix("fused", 55.0001, 600, 600000000);
        LocationResult before = (LocationResult) results.map(LocationResult.create(Arrays.asList(initial, nearby)),
                original -> delivery.map(original, null));
        assertNotNull(before);
        assertEquals(Arrays.asList(initial), before.getLocations());

        Location first = fix("fused", 55, 1000, 1000000000);
        Location second = fix("fused", 55, 2000, 2000000000);
        Location third = fix("fused", 55, 3000, 3000000000L);
        LocationResult real = LocationResult.create(Arrays.asList(first, second, third));
        PlaybackSource route = (provider, time, elapsed) -> {
            double latitude = time == 2000 ? 56.0001 : 56 + (time - 1000) / 1000000.0;
            return fix(provider, latitude, time, elapsed);
        };

        LocationResult playback = (LocationResult) results.map(real, original -> delivery.map(original, route));
        assertNotNull(playback);
        assertEquals(2, playback.getLocations().size());
        assertEquals(1000L, playback.getLocations().get(0).getTime());
        assertEquals(3000L, playback.getLastLocation().getTime());
        assertEquals(56.002, playback.getLastLocation().getLatitude(), 1e-9);
        assertEquals(3000000000L, playback.getLastLocation().getElapsedRealtimeNanos());
        assertEquals(55, real.getLastLocation().getLatitude(), 0);

        Location fourth = fix("fused", 55, 4000, 4000000000L);
        Location fifth = fix("fused", 55.001, 5000, 5000000000L);
        LocationResult originals = LocationResult.create(Arrays.asList(fourth, fifth));
        LocationResult live = (LocationResult) results.map(originals, original -> delivery.map(original, null));
        assertNotNull(live);
        assertEquals(Arrays.asList(fifth), live.getLocations());
        assertNull(results.map(LocationResult.create(Arrays.asList(fifth)), original -> delivery.map(original, null)));
    }

    private static Object failMapping() {
        throw new AssertionError("Failure or cancellation reached the success mapper");
    }

    private static Location fix(String provider, double latitude, long time, long elapsed) {
        Location location = new Location(provider);
        location.setLatitude(latitude);
        location.setLongitude(37);
        location.setTime(time);
        location.setElapsedRealtimeNanos(elapsed);
        return location;
    }
}
