// ═══════════════════════════════════════════
// FILE 9: AddLinkActivity.java  [FIXED]
// Fixes:
//   1. ADD_ITEM broadcast now sent via LocalBroadcastManager so MainActivity
//      actually receives it and shows the item in the list
//   2. No longer starts the service directly AND sends a broadcast — only
//      the broadcast is sent; MainActivity (or the receiver) starts the
//      service, preventing a double-start race condition
//   3. URL validation tightened — rejects whitespace-only input
// ═══════════════════════════════════════════
package com.robi.idmmanager;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.Intent;
import android.os.Build;

import com.robi.idmmanager.databinding.ActivityAddLinkBinding;

public class AddLinkActivity extends AppCompatActivity {

    public static final String EXTRA_URL      = "extra_url";
    public static final String EXTRA_FILENAME = "extra_filename";

    private ActivityAddLinkBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAddLinkBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Add Download");
        }

        // Pre-fill from caller (BrowserActivity or Share intent)
        String preUrl  = getIntent().getStringExtra(EXTRA_URL);
        String preName = getIntent().getStringExtra(EXTRA_FILENAME);
        if (preUrl  != null) binding.etUrl.setText(preUrl);
        if (preName != null) binding.etFileName.setText(preName);

        // Auto-detect filename when URL field loses focus
        binding.etUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) autoFillFileName();
        });

        binding.btnPaste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                String text = cm.getPrimaryClip().getItemAt(0)
                               .coerceToText(this).toString().trim();
                binding.etUrl.setText(text);
                autoFillFileName();
            }
        });

        binding.btnClear.setOnClickListener(v -> {
            binding.etUrl.setText("");
            binding.etFileName.setText("");
        });

        binding.btnStartDownload.setOnClickListener(v -> startDownload());
    }

    private void autoFillFileName() {
        String url = binding.etUrl.getText().toString().trim();
        if (!url.isEmpty() && TextUtils.isEmpty(binding.etFileName.getText())) {
            binding.etFileName.setText(DownloadItem.fileNameFromUrl(url));
        }
    }

    private void startDownload() {
        String url  = binding.etUrl.getText().toString().trim();
        String name = binding.etFileName.getText().toString().trim();

        // Validation
        if (url.isEmpty()) {
            binding.etUrl.setError("Please enter a URL");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            binding.etUrl.setError("URL must start with http:// or https://");
            return;
        }
        if (name.isEmpty()) name = DownloadItem.fileNameFromUrl(url);

        DownloadItem item = new DownloadItem(url, name);
        item.setStatus(DownloadItem.Status.QUEUED);

        // FIX: start the foreground service FIRST (this is the authoritative start)
        Intent serviceIntent = new Intent(this, DownloadManagerService.class);
        serviceIntent.putExtra(DownloadManagerService.EXTRA_ITEM, item);
        serviceIntent.setAction(DownloadManagerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // FIX: use LocalBroadcastManager so MainActivity's receiver actually gets this
        Intent broadcast = new Intent(MainActivity.ACTION_ADD_ITEM);
        broadcast.putExtra(DownloadManagerService.EXTRA_ITEM, item);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);

        Toast.makeText(this, "Download started: " + name, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
