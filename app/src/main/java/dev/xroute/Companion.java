package dev.xroute;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Companion extends Application implements XposedServiceHelper.OnServiceListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners = new HashSet<>();
    final ExecutorService vector = Executors.newSingleThreadExecutor();
    private XposedService service;
    private XposedService unconfirmed;

    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    XposedService service() {
        return service;
    }

    void observe(Runnable listener) {
        listeners.add(listener);
        listener.run();
    }

    void unobserve(Runnable listener) {
        listeners.remove(listener);
    }

    boolean sessionUnconfirmed(XposedService bound) {
        return unconfirmed == bound;
    }

    boolean commitSession(XposedService bound, SharedPreferences.Editor edit) {
        boolean confirmed = false;
        try {
            confirmed = edit.commit();
            return confirmed;
        } finally {
            unconfirmed = confirmed ? null : bound;
        }
    }

    @Override public void onServiceBind(XposedService bound) {
        main.post(() -> {
            service = bound;
            notifyListeners();
        });
    }

    @Override public void onServiceDied(XposedService dead) {
        main.post(() -> {
            if (service == dead) {
                service = null;
                notifyListeners();
            }
        });
    }

    private void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }
}
