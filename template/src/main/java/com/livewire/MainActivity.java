// Copyright 2026 Elijah Wougamon
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.livewire;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView webView;
    private long lastBack;
    // Last system-bar insets applied, so they can be re-applied when the
    // screen rotates (inset listeners don't always re-fire then).
    private int[] lastInsets;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(0xFF0F172A);
        setContentView(webView);

        // Android 16 draws apps edge-to-edge, so the page slides under
        // the status bar (clock/battery) and the navigation bar unless
        // the WebView itself is moved. Padding does NOT move web content
        // — a WebView draws its page across its full bounds — so the
        // system-bar insets are applied as margins on the WebView,
        // shrinking it to the safe area between the bars. The bar areas
        // themselves are painted the page's dark colour with light icons.
        getWindow().getDecorView().setBackgroundColor(0xFF0F172A);
        getWindow().setStatusBarColor(0xFF0F172A);
        getWindow().setNavigationBarColor(0xFF0F172A);
        webView.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars =
                        insets.getInsets(android.view.WindowInsets.Type.systemBars());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            lastInsets = new int[]{left, top, right, bottom};
            applySafeMargins(v, left, top, right, bottom);
            return insets;
        });
        webView.requestApplyInsets();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
        // Fallback in case insets are never dispatched: use the system
        // bar heights from resources so the page still clears the bars.
        // The listener above overwrites these with the exact values
        // when it fires.
        applySafeMargins(webView, 0, systemBarHeight("status_bar_height"), 0,
                systemBarHeight("navigation_bar_height"));

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/www/index.html");
        }
    }

    /** Move a view clear of the system bars by setting its margins. */
    private static void applySafeMargins(android.view.View v,
                                         int left, int top, int right, int bottom) {
        android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (lp == null) return;
        if (lp.leftMargin != left || lp.topMargin != top
                || lp.rightMargin != right || lp.bottomMargin != bottom) {
            lp.setMargins(left, top, right, bottom);
            v.setLayoutParams(lp);
        }
    }

    /** Height of a system bar from the framework's resources, or 0. */
    private int systemBarHeight(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Inset listeners don't always re-fire on rotation; re-apply the
        // last known safe area and ask for fresh insets.
        if (lastInsets != null) {
            applySafeMargins(webView, lastInsets[0], lastInsets[1],
                    lastInsets[2], lastInsets[3]);
        }
        webView.requestApplyInsets();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            finish();
            return;
        }
        lastBack = now;
        Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        webView.restoreState(savedInstanceState);
    }
}
