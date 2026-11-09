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

package com.livewire.converter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Donations page: shows Elijah's PayPal QR code for donations. */
public class DonateActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        // Keep the page clear of the status bar (clock/battery) and the
        // navigation bar, the same way the main screen does.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
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
            applySafeMargins(v, left, top, right, bottom);
            return insets;
        });
        scroll.requestApplyInsets();
        applySafeMargins(scroll, 0, systemBarHeight("status_bar_height"), 0,
                systemBarHeight("navigation_bar_height"));

        TextView title = new TextView(this);
        title.setText("Support Elijah");
        title.setTextSize(26);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("If you enjoy these apps, you can support my work with a "
                + "donation. Scan the code below with your PayPal app.");
        sub.setTextSize(14);
        root.addView(sub);

        ImageView qr = new ImageView(this);
        qr.setImageResource(R.drawable.donate_qr);
        qr.setAdjustViewBounds(true);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        qrParams.topMargin = dp(12);
        qr.setLayoutParams(qrParams);
        root.addView(qr);

        TextView caption = new TextView(this);
        caption.setText("Scan to donate via PayPal");
        caption.setTextSize(14);
        caption.setGravity(android.view.Gravity.CENTER);
        root.addView(caption);

        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> finish());
        root.addView(back);
    }

    /** Move a view clear of the system bars by setting its margins. */
    private static void applySafeMargins(View v,
                                         int left, int top, int right, int bottom) {
        ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) v.getLayoutParams();
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
