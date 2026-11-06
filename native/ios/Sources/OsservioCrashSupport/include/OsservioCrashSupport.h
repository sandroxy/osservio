#ifndef OSSERVIO_CRASH_SUPPORT_H
#define OSSERVIO_CRASH_SUPPORT_H
#include <stdbool.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// Normal execution only. Absolute uptime milliseconds in the mach_absolute_time
// domain (the same as DispatchTime uptimeNanoseconds). Zero leaves baseline only.
void OsservioCrashCaptureUpdate(bool baseline, uint64_t detailedDeadlineUptimeMillis);
// Async-signal-safe: one lock-free load and the platform uptime clock.
bool OsservioCrashCaptureAllowsRecording(void);

// Async-signal-safe fixed-width fallback for unknown signal codes. The caller
// provides 13 bytes, enough for '#' + signed Int32 decimal + NUL. No libc formatter.
void OsservioCrashFormatSignalNumber(int32_t value, char output[13]);

// A process-lifetime admission budget; unloading an image does not renew it.
bool OsservioCrashAdmitImage(uint32_t loadCommandBytes, uint32_t loadCommandCount);
// Async-signal-safe. Bit 0 means image admission excluded at least one image.
uint8_t OsservioCrashImageFlags(void);

typedef enum {
    OsservioCrashHandlersAvailable = 0,
    OsservioCrashSignalHandlerPresent,
    OsservioCrashExceptionHandlerPresent,
    OsservioCrashMachHandlerPresent,
    OsservioCrashDebuggerPresent,
    OsservioCrashHandlerQueryFailed
} OsservioCrashHandlerAvailability;

// Normal execution only. This is a point-in-time preflight, not ownership of
// handlers another SDK might install later or on another thread.
OsservioCrashHandlerAvailability OsservioCrashInspectHandlers(void);
#ifdef __cplusplus
}
#endif
#endif
