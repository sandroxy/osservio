# Crash backend verification

The private backend is documented in
[its source directory](../../native/ios/Vendor/PLCrashReporter/README.md).
No public fatal-recording API or consumer integration is established by these
checks.

From the repository root, run the non-fatal package tests:

```sh
swift test --filter CrashBackendTests -Xswiftc -warnings-as-errors
swift test --filter CrashCapturePermissionTests -Xswiftc -warnings-as-errors
```

They exercise permission changes and expiry, the actual address-only live report
format, privacy exclusions, limits and incomplete decoding. They install no
crash handler in the test runner. Use the iOS package test scheme with
`-only-testing:OsservioCrashBackendTests` and `-only-testing:OsservioCrashTests`
for platform validation as described in
[Development](../../DEVELOPMENT.md).

The internal permission bridge checks Core lifecycle updates, absolute expiry
without polling, exclusive ownership, failed binding, object lifetime, and
revocation on release. Core's `CapturePermissionTests` separately checks initial
delivery, fallback changes during detail, binding contention, and callback reentry.
These checks do not install a crash handler or exercise report persistence.

On macOS with Xcode command-line tools, run:

```sh
python3 testing/crash-backend/verify.py
```

The script builds the internal backend and support targets separately, checks
their exported symbol prefixes, and links a small Objective-C process fixture
against those object files. Each case uses a new child process. Fatal cases
deliberately raise SIGABRT, with core dumps disabled for those children only.
Reports and a JSON receipt remain in an isolated, ignored `.work/crash-backend`
directory. The script never launches, resets, or removes a consumer application.

Checks cover default-off, explicit stop, detailed expiry and baseline fallback,
next-process decoding, UTF-8 metadata boundaries, image admission limits, private
file permissions, existing-file and symlink preservation, and rejection of
conflicting signal/exception/Mach handlers. A small fatal byte budget produces
an incomplete file whose rejection is verified separately. The adapted upstream
file-writer regression checks both exact capacity and integer overflow.
The decoder regression also exercises generic message initialization with
non-zero allocator memory and safe cleanup when no unknown fields are present.
The script then links a Swift decoder probe and reads an actual prior-process
fatal report into Core's typed evidence, checking that signal and stack/image
counts agree with the underlying report. No new report identity is generated.

`CrashReportDecoderTests` uses live encodings with an explicitly changed
classification bit for deterministic mapping checks; those synthetic fixtures
do not prove fatal delivery. It separately rejects live reports, unknown profile
revisions/flags, malformed input, and valid protobuf messages that introduce
forbidden fields. The isolated-process check supplies the real fatal-file path.

These checks do not prove iOS fatal delivery, physical-device behavior, performance
budgets, complete stack recovery, or coexistence with another installed reporter.
