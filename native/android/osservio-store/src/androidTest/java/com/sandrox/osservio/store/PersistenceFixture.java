package com.sandrox.osservio.store;

import android.content.Context;
import android.system.Os;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.PersistenceStatus;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Every fixture owns a fresh directory; no consumer database is opened or removed. */
final class PersistenceFixture implements AutoCloseable {
    final File directory;
    final File file;
    PersistenceFixture() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = new File(context.getCacheDir(), "osservio-storage-test-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        Os.chmod(directory.getAbsolutePath(), 0700);
        file = new File(directory, "breadcrumbs.sqlite");
    }
    BreadcrumbDatabase database(String id, BreadcrumbPersistenceOptions options, long now) {
        return new BreadcrumbDatabase(file, options, id, 123, now);
    }
    BreadcrumbDatabase database(String id) { return database(id, new BreadcrumbPersistenceOptions(), 1000); }
    static List<BreadcrumbEvent> events(int count, String text, BreadcrumbEvent.Retention retention) {
        String session = UUID.randomUUID().toString();
        List<BreadcrumbEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) events.add(BreadcrumbEvent.restore(UUID.randomUUID().toString(), session, i + 1,
            1000, Long.MAX_VALUE, BreadcrumbEvent.Kind.HOST_MARKER, "a", text, false, false, null, retention));
        return events;
    }
    static List<BreadcrumbEvent> events(int count, String text) { return events(count, text, BreadcrumbEvent.Retention.RESTART_RELEVANT); }
    static List<String> ids(List<BreadcrumbEvent> events) {
        List<String> ids = new ArrayList<>(); for (BreadcrumbEvent event : events) ids.add(event.id); return ids;
    }
    static void write(File file, byte[] bytes) throws IOException { try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(bytes); } }
    static byte[] read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
    interface Operation { void run() throws Exception; }
    static void expect(PersistenceStatus.Error expected, Operation operation) throws Exception {
        try { operation.run(); fail("Expected " + expected); }
        catch (Exception failure) { assertEquals(expected, EvidenceStorageException.category(failure)); }
    }
    static final class Result<T> {
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        T value; PersistenceStatus.Error error;
        void complete(T value, PersistenceStatus.Error error) { this.value = value; this.error = error; calls.incrementAndGet(); done.countDown(); }
        Result<T> await() throws InterruptedException { assertTrue("Storage callback timed out", done.await(10, TimeUnit.SECONDS)); assertEquals(1, calls.get()); return this; }
        T success() throws InterruptedException { await(); assertNull(error); return value; }
    }
    @Override public void close() throws IOException { remove(directory); }
    private static void remove(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!file.delete()) throw new IOException("Cannot remove owned test fixture");
    }
}
