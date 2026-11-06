package com.sandrox.osservio.export;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.content.FileProvider;
import com.sandrox.osservio.core.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class IncidentFileExporterTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private File root;
    private final List<IncidentFileExporter> exporters = new ArrayList<>();
    @Before public void createFixtureDirectory() throws Exception {
        root = new File(context.getCacheDir(), "osservio/exports/export-test-" + UUID.randomUUID());
        assertTrue(root.mkdirs()); Os.chmod(root.getAbsolutePath(), 0700);
    }
    @After public void removeOnlyFixtureDirectory() throws Exception {
        for (IncidentFileExporter exporter : exporters) close(exporter);
        remove(root);
    }

    @Test public void completeZIPPreservesDocumentsAndSurvivesCloseAndLateCancellation() throws Exception {
        IncidentFileExporter exporter = exporter(new IncidentExportOptions(), LogClock.SYSTEM);
        IncidentSnapshot snapshot = IncidentDocumentFixture.make("complete");
        Pending pending = new Pending(exporter, snapshot);
        IncidentExporter.PreparedFile prepared = pending.awaitFile(); pending.task.cancel();
        assertEquals(prepared.file.length(), prepared.byteCount);
        assertEquals(0600, Os.stat(prepared.file.getAbsolutePath()).st_mode & 0777);
        IncidentDocuments documents = IncidentDocuments.encode(snapshot);
        try (ZipFile zip = new ZipFile(prepared.file)) {
            assertEquals(2, zip.size());
            assertEquals(documents.metadataJSON, entryText(zip, "incident.json"));
            assertEquals(documents.payloadsJSON, entryText(zip, "payloads.json"));
        }
        close(exporter); assertTrue(prepared.file.exists());
        assertEquals(IncidentExporter.Error.CLOSED, new Pending(exporter, snapshot).awaitError());
        assertEquals(1, pending.callbacks.get());
        assertFalse(hasFilesEndingWith(".partial"));
    }

    @Test public void cancellationBusyAdmissionAndOrderedClose() throws Exception {
        GateClock clock = new GateClock(1);
        IncidentFileExporter exporter = exporter(new IncidentExportOptions(), clock);
        IncidentSnapshot snapshot = IncidentDocumentFixture.make("absent");
        Pending first = new Pending(exporter, snapshot);
        assertTrue(clock.entered.await(3, TimeUnit.SECONDS));
        assertEquals(IncidentExporter.Error.BUSY, new Pending(exporter, snapshot).awaitError());
        first.task.cancel(); first.task.cancel();
        CountDownLatch closed = new CountDownLatch(1);
        exporter.close(error -> { assertNull(error); assertEquals(1, first.callbacks.get()); closed.countDown(); });
        assertEquals(IncidentExporter.Error.CLOSED, new Pending(exporter, snapshot).awaitError());
        clock.release.countDown();
        assertEquals(IncidentExporter.Error.CANCELLED, first.awaitError());
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertFalse(hasFilesEndingWith(".zip", ".partial"));
        new Pending(exporter(new IncidentExportOptions(), LogClock.SYSTEM), snapshot).awaitFile();
    }

    @Test public void sharingProviderOnlyOpensCompletedFilesForReadingWithinItsPrivateRoot() throws Exception {
        IncidentExporter.PreparedFile prepared = new Pending(exporter(new IncidentExportOptions(), LogClock.SYSTEM),
            IncidentDocumentFixture.make("complete")).awaitFile();
        Uri uri = Uri.parse(prepared.contentURI.toString());
        assertEquals("content", uri.getScheme());
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
        assertNotNull(provider); assertFalse(provider.exported); assertTrue(provider.grantUriPermissions);
        assertEquals("application/zip", context.getContentResolver().getType(uri));
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            assertNotNull(cursor); assertTrue(cursor.moveToFirst());
            assertEquals(prepared.file.getName(), cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            assertEquals(prepared.byteCount, cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)));
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(input); assertEquals('P', input.read()); assertEquals('K', input.read());
        }
        assertThrows(SecurityException.class, () -> context.getContentResolver().openFileDescriptor(uri, "rw"));
        assertThrows(SecurityException.class, () -> context.getContentResolver().delete(uri, null, null));
        assertTrue(prepared.file.exists());
        Uri owner = FileProvider.getUriForFile(context, uri.getAuthority(), new File(root, "exports.owner"));
        assertThrows(SecurityException.class, () -> context.getContentResolver().query(owner, null, null, null, null));
        assertThrows(SecurityException.class, () -> context.getContentResolver().openInputStream(owner));
        Uri partial = FileProvider.getUriForFile(context, uri.getAuthority(), new File(root, "osservio-ffffffff-ffff-ffff-ffff-ffffffffffff.partial"));
        assertThrows(SecurityException.class, () -> context.getContentResolver().openInputStream(partial));
        File outside = new File(context.getCacheDir(), "osservio-ffffffff-ffff-ffff-ffff-ffffffffffff.zip");
        assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(context, uri.getAuthority(), outside));
        Uri traversal = Uri.parse("content://" + uri.getAuthority() + "/incidents/%2e%2e/%2e%2e/" + outside.getName());
        assertThrows(SecurityException.class, () -> context.getContentResolver().openInputStream(traversal));
    }

    @Test public void cancellationAfterWritingRemovesTheUnpublishedFile() throws Exception {
        GateClock clock = new GateClock(2);
        IncidentFileExporter exporter = exporter(new IncidentExportOptions(), clock);
        Pending pending = new Pending(exporter, IncidentDocumentFixture.make("complete"));
        assertTrue(clock.entered.await(3, TimeUnit.SECONDS));
        assertTrue(hasFilesEndingWith(".partial"));
        pending.task.cancel(); clock.release.countDown();
        assertEquals(IncidentExporter.Error.CANCELLED, pending.awaitError());
        assertFalse(hasFilesEndingWith(".zip", ".partial"));
    }

    @Test public void exclusiveOwnershipRetriesAfterRelease() throws Exception {
        IncidentFileExporter first = exporter(new IncidentExportOptions(), LogClock.SYSTEM);
        IncidentFileExporter second = exporter(new IncidentExportOptions(), LogClock.SYSTEM);
        IncidentSnapshot snapshot = IncidentDocumentFixture.make("absent");
        new Pending(first, snapshot).awaitFile();
        assertEquals(IncidentExporter.Error.BUSY, new Pending(second, snapshot).awaitError());
        close(first); new Pending(second, snapshot).awaitFile();
    }

    @Test public void missingSharingProviderRejectsPreparationAndDiscardsItsCompletedFile() throws Exception {
        Context unavailable = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public String getPackageName() { return "invalid.osservio.fixture"; }
        };
        IncidentFileExporter exporter = new IncidentFileExporter(unavailable, root, new IncidentExportOptions(), LogClock.SYSTEM);
        exporters.add(exporter);
        assertEquals(IncidentExporter.Error.UNAVAILABLE, new Pending(exporter, IncidentDocumentFixture.make("absent")).awaitError());
        assertFalse(hasFilesEndingWith(".zip", ".partial"));
    }

    @Test public void replacedOwnershipFileStopsTheOldExporter() throws Exception {
        File directory = new File(root, "cache");
        IncidentFileExporter first = new IncidentFileExporter(context, directory, new IncidentExportOptions(), LogClock.SYSTEM);
        IncidentFileExporter second = new IncidentFileExporter(context, directory, new IncidentExportOptions(), LogClock.SYSTEM);
        exporters.add(first); exporters.add(second);
        IncidentSnapshot snapshot = IncidentDocumentFixture.make("absent");
        new Pending(first, snapshot).awaitFile();
        assertTrue(new File(directory, "exports.owner").renameTo(new File(root, "retired.owner")));
        IncidentExporter.PreparedFile newFile = new Pending(second, snapshot).awaitFile();
        assertEquals(IncidentExporter.Error.UNAVAILABLE, new Pending(first, snapshot).awaitError());
        assertTrue(newFile.file.exists());
    }

    @Test public void symbolicLinkCannotBecomeAnOwnedExport() throws Exception {
        File directory = new File(root, "cache");
        IncidentFileExporter exporter = new IncidentFileExporter(context, directory, new IncidentExportOptions(), LogClock.SYSTEM); exporters.add(exporter);
        IncidentSnapshot snapshot = IncidentDocumentFixture.make("absent");
        File original = new Pending(exporter, snapshot).awaitFile().file;
        File outside = new File(root, "outside.txt"); write(outside, "keep");
        File link = new File(directory, "osservio-ffffffff-ffff-ffff-ffff-ffffffffffff.zip");
        Os.symlink(outside.getAbsolutePath(), link.getAbsolutePath());
        assertEquals(IncidentExporter.Error.UNAVAILABLE, new Pending(exporter, snapshot).awaitError());
        try (FileInputStream input = new FileInputStream(outside)) { assertEquals("keep", text(input)); }
        assertTrue(original.exists()); assertEquals(outside.getAbsolutePath(), Os.readlink(link.getAbsolutePath()));
    }

    @Test public void countAgeAndRecoveryPreserveForeignFiles() throws Exception {
        IncidentFileExporter exporter = exporter(new IncidentExportOptions(2, 33554432, 60000), LogClock.SYSTEM);
        IncidentSnapshot snapshot = IncidentDocumentFixture.make("absent");
        File first = new Pending(exporter, snapshot).awaitFile().file;
        assertTrue(first.setLastModified(System.currentTimeMillis() - 10000));
        File second = new Pending(exporter, snapshot).awaitFile().file;
        File third = new Pending(exporter, snapshot).awaitFile().file;
        assertFalse(first.exists()); assertTrue(second.exists());
        assertTrue(third.setLastModified(System.currentTimeMillis() - 61000));
        File partial = new File(root, "osservio-ffffffff-ffff-ffff-ffff-ffffffffffff.partial");
        File foreign = new File(root, "host-owned.txt");
        write(partial, "partial"); write(foreign, "keep");
        assertEquals(IncidentExporter.Error.UNAVAILABLE, new Pending(exporter, snapshot).awaitError());
        assertTrue(partial.exists()); assertTrue(third.exists());
        try (FileInputStream input = new FileInputStream(foreign)) { assertEquals("keep", text(input)); }
        assertTrue(foreign.delete()); // Test-owned fixture only.
        new Pending(exporter, snapshot).awaitFile();
        assertFalse(partial.exists()); assertFalse(third.exists()); assertTrue(second.exists());
    }

    @Test public void cacheByteBudgetEvictsWholeFilesAndRejectsOneOversizedFile() throws Exception {
        IncidentFileExporter exporter = exporter(new IncidentExportOptions(4, 1048576, 86400000), LogClock.SYSTEM);
        IncidentSnapshot snapshot = incompressibleSnapshot(false);
        IncidentExporter.PreparedFile first = new Pending(exporter, snapshot).awaitFile();
        assertTrue(first.byteCount > 524288);
        IncidentExporter.PreparedFile second = new Pending(exporter, snapshot).awaitFile();
        assertFalse(first.file.exists());
        assertEquals(IncidentExporter.Error.TOO_LARGE, new Pending(exporter, incompressibleSnapshot(true)).awaitError());
        assertTrue(second.file.exists());
        assertFalse(hasFilesEndingWith(".partial"));
    }

    @Test public void zipWriterDoesNotReplaceAnExistingDestination() throws Exception {
        IncidentDocuments documents = IncidentDocuments.encode(IncidentDocumentFixture.make("complete"));
        File target = new File(root, "write.partial");
        long bytes = IncidentZIP.write(documents, target, new ExportTask());
        assertEquals(bytes, target.length());
        byte[] original; try (FileInputStream input = new FileInputStream(target); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            original = output.toByteArray();
        }
        assertThrows(Exception.class, () -> IncidentZIP.write(documents, target, new ExportTask()));
        try (FileInputStream input = new FileInputStream(target); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            assertArrayEquals(original, output.toByteArray());
        }
    }

    @Test public void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new IncidentExportOptions(0, 1048576, 60000));
        assertThrows(IllegalArgumentException.class, () -> new IncidentExportOptions(17, 1048576, 60000));
        assertThrows(IllegalArgumentException.class, () -> new IncidentExportOptions(1, 0, 60000));
        assertThrows(IllegalArgumentException.class, () -> new IncidentExportOptions(1, 1048576, 59999));
        for (String address : new String[] {"file:///private/export.zip", "content:/export.zip", "content://owner/path?key=value",
                "content://owner/path#part", "content://user@owner/path", "content://owner"}) {
            assertThrows(IllegalArgumentException.class, () -> new IncidentExporter.PreparedFile(new File(root, "file.zip"), URI.create(address), 1));
        }
    }

    private IncidentFileExporter exporter(IncidentExportOptions options, LogClock clock) {
        IncidentFileExporter value = new IncidentFileExporter(context, root, options, clock); exporters.add(value); return value;
    }
    private boolean hasFilesEndingWith(String... suffixes) {
        String[] names = root.list(); assertNotNull(names);
        for (String name : names) for (String suffix : suffixes) if (name.endsWith(suffix)) return true;
        return false;
    }
    private static void close(IncidentFileExporter exporter) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<IncidentExporter.Error> result = new AtomicReference<>();
        exporter.close(error -> { result.set(error); done.countDown(); });
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertNull(result.get());
    }
    private static String entryText(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name); assertNotNull(entry); assertFalse(entry.isDirectory());
        try (InputStream input = zip.getInputStream(entry)) { return text(input); }
    }
    private static String text(InputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static void write(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(text.getBytes(StandardCharsets.UTF_8)); }
        Os.chmod(file.getAbsolutePath(), 0600);
    }
    private static void remove(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        assertTrue(file.delete());
    }
    private static final class Pending {
        final IncidentExporter.Task task;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger callbacks = new AtomicInteger();
        IncidentExporter.PreparedFile file;
        IncidentExporter.Error error;
        Pending(IncidentFileExporter exporter, IncidentSnapshot snapshot) {
            task = exporter.prepare(snapshot, (value, failure) -> { file = value; error = failure; callbacks.incrementAndGet(); done.countDown(); });
        }
        IncidentExporter.PreparedFile awaitFile() throws Exception {
            assertTrue(done.await(10, TimeUnit.SECONDS)); assertEquals(1, callbacks.get()); assertNull(error); assertNotNull(file); return file;
        }
        IncidentExporter.Error awaitError() throws Exception {
            assertTrue(done.await(10, TimeUnit.SECONDS)); assertEquals(1, callbacks.get()); assertNull(file); assertNotNull(error); return error;
        }
    }
    private static final class GateClock implements LogClock {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger(); final int pauseAt;
        GateClock(int pauseAt) { this.pauseAt = pauseAt; }
        @Override public long monotonicMillis() { return 0; }
        @Override public long wallTimeMillis() {
            if (calls.incrementAndGet() == pauseAt) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
            return System.currentTimeMillis();
        }
    }
    private static IncidentSnapshot incompressibleSnapshot(boolean includeBreadcrumbs) {
        Noise noise = new Noise(); List<LogEvent> logs = new ArrayList<>();
        for (int index = 1; index <= 16; index++) logs.add(LogEvent.restore(IncidentDocumentFixture.SESSION, index, 0, LogLevel.INFO,
            "fixture", noise.text(65529), false, false));
        IncidentSnapshot.Breadcrumbs crumbs = null;
        if (includeBreadcrumbs) {
            List<BreadcrumbEvent> events = new ArrayList<>();
            for (int index = 1; index <= 256; index++) events.add(BreadcrumbEvent.restore(UUID.randomUUID().toString(), IncidentDocumentFixture.SESSION,
                index, 0, null, BreadcrumbEvent.Kind.HOST_MARKER, "a", noise.text(4095), false, false, null, BreadcrumbEvent.Retention.MEMORY_ONLY));
            crumbs = IncidentSnapshot.Breadcrumbs.restore(events, 256, 0);
        }
        return IncidentSnapshot.restore(IncidentDocumentFixture.ID, 0, null, null, crumbs, IncidentLogs.restore(logs, 0, 16, 0), null, null);
    }
    private static final class Noise {
        private long state = 0x123456789abcdefL;
        String text(int count) {
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"; char[] result = new char[count];
            for (int index = 0; index < count; index++) {
                state ^= state << 13; state ^= state >>> 7; state ^= state << 17; result[index] = alphabet.charAt((int) (state >>> 58));
            }
            return new String(result);
        }
    }
}
