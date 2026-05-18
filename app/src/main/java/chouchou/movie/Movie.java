package chouchou.movie;

import android.os.Parcel;
import android.os.Parcelable;

public class Movie implements Parcelable {
    public final String title;
    public final String description;
    public final String magnetLink;
    public final String posterUrl;
    public final String rating;
    public final String sourceUrl;
    public final long size;

    public Movie(String title, String description, String magnetLink, 
                 String posterUrl, String rating, String sourceUrl, long size) {
        this.title = title;
        this.description = description;
        this.magnetLink = magnetLink;
        this.posterUrl = posterUrl;
        this.rating = rating;
        this.sourceUrl = sourceUrl;
        this.size = size;
    }

    protected Movie(Parcel in) {
        title = in.readString();
        description = in.readString();
        magnetLink = in.readString();
        posterUrl = in.readString();
        rating = in.readString();
        sourceUrl = in.readString();
        size = in.readLong();
    }

    public static final Creator<Movie> CREATOR = new Creator<Movie>() {
        @Override
        public Movie createFromParcel(Parcel in) {
            return new Movie(in);
        }

        @Override
        public Movie[] newArray(int size) {
            return new Movie[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(title);
        dest.writeString(description);
        dest.writeString(magnetLink);
        dest.writeString(posterUrl);
        dest.writeString(rating);
        dest.writeString(sourceUrl);
        dest.writeLong(size);
    }

    public String getSizeFormatted() {
        if (size <= 0) return "";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024f);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024f * 1024));
        return String.format("%.1f GB", size / (1024f * 1024 * 1024));
    }
}