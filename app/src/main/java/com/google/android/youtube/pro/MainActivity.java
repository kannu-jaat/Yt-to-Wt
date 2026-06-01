package com.google.android.youtube.pro;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.google.android.youtube.pro.webview.YTProWebView;
import com.google.android.youtube.pro.webview.YTProWebViewClient;
import com.google.android.youtube.pro.webview.YTProWebChromeClient;
import com.google.android.youtube.pro.webview.WebAppInterface;
import com.google.android.youtube.pro.webview.BinaryStreamManager;
import com.google.android.youtube.pro.receivers.MediaCommandReceiver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    // 🔥 APNI FIREBASE DETAILS YAHAN DAALEIN 🔥
    public static final String FIREBASE_URL = "https://whatsapp-web-03-default-rtdb.firebaseio.com"; 

    public boolean portrait = false;
    public boolean isPlaying = false;
    public boolean mediaSession = false;
    public boolean isPip = false;
    public boolean dL = false;

    private YTProWebView web;
    private MediaCommandReceiver broadcastReceiver;
    private OnBackInvokedCallback backCallback;
    public BinaryStreamManager streamManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        SharedPreferences prefs = getSharedPreferences("YTPRO", MODE_PRIVATE);
        if (!prefs.contains("bgplay")) {
            prefs.edit().putBoolean("bgplay", true).apply();
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        load(false);
    }

    public void load(boolean dl) {
        this.dL = dl;
        web = findViewById(R.id.web);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setSupportZoom(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false); 
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 🔥 WA WEB KE LIYE DESKTOP USER-AGENT ZAROORI HAI 🔥
        String desktopUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
        web.getSettings().setUserAgentString(desktopUserAgent);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(web, true);
            web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        Intent intent = getIntent();
        String action = intent.getAction();
        Uri data = intent.getData();
        String url = "https://web.whatsapp.com/"; 

        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            url = data.toString();
        }

        // Add Existing WebAppInterface
        web.addJavascriptInterface(new WebAppInterface(this, web), "Android");
        
        // 🔥 NEW: Add TrackerBridge Interface 🔥
        web.addJavascriptInterface(new TrackerBridge(), "TrackerApp");

        web.setWebChromeClient(new YTProWebChromeClient(this, web));
        web.setWebViewClient(new YTProWebViewClient(this, web));

        web.loadUrl(url);

        setupReceiver();
        setupBackNavigation();
        streamManager = new BinaryStreamManager(web, this);

        // 🔥 WA Web load hone ke baad JS Tracker Inject karein (15 sec delay) 🔥
        new Handler(Looper.getMainLooper()).postDelayed(() -> injectTrackerJS(), 15000); 
    }

    // =======================================================
    // 🟢 THE JAVASCRIPT INJECTOR (Reads & Writes to Firebase via Bridge)
    // =======================================================
    public void injectTrackerJS() {
        String jsCode = "javascript:(function() {" +
            "console.log('🕵️‍♂️ APK Tracker Script Injected Successfully!');" +
            "window.isTargetOnline = false;" +
            "setInterval(() => {" +
            "  try {" +
            "    let targetsStr = TrackerApp.getTargetsFromFirebase();" + 
            "    if (!targetsStr || targetsStr === 'null') return;" +
            "    let headerElement = document.querySelector('header');" +
            "    if (headerElement) {" +
            "      let headerText = headerElement.innerText.toLowerCase();" +
            "      let isTrackingThis = targetsStr.includes('track');" + // Aapka target logic
            "      if (isTrackingThis && (headerText.includes('online') || headerText.includes('typing'))) {" +
            "        if (!window.isTargetOnline) {" +
            "          window.isTargetOnline = true;" +
            "          TrackerApp.updateStatusInFirebase('ONLINE');" + 
            "        }" +
            "      } else if (isTrackingThis && !headerText.includes('online') && !headerText.includes('typing')) {" +
            "        if (window.isTargetOnline) {" +
            "          window.isTargetOnline = false;" +
            "          TrackerApp.updateStatusInFirebase('OFFLINE');" + 
            "        }" +
            "      }" +
            "    }" +
            "  } catch(e) { console.log('Tracker Error:', e); }" +
            "}, 3000);" +
            "})()";

        if (web != null) {
            web.evaluateJavascript(jsCode, null);
        }
    }

    // =======================================================
    // 🟢 JAVA BRIDGE (JAVASCRIPT <-> FIREBASE)
    // =======================================================
    public class TrackerBridge {
        
        // JS call karega Firebase se targets padhne ke liye
        @JavascriptInterface
        public String getTargetsFromFirebase() {
            final String[] result = {"null"};
            Thread thread = new Thread(() -> {
                try {
                    URL url = new URL(FIREBASE_URL + "/tracking_targets.json");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder json = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        json.append(line);
                    }
                    reader.close();
                    result[0] = json.toString();
                } catch (Exception e) {
                    Log.e("TrackerBridge", "Error reading Firebase", e);
                }
            });
            thread.start();
            try { thread.join(); } catch (InterruptedException e) { e.printStackTrace(); }
            return result[0];
        }

        // JS call karega Firebase me status write (update) karne ke liye
        @JavascriptInterface
        public void updateStatusInFirebase(String status) {
            new Thread(() -> {
                try {
                    URL url = new URL(FIREBASE_URL + "/live_status.json");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("PUT"); 
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    
                    String jsonBody = "{\"state\": \"" + status + "\", \"timestamp\": " + System.currentTimeMillis() + "}";
                    
                    OutputStream os = conn.getOutputStream();
                    os.write(jsonBody.getBytes());
                    os.flush();
                    os.close();
                    
                    conn.getResponseCode(); 
                    conn.disconnect();
                    Log.d("TrackerBridge", "Status updated in Firebase: " + status);
                } catch (Exception e) {
                    Log.e("TrackerBridge", "Error updating Firebase", e);
                }
            }).start();
        }
    }
    // =======================================================

    private void setupReceiver() {
        broadcastReceiver = new MediaCommandReceiver(web);
        if (Build.VERSION.SDK_INT >= 34 && getApplicationInfo().targetSdkVersion >= 34) {
            registerReceiver(broadcastReceiver, new IntentFilter("TRACKS_TRACKS"), RECEIVER_EXPORTED);
        } else {
            registerReceiver(broadcastReceiver, new IntentFilter("TRACKS_TRACKS"));
        }
    }

    private void setupBackNavigation() {
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
            backCallback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    handleBackPress();
                }
            };
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    private void handleBackPress() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackPress();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                web.loadUrl("https://web.whatsapp.com/");
            } else {
                Toast.makeText(getApplicationContext(), getString(R.string.grant_mic), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(getApplicationContext(), getString(R.string.grant_storage), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        isPip = isInPictureInPictureMode;
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopService(new Intent(getApplicationContext(), ForegroundService.class));
        if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (streamManager != null) {
            streamManager.cleanup();
        }
    }
}
