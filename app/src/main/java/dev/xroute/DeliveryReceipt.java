package dev.xroute;

import android.os.SystemClock;

record DeliveryReceipt(long fixWallTimeMs, long deliveredElapsedMs) {
    static final long MAX_AGE_MS = 10_000L;

    boolean fresh(long elapsedMs) {
        return deliveredElapsedMs > 0 && elapsedMs >= deliveredElapsedMs && elapsedMs - deliveredElapsedMs < MAX_AGE_MS;
    }

    String text() {
        long seconds = Math.max(0, SystemClock.elapsedRealtime() - deliveredElapsedMs) / 1000;
        return "GPS-подписчик получил точку по таймеру · " + seconds + " с назад";
    }

    Route.Fix fix(Session session) {
        return session == null ? null : session.route().sample((fixWallTimeMs - session.startMs()) / 1000.0);
    }
}
