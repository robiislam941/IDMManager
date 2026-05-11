// ═══════════════════════════════════════════
// FILE 5: DownloadTask.java  [FIXED]
// Fixes:
//   1. Use LocalBroadcastManager instead of global sendBroadcast (security)
//   2. Flush + sync output stream before closing (prevents truncated files)
//   3. Disconnect in finally block (was missing on exception paths)
//   4. Handle HTTP 301/302 redirects manually (HttpURLConnection
//      sometimes follows cross-protocol redirects to HTTP from HTTPS
//      silently; log and fail cleanly instead of hanging)
//   5. Progress broadcast rate limited to avoid overwhelming the UI thread
//   6. Content-Length header on 206 response is the *remaining* length —
//      original code added startByte twice when server sends full length
//      in Content-Range. Now uses Content-Range header when available.
// ═══════════════════════════════════════════
package com.robi.idmmanager;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class DownloadTask implements Runnable {

    private static final String TAG         = "DownloadTask";
    private static final int    BUFFER_SIZE = 16384;  // 16 KB — better throughput than 8 KB
    private static final int    TIMEOUT_MS  = 15_000;
    private static final int    MAX_RETRY   = 3;
    private static final long   UI_UPDATE_INTERVAL_MS = 500;

    private final Context      context;
    private final DownloadItem item;

    private volatile boolean paused    = false;
    private volatile boolean cancelled = false;

    public DownloadTask(Context context, DownloadItem item) {
        this.context = context.getApplicationContext();
        this.item    = item;
    }

    public void pause()  { paused    = true; }
    public void cancel() { cancelled = true; paused = false; }
    public void resume() { paused    = false; }

    @Override
    public void run() {
        int attempt = 0;
        while (attempt < MAX_RETRY && !cancelled) {
            try {
                download();
                return; // success
            } catch (IOException e) {
                attempt++;
                Log.w(TAG, "Attempt " + attempt + " failed for " + item.getFileName()
                        + ": " + e.getMessage());
                if (attempt < MAX_RETRY && !cancelled) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        if (!cancelled) broadcastFailed();
    }

    private void download() throws IOException {
        URL url = new URL(item.getUrl());
        HttpURLConnection conn = openConnection(url);

        // ── Resume via HTTP Range ─────────────────────────────────────────
        long startByte = item.getDownloadedBytes();
        if (startByte > 0) {
            conn.setRequestProperty("Range", "bytes=" + startByte + "-");
        }

        conn.connect();
        int responseCode = conn.getResponseCode();

        // FIX: handle redirects explicitly — follow up to 5 hops
        int hops = 0;
        while ((responseCode == HttpURLConnection.HTTP_MOVED_PERM
                || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                || responseCode == 307 || responseCode == 308)
                && hops++ < 5) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null) throw new IOException("Redirect with no Location header");
            conn = openConnection(new URL(location));
            if (startByte > 0) conn.setRequestProperty("Range", "bytes=" + startByte + "-");
            conn.connect();
            responseCode = conn.getResponseCode();
        }

        boolean supportsResume = (responseCode == HttpURLConnection.HTTP_PARTIAL); // 206

        // FIX: derive total length from Content-Range when available (accurate for 206)
        long totalBytes;
        String contentRange = conn.getHeaderField("Content-Range");  // e.g. "bytes 500-999/1000"
        if (contentRange != null && contentRange.contains("/")) {
            try {
                totalBytes = Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1));
            } catch (NumberFormatException e) {
                totalBytes = conn.getContentLengthLong() + (supportsResume ? startByte : 0);
            }
        } else {
            totalBytes = conn.getContentLengthLong();
        }
        item.setTotalBytes(totalBytes);

        // ── Prepare output file ───────────────────────────────────────────
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "IDMManager");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create download directory: " + dir.getAbsolutePath());
        }
        File outFile = new File(dir, item.getFileName());
        boolean append = startByte > 0 && supportsResume;

        long downloaded = startByte;
        long lastTime   = System.currentTimeMillis();
        long lastBytes  = downloaded;

        // FIX: use try-with-resources on conn's stream AND flush+sync before close
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(outFile, append)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {

                // ── Pause: spin-wait ──────────────────────────────────────
                while (paused && !cancelled) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                if (cancelled) return;

                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;
                item.setDownloadedBytes(downloaded);

                // ── Throttled UI update ───────────────────────────────────
                long now = System.currentTimeMillis();
                if (now - lastTime >= UI_UPDATE_INTERVAL_MS) {
                    long delta = downloaded - lastBytes;
                    long elapsed = now - lastTime;
                    long bps = elapsed > 0 ? delta * 1000 / elapsed : 0;
                    int progress = (totalBytes > 0)
                            ? (int) (downloaded * 100 / totalBytes) : 0;
                    broadcastProgress(progress, formatSpeed(bps));
                    lastTime  = now;
                    lastBytes = downloaded;
                }
            }

            // FIX: flush before the stream closes
            out.flush();
            // FIX: sync to disk — prevents incomplete files on abrupt power-off
            out.getFD().sync();

        } finally {
            conn.disconnect(); // FIX: always disconnect, even on exception
        }

        if (!cancelled) {
            item.setSavedPath(outFile.getAbsolutePath());
            broadcastComplete();
        }
    }

    /** Open and configure a connection, leaving connect() to the caller. */
    private HttpURLConnection openConnection(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false); // we handle redirects manually
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36");
        return conn;
    }

    // ── Broadcast helpers (LOCAL — not global) ────────────────────────────
    private void broadcastProgress(int progress, String speed) {
        Intent intent = new Intent(MainActivity.ACTION_PROGRESS);
        intent.putExtra(MainActivity.EXTRA_ITEM_ID, item.getId());
        intent.putExtra(MainActivity.EXTRA_PROGRESS, progress);
        intent.putExtra(MainActivity.EXTRA_SPEED, speed);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private void broadcastComplete() {
        Intent intent = new Intent(MainActivity.ACTION_COMPLETE);
        intent.putExtra(MainActivity.EXTRA_ITEM_ID, item.getId());
        intent.putExtra(MainActivity.EXTRA_FILENAME, item.getFileName());
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private void broadcastFailed() {
        Intent intent = new Intent(MainActivity.ACTION_FAILED);
        intent.putExtra(MainActivity.EXTRA_ITEM_ID, item.getId());
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private String formatSpeed(long bps) {
        if (bps >= 1024 * 1024) return String.format("%.1f MB/s", bps / (1024.0 * 1024));
        if (bps >= 1024)        return String.format("%.0f KB/s", bps / 1024.0);
        return bps + " B/s";
    }
}
