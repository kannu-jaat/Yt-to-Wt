package com.google.android.youtube.pro;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

        // 🔥 1. SYSTEM ACTIVATED SIGNAL ON STARTUP 🔥
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

        // 🔥 2. PROGRAMMATIC CONTROL PANEL (UI Injection) 🔥
        createControlPanel();

        new Handler(Looper.getMainLooper()).postDelayed(() -> injectTrackerJS(), 15000); 
    }

    // =======================================================
    // 🟢 NEW: APP KE ANDAR CONTROL PANEL BANANA
    // =======================================================
    private void createControlPanel() {
        ViewGroup rootView = (ViewGroup) findViewById(android.R.id.content);
        
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setBackgroundColor(Color.parseColor("#DD111111")); // Dark theme Bar
        panel.setPadding(10, 10, 10, 10);
        panel.setGravity(Gravity.CENTER_VERTICAL);

        EditText inputTarget = new EditText(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        inputTarget.setLayoutParams(ip);
        inputTarget.setHint("Target Name");
        inputTarget.setHintTextColor(Color.GRAY);
        inputTarget.setTextColor(Color.WHITE);
        
        // Puraana saved target load karo
        SharedPreferences localPrefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);
        inputTarget.setText(localPrefs.getString("local_target", ""));

        Button btnTrack = new Button(this);
        btnTrack.setText("Track");
        btnTrack.setBackgroundColor(Color.parseColor("#075E54")); // WhatsApp Green
        btnTrack.setTextColor(Color.WHITE);
        btnTrack.setOnClickListener(v -> {
            String name = inputTarget.getText().toString().trim();
            if(!name.isEmpty()) {
                localPrefs.edit().putString("local_target", name).apply();
                Toast.makeText(this, "Tracking Target Saved: " + name, Toast.LENGTH_SHORT).show();
            }
        });

        Button btnMin = new Button(this);
        btnMin.setText("MIN");
        btnMin.setBackgroundColor(Color.parseColor("#333333"));
        btnMin.setTextColor(Color.WHITE);
        btnMin.setOnClickListener(v -> {
            moveTaskToBack(true); // 🔥 APP MINIMIZE BUTTON LUCK 🔥
        });

        panel.addView(inputTarget);
        panel.addView(btnTrack);
        panel.addView(btnMin);

        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.gravity = Gravity.TOP; // Screen ke upar chipka do
        rootView.addView(panel, fp);
    }

    // =======================================================
    // 🟢 JAVASCRIPT INJECTOR (Reads local target & sends Heartbeat)
    // =======================================================
    public void injectTrackerJS() {
        String jsCode = "javascript:(function() {" +
            "console.log('🕵️‍♂️ APK Tracker Script Injected Successfully!');" +
            "window.isTargetOnline = false;" +
            "window.lastPingTime = 0;" + 
            
            "setInterval(() => {" +
            "  try {" +
            "    let targetNameOrNumber = TrackerApp.getLocalTarget().toLowerCase();" + // Local Memory se uthao
            "    if (!targetNameOrNumber) return;" +
            
            // 📡 HEARTBEAT PING (Har 30 sec me batayega app chalu hai)
            "    let currentTime = Date.now();" +
            "    if (currentTime - window.lastPingTime > 30000) {" +
            "      TrackerApp.updateStatusInFirebase(window.isTargetOnline ? 'ONLINE' : 'LISTENING');" +
            "      window.lastPingTime = currentTime;" +
            "    }" +

            // 🕵️‍♂️ TRACKING LOGIC
            "    let headerElement = document.querySelector('header');" +
            "    if (headerElement) {" +
            "      let headerText = headerElement.innerText.toLowerCase();" +
            "      let isTrackingThis = headerText.includes(targetNameOrNumber);" + 
            
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
    // 🟢 JAVA BRIDGE (Bina Network load ke memory access)
    // =======================================================
    public class TrackerBridge {
        
        @JavascriptInterface
        public String getLocalTarget() {
            SharedPreferences prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);
            return prefs.getString("local_target", "");
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
