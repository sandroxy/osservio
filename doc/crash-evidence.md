# Native signal evidence

Core's `AppleSignalCrash` represents reviewed address-only evidence from an Apple
BSD fatal-signal recorder. The Swift and Java models share validation rules;
the Java model does not provide an Android crash recorder. The internal iOS
decoder maps the private backend's byte format into this model during normal
execution on a later launch. It never runs inside a fatal handler.

`SystemDiagnostic.appleSignal` wraps these facts for the incident archive,
capture-gated recovery, native browsing and portable export. Public recorder
installation and pending-file recovery are not connected yet. OS-owned
[system diagnostics](system-diagnostics.md) remain separate evidence; the same
failure can appear through more than one source without proving a shared identity.

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

[Portable revision 3](../protocol/README.md) carries the complete bounded facts
in `systemDiagnostic.appleSignal`, with unsigned 64-bit values encoded as decimal
strings. Revision-1 and revision-2 documents remain independently validated by the
development conformance checker. There is no native document importer or in-app
symbolication service.

Shared positive/negative fixtures and independent-process decoding checks are
described in [Testing](../testing/README.md) and
[crash backend verification](../testing/crash-backend/README.md).
