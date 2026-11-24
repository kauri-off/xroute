package io.github.kauri_off.xroute;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.json.JSONException;

final class PlaybackController {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private final Companion app;
    private final Consumer<PlaybackState> observer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;
    private final Runnable serviceChanged = this::serviceChanged;
    private XposedService service;
    private PlaybackState state;
    private boolean resumed;
    private long generation;
    private String commandError = "";
    private final Runnable expireReceipt = this::expireReceipt;
    private String cachedRaw;
    private Session cachedSession;

    PlaybackController(Companion app, String initialTarget, Consumer<PlaybackState> observer) {
        this.app = app;
        this.observer = observer;
        state = PlaybackState.disconnected(initialTarget);
    }

    PlaybackState state() { return state; }

    void resume() {
        if (resumed) return;
        resumed = true;
        app.observe(serviceChanged);
    }

    void pause() {
        if (!resumed) return;
        resumed = false;
        generation++;
        app.unobserve(serviceChanged);
        main.removeCallbacks(expireReceipt);
        main.removeCallbacks(refresh);
    }

    private void emit(PlaybackState next) {
        if (next.receipt() != state.receipt()) {
            main.removeCallbacks(expireReceipt);
            if (next.receipt() != null) {
                long age = SystemClock.elapsedRealtime() - next.receipt().deliveredElapsedMs();
                main.postDelayed(expireReceipt, Math.max(0L, DeliveryReceipt.MAX_AGE_MS - age));
            }
        }
        state = next;
        observer.accept(next);
    }

    private void expireReceipt() {
        if (state.receipt() != null) emit(state.withReceipt(null));
    }

    private void serviceChanged() {
        generation++;
        main.removeCallbacks(refresh);
        if (service != app.service()) commandError = "";
        service = app.service();
        if (service == null) {
            emit(PlaybackState.disconnected(state.target()));
            return;
        }
        emit(state.loading());
        refresh();
    }

    void selectTarget(String packageName) {
        emit(state.select(packageName));
    }

    void start(Route route) {
        if (route != null && state.canStart()) submit(route);
    }

    void stop() {
        if (state.canStop()) submit(null);
    }

    private void submit(Route route) {
        main.removeCallbacks(refresh);
        long ticket = ++generation;
        XposedService bound = service;
        String target = state.target();
        emit(state.submitting(route != null));
        app.vector.execute(() -> {
            String failure = "";
            try {
                if (route != null && !bound.getScope().contains(target)) {
                    throw new IllegalStateException("Приложение исключено из области действия.");
                }
                var edit = bound.getRemotePreferences(Session.GROUP).edit();
                if (route == null) edit.remove(Session.KEY);
                else edit.putString(Session.KEY, new Session(target, System.currentTimeMillis(), route).encode());
                if (!app.commitSession(bound, edit)) throw new IllegalStateException("Vector не подтвердил запись.");
            } catch (RuntimeException | JSONException e) {
                failure = (route == null ? "Ошибка остановки. " : "Ошибка публикации. ") + detail(e);
            }
            String error = failure;
            main.post(() -> {
                if (ticket != generation) return;
                commandError = error;
                refresh();
            });
        });
    }

    private void refresh() {
        XposedService bound = service;
        if (!resumed || bound == null) return;
        long ticket = ++generation;
        app.vector.execute(() -> {
            List<String> scope = readScope(bound);
            Session session = null;
            String failure = "";
            try {
                if (app.sessionUnconfirmed(bound)) throw new IllegalStateException("Vector не подтвердил запись.");
                String raw = bound.getRemotePreferences(Session.GROUP).getString(Session.KEY, null);
                if (raw == null) {
                    cachedSession = null;
                    cachedRaw = null;
                } else if (!raw.equals(cachedRaw)) {
                    cachedSession = Session.decode(raw);
                    cachedRaw = raw;
                }
                session = cachedSession;
            } catch (RuntimeException | JSONException e) {
                failure = "Состояние сессии неизвестно. " + detail(e);
            }
            var process = session == null ? PlaybackState.ProcessState.UNKNOWN : readProcess(bound, session.target());
            Session current = session;
            String readError = failure;
            main.post(() -> {
                if (ticket != generation) return;
                String error = readError.isEmpty() ? commandError : readError;
                var next = state.observed(scope, current, process, error, System.currentTimeMillis());
                if (current != null && error.isEmpty() && state.session() == current && state.receipt() != null
                        && state.receipt().fresh(SystemClock.elapsedRealtime())) {
                    next = next.withReceipt(state.receipt());
                }
                emit(next);
                if (current != null && error.isEmpty()) {
                    DeliveryProbe.query(app, current, receipt -> {
                        if (ticket != generation || state.session() != current) return;
                        if (!receipt.fresh(SystemClock.elapsedRealtime())) return;
                        if (state.receipt() == null || state.receipt().deliveredElapsedMs() < receipt.deliveredElapsedMs()) {
                            emit(state.withReceipt(receipt));
                        }
                    });
                }
                main.postDelayed(refresh, 2000L);
            });
        });
    }

    private List<String> readScope(XposedService bound) {
        try {
            return bound.getScope().stream()
                    .filter(name -> name != null && PACKAGE.matcher(name).matches() && !name.equals(app.getPackageName()))
                    .distinct().sorted().collect(Collectors.toList());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static PlaybackState.ProcessState readProcess(XposedService bound, String target) {
        try {
            var processes = bound.getRunningTargets();
            for (HookedTarget process : processes) {
                if (target.equals(process.getProcessName())) return processState(process);
            }
            for (HookedTarget process : processes) {
                if (process.getProcessName().startsWith(target + ":")) return processState(process);
            }
            return PlaybackState.ProcessState.UNLISTED;
        } catch (RuntimeException e) {
            return PlaybackState.ProcessState.UNKNOWN;
        }
    }

    private static PlaybackState.ProcessState processState(HookedTarget process) {
        return switch (process.getState()) {
            case UP_TO_DATE -> PlaybackState.ProcessState.LOADED;
            case STALE -> PlaybackState.ProcessState.STALE;
            case RELOADING -> PlaybackState.ProcessState.RELOADING;
            case FAILED -> PlaybackState.ProcessState.FAILED;
        };
    }

    private static String detail(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
