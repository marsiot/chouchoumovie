package chouchou.movie;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

public class MainActivity extends AppCompatActivity {

    private static final String TAG_BROWSER = "browser";
    private static final String TAG_DOWNLOADS = "downloads";

    private BrowserFragment browserFragment;
    private DownloadsFragment downloadsFragment;
    private Fragment activeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Warm up the BT session early so the first download has it ready.
        BtDownloader.getInstance(this);

        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            browserFragment = new BrowserFragment();
            downloadsFragment = new DownloadsFragment();
            fm.beginTransaction()
                    .add(R.id.fragmentContainer, downloadsFragment, TAG_DOWNLOADS)
                    .hide(downloadsFragment)
                    .add(R.id.fragmentContainer, browserFragment, TAG_BROWSER)
                    .commit();
            activeFragment = browserFragment;
        } else {
            browserFragment = (BrowserFragment) fm.findFragmentByTag(TAG_BROWSER);
            downloadsFragment = (DownloadsFragment) fm.findFragmentByTag(TAG_DOWNLOADS);
            activeFragment = (browserFragment != null && !browserFragment.isHidden())
                    ? browserFragment
                    : downloadsFragment;
        }

        findViewById(R.id.tabHome).setOnClickListener(v -> switchTo(browserFragment));
        findViewById(R.id.tabDownloads).setOnClickListener(v -> switchTo(downloadsFragment));

        refreshTabStyles();
    }

    private void switchTo(Fragment f) {
        if (f == null || f == activeFragment) return;
        getSupportFragmentManager().beginTransaction()
                .hide(activeFragment)
                .show(f)
                .commit();
        activeFragment = f;
        refreshTabStyles();
    }

    private void refreshTabStyles() {
        boolean home = activeFragment == browserFragment;

        ImageView homeIcon = findViewById(R.id.tabHomeIcon);
        TextView homeLabel = findViewById(R.id.tabHomeLabel);
        ImageView dlIcon = findViewById(R.id.tabDownloadsIcon);
        TextView dlLabel = findViewById(R.id.tabDownloadsLabel);

        int active = ContextCompat.getColor(this, R.color.accent);
        int idle = ContextCompat.getColor(this, R.color.text_secondary);

        homeIcon.setColorFilter(home ? active : idle);
        homeLabel.setTextColor(home ? active : idle);
        dlIcon.setColorFilter(home ? idle : active);
        dlLabel.setTextColor(home ? idle : active);
    }

    @Override
    public void onBackPressed() {
        if (activeFragment == browserFragment
                && browserFragment != null
                && browserFragment.handleBackPressed()) {
            return;
        }
        if (activeFragment == downloadsFragment) {
            switchTo(browserFragment);
            return;
        }
        super.onBackPressed();
    }
}
