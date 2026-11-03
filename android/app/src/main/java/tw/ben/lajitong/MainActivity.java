package tw.ben.lajitong;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;

/**
 * 主畫面：用 WebView 顯示 assets/index.html（就是專案資料夾裡的「垃圾車來了.html」）。
 * 網頁透過 window.LJTApp 叫 App 做事：背景追蹤、語音、震動、省電設定。
 */
public class MainActivity extends Activity {
    static volatile boolean visible = false;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final int REQ_LOC = 1, REQ_NOTIF = 2;

    private WebView web;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        TrackerService.createChannels(this);

        web = new WebView(this);
        setContentView(web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);          // localStorage：存我家、提醒點等設定
        ws.setGeolocationEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setTextZoom(100);                     // 字的大小交給網頁自己的「大／特大」

        // 用 https 網址載入 App 內的檔案，網頁才能定位、存設定、連資料平台
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return assetResponse(r.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true; // 外部連結用瀏覽器開
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (hasLocation()) { cb.invoke(origin, true, false); return; }
                geoCallback = cb; geoOrigin = origin;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            }
        });
        web.addJavascriptInterface(new Bridge(), "LJTApp");
        if (saved != null) web.restoreState(saved);
        else web.loadUrl(START_URL);
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_LOC && geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocation(), false);
            geoCallback = null;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        web.onResume();
        TrackerService.cancelAlarms(this); // 打開 App 就停止響鈴
    }

    @Override protected void onPause() {
        visible = false;
        web.onPause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        // 先讓網頁處理（關鬧鐘、縮小地圖、回「我家」）；網頁沒處理就把 App 放到背景，追蹤繼續
        web.evaluateJavascript("window.ljtBack?ljtBack():false", v -> { if (!"true".equals(v)) moveTaskToBack(true); });
    }

    /** 網頁裡用 window.LJTApp.xxx() 呼叫 */
    class Bridge {
        @JavascriptInterface public void start(String cfgJson) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                TrackerService.start(MainActivity.this, cfgJson);
            });
        }
        @JavascriptInterface public void update(String cfgJson) { TrackerService.update(MainActivity.this, cfgJson); }
        @JavascriptInterface public void stop() { TrackerService.stop(MainActivity.this); }
        @JavascriptInterface public boolean isTracking() { return TrackerService.running; }
        @JavascriptInterface public void markFired(String key) { TrackerService.markFired(MainActivity.this, key); }
        @JavascriptInterface public String firedKeys() { return new JSONArray(TrackerService.firedKeys(MainActivity.this)).toString(); }
        @JavascriptInterface public void speak(String text) { TrackerService.speak(MainActivity.this, text); }
        @JavascriptInterface public void vibrate() {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            long[] p = {0, 600, 300, 600, 300, 600};
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(p, -1)); else v.vibrate(p, -1);
        }
        @SuppressLint("BatteryLife")
        @JavascriptInterface public void openBatterySettings() {
            runOnUiThread(() -> {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                try {
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()))
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                    else startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                }
            });
        }
    }

    /** 把 https://appassets.androidplatform.net/assets/xxx 對應到 App 裡的 assets/xxx（不用 androidx.webkit） */
    private WebResourceResponse assetResponse(Uri u) {
        if (!"appassets.androidplatform.net".equals(u.getHost())) return null;
        String path = u.getPath();
        if (path == null || !path.startsWith("/assets/")) return null;
        String name = path.substring("/assets/".length());
        if (name.isEmpty() || name.contains("..")) return null;
        try {
            InputStream in = getAssets().open(name);
            String mime = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "application/javascript"
                    : name.endsWith(".css") ? "text/css" : name.endsWith(".png") ? "image/png"
                    : name.endsWith(".json") || name.endsWith(".webmanifest") ? "application/json" : "application/octet-stream";
            WebResourceResponse res = new WebResourceResponse(mime, "UTF-8", in);
            Map<String, String> h = new HashMap<>(); h.put("Cache-Control", "no-cache"); res.setResponseHeaders(h);
            return res;
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
        }
    }
}
