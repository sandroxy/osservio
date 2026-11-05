package com.sandrox.osservio.core;

import java.util.List;

/** Explicit bounded local persistence. Callbacks may run on any thread, including immediate rejection. */
public interface IncidentArchive {
    interface Completion { void complete(PersistenceStatus.Error error); }
    interface SummariesCompletion { void complete(List<IncidentSummary> incidents, PersistenceStatus.Error error); }
    /** Null with no error means that the snapshot is no longer retained. */
    interface SnapshotCompletion { void complete(IncidentSnapshot snapshot, PersistenceStatus.Error error); }
    /** Event counters refer to snapshots, not their attached breadcrumbs. */
    PersistenceStatus persistenceStatus();
    /** Success follows transaction commit. Saving an already retained identity is rejected. */
    void save(IncidentSnapshot snapshot, Completion completion);
    void readIncidents(SummariesCompletion completion);
    void readIncident(String id, SnapshotCompletion completion);
    /** Deletes archived evidence only. Live histories and capture policy are unchanged. */
    void deleteIncident(String id, Completion completion);
    void deleteAllIncidents(Completion completion);
}
