package io.github.kauri_off.xroute

import android.location.Location
import android.os.SystemClock

internal fun interface PlaybackSource {
    fun sample(provider: String, wallTimeMillis: Long, elapsedRealtimeNanos: Long): Location?

    @JvmRecord
    data class DeliveryFix(val location: Location, val sessionStartMs: Long)

    fun sampleForDelivery(provider: String): DeliveryFix? =
        sample(provider)?.let { DeliveryFix(it, 0) }

    fun delivered(fix: DeliveryFix) {}

    fun lastDelivery(sessionStartMs: Long): DeliveryReceipt? = null

    fun sample(provider: String): Location? =
        sample(provider, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())

    fun observeSessionChanges(listener: Runnable): Runnable? = null

    fun snapshot(): PlaybackSource = this
}
