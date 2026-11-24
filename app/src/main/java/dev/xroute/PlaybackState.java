package dev.xroute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

record PlaybackState(Phase phase, List<String> targets, String target, boolean scopeKnown,
                     Session session, ProcessState process, String error, long nowMs, DeliveryReceipt receipt) {
    enum Phase { DISCONNECTED, LOADING, IDLE, PUBLISHING, PUBLISHED, FINISHED, STOPPING, ERROR }
    enum ProcessState { UNKNOWN, UNLISTED, LOADED, STALE, RELOADING, FAILED }

    PlaybackState {
        targets = Collections.unmodifiableList(new ArrayList<>(targets));
    }

    static PlaybackState disconnected(String target) {
        return new PlaybackState(Phase.DISCONNECTED, Collections.emptyList(), target, false,
                null, ProcessState.UNKNOWN, "", 0, null);
    }

    PlaybackState loading() {
        return new PlaybackState(Phase.LOADING, Collections.emptyList(), target, false,
                null, ProcessState.UNKNOWN, "", 0, null);
    }

    PlaybackState observed(List<String> scope, Session published, ProcessState status, String failure, long now) {
        List<String> packages = scope == null ? Collections.emptyList() : scope;
        String selected = target;
        if (scope != null && !scope.contains(selected)) {
            selected = scope.size() == 1 ? scope.get(0) : "";
        }
        Phase next;
        if (!failure.isEmpty()) next = Phase.ERROR;
        else if (published == null) next = Phase.IDLE;
        else if ((now - published.startMs()) / 1000.0 >= published.route().durationSeconds()) next = Phase.FINISHED;
        else next = Phase.PUBLISHED;
        return new PlaybackState(next, packages, selected, scope != null, published, status, failure, now, null);
    }

    PlaybackState withReceipt(DeliveryReceipt confirmed) {
        return new PlaybackState(phase, targets, target, scopeKnown, session, process, error, nowMs, confirmed);
    }

    PlaybackState select(String packageName) {
        if (busy() || !targets.contains(packageName)) return this;
        return new PlaybackState(phase, targets, packageName, scopeKnown, session, process, error, nowMs, receipt);
    }

    PlaybackState submitting(boolean start) {
        if (start ? !canStart() : !canStop()) return this;
        return new PlaybackState(start ? Phase.PUBLISHING : Phase.STOPPING,
                targets, target, scopeKnown, session, process, "", nowMs, null);
    }

    boolean connected() { return phase != Phase.DISCONNECTED; }
    boolean busy() { return phase == Phase.LOADING || phase == Phase.PUBLISHING || phase == Phase.STOPPING; }
    boolean restart() { return session != null; }
    boolean canStart() { return connected() && !busy() && targets.contains(target); }
    boolean canStop() { return connected() && !busy() && (session != null || phase == Phase.ERROR); }

    String scopeText() {
        if (!connected()) return "Включите XRoute в менеджере Vector.";
        if (phase == Phase.LOADING) return "Проверяем область действия…";
        if (!scopeKnown) return "Не удалось прочитать область действия Vector.";
        if (targets.isEmpty()) return "Выберите приложение в области действия XRoute в Vector и нажмите «Применить».";
        if (target.isEmpty()) return "Выберите приложение для маршрута.";
        return "";
    }

    String sessionText() {
        return switch (phase) {
            case DISCONNECTED -> "Vector не подключён";
            case LOADING -> "Читаем сессию из Vector…";
            case IDLE -> "Маршрут остановлен";
            case PUBLISHING -> "Публикуем маршрут…";
            case STOPPING -> "Останавливаем сессию…";
            case ERROR -> error;
            case FINISHED -> "Расчётное время маршрута истекло · " + session.target() + "\n" + processText() + "\n" + deliveryText();
            case PUBLISHED -> "Опубликовано для " + session.target() + "\n"
                    + clock((nowMs - session.startMs()) / 1000.0) + " / " + clock(session.route().durationSeconds())
                    + " · " + processText() + "\n" + deliveryText();
        };
    }

    private String deliveryText() {
        return receipt == null ? "Доставка точек трекеру не подтверждена" : receipt.text();
    }

    private String processText() {
        if (scopeKnown && !targets.contains(session.target())) return "Приложение исключено из области действия";
        return switch (process) {
            case UNKNOWN -> "Состояние процесса неизвестно";
            case UNLISTED -> "Vector не видит процесс с модулем";
            case LOADED -> "Модуль загружен";
            case STALE -> "Перезапустите приложение для загрузки обновления";
            case RELOADING -> "Vector перезагружает модуль";
            case FAILED -> "Vector сообщил об ошибке загрузки модуля";
        };
    }

    private static String clock(double seconds) {
        long value = (long) Math.max(0, seconds);
        return String.format(Locale.ROOT, "%d:%02d", value / 60, value % 60);
    }
}
