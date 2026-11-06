package com.sandrox.osservio.store;

import static com.sandrox.osservio.store.EvidenceSQLite.integer;
import static com.sandrox.osservio.store.EvidenceSQLite.text;
import static com.sandrox.osservio.store.EvidenceSQLite.textColumn;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sandrox.osservio.core.DiagnosticRecoveryReceipt;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Worker-owned acknowledgements of sanitized report material. The caller owns the transaction. */
final class DiagnosticRecoveryDatabase {
    interface Saver { void save(IncidentSnapshot snapshot); }
    static final int MAXIMUM_ACKNOWLEDGEMENTS = 256;
    static final String[] SCHEMA = {
        "CREATE TABLE diagnostic_acknowledgements (ordinal INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL UNIQUE, "
            + "occurrences INTEGER NOT NULL, processed_at INTEGER NOT NULL)"
    };
    private final SQLiteDatabase database;
    private final IncidentPersistenceOptions options;
    DiagnosticRecoveryDatabase(SQLiteDatabase database, IncidentPersistenceOptions options) { this.database = database; this.options = options; }
    /** Validates even expired rows before deletion so corrupted records are never silently repaired. */
    Map<String, Integer> prepare(long now) {
        long cutoff = cutoff(now);
        Map<String, Integer> retained = new HashMap<>();
        try (Cursor row = database.rawQuery("SELECT ordinal," + textColumn("fingerprint", 64) + ",occurrences,processed_at FROM diagnostic_acknowledgements LIMIT 257", null)) {
            int count = 0;
            while (row.moveToNext()) {
                count++;
                String fingerprint = text(row, 1, 64); long occurrences = integer(row, 2);
                if (count > MAXIMUM_ACKNOWLEDGEMENTS || integer(row, 0) <= 0 || !fingerprint.matches("[0-9a-f]{64}") || occurrences < 1 || occurrences > 32)
                    throw EvidenceStorageException.invalid();
                if (integer(row, 3) >= cutoff) retained.put(fingerprint, (int) occurrences);
            }
        }
        if (now >= Long.MIN_VALUE + options.maximumAgeMillis) database.execSQL("DELETE FROM diagnostic_acknowledgements WHERE processed_at<?", new Object[] {cutoff});
        return retained;
    }
    DiagnosticRecoveryReceipt recover(SystemDiagnosticBatch batch, long now, Saver save) {
        Map<String, Integer> prior = prepare(now), occurrences = new TreeMap<>();
        Map<String, Integer> aliases = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        int ignored = 0, outside = 0, capacity = 0, repeated = 0;
        long cutoff = cutoff(now);
        for (int index = 0; index < batch.diagnostics.size(); index++) {
            SystemDiagnostic diagnostic = batch.diagnostics.get(index);
            if (diagnostic.kind == SystemDiagnostic.Kind.OTHER_EXIT) { ignored++; continue; }
            if (diagnostic.intervalEndMillis < cutoff || diagnostic.intervalEndMillis > now) { outside++; continue; }
            if (diagnostic.textByteCount() > options.textBytesPerIncident) { capacity++; continue; }
            String fingerprint = DiagnosticFingerprint.make(diagnostic);
            Integer previousOccurrence = occurrences.get(fingerprint);
            int occurrence = (previousOccurrence == null ? 0 : previousOccurrence) + 1;
            occurrences.put(fingerprint, occurrence);
            Integer processed = prior.get(fingerprint);
            if (processed != null && occurrence <= processed) repeated++;
            else candidates.add(new Candidate(index, diagnostic));
            String alias = DiagnosticFingerprint.summaryAlias(diagnostic);
            if (alias != null) aliases.put(alias, aliases.containsKey(alias) ? aliases.get(alias) + 1 : 1);
        }
        Collections.sort(candidates, (left, right) -> {
            int result = Long.compare(right.diagnostic.intervalEndMillis, left.diagnostic.intervalEndMillis);
            if (result == 0) result = Long.compare(right.diagnostic.intervalStartMillis, left.diagnostic.intervalStartMillis);
            return result == 0 ? Integer.compare(left.index, right.index) : result;
        });
        int selected = Math.min(candidates.size(), options.retainedDiagnostics);
        capacity += candidates.size() - selected;
        // Oldest first preserves the newest selected evidence under ordinal-based retention.
        for (int index = selected - 1; index >= 0; index--) save.save(IncidentSnapshot.captureDiagnostic(candidates.get(index).diagnostic, batch.observedAtMillis));
        // Keep alias counts out of candidate admission; a batch may contain both richer and summary material.
        for (Map.Entry<String, Integer> alias : aliases.entrySet()) {
            Integer count = occurrences.get(alias.getKey());
            occurrences.put(alias.getKey(), (count == null ? 0 : count) + alias.getValue());
        }
        for (Map.Entry<String, Integer> entry : occurrences.entrySet()) {
            Integer previous = prior.get(entry.getKey());
            if (previous != null) {
                if (entry.getValue() > previous) database.execSQL("UPDATE diagnostic_acknowledgements SET occurrences=? WHERE fingerprint=?",
                    new Object[] {entry.getValue(), entry.getKey()});
            } else database.execSQL("INSERT INTO diagnostic_acknowledgements(fingerprint,occurrences,processed_at) VALUES (?,?,?)",
                new Object[] {entry.getKey(), entry.getValue(), now});
        }
        database.execSQL("DELETE FROM diagnostic_acknowledgements WHERE ordinal IN (SELECT ordinal FROM diagnostic_acknowledgements ORDER BY ordinal DESC LIMIT -1 OFFSET ?)",
            new Object[] {MAXIMUM_ACKNOWLEDGEMENTS});
        return new DiagnosticRecoveryReceipt(batch, selected, repeated, ignored, outside, capacity);
    }
    private long cutoff(long now) { return now < Long.MIN_VALUE + options.maximumAgeMillis ? Long.MIN_VALUE : now - options.maximumAgeMillis; }
    private static final class Candidate {
        final int index; final SystemDiagnostic diagnostic;
        Candidate(int index, SystemDiagnostic diagnostic) { this.index = index; this.diagnostic = diagnostic; }
    }
}
