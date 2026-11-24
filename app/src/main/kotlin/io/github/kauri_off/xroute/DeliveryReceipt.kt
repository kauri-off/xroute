package io.github.kauri_off.xroute

import android.os.SystemClock

@JvmRecord
internal data class DeliveryReceipt(val fixWallTimeMs: Long, val deliveredElapsedMs: Long) {
    fun fresh(elapsedMs: Long) =
        deliveredElapsedMs > 0 && elapsedMs >= deliveredElapsedMs && elapsedMs - deliveredElapsedMs < MAX_AGE_MS

    fun text(): String {
        val seconds = (SystemClock.elapsedRealtime() - deliveredElapsedMs).coerceAtLeast(0) / 1000
        return "GPS-подписчик получил точку по таймеру · $seconds с назад"
    }

    fun fix(session: Session?): Route.Fix? =
        session?.let { it.route.sample((fixWallTimeMs - it.startMs) / 1000.0) }

    companion object {
        const val MAX_AGE_MS = 10_000L
    }
}
