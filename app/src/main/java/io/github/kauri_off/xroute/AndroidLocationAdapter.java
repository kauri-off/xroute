package io.github.kauri_off.xroute;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.AppOpsManager;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class AndroidLocationAdapter {
    private static final String GPS = LocationManager.GPS_PROVIDER;

    private final RouteModule module;
    private final String pkg;
    private final PlaybackSource source;
    private final Map<LocationManager, Map<LocationListener, WeakReference<ListenerGroup>>> listeners = new WeakHashMap<>();
    private volatile Context appContext;

    AndroidLocationAdapter(RouteModule module, String pkg, PlaybackSource source) {
        this.module = module;
        this.pkg = pkg;
        this.source = source;
    }

    void install() {
        hook(ContextWrapper.class, "application-context", "attachBaseContext", chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() instanceof Application app && pkg.equals(app.getPackageName())) {
                appContext = app;
                DeliveryProbe.register(app, source);
            }
            return result;
        }, Context.class);
        hook(LocationManager.class, "last-location", "getLastKnownLocation", chain -> {
            Location original = (Location) chain.proceed();
            if (!GPS.equals(chain.getArg(0))) return original;
            Location fix = latest((LocationManager) chain.getThisObject());
            return fix != null ? fix : original;
        }, String.class);

        boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (modern) {
                hook(LocationManager.class, "current-location", "getCurrentLocation", chain -> current(chain, 4),
                        String.class, LocationRequest.class, CancellationSignal.class, Executor.class, Consumer.class);
            } else {
                hook(LocationManager.class, "current-location", "getCurrentLocation", chain -> current(chain, 3),
                        String.class, CancellationSignal.class, Executor.class, Consumer.class);
            }
        }

        if (!hook(LocationManager.class, "remove-updates", "removeUpdates", this::remove,
                LocationListener.class)) return;
        if (modern) {
            if (!hook(LocationManager.class, "flush-updates", "requestFlush", this::flush,
                    String.class, LocationListener.class, int.class)) return;
            hook(LocationManager.class, "updates", "requestLocationUpdates", this::registerModern,
                    String.class, LocationRequest.class, Executor.class, LocationListener.class);
        } else {
            installLegacy();
        }
    }

    private boolean hook(Class<?> owner, String id, String name, Hooker handler, Class<?>... types) {
        try {
            module.hook(owner.getDeclaredMethod(name, types)).setId("xroute." + id).intercept(handler);
            return true;
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            module.log(Log.WARN, "XRoute", "Недоступен Android-адаптер " + name, e);
            return false;
        }
    }

    private boolean preciseAccess() {
        Context context = appContext;
        if (context == null || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return false;
        try {
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, Process.myUid(),
                    context.getOpPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private Location latest(LocationManager manager) {
        Location fix = source.sample(GPS);
        return fix != null && preciseAccess() && manager.isProviderEnabled(GPS) ? fix : null;
    }

    @SuppressWarnings("unchecked")
    private Object current(Chain chain, int callbackIndex) throws Throwable {
        if (!GPS.equals(chain.getArg(0)) || !(chain.getArg(callbackIndex) instanceof Consumer<?> callback)) {
            return chain.proceed();
        }
        Consumer<Location> receiver = (Consumer<Location>) callback;
        LocationManager manager = (LocationManager) chain.getThisObject();
        Object[] arguments = chain.getArgs().toArray();
        CancellationSignal cancel = (CancellationSignal) arguments[callbackIndex - 2];
        Executor destination = (Executor) arguments[callbackIndex - 1];
        AtomicBoolean delivered = new AtomicBoolean();
        Consumer<Location> once = fix -> {
            if ((cancel == null || !cancel.isCanceled()) && delivered.compareAndSet(false, true)) receiver.accept(fix);
        };
        arguments[callbackIndex] = (Consumer<Location>) original -> {
            if (delivered.get()) return;
            Location fix = latest(manager);
            once.accept(fix != null ? fix : original);
        };
        Object result = chain.proceed(arguments);
        if (!delivered.get() && latest(manager) != null) {
            destination.execute(() -> {
                if (delivered.get()) return;
                Location fix = latest(manager);
                if (fix != null) once.accept(fix);
            });
        }
        return result;
    }

    private void installLegacy() {
        hook(LocationManager.class, "updates-legacy", "requestLocationUpdates", chain -> registerLegacy(chain, 3, true),
                String.class, long.class, float.class, LocationListener.class);
        hook(LocationManager.class, "updates-looper", "requestLocationUpdates", chain -> registerLegacy(chain, 3, true),
                String.class, long.class, float.class, LocationListener.class, Looper.class);
        hook(LocationManager.class, "updates-criteria", "requestLocationUpdates", chain -> registerLegacy(chain, 3, false),
                long.class, float.class, Criteria.class, LocationListener.class, Looper.class);
        hook(LocationManager.class, "single-provider", "requestSingleUpdate", chain -> register(chain, 1,
                GPS.equals(chain.getArg(0)), 0, chain.getArgs().toArray()), String.class, LocationListener.class, Looper.class);
        hook(LocationManager.class, "single-criteria", "requestSingleUpdate", chain -> register(chain, 1,
                false, 0, chain.getArgs().toArray()), Criteria.class, LocationListener.class, Looper.class);
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            hook(LocationManager.class, "updates-executor", "requestLocationUpdates", chain -> registerLegacy(chain, 4, true),
                    String.class, long.class, float.class, Executor.class, LocationListener.class);
            hook(LocationManager.class, "updates-criteria-executor", "requestLocationUpdates", chain -> registerLegacy(chain, 4, false),
                    long.class, float.class, Criteria.class, Executor.class, LocationListener.class);
            try {
                Class<?> requestType = Class.forName("android.location.LocationRequest", false, LocationManager.class.getClassLoader());
                Method provider = requestType.getMethod("getProvider");
                hook(LocationManager.class, "updates-request-executor", "requestLocationUpdates", chain -> {
                    Object request = chain.getArg(0);
                    return register(chain, 2, request != null && GPS.equals(provider.invoke(request)),
                            0, chain.getArgs().toArray());
                }, requestType, Executor.class, LocationListener.class);
            } catch (ReflectiveOperationException ignored) {}
        }
    }

    private Object registerLegacy(Chain chain, int listenerIndex, boolean explicitProvider) throws Throwable {
        boolean gps = explicitProvider && GPS.equals(chain.getArg(0));
        Object[] arguments = chain.getArgs().toArray();
        float distance = 0;
        if (gps && (long) arguments[1] != Long.MAX_VALUE && (float) arguments[2] > 0 && preciseAccess()) {
            distance = (float) arguments[2];
            arguments[2] = 0f;
        }
        return register(chain, listenerIndex, gps, distance, arguments);
    }

    @TargetApi(Build.VERSION_CODES.S)
    private Object registerModern(Chain chain) throws Throwable {
        boolean gps = GPS.equals(chain.getArg(0));
        Object[] arguments = chain.getArgs().toArray();
        float distance = 0;
        if (gps && arguments[1] instanceof LocationRequest request && request.getMaxUpdates() == Integer.MAX_VALUE
                && request.getIntervalMillis() != LocationRequest.PASSIVE_INTERVAL
                && request.getMinUpdateDistanceMeters() > 0 && preciseAccess()) {
            distance = request.getMinUpdateDistanceMeters();
            arguments[1] = new LocationRequest.Builder(request).setMinUpdateDistanceMeters(0).build();
        }
        return register(chain, 3, gps, distance, arguments);
    }

    private LocationManager bucket(Chain chain) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? null : (LocationManager) chain.getThisObject();
    }

    private synchronized Object register(Chain chain, int index, boolean gps, float distance, Object[] arguments) throws Throwable {
        if (!(arguments[index] instanceof LocationListener listener) || listener instanceof ListenerBridge) {
            return chain.proceed();
        }
        Executor executor = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!(arguments[2] instanceof Executor requested)) return chain.proceed();
            executor = requested;
        }
        var entries = listeners.computeIfAbsent(bucket(chain), key -> new WeakHashMap<>());
        var reference = entries.get(listener);
        ListenerGroup group = reference == null ? null : reference.get();
        if (group == null) group = new ListenerGroup(listener, source, this::preciseAccess);
        Object result = group.register(gps, distance, executor, (bridge, dispatch) -> {
            arguments[index] = bridge;
            if (dispatch != null) arguments[2] = dispatch;
            return chain.proceed(arguments);
        });
        if (gps) configureTimer(group, (LocationManager) chain.getThisObject(), arguments, index, executor);
        entries.put(listener, new WeakReference<>(group));
        return result;
    }

    private void configureTimer(ListenerGroup group, LocationManager manager, Object[] args, int index, Executor executor) {
        group.stopTimer();
        long interval;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && args[1] instanceof LocationRequest request) {
            if (request.getMaxUpdates() != Integer.MAX_VALUE || request.getDurationMillis() != Long.MAX_VALUE) return;
            interval = request.getIntervalMillis();
        } else if (args.length >= 4 && args[0] instanceof String && args[1] instanceof Long millis) {
            interval = millis;
            if (args[index - 1] instanceof Executor requested) executor = requested;
        } else return;
        if (interval == Long.MAX_VALUE) return;
        if (executor == null) {
            Looper looper = args[args.length - 1] instanceof Looper specified ? specified : Looper.myLooper();
            Handler handler = new Handler(looper != null ? looper : Looper.getMainLooper());
            executor = handler::post;
        }
        group.startTimer(Math.max(250L, interval), executor, () -> manager.isProviderEnabled(GPS));
    }

    private synchronized Object flush(Chain chain) throws Throwable {
        var entries = listeners.get(bucket(chain));
        if (entries == null) return chain.proceed();
        var reference = entries.get(chain.getArg(1));
        ListenerGroup group = reference == null ? null : reference.get();
        ListenerBridge bridge = group == null ? null : group.registered(GPS.equals(chain.getArg(0)));
        if (bridge == null) return chain.proceed();
        Object[] arguments = chain.getArgs().toArray();
        arguments[1] = bridge;
        return chain.proceed(arguments);
    }

    private synchronized Object remove(Chain chain) throws Throwable {
        var entries = listeners.get(bucket(chain));
        if (entries == null) return chain.proceed();
        Object listener = chain.getArg(0);
        var reference = entries.get(listener);
        ListenerGroup group = reference == null ? null : reference.get();
        if (group == null) {
            entries.remove(listener);
            return chain.proceed();
        }
        group.removeAll(bridge -> chain.proceed(new Object[]{bridge}));
        entries.remove(listener);
        return null;
    }

    @FunctionalInterface
    interface RegisterListener {
        Object register(LocationListener listener, Executor executor) throws Throwable;
    }

    @FunctionalInterface
    interface RemoveListener {
        void remove(LocationListener listener) throws Throwable;
    }

    static final class ListenerGroup {
        private final LocationListener listener;
        private final PlaybackSource source;
        private final BooleanSupplier preciseAccess;
        private final ListenerBridge gps = new ListenerBridge(this);
        private final ListenerBridge other = new ListenerBridge(this);
        private boolean gpsRegistered, otherRegistered;
        private volatile Executor executor;
        private volatile Pulse pulse;

        void startTimer(long interval, Executor destination, BooleanSupplier enabled) {
            Pulse next = new Pulse(interval, executor == null ? destination : dispatch, enabled);
            pulse = next;
            next.unsubscribe = source.observeSessionChanges(next::restart);
            next.restart();
        }

        void stopTimer() {
            Pulse previous = pulse;
            pulse = null;
            if (previous != null) {
                previous.handler.removeCallbacksAndMessages(previous);
                if (previous.unsubscribe != null) previous.unsubscribe.run();
            }
        }

        private final class Pulse {
            final Handler handler = new Handler(Looper.getMainLooper());
            final long interval;
            final Executor destination;
            final BooleanSupplier enabled;
            volatile long revision;
            Runnable unsubscribe;

            Pulse(long interval, Executor destination, BooleanSupplier enabled) {
                this.interval = interval;
                this.destination = destination;
                this.enabled = enabled;
            }

            synchronized void restart() {
                if (pulse != this) return;
                revision++;
                handler.removeCallbacksAndMessages(this);
                gps.filter.resetPlayback();
                schedule(revision, 0);
            }

            private synchronized void schedule(long expected, long delay) {
                if (pulse != this || revision != expected) return;
                long now = SystemClock.uptimeMillis();
                handler.postAtTime(() -> dispatch(expected), this, now + Math.min(Long.MAX_VALUE - now, delay));
            }

            private void dispatch(long expected) {
                if (pulse != this || revision != expected) return;
                try {
                    destination.execute(() -> tick(expected));
                } catch (RejectedExecutionException ignored) {}
            }

            private void tick(long expected) {
                if (pulse != this || revision != expected) return;
                var sample = source.sampleForDelivery(GPS);
                Location fix = sample == null ? null : sample.location();
                if (fix == null && unsubscribe != null) return;
                if (fix != null && preciseAccess.getAsBoolean() && enabled.getAsBoolean()
                        && pulse == this && revision == expected && gps.filter.acceptTimed(fix, interval, true)) {
                    listener.onLocationChanged(fix);
                    source.delivered(sample);
                }
                schedule(expected, interval);
            }
        }

        private final Executor dispatch = task -> executor.execute(task);

        ListenerGroup(LocationListener listener, PlaybackSource source, BooleanSupplier preciseAccess) {
            this.listener = listener;
            this.source = source;
            this.preciseAccess = preciseAccess;
        }

        ListenerBridge registered(boolean forGps) {
            return forGps ? (gpsRegistered ? gps : null) : (otherRegistered ? other : null);
        }

        Object register(boolean forGps, float distance, Executor requested, RegisterListener registration) throws Throwable {
            ListenerBridge bridge = forGps ? gps : other;
            GpsFilter previousFilter = bridge.filter;
            Executor previousExecutor = executor;
            if (forGps) bridge.filter = new GpsFilter(distance);
            if (requested != null) executor = requested;
            try {
                Object result = registration.register(bridge, requested == null ? null : dispatch);
                if (forGps) gpsRegistered = true;
                else otherRegistered = true;
                return result;
            } catch (Throwable error) {
                bridge.filter = previousFilter;
                executor = previousExecutor;
                throw error;
            }
        }

        void removeAll(RemoveListener operation) throws Throwable {
            Throwable failure = null;
            for (ListenerBridge bridge : new ListenerBridge[]{registered(true), registered(false)}) {
                if (bridge == null) continue;
                try {
                    operation.remove(bridge);
                    if (bridge == gps) {
                        stopTimer();
                        gpsRegistered = false;
                    }
                    else otherRegistered = false;
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else if (failure != error) failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }

    static final class GpsFilter {
        private final float distance;
        private long lastElapsed;
        private boolean lastTimed;

        synchronized boolean acceptTimed(Location fix, long interval, boolean timed) {
            long elapsed = fix.getElapsedRealtimeNanos();
            if ((lastTimed || timed) && lastElapsed > 0
                    && elapsed - lastElapsed < TimeUnit.MILLISECONDS.toNanos(interval)) return false;
            if (!accept(fix, true)) return false;
            lastElapsed = elapsed;
            lastTimed = timed;
            return true;
        }

        private Location previousReal;
        private Location previousReplacement;

        GpsFilter(float distance) { this.distance = distance; }

        synchronized void resetPlayback() {
            lastElapsed = 0;
            lastTimed = false;
            previousReplacement = null;
        }

        synchronized boolean accept(Location location, boolean replacement) {
            if (distance <= 0) return true;
            Location previous = replacement ? previousReplacement : previousReal;
            if (!replacement) previousReplacement = null;
            if (previous != null && previous.distanceTo(location) < distance) return false;
            if (replacement) previousReplacement = new Location(location);
            else previousReal = new Location(location);
            return true;
        }
    }

    static final class ListenerBridge implements LocationListener {
        private final ListenerGroup group;
        private volatile GpsFilter filter;

        ListenerBridge(ListenerGroup group) { this.group = group; }

        private Location map(Location original, PlaybackSource playback, GpsFilter filter) {
            if (filter == null || original == null || !GPS.equals(original.getProvider())) return original;
            Location sample = playback.sample(GPS, original.getTime(), original.getElapsedRealtimeNanos());
            Location candidate = sample != null && group.preciseAccess.getAsBoolean() ? sample : original;
            var pulse = group.pulse;
            if (candidate != original && pulse != null) {
                return filter.acceptTimed(candidate, pulse.interval, false) ? candidate : null;
            }
            return filter.accept(candidate, candidate != original) ? candidate : null;
        }

        @Override public void onLocationChanged(Location original) {
            Location candidate = map(original, group.source, filter);
            if (candidate != null || original == null) group.listener.onLocationChanged(candidate);
        }

        @TargetApi(Build.VERSION_CODES.S)
        @Override public void onLocationChanged(List<Location> originals) {
            GpsFilter filter = this.filter;
            if (filter == null) {
                group.listener.onLocationChanged(originals);
                return;
            }
            PlaybackSource playback = group.source.snapshot();
            var locations = new ArrayList<Location>(originals.size());
            boolean changed = false;
            for (Location original : originals) {
                Location candidate = map(original, playback, filter);
                if (candidate != null || original == null) locations.add(candidate);
                changed |= candidate != original;
            }
            if (!changed) group.listener.onLocationChanged(originals);
            else if (!locations.isEmpty()) group.listener.onLocationChanged(locations);
        }

        @Override public void onProviderEnabled(String provider) { group.listener.onProviderEnabled(provider); }
        @Override public void onProviderDisabled(String provider) { group.listener.onProviderDisabled(provider); }
        @TargetApi(Build.VERSION_CODES.S)
        @Override public void onFlushComplete(int requestCode) { group.listener.onFlushComplete(requestCode); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { group.listener.onStatusChanged(provider, status, extras); }
    }
}
