package io.wifidrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the receiver alive while the screen is off or another app is in front. */
public class DropService extends Service {
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("transfer", "Transfer", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, "transfer")
                    .setContentTitle("WifiDrop is receiving files")
                    .setContentText("Keep the hard disk plugged in.")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOngoing(true)
                    .setContentIntent(open)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(1, n);
        } catch (Exception e) {
            Hub.stats.log("Background mode unavailable; keep this screen open.");
        }
        String err = Hub.start(getApplicationContext());
        if (err != null) {
            Hub.stats.lastError = err;
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (wake == null) {
                wake = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifidrop:transfer");
                wake.acquire();
            }
            if (wifi == null) {
                wifi = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wifidrop:transfer");
                wifi.acquire();
            }
        } catch (Exception ignored) { }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        Hub.stop();
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
