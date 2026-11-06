# Incident file export

`IncidentExporter` is the Core port for explicit asynchronous file preparation.
The optional Export module implements it with `IncidentFileExporter`, producing
one temporary ZIP from a frozen sanitized `IncidentSnapshot`. It does not query
live histories, start capture, modify a saved report, upload anything, or open a
share sheet. Native inspector sharing is not connected yet.

The ZIP contains exactly `incident.json` and `payloads.json`, using the
[portable document format](../protocol/README.md). It contains only evidence
already present in the supplied snapshot, including its body inclusion states.
Encoding cannot recover omitted or evicted data. Public descriptions and log
text are not automatically scrubbed for secrets at export time.

## Integration and ownership

Android:

```java
IncidentFileExporter exporter = new IncidentFileExporter(applicationContext);
IncidentExporter.Task task = exporter.prepare(snapshot, (file, error) -> {
    // On success: file.file and file.byteCount describe a complete temporary ZIP.
    // Dispatch to the UI thread before presenting anything. No file has been sent.
});
// task is null for a rejected request; otherwise task.cancel() requests cancellation.
```

iOS:

```swift
let exporter = try IncidentFileExporter(directory: privateExportCacheDirectory)
let task = exporter.prepare(snapshot) { result in
    // On success: result contains a temporary file URL and byte count.
    // Dispatch to the main actor before presenting anything. No file has been sent.
}
// task is nil for a rejected request; otherwise task?.cancel() requests cancellation.
```

Retain the exporter at application scope and use a dedicated directory for it.
Android uses `Context.getCacheDir()/osservio/exports`. On iOS, supply a private
directory below the app's Caches directory. It is excluded from backup and uses
`completeUntilFirstUserAuthentication` file protection. Directories must be owned
by the app user and inaccessible to other users. The SDK creates private files,
rejects symbolic links and unexpected file types, and checks directory and lease
identities before cleanup. It never clears an unrelated directory to recover.

One exporter owns a directory through an exclusive lease. Another instance or
process receives `busy` while that lease remains held. Initialization performs
no filesystem work; the first admitted preparation opens the directory on the
private worker. Do not put host files or other SDK data in this directory.
Android's minimum supported API uses array-based directory listing; the dedicated
directory is bounded by SDK ownership and file creation, not a general-purpose
scanner for arbitrary host directories.

`close(completion:)` / `close(completion)` cancels admitted work and releases the
lease asynchronously. Repeated close after completion succeeds; a concurrent
close in progress reports `busy`. No caller waits for disk I/O. Closing does
not remove completed files, stop capture, or close the incident archive. Android
owners must explicitly close the exporter when disposing of it.

## Admission, cancellation, and failure

Only one preparation is admitted at a time; excess work receives `busy` rather
than entering an unbounded queue. A rejected request returns no task. Callbacks
run exactly once, on any thread, including immediate rejection. They run without
admission locks and must return promptly. Retaining returned files and allocating
multiple exporters remain host responsibilities.

Cancellation is best effort until completion is finalized. It is checked before
and after encoding and between ZIP chunks. A platform JSON encode or filesystem
call already in progress is not interrupted. Cancellation after completion does
not retract an exposed file or stop a receiver that is already reading it.
Close orders its completion after the admitted preparation's callback.

ZIP generation uses Android's system ZIP APIs or pinned ZIPFoundation on iOS.
After writing and closing the archive, the exporter reopens it and verifies
entry names, decoded lengths, contents, and CRCs. Only then is the partial file
renamed and returned. This is integrity checking for a temporary export, not a
guarantee against power loss or a runtime ZIP-import feature.

Failures use bounded categories: `busy`, `closed`, `cancelled`, `tooLarge`,
`storageFull`, `invalidRecord`, or `unavailable` (uppercase enum names on Android).
Raw exception messages, paths, and body contents are not included. An error does
not silently fall back to a smaller or less complete report. No automatic retry
occurs. A failed cleanup can leave a recognized temporary file for the next
preparation to remove; it is not returned to the caller.

## Temporary retention

| Limit | Default | Accepted range |
| --- | --- | --- |
| Completed ZIP files | 4 | 1–16 |
| Completed ZIP bytes | 32 MiB | 1–128 MiB |
| Age since file modification | 24 hours | 1 minute–7 days |
| Counted snapshot text | 2 MiB | Fixed |
| Combined uncompressed JSON | 16 MiB | Fixed |
| Single completed ZIP | 17 MiB | Fixed |
| Concurrent preparations | 1 | Fixed per exporter |

Set cache limits through `IncidentExportOptions`. The counted text definition
is shared with incident snapshots; it excludes structural object overhead.
The JSON encoders buffer their output. These limits are not measured peak-memory,
latency, or energy guarantees. One additional partial ZIP can occupy up to
17 MiB during preparation, beyond the completed-cache budget.

Cleanup runs at preparation and before committing a new file. It removes
recognized abandoned partials and expired files, then evicts the oldest complete
files until count and byte limits fit. A file larger than the cache budget is
rejected before evicting prior complete exports. Cleanup validates the directory
contents before removing anything; unknown files are preserved and cause failure.
Wall-clock changes can delay or accelerate age cleanup. No idle cleanup timer
runs, so an unused cache can retain expired files until the next preparation.

The OS may purge cache files. A new export may evict an older one even before its
age limit. Do not delete a file as soon as a share sheet closes: another app may
still be reading it. Copy an export to a host-owned durable destination when it
must remain available independently. The SDK does not track external readers or
promise a fixed minimum availability period. Deleting an export never deletes
the separately stored incident or live history.
