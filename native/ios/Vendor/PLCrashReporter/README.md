# Private crash-recording backend

This directory vendors the recording and decoding implementation from
[PLCrashReporter 1.12.2](https://github.com/microsoft/plcrashreporter/tree/0254f941c646b1ed17b243654723d0f071e990d0),
commit `0254f941c646b1ed17b243654723d0f071e990d0`. It is an internal target,
not an exported SDK product. Existing Osservio products do not install or link
this backend. The capture permission and handler preflight are Osservio-owned
code in `Sources/OsservioCrashSupport`.

The bundled protobuf-c runtime is upgraded separately to
[1.5.2](https://github.com/protobuf-c/protobuf-c/tree/4719fdd7760624388c2c5b9d6759eb6a47490626),
commit `4719fdd7760624388c2c5b9d6759eb6a47490626`. This includes upstream decoder
fixes made after PLCrashReporter's embedded 1.4.0. Generated message descriptors
remain compatible with this runtime and are preserved from PLCrashReporter.

## Recording profile

The build supports BSD fatal signals only. Mach exception handling is compiled
out; Objective-C exception hooks and symbolication are rejected at installation.
Only the faulting thread is recorded, with at most 128 address frames and 512
binary-image descriptions. Image descriptions retain addresses, sizes, UUIDs
and CPU identifiers, with empty names. Registers, executable paths, parent
process information, exception text and application-supplied custom data are
excluded before encoding. The backend does not dump raw stack memory.

UTF-8 application and system metadata is limited to 256 bytes per value during
normal execution. Oversized values and values containing U+0000 are omitted,
not silently truncated. The
private report's custom-data field contains the UTF-8 marker `OsservioCrash/1`
followed by one flags byte. Bit 0 indicates that at least one image was excluded
by loader-cache admission; other bits are reserved. This private profile is not
the portable incident-document protocol.

The loader cache admits at most 1,024 images over the process lifetime, with at
most 65,536 load-command bytes and 4,096 commands per admission. It uses a constant
image name and does not cache executable paths. Unloading an image does not
renew the admission budget. These bounds limit cache entries and input work;
they are not a measured peak-memory or latency guarantee. Stack unwinding remains
best effort, and a report that reaches a frame or image limit may be incomplete.

The configured report limit must be positive and at most 65,536 bytes. Both live
test reports and fatal reports use that limit. Files are created exclusively
with mode `0600`, without following symlinks or overwriting unrecovered files.
New directories use `0700`. A failed or interrupted write can leave an incomplete
file; recovery must validate it before treating it as usable evidence.

## Ownership and lifecycle

Installation checks existing BSD signal handlers, Objective-C exception handlers,
relevant task/current-thread Mach exception ports, and an attached debugger.
An unavailable query or detected conflict rejects installation. OS postmortem,
resource and corpse notifications are outside this check and are preserved.
This is a point-in-time check, not proof of coexistence: a host must establish
exclusive crash-handler ownership and must not install another reporter later.
Handlers on other threads are not exhaustively inspected.

Installation and the image cache are process scoped. Stopping capture closes
an atomic write permission; it does not uninstall the backend or remove its
loader bookkeeping. Detailed permission expires using the uptime clock without
calling Swift, Objective-C, a database, or normal ingestion from the signal
handler. A fatal write already admitted may finish after permission closes.
The internal `OsservioCrash` module mirrors Core changes into this permission
under Core's admission lock. It uses the absolute `SystemLogClock` deadline, so
delayed delivery cannot extend a session. Injected Swift clocks are rejected
because the signal handler cannot call them. There is one permission owner per
process; failed secondary bindings cannot change an existing owner's permission.
Releasing that owner revokes permission. This internal connection does not yet
provide a public recorder or install a backend in consumer applications.

Core's clock and the backend use the same Mach uptime domain, which excludes
system sleep; see [DispatchTime](https://developer.apple.com/documentation/dispatch/dispatchtime).
Permission expiry therefore follows the same clock as ordinary detailed capture.

This backend cannot report SIGKILL, watchdog termination, or an OOM kill, and it
does not provide an original Objective-C exception throw stack. BSD handler
coverage also has platform and termination-path limitations inherited from
PLCrashReporter. Address stacks require matching binary UUIDs and symbols for
source-level interpretation. OS diagnostic sources remain separate evidence.

## Local changes and maintenance

[UPSTREAM.json](UPSTREAM.json) records original and vendored PLCrashReporter file hashes;
[changes.patch](changes.patch) records changes to upstream files. Most changed
files add the upstream namespace map consistently to each translation unit or
use local headers instead of a host's separate `CrashReporter` framework.
The `shared_handler_context` C++ symbol is also namespaced.
Upstream whitespace is preserved in source, retained tests and the patch; the
local Git attributes exclude those files from whitespace diagnostics.
The replaced protobuf-c runtime has its own
[source record](PROTOBUF_C_UPSTREAM.json) and [local patch](protobuf-c-changes.patch).

Other changes implement the recording profile, pre-write permission, handler
preflight, loader admission, private/exclusive file creation, and fatal byte
limit. The process-wide installation mutex is made static, and the bounded file
writer rejects overflowing lengths before addition. Unwinding and protobuf
encoding remain upstream implementations.

The Objective-C decoder rejects input outside 8–65,536 bytes before invoking
protobuf-c. This target does not use protobuf-c's generic packing APIs: the crash
writer uses the separate upstream async encoder. The embedded protobuf-c file
alone disables Clang's 64-to-32-bit implicit-narrowing diagnostic for its reviewed
wire-length conversions; other diagnostics remain enabled. This keeps the
generic upstream implementation intact behind the bounded decoder entry point.

For an update, compare against the exact recorded commit, review upstream changes
in the touched paths, reapply the local changes, refresh both hashes and patch,
and run the [backend checks](../../../../testing/crash-backend/README.md).
Keep symbol isolation, privacy, actual fatal writes, stop/expiry, incomplete
recovery, ownership conflicts and file-budget behavior under verification.

`UpstreamTests` retains the original async-file and encoding regression sources
and their supporting declarations. They are reference material, not an executed
upstream suite. Osservio's process fixture adapts the upstream file-limit test
and adds overflow and preservation cases; Swift tests exercise live reports and
incomplete decoding on macOS and iOS.

## Licenses

Keep [the upstream license](LICENSE), original source headers,
[Apache 2.0](Apache-2.0.txt) and [the embedded protobuf-c notices](protobuf-c-LICENSE.txt)
with redistributed code and artifacts. The upstream root license describes the
main code under MIT-style terms and `PLCrashLogWriterEncoding.c` under Apache 2.0.
The replacement protobuf-c runtime carries its own BSD 2-Clause license and
source-header notices, retained separately as well. The added Osservio support
code does not acquire an upstream license merely by linking this backend.
