package dev.xroute;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

final class FusedAdapter {
    private static final String PACKAGE = "com.google.android.gms.location.";
    private static final int GRANULARITY_PERMISSION_LEVEL = 0;
    private static final int GRANULARITY_FINE = 2;

    private final XposedModule module;
    private final PlaybackSource source;
    private final FusedAdapterTasks tasks;
    private final FusedAdapterRequests requests;
    private final FusedAdapterResults results;
    private final Method applicationContext;
    private final Method apiKey;
    private final Channel callback;
    private final Channel listener;
    private final Set<Class<?>> clients = new HashSet<>();
    private final Set<Method> deliveries = new HashSet<>();

    static void install(XposedModule module, ClassLoader loader, PlaybackSource source) {
        Class<?> client = findClass(loader, PACKAGE + "FusedLocationProviderClient");
        if (client == null) return;
        try {
            FusedAdapter adapter = new FusedAdapter(module, loader, source);
            if (!client.isInterface() && !Modifier.isAbstract(client.getModifiers())) {
                adapter.hookClient(client);
                return;
            }
            Class<?> services = Class.forName(PACKAGE + "LocationServices", false, loader);
            for (Method method : services.getDeclaredMethods()) {
                if (!method.getName().equals("getFusedLocationProviderClient") || method.getReturnType() != client
                        || !Modifier.isStatic(method.getModifiers())) continue;
                module.hook(method).setId("xroute.fused.factory").intercept(chain -> {
                    Object result = chain.proceed();
                    if (result != null) adapter.hookClient(result.getClass());
                    return result;
                });
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            module.log(Log.WARN, "XRoute", "Адаптер Google Play Services недоступен", e);
        }
    }

    private FusedAdapter(XposedModule module, ClassLoader loader, PlaybackSource source)
            throws ReflectiveOperationException {
        this.module = module;
        this.source = source;
        tasks = new FusedAdapterTasks(loader);
        requests = FusedAdapterRequests.find(loader);
        Class<?> api = Class.forName("com.google.android.gms.common.api.GoogleApi", false, loader);
        applicationContext = api.getMethod("getApplicationContext");
        apiKey = api.getMethod("getApiKey");
        Class<?> result = Class.forName(PACKAGE + "LocationResult", false, loader);
        results = new FusedAdapterResults(result);
        callback = new Channel(findClass(loader, PACKAGE + "LocationCallback"), "onLocationResult", result);
        listener = new Channel(findClass(loader, PACKAGE + "LocationListener"), "onLocationChanged", Location.class);
    }

    private synchronized void hookClient(Class<?> client) {
        if (!clients.add(client)) return;
        for (Method method : client.getMethods()) {
            if (method.getReturnType() != tasks.task || Modifier.isAbstract(method.getModifiers())) continue;
            switch (method.getName()) {
                case "getLastLocation", "getCurrentLocation" ->
                        module.hook(method).setId("xroute.fused.single").intercept(this::single);
                case "requestLocationUpdates" -> hookRequest(method);
                case "removeLocationUpdates" -> hookRemove(method);
                default -> { }
            }
        }
    }

    private Object single(Chain chain) throws Throwable {
        Object original = chain.proceed();
        if (!chain.getArgs().isEmpty() && !allowsPrecise(chain.getArg(0))) return original;
        Context context = context(chain.getThisObject());
        if (context == null) return original;
        return tasks.mapSuccess(original, result -> {
            if (!(result instanceof Location location) || !hasPrecisePermission(context)) return result;
            try {
                Location replacement = source.sample(FusedAdapterResults.provider(location));
                return replacement == null ? result : replacement;
            } catch (RuntimeException e) {
                return result;
            }
        });
    }

    private void hookRequest(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 3 || !params[0].getName().equals(PACKAGE + "LocationRequest")) return;
        int index = params[1] == Executor.class ? 2 : 1;
        if (index == 1 && params[2] != Looper.class) return;
        Channel channel = channel(params[index]);
        if (channel == null) return;
        module.hook(method).setId("xroute.fused.request").intercept(chain -> {
            Object receiver = chain.getArg(index);
            Object scope = clientScope(chain.getThisObject());
            if (receiver == null || scope == null || !hookDelivery(channel, receiver.getClass())) return chain.proceed();
            Context context = allowsPrecise(chain.getArg(0)) ? context(chain.getThisObject()) : null;
            FusedAdapterRequests.Adapted adapted = adaptRequest(chain.getArg(0), context);
            float distance = adapted == null ? 0 : adapted.distance();
            Binding binding = new Binding(context, new FusedAdapterResults.Delivery(distance));
            Object[] arguments = adapted == null ? null : chain.getArgs().toArray();
            if (arguments != null) arguments[0] = adapted.request();
            return changeSubscription(chain, channel, receiver, scope, binding, false, arguments);
        });
    }

    private void hookRemove(Method method) {
        Class<?>[] params = method.getParameterTypes();
        Channel channel = params.length == 1 ? channel(params[0]) : null;
        if (channel == null) return;
        module.hook(method).setId("xroute.fused.remove").intercept(chain -> {
            Object receiver = chain.getArg(0);
            Object scope = clientScope(chain.getThisObject());
            return receiver == null || scope == null ? chain.proceed()
                    : changeSubscription(chain, channel, receiver, scope, null, true, null);
        });
    }

    private FusedAdapterRequests.Adapted adaptRequest(Object request, Context context) {
        if (requests == null || !hasPrecisePermission(context)) return null;
        return requests.adapt(request);
    }

    private Object changeSubscription(Chain chain, Channel channel, Object receiver, Object scope, Binding binding,
                                      boolean removal, Object[] arguments) throws Throwable {
        var submission = channel.subscriptions.submit(receiver, scope, binding, removal,
                () -> arguments == null ? chain.proceed() : chain.proceed(arguments));
        var change = submission.change();
        Object task = submission.task();
        if (change != null && !tasks.observe(task, succeeded -> channel.subscriptions.settle(change, succeeded))) {
            channel.subscriptions.settle(change, false);
        }
        return task;
    }

    private synchronized boolean hookDelivery(Channel channel, Class<?> receiver) {
        if (Proxy.isProxyClass(receiver)) return false;
        try {
            Method method = receiver.getMethod(channel.methodName, channel.payloadType);
            if (deliveries.contains(method)) return true;
            module.hook(method).setId("xroute.fused.delivery").intercept(chain -> {
                Object subscriber = chain.getThisObject();
                Object previous = channel.dispatching.get();
                if (subscriber == previous) return chain.proceed();
                Binding binding = channel.subscriptions.get(subscriber);
                Object payload = chain.getArg(0);
                if (binding == null || payload == null) return chain.proceed();
                Object replacement = rewriteDelivery(channel, payload, binding);
                if (replacement == null) return null;
                channel.dispatching.set(subscriber);
                try {
                    return replacement == payload ? chain.proceed() : chain.proceed(new Object[]{replacement});
                } finally {
                    if (previous == null) channel.dispatching.remove();
                    else channel.dispatching.set(previous);
                }
            });
            deliveries.add(method);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private Object rewriteDelivery(Channel channel, Object payload, Binding binding) {
        try {
            boolean precise = hasPrecisePermission(binding.context());
            if (channel == listener) {
                return binding.delivery().map((Location) payload, precise ? source : null);
            }
            PlaybackSource playback = precise ? source.snapshot() : null;
            return results.map(payload, original -> binding.delivery().map(original, playback));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return payload;
        }
    }

    private Channel channel(Class<?> receiver) {
        if (receiver == callback.receiverType) return callback;
        if (receiver == listener.receiverType) return listener;
        return null;
    }

    private Context context(Object client) {
        try {
            return (Context) applicationContext.invoke(client);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private Object clientScope(Object client) {
        try {
            return apiKey.invoke(client);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean hasPrecisePermission(Context context) {
        if (context == null) return false;
        try {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, Process.myUid(), context.getOpPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean allowsPrecise(Object request) {
        if (request == null || request instanceof Integer) return true;
        try {
            int granularity = (Integer) request.getClass().getMethod("getGranularity").invoke(request);
            return granularity == GRANULARITY_PERMISSION_LEVEL || granularity == GRANULARITY_FINE;
        } catch (NoSuchMethodException e) {
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static Class<?> findClass(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private record Binding(Context context, FusedAdapterResults.Delivery delivery) { }

    private static final class Channel {
        final Class<?> receiverType;
        final String methodName;
        final Class<?> payloadType;
        final FusedAdapterSubscriptions<Binding> subscriptions = new FusedAdapterSubscriptions<>();
        final ThreadLocal<Object> dispatching = new ThreadLocal<>();

        Channel(Class<?> receiverType, String methodName, Class<?> payloadType) {
            this.receiverType = receiverType;
            this.methodName = methodName;
            this.payloadType = payloadType;
        }
    }
}
