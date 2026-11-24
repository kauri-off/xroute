package dev.xroute;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

final class FusedAdapterSubscriptions<T> {
    private final Object submissions = new Object();
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final Map<Key, Map<Object, Change<T>>> entries = new HashMap<>();
    private Change<T> forwarding;

    Submission<T> submit(Object receiver, Object scope, T value, boolean removal, Operation operation) throws Throwable {
        synchronized (submissions) {
            Change<T> outer = forwarding;
            if (outer != null && outer.key.get() == receiver && outer.scope.equals(scope) && outer.removal == removal) {
                return new Submission<>(null, operation.run());
            }
            Change<T> change = begin(receiver, scope, value, removal);
            forwarding = change;
            try {
                return new Submission<>(change, operation.run());
            } catch (Throwable error) {
                settle(change, false);
                throw error;
            } finally {
                forwarding = outer;
            }
        }
    }

    synchronized Change<T> begin(Object receiver, Object scope, T value, boolean removal) {
        expunge();
        Key key = new Key(receiver, collected);
        Change<T> change = new Change<>(key, scope, value, removal);
        change.previous = entries.computeIfAbsent(key, ignored -> new HashMap<>()).put(scope, change);
        return change;
    }

    synchronized void settle(Change<T> change, boolean succeeded) {
        expunge();
        if (change.finished) return;
        change.finished = true;
        change.succeeded = succeeded;
        if (succeeded) change.previous = null;

        if (change.key.get() == null) return;
        Map<Object, Change<T>> scopes = entries.get(change.key);
        if (scopes == null) return;
        Change<T> latest = scopes.get(change.scope);
        if (latest != change) return;
        Change<T> remaining = active(latest);
        if (remaining == null || remaining.finished && remaining.removal) {
            scopes.remove(change.scope);
            if (scopes.isEmpty()) entries.remove(change.key);
        } else if (remaining != latest) {
            scopes.put(change.scope, remaining);
        }
    }

    synchronized T get(Object receiver) {
        expunge();
        Map<Object, Change<T>> scopes = entries.get(new Key(receiver, null));
        if (scopes == null) return null;
        Change<T> match = null;
        for (Change<T> change : scopes.values()) {
            Change<T> active = delivery(change);
            if (active == null) continue;
            if (match != null) return null;
            match = active;
        }
        return match == null ? null : match.value;
    }

    private static <T> Change<T> delivery(Change<T> change) {
        Change<T> latest = active(change);
        while (latest != null && latest.removal && !latest.finished) {
            latest = active(latest.previous);
        }
        return latest == null || latest.removal ? null : latest;
    }

    private static <T> Change<T> active(Change<T> change) {
        while (change != null && change.finished && !change.succeeded) {
            change = change.previous;
        }
        return change;
    }

    private void expunge() {
        Key key;
        while ((key = (Key) collected.poll()) != null) entries.remove(key);
    }

    record Submission<T>(Change<T> change, Object task) { }

    interface Operation {
        Object run() throws Throwable;
    }

    static final class Change<T> {
        private final Key key;
        private final Object scope;
        private final T value;
        private final boolean removal;
        private Change<T> previous;
        private boolean finished;
        private boolean succeeded;

        private Change(Key key, Object scope, T value, boolean removal) {
            this.key = key;
            this.scope = scope;
            this.value = value;
            this.removal = removal;
        }
    }

    private static final class Key extends WeakReference<Object> {
        private final int hash;

        Key(Object receiver, ReferenceQueue<Object> queue) {
            super(receiver, queue);
            hash = System.identityHashCode(receiver);
        }

        @Override public int hashCode() {
            return hash;
        }

        @Override public boolean equals(Object object) {
            if (object == this) return true;
            Object receiver = get();
            return receiver != null && object instanceof Key key && receiver == key.get();
        }
    }
}
