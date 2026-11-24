package dev.xroute;

import android.app.Application;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class Companion extends Application implements XposedServiceHelper.OnServiceListener {
    volatile XposedService service;

    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override public void onServiceBind(XposedService bound) { service = bound; }
    @Override public void onServiceDied(XposedService dead) { if (service == dead) service = null; }
}
