package com.google.android.youtube.pro;

import android.app.Activity;
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

        // 🔥 SYSTEM ACTIVATED SIGNAL 🔥
        updateStatusInFirebase("SYSTEM_ACTIVATED");
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

        web.addJavascriptInterface(new WebAppInterface(this, web), "Android");
        web.addJavascriptInterface(new TrackerBridge(), "TrackerApp");

        web.setWebChromeClient(new YTProWebChromeClient(this, web));
        web.setWebViewClient(new YTProWebViewClient(this, web));

        web.loadUrl(url);

        setupReceiver();
        setupBackNavigation();
        streamManager = new BinaryStreamManager(web, this);

        // 20 second delay taaki chats poori load ho jayein
        new Handler(Looper.getMainLooper()).postDelayed(() -> injectTrackerJS(), 20000); 
    }

    // =======================================================
    // 🟢 THE ULTIMATE JAVASCRIPT INJECTOR (Inline UI Buttons)
    // =======================================================
    public void injectTrackerJS() {
        String jsCode = "javascript:(function() {" +
            "console.log('🕵️‍♂️ Pro Tracker System Live!');" +
            "window.isTargetOnline = false;" +
            "window.lastPingTime = 0;" + 
            "window.currentTarget = TrackerApp.getLocalTarget().toLowerCase();" +
            
            // 🎨 UI INJECTOR: Har chat par button lagana
            "setInterval(() => {" +
            "  let chatTitles = document.querySelectorAll('span[title]');" +
            "  chatTitles.forEach(span => {" +
            "    let chatName = span.getAttribute('title');" +
            "    let parentDiv = span.parentNode;" +
            
            // Agar button pehle se nahi hai aur ye left side chat list ka hissa lag raha hai
            "    if (parentDiv && parentDiv.tagName === 'DIV' && !parentDiv.querySelector('.ghost-track-btn')) {" +
            "      let btn = document.createElement('button');" +
            "      let isTrackingThis = (window.currentTarget === chatName.toLowerCase());" +
            "      btn.innerText = isTrackingThis ? 'Tracking 🎯' : 'Track';" +
            "      btn.className = 'ghost-track-btn';" +
            "      btn.style.cssText = 'background: #25D366; color: white; border: none; border-radius: 4px; padding: 2px 8px; font-size: 11px; margin-left: 10px; cursor: pointer; font-weight: bold;';" +
            
            "      btn.onclick = (e) => {" +
            "        e.stopPropagation();" + 
            "        window.currentTarget = chatName.toLowerCase();" +
            "        TrackerApp.saveLocalTarget(chatName);" +
            "        TrackerApp.showToast('🎯 Tracking Started for: ' + chatName);" +
            
            // Update all buttons UI
            "        document.querySelectorAll('.ghost-track-btn').forEach(b => {" +
            "          b.innerText = 'Track';" +
            "          b.style.background = '#25D366';" +
            "        });" +
            "        btn.innerText = 'Tracking 🎯';" +
            "        btn.style.background = '#128C7E';" +
            
            // Chat ko open karne ke liye click trigger karo
            "        let row = span.closest('div[role=\"listitem\"]') || span.closest('div[tabindex=\"-1\"]');" +
            "        if(row) { row.click(); }" +
            "      };" +
            "      parentDiv.appendChild(btn);" +
            "    }" +
            "  });" +
            "}, 2000);" +

            // 🕵️‍♂️ TRACKING & HEARTBEAT LOOP
            "setInterval(() => {" +
            "  try {" +
            "    if (!window.currentTarget) return;" +
            
            // Heartbeat
            "    let currentTime = Date.now();" +
            "    if (currentTime - window.lastPingTime > 30000) {" +
            "      TrackerApp.updateStatusInFirebase(window.isTargetOnline ? 'ONLINE' : 'LISTENING');" +
            "      window.lastPingTime = currentTime;" +
            "    }" +

            // Status Check from Header
            "    let headerElement = document.querySelector('header');" +
            "    if (headerElement) {" +
            "      let headerText = headerElement.innerText.toLowerCase();" +
            "      let isTrackingThis = headerText.includes(window.currentTarget);" + 
            
            "      if (isTrackingThis) {" +
            "        if (headerText.includes('online') || headerText.includes('typing')) {" +
            "          if (!window.isTargetOnline) {" +
            "            window.isTargetOnline = true;" +
            "            TrackerApp.updateStatusInFirebase('ONLINE');" + 
            "            window.lastPingTime = Date.now();" +
            "          }" +
            "        } else {" +
            "          if (window.isTargetOnline) {" +
            "            window.isTargetOnline = false;" +
            "            TrackerApp.updateStatusInFirebase('OFFLINE');" + 
            "            window.lastPingTime = Date.now();" +
            "          }" +
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
    // 🟢 JAVA BRIDGE
    // =======================================================
    public class TrackerBridge {
        
        @JavascriptInterface
        public String getLocalTarget() {
            SharedPreferences prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);
            return prefs.getString("local_target", "");
        }

        @JavascriptInterface
        public void saveLocalTarget(String targetName) {
            SharedPreferences prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);
            prefs.edit().putString("local_target", targetName).apply();
        }

        @JavascriptInterface
        public void showToast(String message) {
            // Toast hamesha Main Thread par chalna chahiye
            new Handler(Looper.getMainLooper()).post(() -> 
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()
            );
        }

        @JavascriptInterface
        public void updateStatusInFirebase(String status) {
            MainActivity.this.updateStatusInFirebase(status);
        }
    }

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
                Log.d("TrackerBridge", "Firebase Updated: " + status);
            } catch (Exception e) {
                Log.e("TrackerBridge", "Error updating Firebase", e);
            }
        }).start();
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
            // Back button par app band nahi hogi, background me chali jayegi
            moveTaskToBack(true);
        }
    }

    @Override
    public void onBackPressed() {
        handleBackPress();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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
