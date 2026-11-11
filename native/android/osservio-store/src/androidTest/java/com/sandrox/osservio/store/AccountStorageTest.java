package com.sandrox.osservio.store;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import java.io.File;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

/** Exercises public account-scoped APIs within one new, test-owned root. */
@RunWith(AndroidJUnit4.class)
public final class AccountStorageTest {
    @Test public void separateOwnersCannotReadEachOthersEvidenceAndReopenWithoutDeletion() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            Context context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getNoBackupFilesDir() { return fixture.directory; }
            };
            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            SQLiteIncidentStore incidentsA = new SQLiteIncidentStore(context, a, new IncidentPersistenceOptions());
            SQLiteIncidentStore incidentsB = new SQLiteIncidentStore(context, b, new IncidentPersistenceOptions());
            SQLiteIncidentStore process = new SQLiteIncidentStore(context);
            SQLiteBreadcrumbStore runsA = new SQLiteBreadcrumbStore(context, a, new BreadcrumbPersistenceOptions());
            SQLiteBreadcrumbStore runsB = new SQLiteBreadcrumbStore(context, b, new BreadcrumbPersistenceOptions());
            IncidentSnapshot first = IncidentSnapshot.capture(LogMessage.publicText("account A"), null, null);
            IncidentSnapshot second = IncidentSnapshot.capture(LogMessage.publicText("account B"), null, null);
            try {
                Result<Void> savedA = new Result<>(), savedB = new Result<>();
                incidentsA.save(first, error -> savedA.complete(null, error));
                incidentsB.save(second, error -> savedB.complete(null, error)); savedA.success(); savedB.success();
                assertEquals(first.id, summaries(incidentsA).get(0).id); assertEquals(second.id, summaries(incidentsB).get(0).id);
                assertTrue(summaries(process).isEmpty());
                Result<IncidentSnapshot> missing = new Result<>(); incidentsB.readIncident(first.id, missing::complete); assertNull(missing.success());
                runsA.append(events(1, "account A").get(0)); runsB.append(events(1, "account B").get(0));
                Result<Void> flushedA = new Result<>(), flushedB = new Result<>();
                runsA.flush(error -> flushedA.complete(null, error)); runsB.flush(error -> flushedB.complete(null, error)); flushedA.success(); flushedB.success();
                Result<RetainedBreadcrumbs> other = new Result<>(); runsB.readRun(runsA.currentRunId, other::complete); assertNull(other.success());
                assertEquals("account B", runsB.snapshot().events.get(0).message);
            } finally { close(incidentsA); close(incidentsB); close(process); close(runsA); close(runsB); }
            SQLiteIncidentStore reopened = new SQLiteIncidentStore(context, a, new IncidentPersistenceOptions());
            SQLiteBreadcrumbStore oldRuns = new SQLiteBreadcrumbStore(context, a, new BreadcrumbPersistenceOptions());
            try {
                assertEquals(first.id, summaries(reopened).get(0).id);
                Result<RetainedBreadcrumbs> retained = new Result<>(); oldRuns.readRun(runsA.currentRunId, retained::complete);
                assertEquals("account A", retained.success().events.get(0).message);
                Result<Void> late = new Result<>(); incidentsA.save(second, error -> late.complete(null, error));
                assertEquals(PersistenceStatus.Error.CLOSED, late.await().error);
            } finally { close(reopened); close(oldRuns); }
        }
    }
    private static List<IncidentSummary> summaries(SQLiteIncidentStore store) throws Exception {
        Result<List<IncidentSummary>> result = new Result<>(); store.readIncidents(result::complete); return result.success();
    }
    private static void close(SQLiteIncidentStore store) throws Exception {
        Result<Void> result = new Result<>(); store.close(error -> result.complete(null, error)); result.success();
    }
    private static void close(SQLiteBreadcrumbStore store) throws Exception {
        Result<Void> result = new Result<>(); store.close(error -> result.complete(null, error)); result.success();
    }
}
