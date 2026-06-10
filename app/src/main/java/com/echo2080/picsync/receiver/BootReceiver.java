package com.echo2080.picsync.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.echo2080.picsync.service.SyncService;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "手机开机，启动同步服务");

            // 启动后台服务
            Intent serviceIntent = new Intent(context, SyncService.class);
            context.startForegroundService(serviceIntent);
        }
    }
}