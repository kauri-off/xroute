package dev.xroute;

import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import org.json.JSONException;
import java.util.Objects;

public final class RouteModule extends XposedModule {
    private String previous;
    private Session session;

    private synchronized Session current(String raw) {
        if (!Objects.equals(raw, previous)) {
            previous = raw;
            session = null;
            try {
                if (raw != null) session = Session.decode(raw);
            } catch (JSONException | IllegalArgumentException e) {
                log(Log.WARN, "XRoute", "Некорректный маршрут", e);
            }
        }
        return session;
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!param.isFirstPackage() || pkg.equals("dev.xroute") ||
                (getFrameworkProperties() & PROP_CAP_REMOTE) == 0) return;
        try {
            var method = LocationManager.class.getMethod("getLastKnownLocation", String.class);
            hook(method).setId("xroute.last-location").intercept(chain -> {
                Location original = (Location) chain.proceed();
                if (original == null || !LocationManager.GPS_PROVIDER.equals(chain.getArg(0))) return original;
                Session active = current(getRemotePreferences(Session.GROUP).getString(Session.KEY, null));
                if (active == null || !pkg.equals(active.target())) return original;
                long now = System.currentTimeMillis();
                Route.Fix fix = active.route().sample((now - active.startMs()) / 1000.0);
                Location location = new Location(LocationManager.GPS_PROVIDER);
                location.setLatitude(fix.point().lat());
                location.setLongitude(fix.point().lon());
                location.setSpeed((float) fix.speedMps());
                location.setBearing((float) fix.bearingDegrees());
                location.setAccuracy(5);
                location.setTime(now);
                location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                return location;
            });
        } catch (NoSuchMethodException e) {
            log(Log.WARN, "XRoute", "GPS-адаптер недоступен", e);
        }
    }
}
