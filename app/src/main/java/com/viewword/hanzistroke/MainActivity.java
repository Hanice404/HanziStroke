package com.viewword.hanzistroke;

import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ProgressBar progressBar;
    private AndroidVoiceBridge voiceBridge;
    private long lastBackPressTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        initWebView();

        // 注册现代化返回键拦截器，确保在全面屏手势/实体返回键下均能拦截
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleAppBackPress();
            }
        });
    }

    private void initWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        voiceBridge = new AndroidVoiceBridge(this, webView);
        webView.addJavascriptInterface(voiceBridge, "AndroidVoiceBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // 自动授予网页音频录制权限
                runOnUiThread(() -> {
                    for (String res : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                            return;
                        }
                    }
                    request.grant(request.getResources());
                });
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AndroidVoiceBridge.REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (voiceBridge != null) {
                    voiceBridge.startRecordingInternal();
                }
            } else {
                if (voiceBridge != null) {
                    voiceBridge.callJs("window.showCustomToast && window.showCustomToast('需要麦克风权限才能录音查字', 'error');");
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        handleAppBackPress();
    }

    private void handleAppBackPress() {
        if (webView != null) {
            webView.evaluateJavascript(
                "(function(){\n" +
                "  try {\n" +
                "    return typeof window.handleAndroidBackPress === 'function' ? window.handleAndroidBackPress() : false;\n" +
                "  } catch(e) {\n" +
                "    return false;\n" +
                "  }\n" +
                "})()",
                value -> {
                    boolean handled = "true".equalsIgnoreCase(value) || Boolean.parseBoolean(value);
                    if (handled) {
                        // 网页内已拦截消费（如关闭弹窗或返回首页），重置退出计时
                        lastBackPressTime = 0;
                        return;
                    }
                    // 当前已经在首页，需要按两次返回键才退出应用
                    triggerDoubleBackExit();
                }
            );
        } else {
            triggerDoubleBackExit();
        }
    }

    private void triggerDoubleBackExit() {
        runOnUiThread(() -> {
            long now = System.currentTimeMillis();
            if (now - lastBackPressTime < 2000) {
                finish();
            } else {
                lastBackPressTime = now;
                Toast.makeText(MainActivity.this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (voiceBridge != null) {
            voiceBridge.destroy();
            voiceBridge = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
