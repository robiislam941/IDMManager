// ═══════════════════════════════════════════
// FILE 8: BrowserActivity.java  [FIXED]
// Fixes:
//   1. Added @JavascriptInterface VideoJsBridge so Android.onVideoFound()
//      in the injected JS actually works (was silently no-op before)
//   2. JS injection now uses evaluateJavascript() instead of loadUrl("javascript:…")
//      which is deprecated and blocks the renderer thread
//   3. addJavascriptInterface() called before any page is loaded
//   4. WebView.destroy() called in onDestroy() (already present — kept)
//   5. Download detection bar hides correctly on new page start
// ═══════════════════════════════════════════
package com.robi.idmmanager;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class BrowserActivity extends AppCompatActivity {

    private WebView     webView;
    private EditText    etUrl;
    private ProgressBar progressBar;
    private LinearLayout downloadBar;
    private String detectedVideoUrl = null;

    private static final String[] VIDEO_EXTS = {
            ".mp4", ".mkv", ".avi", ".mov", ".flv", ".webm",
            ".3gp", ".m4v", ".mpg", ".mpeg", ".ts", ".m3u8"
    };

    // FIX: JS bridge so Android.onVideoFound() calls from injected JS actually work
    private class VideoJsBridge {
        @JavascriptInterface
        public void onVideoFound(String url) {
            if (url != null && !url.isEmpty()) {
                // Called from JS thread — must post to main thread
                webView.post(() -> {
                    detectedVideoUrl = url;
                    showDownloadBar(url);
                });
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        webView     = findViewById(R.id.webView);
        etUrl       = findViewById(R.id.etUrl);
        progressBar = findViewById(R.id.browserProgress);
        downloadBar = findViewById(R.id.downloadDetectedBar);
        ImageButton btnGo      = findViewById(R.id.btnGo);
        ImageButton btnBack    = findViewById(R.id.btnBack);
        ImageButton btnForward = findViewById(R.id.btnForward);
        ImageButton btnRefresh = findViewById(R.id.btnRefresh);
        ImageButton btnDl      = findViewById(R.id.btnDownloadDetected);

        // ── WebView settings ──────────────────────────────────────────────
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36");

        // FIX: register JS bridge BEFORE loading any page
        webView.addJavascriptInterface(new VideoJsBridge(), "Android");

        // ── WebViewClient ─────────────────────────────────────────────────
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                checkForVideoUrl(req.getUrl().toString());
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                etUrl.setText(url);
                hideDownloadBar();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                etUrl.setText(url);
                injectVideoDetectionScript();
            }
        });

        // ── WebChromeClient ───────────────────────────────────────────────
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
            }
            @Override
            public void onReceivedTitle(WebView view, String title) {
                setTitle(title);
            }
        });

        // ── Controls ──────────────────────────────────────────────────────
        btnGo.setOnClickListener(v -> navigate());
        etUrl.setOnEditorActionListener((v, actionId, event) -> { navigate(); return true; });
        btnBack.setOnClickListener(v    -> { if (webView.canGoBack())    webView.goBack(); });
        btnForward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        btnRefresh.setOnClickListener(v -> webView.reload());
        btnDl.setOnClickListener(v -> {
            if (detectedVideoUrl != null) startDownloadFromUrl(detectedVideoUrl);
        });

        webView.loadUrl("https://www.google.com");
    }

    private void navigate() {
        String raw = etUrl.getText().toString().trim();
        if (raw.isEmpty()) return;
        String url;
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            url = raw;
        } else if (raw.contains(".") && !raw.contains(" ")) {
            url = "https://" + raw;
        } else {
            url = "https://www.google.com/search?q=" + raw.replace(" ", "+");
        }
        webView.loadUrl(url);
    }

    private void checkForVideoUrl(String url) {
        if (url == null) return;
        String lower = url.toLowerCase();
        for (String ext : VIDEO_EXTS) {
            if (lower.contains(ext)) {
                detectedVideoUrl = url;
                showDownloadBar(url);
                return;
            }
        }
    }

    /** FIX: use evaluateJavascript (non-deprecated, doesn't block renderer). */
    private void injectVideoDetectionScript() {
        String js =
            "(function(){" +
            "  var vs = document.querySelectorAll('video');" +
            "  for(var i=0;i<vs.length;i++){" +
            "    var s = vs[i].src || vs[i].currentSrc;" +
            "    if(s && s.startsWith('http')) Android.onVideoFound(s);" +
            "    vs[i].addEventListener('play', function(){" +
            "      var u = this.src||this.currentSrc;" +
            "      if(u && u.startsWith('http')) Android.onVideoFound(u);" +
            "    });" +
            "  }" +
            "})();";
        webView.evaluateJavascript(js, null); // FIX: was loadUrl("javascript:…")
    }

    private void showDownloadBar(String url) {
        String name = DownloadItem.fileNameFromUrl(url);
        runOnUiThread(() -> {
            downloadBar.setVisibility(View.VISIBLE);
            ((TextView) downloadBar.findViewById(R.id.tvDetectedFileName))
                    .setText("Video: " + name);
        });
    }

    private void hideDownloadBar() {
        detectedVideoUrl = null;
        runOnUiThread(() -> downloadBar.setVisibility(View.GONE));
    }

    private void startDownloadFromUrl(String url) {
        String fileName = DownloadItem.fileNameFromUrl(url);
        Intent i = new Intent(this, AddLinkActivity.class);
        i.putExtra(AddLinkActivity.EXTRA_URL, url);
        i.putExtra(AddLinkActivity.EXTRA_FILENAME, fileName);
        startActivity(i);
        Toast.makeText(this, "Added to downloads", Toast.LENGTH_SHORT).show();
        hideDownloadBar();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webView.destroy();
    }
}
