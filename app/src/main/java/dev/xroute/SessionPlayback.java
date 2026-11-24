package dev.xroute;

import android.content.SharedPreferences;
import android.location.Location;
import android.os.SystemClock;
import java.util.Objects;
import java.util.function.Consumer;
import org.json.JSONException;

final class SessionPlayback implements PlaybackSource {
    private final String packageName;
    private final SharedPreferences preferences;
    private final Consumer<Exception> onReadError;
    private String previous;
    private Session session;
    private boolean readFailed;
    private long deliveredSession;
    private DeliveryReceipt lastReceipt;

    SessionPlayback(String packageName, SharedPreferences preferences, Consumer<Exception> onReadError) {
        this.packageName = packageName;
        this.preferences = preferences;
        this.onReadError = onReadError;
    }

    private synchronized Session active() {
        String raw;
        try {
            raw = preferences.getString(Session.KEY, null);
        } catch (RuntimeException error) {
            if (!readFailed) onReadError.accept(error);
            readFailed = true;
            return null;
        }
        readFailed = false;
        if (!Objects.equals(raw, previous)) {
            previous = raw;
            session = null;
            if (raw != null) {
                try {
                    Session decoded = Session.decode(raw);
                    if (packageName.equals(decoded.target())) session = decoded;
                } catch (JSONException | IllegalArgumentException error) {
                    onReadError.accept(error);
                }
            }
        }
        return session;
    }

    @Override public Location sample(String provider, long wallTimeMillis, long elapsedRealtimeNanos) {
        return sample(active(), provider, wallTimeMillis, elapsedRealtimeNanos);
    }

    @Override public DeliveryFix sampleForDelivery(String provider) {
        Session current = active();
        Location fix = sample(current, provider, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos());
        return fix == null ? null : new DeliveryFix(fix, current.startMs());
    }

    @Override public synchronized void delivered(DeliveryFix fix) {
        if (session != null && session.startMs() == fix.sessionStartMs()) {
            deliveredSession = fix.sessionStartMs();
            lastReceipt = new DeliveryReceipt(fix.location().getTime(), SystemClock.elapsedRealtime());
        }
    }

    @Override public synchronized DeliveryReceipt lastDelivery(long sessionStartMs) {
        return sessionStartMs == deliveredSession ? lastReceipt : null;
    }

    @Override public PlaybackSource snapshot() {
        Session session = active();
        return (provider, wallTime, elapsedRealtime) -> sample(session, provider, wallTime, elapsedRealtime);
    }

    @Override public Runnable observeSessionChanges(Runnable listener) {
        SharedPreferences.OnSharedPreferenceChangeListener observer = (prefs, key) -> {
            if (key == null || key.equals(Session.KEY)) listener.run();
        };
        preferences.registerOnSharedPreferenceChangeListener(observer);
        return () -> preferences.unregisterOnSharedPreferenceChangeListener(observer);
    }

    private static Location sample(Session session, String provider, long wallTime, long elapsedRealtime) {
        if (session == null || wallTime < session.startMs()) return null;
        Route.Fix fix = session.route().sample((wallTime - session.startMs()) / 1000.0);
        Location location = new Location(provider);
        location.setLatitude(fix.point().lat());
        location.setLongitude(fix.point().lon());
        location.setSpeed((float) fix.speedMps());
        location.setBearing((float) fix.bearingDegrees());
        location.setAccuracy(5);
        location.setTime(wallTime);
        location.setElapsedRealtimeNanos(elapsedRealtime);
        return location;
    }
}
