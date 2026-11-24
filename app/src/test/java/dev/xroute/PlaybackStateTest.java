package dev.xroute;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackStateTest {
    private static final String TARGET = "dev.route.tracker";
    private static final String SECOND = "dev.route.other";
    private final Session session = new Session(TARGET, 1000,
            new Route(List.of(new Route.Point(0, 0), new Route.Point(0, .01)), 1, Route.Profile.LINEAR));
    private final PlaybackState offline = PlaybackState.disconnected("obsolete.target");

    @Test public void enablesStartOnlyAfterConfirmedScopeAndDisablesConflictingCommands() {
        assertFalse(offline.canStart());
        var loading = offline.loading();
        assertFalse(loading.canStart());
        var idle = loading.observed(List.of(TARGET), null, PlaybackState.ProcessState.UNKNOWN, "", 1000);
        assertEquals(PlaybackState.Phase.IDLE, idle.phase());
        assertEquals(TARGET, idle.target());
        assertTrue(idle.canStart());
        assertFalse(idle.canStop());
        var writing = idle.submitting(true);
        assertEquals(PlaybackState.Phase.PUBLISHING, writing.phase());
        assertFalse(writing.canStart());
        assertFalse(writing.canStop());
    }

    @Test public void keepsPublishedSessionSeparateFromDraftTargetAndModuleStatus() {
        var published = offline.observed(List.of(TARGET, SECOND), session, PlaybackState.ProcessState.UNKNOWN, "", 2000);
        assertEquals("", published.target());
        assertEquals(PlaybackState.Phase.PUBLISHED, published.phase());
        assertTrue(published.sessionText().contains("Состояние процесса неизвестно"));
        assertTrue(published.canStop());
        var changed = published.select(SECOND);
        assertEquals(SECOND, changed.target());
        assertEquals(TARGET, changed.session().target());
        assertTrue(changed.sessionText().contains(TARGET));
        var finished = changed.observed(List.of(SECOND), session, PlaybackState.ProcessState.LOADED, "", Long.MAX_VALUE);
        assertEquals(PlaybackState.Phase.FINISHED, finished.phase());
        assertTrue(finished.sessionText().contains("исключено из области"));
        assertTrue(finished.canStop());
        var stopping = finished.submitting(false);
        assertEquals(PlaybackState.Phase.STOPPING, stopping.phase());
        assertFalse(stopping.canStart());
        assertFalse(stopping.canStop());
        var stopped = stopping.observed(List.of(SECOND), null, PlaybackState.ProcessState.UNKNOWN, "", 3000);
        assertEquals(PlaybackState.Phase.IDLE, stopped.phase());
    }

    @Test public void reportsReadFailureAndOffersRecoveryWithoutClaimingDelivery() {
        var failed = offline.observed(null, null, PlaybackState.ProcessState.UNKNOWN, "Не удалось прочитать", 1000);
        assertEquals(PlaybackState.Phase.ERROR, failed.phase());
        assertEquals("Не удалось прочитать", failed.sessionText());
        assertFalse(failed.canStart());
        assertTrue(failed.canStop());
        assertFalse(failed.scopeKnown());
        var missing = failed.observed(List.of(TARGET), session, PlaybackState.ProcessState.UNLISTED, "", 2000);
        assertTrue(missing.sessionText().contains("Vector не видит процесс"));
        var loaded = missing.observed(List.of(TARGET), session, PlaybackState.ProcessState.LOADED, "", 3000);
        assertTrue(loaded.sessionText().contains("Модуль загружен"));
        assertTrue(loaded.sessionText().contains("Доставка точек трекеру не подтверждена"));
        var receipt = new DeliveryReceipt(2500, 50);
        var acknowledged = loaded.withReceipt(receipt);
        assertSame(receipt, acknowledged.receipt());
        assertTrue(receipt.fresh(50));
        assertTrue(receipt.fresh(10049));
        assertFalse(receipt.fresh(10050));
        assertFalse(receipt.fresh(49));
        var restarted = acknowledged.observed(List.of(TARGET), new Session(TARGET, 3001, session.route()),
                PlaybackState.ProcessState.LOADED, "", 3002);
        assertNull(restarted.receipt());
    }
}
