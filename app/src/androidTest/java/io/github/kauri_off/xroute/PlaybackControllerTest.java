package io.github.kauri_off.xroute;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PlaybackControllerTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Companion app = (Companion) instrumentation.getTargetContext().getApplicationContext();
    private final LinkedBlockingQueue<PlaybackState> changes = new LinkedBlockingQueue<>();
    private final PlaybackController controller = new PlaybackController(app, "", changes::add);
    private final Route route = new Route(List.of(new Route.Point(0, 0), new Route.Point(0, .01)),
            1.5, Route.Profile.LINEAR);

    @Test public void failedStopDoesNotReportStoppedAndRetryClearsRemoteSession() throws Exception {
        var failWrites = new AtomicBoolean();
        var framework = new TestFramework() {
            @Override public synchronized void updateRemotePreferences(String group, Bundle changes) {
                if (failWrites.get()) throw new IllegalStateException("Disconnected during commit");
                super.updateRemotePreferences(group, changes);
            }
        };
        framework.bind(app);
        instrumentation.runOnMainSync(controller::resume);
        try {
            await(PlaybackState.Phase.IDLE);
            instrumentation.runOnMainSync(() -> controller.start(route));
            await(PlaybackState.Phase.PUBLISHING);
            await(PlaybackState.Phase.PUBLISHED);
            failWrites.set(true);
            instrumentation.runOnMainSync(controller::stop);
            await(PlaybackState.Phase.STOPPING);
            assertTrue(await(PlaybackState.Phase.ERROR).canStop());
            assertNotNull(framework.published());
            failWrites.set(false);
            instrumentation.runOnMainSync(controller::stop);
            await(PlaybackState.Phase.STOPPING);
            await(PlaybackState.Phase.IDLE);
            assertNull(framework.published());
        } finally {
            instrumentation.runOnMainSync(controller::pause);
        }
    }

    @Test public void ignoresReadFromDeadServiceWhenReplacementHasDifferentScope() throws Exception {
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var old = new TestFramework() {
            @Override public List<String> getScope() {
                reading.countDown();
                try {
                    if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("Read was not released");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return super.getScope();
            }
        };
        old.bind(app);
        instrumentation.runOnMainSync(controller::resume);
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> app.onServiceDied(app.service()));
            var disconnected = await(PlaybackState.Phase.DISCONNECTED);
            assertFalse(disconnected.canStart());
            var next = new TestFramework() {
                @Override public List<String> getScope() { return List.of("dev.test.replacement"); }
            };
            next.bind(app);
            await(PlaybackState.Phase.LOADING);
            release.countDown();
            var ready = await(PlaybackState.Phase.IDLE);
            assertEquals("dev.test.replacement", ready.target());
            instrumentation.runOnMainSync(() -> controller.start(route));
            await(PlaybackState.Phase.PUBLISHED);
            assertEquals("dev.test.replacement", Session.decode(next.published()).target());
            assertNull(old.published());
        } finally {
            release.countDown();
            instrumentation.runOnMainSync(controller::pause);
        }
    }

    private PlaybackState await(PlaybackState.Phase phase) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (true) {
            var current = changes.poll(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            assertNotNull("Expected " + phase, current);
            if (current.phase() == phase) return current;
        }
    }
}
