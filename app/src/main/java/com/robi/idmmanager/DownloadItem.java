// ═══════════════════════════════════════════
// FILE 6: DownloadItem.java  [FIXED]
// Fixes:
//   - savedPath initialised to empty string (not null) to avoid NPE on open
//   - getStatusLabel() uses exhaustive switch with default
// ═══════════════════════════════════════════
package com.robi.idmmanager;

import java.io.Serializable;
import java.util.UUID;

public class DownloadItem implements Serializable {

    private static final long serialVersionUID = 1L;   // required for stable Serializable

    public enum Status {
        QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED
    }

    private final String id;
    private String url;
    private String fileName;
    private String savedPath   = "";   // FIX: was null → NPE when opening completed file
    private long   totalBytes  = -1;
    private long   downloadedBytes = 0;
    private int    progress    = 0;
    private String speed       = "0 KB/s";
    private Status status      = Status.QUEUED;

    public DownloadItem(String url, String fileName) {
        this.id       = UUID.randomUUID().toString();
        this.url      = url;
        this.fileName = sanitizeFileName(fileName);
    }

    // ── Getters ──────────────────────────────────────────────────────────────
    public String getId()              { return id; }
    public String getUrl()             { return url; }
    public String getFileName()        { return fileName; }
    public String getSavedPath()       { return savedPath; }
    public long   getTotalBytes()      { return totalBytes; }
    public long   getDownloadedBytes() { return downloadedBytes; }
    public int    getProgress()        { return progress; }
    public String getSpeed()           { return speed; }
    public Status getStatus()          { return status; }

    // ── Setters ──────────────────────────────────────────────────────────────
    public void setUrl(String url)                      { this.url = url; }
    public void setFileName(String n)                   { this.fileName = sanitizeFileName(n); }
    public void setSavedPath(String p)                  { this.savedPath = (p != null) ? p : ""; }
    public void setTotalBytes(long b)                   { this.totalBytes = b; }
    public void setDownloadedBytes(long b)              { this.downloadedBytes = b; }
    public void setProgress(int p)                      { this.progress = p; }
    public void setSpeed(String s)                      { this.speed = (s != null) ? s : ""; }
    public void setStatus(Status s)                     { this.status = s; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Human-readable total size string. */
    public String getFormattedSize() {
        if (totalBytes <= 0) return "Unknown size";
        if (totalBytes >= 1024L * 1024 * 1024)
            return String.format("%.2f GB", totalBytes / (1024.0 * 1024 * 1024));
        if (totalBytes >= 1024 * 1024)
            return String.format("%.1f MB", totalBytes / (1024.0 * 1024));
        if (totalBytes >= 1024)
            return String.format("%.0f KB", totalBytes / 1024.0);
        return totalBytes + " B";
    }

    /** Derive a filename from a URL, stripping query params. */
    public static String fileNameFromUrl(String url) {
        if (url == null || url.isEmpty())
            return "download_" + System.currentTimeMillis();
        // Strip query string / fragment
        String name = url;
        int q = name.indexOf('?');
        if (q != -1) name = name.substring(0, q);
        int hash = name.indexOf('#');
        if (hash != -1) name = name.substring(0, hash);   // FIX: also strip fragment
        int slash = name.lastIndexOf('/');
        if (slash != -1 && slash < name.length() - 1)
            name = name.substring(slash + 1);
        name = name.trim();
        if (name.isEmpty()) return "download_" + System.currentTimeMillis();
        return name;
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty())
            return "download_" + System.currentTimeMillis();
        // FIX: also replace null bytes and leading dots (hidden files on Linux)
        return name.trim()
                   .replaceAll("[\\\\/:*?\"<>|\\x00]", "_")
                   .replaceAll("^\\.+", "_");
    }

    public String getStatusLabel() {
        switch (status) {
            case QUEUED:      return "Queued";
            case DOWNLOADING: return "Downloading";
            case PAUSED:      return "Paused";
            case COMPLETED:   return "Complete";
            case FAILED:      return "Failed";
            default:          return "Unknown";   // FIX: was missing default
        }
    }
}
