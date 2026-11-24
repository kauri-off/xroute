package dev.xroute;

import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import io.github.libxposed.service.IXposedService;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

final class TestFramework extends IXposedService.Default {
    static final String TRACKER = "org.example.tracker";
    private final Binder binder = new Binder();
    private final HashMap<String, Object> values = new HashMap<>();

    android.content.SharedPreferences targetPreferences;

    void bind(Context context) {
        targetPreferences = context.getSharedPreferences("test-framework-target", Context.MODE_PRIVATE);
        targetPreferences.edit().clear().commit();
        binder.attachInterface(this, IXposedService.DESCRIPTOR);
        Bundle extras = new Bundle();
        extras.putBinder("binder", binder);
        Uri provider = Uri.parse("content://" + context.getPackageName() + IXposedService.AUTHORITY_SUFFIX);
        context.getContentResolver().call(provider, IXposedService.SEND_BINDER, null, extras);
    }

    @Override public IBinder asBinder() { return binder; }
    @Override public int getApiVersion() { return API_102; }
    @Override public String getFrameworkName() { return "Test framework"; }
    @Override public long getFrameworkProperties() { return PROP_CAP_REMOTE; }
    @Override public List<String> getScope() { return Collections.singletonList(TRACKER); }

    @Override public synchronized Bundle requestRemotePreferences(String group) {
        Bundle snapshot = new Bundle();
        snapshot.putSerializable("map", new HashMap<>(values));
        return snapshot;
    }

    @Override public synchronized void updateRemotePreferences(String group, Bundle changes) {
        if (!Session.GROUP.equals(group)) throw new AssertionError(group);
        if (changes.getBoolean("clear")) values.clear();
        for (Object key : (HashSet<?>) changes.getSerializable("delete")) values.remove(key);
        ((HashMap<?, ?>) changes.getSerializable("put")).forEach((k, v) -> values.put((String) k, v));
        targetPreferences.edit().putString(Session.KEY, (String) values.get(Session.KEY)).commit();
    }

    synchronized String published() { return (String) values.get(Session.KEY); }
}
