package chouchou.movie;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DownloadsFragment extends Fragment {

    private RecyclerView downloadList;
    private DownloadAdapter adapter;
    private final List<DownloadItem> downloads = new ArrayList<>();
    private DownloadManager downloadManager;
    private DownloadItem.Status activeFilter;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloads, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        downloadManager = DownloadManager.getInstance(requireContext());
        downloadManager.setOnDownloadListener(new DownloadManager.OnDownloadListener() {
            @Override public void onDownloadAdded(DownloadItem item)      { post(() -> addOrUpdate(item)); }
            @Override public void onDownloadProgress(DownloadItem item)   { post(() -> updateDownload(item)); }
            @Override public void onDownloadCompleted(DownloadItem item)  { post(() -> { updateDownload(item); toast(item.movie.title + " 下载完成"); }); }
            @Override public void onDownloadFailed(DownloadItem item, String error) { post(() -> updateDownload(item)); }
            @Override public void onDownloadPaused(DownloadItem item)     { post(() -> updateDownload(item)); }
            @Override public void onDownloadResumed(DownloadItem item)    { post(() -> updateDownload(item)); }
            @Override public void onDownloadRemoved(DownloadItem item)    { post(() -> removeDownload(item)); }
        });

        downloadList = view.findViewById(R.id.downloadList);
        downloadList.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new DownloadAdapter();
        downloadList.setAdapter(adapter);

        view.findViewById(R.id.tabAll).setOnClickListener(v -> filterByStatus(null, view));
        view.findViewById(R.id.tabDownloading).setOnClickListener(v -> filterByStatus(DownloadItem.Status.DOWNLOADING, view));
        view.findViewById(R.id.tabCompleted).setOnClickListener(v -> filterByStatus(DownloadItem.Status.COMPLETED, view));

        loadDownloads();
        updateTabs(view, null);
        downloadManager.resumeAllDownloads();
    }

    private void post(Runnable r) {
        main.post(r);
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private void loadDownloads() {
        downloads.clear();
        downloads.addAll(downloadManager.getAllDownloads());
        applyFilterAndPublish();
    }

    private void addOrUpdate(DownloadItem item) {
        for (int i = 0; i < downloads.size(); i++) {
            if (downloads.get(i).movie.magnetLink.equals(item.movie.magnetLink)) {
                downloads.set(i, item);
                applyFilterAndPublish();
                return;
            }
        }
        downloads.add(item);
        applyFilterAndPublish();
    }

    private void updateDownload(DownloadItem item) {
        for (int i = 0; i < downloads.size(); i++) {
            if (downloads.get(i).movie.magnetLink.equals(item.movie.magnetLink)) {
                downloads.set(i, item);
                applyFilterAndPublish();
                return;
            }
        }
    }

    private void removeDownload(DownloadItem item) {
        for (int i = 0; i < downloads.size(); i++) {
            if (downloads.get(i).movie.magnetLink.equals(item.movie.magnetLink)) {
                downloads.remove(i);
                applyFilterAndPublish();
                return;
            }
        }
    }

    private void applyFilterAndPublish() {
        List<DownloadItem> filtered;
        if (activeFilter == null) {
            filtered = new ArrayList<>(downloads);
        } else {
            filtered = new ArrayList<>();
            for (DownloadItem item : downloads) {
                if (item.status == activeFilter) filtered.add(item);
            }
        }
        adapter.setDownloads(filtered);
    }

    private void filterByStatus(DownloadItem.Status status, View root) {
        this.activeFilter = status;
        applyFilterAndPublish();
        updateTabs(root, status);
    }

    private void updateTabs(View root, DownloadItem.Status activeStatus) {
        root.findViewById(R.id.tabAll).setBackgroundResource(activeStatus == null ? R.drawable.bg_tab_active : 0);
        root.findViewById(R.id.tabDownloading).setBackgroundResource(activeStatus == DownloadItem.Status.DOWNLOADING ? R.drawable.bg_tab_active : 0);
        root.findViewById(R.id.tabCompleted).setBackgroundResource(activeStatus == DownloadItem.Status.COMPLETED ? R.drawable.bg_tab_active : 0);
    }

    private void showDownloadOptions(DownloadItem item) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_download_actions, null);
        dialog.setContentView(view);

        TextView title = view.findViewById(R.id.dialogTitle);
        title.setText(item.movie.title);

        if (item.status == DownloadItem.Status.DOWNLOADING) {
            view.findViewById(R.id.actionPause).setVisibility(View.VISIBLE);
            view.findViewById(R.id.actionResume).setVisibility(View.GONE);
            view.findViewById(R.id.actionPause).setOnClickListener(v -> {
                downloadManager.pauseDownload(item.movie.magnetLink);
                dialog.dismiss();
            });
        } else if (item.status == DownloadItem.Status.PAUSED) {
            view.findViewById(R.id.actionPause).setVisibility(View.GONE);
            view.findViewById(R.id.actionResume).setVisibility(View.VISIBLE);
            view.findViewById(R.id.actionResume).setOnClickListener(v -> {
                downloadManager.resumeDownload(item.movie.magnetLink);
                dialog.dismiss();
            });
        } else {
            view.findViewById(R.id.actionPause).setVisibility(View.GONE);
            view.findViewById(R.id.actionResume).setVisibility(View.GONE);
        }

        view.findViewById(R.id.actionDelete).setOnClickListener(v -> {
            downloadManager.removeDownload(item.movie.magnetLink);
            dialog.dismiss();
        });

        view.findViewById(R.id.actionCancel).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void playMovie(DownloadItem item) {
        if (item.status != DownloadItem.Status.COMPLETED || item.savePath == null || item.savePath.isEmpty()) {
            toast("电影尚未下载完成");
            return;
        }
        File file = new File(item.savePath);
        if (!file.exists()) {
            toast("文件不存在");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(file), "video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.VH> {
        private final List<DownloadItem> items = new ArrayList<>();
        private final java.util.Map<String, Boolean> expandedMap = new java.util.HashMap<>();

        void setDownloads(List<DownloadItem> newItems) {
            java.util.Set<String> existingHashes = new java.util.HashSet<>();
            for (DownloadItem item : items) {
                existingHashes.add(item.movie.magnetLink);
            }
            
            items.clear();
            items.addAll(newItems);
            
            for (DownloadItem item : newItems) {
                String key = item.movie.magnetLink;
                if (!expandedMap.containsKey(key)) {
                    expandedMap.put(key, false);
                }
            }
            
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            DownloadItem item = items.get(position);
            String key = item.movie.magnetLink;
            boolean isExpanded = expandedMap.getOrDefault(key, false);
            Context ctx = h.itemView.getContext();
            h.title.setText(item.movie.title);
            h.size.setText(item.movie.getSizeFormatted());

            switch (item.status) {
                case DOWNLOADING:
                    h.status.setText("下载中");
                    h.status.setTextColor(ctx.getResources().getColor(R.color.accent));
                    h.progressBar.setVisibility(View.VISIBLE);
                    h.progressBar.setProgress(item.progress);
                    h.progressText.setText(item.progress + "%");
                    h.speed.setText(item.speed);
                    h.playButton.setVisibility(View.GONE);
                    h.pauseButton.setVisibility(View.VISIBLE);
                    h.resumeButton.setVisibility(View.GONE);
                    break;
                case PAUSED:
                    h.status.setText("已暂停");
                    h.status.setTextColor(ctx.getResources().getColor(R.color.text_secondary));
                    h.progressBar.setVisibility(View.VISIBLE);
                    h.progressBar.setProgress(item.progress);
                    h.progressText.setText(item.progress + "%");
                    h.speed.setText("");
                    h.playButton.setVisibility(View.GONE);
                    h.pauseButton.setVisibility(View.GONE);
                    h.resumeButton.setVisibility(View.VISIBLE);
                    break;
                case COMPLETED:
                    h.status.setText("已完成");
                    h.status.setTextColor(ctx.getResources().getColor(R.color.accent));
                    h.progressBar.setVisibility(View.GONE);
                    h.progressText.setText("");
                    h.speed.setText("");
                    h.playButton.setVisibility(View.VISIBLE);
                    h.pauseButton.setVisibility(View.GONE);
                    h.resumeButton.setVisibility(View.GONE);
                    break;
                case FAILED:
                    h.status.setText("下载失败");
                    h.status.setTextColor(ctx.getResources().getColor(R.color.text_secondary));
                    h.progressBar.setVisibility(View.GONE);
                    h.progressText.setText("");
                    h.speed.setText("");
                    h.playButton.setVisibility(View.GONE);
                    h.pauseButton.setVisibility(View.GONE);
                    h.resumeButton.setVisibility(View.GONE);
                    break;
                case WAITING:
                default:
                    h.status.setText("等待中");
                    h.status.setTextColor(ctx.getResources().getColor(R.color.text_secondary));
                    h.progressBar.setVisibility(View.VISIBLE);
                    h.progressBar.setProgress(0);
                    h.progressText.setText("");
                    h.speed.setText("");
                    h.playButton.setVisibility(View.GONE);
                    h.pauseButton.setVisibility(View.GONE);
                    h.resumeButton.setVisibility(View.GONE);
                    break;
            }

            h.detailPanel.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            h.expandButton.setRotation(isExpanded ? 180 : 0);
            
            if (isExpanded) {
                h.detailSeeds.setText(String.valueOf(item.seeds));
                h.detailPeers.setText(String.valueOf(item.peers));
                h.detailDownSpeed.setText(item.speed);
                h.detailUpSpeed.setText(formatSpeed(item.uploadSpeed));
                h.detailDownloaded.setText(formatBytes(item.downloadedBytes) + " / " + formatBytes(item.totalBytes));
                h.detailRemaining.setText(calculateRemaining(item));
                h.detailMagnet.setText(item.movie.magnetLink);
                h.detailPath.setText(item.savePath != null ? item.savePath : "");
            }

            h.pauseButton.setOnClickListener(v -> downloadManager.pauseDownload(item.movie.magnetLink));
            h.resumeButton.setOnClickListener(v -> downloadManager.resumeDownload(item.movie.magnetLink));
            h.deleteButton.setOnClickListener(v -> downloadManager.removeDownload(item.movie.magnetLink));
            h.playButton.setOnClickListener(v -> playMovie(item));
            h.expandButton.setOnClickListener(v -> {
                boolean newState = !isExpanded;
                expandedMap.put(key, newState);
                notifyItemChanged(position);
            });
        }

        private String formatBytes(long bytes) {
            if (bytes <= 0) return "0 B";
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024f);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024f * 1024));
            return String.format("%.1f GB", bytes / (1024f * 1024 * 1024));
        }

        private String formatSpeed(long bytesPerSecond) {
            if (bytesPerSecond <= 0) return "0 B/s";
            if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
            if (bytesPerSecond < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSecond / 1024f);
            return String.format("%.1f MB/s", bytesPerSecond / (1024f * 1024));
        }

        private String calculateRemaining(DownloadItem item) {
            if (item.totalBytes <= 0 || item.downloadedBytes <= 0) return "计算中...";
            long remaining = item.totalBytes - item.downloadedBytes;
            int speed = item.speed != null && !item.speed.isEmpty() ? parseSpeed(item.speed) : 0;
            if (speed <= 0) return "计算中...";
            long seconds = remaining / speed;
            if (seconds < 60) return seconds + " 秒";
            if (seconds < 3600) return (seconds / 60) + " 分钟";
            return (seconds / 3600) + " 小时";
        }

        private int parseSpeed(String speedStr) {
            try {
                if (speedStr.contains("MB/s")) {
                    return (int) (Float.parseFloat(speedStr.replace("MB/s", "").trim()) * 1024 * 1024);
                }
                if (speedStr.contains("KB/s")) {
                    return (int) (Float.parseFloat(speedStr.replace("KB/s", "").trim()) * 1024);
                }
                if (speedStr.contains("B/s")) {
                    return Integer.parseInt(speedStr.replace("B/s", "").trim());
                }
            } catch (Exception e) {
            }
            return 0;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView size;
            final TextView status;
            final ProgressBar progressBar;
            final TextView progressText;
            final TextView speed;
            final ImageView playButton;
            final ImageView pauseButton;
            final ImageView resumeButton;
            final ImageView deleteButton;
            final ImageView expandButton;
            final View detailPanel;
            final TextView detailSeeds;
            final TextView detailPeers;
            final TextView detailDownSpeed;
            final TextView detailUpSpeed;
            final TextView detailDownloaded;
            final TextView detailRemaining;
            final TextView detailMagnet;
            final TextView detailPath;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.downloadTitle);
                size = v.findViewById(R.id.downloadSize);
                status = v.findViewById(R.id.downloadStatus);
                progressBar = v.findViewById(R.id.downloadProgress);
                progressText = v.findViewById(R.id.progressText);
                speed = v.findViewById(R.id.downloadSpeed);
                playButton = v.findViewById(R.id.btnPlayMovie);
                pauseButton = v.findViewById(R.id.btnPause);
                resumeButton = v.findViewById(R.id.btnResume);
                deleteButton = v.findViewById(R.id.btnDelete);
                expandButton = v.findViewById(R.id.btnExpand);
                detailPanel = v.findViewById(R.id.detailPanel);
                detailSeeds = v.findViewById(R.id.detailSeeds);
                detailPeers = v.findViewById(R.id.detailPeers);
                detailDownSpeed = v.findViewById(R.id.detailDownSpeed);
                detailUpSpeed = v.findViewById(R.id.detailUpSpeed);
                detailDownloaded = v.findViewById(R.id.detailDownloaded);
                detailRemaining = v.findViewById(R.id.detailRemaining);
                detailMagnet = v.findViewById(R.id.detailMagnet);
                detailPath = v.findViewById(R.id.detailPath);
            }
        }
    }
}
