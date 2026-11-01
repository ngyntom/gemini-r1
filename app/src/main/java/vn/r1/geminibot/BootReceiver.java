package vn.r1.geminibot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Tự khởi động bot khi R1 bật nguồn. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        ctx.startService(new Intent(ctx, BotService.class));
    }
}
