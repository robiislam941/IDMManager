// ═══════════════════════════════════════════
// FILE 3: MainActivity.java  [FIXED]
// Fixes:
//   1. BroadcastReceiver switched to LocalBroadcastManager (was global)
//   2. ADD_ITEM broadcast now registered and handled — items added from
//      AddLinkActivity actually appear in the download list
//   3. Completed items are tappable to open the downloaded file
//   4. Runtime notification permission requested on Android 13+
//   5. Serializable extra de-serialised with typed API on API 33+
// ═══════════════════════════════════════════
package com.robi.idmmanager;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.robi.idmmanager.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    // ── Public constants ─────────────────────────────────────────────────
    public static final String ACTION_PROGRESS = "com.robi.idmmanager.PROGRESS";
    public static final String ACTION_COMPLETE = "com.robi.idmmanager.COMPLETE";
    public static final String ACTION_FAILED   = "com.robi.idmmanager.FAILED";
    // FIX: ADD_ITEM was broadcast by AddLinkActivity but never listened to here
    public static final String ACTION_ADD_ITEM = "com.robi.idmmanager.ADD_ITEM";

    public static final String EXTRA_ITEM_ID  = "item_id";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_SPEED    = "speed";
    public static final String EXTRA_FILENAME = "filename";

    private static final int REQ_NOTIFICATION_PERM = 1001;

    // ── Fields ───────────────────────────────────────────────────────────
    private ActivityMainBinding binding;
    private DownloadAdapter     adapter;
    private List<DownloadItem>  downloadList;
    private AdView              adView;

    // ── Receiver: progress / complete / failed / add ─────────────────────
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String id       = intent.getStringExtra(EXTRA_ITEM_ID);
            int    progress = intent.getIntExtra(EXTRA_PROGRESS, 0);
            String speed    = intent.getStringExtra(EXTRA_SPEED);
            String filename = intent.getStringExtra(EXTRA_FILENAME);

            switch (intent.getAction()) {
                case ACTION_PROGRESS:
                    updateItemProgress(id, progress, speed, DownloadItem.Status.DOWNLOADING);
                    break;
                case ACTION_COMPLETE:
                    updateItemProgress(id, 100, "Done", DownloadItem.Status.COMPLETED);
                    if (filename != null)
                        Toast.makeText(ctx, filename + " downloaded!", Toast.LENGTH_SHORT).show();
                    break;
                case ACTION_FAILED:
                    updateItemProgress(id, 0, "Failed", DownloadItem.Status.FAILED);
                    break;
                // FIX: handle items added from AddLinkActivity
                case ACTION_ADD_ITEM: {
                    DownloadItem item = extractItem(intent);
                    if (item != null) addItemToList(item);
                    break;
                }
            }
            toggleEmptyView();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyThemePreference();
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        // AdMob
        MobileAds.initialize(this, s -> {});
        adView = binding.adView;
        adView.loadAd(new AdRequest.Builder().build());

        // RecyclerView
        downloadList = new ArrayList<>();
        adapter = new DownloadAdapter(downloadList, this);
        binding.recyclerDownloads.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerDownloads.setAdapter(adapter);
        toggleEmptyView();

        // FAB
        binding.fabAdd.setOnClickListener(v ->
                startActivity(new Intent(this, AddLinkActivity.class)));

        // Browser
        binding.btnBrowser.setOnClickListener(v ->
                startActivity(new Intent(this, BrowserActivity.class)));

        // FIX: register for all four actions via LocalBroadcastManager
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PROGRESS);
        filter.addAction(ACTION_COMPLETE);
        filter.addAction(ACTION_FAILED);
        filter.addAction(ACTION_ADD_ITEM);
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(downloadReceiver, filter);

        // FIX: request POST_NOTIFICATIONS on Android 13+
        requestNotificationPermission();

        handleSharedIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSharedIntent(intent);
    }

    /** Handle URLs shared from Chrome or other apps. */
    private void handleSharedIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) {
                Intent i = new Intent(this, AddLinkActivity.class);
                i.putExtra(AddLinkActivity.EXTRA_URL, text);
                startActivity(i);
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        } else if (id == R.id.action_clear_completed) {
            clearCompleted();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Download list helpers ─────────────────────────────────────────────

    /** Insert a new item at the top of the list (does not start the service). */
    private void addItemToList(DownloadItem item) {
        // Avoid duplicates if the item was already added locally
        for (DownloadItem d : downloadList) {
            if (d.getId().equals(item.getId())) return;
        }
        downloadList.add(0, item);
        adapter.notifyItemInserted(0);
        toggleEmptyView();
    }

    /** Called from DownloadAdapter: pause a running download. */
    public void pauseDownload(String itemId) {
        Intent i = new Intent(this, DownloadManagerService.class);
        i.setAction(DownloadManagerService.ACTION_PAUSE);
        i.putExtra(EXTRA_ITEM_ID, itemId);
        startService(i);
    }

    /** Called from DownloadAdapter: resume a paused download. */
    public void resumeDownload(DownloadItem item) {
        Intent i = new Intent(this, DownloadManagerService.class);
        i.setAction(DownloadManagerService.ACTION_RESUME);
        i.putExtra(DownloadManagerService.EXTRA_ITEM, item);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    /** Called from DownloadAdapter: cancel and remove a download. */
    public void removeDownload(String itemId, int position) {
        Intent i = new Intent(this, DownloadManagerService.class);
        i.setAction(DownloadManagerService.ACTION_CANCEL);
        i.putExtra(EXTRA_ITEM_ID, itemId);
        startService(i);
        if (position >= 0 && position < downloadList.size()) {
            downloadList.remove(position);
            adapter.notifyItemRemoved(position);
        }
        toggleEmptyView();
    }

    /** FIX: open a completed file when the user taps on it. */
    public void openDownloadedFile(DownloadItem item) {
        String path = item.getSavedPath();
        if (path == null || path.isEmpty()) {
            Toast.makeText(this, "File path not found", Toast.LENGTH_SHORT).show();
            return;
        }
        File file = new File(path);
        if (!file.exists()) {
            Toast.makeText(this, "File not found on disk", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", file);
        Intent open = new Intent(Intent.ACTION_VIEW);
        open.setDataAndType(uri, getContentResolver().getType(uri));
        open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(open);
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateItemProgress(String id, int progress, String speed,
                                    DownloadItem.Status status) {
        for (int i = 0; i < downloadList.size(); i++) {
            DownloadItem it = downloadList.get(i);
            if (it.getId().equals(id)) {
                it.setProgress(progress);
                it.setSpeed(speed != null ? speed : "");
                it.setStatus(status);
                adapter.notifyItemChanged(i);
                break;
            }
        }
    }

    private void clearCompleted() {
        downloadList.removeIf(it -> it.getStatus() == DownloadItem.Status.COMPLETED);
        adapter.notifyDataSetChanged();
        toggleEmptyView();
    }

    private void toggleEmptyView() {
        boolean empty = downloadList.isEmpty();
        binding.layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.recyclerDownloads.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void applyThemePreference() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        switch (prefs.getString("theme_mode", "system")) {
            case "light":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO); break;
            case "dark":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    // FIX: ask for notification permission on Android 13+
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION_PERM);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private DownloadItem extractItem(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getSerializableExtra(DownloadManagerService.EXTRA_ITEM, DownloadItem.class);
        }
        return (DownloadItem) intent.getSerializableExtra(DownloadManagerService.EXTRA_ITEM);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadReceiver);
        if (adView != null) adView.destroy();
    }

    @Override
    protected void onResume() { super.onResume(); if (adView != null) adView.resume(); }

    @Override
    protected void onPause()  { super.onPause();  if (adView != null) adView.pause(); }
}
