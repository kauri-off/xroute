package dev.xroute;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FusedAdapterSubscriptionsTest {
    private static final String FIRST = "first scope";
    private static final String SECOND = "second scope";

    @Test public void identityDistinguishesEqualReceivers() {
        var subscriptions = new FusedAdapterSubscriptions<String>();
        Object first = new String("receiver");
        Object second = new String("receiver");
        subscriptions.settle(subscriptions.begin(first, FIRST, "first", false), true);
        subscriptions.settle(subscriptions.begin(second, FIRST, "second", false), true);
        assertEquals("first", subscriptions.get(first));
        assertEquals("second", subscriptions.get(second));
    }

    @Test public void failedReplacementRestoresSuccessfulEarlierRequest() {
        var subscriptions = new FusedAdapterSubscriptions<String>();
        Object receiver = new Object();
        var first = subscriptions.begin(receiver, FIRST, "first", false);
        var second = subscriptions.begin(receiver, FIRST, "second", false);
        subscriptions.settle(first, true);
        assertEquals("second", subscriptions.get(receiver));
        subscriptions.settle(second, false);
        assertEquals("first", subscriptions.get(receiver));
    }

    @Test public void failedRemovalKeepsRegistrationAndSuccessfulRemovalClearsIt() {
        var subscriptions = new FusedAdapterSubscriptions<String>();
        Object receiver = new Object();
        subscriptions.settle(subscriptions.begin(receiver, FIRST, "active", false), true);
        var removal = subscriptions.begin(receiver, FIRST, null, true);
        assertEquals("active", subscriptions.get(receiver));
        subscriptions.settle(removal, false);
        assertEquals("active", subscriptions.get(receiver));
        subscriptions.settle(subscriptions.begin(receiver, FIRST, null, true), true);
        assertNull(subscriptions.get(receiver));
    }

    @Test public void successfulRemovalStaysRemovedAfterFailedReplacement() {
        var subscriptions = new FusedAdapterSubscriptions<String>();
        Object receiver = new Object();
        subscriptions.settle(subscriptions.begin(receiver, FIRST, "old", false), true);
        var removal = subscriptions.begin(receiver, FIRST, null, true);
        var replacement = subscriptions.begin(receiver, FIRST, "new", false);
        subscriptions.settle(removal, true);
        assertEquals("new", subscriptions.get(receiver));
        subscriptions.settle(replacement, false);
        assertNull(subscriptions.get(receiver));
    }

    @Test public void attributionScopesDoNotRemoveEachOtherAndAmbiguousDeliveryPassesThrough() {
        var subscriptions = new FusedAdapterSubscriptions<String>();
        Object receiver = new Object();
        subscriptions.settle(subscriptions.begin(receiver, FIRST, "first", false), true);
        subscriptions.settle(subscriptions.begin(receiver, SECOND, null, true), true);
        assertEquals("first", subscriptions.get(receiver));
        subscriptions.settle(subscriptions.begin(receiver, SECOND, "second", false), true);
        assertNull(subscriptions.get(receiver));
        subscriptions.settle(subscriptions.begin(receiver, new String(SECOND), null, true), true);
        assertEquals("first", subscriptions.get(receiver));
    }

    @Test public void serializesSubmissionButLetsDeliveryReadWhileSubmitting() throws Exception {
        var subscriptions = new FusedAdapterSubscriptions<String>();
        Object receiver = new Object();
        var firstEntered = new CountDownLatch(1);
        var secondTried = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(3);
        try {
            var register = threads.submit(() -> submit(subscriptions, receiver, "active", false, () -> {
                firstEntered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            }));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var remove = threads.submit(() -> {
                secondTried.countDown();
                return submit(subscriptions, receiver, null, true, () -> {
                    secondEntered.countDown();
                    return null;
                });
            });
            assertTrue(secondTried.await(5, TimeUnit.SECONDS));
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
            assertEquals("active", threads.submit(() -> subscriptions.get(receiver)).get(5, TimeUnit.SECONDS));
            release.countDown();
            subscriptions.settle(remove.get(5, TimeUnit.SECONDS).change(), true);
            subscriptions.settle(register.get(5, TimeUnit.SECONDS).change(), true);
            assertNull(subscriptions.get(receiver));
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    @Test public void nestedOverloadKeepsOuterBinding() throws Throwable {
        var subscriptions = new FusedAdapterSubscriptions<String>();
        Object receiver = new Object();
        var outer = subscriptions.submit(receiver, FIRST, "original threshold", false, () -> {
            var nested = subscriptions.submit(receiver, FIRST, "cleared threshold", false, () -> "task");
            assertNull(nested.change());
            return nested.task();
        });
        assertEquals("task", outer.task());
        subscriptions.settle(outer.change(), true);
        assertEquals("original threshold", subscriptions.get(receiver));
    }

    private static FusedAdapterSubscriptions.Submission<String> submit(FusedAdapterSubscriptions<String> subscriptions,
            Object receiver, String binding, boolean removal, FusedAdapterSubscriptions.Operation operation) {
        try {
            return subscriptions.submit(receiver, FIRST, binding, removal, operation);
        } catch (Throwable error) {
            throw new AssertionError(error);
        }
    }
}
