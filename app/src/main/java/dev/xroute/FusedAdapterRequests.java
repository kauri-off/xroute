package dev.xroute;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

final class FusedAdapterRequests {
    record Adapted(Object request, float distance) { }

    private final Method getDistance;
    private final Method getMaxUpdates;
    private final Method isPassive;
    private final Constructor<?> copy;
    private final Method setDistance;
    private final Method build;

    static FusedAdapterRequests find(ClassLoader loader) {
        try {
            Class<?> request = Class.forName("com.google.android.gms.location.LocationRequest", false, loader);
            return new FusedAdapterRequests(request);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private FusedAdapterRequests(Class<?> request) throws ReflectiveOperationException {
        Class<?> builder = Class.forName(request.getName() + "$Builder", false, request.getClassLoader());
        getDistance = request.getMethod("getMinUpdateDistanceMeters");
        getMaxUpdates = request.getMethod("getMaxUpdates");
        isPassive = request.getMethod("isPassive");
        copy = builder.getConstructor(request);
        setDistance = builder.getMethod("setMinUpdateDistanceMeters", float.class);
        build = builder.getMethod("build");
    }

    Adapted adapt(Object request) {
        if (request == null) return null;
        try {
            float distance = (Float) getDistance.invoke(request);
            if (!(distance > 0) || (Integer) getMaxUpdates.invoke(request) != Integer.MAX_VALUE
                    || (Boolean) isPassive.invoke(request)) return null;
            Object builder = copy.newInstance(request);
            setDistance.invoke(builder, 0f);
            return new Adapted(build.invoke(builder), distance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
