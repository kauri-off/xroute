package dev.xroute;

import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedModule;

public final class RouteModule extends XposedModule {
    @Override public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!param.isFirstPackage() || pkg.equals(getModuleApplicationInfo().packageName) ||
                (getFrameworkProperties() & PROP_CAP_REMOTE) == 0) return;
        SharedPreferences preferences;
        try {
            preferences = getRemotePreferences(Session.GROUP);
        } catch (RuntimeException e) {
            log(Log.WARN, "XRoute", "Не удалось подключиться к настройкам маршрута", e);
            return;
        }
        PlaybackSource source = new SessionPlayback(pkg, preferences,
                error -> log(Log.WARN, "XRoute", "Не удалось прочитать маршрут", error));
        new AndroidLocationAdapter(this, pkg, source).install();
        FusedAdapter.install(this, param.getClassLoader(), source);
    }
}
