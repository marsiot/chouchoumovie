package chouchou.movie;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static final int MAX_CONCURRENT_DOWNLOADS = 3;
    private static final String PREFS_NAME = "chouchou_downloads";
    private static final String KEY_DOWNLOADS = "downloads";

    private static DownloadManager instance;

    private final Context context;
    private final OkHttpClient client;
    private static final ConcurrentHashMap<String, DownloadItem> downloads = new ConcurrentHashMap<>();
    private static int activeDownloads = 0;
    private OnDownloadListener listener;

    public static synchronized DownloadManager getInstance(Context context) {
        if (instance == null) {
            instance = new DownloadManager(context.getApplicationContext());
        }
        return instance;
    }

    public interface OnDownloadListener {
        void onDownloadAdded(DownloadItem item);
        void onDownloadProgress(DownloadItem item);
        void onDownloadCompleted(DownloadItem item);
        void onDownloadFailed(DownloadItem item, String error);
        void onDownloadPaused(DownloadItem item);
        void onDownloadResumed(DownloadItem item);
        void onDownloadRemoved(DownloadItem item);
    }

    public DownloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.client = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        loadDownloads();
    }

    private void loadDownloads() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jsonString = prefs.getString(KEY_DOWNLOADS, "[]");
        try {
            JSONArray array = new JSONArray(jsonString);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                DownloadItem item = DownloadItem.fromJson(obj);
                downloads.put(item.movie.magnetLink, item);
                Log.d(TAG, "Loaded download: " + item.movie.title + ", status: " + item.status);
            }
            Log.d(TAG, "Loaded " + downloads.size() + " downloads");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to load downloads", e);
        }
    }

    public void resumeAllDownloads() {
        for (DownloadItem item : downloads.values()) {
            if (item.status == DownloadItem.Status.DOWNLOADING && item.movie.magnetLink.startsWith("magnet:")) {
                item.speed = "连接中...";
                BtDownloader btDownloader = BtDownloader.getInstance(context);
                btDownloader.setListener(item.movie.magnetLink, new BtDownloader.OnDownloadListener() {
                    @Override
                    public void onProgress(String magnetLink, int progress, long downloadedBytes, long totalBytes, String speed, int seeds, int peers, long uploadSpeed) {
                        item.progress = progress;
                        item.downloadedBytes = downloadedBytes;
                        item.speed = speed;
                        item.totalBytes = totalBytes;
                        item.seeds = seeds;
                        item.peers = peers;
                        item.uploadSpeed = uploadSpeed;
                        saveDownloads();
                        if (listener != null) {
                            listener.onDownloadProgress(item);
                        }
                    }

                    @Override
                    public void onCompleted(String magnetLink, String savePath) {
                        item.status = DownloadItem.Status.COMPLETED;
                        item.progress = 100;
                        item.savePath = savePath;
                        item.speed = "";
                        saveDownloads();
                        if (listener != null) {
                            listener.onDownloadCompleted(item);
                        }
                    }

                    @Override
                    public void onFailed(String magnetLink, String error) {
                        item.status = DownloadItem.Status.FAILED;
                        saveDownloads();
                        if (listener != null) {
                            listener.onDownloadFailed(item, error);
                        }
                    }

                    @Override
                    public void onMetadataReceived(String magnetLink, String name, long size) {
                        Log.d(TAG, "onMetadataReceived: " + name + ", size: " + size);
                        if (item.movie.title.equals("未知电影") || item.movie.title.startsWith("电影_")) {
                            item.movie = new Movie(name, item.movie.description, item.movie.magnetLink,
                                                   item.movie.posterUrl, item.movie.rating,
                                                   item.movie.sourceUrl, size);
                            downloads.put(magnetLink, item);
                            Log.d(TAG, "Updated movie name to: " + name);
                        }
                        saveDownloads();
                        if (listener != null) {
                            listener.onDownloadProgress(item);
                        }
                    }
                });
                
                String resumePath = item.savePath;
                if (resumePath == null || resumePath.isEmpty()) {
                    resumePath = null;
                }
                btDownloader.addTorrent(item.movie.magnetLink, resumePath);
                Log.d(TAG, "Resumed download: " + item.movie.title + ", path: " + resumePath);
            }
        }
    }

    private void saveDownloads() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        try {
            JSONArray array = new JSONArray();
            for (DownloadItem item : downloads.values()) {
                array.put(item.toJson());
            }
            editor.putString(KEY_DOWNLOADS, array.toString());
            editor.apply();
            Log.d(TAG, "Saved " + downloads.size() + " downloads");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save downloads", e);
        }
    }

    public void setOnDownloadListener(OnDownloadListener listener) {
        this.listener = listener;
    }

    public void addDownload(Movie movie) {
        String key = movie.magnetLink;
        if (downloads.containsKey(key)) {
            Toast.makeText(context, "该电影已在下载列表中", Toast.LENGTH_SHORT).show();
            return;
        }

        DownloadItem item = new DownloadItem(movie);
        
        if (movie.magnetLink.startsWith("magnet:")) {
            handleMagnetLink(item);
        } else {
            downloads.put(key, item);
            if (listener != null) {
                listener.onDownloadAdded(item);
            }
            startDownloadIfAvailable(item);
        }
    }

    private void handleMagnetLink(DownloadItem item) {
        item.status = DownloadItem.Status.DOWNLOADING;
        item.progress = 0;
        item.speed = "连接中...";
        downloads.put(item.movie.magnetLink, item);
        saveDownloads();
        
        if (listener != null) {
            listener.onDownloadAdded(item);
        }
        
        BtDownloader btDownloader = BtDownloader.getInstance(context);
        btDownloader.setListener(item.movie.magnetLink, new BtDownloader.OnDownloadListener() {
            @Override
            public void onProgress(String magnetLink, int progress, long downloadedBytes, long totalBytes, String speed, int seeds, int peers, long uploadSpeed) {
                item.progress = progress;
                item.downloadedBytes = downloadedBytes;
                item.speed = speed;
                item.totalBytes = totalBytes;
                item.seeds = seeds;
                item.peers = peers;
                item.uploadSpeed = uploadSpeed;
                saveDownloads();
                if (listener != null) {
                    listener.onDownloadProgress(item);
                }
            }

            @Override
            public void onCompleted(String magnetLink, String savePath) {
                item.status = DownloadItem.Status.COMPLETED;
                item.progress = 100;
                item.savePath = savePath;
                item.speed = "";
                saveDownloads();
                if (listener != null) {
                    listener.onDownloadCompleted(item);
                }
            }

            @Override
            public void onFailed(String magnetLink, String error) {
                item.status = DownloadItem.Status.FAILED;
                saveDownloads();
                if (listener != null) {
                    listener.onDownloadFailed(item, error);
                }
            }

            @Override
            public void onMetadataReceived(String magnetLink, String name, long size) {
                Log.d(TAG, "onMetadataReceived: " + name + ", size: " + size);
                if (item.movie.title.equals("未知电影") || item.movie.title.startsWith("电影_")) {
                    item.movie = new Movie(name, item.movie.description, item.movie.magnetLink,
                                           item.movie.posterUrl, item.movie.rating,
                                           item.movie.sourceUrl, size);
                    downloads.put(magnetLink, item);
                    Log.d(TAG, "Updated movie name to: " + name);
                }
                saveDownloads();
                if (listener != null) {
                    listener.onDownloadProgress(item);
                }
            }
        });

        btDownloader.addTorrent(item.movie.magnetLink);
    }

    private synchronized void startDownloadIfAvailable(DownloadItem item) {
        if (activeDownloads >= MAX_CONCURRENT_DOWNLOADS) {
            item.status = DownloadItem.Status.WAITING;
            if (listener != null) {
                listener.onDownloadAdded(item);
            }
            return;
        }

        activeDownloads++;
        item.status = DownloadItem.Status.DOWNLOADING;
        
        if (listener != null) {
            listener.onDownloadAdded(item);
        }

        new DownloadTask(item).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void pauseDownload(String magnetLink) {
        DownloadItem item = downloads.get(magnetLink);
        if (item != null && item.status == DownloadItem.Status.DOWNLOADING) {
            item.status = DownloadItem.Status.PAUSED;
            item.speed = "";
            if (item.movie.magnetLink.startsWith("magnet:")) {
                BtDownloader.getInstance(context).pauseTorrent(magnetLink);
            }
            saveDownloads();
            if (listener != null) {
                listener.onDownloadPaused(item);
            }
        }
    }

    public void resumeDownload(String magnetLink) {
        DownloadItem item = downloads.get(magnetLink);
        if (item != null && item.status == DownloadItem.Status.PAUSED) {
            item.status = DownloadItem.Status.DOWNLOADING;
            item.speed = "连接中...";
            if (item.movie.magnetLink.startsWith("magnet:")) {
                BtDownloader.getInstance(context).resumeTorrent(magnetLink);
            } else {
                startDownloadIfAvailable(item);
            }
            saveDownloads();
            if (listener != null) {
                listener.onDownloadResumed(item);
            }
        }
    }

    public void removeDownload(String magnetLink) {
        DownloadItem item = downloads.remove(magnetLink);
        if (item != null) {
            if (item.movie.magnetLink.startsWith("magnet:")) {
                BtDownloader.getInstance(context).removeTorrent(magnetLink);
            }
            saveDownloads();
            if (listener != null) {
                listener.onDownloadRemoved(item);
            }
        }
    }

    public DownloadItem getDownload(String magnetLink) {
        return downloads.get(magnetLink);
    }

    public List<DownloadItem> getAllDownloads() {
        return new ArrayList<>(downloads.values());
    }

    public List<DownloadItem> getDownloadsByStatus(DownloadItem.Status status) {
        List<DownloadItem> result = new ArrayList<>();
        for (DownloadItem item : downloads.values()) {
            if (item.status == status) {
                result.add(item);
            }
        }
        return result;
    }

    private String getDownloadPath() {
        File downloadDir = new File(Environment.getExternalStorageDirectory(), "Movies/Chouchou");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        return downloadDir.getAbsolutePath();
    }

    private class DownloadTask extends AsyncTask<Void, Integer, Boolean> {
        private final DownloadItem item;
        private String error;

        DownloadTask(DownloadItem item) {
            this.item = item;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            String url = item.movie.magnetLink;
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    error = "HTTP error: " + response.code();
                    return false;
                }

                long contentLength = response.body().contentLength();
                String fileName = item.movie.title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5.-]", "_") + ".mp4";
                String savePath = getDownloadPath() + "/" + fileName;
                
                item.savePath = savePath;

                try (InputStream inputStream = response.body().byteStream();
                     FileOutputStream outputStream = new FileOutputStream(savePath)) {

                    byte[] buffer = new byte[8192];
                    long totalRead = 0;
                    int bytesRead;

                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        if (item.status == DownloadItem.Status.PAUSED) {
                            return null;
                        }

                        outputStream.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        item.downloadedBytes = totalRead;

                        if (contentLength > 0) {
                            int progress = (int) ((totalRead * 100) / contentLength);
                            item.progress = progress;
                            publishProgress(progress);
                        }

                        long speed = totalRead / 1024;
                        item.speed = speed + " KB/s";
                    }

                    item.progress = 100;
                    item.status = DownloadItem.Status.COMPLETED;
                    return true;

                } catch (IOException e) {
                    error = "Write file failed: " + e.getMessage();
                    return false;
                }

            } catch (IOException e) {
                error = "Network error: " + e.getMessage();
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            saveDownloads();
            if (listener != null) {
                listener.onDownloadProgress(item);
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            activeDownloads--;
            
            if (success == null) {
                item.status = DownloadItem.Status.PAUSED;
                saveDownloads();
                if (listener != null) {
                    listener.onDownloadPaused(item);
                }
            } else if (success) {
                saveDownloads();
                if (listener != null) {
                    listener.onDownloadCompleted(item);
                }
            } else {
                item.status = DownloadItem.Status.FAILED;
                saveDownloads();
                if (listener != null) {
                    listener.onDownloadFailed(item, error);
                }
            }

            startNextWaitingDownload();
        }
    }

    private synchronized void startNextWaitingDownload() {
        for (DownloadItem item : downloads.values()) {
            if (item.status == DownloadItem.Status.WAITING) {
                startDownloadIfAvailable(item);
                break;
            }
        }
    }
}