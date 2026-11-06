package com.sandrox.osservio.export;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.sandrox.osservio.core.IncidentExporter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Worker-confined private cache. Unrecognized entries are preserved and make preparation fail. */
final class ExportCache implements AutoCloseable {
    private final File directory;
    private final IncidentExportOptions options;
    private final long directoryDevice;
    private final long directoryInode;
    private FileOutputStream owner;
    private FileLock lease;
    private static final class Entry {
        final File file; final long bytes; final long modified; final boolean partial;
        Entry(File file, long bytes, boolean partial) {
            this.file = file; this.bytes = bytes; this.modified = file.lastModified(); this.partial = partial;
        }
    }
    ExportCache(File directory, IncidentExportOptions options) throws IOException, ErrnoException {
        this.directory = directory.getAbsoluteFile(); this.options = options;
        if (directory.mkdirs()) Os.chmod(directory.getAbsolutePath(), 0700);
        StructStat info = Os.lstat(directory.getAbsolutePath());
        if (!OsConstants.S_ISDIR(info.st_mode) || info.st_uid != android.os.Process.myUid() || (info.st_mode & 0077) != 0)
            throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
        directoryDevice = info.st_dev; directoryInode = info.st_ino;
        File lock = new File(directory, "exports.owner");
        owner = new FileOutputStream(Os.open(lock.getAbsolutePath(), OsConstants.O_CREAT | OsConstants.O_RDWR | OsConstants.O_NOFOLLOW, 0600));
        try {
            info = Os.fstat(owner.getFD());
            if (!privateFile(info)) throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
            lease = owner.getChannel().tryLock();
            if (lease == null) throw new ExportFailure(IncidentExporter.Error.BUSY);
        } catch (OverlappingFileLockException failure) {
            close(); throw new ExportFailure(IncidentExporter.Error.BUSY);
        } catch (IOException | RuntimeException | ErrnoException failure) { close(); throw failure; }
    }
    File newPartial() { return new File(directory, "osservio-" + UUID.randomUUID() + ".partial"); }
    void clean(long now) throws IOException, ErrnoException { clean(now, 0, null); }
    private void clean(long now, long incomingBytes, File preserving) throws IOException, ErrnoException {
        if (incomingBytes > options.cacheBytes) throw new ExportFailure(IncidentExporter.Error.TOO_LARGE);
        StructStat root = Os.lstat(directory.getAbsolutePath());
        StructStat opened = Os.fstat(owner.getFD());
        StructStat named = Os.lstat(new File(directory, "exports.owner").getAbsolutePath());
        if (!OsConstants.S_ISDIR(root.st_mode) || root.st_dev != directoryDevice || root.st_ino != directoryInode
                || root.st_uid != android.os.Process.myUid() || (root.st_mode & 0077) != 0
                || !privateFile(named) || named.st_dev != opened.st_dev || named.st_ino != opened.st_ino)
            throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
        // API 23 directory listing is array-based. This private directory is exclusively SDK-owned;
        // normal operation creates at most 16 completed files, one partial, and the ownership file.
        String[] names = directory.list();
        if (names == null || names.length > 32) throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
        List<Entry> files = new ArrayList<>();
        for (String name : names) {
            if (name.equals("exports.owner")) continue;
            boolean partial = name.endsWith(".partial"); String suffix = partial ? ".partial" : ".zip";
            if (!name.matches("osservio-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\" + suffix))
                throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
            File file = new File(directory, name); StructStat info = Os.lstat(file.getAbsolutePath());
            if (!privateFile(info) || info.st_size < 0) throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
            files.add(new Entry(file, info.st_size, partial));
        }
        List<Entry> retained = new ArrayList<>();
        for (Entry entry : files) {
            if (entry.file.equals(preserving)) continue;
            boolean expired = now >= Long.MIN_VALUE + options.maximumAgeMillis && entry.modified <= now - options.maximumAgeMillis;
            if (entry.partial || entry.bytes == 0 || entry.bytes > IncidentExportOptions.MAXIMUM_FILE_BYTES || expired) remove(entry.file);
            else retained.add(entry);
        }
        Collections.sort(retained, (left, right) -> left.modified == right.modified
            ? left.file.getName().compareTo(right.file.getName()) : Long.compare(left.modified, right.modified));
        long bytes = 0; for (Entry entry : retained) bytes += entry.bytes;
        int incomingCount = incomingBytes == 0 ? 0 : 1;
        while (retained.size() + incomingCount > options.fileCount || bytes + incomingBytes > options.cacheBytes) {
            Entry entry = retained.remove(0); remove(entry.file); bytes -= entry.bytes;
        }
    }
    IncidentExporter.PreparedFile commit(File partial, long bytes, long now) throws IOException, ErrnoException {
        clean(now, bytes, partial);
        File target = new File(directory, partial.getName().replace(".partial", ".zip"));
        if (target.exists()) throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
        Os.rename(partial.getAbsolutePath(), target.getAbsolutePath());
        return new IncidentExporter.PreparedFile(target, bytes);
    }
    private static boolean privateFile(StructStat info) {
        return OsConstants.S_ISREG(info.st_mode) && info.st_uid == android.os.Process.myUid()
            && (info.st_mode & 0077) == 0 && info.st_nlink == 1;
    }
    private static void remove(File file) throws IOException {
        if (!file.delete()) throw new ExportFailure(IncidentExporter.Error.UNAVAILABLE);
    }
    @Override public void close() throws IOException {
        try { if (lease != null) lease.release(); }
        finally { lease = null; if (owner != null) { owner.close(); owner = null; } }
    }
}
