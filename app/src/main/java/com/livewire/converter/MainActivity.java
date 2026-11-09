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
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.livewire.converter.core.ApkBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * HTML to APK: pick a single HTML file, name the app, build a signed APK
 * on the device and save / install it. Outputs are signed v1+v2+v3 with a
 * key generated on this phone, so they install on Android 16.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_HTML = 1;
    private static final int REQ_SAVE_APK = 2;
    private static final int REQ_PICK_ICON = 3;

    // Signing key for the APKs this app builds. It is generated on this
    // phone the first time an app is built and kept in the Android
    // KeyStore, so it never leaves the device and is never shipped with
    // the app. Apps built on this phone share its key and can update
    // each other; clearing the app's data or switching phones loses it.
    private static final String KEY_ALIAS = "converter";

    private TextView statusView;
    private TextView fileView;
    private EditText nameInput;
    private EditText packageInput;
    private Button pickButton;
    private Button buildButton;
    private Button saveButton;
    private Button installButton;

    private Uri htmlUri;
    private String htmlName;
    private Uri iconUri;
    private String iconName;
    private ImageView iconPreview;
    private TextView iconView;
    private byte[] builtApk;
    private Uri savedUri;
    private boolean packageEdited;
    private ScrollView scrollView;
    private int[] lastInsets;

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
        scrollView = scroll;

        // Android 16 draws apps edge-to-edge: move the whole screen
        // clear of the status bar (clock/battery) and the navigation
        // bar by applying the system-bar insets as margins on the
        // ScrollView, so nothing is hidden under them.
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
            lastInsets = new int[]{left, top, right, bottom};
            applySafeMargins(v, left, top, right, bottom);
            return insets;
        });
        scroll.requestApplyInsets();
        // Fallback in case insets are never dispatched: use the system
        // bar heights from resources. The listener overwrites these
        // with the exact values when it fires.
        applySafeMargins(scroll, 0, systemBarHeight("status_bar_height"), 0,
                systemBarHeight("navigation_bar_height"));

        TextView title = new TextView(this);
        title.setText("HTML to APK");
        title.setTextSize(26);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Turn a single HTML file into an installable app. "
                + "APKs are signed v1+v2+v3, so they install on Android 16.");
        sub.setTextSize(14);
        root.addView(sub);

        pickButton = new Button(this);
        pickButton.setText("1 · Pick HTML file");
        pickButton.setOnClickListener(v -> pickHtml());
        root.addView(pickButton);

        fileView = new TextView(this);
        fileView.setText("No file picked yet.");
        fileView.setTextSize(14);
        root.addView(fileView);

        TextView nameLabel = new TextView(this);
        nameLabel.setText("App name");
        root.addView(nameLabel);
        nameInput = new EditText(this);
        nameInput.setHint("e.g. My Game");
        nameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!packageEdited) {
                    packageInput.setText(ApkBuilder.defaultPackageName(s.toString()));
                }
            }
        });
        root.addView(nameInput);

        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("Package name (must be unique per app)");
        root.addView(pkgLabel);
        packageInput = new EditText(this);
        packageInput.setHint("com.example.mygame");
        packageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                packageEdited = true;
            }
        });
        root.addView(packageInput);

        TextView iconLabel = new TextView(this);
        iconLabel.setText("App icon (optional — square images work best)");
        root.addView(iconLabel);
        iconPreview = new ImageView(this);
        LinearLayout.LayoutParams previewParams =
                new LinearLayout.LayoutParams(dp(96), dp(96));
        previewParams.topMargin = dp(4);
        iconPreview.setLayoutParams(previewParams);
        iconPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(iconPreview);
        iconView = new TextView(this);
        iconView.setText("Default icon will be used.");
        iconView.setTextSize(14);
        root.addView(iconView);
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pickIconButton = new Button(this);
        pickIconButton.setText("Pick icon");
        pickIconButton.setOnClickListener(v -> pickIcon());
        iconRow.addView(pickIconButton);
        Button clearIconButton = new Button(this);
        clearIconButton.setText("Use default");
        clearIconButton.setOnClickListener(v -> {
            iconUri = null;
            iconName = null;
            iconPreview.setImageDrawable(null);
            iconView.setText("Default icon will be used.");
        });
        iconRow.addView(clearIconButton);
        root.addView(iconRow);

        buildButton = new Button(this);
        buildButton.setText("2 · Build APK");
        buildButton.setOnClickListener(v -> buildApk());
        root.addView(buildButton);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        root.addView(statusView);

        saveButton = new Button(this);
        saveButton.setText("3 · Save APK");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveApk());
        root.addView(saveButton);

        installButton = new Button(this);
        installButton.setText("Install");
        installButton.setEnabled(false);
        installButton.setOnClickListener(v -> installApk());
        root.addView(installButton);

        Button donateButton = new Button(this);
        donateButton.setText("Donate");
        donateButton.setOnClickListener(v -> startActivity(
                new Intent(this, DonateActivity.class)));
        root.addView(donateButton);

        TextView note = new TextView(this);
        note.setText("Note: single HTML file only — separate images, CSS or JS "
                + "files are not included. This phone generates its own signing "
                + "key the first time you build, so two apps with the same "
                + "package name will replace each other; give each app its own "
                + "package name.");
        note.setTextSize(12);
        root.addView(note);

        if (savedInstanceState != null) {
            String uri = savedInstanceState.getString("htmlUri");
            if (uri != null) {
                htmlUri = Uri.parse(uri);
                htmlName = savedInstanceState.getString("htmlName");
                fileView.setText("Picked: " + htmlName);
            }
            nameInput.setText(savedInstanceState.getString("appName", ""));
            packageInput.setText(savedInstanceState.getString("packageName", ""));
            packageEdited = savedInstanceState.getBoolean("packageEdited", false);
            String iconUriStr = savedInstanceState.getString("iconUri");
            if (iconUriStr != null) {
                iconUri = Uri.parse(iconUriStr);
                iconName = savedInstanceState.getString("iconName");
                iconPreview.setImageURI(iconUri);
                iconView.setText("Icon: " + iconName);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (htmlUri != null) {
            outState.putString("htmlUri", htmlUri.toString());
            outState.putString("htmlName", htmlName);
        }
        if (iconUri != null) {
            outState.putString("iconUri", iconUri.toString());
            outState.putString("iconName", iconName);
        }
        outState.putString("appName", nameInput.getText().toString());
        outState.putString("packageName", packageInput.getText().toString());
        outState.putBoolean("packageEdited", packageEdited);
    }

    private void pickHtml() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "text/plain"});
        startActivityForResult(intent, REQ_PICK_HTML);
    }

    private void pickIcon() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_PICK_ICON);
    }

    private void saveApk() {
        if (builtApk == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        String name = nameInput.getText().toString().trim();
        intent.putExtra(Intent.EXTRA_TITLE, (name.isEmpty() ? "app" : name) + ".apk");
        startActivityForResult(intent, REQ_SAVE_APK);
    }

    private void installApk() {
        if (savedUri == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(savedUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No installer found: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void buildApk() {
        if (htmlUri == null) {
            setStatus("Pick an HTML file first.");
            return;
        }
        final String appName = nameInput.getText().toString().trim();
        final String packageName = packageInput.getText().toString().trim();
        if (appName.isEmpty()) {
            setStatus("Enter an app name.");
            return;
        }
        try {
            ApkBuilder.validatePackageName(packageName);
        } catch (IllegalArgumentException e) {
            setStatus("Bad package name: " + e.getMessage());
            return;
        }
        setStatus("Building…");
        setBusy(true);
        final Uri uri = htmlUri;
        final Uri icon = iconUri;
        new Thread(() -> {
            try {
                byte[] html = readUri(uri);
                byte[] template = readAsset("template.apk");
                Map<String, byte[]> iconPngs = null;
                if (icon != null) {
                    iconPngs = buildIconPngs(icon);
                }
                PrivateKey signingKey = getOrCreateSigningKey();
                List<Certificate> certs = new ArrayList<>();
                certs.add(getSigningCertificate());
                byte[] apk = ApkBuilder.build(template, html, appName, packageName,
                        iconPngs, signingKey, certs, getCacheDir());
                builtApk = apk;
                savedUri = null;
                runOnUiThread(() -> {
                    setStatus("Built " + appName + " (" + apk.length + " bytes, package "
                            + packageName + "). Save it, then install.");
                    saveButton.setEnabled(true);
                    installButton.setEnabled(false);
                    setBusy(false);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    setStatus("Build failed: " + e.getMessage());
                    setBusy(false);
                });
            }
        }).start();
    }

    /**
     * The signing key for apps built on this phone. Generated once in the
     * Android KeyStore (RSA 2048, self-signed cert, 25 years) and reused
     * afterwards; the key material never leaves the device.
     */
    private PrivateKey getOrCreateSigningKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            Calendar notBefore = Calendar.getInstance();
            Calendar notAfter = Calendar.getInstance();
            notAfter.add(Calendar.YEAR, 25);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setCertificateSubject(new X500Principal("CN=HTML to APK Converter"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(notBefore.getTime())
                    .setCertificateNotAfter(notAfter.getTime())
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build();
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            kpg.initialize(spec);
            kpg.generateKeyPair();
        }
        return (PrivateKey) ks.getKey(KEY_ALIAS, null);
    }

    private Certificate getSigningCertificate() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Certificate cert = ks.getCertificate(KEY_ALIAS);
        if (cert == null) {
            throw new IllegalStateException("signing certificate not found");
        }
        return cert;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_HTML) {
            htmlUri = uri;
            htmlName = displayName(uri);
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            fileView.setText("Picked: " + htmlName);
            if (htmlName != null) {
                // Loading a new file always refreshes the app name and
                // package from the file name, even if they were set before.
                packageEdited = false;
                String appName = fileNameToAppName(htmlName);
                nameInput.setText(appName);
                packageInput.setText(ApkBuilder.defaultPackageName(appName));
            }
            setStatus("");
        } else if (requestCode == REQ_PICK_ICON) {
            iconUri = uri;
            iconName = displayName(uri);
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            iconPreview.setImageURI(uri);
            iconView.setText("Icon: " + iconName);
            setStatus("");
        } else if (requestCode == REQ_SAVE_APK) {
            savedUri = uri;
            try {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) {
                    throw new IllegalStateException("could not open output");
                }
                out.write(builtApk);
                out.close();
                setStatus("Saved. Tap Install to install it.");
                installButton.setEnabled(true);
            } catch (Exception e) {
                setStatus("Save failed: " + e.getMessage());
            }
        }
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String path = uri.getLastPathSegment();
        return path != null ? path : uri.toString();
    }

    /**
     * Turn a file name into a display name: "Live_Wire_Wiring_Sim.html"
     * becomes "Live Wire Wiring Sim", "my-cool-game.html" becomes
     * "My Cool Game", "MyGame.html" becomes "My Game".
     */
    private static String fileNameToAppName(String fileName) {
        String base = fileName.replaceAll("(?i)\\.html?$", "");
        base = base.replace('_', ' ').replace('-', ' ');
        base = base.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        base = base.replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ");
        base = base.trim().replaceAll("\\s+", " ");
        StringBuilder out = new StringBuilder();
        boolean capNext = true;
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == ' ') {
                capNext = true;
                out.append(c);
            } else if (capNext) {
                out.append(Character.toUpperCase(c));
                capNext = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private byte[] readUri(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalStateException("could not open file");
        }
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private byte[] readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Decode the picked image, center-crop it to a square, and scale it to
     * every launcher density, encoded as PNG bytes keyed by template entry name.
     */
    private Map<String, byte[]> buildIconPngs(Uri icon) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(icon);
        if (in == null) {
            throw new IllegalStateException("could not open icon");
        }
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            in.close();
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("that file is not a readable image");
        }
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > 1024) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        in = getContentResolver().openInputStream(icon);
        if (in == null) {
            throw new IllegalStateException("could not open icon");
        }
        Bitmap src;
        try {
            src = BitmapFactory.decodeStream(in, null, opts);
        } finally {
            in.close();
        }
        if (src == null) {
            throw new IllegalArgumentException("that file is not a readable image");
        }
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap square = Bitmap.createBitmap(src,
                (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Map<String, byte[]> out = new HashMap<>();
        for (int i = 0; i < ApkBuilder.ICON_DENSITIES.length; i++) {
            int size = ApkBuilder.ICON_SIZES[i];
            Bitmap scaled = Bitmap.createScaledBitmap(square, size, size, true);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, png);
            byte[] pngBytes = png.toByteArray();
            for (String entryName : ApkBuilder.iconEntryNames(ApkBuilder.ICON_DENSITIES[i])) {
                out.put(entryName, pngBytes);
            }
            if (scaled != square) {
                scaled.recycle();
            }
        }
        if (square != src) {
            square.recycle();
        }
        src.recycle();
        return out;
    }

    private void setStatus(String s) {
        statusView.setText(s);
    }

    private void setBusy(boolean busy) {
        pickButton.setEnabled(!busy);
        buildButton.setEnabled(!busy);
        saveButton.setEnabled(!busy && builtApk != null);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Inset listeners don't always re-fire on rotation; re-apply the
        // last known safe area and ask for fresh insets.
        if (lastInsets != null && scrollView != null) {
            applySafeMargins(scrollView, lastInsets[0], lastInsets[1],
                    lastInsets[2], lastInsets[3]);
        }
        if (scrollView != null) {
            scrollView.requestApplyInsets();
        }
    }
}
