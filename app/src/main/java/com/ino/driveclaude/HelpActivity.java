package com.ino.driveclaude;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/** Geminiを有料枠でほぼ無料に使う方法を、図解つきで見せる画面。 */
public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0D1117);
        getWindow().setNavigationBarColor(0xFF0D1117);

        WebView wv = new WebView(this);
        wv.setBackgroundColor(0xFF0D1117);
        WebSettings st = wv.getSettings();
        st.setJavaScriptEnabled(false);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(false);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl()));
                } catch (Exception ignored) { }
                return true;
            }
        });
        wv.loadDataWithBaseURL(null, buildHtml(), "text/html", "utf-8", null);
        setContentView(wv);
    }

    private String buildHtml() {
        Prefs p = new Prefs(this);
        int count = p.searchCount();
        double pct = Math.min(100.0, count * 100.0 / Prefs.SEARCH_FREE_PER_MONTH);
        String page = getIntent().getStringExtra("page");
        String file = "gemini".equals(page) || page == null ? "gemini_billing_help.html" : "guide_" + page + ".html";
        try (InputStream in = getAssets().open(file)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8")
                    .replace("{{COUNT}}", String.format(Locale.JAPAN, "%,d", count))
                    .replace("{{PCT}}", String.format(Locale.US, "%.1f", pct))
                    .replace("{{MONTH}}", p.searchMonth());
        } catch (Exception e) {
            return "<html><body style='background:#0d1117;color:#e6edf3'>ガイドを読み込めませんでした。</body></html>";
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
