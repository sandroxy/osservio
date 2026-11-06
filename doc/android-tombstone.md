# Android tombstone evidence

`AndroidTombstone` is a reviewed native Core value for address evidence from an
Android system tombstone. Runtime contains its internal, bounded protobuf
decoder. The value is not currently an incident attachment or portable export
field. `SystemDiagnostics` still returns the exit facts described in
[system diagnostics](system-diagnostics.md); constructing this value does not
install handlers, query OS history or record a crash.

## Evidence

The model preserves the AOSP architecture code, numeric signal and signed code,
faulting OS thread ID, optional fault address, and at most 128 ordered frames
from that thread. Each frame retains its absolute program counter, ELF-relative
program counter and optional lowercase ELF build ID of at most 128 hex digits.
Unknown nonnegative architecture codes remain numeric. ELF build IDs are not
Mach-O UUIDs and are not inferred from library paths. The relative PC is the
unwinder's reported value, not a file offset; no second address adjustment is
applied. Matching symbol files remain necessary for offline symbolication.

Addresses retain unsigned 64-bit precision: canonical decimal strings on Java,
`UInt64` on Swift. Signal codes remain signed 32-bit integers. A missing fault
address differs from address zero. No reported stack means an empty frame
array. Reaching 128 frames exposes a count limit; fewer frames do not establish
that unwinding was complete. No synthetic report identity or current-build
metadata is added. Failure time and process association belong to the system
exit record, not to a later read or to the current host process.

Neither platform's model accepts raw paths, function names, thread names,
registers, memory dumps, abort messages, command lines, arbitrary diagnostic
text or process-state summaries. The shared model does not imply Android
collection on iOS.

## Decoding boundary

The internal decoder accepts at most 1,048,576 input bytes, visits at most
32,768 field tags across its passes, and examines at most 1,024 thread-map
entries. These are admission limits, not measured latency or peak-memory
guarantees. Input is owned by the worker and is never exposed by the resulting
value. No input string is constructed except a bounded, validated build ID.

The decoder first reads process/thread/signal facts, checks the expected PID,
then locates the faulting thread independently of protobuf field order. A
missing thread entry leaves frames unavailable. A conflicting thread ID,
duplicate selected singular field, malformed selected value or invalid wire
structure rejects the evidence. Unknown fields are skipped. Ignored message
payloads and frames beyond the retention cap are not recursively validated or
decoded. Proto3's omitted scalar defaults are respected, including architecture
ARM32 and zero frame addresses; missing required process/thread/signal facts
cannot become a successful report.

The source schema is AOSP's
[tombstone protobuf](https://android.googlesource.com/platform/system/core/+/545d2487e38192a2ce25040897ced877cf6b4f53/debuggerd/proto/tombstone.proto).
The wire reader is a reviewed
[Epitaph subset](../native/android/vendor/epitaph/README.md), with retained
upstream regression tests. Native restoration tests use the same field-bound
fixtures. Runtime tests additionally cover real upstream crash bytes, unknown
fields, privacy exclusions, unsigned addresses, overflows, malformed lengths,
thread association, corruption and work caps. They do not establish real OS
delivery, fatal-handler behavior, physical-device performance or symbolication.
