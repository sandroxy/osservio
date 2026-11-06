package com.sandrox.osservio.export;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.sandrox.osservio.core.IncidentExporter;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Platform ZIP encoding and verification; only two fixed entries with bounded document contents. */
final class IncidentZIP {
    private IncidentZIP() { }
    static long write(IncidentDocuments documents, File target, ExportTask task) throws IOException, ErrnoException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("incident.json", documents.metadataJSON.getBytes(StandardCharsets.UTF_8));
        entries.put("payloads.json", documents.payloadsJSON.getBytes(StandardCharsets.UTF_8));
        try (FileOutputStream file = new FileOutputStream(Os.open(target.getAbsolutePath(),
                OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600));
                ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(file, 16384))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                task.check(); ZipEntry header = new ZipEntry(entry.getKey()); header.setTime(978307200000L);
                zip.putNextEntry(header); byte[] data = entry.getValue();
                for (int offset = 0; offset < data.length; offset += 16384) {
                    task.check(); zip.write(data, offset, Math.min(16384, data.length - offset));
                }
                zip.closeEntry();
            }
        }
        long size = target.length();
        if (size <= 0 || size > IncidentExportOptions.MAXIMUM_FILE_BYTES) throw new ExportFailure(IncidentExporter.Error.TOO_LARGE);
        task.check();
        // Read after close to verify central directory, decoded content, sizes, and CRC before exposing the file.
        try (ZipFile archive = new ZipFile(target)) {
            if (archive.size() != 2) throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
            Set<String> seen = new HashSet<>(); Enumeration<? extends ZipEntry> values = archive.entries();
            while (values.hasMoreElements()) {
                ZipEntry entry = values.nextElement(); byte[] expected = entries.get(entry.getName());
                if (expected == null || !seen.add(entry.getName()) || entry.isDirectory() || entry.getSize() != expected.length)
                    throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
                int offset = 0; CRC32 crc = new CRC32(); byte[] buffer = new byte[16384];
                try (InputStream input = archive.getInputStream(entry)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        task.check();
                        if (count > expected.length - offset) throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
                        for (int index = 0; index < count; index++) if (buffer[index] != expected[offset + index])
                            throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
                        crc.update(buffer, 0, count); offset += count;
                    }
                }
                if (offset != expected.length || crc.getValue() != entry.getCrc()) throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
            }
        }
        return size;
    }
}
