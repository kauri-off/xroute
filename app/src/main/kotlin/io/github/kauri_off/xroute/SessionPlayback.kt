package io.github.kauri_off.xroute

import android.content.SharedPreferences
import android.location.Location
import android.os.SystemClock
import io.github.kauri_off.xroute.PlaybackSource.DeliveryFix
import org.json.JSONException
import java.util.function.Consumer

internal class SessionPlayback(
    private val packageName: String,
    private val preferences: SharedPreferences,
    private val onReadError: Consumer<Exception>,
) : PlaybackSource {
    private var previous: String? = null
    private var session: Session? = null
    private var readFailed = false
    private var deliveredSession = 0L
    private var lastReceipt: DeliveryReceipt? = null

    @Synchronized
    private fun active(): Session? {
        val raw = try {
            preferences.getString(Session.KEY, null)
        } catch (error: RuntimeException) {
            if (!readFailed) onReadError.accept(error)
            readFailed = true
            return null
        }
        readFailed = false
        if (raw == previous) return session

        previous = raw
        session = null
        if (raw != null) {
            try {
                session = Session.decode(raw).takeIf { it.target == packageName }
            } catch (error: JSONException) {
                onReadError.accept(error)
            } catch (error: IllegalArgumentException) {
                onReadError.accept(error)
            }
        }
        return session
    }

    override fun sample(provider: String, wallTimeMillis: Long, elapsedRealtimeNanos: Long): Location? =
        sample(active(), provider, wallTimeMillis, elapsedRealtimeNanos)

    override fun sampleForDelivery(provider: String): DeliveryFix? {
        val current = active() ?: return null
        val fix = sample(current, provider, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()) ?: return null
        return DeliveryFix(fix, current.startMs)
    }

    @Synchronized
    override fun delivered(fix: DeliveryFix) {
        if (session?.startMs == fix.sessionStartMs) {
            deliveredSession = fix.sessionStartMs
            lastReceipt = DeliveryReceipt(fix.location.time, SystemClock.elapsedRealtime())
        }
    }

    @Synchronized
    override fun lastDelivery(sessionStartMs: Long): DeliveryReceipt? =
        lastReceipt.takeIf { sessionStartMs == deliveredSession }

    override fun snapshot(): PlaybackSource {
        val current = active()
        return PlaybackSource { provider, wallTime, elapsedRealtime ->
            sample(current, provider, wallTime, elapsedRealtime)
        }
    }

    override fun observeSessionChanges(listener: Runnable): Runnable {
        val observer = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == Session.KEY) listener.run()
        }
        preferences.registerOnSharedPreferenceChangeListener(observer)
        return Runnable { preferences.unregisterOnSharedPreferenceChangeListener(observer) }
    }

    private fun sample(session: Session?, provider: String, wallTime: Long, elapsedRealtime: Long): Location? {
        if (session == null || wallTime < session.startMs) return null
        val fix = session.route.sample((wallTime - session.startMs) / 1000.0)
        return Location(provider).apply {
            latitude = fix.point().lat()
            longitude = fix.point().lon()
            speed = fix.speedMps().toFloat()
            bearing = fix.bearingDegrees().toFloat()
            accuracy = 5f
            time = wallTime
            elapsedRealtimeNanos = elapsedRealtime
        }
    }
}
