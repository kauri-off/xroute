package dev.xroute;

import android.location.Location;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

final class FusedAdapterResults {
    private static final String PROVIDER = "fused";
    private final Method locations;
    private final Method create;

    FusedAdapterResults(Class<?> result) throws ReflectiveOperationException {
        locations = result.getMethod("getLocations");
        create = result.getMethod("create", List.class);
    }

    Object map(Object original, UnaryOperator<Location> map) throws ReflectiveOperationException {
        List<?> fixes = (List<?>) locations.invoke(original);
        if (fixes.isEmpty()) return original;
        List<Location> replacements = new ArrayList<>(fixes.size());
        boolean changed = false;
        for (Object fix : fixes) {
            Location replacement = map.apply((Location) fix);
            if (replacement != null) replacements.add(replacement);
            changed |= replacement != fix;
        }
        if (!changed) return original;
        return replacements.isEmpty() ? null : create.invoke(null, replacements);
    }

    static String provider(Location location) {
        String provider = location.getProvider();
        return provider == null ? PROVIDER : provider;
    }

    static final class Delivery {
        private final AndroidLocationAdapter.GpsFilter filter;

        Delivery(float distance) {
            filter = new AndroidLocationAdapter.GpsFilter(distance);
        }

        Location map(Location original, PlaybackSource playback) {
            if (original == null) return null;
            Location replacement = playback == null ? null
                    : playback.sample(provider(original), original.getTime(), original.getElapsedRealtimeNanos());
            Location candidate = replacement == null ? original : replacement;
            return filter.accept(candidate, replacement != null) ? candidate : null;
        }
    }
}
