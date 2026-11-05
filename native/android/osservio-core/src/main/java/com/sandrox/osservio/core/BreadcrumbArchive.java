package com.sandrox.osservio.core;

import java.util.List;

/** Bounded asynchronous access to committed evidence. Callbacks may run on any thread. */
public interface BreadcrumbArchive {
    interface RunsCompletion { void complete(List<RetainedRun> runs, PersistenceStatus.Error error); }
    /** A null result with no error means that the run is no longer retained. */
    interface RunCompletion { void complete(RetainedBreadcrumbs run, PersistenceStatus.Error error); }
    interface Completion { void complete(PersistenceStatus.Error error); }
    PersistenceStatus persistenceStatus();
    void readRuns(RunsCompletion completion);
    void readRun(String id, RunCompletion completion);
    /** Barrier for admitted persistence work, excluding discarded observations reported in status. */
    void flush(Completion completion);
    /** Deletes committed evidence after draining admitted writes. Live history and capture are unchanged. */
    void deleteRun(String id, Completion completion);
    void deleteAllRuns(Completion completion);
}
