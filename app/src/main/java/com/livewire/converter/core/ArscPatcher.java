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

/**
 * Replaces the package name stored in resources.arsc. The name lives in
 * each Package chunk as a fixed 128 UTF-16 char (256 byte) field, so this
 * is a plain overwrite with zero padding - no offsets change.
 */
public final class ArscPatcher {

    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;

    private ArscPatcher() {}

    public static byte[] setPackageName(byte[] arsc, String packageName) throws Exception {
        if (packageName.length() > 127) {
            throw new IllegalArgumentException("package name too long (max 127 chars)");
        }
        if (AxmlPatcher.readU16(arsc, 0) != RES_TABLE_TYPE) {
            throw new IllegalArgumentException("not a resources.arsc file");
        }
        byte[] out = arsc.clone();
        int off = AxmlPatcher.readU16(arsc, 2); // headerSize (12)
        int patched = 0;
        while (off + 8 <= arsc.length) {
            int type = AxmlPatcher.readU16(arsc, off);
            int chunkSize = AxmlPatcher.readU32(arsc, off + 4);
            if (chunkSize <= 0) {
                break;
            }
            if (type == RES_TABLE_PACKAGE_TYPE) {
                // Package chunk: header (8) + id (4) + name (256 bytes UTF-16LE).
                int nameOff = off + 12;
                for (int i = 0; i < 128; i++) {
                    int c = i < packageName.length() ? packageName.charAt(i) : 0;
                    out[nameOff + i * 2] = (byte) (c & 0xFF);
                    out[nameOff + i * 2 + 1] = (byte) ((c >> 8) & 0xFF);
                }
                patched++;
            }
            off += chunkSize;
        }
        if (patched == 0) {
            throw new IllegalArgumentException("no package chunk found in resources.arsc");
        }
        return out;
    }

    /** Read the package name from the first Package chunk (for tests). */
    public static String getPackageName(byte[] arsc) {
        int off = AxmlPatcher.readU16(arsc, 2);
        while (off + 8 <= arsc.length) {
            int type = AxmlPatcher.readU16(arsc, off);
            int chunkSize = AxmlPatcher.readU32(arsc, off + 4);
            if (chunkSize <= 0) {
                break;
            }
            if (type == RES_TABLE_PACKAGE_TYPE) {
                StringBuilder sb = new StringBuilder();
                int nameOff = off + 12;
                for (int i = 0; i < 128; i++) {
                    int c = AxmlPatcher.readU16(arsc, nameOff + i * 2);
                    if (c == 0) {
                        break;
                    }
                    sb.append((char) c);
                }
                return sb.toString();
            }
            off += chunkSize;
        }
        return null;
    }
}
