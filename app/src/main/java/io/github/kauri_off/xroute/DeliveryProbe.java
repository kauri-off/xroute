package io.github.kauri_off.xroute;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import java.util.function.Consumer;

final class DeliveryProbe {
    private static final String ACTION = BuildConfig.APPLICATION_ID + ".QUERY_PLAYBACK";
    private static final String PERMISSION = BuildConfig.APPLICATION_ID + ".permission.QUERY_PLAYBACK";

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    static void register(Context context, PlaybackSource source) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                long session = intent.getLongExtra("session", 0);
                ResultReceiver reply = intent.getParcelableExtra("reply");
                if (session <= 0 || reply == null) return;
                DeliveryReceipt receipt = source.lastDelivery(session);
                if (receipt == null) return;
                Bundle value = new Bundle();
                value.putLong("elapsed", receipt.deliveredElapsedMs());
                value.putLong("fix", receipt.fixWallTimeMs());
                reply.send(1, value);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter, PERMISSION, null);
        }
    }

    static void query(Context context, Session session, Consumer<DeliveryReceipt> observer) {
        ResultReceiver reply = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int result, Bundle data) {
                if (result != 1 || data == null) return;
                var receipt = new DeliveryReceipt(data.getLong("fix"), data.getLong("elapsed"));
                if (receipt.fixWallTimeMs() >= session.startMs() && receipt.fresh(SystemClock.elapsedRealtime())) {
                    observer.accept(receipt);
                }
            }
        };
        context.sendBroadcast(new Intent(ACTION).setPackage(session.target())
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("session", session.startMs()).putExtra("reply", reply));
    }
}
