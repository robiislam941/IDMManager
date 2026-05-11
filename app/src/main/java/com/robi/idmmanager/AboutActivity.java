// ═══════════════════════════════════════════
// FILE 11: AboutActivity.java
// Path: app/src/main/java/com/robi/idmmanager/AboutActivity.java
// ═══════════════════════════════════════════
package com.robi.idmmanager;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("About");
        }

        // App version
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView tvVersion = findViewById(R.id.tvVersion);
            tvVersion.setText("Version " + pInfo.versionName);
        } catch (PackageManager.NameNotFoundException ignored) {}

        // Privacy Policy
        findViewById(R.id.btnPrivacyPolicy).setOnClickListener(v -> {
            // Replace with your real Privacy Policy URL
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://sites.google.com/view/idmmanager-privacy-policy"));
            startActivity(intent);
        });

        // Rate App
        findViewById(R.id.btnRateApp).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + getPackageName()));
            try { startActivity(intent); }
            catch (Exception e) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName())));
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
