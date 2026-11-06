# Native signal evidence

Core's `AppleSignalCrash` represents reviewed address-only evidence from an Apple
BSD fatal-signal recorder. The Swift and Java models share validation rules;
the Java model does not provide an Android crash recorder. The internal iOS
decoder maps the private backend's byte format into this model during normal
execution on a later launch. It never runs inside a fatal handler.

`SystemDiagnostic.appleSignal` wraps these facts for the incident archive,
capture-gated recovery, native browsing and portable export. The optional iOS
`OsservioCrash` product provides explicit installation and pending-file recovery. OS-owned
[system diagnostics](system-diagnostics.md) remain separate evidence; the same
failure can appear through more than one source without proving a shared identity.

## Installation and ownership

`CrashReports.open(directory:completion:)` opens a dedicated app-private directory
asynchronously. Retain the returned source to read previous-process reports through
`SystemDiagnosticProvider`, or pass it to `SystemDiagnosticRecovery` for capture-gated
incident persistence. Opening and reading do not install handlers or enable capture.
Read and recover pending evidence before installing a recorder, so all four old
slots can be processed before one is reserved for the new process.

`CrashRecorder.install(reports:captureController:completion:)` explicitly installs
the BSD signal backend on the source's worker. It requires `SystemLogClock` and
exclusive crash-handler ownership. Baseline or unexpired detailed capture permits
new fatal files; off prevents new writes. The absolute detailed deadline remains
effective without polling Core. A write admitted before stop may finish.

The installer reports conflicts with existing BSD handlers, an Objective-C
exception handler, relevant task/installation-thread Mach exception ports, or an
attached debugger. Failed inspection also rejects installation. It does not inspect
every thread or detect reporters installed later. A conflict does not prevent
reading old reports and does not reserve a new slot. There is no automatic handler
replacement, coexistence claim or fallback recorder.

Installation is process-long, including while capture is off or the inspector is
hidden. There is no uninstall operation. Once the backend is entered, its source,
directory lease, Core permission binding and handler context are retained until
process exit. A partially failed installation stays permanently disarmed; later
Core changes cannot activate it, and retry reports `restartRequired`. A successful
second install reports `alreadyInstalled`. The loader cache remains installed
while capture is off; off is not a zero-overhead guarantee.

Opening has one pending operation process-wide. Each source accepts one pending
read, and the installer admits one process-wide installation attempt at a time.
Rejections may complete immediately; accepted file work and installation complete
on utility workers. Callbacks run without SDK locks, and UI updates must dispatch
to the main actor. Releasing an uninstalled source releases its directory lease;
releasing an installed source does not uninstall or disable the pinned recorder.

## Pending-file retention

The private directory uses an exact format marker, an exclusive file lease and
four fixed report slots. Reports are at most 65,536 bytes each, so report content
is bounded to 262,144 bytes, excluding filesystem metadata. A recorder reserves
one slot; at most three prior reports then remain. Reservation selects an empty
slot or evicts the oldest file, with explicit excluded-count and capacity flags.
Opening alone does not reserve or evict a fourth report for capacity.

At open, validated files older than seven days by modification time are removed.
This is a startup cleanup rule, not a running timer; wall-clock changes can affect
the age window. Reads do not delete files or acknowledge archive delivery. Repeated
reads preserve original evidence and use the archive's bounded acknowledgements.
Malformed bounded reports contribute a rejected count. Unknown directory contents,
unsupported markers, insecure files, symlinks, hard links and oversized files fail
explicitly instead of resetting the directory. All slots are validated before
retention deletes any report. Do not share this directory with host data or other SDKs.

New directories and reports use `0700` and `0600`; the directory is excluded from
backup. On iOS, newly created pending files inherit complete-until-first-user-
authentication protection. Access may fail before the first unlock after reboot.
The bundled privacy declaration covers app-container file metadata used for these
limits and uptime used for capture expiry; hosts retain responsibility for their
own privacy disclosures and data policy.

Incomplete writes, disk failure or a termination mechanism outside BSD coverage
can leave no usable report. This API does not capture SIGKILL, watchdog or OOM
termination, and does not recover the original Objective-C exception throw stack.
There is no automatic attachment of current-launch logs, bodies or breadcrumbs.

## Retained facts

| Field | Contract |
| --- | --- |
| Report identity | Original recorder UUID in lowercase, with separators; never a newly generated recovery ID |
| Timestamp | Original positive Unix time at whole-second precision, expressed in milliseconds |
| Signal | SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV, or SIGTRAP |
| Signal code | A reviewed symbolic code for that signal, or `#` followed by canonical signed 32-bit decimal |
| Fault address | Exact unsigned 64-bit value; zero is retained rather than interpreted as missing |
| Thread number | Optional unsigned 32-bit recorder enumeration index, not an OS thread ID |
| Frames | At most 128 instruction addresses, newest first; empty means unavailable |
| Images | At most 512 entries with base, size, optional Mach-O UUID and optional paired Mach CPU identifiers |
| Metadata | Allowlisted public app version/build, OS version and device model, at most 256 UTF-8 bytes each |
| Image-cache loss | A flag indicating that at least one loader-cache entry was excluded; no invented loss count |

An absent thread number requires an empty frame array. A present thread may still
have no recovered frames. Image UUIDs use 32 lowercase hexadecimal digits without
separators. A zero image size means no usable address extent; nonzero ranges may
not overflow the unsigned 64-bit address space. CPU type and subtype are either
both available or both absent. The Java model represents unsigned 64-bit values
as canonical decimal strings to preserve their full range.

Reaching a frame or image count cap indicates possible incompleteness. A shorter
stack is also best effort; it is not proof of complete unwinding. Address stacks
need matching image UUIDs and symbol files for source-level interpretation. The
model does not guess symbols, exception causes, image paths, or missing frames.

Metadata comes from the recorded process, never from the recovery launch. The
backend omits oversized or NUL-containing metadata instead of truncating it;
the model rejects truncated metadata and embedded NULs. A missing or invalid
original timestamp is rejected rather than replaced with recovery time.
Neither the report UUID nor a nearby timestamp establishes an application session
or permits attaching the current launch's breadcrumbs as historical evidence.

## Decoding boundary

The [private backend profile](../native/ios/Vendor/PLCrashReporter/README.md)
allows at most 65,536 encoded bytes. The iOS decoder requires report information,
a fatal rather than user-requested report, the exact private profile marker and
known flags. It rejects process information, exception text, Mach exception data,
multiple recorded threads, registers, symbol text and nonempty image paths.
Valid protobuf syntax alone does not satisfy this profile.

`AppleSignalCrash.restore` validates sanitized facts without changing identity,
precision or completeness. No raw memory, arbitrary custom data, log bodies or
current environment can enter through this contract. Lists and metadata are
immutable snapshots. This native value contract is independent of the portable
incident-document revision and private SQLite schema.

## Archive and inspection

Wrap a validated `AppleSignalCrash` in `SystemDiagnostic.appleSignal` and pass it
to `IncidentSnapshot.captureDiagnostic`, or to an `appleSignal` source batch for
the existing [recovery port](system-diagnostics.md#bounded-batch-recovery).
The enclosing snapshot records recovery observation time. Both diagnostic interval
endpoints retain the original recorder timestamp; neither is taken from the
current launch. No current environment or breadcrumb attachment is added.

The incident store retains signal reports under its existing system-report count,
age and byte budgets. Private database revision 5 adds normalized header, ordered
frame and ordered image tables. Exact unsigned values use canonical decimal text,
including values beyond SQLite's signed integer range. Reads check expected counts,
contiguous positions and source-specific invariants. Missing or malformed facts
fail the read instead of being silently repaired. Child writes, retention changes
and recovery acknowledgements share the incident transaction; deletion cascades
to the complete address evidence. Earlier schemas upgrade without replacing
reports or acknowledgements.

Recovery fingerprints include every retained signal fact, including the original
report UUID, address order and completeness flag. Existing OS-source fingerprints
remain unchanged. A repeated batch can therefore be acknowledged after reopen or
explicit incident deletion. This is source-material deduplication, not correlation
with an Android exit or MetricKit report.

The native inspector shows original time, source, signal/code, exact hexadecimal
addresses, historical metadata, missing fields and recording limits. Separate
lists format frames and images as their rows become visible. Copying a row or the
whole list is explicit. Zero image size is displayed as unavailable, while a zero
fault/instruction address remains an actual recorded value. The summary counts
are not a complete stack dump; use the address lists or export for that evidence.

[Portable revision 4](../protocol/README.md) carries the complete bounded facts
in `systemDiagnostic.appleSignal`, with unsigned 64-bit values encoded as decimal
strings. Revision-1, revision-2 and revision-3 documents remain independently validated by the
development conformance checker. There is no native document importer or in-app
symbolication service.

Shared positive/negative fixtures and independent-process decoding checks are
described in [Testing](../testing/README.md) and
[crash backend verification](../testing/crash-backend/README.md).
