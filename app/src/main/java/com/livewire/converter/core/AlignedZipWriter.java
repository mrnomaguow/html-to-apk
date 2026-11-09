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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes a ZIP file by hand so stored (uncompressed) entries start on a
 * 4-byte boundary, exactly like zipalign -f -p 4. No data descriptors:
 * CRC and sizes are written up front in each local header.
 */
public final class AlignedZipWriter {

    public static final int STORED = 0;
    public static final int DEFLATED = 8;

    public static final class Entry {
        public final String name;
        public final byte[] data;
        public final int method;

        public Entry(String name, byte[] data, int method) {
            this.name = name;
            this.data = data;
            this.method = method;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void add(String name, byte[] data, int method) {
        entries.add(new Entry(name, data, method));
    }

    public byte[] finish() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> localOffsets = new ArrayList<>(entries.size());
        List<byte[]> compressedData = new ArrayList<>(entries.size());
        List<Integer> crcs = new ArrayList<>(entries.size());

        for (Entry e : entries) {
            byte[] nameBytes = e.name.getBytes("UTF-8");
            byte[] payload;
            if (e.method == DEFLATED) {
                Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
                def.setInput(e.data);
                def.finish();
                ByteArrayOutputStream comp = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                while (!def.finished()) {
                    int n = def.deflate(buf);
                    comp.write(buf, 0, n);
                }
                def.end();
                payload = comp.toByteArray();
            } else {
                payload = e.data;
            }
            compressedData.add(payload);
            CRC32 crc = new CRC32();
            crc.update(e.data);
            crcs.add((int) crc.getValue());

            int localOffset = out.size();
            localOffsets.add(localOffset);
            int extraLen = 0;
            if (e.method == STORED) {
                // Pad so the data starts on a 4-byte boundary.
                int headerLen = 30 + nameBytes.length + 4;
                int pad = (4 - ((localOffset + headerLen) % 4)) % 4;
                extraLen = 4 + pad;
            }
            // Local file header.
            writeU32(out, 0x04034b50);
            writeU16(out, e.method == STORED ? 10 : 20); // version needed
            writeU16(out, 0); // flags
            writeU16(out, e.method);
            writeU16(out, 0); // mod time
            writeU16(out, 0x21); // mod date (1980-01-01)
            writeU32(out, crcs.get(crcs.size() - 1));
            writeU32(out, payload.length);
            writeU32(out, e.data.length);
            writeU16(out, nameBytes.length);
            writeU16(out, extraLen);
            out.write(nameBytes);
            if (extraLen > 0) {
                writeU16(out, 0xD935); // zipalign padding marker
                writeU16(out, extraLen - 4);
                for (int i = 0; i < extraLen - 4; i++) {
                    out.write(0);
                }
            }
            out.write(payload);
        }

        int cdOffset = out.size();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            byte[] nameBytes = e.name.getBytes("UTF-8");
            byte[] payload = compressedData.get(i);
            int extraLen = 0;
            if (e.method == STORED) {
                int localOffset = localOffsets.get(i);
                int headerLen = 30 + nameBytes.length + 4;
                int pad = (4 - ((localOffset + headerLen) % 4)) % 4;
                extraLen = 4 + pad;
            }
            writeU32(out, 0x02014b50); // central directory header
            writeU16(out, 0x0314); // version made by (Unix, 20)
            writeU16(out, e.method == STORED ? 10 : 20); // version needed
            writeU16(out, 0); // flags
            writeU16(out, e.method);
            writeU16(out, 0); // mod time
            writeU16(out, 0x21); // mod date
            writeU32(out, crcs.get(i));
            writeU32(out, payload.length);
            writeU32(out, e.data.length);
            writeU16(out, nameBytes.length);
            writeU16(out, extraLen);
            writeU16(out, 0); // comment length
            writeU16(out, 0); // disk number
            writeU16(out, 0); // internal attrs
            writeU32(out, 0); // external attrs
            writeU32(out, localOffsets.get(i));
            out.write(nameBytes);
            if (extraLen > 0) {
                writeU16(out, 0xD935);
                writeU16(out, extraLen - 4);
                for (int j = 0; j < extraLen - 4; j++) {
                    out.write(0);
                }
            }
        }
        int cdSize = out.size() - cdOffset;
        // End of central directory.
        writeU32(out, 0x06054b50);
        writeU16(out, 0); // disk
        writeU16(out, 0); // cd disk
        writeU16(out, entries.size());
        writeU16(out, entries.size());
        writeU32(out, cdSize);
        writeU32(out, cdOffset);
        writeU16(out, 0); // comment length
        return out.toByteArray();
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}
