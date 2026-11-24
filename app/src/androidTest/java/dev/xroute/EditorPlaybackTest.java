package dev.xroute;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EditorPlaybackTest {
    @Test public void publishesEditedDraftThenStopsThroughServiceBinder() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        TestFramework framework = new TestFramework();
        framework.bind(context);
        var draft = context.getSharedPreferences("editor", Context.MODE_PRIVATE);
        assertTrue(draft.edit().clear().putString("points", "[[55.75,37.62],[55.751,37.621]]")
                .putString("target", TestFramework.TRACKER).putString("speed", "5").commit());
        try (ActivityScenario<Editor> screen = ActivityScenario.launch(Editor.class)) {
            waitUntil(() -> {
                boolean[] ready = {false};
                screen.onActivity(a -> {
                    View start = label(a.getWindow().getDecorView(), "Старт");
                    if (start != null && start.isEnabled()) ready[0] = start.performClick();
                });
                return ready[0];
            });
            waitUntil(() -> framework.published() != null);
            Session published = Session.decode(framework.published());
            assertEquals(TestFramework.TRACKER, published.target());
            assertEquals(2, published.route().points.size());
            assertEquals(5 / 3.6, published.route().meanMps, .000001);
            var source = new SessionPlayback(TestFramework.TRACKER, framework.targetPreferences, e -> { throw new AssertionError(e); });
            var other = new SessionPlayback("org.example.other", framework.targetPreferences, e -> { throw new AssertionError(e); });
            var fix = source.sample("gps", published.startMs() + 1000, 1000000000);
            assertNotNull(fix);
            assertTrue(fix.getLatitude() > published.route().points.get(0).lat());
            assertNull(other.sample("gps"));
            screen.onActivity(a -> assertTrue(label(a.getWindow().getDecorView(), "Стоп").performClick()));
            waitUntil(() -> framework.published() == null);
            assertNull(source.sample("gps"));
        } finally {
            draft.edit().clear().commit();
        }
    }

    private static View label(View root, String text) {
        if (root instanceof TextView view && text.contentEquals(view.getText())) return view;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = label(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void waitUntil(BooleanSupplier ready) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (!ready.getAsBoolean()) {
            assertTrue("Timed out waiting for playback state", android.os.SystemClock.elapsedRealtime() < deadline);
            Thread.sleep(40);
        }
    }
}
