package io.github.kauri_off.xroute

import android.util.Log
import io.github.libxposed.api.XposedInterface.PROP_CAP_REMOTE
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class RouteModule : XposedModule() {
    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        if (!param.isFirstPackage || packageName == moduleApplicationInfo.packageName ||
            (frameworkProperties and PROP_CAP_REMOTE) == 0L
        ) return

        val preferences = try {
            getRemotePreferences(Session.GROUP)
        } catch (error: RuntimeException) {
            log(Log.WARN, "XRoute", "Не удалось подключиться к настройкам маршрута", error)
            return
        }
        val source = SessionPlayback(packageName, preferences) { error ->
            log(Log.WARN, "XRoute", "Не удалось прочитать маршрут", error)
        }
        AndroidLocationAdapter(this, packageName, source).install()
        FusedAdapter.install(this, param.classLoader, source)
    }
}
