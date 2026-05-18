package chouchou.movie;

import android.os.Parcel;
import android.os.Parcelable;

import org.json.JSONException;
import org.json.JSONObject;

public class DownloadItem implements Parcelable {
    public enum Status {
        WAITING, DOWNLOADING, PAUSED, COMPLETED, FAILED
    }

    public Movie movie;
    public String savePath;
    public int progress;
    public String speed;
    public long downloadedBytes;
    public Status status;
    
    public int seeds;
    public int peers;
    public long uploadSpeed;
    public long totalBytes;

    public DownloadItem(Movie movie) {
        this.movie = movie;
        this.savePath = "";
        this.progress = 0;
        this.speed = "";
        this.downloadedBytes = 0;
        this.status = Status.WAITING;
        this.seeds = 0;
        this.peers = 0;
        this.uploadSpeed = 0;
        this.totalBytes = 0;
    }

    protected DownloadItem(Parcel in) {
        movie = in.readParcelable(Movie.class.getClassLoader());
        savePath = in.readString();
        progress = in.readInt();
        speed = in.readString();
        downloadedBytes = in.readLong();
        status = Status.valueOf(in.readString());
        seeds = in.readInt();
        peers = in.readInt();
        uploadSpeed = in.readLong();
        totalBytes = in.readLong();
    }

    public static final Creator<DownloadItem> CREATOR = new Creator<DownloadItem>() {
        @Override
        public DownloadItem createFromParcel(Parcel in) {
            return new DownloadItem(in);
        }

        @Override
        public DownloadItem[] newArray(int size) {
            return new DownloadItem[size];
        }
    };

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("title", movie.title);
        obj.put("description", movie.description);
        obj.put("magnetLink", movie.magnetLink);
        obj.put("posterUrl", movie.posterUrl);
        obj.put("rating", movie.rating);
        obj.put("sourceUrl", movie.sourceUrl);
        obj.put("size", movie.size);
        obj.put("savePath", savePath != null ? savePath : "");
        obj.put("progress", progress);
        obj.put("speed", speed != null ? speed : "");
        obj.put("downloadedBytes", downloadedBytes);
        obj.put("status", status.name());
        obj.put("seeds", seeds);
        obj.put("peers", peers);
        obj.put("uploadSpeed", uploadSpeed);
        obj.put("totalBytes", totalBytes);
        return obj;
    }

    public static DownloadItem fromJson(JSONObject obj) throws JSONException {
        Movie movie = new Movie(
                obj.getString("title"),
                obj.getString("description"),
                obj.getString("magnetLink"),
                obj.getString("posterUrl"),
                obj.getString("rating"),
                obj.getString("sourceUrl"),
                obj.getLong("size")
        );
        DownloadItem item = new DownloadItem(movie);
        item.savePath = obj.getString("savePath");
        item.progress = obj.getInt("progress");
        item.speed = obj.getString("speed");
        item.downloadedBytes = obj.getLong("downloadedBytes");
        item.status = Status.valueOf(obj.getString("status"));
        item.seeds = obj.optInt("seeds", 0);
        item.peers = obj.optInt("peers", 0);
        item.uploadSpeed = obj.optLong("uploadSpeed", 0);
        item.totalBytes = obj.optLong("totalBytes", 0);
        return item;
    }

    public String getProgressText() {
        return progress + "%";
    }

    public String getDownloadedFormatted() {
        if (downloadedBytes <= 0) return "";
        if (downloadedBytes < 1024) return downloadedBytes + " B";
        if (downloadedBytes < 1024 * 1024) return String.format("%.1f KB", downloadedBytes / 1024f);
        if (downloadedBytes < 1024 * 1024 * 1024) return String.format("%.1f MB", downloadedBytes / (1024f * 1024));
        return String.format("%.1f GB", downloadedBytes / (1024f * 1024 * 1024));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(movie, flags);
        dest.writeString(savePath);
        dest.writeInt(progress);
        dest.writeString(speed);
        dest.writeLong(downloadedBytes);
        dest.writeString(status.name());
        dest.writeInt(seeds);
        dest.writeInt(peers);
        dest.writeLong(uploadSpeed);
        dest.writeLong(totalBytes);
    }
}