# Android tombstone evidence

`AndroidTombstone` is a reviewed native Core value for address evidence from an
Android system tombstone. On Android 12/API 31 and later, Runtime's
`SystemDiagnostics` reads native-crash traces associated with eligible main-process
exit records. It decodes reviewed fields into `AndroidExit.tombstone`, alongside
the original exit facts described in [system diagnostics](system-diagnostics.md).
The incident archive, native viewers and portable exporter preserve this evidence.
No signal or Java exception handler is installed. The OS produces and owns the
raw tombstone; Osservio does not persist or export it.

Android 11/API 30 supplies exit facts without native tombstone access. Earlier
versions cannot use this history provider. Java exception stacks and raw ANR
traces are not collected. A trace attached to a non-native exit is not decoded:
Android may retain an earlier ANR trace even when the eventual exit had another
reason. System history and its separate trace buffer may be overwritten or
unavailable, so a retained exit record does not guarantee a readable stack.

## Read states

Each Android exit carries `tombstoneState`, independently of its exit classification:

| State | Meaning |
| --- | --- |
| `notRequested` | This value or legacy record has no trace-read result |
| `notApplicable` | The system reason is not `REASON_CRASH_NATIVE` |
| `unsupported` | The exit is native, but the Android version lacks this trace API |
| `unavailable` | The OS supplied no stream, or opening/reading/closing it failed |
| `invalid` | Selected evidence is malformed, ambiguous or mismatches the exit PID |
| `tooLarge` | The input exceeds the per-report byte cap |
| `limitReached` | A query byte budget or decoder traversal budget was reached |
| `available` | Reviewed trace evidence is present; its frames may still be unavailable |

Only `available` has a non-null tombstone. Only a non-native exit uses
`notApplicable`. A trace failure preserves valid exit facts and its explicit
state instead of discarding the entire exit record. The source API supplies
the record/trace association; timestamps or similar symptoms are not used to
merge unrelated crashes.

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

One worker query admits at most 8,388,608 stream bytes across at most 32 inspected
exit records. A reusable 1,048,577-byte buffer permits one extra byte to detect
an oversized report; it is cleared after each read. A throwing read retains its
requested byte reservation because it may have consumed bytes before failing.
When the query budget is exhausted, no additional stream is opened. Streams are
closed on success and failure. Missing EOF at a budget boundary is not accepted
as a complete report. I/O occurs on the provider worker, never a capture hook.

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

## Archive and viewing

The existing capture-gated recovery workflow stores this reviewed value with
the exit record. Private database revision 6 adds trace state and ordered frames;
revision-5 records retain their identities and evidence, with native traces
marked `notRequested`. Failed upgrades roll back without resetting the archive.

Recovery fingerprints keep the original summary material compatible. Trace-read
states are operational results, not distinct failures. A newly available trace
can add a richer immutable report after a previously saved summary; it does not
rewrite the earlier record. A richer report also acknowledges the summary, so
later trace eviction does not add a degraded duplicate. The existing bounded
acknowledgement window still applies; report counts are not unique-crash counts.

Native diagnostic views show state, signal, architecture, thread ID and fault
address without querying current device context. The address list exposes
absolute and ELF-relative PCs with build IDs; row and whole-list copying are
explicit. Missing stacks have no active address-list navigation. Portable
[revision 4](../protocol/README.md) carries the same facts and states. Native
archive tests cover exact restoration, source/state invariants, corruption,
transactional recovery, multiplicity, deletion acknowledgements and additive
migration. These checks do not replace a real next-launch recovery test.
