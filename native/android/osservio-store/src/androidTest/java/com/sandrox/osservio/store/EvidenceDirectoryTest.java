package com.sandrox.osservio.store;

import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.PersistenceStatus;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class EvidenceDirectoryTest {
    @Test public void rejectedAliasesCannotReleaseTheActiveWriter() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            try (EvidenceDirectory owner = new EvidenceDirectory(fixture.directory)) {
                expect(PersistenceStatus.Error.BUSY, () -> new EvidenceDirectory(new File(fixture.directory, ".")));
                expect(PersistenceStatus.Error.BUSY, () -> new EvidenceDirectory(fixture.directory));
                assertEquals(fixture.file, owner.databaseFile);
            }
            try (EvidenceDirectory replacement = new EvidenceDirectory(fixture.directory)) {
                assertEquals(fixture.file, replacement.databaseFile);
            }
        }
    }

    @Test public void contentStoresHaveIndependentWriterOwnership() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture();
             EvidenceDirectory breadcrumbs = new EvidenceDirectory(fixture.directory);
             EvidenceDirectory incidents = new EvidenceDirectory(fixture.directory, EvidenceDirectory.Contents.INCIDENTS)) {
            assertNotEquals(breadcrumbs.databaseFile, incidents.databaseFile);
            expect(PersistenceStatus.Error.BUSY, () -> new EvidenceDirectory(fixture.directory, EvidenceDirectory.Contents.INCIDENTS));
        }
    }

    @Test public void failedOpenReturnsProcessOwnership() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            File link = new File(fixture.directory, "breadcrumbs.owner");
            Os.symlink("missing-target", link.getAbsolutePath());
            try { new EvidenceDirectory(fixture.directory); fail("A linked owner file must be rejected"); }
            catch (android.system.ErrnoException expected) { assertEquals(android.system.OsConstants.ELOOP, expected.errno); }
            assertTrue(link.delete());
            try (EvidenceDirectory owner = new EvidenceDirectory(fixture.directory)) {
                assertEquals(fixture.file, owner.databaseFile);
            }
        }
    }
}
