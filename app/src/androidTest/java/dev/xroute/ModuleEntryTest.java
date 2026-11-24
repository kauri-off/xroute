package dev.xroute;

import android.Manifest;
import android.app.AppComponentFactory;
import android.app.AppOpsManager;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Build;
import android.os.Process;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.HookBuilder;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ModuleEntryTest {
    private static final String TARGET = "org.example.tracker";
    private static final String OTHER = "org.example.other";
    private static final String GPS = LocationManager.GPS_PROVIDER;
    private static final Executor DIRECT = Runnable::run;

    private final ClassLoader loader = ModuleEntryTest.class.getClassLoader();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final SharedPreferences preferences = context.getSharedPreferences("module-entry-test", Context.MODE_PRIVATE);
    private final Route route = new Route(Arrays.asList(new Route.Point(10, 20), new Route.Point(10.02, 20)),
            10, Route.Profile.LINEAR);
    private long start;

    @Rule public final GrantPermissionRule locationPermission = GrantPermissionRule.grant(
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);

    @Before public void publish() throws Exception {
        start = System.currentTimeMillis() - 10_000;
        assertTrue(preferences.edit().putString(Session.KEY, new Session(TARGET, start, route).encode()).commit());
    }

    @After public void clear() {
        assertTrue(preferences.edit().clear().commit());
    }

    @Test public void packageReadySkipsCompanionAndSecondaryPackage() throws Exception {
        var companion = load(context.getPackageName(), true);
        assertTrue(companion.hooks.isEmpty());
        assertEquals(0, companion.preferencesReads);

        var secondary = load(TARGET, false);
        assertTrue(secondary.hooks.isEmpty());
        assertEquals(0, secondary.preferencesReads);
    }

    @Test public void androidLastAndListenerDeliveryUseRuntimeSignatures() throws Throwable {
        try (ActivityScenario<Editor> screen = ActivityScenario.launch(Editor.class)) {
            screen.onActivity(activity -> assertFineLocationAllowed());
            assertAndroidDelivery();
        }
    }

    private void assertAndroidDelivery() throws Throwable {
        var framework = load(TARGET, true);
        assertEquals(1, framework.preferencesReads);
        var manager = context.getSystemService(LocationManager.class);
        assertNotNull(manager);
        var app = new TrackerApplication();
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        framework.dispatch(attach, app, (receiver, args) -> {
            ((TrackerApplication) receiver).attach((Context) args[0]);
            return null;
        }, context);

        Location first = fix(GPS, start + 1_000, 1_000_000_000L);
        Location second = fix(GPS, start + 2_000, 2_000_000_000L);
        Method last = LocationManager.class.getMethod("getLastKnownLocation", String.class);
        assertRouteLast((Location) framework.dispatch(last, manager, (receiver, args) -> first, GPS));
        assertNull(framework.dispatch(last, manager, (receiver, args) -> null, GPS));

        var received = new ArrayList<Location>();
        LocationListener receiver = new LocationListener() {
            @Override public void onLocationChanged(Location fix) { received.add(fix); }
            @Override public void onLocationChanged(List<Location> fixes) { received.addAll(fixes); }
        };
        var upstream = new AtomicReference<LocationListener>();
        Origin request = (object, args) -> {
            upstream.set((LocationListener) args[args.length - 1]);
            return null;
        };
        if (Build.VERSION.SDK_INT >= 31) {
            Method updates = LocationManager.class.getMethod("requestLocationUpdates",
                    String.class, LocationRequest.class, Executor.class, LocationListener.class);
            framework.dispatch(updates, manager, request, GPS, new LocationRequest.Builder(1_000).build(), DIRECT, receiver);
            upstream.get().onLocationChanged(Arrays.asList(first, second));
        } else if (Build.VERSION.SDK_INT == 30) {
            Method updates = LocationManager.class.getMethod("requestLocationUpdates",
                    String.class, long.class, float.class, Executor.class, LocationListener.class);
            framework.dispatch(updates, manager, request, GPS, 1_000L, 0f, DIRECT, receiver);
            upstream.get().onLocationChanged(first);
        } else {
            Method updates = LocationManager.class.getMethod("requestLocationUpdates",
                    String.class, long.class, float.class, LocationListener.class);
            framework.dispatch(updates, manager, request, GPS, 1_000L, 0f, receiver);
            upstream.get().onLocationChanged(first);
        }
        assertBatch(Build.VERSION.SDK_INT >= 31 ? Arrays.asList(first, second) : Collections.singletonList(first), received);

        Method remove = LocationManager.class.getMethod("removeUpdates", LocationListener.class);
        framework.dispatch(remove, manager, (object, args) -> {
            assertSame(upstream.get(), args[0]);
            return null;
        }, receiver);
    }

    @Test public void fusedLastAndBatchedCallbackRespectPublishedTarget() throws Throwable {
        try (ActivityScenario<Editor> screen = ActivityScenario.launch(Editor.class)) {
            screen.onActivity(activity -> assertFineLocationAllowed());
            Location first = fix("fused", start + 1_000, 1_000_000_000L);
            Location second = fix("fused", start + 2_000, 2_000_000_000L);
            var batch = LocationResult.create(Arrays.asList(first, second));
            assertFusedDelivery(TARGET, true, batch);
            assertFusedDelivery(OTHER, false, batch);
        }
    }

    private void assertFusedDelivery(String pkg, boolean mapped, LocationResult batch) throws Throwable {
        var framework = load(pkg, true);
        Method factory = LocationServices.class.getMethod("getFusedLocationProviderClient", Context.class);
        Object client = framework.dispatch(factory, null,
                (receiver, args) -> LocationServices.getFusedLocationProviderClient((Context) args[0]), context);
        assertSame(loader, client.getClass().getClassLoader());
        Location original = batch.getLastLocation();
        Method last = client.getClass().getMethod("getLastLocation");
        Object task = framework.dispatch(last, client, (receiver, args) -> Tasks.forResult(original));
        Location lastFix = (Location) ((Task<?>) task).getResult();
        if (mapped) assertRouteLast(lastFix);
        else assertSame(original, lastFix);

        var delivered = new AtomicReference<LocationResult>();
        var callback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult result) { delivered.set(result); }
        };
        var upstream = new AtomicReference<LocationCallback>();
        var request = new com.google.android.gms.location.LocationRequest.Builder(1_000).build();
        Method updates = client.getClass().getMethod("requestLocationUpdates",
                com.google.android.gms.location.LocationRequest.class, Executor.class, LocationCallback.class);
        framework.dispatch(updates, client, (receiver, args) -> {
            upstream.set((LocationCallback) args[2]);
            return Tasks.forResult(null);
        }, request, DIRECT, callback);
        LocationCallback registered = upstream.get();
        Method deliver = registered.getClass().getMethod("onLocationResult", LocationResult.class);
        Origin delivery = (receiver, args) -> {
            ((LocationCallback) receiver).onLocationResult((LocationResult) args[0]);
            return null;
        };
        framework.dispatch(deliver, registered, delivery, batch);
        LocationResult result = delivered.get();
        assertNotNull(result);
        if (mapped) assertBatch(batch.getLocations(), result.getLocations());
        else assertSame(batch, result);

        Method remove = client.getClass().getMethod("removeLocationUpdates", LocationCallback.class);
        framework.dispatch(remove, client, (receiver, args) -> {
            assertSame(registered, args[0]);
            return Tasks.forResult(null);
        }, callback);
        framework.dispatch(deliver, registered, delivery, batch);
        assertSame(batch, delivered.get());
    }

    private void assertRouteLast(Location fix) {
        assertNotNull(fix);
        var expected = route.sample((fix.getTime() - start) / 1_000.0).point();
        assertEquals(expected.lat(), fix.getLatitude(), 1e-9);
        assertEquals(expected.lon(), fix.getLongitude(), 1e-9);
    }

    private void assertBatch(List<Location> input, List<Location> delivered) {
        assertEquals(input.size(), delivered.size());
        for (int i = 0; i < input.size(); i++) {
            Location original = input.get(i);
            Location fix = delivered.get(i);
            assertEquals(original.getTime(), fix.getTime());
            assertEquals(original.getElapsedRealtimeNanos(), fix.getElapsedRealtimeNanos());
            assertRouteLast(fix);
        }
    }

    private void assertFineLocationAllowed() {
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION));
        var appOps = context.getSystemService(AppOpsManager.class);
        assertEquals(AppOpsManager.MODE_ALLOWED, appOps.checkOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION,
                Process.myUid(), context.getOpPackageName()));
    }

    private Dispatcher load(String pkg, boolean first) throws Exception {
        var dispatcher = new Dispatcher();
        var module = (XposedModule) Class.forName("dev.xroute.RouteModule", true, loader).getConstructor().newInstance();
        module.attachFramework(dispatcher.framework(), dispatcher.hooks::clear);
        assertEquals(XposedInterface.API_102, module.getApiVersion());
        module.onPackageReady(new Ready(pkg, first, loader));
        return dispatcher;
    }

    private static Location fix(String provider, long time, long elapsed) {
        Location fix = new Location(provider);
        fix.setLatitude(55);
        fix.setLongitude(37);
        fix.setTime(time);
        fix.setElapsedRealtimeNanos(elapsed);
        return fix;
    }

    private static final class TrackerApplication extends Application {
        void attach(Context context) { attachBaseContext(context); }
        @Override public String getPackageName() { return TARGET; }
    }

    private record Ready(String pkg, boolean first, ClassLoader loader) implements PackageReadyParam {
        @Override public String getPackageName() { return pkg; }
        @Override public ApplicationInfo getApplicationInfo() {
            var info = new ApplicationInfo();
            info.packageName = pkg;
            return info;
        }
        @Override public boolean isFirstPackage() { return first; }
        @Override public ClassLoader getDefaultClassLoader() { return loader; }
        @Override public ClassLoader getClassLoader() { return loader; }
        @Override public AppComponentFactory getAppComponentFactory() { return new AppComponentFactory(); }
    }

    private final class Dispatcher {
        final Map<Executable, Hooker> hooks = new HashMap<>();
        int preferencesReads;

        XposedInterface framework() {
            return (XposedInterface) Proxy.newProxyInstance(loader, new Class<?>[]{XposedInterface.class}, (p, method, args) -> {
                return switch (method.getName()) {
                    case "getApiVersion" -> XposedInterface.API_102;
                    case "getFrameworkProperties" -> XposedInterface.PROP_CAP_REMOTE;
                    case "getModuleApplicationInfo" -> context.getApplicationInfo();
                    case "getRemotePreferences" -> {
                        assertEquals(Session.GROUP, args[0]);
                        preferencesReads++;
                        yield preferences;
                    }
                    case "hook" -> builder((Executable) args[0]);
                    case "log" -> null;
                    default -> throw new AssertionError(method);
                };
            });
        }

        private HookBuilder builder(Executable executable) {
            return new HookBuilder() {
                private String id;

                @Override public HookBuilder setPriority(int priority) { return this; }
                @Override public HookBuilder setExceptionMode(XposedInterface.ExceptionMode mode) { return this; }
                @Override public HookBuilder setId(String name) {
                    id = name;
                    return this;
                }
                @Override public HookHandle intercept(Hooker hooker) {
                    assertNull("Duplicate hook for " + executable, hooks.put(executable, hooker));
                    return new HookHandle() {
                        @Override public Executable getExecutable() { return executable; }
                        @Override public String getId() { return id; }
                        @Override public void unhook() { hooks.remove(executable); }
                        @Override public HookHandle replaceHook(Hooker replacement) {
                            hooks.put(executable, replacement);
                            return this;
                        }
                    };
                }
            };
        }

        Object dispatch(Method method, Object receiver, Origin origin, Object... arguments) throws Throwable {
            Hooker hook = hooks.get(method);
            assertNotNull("Missing hook for " + method, hook);
            return hook.intercept(new Invocation(method, receiver, origin, arguments));
        }
    }

    private record Invocation(Executable executable, Object receiver, Origin origin, Object[] arguments) implements Chain {
        @Override public Executable getExecutable() { return executable; }
        @Override public Object getThisObject() { return receiver; }
        @Override public List<Object> getArgs() { return Collections.unmodifiableList(Arrays.asList(arguments)); }
        @Override public Object getArg(int index) { return arguments[index]; }
        @Override public Object proceed() throws Throwable { return origin.call(receiver, arguments); }
        @Override public Object proceed(Object[] args) throws Throwable { return origin.call(receiver, args); }
        @Override public Object proceedWith(Object object) throws Throwable { return origin.call(object, arguments); }
        @Override public Object proceedWith(Object object, Object[] args) throws Throwable { return origin.call(object, args); }
    }

    private interface Origin {
        Object call(Object receiver, Object[] arguments) throws Throwable;
    }
}
