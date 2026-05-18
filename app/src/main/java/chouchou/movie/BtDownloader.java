package chouchou.movie;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.libtorrent4j.AlertListener;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.AddTorrentAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BtDownloader {
    private static final String TAG = "BtDownloader";
    private static final Pattern HASH_PATTERN =
            Pattern.compile("xt=urn:btih:([a-fA-F0-9]{40})");

    private static BtDownloader instance;

    private final Context context;
    private final SessionManager session;
    private final ScheduledExecutorService poller;
    private final ExecutorService addExecutor;

    // magnet -> listener
    private final ConcurrentHashMap<String, OnDownloadListener> listeners = new ConcurrentHashMap<>();
    // infoHash (hex, lower-case) -> magnet
    private final ConcurrentHashMap<String, String> hashToMagnet = new ConcurrentHashMap<>();
    // infoHash (hex, lower-case) -> handle
    private final ConcurrentHashMap<String, TorrentHandle> handles = new ConcurrentHashMap<>();
    // infoHash (hex, lower-case) -> whether metadata callback already fired
    private final ConcurrentHashMap<String, Boolean> metadataFired = new ConcurrentHashMap<>();
    // infoHash (hex, lower-case) -> whether finished callback already fired
    private final ConcurrentHashMap<String, Boolean> finishedFired = new ConcurrentHashMap<>();
    // Track handles that caused crashes to avoid repeated crashes
    private final java.util.Set<String> crashedHandles = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public interface OnDownloadListener {
        void onProgress(String magnetLink, int progress, long downloadedBytes, long totalBytes, String speed, int seeds, int peers, long uploadSpeed);
        void onCompleted(String magnetLink, String savePath);
        void onFailed(String magnetLink, String error);
        void onMetadataReceived(String magnetLink, String name, long size);
    }

    private BtDownloader(Context context) {
        this.context = context.getApplicationContext();
        this.session = new SessionManager();
        this.session.addListener(new SessionAlertListener());
        
        Log.d(TAG, "Initializing SessionManager with optimizations...");
        
        configureSession();
        
        try {
            this.session.start();
            Log.d(TAG, "SessionManager started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SessionManager", e);
        }

        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bt-poller");
            t.setDaemon(true);
            return t;
        });
        this.poller.scheduleAtFixedRate(this::poll, 1, 5, TimeUnit.SECONDS);

        this.addExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "bt-add");
            t.setDaemon(true);
            return t;
        });
    }

    private void configureSession() {
        Log.d(TAG, "Configuring session with optimizations...");
        
        try {
            java.lang.reflect.Method applySettings = session.getClass().getMethod("applySettings", Object.class);
            Object settings = Class.forName("org.libtorrent4j.settings_pack").newInstance();
            
            java.lang.reflect.Method setInt = settings.getClass().getMethod("set_int", int.class, int.class);
            java.lang.reflect.Method setBool = settings.getClass().getMethod("set_bool", int.class, boolean.class);
            
            Object intTypes = Class.forName("org.libtorrent4j.settings_pack$int_types").getDeclaredField("SWIG_INSTANCE").get(null);
            Object boolTypes = Class.forName("org.libtorrent4j.settings_pack$bool_types").getDeclaredField("SWIG_INSTANCE").get(null);
            
            setInt.invoke(settings, intTypes.getClass().getMethod("max_uploads").invoke(intTypes), 15);
            setInt.invoke(settings, intTypes.getClass().getMethod("max_connections").invoke(intTypes), 100);
            setInt.invoke(settings, intTypes.getClass().getMethod("max_half_open_connections").invoke(intTypes), 50);
            setInt.invoke(settings, intTypes.getClass().getMethod("download_rate_limit").invoke(intTypes), -1);
            setInt.invoke(settings, intTypes.getClass().getMethod("upload_rate_limit").invoke(intTypes), -1);
            setBool.invoke(settings, boolTypes.getClass().getMethod("enable_dht").invoke(boolTypes), true);
            setBool.invoke(settings, boolTypes.getClass().getMethod("enable_lsd").invoke(boolTypes), true);
            setBool.invoke(settings, boolTypes.getClass().getMethod("enable_upnp").invoke(boolTypes), true);
            setBool.invoke(settings, boolTypes.getClass().getMethod("enable_natpmp").invoke(boolTypes), true);
            
            applySettings.invoke(session, settings);
            Log.d(TAG, "Session settings applied via reflection");
        } catch (Exception e) {
            Log.w(TAG, "Failed to apply session settings", e);
        }
        
        Log.d(TAG, "Session configured with optimized settings");
    }

    public static synchronized BtDownloader getInstance(Context context) {
        if (instance == null) {
            instance = new BtDownloader(context);
        }
        return instance;
    }

    public void setListener(String magnetLink, OnDownloadListener listener) {
        if (magnetLink == null) return;
        if (listener == null) {
            listeners.remove(magnetLink);
        } else {
            listeners.put(magnetLink, listener);
        }
    }

    public void addTorrent(String magnetLink) {
        addTorrent(magnetLink, null);
    }

    public void addTorrent(String magnetLink, String resumePath) {
        if (magnetLink == null) return;
        String hash = extractHash(magnetLink);
        if (hash == null) {
            OnDownloadListener l = listeners.get(magnetLink);
            if (l != null) l.onFailed(magnetLink, "无法识别磁力链接");
            return;
        }
        hashToMagnet.put(hash, magnetLink);
        Log.d(TAG, "Adding torrent: " + hash + ", resumePath: " + resumePath);

        addExecutor.execute(() -> {
            File saveDir = getSaveDir();
            Log.d(TAG, "Save directory: " + saveDir.getAbsolutePath());
            try {
                TorrentInfo ti = null;
                
                File torrentFile = new File(saveDir, hash + ".torrent");
                if (torrentFile.exists()) {
                    Log.d(TAG, "Found existing torrent file: " + torrentFile.getAbsolutePath());
                    try {
                        java.io.FileInputStream fis = new java.io.FileInputStream(torrentFile);
                        byte[] data = new byte[(int) torrentFile.length()];
                        fis.read(data);
                        fis.close();
                        ti = TorrentInfo.bdecode(data);
                        Log.d(TAG, "Loaded torrent from file: " + ti.name());
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to load torrent file", e);
                    }
                }
                
                if (ti == null) {
                    Log.d(TAG, "Starting fetchMagnet for hash: " + hash);
                    byte[] data = session.fetchMagnet(magnetLink, 120, saveDir);
                    if (data == null) {
                        Log.w(TAG, "fetchMagnet returned null for hash: " + hash);
                        OnDownloadListener l = listeners.get(magnetLink);
                        if (l != null) l.onFailed(magnetLink, "无法获取种子元数据，请检查网络连接或稍后重试");
                        return;
                    }
                    Log.d(TAG, "Successfully fetched metadata for hash: " + hash + ", size: " + data.length);
                    ti = TorrentInfo.bdecode(data);
                    
                    try {
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(torrentFile);
                        fos.write(data);
                        fos.close();
                        Log.d(TAG, "Saved torrent file: " + torrentFile.getAbsolutePath());
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to save torrent file", e);
                    }
                }
                
                Log.d(TAG, "Torrent name: " + ti.name() + ", size: " + ti.totalSize());
                session.download(ti, saveDir);
                Log.d(TAG, "Torrent added to session: " + hash);
                
                TorrentHandle h = session.find(ti.infoHash());
                if (h != null) {
                    try {
                        h.status();
                        handles.put(hash, h);
                        Log.d(TAG, "Successfully found torrent handle: " + hash);
                        
                        if (resumePath != null && !resumePath.isEmpty()) {
                            try {
                                h.resume();
                                Log.d(TAG, "Resumed existing download: " + hash);
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to resume torrent", e);
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to validate torrent handle for hash: " + hash, t);
                    }
                } else {
                    Log.w(TAG, "Failed to find torrent handle for hash: " + hash);
                }
            } catch (Throwable t) {
                Log.e(TAG, "addTorrent failed for hash: " + hash, t);
                OnDownloadListener l = listeners.get(magnetLink);
                if (l != null) l.onFailed(magnetLink, "添加种子失败: " + t.getMessage());
            }
        });
    }

    public void pauseTorrent(String magnetLink) {
        TorrentHandle h = handleForMagnet(magnetLink);
        if (h != null) {
            try {
                h.pause();
            } catch (Throwable t) {
                Log.w(TAG, "pauseTorrent failed", t);
            }
        }
    }

    public void resumeTorrent(String magnetLink) {
        TorrentHandle h = handleForMagnet(magnetLink);
        if (h != null) {
            try {
                h.resume();
            } catch (Throwable t) {
                Log.w(TAG, "resumeTorrent failed", t);
            }
        }
    }

    public void removeTorrent(String magnetLink) {
        Log.d(TAG, "removeTorrent called for: " + magnetLink);
        if (magnetLink == null) return;
        String hash = extractHash(magnetLink);
        listeners.remove(magnetLink);
        if (hash == null) return;
        hashToMagnet.remove(hash);
        metadataFired.remove(hash);
        finishedFired.remove(hash);
        TorrentHandle h = handles.remove(hash);
        Log.d(TAG, "Removed torrent handle for hash: " + hash + ", was present: " + (h != null));
        if (h != null) {
            try {
                session.remove(h);
            } catch (Throwable t) {
                Log.w(TAG, "remove failed", t);
            }
        }
    }

    public boolean isRunning() {
        return session.isRunning();
    }

    public void shutdown() {
        try { poller.shutdownNow(); } catch (Throwable ignored) {}
        try { addExecutor.shutdownNow(); } catch (Throwable ignored) {}
        try { session.stop(); } catch (Throwable ignored) {}
    }

    private TorrentHandle handleForMagnet(String magnetLink) {
        String hash = extractHash(magnetLink);
        return hash == null ? null : handles.get(hash);
    }

    private void poll() {
        try {
            Log.d(TAG, "Polling " + handles.size() + " torrents");
            java.util.List<String> toRemove = new java.util.ArrayList<>();
            
            for (Map.Entry<String, TorrentHandle> e : new java.util.HashMap<>(handles).entrySet()) {
                String hash = e.getKey();
                TorrentHandle h = e.getValue();
                
                if (h == null) {
                    toRemove.add(hash);
                    continue;
                }

                if (crashedHandles.contains(hash)) {
                    Log.w(TAG, "Skipping previously crashed handle for hash: " + hash);
                    continue;
                }

                String magnet = hashToMagnet.get(hash);
                OnDownloadListener l = magnet == null ? null : listeners.get(magnet);
                
                if (Boolean.TRUE.equals(finishedFired.get(hash))) continue;

                TorrentStatus st = safeGetStatus(h);
                if (st == null) {
                    Log.w(TAG, "TorrentStatus is null for hash: " + hash);
                    crashedHandles.add(hash);
                    toRemove.add(hash);
                    continue;
                }

                long total = 0, downloaded = 0;
                int downRate = 0, upRate = 0, progressPct = 0, numSeeds = 0, numPeers = 0;
                String name = "";
                boolean isFinished = false;
                TorrentStatus.State state = null;

                try {
                    total = st.total();
                    downloaded = st.totalDone();
                    downRate = st.downloadRate();
                    upRate = st.uploadRate();
                    progressPct = Math.round(st.progress() * 100f);
                    numSeeds = st.numSeeds();
                    numPeers = st.numPeers();
                    name = st.name();
                    isFinished = st.isFinished();
                    state = st.state();
                } catch (Throwable ex) {
                    Log.w(TAG, "Failed to get torrent status fields for hash: " + hash, ex);
                    continue;
                }

                if (total > 0 && !Boolean.TRUE.equals(metadataFired.get(hash))) {
                    metadataFired.put(hash, true);
                    if (name != null && !name.isEmpty()) {
                        try {
                            l.onMetadataReceived(magnet, name, total);
                        } catch (Exception ex) {
                            Log.w(TAG, "onMetadataReceived callback failed", ex);
                        }
                    }
                }

                try {
                    l.onProgress(magnet, progressPct, downloaded, total, formatSpeed(downRate), numSeeds, numPeers, upRate);
                } catch (Exception ex) {
                    Log.w(TAG, "onProgress callback failed for hash: " + hash, ex);
                }

                if (isFinished || (state != null && state == TorrentStatus.State.SEEDING)) {
                    finishedFired.put(hash, true);
                    String savePath = "";
                    try {
                        String sp = safeGetSavePath(h);
                        File saved = new File(sp, name);
                        savePath = saved.getAbsolutePath();
                    } catch (Throwable ex) {
                        Log.w(TAG, "Failed to get save path for hash: " + hash, ex);
                    }
                    try {
                        l.onCompleted(magnet, savePath);
                    } catch (Exception ex) {
                        Log.w(TAG, "onCompleted callback failed for hash: " + hash, ex);
                    }
                }
            }
            
            for (String hash : toRemove) {
                handles.remove(hash);
                Log.d(TAG, "Removed invalid handle for hash: " + hash);
            }
        } catch (Throwable t) {
            Log.e(TAG, "poll error", t);
        }
    }

    private TorrentStatus safeGetStatus(TorrentHandle h) {
        if (h == null) return null;
        try {
            return h.status();
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "JNI link error in safeGetStatus", e);
            return null;
        } catch (NoSuchMethodError e) {
            Log.w(TAG, "No such method error in safeGetStatus", e);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "Unexpected error in safeGetStatus", t);
            return null;
        }
    }

    private String safeGetSavePath(TorrentHandle h) {
        if (h == null) return "";
        try {
            return h.savePath();
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "JNI link error in safeGetSavePath", e);
            return "";
        } catch (Throwable t) {
            Log.w(TAG, "Unexpected error in safeGetSavePath", t);
            return "";
        }
    }

    private class SessionAlertListener implements AlertListener {
        @Override
        public int[] types() {
            return new int[]{
                    AlertType.ADD_TORRENT.swig(),
                    AlertType.TORRENT_FINISHED.swig(),
                    AlertType.TORRENT_ERROR.swig()
            };
        }

        @Override
        public void alert(Alert<?> alert) {
            try {
                if (alert.type() == AlertType.ADD_TORRENT) {
                    AddTorrentAlert a = (AddTorrentAlert) alert;
                    TorrentHandle h = a.handle();
                    if (h == null) {
                        Log.w(TAG, "ADD_TORRENT alert with null handle");
                        return;
                    }
                    
                    boolean isValid = false;
                    try {
                        h.status();
                        isValid = true;
                    } catch (Exception e) {
                        Log.w(TAG, "handle validation failed in ADD_TORRENT", e);
                        return;
                    }
                    
                    if (!isValid) {
                        Log.w(TAG, "ADD_TORRENT alert with invalid handle");
                        return;
                    }
                    
                    String hash = h.infoHash().toHex().toLowerCase();
                    handles.put(hash, h);
                    Log.d(TAG, "Torrent handle added: " + hash);
                } else if (alert.type() == AlertType.TORRENT_FINISHED) {
                    TorrentFinishedAlert a = (TorrentFinishedAlert) alert;
                    TorrentHandle h = a.handle();
                    if (h == null) return;
                    
                    String hash = null;
                    try {
                        hash = h.infoHash().toHex().toLowerCase();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to get infoHash in TORRENT_FINISHED", e);
                        return;
                    }
                    
                    if (Boolean.TRUE.equals(finishedFired.get(hash))) return;
                    finishedFired.put(hash, true);
                    String magnet = hashToMagnet.get(hash);
                    OnDownloadListener l = magnet == null ? null : listeners.get(magnet);
                    if (l != null) {
                        TorrentStatus st = h.status();
                        File saved = new File(h.savePath(), st.name());
                        l.onCompleted(magnet, saved.getAbsolutePath());
                    }
                } else if (alert.type() == AlertType.TORRENT_ERROR) {
                    TorrentErrorAlert a = (TorrentErrorAlert) alert;
                    TorrentHandle h = a.handle();
                    if (h == null) return;
                    
                    String hash = null;
                    try {
                        hash = h.infoHash().toHex().toLowerCase();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to get infoHash in TORRENT_ERROR", e);
                        return;
                    }
                    
                    String magnet = hashToMagnet.get(hash);
                    OnDownloadListener l = magnet == null ? null : listeners.get(magnet);
                    if (l != null) l.onFailed(magnet, a.message());
                }
            } catch (Throwable t) {
                Log.e(TAG, "alert handler failed", t);
            }
        }
    }

    private File getSaveDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), "ChouchouMovie");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String extractHash(String magnetLink) {
        if (magnetLink == null) return null;
        Matcher m = HASH_PATTERN.matcher(magnetLink);
        if (m.find()) return m.group(1).toLowerCase();
        return null;
    }

    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) return "0 B/s";
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0));
    }
}
