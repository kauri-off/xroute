package dev.xroute;

import android.location.Location;
import android.os.SystemClock;

interface PlaybackSource {
    Location sample(String provider, long wallTimeMillis, long elapsedRealtimeNanos);

    default Location sample(String provider) {
        return sample(provider, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos());
    }

    default PlaybackSource snapshot() { return this; }
}
