package dev.nofocus.folderplayer;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Visible, cancellable lifetime for local media preparation and encoding. */
public final class VisualMusicWorkService extends Service {
    static volatile Runnable cancel;
    private PowerManager.WakeLock wake;
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("visual-work", "Visual music export", NotificationManager.IMPORTANCE_LOW));
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoFocus:VisualWork"); wake.acquire(2 * 60 * 60 * 1000L);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "cancel".equals(intent.getAction())) { if (cancel != null) cancel.run(); stopSelf(); return START_NOT_STICKY; }
        PendingIntent open = PendingIntent.getActivity(this, 62, new Intent(this, VisualMusicActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 63, new Intent(this, VisualMusicWorkService.class).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "visual-work") : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(R.drawable.ic_stat_music_note).setContentTitle("Preparing visual music")
                .setContentText("Open NoFocus for progress").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Cancel", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(62, notification, Build.VERSION.SDK_INT >= 35 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(62, notification);
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId, int type) { if (cancel != null) cancel.run(); stopSelf(); }
    @Override public void onDestroy() { if (wake != null && wake.isHeld()) wake.release(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
