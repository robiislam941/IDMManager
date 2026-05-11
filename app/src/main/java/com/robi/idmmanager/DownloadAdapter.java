// ═══════════════════════════════════════════
// FILE 7: DownloadAdapter.java  [FIXED]
// Fixes:
//   1. Completed items are now tappable — calls MainActivity.openDownloadedFile()
//   2. getAdapterPosition() replaced with getBindingAdapterPosition()
//      (getAdapterPosition() was deprecated in RecyclerView 1.2)
//   3. Status color now works on API < 23 via ContextCompat
// ═══════════════════════════════════════════
package com.robi.idmmanager;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.ViewHolder> {

    private final List<DownloadItem> items;
    private final MainActivity       host;

    public DownloadAdapter(List<DownloadItem> items, MainActivity host) {
        this.items = items;
        this.host  = host;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_download, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadItem item = items.get(position);

        holder.tvFileName.setText(item.getFileName());
        holder.tvStatus.setText(item.getStatusLabel());
        holder.tvSpeed.setText(item.getSpeed());
        holder.tvSize.setText(item.getFormattedSize());
        holder.progressBar.setProgress(item.getProgress());
        holder.tvProgress.setText(item.getProgress() + "%");

        // ── Pause/Resume button state ─────────────────────────────────────
        switch (item.getStatus()) {
            case DOWNLOADING:
                holder.btnPauseResume.setImageResource(android.R.drawable.ic_media_pause);
                holder.btnPauseResume.setEnabled(true);
                break;
            case PAUSED:
            case FAILED:
            case QUEUED:
                holder.btnPauseResume.setImageResource(android.R.drawable.ic_media_play);
                holder.btnPauseResume.setEnabled(true);
                break;
            case COMPLETED:
                holder.btnPauseResume.setImageResource(android.R.drawable.ic_media_play);
                holder.btnPauseResume.setEnabled(false);
                break;
        }

        // ── Status colour (FIX: use ContextCompat for API < 23 safety) ───
        int colorRes;
        switch (item.getStatus()) {
            case COMPLETED: colorRes = R.color.status_complete; break;
            case FAILED:    colorRes = R.color.status_failed;   break;
            case PAUSED:    colorRes = R.color.status_paused;   break;
            default:        colorRes = R.color.status_active;   break;
        }
        holder.tvStatus.setTextColor(
                ContextCompat.getColor(holder.itemView.getContext(), colorRes));

        // ── Tap row to open file when completed ───────────────────────────
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition(); // FIX: deprecated method replaced
            if (pos == RecyclerView.NO_ID) return;
            DownloadItem it = items.get(pos);
            if (it.getStatus() == DownloadItem.Status.COMPLETED) {
                host.openDownloadedFile(it);
            }
        });

        // ── Pause / Resume ────────────────────────────────────────────────
        holder.btnPauseResume.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition(); // FIX
            if (pos == RecyclerView.NO_ID) return;
            DownloadItem it = items.get(pos);
            if (it.getStatus() == DownloadItem.Status.DOWNLOADING) {
                it.setStatus(DownloadItem.Status.PAUSED);
                notifyItemChanged(pos);
                host.pauseDownload(it.getId());
            } else {
                it.setStatus(DownloadItem.Status.QUEUED);
                notifyItemChanged(pos);
                host.resumeDownload(it);
            }
        });

        // ── Delete ────────────────────────────────────────────────────────
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition(); // FIX
            if (pos != RecyclerView.NO_ID) {
                host.removeDownload(items.get(pos).getId(), pos);
            }
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView    tvFileName, tvStatus, tvSpeed, tvSize, tvProgress;
        ProgressBar progressBar;
        ImageButton btnPauseResume, btnDelete;

        ViewHolder(@NonNull View view) {
            super(view);
            tvFileName     = view.findViewById(R.id.tvFileName);
            tvStatus       = view.findViewById(R.id.tvStatus);
            tvSpeed        = view.findViewById(R.id.tvSpeed);
            tvSize         = view.findViewById(R.id.tvSize);
            tvProgress     = view.findViewById(R.id.tvProgress);
            progressBar    = view.findViewById(R.id.progressBar);
            btnPauseResume = view.findViewById(R.id.btnPauseResume);
            btnDelete      = view.findViewById(R.id.btnDelete);
        }
    }
}
