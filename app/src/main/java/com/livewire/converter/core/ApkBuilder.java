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

package com.livewire.converter.core;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns a template APK + a user HTML file into a signed installable APK.
 * Pure Java (no Android classes) so the same code runs in the app and in
 * the JVM test harness. The template is built with aapt2, so resources.arsc
 * and classes.dex stay stored (uncompressed) and aligned, and the output
 * is signed v1+v2+v3 with the signing key handed in by the caller.
 * The caller owns the key: on Android it is generated on the device and
 * kept in the Android KeyStore, so no signing key is ever shipped with
 * the app or stored in this repository.
 */
public final class ApkBuilder {

    public static final String TEMPLATE_PACKAGE = "com.livewire.template";
    public static final String TEMPLATE_LABEL = "HTML App";

    private ApkBuilder() {}

    /** Launcher icon sizes (px) per density, matching the template's mipmap entries. */
    public static final String[] ICON_DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    public static final int[] ICON_SIZES = {48, 72, 96, 144, 192};

    /** Entry names in the template APK for a density's launcher icons. */
    public static String[] iconEntryNames(String density) {
        return new String[]{
                "res/mipmap-" + density + "-v4/ic_launcher.png",
                "res/mipmap-" + density + "-v4/ic_launcher_round.png"};
    }

    /**
     * @param templateApk  unsigned template APK bytes
     * @param html         the user's HTML file bytes
     * @param appName      label shown on the home screen
     * @param packageName  application id, e.g. com.example.mygame
     * @param iconPngs     optional map of template entry name -> PNG bytes
     *                     (from {@link #iconEntryNames}); null or empty keeps
     *                     the template's default icon
     * @param signingKey   private key to sign the output APK with
     * @param certificates the key's certificate chain (at least one cert)
     * @param workDir      scratch directory for temp files
     * @return signed APK bytes
     */
    public static byte[] build(byte[] templateApk, byte[] html, String appName,
                               String packageName, Map<String, byte[]> iconPngs,
                               PrivateKey signingKey,
                               List<Certificate> certificates,
                               File workDir) throws Exception {
        validatePackageName(packageName);
        if (appName == null || appName.trim().isEmpty()) {
            throw new IllegalArgumentException("app name is required");
        }
        if (html == null || html.length == 0) {
            throw new IllegalArgumentException("HTML file is empty");
        }

        // 1. Read the template and patch entries.
        Map<String, String> replacements = new HashMap<>();
        replacements.put(TEMPLATE_PACKAGE, packageName);
        replacements.put(TEMPLATE_LABEL, appName);
        AlignedZipWriter writer = new AlignedZipWriter();
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(templateApk));
        ZipEntry entry;
        while ((entry = zin.getNextEntry()) != null) {
            String name = entry.getName();
            if (name.startsWith("META-INF/")) {
                continue; // template is unsigned; signer adds fresh signatures
            }
            byte[] data = readAll(zin);
            int method;
            if (iconPngs != null && iconPngs.containsKey(name)) {
                data = iconPngs.get(name);
                method = AlignedZipWriter.STORED;
            } else if (name.equals("AndroidManifest.xml")) {
                data = AxmlPatcher.replaceStrings(data, replacements);
                method = AlignedZipWriter.DEFLATED;
            } else if (name.equals("resources.arsc")) {
                data = ArscPatcher.setPackageName(data, packageName);
                method = AlignedZipWriter.STORED;
            } else if (name.equals("assets/www/index.html")) {
                data = html;
                method = AlignedZipWriter.DEFLATED;
            } else if (name.equals("classes.dex") || name.endsWith(".png")
                    || name.endsWith(".arsc")) {
                method = AlignedZipWriter.STORED;
            } else {
                method = entry.getMethod() == ZipEntry.STORED
                        ? AlignedZipWriter.STORED : AlignedZipWriter.DEFLATED;
            }
            writer.add(name, data, method);
        }
        zin.close();
        byte[] unsignedApk = writer.finish();

        // 2. Sign v1+v2+v3 with the caller's key.
        File unsignedFile = new File(workDir, "unsigned.apk");
        File signedFile = new File(workDir, "signed.apk");
        writeFile(unsignedFile, unsignedApk);

        if (signingKey == null || certificates == null || certificates.isEmpty()) {
            throw new IllegalStateException("signing key not available");
        }
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : certificates) {
            certs.add((X509Certificate) c);
        }
        ApkSigner.SignerConfig signerConfig =
                new ApkSigner.SignerConfig.Builder("converter", signingKey, certs).build();
        ApkSigner signer = new ApkSigner.Builder(
                Collections.singletonList(signerConfig))
                .setInputApk(unsignedFile)
                .setOutputApk(signedFile)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(24)
                .build();
        signer.sign();

        byte[] signed = readFile(signedFile);
        unsignedFile.delete();
        signedFile.delete();
        return signed;
    }

    /** Package names must be valid Java-style identifiers, dot separated. */
    public static void validatePackageName(String packageName) {
        if (packageName == null || packageName.length() > 127) {
            throw new IllegalArgumentException("package name is required (max 127 chars)");
        }
        String[] parts = packageName.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("package name needs at least two parts, e.g. com.example.mygame");
        }
        for (String part : parts) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                throw new IllegalArgumentException("bad package part: '" + part + "'");
            }
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                    throw new IllegalArgumentException("bad package part: '" + part + "'");
                }
            }
        }
        if (packageName.contains("..")) {
            throw new IllegalArgumentException("package name must not contain '..'");
        }
    }

    /** Build a default package name from an app name, e.g. "My Game" -> com.livewire.mygame. */
    public static String defaultPackageName(String appName) {
        StringBuilder sb = new StringBuilder("com.livewire.");
        boolean first = true;
        for (int i = 0; i < appName.length(); i++) {
            char c = Character.toLowerCase(appName.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                if (first && c >= '0' && c <= '9') {
                    sb.append('a');
                }
                sb.append(c);
                first = false;
            }
        }
        String pkg = sb.toString();
        if (pkg.equals("com.livewire.")) {
            pkg = "com.livewire.app";
        }
        return pkg;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static void writeFile(File f, byte[] data) throws Exception {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    static byte[] readFile(File f) throws Exception {
        return readAll(new FileInputStream(f));
    }
}
