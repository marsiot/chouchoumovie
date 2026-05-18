package chouchou.movie;

import android.os.AsyncTask;
import android.util.Log;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HtmlParser {
    private static final String TAG = "HtmlParser";
    private static final Pattern MAGNET_PATTERN = Pattern.compile("magnet:\\?xt=urn:btih:[a-zA-Z0-9]+[^\\s\"'<>]*");
    private static final Pattern MAGNET_PATTERN_HTML = Pattern.compile("magnet:&amp;xt=urn:btih:[a-zA-Z0-9]+[^\\s\"'<>]*");
    private static final Pattern SIZE_PATTERN = Pattern.compile("(\\d+(\\.\\d+)?)\\s*(GB|MB|KB)");

    public interface OnParseListener {
        void onSuccess(List<Movie> movies);
        void onError(String error);
    }

    private final OkHttpClient client;

    public HtmlParser() {
        this.client = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    public void parseUrls(List<String> urls, OnParseListener listener) {
        new ParseTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, urls.toArray(new String[0]));
    }

    private class ParseTask extends AsyncTask<String, Void, List<Movie>> {
        private final OnParseListener listener;
        private String lastError;

        ParseTask(OnParseListener listener) {
            this.listener = listener;
        }

        @Override
        protected List<Movie> doInBackground(String... urls) {
            List<Movie> allMovies = new ArrayList<>();
            for (String url : urls) {
                try {
                    List<Movie> movies = parseUrl(url);
                    allMovies.addAll(movies);
                } catch (Exception e) {
                    Log.e(TAG, "Parse URL failed: " + url, e);
                    lastError = e.getMessage();
                }
            }
            return allMovies;
        }

        @Override
        protected void onPostExecute(List<Movie> movies) {
            if (movies != null && !movies.isEmpty()) {
                listener.onSuccess(movies);
            } else if (lastError != null) {
                listener.onError(lastError);
            } else {
                listener.onSuccess(new ArrayList<>());
            }
        }
    }

    private List<Movie> parseUrl(String url) throws IOException {
        List<Movie> movies = new ArrayList<>();
        Log.d(TAG, "开始解析网址: " + url);
        
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            Log.d(TAG, "HTTP响应码: " + response.code());
            if (!response.isSuccessful()) {
                throw new IOException("HTTP error code: " + response.code());
            }
            
            String html = response.body().string();
            Log.d(TAG, "HTML内容长度: " + html.length() + " 字符");
            
            Document doc = Jsoup.parse(html);
            
            List<String> movieUrls = extractMovieUrls(doc, url);
            Log.d(TAG, "找到电影详情链接: " + movieUrls.size() + " 个");
            
            int maxPages = 5;
            for (int i = 0; i < Math.min(maxPages, movieUrls.size()); i++) {
                try {
                    movies.addAll(parseMovieDetail(movieUrls.get(i)));
                } catch (Exception e) {
                    Log.w(TAG, "解析电影页面失败: " + movieUrls.get(i), e);
                }
            }
            
            List<Movie> items = parseMovieItems(doc, url);
            List<Movie> magnets = parseMagnetLinksFromText(html, url);
            
            Log.d(TAG, "解析到电影条目: " + items.size() + " 条");
            Log.d(TAG, "解析到磁力链接: " + magnets.size() + " 条");
            
            movies.addAll(items);
            movies.addAll(magnets);
        } catch (Exception e) {
            Log.e(TAG, "解析失败: " + e.getMessage(), e);
            throw e;
        }
        
        Log.d(TAG, "总共解析到: " + movies.size() + " 部电影");
        return movies;
    }
    
    private List<String> extractMovieUrls(Document doc, String baseUrl) {
        List<String> urls = new ArrayList<>();
        
        Elements links = doc.select("a[href*='movie'], a[href*='film'], a[href*='play']");
        for (Element link : links) {
            String href = link.attr("href");
            if (href != null && !href.isEmpty()) {
                if (href.startsWith("//")) {
                    href = "https:" + href;
                } else if (!href.startsWith("http")) {
                    try {
                        java.net.URL base = new java.net.URL(baseUrl);
                        if (href.startsWith("/")) {
                            href = base.getProtocol() + "://" + base.getHost() + href;
                        } else {
                            href = base.getProtocol() + "://" + base.getHost() + "/" + href;
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
                
                if (href.matches(".*/movie/\\d+\\.html$")) {
                    if (!urls.contains(href)) {
                        urls.add(href);
                    }
                }
            }
        }
        
        return urls;
    }
    
    private List<Movie> parseMovieDetail(String url) throws IOException {
        List<Movie> movies = new ArrayList<>();
        Log.d(TAG, "解析电影详情页: " + url);
        
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.d(TAG, "HTTP失败: " + response.code());
                return movies;
            }
            
            String html = response.body().string();
            Log.d(TAG, "详情页HTML长度: " + html.length());
            
            if (html.contains("magnet:")) {
                Log.d(TAG, "页面包含magnet链接!");
            }
            
            Document doc = Jsoup.parse(html);
            
            Elements playLinks = doc.select("a[href*='play'], a[href*='down'], a[onclick*='play']");
            Log.d(TAG, "找到播放/下载链接: " + playLinks.size());
            for (int i = 0; i < Math.min(3, playLinks.size()); i++) {
                Element link = playLinks.get(i);
                Log.d(TAG, "播放链接 " + i + ": " + link.attr("href") + " | " + link.text());
            }
            
            String title = extractTitle(doc.body());
            String description = extractDescription(doc.body());
            Log.d(TAG, "标题: " + title);
            
            Matcher magnetMatcher = MAGNET_PATTERN.matcher(html);
            while (magnetMatcher.find()) {
                String magnetLink = magnetMatcher.group();
                Log.d(TAG, "找到磁力链接: " + magnetLink);
                String movieTitle = title;
                if (movieTitle == null || movieTitle.isEmpty()) {
                    movieTitle = extractTitleFromMagnet(magnetLink);
                }
                if (movieTitle == null || movieTitle.isEmpty()) {
                    movieTitle = "未知电影";
                }
                movies.add(new Movie(movieTitle, description, magnetLink, "", "", url, 0));
            }
            
            Matcher htmlMatcher = MAGNET_PATTERN_HTML.matcher(html);
            while (htmlMatcher.find()) {
                String magnetLink = htmlMatcher.group().replace("&amp;", "&");
                Log.d(TAG, "找到HTML磁力链接: " + magnetLink);
                String movieTitle = title;
                if (movieTitle == null || movieTitle.isEmpty()) {
                    movieTitle = extractTitleFromMagnet(magnetLink);
                }
                if (movieTitle == null || movieTitle.isEmpty()) {
                    movieTitle = "未知电影";
                }
                movies.add(new Movie(movieTitle, description, magnetLink, "", "", url, 0));
            }
            
            Log.d(TAG, "从详情页解析到: " + movies.size() + " 部电影");
        }
        
        return movies;
    }

    private List<Movie> parseMovieItems(Document doc, String sourceUrl) {
        List<Movie> movies = new ArrayList<>();
        
        Elements items = doc.select(".movie-item, .item, .film-item, .video-item, article, .list-item, .list li, .grid-item, .card, .post, .entry, tr");
        
        for (Element item : items) {
            try {
                String title = extractTitle(item);
                String description = extractDescription(item);
                String magnetLink = extractMagnetLink(item);
                String posterUrl = extractPosterUrl(item, sourceUrl);
                String rating = extractRating(item);
                long size = extractSize(item);

                if (magnetLink != null && magnetLink.startsWith("magnet:")) {
                    if (title == null || title.isEmpty()) {
                        title = extractTitleFromMagnet(magnetLink);
                    }
                    movies.add(new Movie(title, description, magnetLink, posterUrl, rating, sourceUrl, size));
                }
            } catch (Exception e) {
                Log.d(TAG, "Parse item failed", e);
            }
        }
        
        return movies;
    }

    private List<Movie> parseMagnetLinksFromText(String html, String sourceUrl) {
        List<Movie> movies = new ArrayList<>();
        
        Matcher magnetMatcher = MAGNET_PATTERN.matcher(html);
        while (magnetMatcher.find()) {
            String magnetLink = magnetMatcher.group();
            String title = extractTitleFromMagnet(magnetLink);
            if (title == null || title.isEmpty()) {
                title = "未知电影";
            }
            movies.add(new Movie(title, "", magnetLink, "", "", sourceUrl, 0));
        }
        
        Matcher htmlMatcher = MAGNET_PATTERN_HTML.matcher(html);
        while (htmlMatcher.find()) {
            String magnetLink = htmlMatcher.group().replace("&amp;", "&");
            String title = extractTitleFromMagnet(magnetLink);
            if (title == null || title.isEmpty()) {
                title = "未知电影";
            }
            movies.add(new Movie(title, "", magnetLink, "", "", sourceUrl, 0));
        }
        
        return movies;
    }

    private String extractTitle(Element item) {
        Element titleEl = item.selectFirst("h2, h3, .title, .name, .film-title");
        String title = titleEl != null ? titleEl.text() : null;
        if (title == null || title.isEmpty()) {
            Element aEl = item.selectFirst("a");
            title = aEl != null ? aEl.text() : null;
        }
        return title;
    }

    private String extractDescription(Element item) {
        Element descEl = item.selectFirst(".desc, .description, .summary, .intro");
        return descEl != null ? descEl.text() : null;
    }

    private String extractMagnetLink(Element item) {
        Element link = item.selectFirst("a[href^=magnet:]");
        if (link != null) {
            return link.attr("href");
        }
        
        String text = item.text();
        Matcher matcher = MAGNET_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private String extractPosterUrl(Element item, String sourceUrl) {
        Element img = item.selectFirst("img");
        if (img != null) {
            String src = img.attr("src");
            if (src != null && !src.isEmpty()) {
                if (src.startsWith("//")) {
                    src = "https:" + src;
                } else if (!src.startsWith("http")) {
                    try {
                        java.net.URL baseUrl = new java.net.URL(sourceUrl);
                        src = baseUrl.getProtocol() + "://" + baseUrl.getHost() + 
                              (src.startsWith("/") ? "" : "/") + src;
                    } catch (Exception e) {
                        // ignore
                    }
                }
                return src;
            }
        }
        return "";
    }

    private String extractRating(Element item) {
        Element rating = item.selectFirst(".rating, .score, .imdb");
        if (rating != null) {
            String text = rating.text();
            Matcher matcher = Pattern.compile("(\\d+(\\.\\d+)?)").matcher(text);
            return matcher.find() ? matcher.group() : null;
        }
        return "";
    }

    private long extractSize(Element item) {
        String text = item.text();
        Matcher matcher = SIZE_PATTERN.matcher(text);
        if (matcher.find()) {
            double num = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(3).toUpperCase();
            switch (unit) {
                case "GB": return (long) (num * 1024 * 1024 * 1024);
                case "MB": return (long) (num * 1024 * 1024);
                case "KB": return (long) (num * 1024);
            }
        }
        return 0;
    }

    private String extractTitleFromMagnet(String magnetLink) {
        String[] parts = magnetLink.split("&");
        for (String part : parts) {
            if (part.startsWith("dn=")) {
                try {
                    return java.net.URLDecoder.decode(part.substring(3), "UTF-8");
                } catch (Exception e) {
                    return part.substring(3).replace("+", " ");
                }
            }
        }
        return null;
    }
}