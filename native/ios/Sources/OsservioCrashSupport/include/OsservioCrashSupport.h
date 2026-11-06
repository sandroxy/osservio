#ifndef OSSERVIO_CRASH_SUPPORT_H
#define OSSERVIO_CRASH_SUPPORT_H
#include <stdbool.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// Normal execution only. A zero duration leaves only the baseline permission.
void OsservioCrashCaptureUpdate(bool baseline, uint64_t detailedRemainingMillis);
// Async-signal-safe: one lock-free load and the platform uptime clock.
bool OsservioCrashCaptureAllowsRecording(void);

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
