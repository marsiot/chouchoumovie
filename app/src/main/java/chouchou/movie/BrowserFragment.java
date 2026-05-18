package chouchou.movie;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BrowserFragment extends Fragment {

    private WebView webView;
    private EditText urlInput;
    private ProgressBar loadingBar;
    private DownloadManager downloadManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_browser, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        downloadManager = DownloadManager.getInstance(requireContext());
        webView = view.findViewById(R.id.webView);
        urlInput = view.findViewById(R.id.urlInput);
        loadingBar = view.findViewById(R.id.loadingBar);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            private boolean handleUrl(String url) {
                if (url.startsWith("magnet:")) {
                    handleMagnetLink(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (loadingBar != null) loadingBar.setVisibility(View.VISIBLE);
                if (urlInput != null && !urlInput.hasFocus()) urlInput.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (loadingBar != null) loadingBar.setVisibility(View.GONE);
                if (urlInput != null && !urlInput.hasFocus()) urlInput.setText(url);
            }
        });

        webView.setDownloadListener((url, ua, disp, mime, len) -> {
            if (url.startsWith("magnet:")) handleMagnetLink(url);
        });

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void downloadMagnet(String url) {
                if (url != null && url.startsWith("magnet:")) handleMagnetLink(url);
            }
        }, "ChouchouMovie");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (loadingBar != null) loadingBar.setProgress(newProgress);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView popup = new WebView(requireContext());
                WebSettings popupSettings = popup.getSettings();
                popupSettings.setJavaScriptEnabled(true);
                popupSettings.setUserAgentString(settings.getUserAgentString());
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        return handlePopupUrl(v, request.getUrl().toString());
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        return handlePopupUrl(v, url);
                    }

                    private boolean handlePopupUrl(WebView v, String url) {
                        if (url.startsWith("magnet:")) {
                            handleMagnetLink(url);
                        } else {
                            webView.loadUrl(url);
                        }
                        v.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });

        ImageView btnBack = view.findViewById(R.id.btnBack);
        ImageView btnRefresh = view.findViewById(R.id.btnRefresh);
        ImageView btnSettings = view.findViewById(R.id.btnSettings);

        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        btnRefresh.setOnClickListener(v -> webView.reload());
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SettingsActivity.class)));

        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadFromInput();
                return true;
            }
            return false;
        });

        loadDefaultUrl();
    }

    private void loadFromInput() {
        String text = urlInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        String url = normalize(text);
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        urlInput.clearFocus();
        webView.loadUrl(url);
    }

    private String normalize(String s) {
        if (s.startsWith("magnet:")) return s;
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        // No scheme: if it looks like a domain, prepend https; else search.
        if (s.contains(" ") || !s.contains(".")) {
            return "https://www.google.com/search?q=" + Uri.encode(s);
        }
        return "https://" + s;
    }

    private void loadDefaultUrl() {
        SharedPreferences sp = Providers.prefs(requireContext());
        String url = Providers.getParserUrls(sp).get(0);
        if (!url.startsWith("http")) url = "https://" + url;
        webView.loadUrl(url);
    }

    public boolean handleBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    private void handleMagnetLink(String magnetLink) {
        android.util.Log.d("BrowserFragment", "handleMagnetLink called with: " + magnetLink);
        String title = extractTitleFromMagnet(magnetLink);
        if (title == null || title.isEmpty()) title = "未知电影";
        android.util.Log.d("BrowserFragment", "Extracted title: " + title);

        Movie movie = new Movie(title, "", magnetLink, "", "", "", 0);
        downloadManager.addDownload(movie);

        Toast.makeText(requireContext(), "已添加到下载列表: " + title, Toast.LENGTH_SHORT).show();
    }

    private String extractTitleFromMagnet(String magnetLink) {
        String[] parts = magnetLink.split("&");
        for (String part : parts) {
            if (part.startsWith("dn=")) {
                try {
                    return Uri.decode(part.substring(3));
                } catch (Exception e) {
                    return part.substring(3).replace("+", " ");
                }
            }
        }
        Matcher matcher = Pattern.compile("magnet:\\?xt=urn:btih:([a-fA-F0-9]{40})").matcher(magnetLink);
        if (matcher.find()) {
            return "电影_" + matcher.group(1).substring(0, 8).toUpperCase();
        }
        return null;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    public void onDestroyView() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(new WebViewClient());
            webView.setWebChromeClient(new WebChromeClient());
            webView.destroy();
            webView = null;
        }
        super.onDestroyView();
    }
}
