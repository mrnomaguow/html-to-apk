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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replaces strings in a binary Android XML file (AndroidManifest.xml) by
 * rebuilding its string pool. String indices are preserved, so every
 * attribute that referenced a string still points at the same index and
 * now reads the new value. Handles both UTF-8 and UTF-16 string pools.
 */
public final class AxmlPatcher {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int UTF8_FLAG = 0x100;

    private AxmlPatcher() {}

    /** Replace every pool string whose value equals a key in {@code replacements}. */
    public static byte[] replaceStrings(byte[] axml, Map<String, String> replacements) throws Exception {
        if (readU16(axml, 0) != RES_XML_TYPE) {
            throw new IllegalArgumentException("not a binary XML file");
        }
        int poolOff = 8; // string pool chunk follows the 8-byte file header
        if (readU16(axml, poolOff) != RES_STRING_POOL_TYPE) {
            throw new IllegalArgumentException("string pool chunk not found at offset 8");
        }
        int headerSize = readU16(axml, poolOff + 2);
        int chunkSize = readU32(axml, poolOff + 4);
        int stringCount = readU32(axml, poolOff + 8);
        int styleCount = readU32(axml, poolOff + 12);
        int flags = readU32(axml, poolOff + 16);
        int stringsStart = readU32(axml, poolOff + 20);
        if (styleCount != 0) {
            throw new IllegalArgumentException("styled string pool not supported");
        }
        boolean utf8 = (flags & UTF8_FLAG) != 0;

        List<String> strings = new ArrayList<>(stringCount);
        for (int i = 0; i < stringCount; i++) {
            int strOff = poolOff + stringsStart + readU32(axml, poolOff + headerSize + i * 4);
            strings.add(readString(axml, strOff, utf8));
        }
        for (int i = 0; i < strings.size(); i++) {
            String replacement = replacements.get(strings.get(i));
            if (replacement != null) {
                strings.set(i, replacement);
            }
        }

        // Rebuild the pool chunk.
        ByteArrayOutputStream pool = new ByteArrayOutputStream();
        ByteArrayOutputStream stringData = new ByteArrayOutputStream();
        int[] offsets = new int[stringCount];
        int pos = 0;
        for (int i = 0; i < stringCount; i++) {
            offsets[i] = pos;
            byte[] encoded = encodeString(strings.get(i), utf8);
            stringData.write(encoded);
            pos += encoded.length;
        }
        int newStringsStart = headerSize + 4 * stringCount;
        // Chunks must be 4-byte aligned: pad the string data with zeros.
        int dataLen = stringData.size();
        int pad = (4 - (newStringsStart + dataLen) % 4) % 4;
        for (int i = 0; i < pad; i++) {
            stringData.write(0);
        }
        int newChunkSize = newStringsStart + stringData.size();
        writeU16(pool, RES_STRING_POOL_TYPE);
        writeU16(pool, headerSize);
        writeU32(pool, newChunkSize);
        writeU32(pool, stringCount);
        writeU32(pool, styleCount);
        writeU32(pool, flags);
        writeU32(pool, newStringsStart);
        writeU32(pool, 0); // stylesStart
        for (int off : offsets) {
            writeU32(pool, off);
        }
        pool.write(stringData.toByteArray());
        byte[] newPool = pool.toByteArray();

        // Reassemble: file header (size updated) + new pool + rest of file.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(axml, 0, 2); // type
        writeU16(out, readU16(axml, 2)); // headerSize
        int restOff = poolOff + chunkSize;
        int newFileSize = 8 + newPool.length + (axml.length - restOff);
        writeU32(out, newFileSize);
        out.write(newPool);
        out.write(axml, restOff, axml.length - restOff);
        return out.toByteArray();
    }

    /** List the strings in the pool (for debugging / tests). */
    public static List<String> listStrings(byte[] axml) throws Exception {
        int poolOff = 8;
        int headerSize = readU16(axml, poolOff + 2);
        int stringCount = readU32(axml, poolOff + 8);
        int flags = readU32(axml, poolOff + 16);
        int stringsStart = readU32(axml, poolOff + 20);
        boolean utf8 = (flags & UTF8_FLAG) != 0;
        List<String> strings = new ArrayList<>(stringCount);
        for (int i = 0; i < stringCount; i++) {
            int strOff = poolOff + stringsStart + readU32(axml, poolOff + headerSize + i * 4);
            strings.add(readString(axml, strOff, utf8));
        }
        return strings;
    }

    private static String readString(byte[] data, int off, boolean utf8) throws Exception {
        if (utf8) {
            int[] p = new int[]{off};
            readUtf8Length(data, p); // UTF-16 char count, ignored on decode
            int byteLen = readUtf8Length(data, p);
            return new String(data, p[0], byteLen, StandardCharsets.UTF_8);
        } else {
            return readUtf16String(data, off);
        }
    }

    private static String readUtf16String(byte[] data, int off) {
        int p = off;
        int first = readU16(data, p);
        p += 2;
        int len;
        if ((first & 0x8000) != 0) {
            int second = readU16(data, p);
            p += 2;
            len = ((first & 0x7FFF) << 16) | second;
        } else {
            len = first;
        }
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = (char) readU16(data, p);
            p += 2;
        }
        return new String(chars);
    }

    private static int readUtf8Length(byte[] data, int[] p) {
        int b1 = data[p[0]++] & 0xFF;
        if ((b1 & 0x80) == 0) {
            return b1;
        }
        int b2 = data[p[0]++] & 0xFF;
        return ((b1 & 0x7F) << 8) | b2;
    }

    private static int readUtf16Length(byte[] data, int off) {
        return readU16(data, off);
    }

    private static byte[] encodeString(String s, boolean utf8) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (utf8) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeUtf8Length(out, s.length());
            writeUtf8Length(out, bytes.length);
            out.write(bytes);
            out.write(0);
        } else {
            writeUtf16Length(out, s.length());
            for (int i = 0; i < s.length(); i++) {
                writeU16(out, s.charAt(i));
            }
            writeU16(out, 0);
        }
        return out.toByteArray();
    }

    private static void writeUtf8Length(ByteArrayOutputStream out, int len) {
        if (len < 0x80) {
            out.write(len);
        } else {
            out.write(0x80 | (len >> 8));
            out.write(len & 0xFF);
        }
    }

    private static void writeUtf16Length(ByteArrayOutputStream out, int len) {
        if (len < 0x8000) {
            writeU16(out, len);
        } else {
            writeU16(out, 0x8000 | (len >> 16));
            writeU16(out, len & 0xFFFF);
        }
    }

    static int readU16(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    static int readU32(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    static void writeU16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    static void writeU32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}
