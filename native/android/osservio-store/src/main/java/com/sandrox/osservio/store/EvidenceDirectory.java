package com.sandrox.osservio.store;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.sandrox.osservio.core.PersistenceStatus;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashSet;
import java.util.Set;

/** Owns a private directory lease. No operation is invoked from the capture or UI thread. */
final class EvidenceDirectory implements AutoCloseable {
    // Older Android FileChannel implementations track overlapping locks per
    // channel, while POSIX locks are process-owned. Reject duplicate writers
    // before opening another descriptor, whose close could release our OS lock.
    private static final Set<String> processLeases = new HashSet<>();
    enum Contents {
        BREADCRUMBS("breadcrumbs"), INCIDENTS("incidents");
        final String basename;
        Contents(String basename) { this.basename = basename; }
    }
    final File databaseFile;
    private FileOutputStream owner;
    private FileLock lease;
    private String processLease;
    EvidenceDirectory(File directory) throws IOException, ErrnoException {
        this(directory, Contents.BREADCRUMBS);
    }
    EvidenceDirectory(File directory, Contents contents) throws IOException, ErrnoException {
        boolean created = directory.mkdirs();
        if (created) Os.chmod(directory.getAbsolutePath(), 0700);
        StructStat info = Os.lstat(directory.getAbsolutePath());
        if (!OsConstants.S_ISDIR(info.st_mode) || (info.st_mode & 0077) != 0 || info.st_uid != android.os.Process.myUid()) {
            throw new EvidenceStorageException(PersistenceStatus.Error.UNAVAILABLE);
        }
        databaseFile = new File(directory, contents.basename + ".sqlite");
        File lock = new File(directory, contents.basename + ".owner");
        String key = info.st_dev + ":" + info.st_ino + ":" + contents.basename;
        synchronized (processLeases) {
            if (!processLeases.add(key)) throw new EvidenceStorageException(PersistenceStatus.Error.BUSY);
            processLease = key;
        }
        try {
            owner = new FileOutputStream(Os.open(lock.getAbsolutePath(), OsConstants.O_CREAT | OsConstants.O_RDWR | OsConstants.O_NOFOLLOW, 0600));
            lease = owner.getChannel().tryLock();
            if (lease == null) throw new EvidenceStorageException(PersistenceStatus.Error.BUSY);
        } catch (OverlappingFileLockException failure) {
            close(); throw new EvidenceStorageException(PersistenceStatus.Error.BUSY);
        } catch (RuntimeException | IOException | ErrnoException failure) { close(); throw failure; }
    }
    @Override public void close() throws IOException {
        try { if (lease != null) lease.release(); }
        finally {
            lease = null;
            try { if (owner != null) owner.close(); }
            finally {
                owner = null;
                synchronized (processLeases) {
                    if (processLease != null) { processLeases.remove(processLease); processLease = null; }
                }
            }
        }
    }
}
