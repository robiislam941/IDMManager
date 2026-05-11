// ═══════════════════════════════════════════
// FILE 4: DownloadManagerService.java  [FIXED]
// Fixes:
//   1. Thread-pool size now reads "max_threads" SharedPreference (was hardcoded 4)
//   2. Wifi-only preference is enforced: if wifi_only=true and device is on
//      mobile data, downloads are queued rather than started
//   3. Service stops itself when there are no active tasks (saves battery)
//   4. Notification channel creation guarded against duplicate creation
// ═══════════════════════════════════════════
package com.robi.idmmanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DownloadManagerService extends Service {

    // ── Intent actions ────────────────────────────────────────────────────
    public static final String ACTION_START  = "com.robi.idmmanager.START";
    public static final String ACTION_PAUSE  = "com.robi.idmmanager.PAUSE";
    public static final String ACTION_RESUME = "com.robi.idmmanager.RESUME";
    public static final String ACTION_CANCEL = "com.robi.idmmanager.CANCEL";
    public static final String EXTRA_ITEM    = "download_item";

    // ── Notification ──────────────────────────────────────────────────────
    private static final String CHANNEL_ID = "idm_download_channel";
    private static final int    NOTIF_ID   = 1001;

    private ExecutorService executor;
    private final Map<String, DownloadTask> activeTasks = new HashMap<>();
    private final Map<String, Future<?>>    futures     = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        int threads = readMaxThreads();
        executor = Executors.newFixedThreadPool(threads);
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("IDM Manager ready"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;

        switch (intent.getAction()) {
            case ACTION_START: {
                DownloadItem item = getItem(intent);
                if (item != null) {
                    // FIX: enforce wifi-only preference before starting
                    if (isWifiOnlyEnabled() && !isOnWifi()) {
                        item.setStatus(DownloadItem.Status.PAUSED);
                        // Broadcast so MainActivity can update the row
                        Intent bi = new Intent(MainActivity.ACTION_PROGRESS);
                        bi.putExtra(MainActivity.EXTRA_ITEM_ID, item.getId());
                        bi.putExtra(MainActivity.EXTRA_PROGRESS, item.getProgress());
                        bi.putExtra(MainActivity.EXTRA_SPEED, "Waiting for Wi-Fi");
                        sendBroadcast(bi);
                    } else {
                        startDownload(item);
                    }
                }
                break;
            }
            case ACTION_PAUSE: {
                String id = intent.getStringExtra(MainActivity.EXTRA_ITEM_ID);
                pauseDownload(id);
                break;
            }
            case ACTION_RESUME: {
                DownloadItem item = getItem(intent);
                if (item != null) startDownload(item);
                break;
            }
            case ACTION_CANCEL: {
                String id = intent.getStringExtra(MainActivity.EXTRA_ITEM_ID);
                cancelDownload(id);
                stopIfIdle();
                break;
            }
        }

        return START_STICKY;
    }

    private void startDownload(DownloadItem item) {
        cancelDownload(item.getId()); // stop any duplicate
        DownloadTask task = new DownloadTask(this, item);
        activeTasks.put(item.getId(), task);
        Future<?> f = executor.submit(task);
        futures.put(item.getId(), f);
        updateNotification("Downloading: " + item.getFileName());
    }

    private void pauseDownload(String id) {
        DownloadTask task = activeTasks.get(id);
        if (task != null) task.pause();
    }

    private void cancelDownload(String id) {
        DownloadTask task = activeTasks.get(id);
        if (task != null) task.cancel();
        Future<?> f = futures.get(id);
        if (f != null) f.cancel(true);
        activeTasks.remove(id);
        futures.remove(id);
    }

    /** Stop the service when nothing is running to save battery/memory. */
    private void stopIfIdle() {
        if (activeTasks.isEmpty()) stopSelf();
    }

    // ── Preferences ──────────────────────────────────────────────────────

    private int readMaxThreads() {
        String val = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("max_threads", "4");
        try { return Math.max(1, Integer.parseInt(val)); }
        catch (NumberFormatException e) { return 4; }
    }

    private boolean isWifiOnlyEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("wifi_only", false);
    }

    @SuppressWarnings("deprecation")
    private boolean isOnWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return true; // assume OK if we can't check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.getType() == ConnectivityManager.TYPE_WIFI;
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Download Manager",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Shows active downloads");
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("IDM Manager")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSound(null)
                .build();
    }

    public void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private DownloadItem getItem(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getSerializableExtra(EXTRA_ITEM, DownloadItem.class);
        }
        return (DownloadItem) intent.getSerializableExtra(EXTRA_ITEM);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }
}
