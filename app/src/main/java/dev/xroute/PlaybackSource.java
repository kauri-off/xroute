package dev.xroute;

import android.location.Location;
import android.os.SystemClock;

interface PlaybackSource {
    Location sample(String provider, long wallTimeMillis, long elapsedRealtimeNanos);

    record DeliveryFix(Location location, long sessionStartMs) {}

    default DeliveryFix sampleForDelivery(String provider) {
        Location fix = sample(provider);
        return fix == null ? null : new DeliveryFix(fix, 0);
    }

    default void delivered(DeliveryFix fix) {}

    default DeliveryReceipt lastDelivery(long sessionStartMs) { return null; }

    default Location sample(String provider) {
        return sample(provider, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos());
    }

    default Runnable observeSessionChanges(Runnable listener) { return null; }

    default PlaybackSource snapshot() { return this; }
}
