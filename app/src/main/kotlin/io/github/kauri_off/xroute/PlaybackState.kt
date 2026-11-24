package io.github.kauri_off.xroute

import java.util.Collections
import java.util.Locale

@JvmRecord
@ConsistentCopyVisibility
internal data class PlaybackState private constructor(
    val phase: Phase,
    val targets: List<String>,
    val target: String,
    val scopeKnown: Boolean,
    val session: Session?,
    val process: ProcessState,
    val error: String,
    val nowMs: Long,
    val receipt: DeliveryReceipt?,
) {
    enum class Phase { DISCONNECTED, LOADING, IDLE, PUBLISHING, PUBLISHED, FINISHED, STOPPING, ERROR }
    enum class ProcessState { UNKNOWN, UNLISTED, LOADED, STALE, RELOADING, FAILED }

    fun loading() = initial(Phase.LOADING, target)

    fun observed(
        scope: List<String>?,
        published: Session?,
        status: ProcessState,
        failure: String,
        now: Long,
    ): PlaybackState {
        val packages = scope?.let { Collections.unmodifiableList(ArrayList(it)) }.orEmpty()
        val selected = if (scope == null || target in packages) target else packages.singleOrNull().orEmpty()
        val next = when {
            failure.isNotEmpty() -> Phase.ERROR
            published == null -> Phase.IDLE
            (now - published.startMs) / 1000.0 >= published.route.durationSeconds() -> Phase.FINISHED
            else -> Phase.PUBLISHED
        }
        return PlaybackState(next, packages, selected, scope != null, published, status, failure, now, null)
    }

    fun withReceipt(confirmed: DeliveryReceipt?) = copy(receipt = confirmed)

    fun select(packageName: String): PlaybackState {
        if (busy() || packageName !in targets) return this
        return copy(target = packageName)
    }

    fun submitting(start: Boolean): PlaybackState {
        val allowed = if (start) canStart() else canStop()
        if (!allowed) return this
        val next = if (start) Phase.PUBLISHING else Phase.STOPPING
        return copy(phase = next, error = "", receipt = null)
    }

    fun connected() = phase != Phase.DISCONNECTED
    fun busy() = phase == Phase.LOADING || phase == Phase.PUBLISHING || phase == Phase.STOPPING
    fun restart() = session != null
    fun canStart() = connected() && !busy() && target in targets
    fun canStop() = connected() && !busy() && (session != null || phase == Phase.ERROR)

    fun scopeText() = when {
        !connected() -> "Включите XRoute в менеджере Vector."
        phase == Phase.LOADING -> "Проверяем область действия…"
        !scopeKnown -> "Не удалось прочитать область действия Vector."
        targets.isEmpty() -> "Выберите приложение в области действия XRoute в Vector и нажмите «Применить»."
        target.isEmpty() -> "Выберите приложение для маршрута."
        else -> ""
    }

    fun sessionText(): String = when (phase) {
        Phase.DISCONNECTED -> "Vector не подключён"
        Phase.LOADING -> "Читаем сессию из Vector…"
        Phase.IDLE -> "Маршрут остановлен"
        Phase.PUBLISHING -> "Публикуем маршрут…"
        Phase.STOPPING -> "Останавливаем сессию…"
        Phase.ERROR -> error
        Phase.FINISHED, Phase.PUBLISHED -> publishedText(checkNotNull(session))
    }

    private fun publishedText(published: Session): String {
        val process = processText(published.target)
        val delivery = receipt?.text() ?: "Доставка точек трекеру не подтверждена"
        if (phase == Phase.FINISHED) return "Расчётное время маршрута истекло · ${published.target}\n$process\n$delivery"
        val elapsed = clock((nowMs - published.startMs) / 1000.0)
        val duration = clock(published.route.durationSeconds())
        return "Опубликовано для ${published.target}\n$elapsed / $duration · $process\n$delivery"
    }

    private fun processText(publishedTarget: String): String {
        if (scopeKnown && publishedTarget !in targets) return "Приложение исключено из области действия"
        return when (process) {
            ProcessState.UNKNOWN -> "Состояние процесса неизвестно"
            ProcessState.UNLISTED -> "Vector не видит процесс с модулем"
            ProcessState.LOADED -> "Модуль загружен"
            ProcessState.STALE -> "Перезапустите приложение для загрузки обновления"
            ProcessState.RELOADING -> "Vector перезагружает модуль"
            ProcessState.FAILED -> "Vector сообщил об ошибке загрузки модуля"
        }
    }

    private fun clock(seconds: Double): String {
        val value = seconds.coerceAtLeast(0.0).toLong()
        return String.format(Locale.ROOT, "%d:%02d", value / 60, value % 60)
    }

    companion object {
        @JvmStatic
        fun disconnected(target: String) = initial(Phase.DISCONNECTED, target)

        private fun initial(phase: Phase, target: String) =
            PlaybackState(phase, emptyList(), target, false, null, ProcessState.UNKNOWN, "", 0, null)
    }
}
